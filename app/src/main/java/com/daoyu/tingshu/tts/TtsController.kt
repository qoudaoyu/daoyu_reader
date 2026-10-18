package com.daoyu.tingshu.tts

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import com.daoyu.tingshu.App
import com.daoyu.tingshu.data.Book
import com.daoyu.tingshu.data.Chapter
import com.daoyu.tingshu.data.TtsSettings
import com.daoyu.tingshu.data.TtsUsage
import com.daoyu.tingshu.drama.DoubaoVoices
import com.daoyu.tingshu.drama.Roles
import com.daoyu.tingshu.drama.VoicePools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

data class TtsState(
    val active: Boolean = false,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val bookId: Long = -1,
    val bookTitle: String = "",
    val chapter: Int = 0,
    val chapterTitle: String = "",
    val segStart: Int = 0,
    val segEnd: Int = 0,
    val engine: String = Engines.EDGE,
    val sleepChoice: Int = 0,          // 0 off, minutes, -1 = end of chapter
    val sleepLabel: String = "",
    val drama: Boolean = false,        // multi-role mode
    val status: String = "",           // e.g. "正在分析本章角色…"
    val speaker: String? = null,       // who is speaking the current segment (multi-role)
)

/**
 * Drives listening: splits chapters into segments, synthesizes ahead with the chosen engine
 * (falling back when an engine fails), caches audio, and plays segments back to back.
 */
class TtsController(private val context: Context) {
    private val app get() = App.instance
    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    // never let a listening bug take the whole app down; report it instead
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        android.util.Log.e("TtsController", "listening error", e)
        _messages.tryEmit("听书出错：${e.message ?: e.javaClass.simpleName}")
    })

    private val systemTts by lazy { SystemTts(context) }
    private val cacheDir = File(context.cacheDir, "tts").apply { mkdirs() }

    private var book: Book? = null
    private var chapters: List<Chapter> = emptyList()
    private val queue = ArrayList<Seg>()
    private var playIndex = 0
    private val results = HashMap<Int, CompletableDeferred<File?>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var producer: Job? = null
    private var player: Job? = null
    private var sleepJob: Job? = null
    private var sessionEngine = Engines.EDGE
    private var sessionDrama = false
    private val director get() = app.director
    private var paused = false
    private var mp: MediaPlayer? = null
    /** Test hook: every file handed to the player, in playback order. */
    internal var onPlay: ((Seg, File) -> Unit)? = null
    private val warned = HashSet<String>()

    // ---------------------------------------------------------------- public API

    fun start(b: Book, chapter: Int, offset: Int) {
        scope.launch {
            stopInternal(keepActive = true)
            book = b
            chapters = withContext(Dispatchers.IO) { app.db.chapters().list(b.id) }
            if (chapters.isEmpty()) return@launch
            sessionEngine = b.ttsEngine ?: settings().engine
            sessionDrama = b.dramaMode
            warned.clear()
            director.notifyGrokDown = { notifyOnce("grokDown", "Grok 连不上（可能没开代理），暂用千问 Max 分析角色") }
            queue.clear()
            _state.update {
                it.copy(active = true, playing = true, loading = true, bookId = b.id, bookTitle = b.title, engine = sessionEngine, drama = sessionDrama)
            }
            ContextCompat.startForegroundService(context, Intent(context, TtsService::class.java))
            appendChapter(chapter.coerceIn(chapters.indices), offset)
            playIndex = 0
            paused = false
            requestFocus()
            trimCache()
            launchPipeline()
        }
    }

    fun togglePause() = if (_state.value.playing) pause() else resume()

    fun pause() {
        if (!_state.value.active) return
        paused = true
        mp?.takeIf { it.isPlaying }?.pause()
        _state.update { it.copy(playing = false) }
    }

    fun resume() {
        if (!_state.value.active) return
        paused = false
        requestFocus()
        mp?.start()
        _state.update { it.copy(playing = true) }
    }

    fun stop() = scope.launch { stopInternal(keepActive = false) }

    fun next() = jump(playIndex + 1)
    fun prev() = jump((playIndex - 1).coerceAtLeast(0))

    /** Voice / speed changed: re-synthesize from the current segment. */
    fun onSettingsChanged() {
        if (_state.value.active) jump(playIndex)
    }

    fun switchEngine(engine: String) {
        sessionEngine = engine
        warned.clear()
        _state.update { it.copy(engine = engine) }
        if (_state.value.active) jump(playIndex)
    }

    /** Switch single ↔ multi-role at any time; listening continues from the current sentence. */
    fun setDramaMode(bookId: Long, on: Boolean) {
        app.appScope.launch(Dispatchers.IO) { app.db.books().setDramaMode(bookId, on) }
        if (book?.id == bookId) book = book?.copy(dramaMode = on)
        if (_state.value.active && _state.value.bookId == bookId) {
            sessionDrama = on
            warned.clear()
            _state.update { it.copy(drama = on) }
            jump(playIndex)
        }
    }

    /** Index of the queued segment covering a text position, or -1. */
    private fun indexAt(chapter: Int, offset: Int) = queue.indexOfFirst { it.chapter == chapter && offset >= it.start && offset < it.end }

    /** After a speaker correction: rebuild from that line if it is in the listening queue. */
    fun refreshAt(chapter: Int, offset: Int) {
        if (!_state.value.active) return
        val i = indexAt(chapter, offset)
        if (i >= 0 && i >= playIndex) jump(playIndex) else if (i >= 0) jump(i)
    }

    /** Throw away the cached audio of the sentence at this position and play it again. Returns false if not queued. */
    fun resynthesizeAt(chapter: Int, offset: Int): Boolean {
        if (!_state.value.active) return false
        val i = indexAt(chapter, offset)
        if (i < 0) return false
        results[i]?.let { d -> if (d.isCompleted) runCatching { d.getCompleted()?.delete() } }
        cacheFilesFor(queue[i]).forEach { it.delete() }
        jump(i)
        return true
    }

    fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        _state.update { it.copy(sleepChoice = minutes, sleepLabel = if (minutes == -1) "本章结束后停止" else "") }
        if (minutes > 0) {
            val end = System.currentTimeMillis() + minutes * 60_000L
            sleepJob = scope.launch {
                while (isActive) {
                    val left = end - System.currentTimeMillis()
                    if (left <= 0) break
                    _state.update { it.copy(sleepLabel = "${(left / 60_000) + 1} 分钟后停止") }
                    delay(minOf(left, 20_000L))
                }
                pause()
                _state.update { it.copy(sleepChoice = 0, sleepLabel = "") }
            }
        }
    }

    // ---------------------------------------------------------------- pipeline

    private fun settings(): TtsSettings = app.settings.tts.value

    private fun maxLenFor(engine: String) = when (engine) {
        Engines.GEMINI -> 260
        Engines.COSY -> 180
        Engines.QWEN -> 200
        else -> 150
    }

    private suspend fun appendChapter(ch: Int, offset: Int) {
        val b = book ?: return
        val body = withContext(Dispatchers.IO) { app.storage.readChapter(b.id, ch) }
        val segs = if (sessionDrama) {
            // this chapter goes first (foreground); the next three are analysed in the background meanwhile
            val now = if (director.isCurrent(b.id, ch)) null else scope.async { director.ensureChapter(b.id, ch, urgent = true) }
            for (k in 1..3) if (ch + k < chapters.size) director.prefetch(b.id, ch + k)
            var roles = director.cached(b.id, ch)?.takeIf { director.isCurrent(b.id, ch) }
            if (roles == null) {
                val stale = director.cached(b.id, ch)
                _state.update { it.copy(status = "正在分析本章角色…") }
                roles = now?.await() ?: director.ensureChapter(b.id, ch) ?: stale
                _state.update { it.copy(status = "") }
                if (roles == null) notifyOnce("drama$ch", "本章角色分析失败（${director.lastError ?: "网络错误"}），本章用旁白朗读")
            }
            director.grokFallbackReason?.let { notifyOnce("grokFallback", "千问角色分析出错（$it），已改用 Grok 分析") }
            if (director.castBroken(b.id)) notifyOnce("castBroken", "本书角色表文件损坏，已另存为 cast.json.broken，角色会重新分配音色，可在角色表里调整")
            // characters met before 豆包 was switched on still need a voice
            if (director.usesDoubao()) runCatching { director.ensureDoubaoVoices(b.id) }
            withContext(Dispatchers.Default) { Segmenter.splitDrama(ch, body, offset, roles?.lines.orEmpty(), 200) }
        } else withContext(Dispatchers.Default) { Segmenter.split(ch, body, offset, maxLenFor(sessionEngine)) }
        // an empty chapter still needs a placeholder so we move on to the next one
        queue.addAll(segs)
        if (segs.isEmpty() && ch + 1 < chapters.size) appendChapter(ch + 1, 0)
    }

    private fun jump(index: Int) {
        if (!_state.value.active) return
        scope.launch {
            producer?.cancel(); player?.cancel()
            releasePlayer()
            results.values.forEach { it.cancel() }
            results.clear()
            if (index >= queue.size) {
                val last = queue.lastOrNull()
                if (last != null && last.chapter + 1 < chapters.size) appendChapter(last.chapter + 1, 0)
            }
            playIndex = index.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
            // re-split from here so a new engine gets segments sized for it
            queue.getOrNull(playIndex)?.let { seg ->
                val keep = queue.subList(0, playIndex).toList()
                queue.clear(); queue.addAll(keep)
                appendChapter(seg.chapter, seg.start)
            }
            paused = false
            _state.update { it.copy(playing = true) }
            launchPipeline()
        }
    }

    private fun deferredAt(i: Int) = results.getOrPut(i) { CompletableDeferred() }

    private fun launchPipeline() {
        producer = scope.launch {
            var n = playIndex
            // multi-role lines are short, so synthesize several at once to stay ahead of playback
            val workers = kotlinx.coroutines.sync.Semaphore(if (sessionDrama) 3 else 1)
            while (isActive) {
                // keep the queue topped up across chapter boundaries
                if (n >= queue.size - 1) {
                    val last = queue.lastOrNull()
                    if (last != null && last.chapter + 1 < chapters.size) appendChapter(last.chapter + 1, 0)
                }
                val ahead = if (sessionDrama) 8 else settings().prefetch.coerceIn(1, 8)
                if (n < queue.size && n <= playIndex + ahead) {
                    val d = deferredAt(n)
                    if (!d.isCompleted) {
                        val seg = queue[n]
                        workers.acquire()
                        launch {
                            try { d.complete(if (seg.drama) synthesizeDrama(seg) else synthesizeWithFallback(seg)) }
                            finally { workers.release() }
                        }
                    }
                    n++
                } else {
                    wake.receive()
                    if (n < playIndex) n = playIndex
                }
            }
        }
        player = scope.launch {
            while (isActive) {
                if (playIndex >= queue.size) {
                    _messages.tryEmit("全书已读完")
                    stopInternal(keepActive = false)
                    return@launch
                }
                val seg = queue[playIndex]
                val chTitle = chapters.getOrNull(seg.chapter)?.title.orEmpty()
                val d = deferredAt(playIndex)
                _state.update { it.copy(chapter = seg.chapter, chapterTitle = chTitle, segStart = seg.start, segEnd = seg.end, loading = !d.isCompleted, engine = sessionEngine, speaker = if (seg.drama) seg.role else null) }
                saveProgress(seg)
                val file = d.await()
                _state.update { it.copy(loading = false) }
                if (file == null) {
                    _messages.tryEmit(failures.remove(seg) ?: "所有语音引擎都不可用，已暂停")
                    pause()
                    // wait until the user resumes, then retry from this segment
                    while (paused && isActive) delay(300)
                    jump(playIndex)
                    return@launch
                }
                onPlay?.invoke(seg, file)
                if (!playFile(file)) {
                    // the file could not be played (and was deleted): make it again once rather than skip the sentence
                    val again = if (seg.drama) synthesizeDrama(seg) else synthesizeWithFallback(seg)
                    if (again != null) onPlay?.invoke(seg, again)
                    if (again == null || !playFile(again)) {
                        _messages.tryEmit(failures.remove(seg) ?: "这一句播放失败，已暂停，点播放重试")
                        pause()
                        while (paused && isActive) delay(300)
                        jump(playIndex)
                        return@launch
                    }
                }
                val nextSeg = queue.getOrNull(playIndex + 1)
                if (_state.value.sleepChoice == -1 && (nextSeg == null || nextSeg.chapter != seg.chapter)) {
                    playIndex++
                    pause()
                    _state.update { it.copy(sleepChoice = 0, sleepLabel = "") }
                    while (paused && isActive) delay(300)
                    wake.trySend(Unit)
                    continue
                }
                results.remove(playIndex - 2)
                playIndex++
                wake.trySend(Unit)
            }
        }
    }

    private fun engineOf(id: String): TtsEngine = when (id) {
        Engines.EDGE -> EdgeTts
        Engines.COSY -> CosyVoiceTts
        Engines.GEMINI -> GeminiTts
        Engines.QWEN -> QwenTts
        else -> systemTts
    }

    private fun fallbackOf(id: String): String? = when (id) {
        Engines.GEMINI -> settings().geminiFallback.takeIf { it != Engines.GEMINI } ?: Engines.EDGE
        Engines.COSY -> Engines.EDGE
        Engines.QWEN -> Engines.EDGE
        Engines.EDGE -> Engines.SYSTEM
        else -> null
    }

    private fun cacheFile(engine: TtsEngine, s: TtsSettings, text: String): File {
        val md = MessageDigest.getInstance("SHA-1").digest("${engine.id}|${engine.voiceKey(s)}|$text".toByteArray())
        return File(cacheDir, md.joinToString("") { "%02x".format(it) } + "." + engine.ext)
    }

    private suspend fun overLimit(engine: String, s: TtsSettings): Boolean {
        val limit = when (engine) { Engines.COSY -> s.cosyMonthlyLimit; Engines.GEMINI -> s.geminiMonthlyLimit; Engines.QWEN -> s.qwenMonthlyLimit; Engines.DOUBAO -> s.doubaoMonthlyLimit; else -> 0 }
        if (limit <= 0) return false
        val used = withContext(Dispatchers.IO) { app.db.usage().get(month(), engine)?.chars ?: 0 }
        return used >= limit * 10_000L
    }

    /** Returns an audio file, or null when every engine in the chain failed. */
    private suspend fun synthesizeWithFallback(seg: Seg): File? {
        var next: String? = sessionEngine
        while (next != null) {
            val engineId: String = next
            val s = settings()
            val engine = engineOf(engineId)
            val out = cacheFile(engine, s, seg.text)
            if (out.exists() && out.length() > 0) { out.setLastModified(System.currentTimeMillis()); return out }
            if (overLimit(engineId, s)) {
                notifyOnce("limit$engineId", "${Engines.label(engineId)} 已达本月用量上限，改用 ${Engines.label(fallbackOf(engineId) ?: "")}")
                next = fallbackOf(engineId); continue
            }
            var attempt = 0
            while (true) {
                try {
                    val tmp = File(out.path + ".part")
                    val info = withContext(Dispatchers.IO) { engine.synthesize(seg.text, s, tmp) }
                    tmp.renameTo(out)
                    recordUsage(engineId, seg.text, info)
                    if (engineId != sessionEngine) {
                        // stay on the working engine for the rest of this session
                        sessionEngine = engineId
                        _state.update { it.copy(engine = engineId) }
                    }
                    return out
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TtsException) {
                    if (!e.skipEngine && attempt < 2) { attempt++; delay(800L * attempt); continue }
                    val fb = fallbackOf(engineId)
                    notifyOnce("err$engineId", "${e.message}${if (fb != null) "，已切换到 ${Engines.label(fb)}" else ""}")
                    next = fb
                    break
                } catch (e: Exception) {
                    if (attempt < 2) { attempt++; delay(800L * attempt); continue }
                    val fb = fallbackOf(engineId)
                    notifyOnce("err$engineId", "${Engines.label(engineId)} 出错：${e.message ?: e.javaClass.simpleName}${if (fb != null) "，已切换到 ${Engines.label(fb)}" else ""}")
                    next = fb
                    break
                }
            }
        }
        return null
    }

    private fun hashName(key: String, ext: String): File {
        val md = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(cacheDir, md.joinToString("") { "%02x".format(it) } + "." + ext)
    }

    /**
     * Voices for one multi-role segment. [doubaoVoice] is non-null when 豆包 voices the characters; it is ""
     * while the character has no saved voice yet; for narration it is the narrator's 豆包 voice, if 豆包 narrates.
     * Otherwise narration and characters without a Qwen voice use Edge.
     */
    private data class DramaVoice(
        val role: String?,                  // canonical character name; null = narrator / passer-by
        val doubaoVoice: String?, val doubaoInstruct: String,
        val qwenVoice: String?, val instruct: String,
        val edgeVoice: String,
    )

    private fun dramaVoiceOf(seg: Seg): DramaVoice {
        val s = settings()
        val b = book!!
        val narrator = director.cast(b.id).value.narratorVoice ?: s.dramaNarratorVoice
        val m = seg.role?.let { director.resolve(b.id, it) }
        if (m == null && Roles.isPasserby(seg.role)) {
            // unnamed speakers get an ordinary voice of their gender, not the narrator's storytelling one
            val female = seg.role == Roles.PASSERBY_F
            val edge = if (female) "zh-CN-XiaoyiNeural" else "zh-CN-YunjianNeural"
            val db = if (director.usesDoubao() && !freeVoicesForTest) (if (female) DoubaoVoices.PASSERBY_F else DoubaoVoices.PASSERBY_M) else null
            return DramaVoice(seg.role, db, doubaoInstruction(seg), null, "", edge)
        }
        if (m == null) {
            val db = if (director.narratesWithDoubao() && !freeVoicesForTest) director.narratorDoubao(b.id) else null
            // no preceding text for narration: with a character's line as context 豆包 reads it higher and brighter,
            // so the narrator seemed to change voice from paragraph to paragraph (1.3.8)
            return DramaVoice(null, db, if (db != null) NARRATION else "", null, "", narrator)
        }
        val fallback = if (m.gender.startsWith("女")) "zh-CN-XiaoyiNeural" else "zh-CN-YunjianNeural"
        if (freeVoicesForTest) return DramaVoice(m.name, null, "", null, "", fallback)
        // 豆包: the voice already carries age and temperament; the line's mood goes in as an unbilled instruction
        if (director.usesDoubao()) return DramaVoice(m.name, m.doubaoVoice, doubaoInstruction(seg), null, "", fallback)
        if (m.voice.isEmpty()) return DramaVoice(m.name, null, "", null, "", fallback)
        // a designed Qwen voice already sounds like the character and ignores instructions
        if (VoicePools.isDesigned(m.voice)) return DramaVoice(m.name, null, "", m.voice, "", fallback)
        val persona = VoicePools.persona(m)
        return DramaVoice(m.name, null, "", m.voice, if (seg.emotion.isBlank()) persona else "$persona；${seg.emotion}", fallback)
    }

    /** 豆包 follows a vivid, voice-only direction best; older analyses only have a short mood word. */
    private fun doubaoInstruction(seg: Seg): String {
        val e = seg.emotion.trim().trimEnd('。')
        if (emotionV1ForTest) return if (e.isEmpty()) "" else "像影视剧演员一样把情绪演足，" + (if (e.startsWith("用") && e.endsWith("说")) e else "用${e}的语气说")
        val tone = when {
            e.isEmpty() || seg.intensity == 0 -> ""        // everyday lines: no acting direction, 豆包 reads them naturally
            e.startsWith("用") && e.endsWith("说") -> e
            else -> "用${e}的语气说"
        }
        // only clearly emotional lines (or older analyses without an intensity) are pushed to act it out fully
        val inst = if (tone.isNotEmpty() && (seg.intensity >= 2 || seg.intensity < 0)) "像影视剧演员一样把情绪演足，$tone" else tone
        return withContext(seg, inst)
    }

    /**
     * Adds the narration just before [seg] as reference for 豆包 (context_texts is not read aloud or billed),
     * so a line is voiced knowing what just happened. Up to ~80 characters of the same chapter. Other speakers'
     * lines are left out: 豆包 drifts towards the voice of whatever line it is given.
     */
    private fun withContext(seg: Seg, instruction: String): String {
        if (emotionV1ForTest) return instruction
        val before = runCatching {
            val q = queue.toList()   // a concurrent rebuild only costs this line its context
            val i = q.indexOf(seg)
            if (i <= 0) "" else q.subList(maxOf(0, i - 3), i).filter { it.chapter == seg.chapter && it.role == null }.joinToString("") { it.text }.takeLast(80)
        }.getOrDefault("")
        if (before.isBlank()) return instruction
        return "上文（只作参考，不要读出来）：$before\n" + instruction.ifEmpty { "自然地接着说" }
    }

    /** A/B tests: the 1.3.7 behaviour (every line "演足", no preceding text). */
    internal var emotionV1ForTest = false

    internal suspend fun synthesizeDramaForTest(seg: Seg): File? = synthesizeDrama(seg)

    /** Narration keeps an even storyteller tone; the line's own mood belongs to the characters. */
    private val NARRATION = "用说书人的口吻，从容平稳地叙述"

    /** Tests that only measure pacing use free Edge voices for dialogue too. */
    internal var freeVoicesForTest = false

    /** Why a segment could not be voiced; shown when playback stops at it. */
    private val failures = java.util.concurrent.ConcurrentHashMap<Seg, String>()

    private fun cacheFilesFor(seg: Seg): List<File> {
        if (!seg.drama) return Engines.all.map { cacheFile(engineOf(it), settings(), seg.text) }
        val v = dramaVoiceOf(seg)
        return listOfNotNull(
            v.doubaoVoice?.takeIf { it.isNotEmpty() }?.let { hashName("db|$it|${v.doubaoInstruct}|${seg.text}", "mp3") },
            v.qwenVoice?.let { hashName("dq|$it|${v.instruct}|${seg.text}", "wav") },
            hashName("de|${v.edgeVoice}|${settings().edgeRate}|${seg.text}", "mp3"),
        )
    }

    /**
     * Multi-role: each character speaks with its saved 豆包 (or Qwen) voice, narration with 豆包 or Edge.
     * A voice that fails is never swapped for another engine or voice behind the listener's back: the
     * segment fails, playback pauses there, and the listener retries or picks another voice in the cast.
     */
    private suspend fun synthesizeDrama(seg: Seg): File? {
        val s = settings()
        var v = dramaVoiceOf(seg)
        val who = v.role?.let { "「$it」的配音" } ?: "旁白"
        val fix = if (v.role != null) "点播放重试，或到角色表换一个音色" else "点播放重试"
        if (v.doubaoVoice != null) {
            if (v.role != null && v.doubaoVoice.isNullOrEmpty()) {
                // first line of a new character: choose and save its voice before anything is played with it
                runCatching { director.ensureDoubaoVoices(book!!.id) }
                v = dramaVoiceOf(seg)
            }
            val voice = v.doubaoVoice
            if (voice.isNullOrEmpty()) return fail(seg, "${who}还没能分配音色（${director.lastError ?: "角色表保存失败"}），已暂停，$fix")
            if (overLimit(Engines.DOUBAO, s)) return fail(seg, "豆包语音已达本月用量上限，已暂停。可在设置里调高上限")
            val instruct = v.doubaoInstruct
            val out = hashName("db|$voice|$instruct|${seg.text}", "mp3")
            val err = synthInto(out, Engines.DOUBAO, seg.text) { DoubaoTts.synthesize(seg.text, voice, instruct, it) }
            return if (err == null) out else fail(seg, "${who}暂时不可用：$err。已暂停，$fix")
        }
        val qwen = v.qwenVoice
        if (v.role != null && qwen != null) {
            if (overLimit(Engines.QWEN, s)) return fail(seg, "通义千问语音已达本月用量上限，已暂停。可在设置里调高上限")
            val instruct = v.instruct
            val out = hashName("dq|$qwen|$instruct|${seg.text}", "wav")
            val err = synthInto(out, Engines.QWEN, seg.text) { QwenTts.synthesizeWith(seg.text, qwen, instruct, it) }
            return if (err == null) out else fail(seg, "${who}暂时不可用：$err。已暂停，$fix")
        }
        val edge = v.edgeVoice
        val out = hashName("de|$edge|${s.edgeRate}|${seg.text}", "mp3")
        val err = synthInto(out, null, seg.text) { EdgeTts.synthesize(seg.text, s.copy(edgeVoice = edge), it) }
        return if (err == null) out else fail(seg, "${who}暂时不可用：$err。已暂停，$fix")
    }

    private fun fail(seg: Seg, why: String): File? {
        failures[seg] = why
        return null
    }

    /** Synthesizes into [out] unless it is cached, retrying passing errors. Returns null on success, else why it failed. */
    private suspend fun synthInto(out: File, usageEngine: String?, text: String, call: suspend (File) -> SynthInfo): String? {
        if (out.exists() && out.length() > 0) { out.setLastModified(System.currentTimeMillis()); return null }
        var attempt = 0
        while (true) {
            try {
                val tmp = File(out.path + ".part")
                val info = call(tmp)
                if (!tmp.renameTo(out)) return "缓存写入失败"
                if (usageEngine != null) recordUsage(usageEngine, text, info)
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a bad key or used-up quota will not fix itself; anything else gets two more tries
                if (!(e is TtsException && e.skipEngine) && attempt++ < 2) { delay(800L * attempt); continue }
                return e.message ?: e.javaClass.simpleName
            }
        }
    }

    private fun notifyOnce(key: String, msg: String) {
        if (warned.add(key)) _messages.tryEmit(msg)
    }

    private fun month() = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    private suspend fun recordUsage(engine: String, text: String, info: SynthInfo) {
        if (engine != Engines.COSY && engine != Engines.GEMINI && engine != Engines.QWEN && engine != Engines.DOUBAO) return
        val billed = if (info.billedChars > 0) info.billedChars else if (engine == Engines.GEMINI) 0L else aliyunBillingChars(text)
        withContext(Dispatchers.IO) {
            val dao = app.db.usage()
            val m = month()
            val u = dao.get(m, engine) ?: TtsUsage(m, engine, 0, 0, 0, 0)
            dao.put(u.copy(chars = u.chars + text.length, requests = u.requests + 1, audioTokens = u.audioTokens + info.audioTokens, billedChars = u.billedChars + billed))
        }
    }

    private fun saveProgress(seg: Seg) {
        val b = book ?: return
        val ch = chapters.getOrNull(seg.chapter) ?: return
        val progress = if (b.totalChars > 0) ((ch.startChar + seg.start).toFloat() / b.totalChars).coerceIn(0f, 1f) else 0f
        app.appScope.launch(Dispatchers.IO) { app.db.books().saveProgress(b.id, seg.chapter, seg.start, progress) }
    }

    // ---------------------------------------------------------------- playback

    /** Robolectric's MediaPlayer cannot play real audio; tests that drive the pipeline treat every file as played. */
    internal var skipPlaybackForTest = false

    /** Plays [file] to the end. Returns false when it could not be played; the broken file is deleted. */
    private suspend fun playFile(file: File): Boolean = if (skipPlaybackForTest) true else suspendCancellableCoroutine<Boolean> { cont ->
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { if (cont.isActive) cont.resume(true) }
            p.setOnErrorListener { _, _, _ -> file.delete(); if (cont.isActive) cont.resume(false); true }
            p.prepare()
            mp = p
            if (!paused) p.start()
        } catch (e: Exception) {
            file.delete()
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { scope.launch { if (mp === p) releasePlayer() else p.release() } }
    }.also { releasePlayer() }

    private fun releasePlayer() {
        mp?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        mp = null
    }

    private suspend fun stopInternal(keepActive: Boolean) {
        producer?.cancel(); player?.cancel()
        producer = null; player = null
        releasePlayer()
        results.values.forEach { it.cancel() }
        results.clear()
        if (!keepActive) {
            sleepJob?.cancel()
            abandonFocus()
            _state.value = TtsState(engine = sessionEngine)
        }
    }

    // ---------------------------------------------------------------- audio focus

    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private var resumeOnGain = false
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; pause() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        if (_state.value.playing) { resumeOnGain = true; pause() }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) { resumeOnGain = false; resume() }
                }
            }.build()
    }

    private fun requestFocus() { audioManager.requestAudioFocus(focusRequest) }
    private fun abandonFocus() { audioManager.abandonAudioFocusRequest(focusRequest) }

    private fun trimCache() = scope.launch(Dispatchers.IO) {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return@launch
        var total = files.sumOf { it.length() }
        val max = 400L * 1024 * 1024
        for (f in files) {
            if (total <= max * 3 / 4) break
            total -= f.length(); f.delete()
        }
    }

    private var previewPlayer: MediaPlayer? = null

    /** Synthesizes a short sample with exactly this engine (no fallback). Returns an error text or null. */
    suspend fun preview(engineId: String): String? {
        val s = settings()
        val engine = engineOf(engineId)
        val text = "这里是刀鱼听书。夜色渐深，说书人轻拍醒木：列位看官，今日咱们接着往下讲。"
        val out = File(context.cacheDir, "preview." + engine.ext)
        return try {
            withContext(Dispatchers.IO) { engine.synthesize(text, s, out) }
            previewPlayer?.release()
            previewPlayer = MediaPlayer().apply {
                setDataSource(out.absolutePath); prepare(); start()
                setOnCompletionListener { it.release(); if (previewPlayer === it) previewPlayer = null }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Plays a short Qwen sample for the cast page. Returns an error text or null. */
    /** Plays a local clip (e.g. the free preview saved when a voice was designed). */
    fun previewFile(f: File): String? = try {
        previewPlayer?.release()
        previewPlayer = MediaPlayer().apply {
            setDataSource(f.absolutePath); prepare(); start()
            setOnCompletionListener { it.release(); if (previewPlayer === it) previewPlayer = null }
        }
        null
    } catch (e: Exception) { e.message ?: e.javaClass.simpleName }

    suspend fun previewDoubao(voice: String, text: String): String? {
        val out = File(context.cacheDir, "preview_db_${(voice + text).hashCode()}.mp3")
        return try {
            if (!out.exists() || out.length() < 200) DoubaoTts.synthesize(text, voice, "", out)
            previewFile(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    suspend fun previewQwen(voice: String, instruct: String, text: String): String? {
        val out = File(context.cacheDir, "preview_role.wav")
        return try {
            QwenTts.synthesizeWith(text, voice, instruct, out)
            previewPlayer?.release()
            previewPlayer = MediaPlayer().apply {
                setDataSource(out.absolutePath); prepare(); start()
                setOnCompletionListener { it.release(); if (previewPlayer === it) previewPlayer = null }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    fun cacheSize(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0
    fun clearCache() { cacheDir.listFiles()?.forEach { it.delete() } }
}
