package com.daoyu.tingshu.drama

import com.daoyu.tingshu.App
import com.daoyu.tingshu.data.TtsUsage
import com.daoyu.tingshu.tts.Http
import com.daoyu.tingshu.tts.TtsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The platform's content filter refused this text (often a false positive on ordinary fiction). */
class ContentBlocked(msg: String) : Exception(msg)

/** Grok (api.x.ai) could not be reached – usually because the phone's proxy is off. */
class GrokUnreachable(msg: String) : Exception(msg)

@Serializable
data class PreanalyzeState(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val error: String? = null)

/**
 * The "director": works out who speaks each quoted line (Qwen-Plus on Aliyun Bailian, same key as
 * CosyVoice), keeps a per-book cast with stable voices, and stores per-chapter results on disk so a
 * chapter is analysed – and paid for – only once.
 */
class DramaDirector {
    private val app get() = App.instance
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val bookLocks = ConcurrentHashMap<Long, Mutex>()
    private val casts = ConcurrentHashMap<Long, MutableStateFlow<Cast>>()
    private val inflight = ConcurrentHashMap<String, Deferred<ChapterRoles?>>()
    private val pre = ConcurrentHashMap<Long, MutableStateFlow<PreanalyzeState>>()

    private fun dir(bookId: Long) = File(app.storage.dir(bookId), "drama").apply { mkdirs() }
    private fun castFile(bookId: Long) = File(dir(bookId), "cast.json")
    private fun rolesFile(bookId: Long, ch: Int) = File(dir(bookId), "ch_$ch.json")
    private fun lock(bookId: Long) = bookLocks.getOrPut(bookId) { Mutex() }

    // ------------------------------------------------------------------ cast

    fun cast(bookId: Long): StateFlow<Cast> = castFlow(bookId).asStateFlow()

    /** Books whose cast.json could not be read. The file is kept as cast.json.broken and the listener is told. */
    private val brokenCast = ConcurrentHashMap.newKeySet<Long>()

    fun castBroken(bookId: Long): Boolean { castFlow(bookId); return bookId in brokenCast }

    private fun castFlow(bookId: Long) = casts.getOrPut(bookId) {
        val f = castFile(bookId)
        val c = if (!f.exists()) Cast() else runCatching { json.decodeFromString<Cast>(f.readText()) }.getOrElse {
            brokenCast.add(bookId)
            runCatching { f.copyTo(File(f.path + ".broken"), overwrite = true) }
            Cast()
        }
        MutableStateFlow(c)
    }

    /** Writes the cast to disk first (atomically) and only then publishes it, so voices in use are always saved ones. */
    private fun saveCast(bookId: Long, c: Cast) {
        val f = castFile(bookId)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.encodeToString(Cast.serializer(), c))
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("角色表保存失败") }
        castFlow(bookId).value = c
    }

    /** Follows merges; returns the member that actually speaks for [name], or null for narrator/passer-by. */
    fun resolve(bookId: Long, name: String): CastMember? {
        val members = castFlow(bookId).value.members
        var m = members.firstOrNull { it.name == name } ?: members.firstOrNull { name in it.aliases } ?: return null
        var guard = 0
        while (m.mergedInto != null && guard++ < 10) m = members.firstOrNull { it.name == m.mergedInto } ?: break
        return m
    }

    suspend fun setVoice(bookId: Long, name: String, voice: String) = lock(bookId).withLock {
        val c = castFlow(bookId).value
        saveCast(bookId, c.copy(members = c.members.map { if (it.name == name) it.copy(voice = voice, userVoice = true) else it }))
    }

    suspend fun setNarrator(bookId: Long, edgeVoice: String) = lock(bookId).withLock {
        saveCast(bookId, castFlow(bookId).value.copy(narratorVoice = edgeVoice))
    }

    suspend fun setNarratorDoubao(bookId: Long, voice: String) = lock(bookId).withLock {
        saveCast(bookId, castFlow(bookId).value.copy(narratorDoubao = voice))
    }

    /** True when narration is voiced by 豆包 rather than Edge. */
    fun narratesWithDoubao(): Boolean = usesDoubao() && app.settings.tts.value.dramaNarratorEngine == "doubao"

    /** The 豆包 voice narrating [bookId]; characters are not given this voice automatically. */
    fun narratorDoubao(bookId: Long): String = cast(bookId).value.narratorDoubao ?: app.settings.tts.value.dramaNarratorDoubao

    /** "A is really B": A's lines are spoken with B's voice from now on. */
    suspend fun merge(bookId: Long, from: String, into: String) = lock(bookId).withLock {
        if (from == into) return@withLock
        val c = castFlow(bookId).value
        val src = c.members.firstOrNull { it.name == from } ?: return@withLock
        saveCast(bookId, c.copy(members = c.members.map {
            when (it.name) {
                from -> it.copy(mergedInto = into)
                into -> it.copy(aliases = (it.aliases + from + src.aliases).distinct(), lines = it.lines + src.lines)
                else -> it
            }
        }))
    }

    // ------------------------------------------------------------------ chapters

    fun cached(bookId: Long, ch: Int): ChapterRoles? = rolesFile(bookId, ch).takeIf { it.exists() }?.let {
        runCatching { json.decodeFromString<ChapterRoles>(it.readText()) }.getOrNull()
    }

    /** Analysed with the current method (older results get re-analysed when played). */
    fun isCurrent(bookId: Long, ch: Int) = (cached(bookId, ch)?.version ?: 0) >= ROLES_VERSION

    /** Analyse a chapter if needed. Returns null when it could not be analysed (no key, network…). */
    /**
     * @param urgent playback is waiting for this chapter: its parts go ahead of look-ahead work.
     */
    suspend fun ensureChapter(bookId: Long, ch: Int, urgent: Boolean = true): ChapterRoles? {
        cached(bookId, ch)?.takeIf { it.version >= ROLES_VERSION }?.let { return it }
        val key = "$bookId:$ch"
        if (urgent) urgentKeys.add(key)
        val d = inflight[key] ?: run {
            val nd = CompletableDeferred<ChapterRoles?>()
            val prev = inflight.putIfAbsent(key, nd)
            if (prev != null) return prev.await()
            app.appScope.launch(Dispatchers.IO) {
                try { nd.complete(runCatching { analyse(bookId, ch) }.getOrElse { lastError = it.message; null }) }
                finally { inflight.remove(key) }
            }
            nd
        }
        return try { d.await() } finally { if (urgent) urgentKeys.remove(key) }
    }

    private val urgentKeys = ConcurrentHashMap.newKeySet<String>()
    private val fgSlots = Semaphore(8)
    private val bgActive = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Runs one model call for chapter [key]. Look-ahead chapters share two background slots; a chapter that
     * playback is waiting for (also one whose look-ahead is already under way) takes the foreground slots.
     */
    private suspend fun <T> slot(key: String, block: suspend () -> T): T {
        while (true) {
            if (key in urgentKeys) return fgSlots.withPermit { block() }
            val n = bgActive.get()
            if (n < 2 && bgActive.compareAndSet(n, n + 1)) break
            delay(250)
        }
        try { return block() } finally { bgActive.decrementAndGet() }
    }

    @Volatile var lastError: String? = null; private set

    /** Fire-and-forget look-ahead used while listening. */
    fun prefetch(bookId: Long, ch: Int) {
        if (ch < 0 || isCurrent(bookId, ch)) return
        app.appScope.launch(Dispatchers.IO) { ensureChapter(bookId, ch, urgent = false) }
    }

    suspend fun overrideSpeaker(bookId: Long, ch: Int, offset: Int, speaker: String): Boolean = lock(bookId).withLock {
        val r = cached(bookId, ch) ?: return@withLock false
        val idx = r.lines.indexOfFirst { offset >= it.start && offset < it.end }
        if (idx < 0) return@withLock false
        val lines = r.lines.toMutableList()
        lines[idx] = lines[idx].copy(speaker = speaker, manual = true)
        rolesFile(bookId, ch).writeText(json.encodeToString(ChapterRoles.serializer(), r.copy(lines = lines)))
        true
    }

    /** The quoted line covering [offset], if the chapter has been analysed. */
    fun lineAt(bookId: Long, ch: Int, offset: Int): RoleLine? = cached(bookId, ch)?.lines?.firstOrNull { offset >= it.start && offset < it.end }

    // ------------------------------------------------------------------ pre-analysis

    fun preanalyzeState(bookId: Long): StateFlow<PreanalyzeState> = pre.getOrPut(bookId) { MutableStateFlow(PreanalyzeState()) }.asStateFlow()

    fun preanalyze(bookId: Long, from: Int, count: Int, chapterCount: Int) {
        val st = pre.getOrPut(bookId) { MutableStateFlow(PreanalyzeState()) }
        if (st.value.running) return
        val targets = (from until minOf(chapterCount, from + count)).filter { !isCurrent(bookId, it) }
        st.value = PreanalyzeState(running = true, done = 0, total = targets.size)
        app.appScope.launch(Dispatchers.IO) {
            val sem = Semaphore(3)
            var failed: String? = null
            coroutineScope {
                targets.map { ch ->
                    async {
                        sem.withPermit {
                            if (failed != null) return@withPermit
                            if (ensureChapter(bookId, ch, urgent = false) == null) failed = lastError ?: "分析失败"
                            st.value = st.value.copy(done = st.value.done + 1)
                        }
                    }
                }.forEach { it.await() }
            }
            st.value = st.value.copy(running = false, error = failed)
        }
    }

    // ------------------------------------------------------------------ analysis

    /** Test hook: raw prompt and reply of each part. */
    internal var onReply: ((String, String) -> Unit)? = null

    /** Test hook: (chapter, milliseconds, quotes) after each analysis. */
    internal var onAnalysed: ((Int, Long, Int) -> Unit)? = null

    private suspend fun analyse(bookId: Long, ch: Int): ChapterRoles {
        val t0 = System.currentTimeMillis()
        val r = analyseInner(bookId, ch)
        onAnalysed?.invoke(ch, System.currentTimeMillis() - t0, r.lines.size)
        return r
    }

    private suspend fun analyseInner(bookId: Long, ch: Int): ChapterRoles {
        val body = app.storage.readChapter(bookId, ch)
        val old = cached(bookId, ch)
        val all = QuoteFinder.find(body)
        // words in quotes inside a narrative sentence (所谓的“天才”) are narration – no need to ask
        val embedded = all.filter { QuoteFinder.isEmbedded(body, it) }
        val quotes = all - embedded.toSet()
        if (quotes.isEmpty()) return finish(bookId, ch, embedded.map { RoleLine(it.first, it.last + 1, Roles.NARRATOR) }, old)

        // Chapters are cut into small paragraph-aligned parts (≈30 quotes) that are analysed in parallel:
        // a single 300-line chapter used to take over two minutes in one reply.
        val parts = splitParts(body, quotes)
        val lines = ArrayList<RoleLine>()
        coroutineScope {
            parts.map { part ->
                async {
                    val q = quotes.filter { it.first >= part.first && it.first <= part.last }
                    if (q.isEmpty()) emptyList() else analyseRobust(bookId, ch, body, part, q, withContext = true)
                }
            }.forEach { lines.addAll(it.await()) }
        }
        embedded.forEach { lines.add(RoleLine(it.first, it.last + 1, Roles.NARRATOR)) }
        val r = finish(bookId, ch, lines, old)
        designVoices(bookId, body, r)
        return r
    }

    /** Paragraph-aligned parts of at most [PART_QUOTES] quotes / [PART_CHARS] characters. */
    private fun splitParts(body: String, quotes: List<IntRange>): List<IntRange> {
        val parts = ArrayList<IntRange>()
        var s = 0
        while (s < body.length) {
            var e = minOf(body.length, s + PART_CHARS)
            val inside = quotes.filter { it.first >= s && it.first < e }
            if (inside.size > PART_QUOTES) e = inside[PART_QUOTES].first
            if (e < body.length) {
                // cut at a paragraph end that does not fall inside a quote; keep at least a third of the part
                val nl = body.lastIndexOf('\n', e - 1)
                if (nl > s + (e - s) / 3 && quotes.none { nl in it }) e = nl + 1
            }
            if (e <= s) e = minOf(body.length, s + PART_CHARS)
            parts.add(s until e); s = e
        }
        return parts
    }

    /** Saves the result, carrying over speakers the user corrected by hand in an older analysis. */
    private fun finish(bookId: Long, ch: Int, lines: List<RoleLine>, old: ChapterRoles?): ChapterRoles {
        val manual = old?.lines?.filter { it.manual }?.associateBy { it.start }.orEmpty()
        val merged = lines.map { l -> manual[l.start]?.takeIf { it.end == l.end }?.let { l.copy(speaker = it.speaker, manual = true) } ?: l }
        val r = ChapterRoles(ROLES_VERSION, merged.sortedBy { it.start })
        save(bookId, ch, r)
        return r
    }

    // ------------------------------------------------------------------ designed voices

    /** Characters with at least this many lines get a designed voice. */
    private val designMinLines = 3
    private val designCap = 30
    private val designing = ConcurrentHashMap.newKeySet<String>()

    private fun previewFile(bookId: Long, name: String) = File(dir(bookId), "voice_${Integer.toHexString(name.hashCode())}.wav")

    /** Free preview clip saved when the voice was designed, if any. */
    fun designedPreview(bookId: Long, name: String): File? = previewFile(bookId, name).takeIf { it.exists() && it.length() > 100 }

    /** Main characters (enough lines) without a designed voice get one, in parallel, before the chapter is played. */
    /** Dialogue voices come from 豆包 when that engine is selected and its key is set. */
    fun usesDoubao(): Boolean = app.settings.tts.value.dramaVoiceEngine == "doubao" && app.keys.volcKey.isNotBlank()

    private suspend fun designVoices(bookId: Long, body: String, r: ChapterRoles) {
        if (usesDoubao()) { ensureDoubaoVoices(bookId); return }
        if (!app.settings.tts.value.dramaDesignVoices) return
        val members = castFlow(bookId).value.members
        val already = members.count { it.designedVoice.isNotEmpty() }
        val todo = members.filter { it.mergedInto == null && it.designedVoice.isEmpty() && !it.designFailed && !it.userVoice && it.lines >= designMinLines }
            .sortedByDescending { it.lines }.take((designCap - already).coerceAtLeast(0))
        if (todo.isEmpty()) return
        coroutineScope {
            todo.map { m -> async { runCatching { designFor(bookId, m.name, sampleLine(body, r, m.name)) } } }.forEach { it.await() }
        }
    }

    /** One of the character's own lines from this chapter, used as the preview text. */
    private fun sampleLine(body: String, r: ChapterRoles, name: String): String =
        r.lines.filter { it.speaker == name }.map { body.substring(it.start + 1, (it.end - 1).coerceAtLeast(it.start + 1)) }
            .filter { it.length in 8..40 }.maxByOrNull { it.length } ?: ""

    /** Designs (or re-designs) a voice for [name] from its current description and makes it the character's voice. */
    suspend fun designFor(bookId: Long, name: String, previewText: String = ""): Boolean {
        if (!designing.add("$bookId:$name")) return false
        try {
            val m = castFlow(bookId).value.members.firstOrNull { it.name == name } ?: return false
            val voice = try {
                VoiceDesigner.design(m, previewText.ifBlank { "我是${m.name}。今天的事，咱们慢慢说。" }, previewFile(bookId, name))
            } catch (e: Exception) {
                lastError = e.message
                lock(bookId).withLock {
                    val c = castFlow(bookId).value
                    saveCast(bookId, c.copy(members = c.members.map { if (it.name == name) it.copy(designFailed = true) else it }))
                }
                return false
            }
            lock(bookId).withLock {
                val c = castFlow(bookId).value
                saveCast(bookId, c.copy(members = c.members.map {
                    if (it.name != name) it
                    else it.copy(designedVoice = voice, designFailed = false, voice = if (it.userVoice && !VoicePools.isDesigned(it.voice)) it.voice else voice)
                }))
            }
            return true
        } finally { designing.remove("$bookId:$name") }
    }

    fun isDesigning(bookId: Long, name: String) = "$bookId:$name" in designing

    // ------------------------------------------------------------------ 豆包 voices

    private val picking = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Gives every character without a 豆包 voice one from the catalog. The director model matches the
     * character's description against the voice library (one small call for all new characters);
     * anything it gets wrong falls back to a gender/age rule.
     */
    suspend fun ensureDoubaoVoices(bookId: Long) {
        // a voice once given to a character is never taken away automatically; only the listener changes it
        val todo = castFlow(bookId).value.members.filter { it.mergedInto == null && it.doubaoVoice.isEmpty() && !it.doubaoUser }
        if (todo.isEmpty()) return
        if (!picking.add(bookId)) {
            // another call is already choosing: wait for it, so callers only go on with saved voices
            while (bookId in picking) delay(200)
            return
        }
        try {
            val picks = HashMap<String, String>()
            runCatching {
                val taken = castFlow(bookId).value.members.filter { it.mergedInto == null && it.doubaoVoice.isNotEmpty() }
                    .sortedByDescending { it.lines }.take(20)
                val narrator = if (narratesWithDoubao()) "\n旁白 → ${narratorDoubao(bookId)}（不要选这个）" else ""
                val prompt = """你是有声书配音导演。请从下面的豆包音色库里，为每个新角色挑选最贴合的音色。
要求：性别必须一致；年龄段尽量一致；优先匹配角色的性格与嗓音描述；同等合适时选"表现力"分高的（分数是实测的情绪表现力，越高越有戏）；不要和"已占用"里的主要角色重复，除非实在没有合适的。
音色库（id|名称|性别|年龄|特点|表现力）：
${DoubaoVoices.catalogText(todo.map { if (it.gender.startsWith("女")) "女" else "男" }.toSet())}

已占用：
${taken.joinToString("\n") { "${it.name}（${it.gender}，${it.age}）→ ${it.doubaoVoice}" }.ifEmpty { "无" }}$narrator

新角色：
${todo.joinToString("\n") { "${it.name}｜${it.gender}｜${it.age}｜${it.voiceDesc.ifEmpty { "无描述" }}｜已说 ${it.lines} 句" }}

只输出 JSON：{"picks":[{"name":"角色名","voice":"音色id"}]}"""
                val root = json.parseToJsonElement(callLlm(prompt).trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).jsonObject
                root["picks"]?.jsonArray?.forEach { e ->
                    val o = e.jsonObject
                    val n = o["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val v = o["voice"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    picks[n] = v
                }
            }.onFailure { lastError = it.message }
            lock(bookId).withLock {
                val narrator = if (narratesWithDoubao()) narratorDoubao(bookId) else null
                val members = castFlow(bookId).value.members.toMutableList()
                for (i in members.indices) {
                    val m = members[i]
                    if (m.mergedInto != null || m.doubaoVoice.isNotEmpty() || m.doubaoUser) continue
                    val v = picks[m.name]?.takeIf { DoubaoVoices.acceptable(it, m.gender) && it != narrator } ?: DoubaoVoices.assign(m.gender, m.age, members, narrator)
                    members[i] = m.copy(doubaoVoice = v)
                }
                saveCast(bookId, castFlow(bookId).value.copy(members = members))
            }
        } finally { picking.remove(bookId) }
    }

    suspend fun setDoubaoVoice(bookId: Long, name: String, voice: String) = lock(bookId).withLock {
        val c = castFlow(bookId).value
        saveCast(bookId, c.copy(members = c.members.map { if (it.name == name) it.copy(doubaoVoice = voice, doubaoUser = true) else it }))
    }

    /** User edited how the character should sound. */
    suspend fun setVoiceDesc(bookId: Long, name: String, desc: String) = lock(bookId).withLock {
        val c = castFlow(bookId).value
        saveCast(bookId, c.copy(members = c.members.map { if (it.name == name) it.copy(voiceDesc = desc.trim()) else it }))
    }

    private fun save(bookId: Long, ch: Int, r: ChapterRoles) =
        rolesFile(bookId, ch).writeText(json.encodeToString(ChapterRoles.serializer(), r))

    /**
     * Analyses a part; if the platform's content filter rejects it, the part is halved (paragraph-aligned) and
     * each half tried again without the preceding-context block, down to a few lines. Whatever still cannot be
     * sent is attributed with simple "某某道" rules, so one flagged sentence no longer costs the whole chapter.
     */
    private suspend fun analyseRobust(bookId: Long, ch: Int, body: String, part: IntRange, quotes: List<IntRange>, withContext: Boolean): List<RoleLine> {
        try {
            return try { analysePart(bookId, ch, body, part, quotes, withContext) }
            catch (e: ContentBlocked) { throw e }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                try { analysePart(bookId, ch, body, part, quotes, withContext) }   // one retry for other errors
                catch (e2: ContentBlocked) { throw e2 }
                catch (e2: CancellationException) { throw e2 }
                catch (e2: Exception) {
                    // 千问 itself is failing (model retired, quota, outage): Grok takes the part if it can be reached
                    if (grokMain() || !app.settings.tts.value.grokRescue || !grokUsable()) throw e2
                    try { analysePart(bookId, ch, body, part, quotes, withContext, forceGrok = true).also { grokFallback.incrementAndGet(); grokFallbackReason = e2.message } }
                    catch (e3: CancellationException) { throw e3 }
                    catch (_: Exception) { throw e2 }
                }
            }
        } catch (e: ContentBlocked) {
            blockedParts.incrementAndGet()
            // Grok does not apply the Aliyun filter: hand it the whole part if it can be reached
            if (app.settings.tts.value.grokRescue && grokUsable()) {
                try { return analysePart(bookId, ch, body, part, quotes, withContext, forceGrok = true).also { grokRescued.incrementAndGet() } }
                catch (_: GrokUnreachable) { /* proxy off: split and use the rules below */ }
                catch (_: Exception) { }
            }
            if (quotes.size <= 1 || part.last - part.first < 150) return ruleBased(bookId, body, quotes)
            val mid = (part.first + part.last) / 2
            var cut = body.indexOf('\n', mid).takeIf { it in (part.first + 1) until part.last }
                ?: body.lastIndexOf('\n', mid).takeIf { it > part.first }
                ?: (mid until part.last).firstOrNull { body[it] in "。！？" } ?: mid
            if (quotes.any { cut in it }) cut = quotes.first { cut in it }.first - 1
            val halves = listOf(part.first..cut, (cut + 1)..part.last)
            return coroutineScope {
                halves.map { h ->
                    async {
                        val q = quotes.filter { it.first in h }
                        if (q.isEmpty()) emptyList() else analyseRobust(bookId, ch, body, h, q, withContext = false)
                    }
                }.flatMap { it.await() }
            }
        }
    }

    /** Parts the content filter refused / that Grok then analysed (for tests / diagnostics). */
    val blockedParts = java.util.concurrent.atomic.AtomicInteger(0)
    val grokRescued = java.util.concurrent.atomic.AtomicInteger(0)

    /** Parts Grok analysed because 千问 kept failing for reasons other than the content filter. */
    val grokFallback = java.util.concurrent.atomic.AtomicInteger(0)

    /** Why 千问 failed the last time Grok stepped in; shown to the listener once. */
    @Volatile var grokFallbackReason: String? = null

    /** "林墨（少年）" / " 林墨 " → "林墨". */
    private fun cleanName(s: String) = s.replace(Regex("[（(][^）)]*[）)]"), "").trim()

    private val speechVerbs = Regex("(道|说|问|喊|叫|笑|叹|喝|吼|骂|答|应|嚷|嘀咕|开口|低语|冷哼|怒斥)")

    /** Fallback attribution: the nearest known name in "某某(冷笑)道：“…”" / "“…”某某道" around the quote. */
    private fun ruleBased(bookId: Long, body: String, quotes: List<IntRange>, known: Map<Int, String> = emptyMap()): List<RoleLine> {
        val members = castFlow(bookId).value.members.filter { it.mergedInto == null }
        val names = members.flatMap { m -> (listOf(m.name) + m.aliases.filter { it.length >= 2 }).map { it to m.name } }.sortedByDescending { it.first.length }
        return quotes.map { q ->
            known[q.first]?.let { return@map it }
            val lineStart = body.lastIndexOf('\n', q.first).let { if (it < 0) 0 else it + 1 }
            val lineEnd = body.indexOf('\n', q.last).let { if (it < 0) body.length else it }
            val before = body.substring(maxOf(lineStart, q.first - 30), q.first)
            val after = body.substring(q.last + 1, minOf(lineEnd, q.last + 1 + 30))
            fun find(t: String, fromEnd: Boolean): String? {
                val hits = names.mapNotNull { (alias, name) -> val i = if (fromEnd) t.lastIndexOf(alias) else t.indexOf(alias); if (i >= 0 && speechVerbs.containsMatchIn(t.substring(i))) (i to name) else null }
                return (if (fromEnd) hits.maxByOrNull { it.first } else hits.minByOrNull { it.first })?.second
            }
            val intro = before.substringAfterLast('”')
            // "林墨抬起头：“…”" has no speech verb, but a colon right before the quote still names the speaker
            val colon = if (intro.trimEnd().endsWith('：') || intro.trimEnd().endsWith(':')) names.mapNotNull { (alias, name) -> intro.lastIndexOf(alias).takeIf { it >= 0 }?.let { it to name } }.maxByOrNull { it.first }?.second else null
            find(after.substringBefore('“'), false) ?: find(intro, true) ?: colon
        }.let { who ->
            // untagged lines in a back-and-forth (nothing but line breaks between the quotes): the speaker of the
            // line before last speaks again
            fun adjacent(i: Int) = body.substring(quotes[i - 1].last + 1, quotes[i].first).isBlank()
            val res = who.toMutableList()
            for (i in res.indices) {
                if (res[i] != null || i < 2 || !adjacent(i)) continue
                val a = res[i - 2]; val b = res[i - 1]
                if (a != null && b != null && a != b) res[i] = a
            }
            quotes.mapIndexed { i, q -> RoleLine(q.first, q.last + 1, res[i] ?: Roles.PASSERBY) }
        }
    }

    /** The reply left out or garbled the speaker of too many lines; the part is asked once more. */
    private class MalformedReply : Exception()

    private suspend fun analysePart(bookId: Long, ch: Int, body: String, part: IntRange, quotes: List<IntRange>, withContext: Boolean = true, forceGrok: Boolean = false, attempt: Int = 0): List<RoleLine> {
        val text = body.substring(part.first, part.last + 1)
        // mark quotes as 【Q0】“…”
        val sb = StringBuilder()
        var pos = part.first
        quotes.forEachIndexed { i, r ->
            sb.append(body, pos, r.first).append("【Q").append(i).append("】").append(body, r.first, r.last + 1)
            pos = r.last + 1
        }
        sb.append(body, pos, part.last + 1)
        // a part in the middle of a chapter gets the preceding lines as context (who was talking to whom)
        val before = if (withContext && part.first > 0) body.substring(maxOf(0, part.first - 400), part.first).substringAfter('\n') else ""

        // only send characters that appear in this text or just before it, plus the most important ones; a known
        // character missing here (only called 他/老者 in this part) used to come back as a passer-by
        val all = castFlow(bookId).value.members.filter { it.mergedInto == null }
        val near = text + before
        val relevant = (all.filter { m -> (listOf(m.name) + m.aliases).any { it.length >= 2 && near.contains(it) } } +
            all.sortedByDescending { it.lines }.take(12)).distinctBy { it.name }.take(40)
        val registry = buildJsonArray {
            relevant.forEach { m -> addJsonObject { put("name", m.name); putJsonArray("aliases") { m.aliases.forEach { add(it) } }; put("gender", m.gender); put("age", m.age) } }
        }

        val prompt = """你是有声书导演，要给小说里的每句引号内容安排配音。下面一段文字中，引号已用【Q编号】标出，共 ${quotes.size} 处（Q0 到 Q${quotes.size - 1}）。
已知角色表（name 为标准名，aliases 为别称）：
$registry

判断规则：
- 先看引号前后的叙述（如"某某道""某某冷笑一声""她问"）确定说话人；没有提示语的连续对白，按对话双方轮流推断。
- 动作描写里出现的人不一定是说话人，例如"甲看向乙，乙躬身道：……"说话的是乙。
- 留意回话里的称呼：下一句怎么称呼对方（如"小丫头""老夫""哥"），能反推出上一句是谁说的；性别、辈分要对得上。
- 引号里如果不是有人说出口的话，speaker 填"旁白"：强调词或外号（所谓的"天才"）、牌匾招牌、书名、俗语名言、转述或回忆里提到的字句（他想起"活下去"三个字）。
- 角色的心里话（心道、暗想）算该角色的对白。
- 没有名字但身份明确、说话不止一两句的配角（卖花的女孩、店小二、老妇人），用身份当名字（如"卖花女孩"），作为角色列进 characters 并标出性别、年龄。
- 角色表里的人或本段有名字的人，即使这句只用代称（他、她、少年、那人、老者、家主、哥），也要结合上下文判断是谁并填标准名，不要填"路人"。
- 其余无名人物（围观者、一闪而过的下人）和群体（众人、亲友们、几个人齐声）：男性或不确定填"路人"，女性填"路人女"，不要列进 characters。

输出 JSON：
1. characters：本段所有说过话的有名角色，只说一两句的也算（如"杨泉正站在一旁……道"里的杨泉），含角色表里已有的，必须沿用标准名。gender 男/女；age 儿童/少年/青年/中年/老年；aliases 为本段里对他的其他称呼；voice 用一句话描述适合他的嗓音和说话气质（年龄、嗓音、性格），如"四十多岁，嗓音低沉浑厚，说话沉稳威严"。
2. lines：每个编号一项，写成 {"id":编号,"head":"引号内前4个字","speaker":"说话人","emotion":"语气","intensity":强度}，五个字段都要有。speaker 必须是标准名、"路人"、"路人女"或"旁白"；emotion 是给配音的指令，8~20 字，只写声音上听得出来的：情绪和强度、音量、语速、气息（如"压着怒火咬牙低吼""带哭腔急切委屈""慢悠悠阴阳怪气地冷笑"），结合上下文判断情绪；intensity 是整数 0~3：0 平常说话（寒暄、交代事情，emotion 可留空），1 带点情绪，2 情绪明显，3 情绪强烈（怒吼、痛哭、狂喜）。多数日常对白是 0 或 1，不要每句都拔高。
只输出 JSON：{"characters":[{"name":"","aliases":[],"gender":"","age":"","voice":""}],"lines":[{"id":0,"head":"","speaker":"","emotion":"","intensity":0}]}

${if (before.isNotBlank()) "【上文，仅供理解，不用标注】\n$before\n【正文】\n" else ""}$sb"""

        val reply = slot("$bookId:$ch") { callLlm(prompt, forceGrok) }
        onReply?.invoke(prompt, reply)
        val root = json.parseToJsonElement(reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).jsonObject

        try { return attribute(bookId, body, quotes, root, strict = attempt == 0) }
        catch (_: MalformedReply) { return analysePart(bookId, ch, body, part, quotes, withContext, forceGrok, attempt + 1) }
    }

    /** Maps a reply onto the quotes and saves new characters. [strict]: throw [MalformedReply] for a garbled reply. */
    private suspend fun attribute(bookId: Long, body: String, quotes: List<IntRange>, root: kotlinx.serialization.json.JsonObject, strict: Boolean): List<RoleLine> {
        lock(bookId).withLock {
            var members = castFlow(bookId).value.members.toMutableList()
            // new characters → cast, with an automatically assigned voice
            (root["characters"] ?: root["new_characters"])?.jsonArray?.forEach { e ->
                val o = e.jsonObject
                val name = cleanName(o["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
                if (name.isEmpty() || name == Roles.NARRATOR || Roles.isPasserby(name)) return@forEach
                val aliases = o["aliases"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }?.filter { it.isNotEmpty() && it != name }.orEmpty()
                val existing = members.indexOfFirst { it.name == name || name in it.aliases }
                val desc = o["voice"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(60)
                if (existing >= 0) {
                    val e0 = members[existing]
                    members[existing] = e0.copy(aliases = (e0.aliases + aliases).distinct(), voiceDesc = e0.voiceDesc.ifEmpty { desc })
                } else {
                    val gender = o["gender"]?.jsonPrimitive?.contentOrNull ?: "男"
                    val age = o["age"]?.jsonPrimitive?.contentOrNull ?: "青年"
                    members.add(CastMember(name, aliases, gender, age, VoicePools.assign(gender, age, members), voiceDesc = desc))
                }
            }
            val out = ArrayList<RoleLine>()
            val counts = HashMap<String, Int>()
            var bad = 0
            // align each answer to its quote: trust the id only if the echoed opening words match
            fun norm(t: String) = t.filter { it.isLetterOrDigit() }
            val quoteHeads = quotes.map { norm(body.substring(it.first, it.last + 1)) }
            val taken = HashSet<Int>()
            root["lines"]?.jsonArray?.forEach { e ->
                // {"id","head","speaker","emotion","intensity"}: with the array form [id, head, speaker, tone, level]
                // qwen3.8-max sometimes dropped the speaker column for a whole part (1.3.10); arrays are still read
                val arr = e as? kotlinx.serialization.json.JsonArray
                val o = e as? kotlinx.serialization.json.JsonObject
                fun field(i: Int, k: String) = (arr?.getOrNull(i) ?: o?.get(k))?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                val id = field(0, "id")?.trim()?.toIntOrNull() ?: return@forEach
                // [0, "幼娘，你", "心疼自责又急切", 2]: the speaker column is missing, the tone sits in its place
                if (arr != null && arr.size == 4 && (arr[3] as? kotlinx.serialization.json.JsonPrimitive)?.isString == false) { bad++; return@forEach }
                // the model sometimes copies past the closing quote: 糊涂！”林震天… → 糊涂
                val head = norm(field(1, "head").orEmpty().split('”', '」', '』', '"').first()).take(4)
                fun matches(i: Int): Boolean {
                    if (head.isEmpty()) return true
                    val q = quoteHeads.getOrNull(i) ?: return false
                    return if (q.length >= head.length) q.startsWith(head) else q.isNotEmpty() && head.startsWith(q)
                }
                // a sloppy copy (再胡说就 for 再胡说我就) still counts when it lands on its own number
                fun loose(i: Int): Boolean {
                    val q = quoteHeads.getOrNull(i)?.take(8) ?: return false
                    return head.length >= 2 && (q.startsWith(head.take(2)) || head.count { it in q } >= head.length - 1)
                }
                val idx = when {
                    id in quotes.indices && id !in taken && matches(id) -> id
                    head.isNotEmpty() -> quotes.indices.filter { it !in taken && matches(it) }.minByOrNull { kotlin.math.abs(it - id) }
                        ?: id.takeIf { it in quotes.indices && it !in taken && loose(it) } ?: return@forEach
                    else -> return@forEach
                }
                taken.add(idx)
                val r = quotes[idx]
                var sp = cleanName(field(2, "speaker").orEmpty())
                val known = members.firstOrNull { it.name == sp && it.mergedInto == null } ?: members.firstOrNull { it.name == sp }
                    ?: members.filter { sp in it.aliases }.singleOrNull()
                    // "如烟" for 柳如烟, "林墨少爷" for 林墨: one character only, otherwise it stays unknown
                    ?: sp.takeIf { it.length >= 2 && !Roles.isPasserby(it) && it != Roles.NARRATOR }?.let { t ->
                        members.filter { m -> m.mergedInto == null && (listOf(m.name) + m.aliases).any { n -> n.length >= 2 && (t.contains(n) || n.contains(t)) } }.singleOrNull()
                    }
                sp = when {
                    sp == Roles.NARRATOR -> Roles.NARRATOR
                    sp == Roles.PASSERBY_F -> Roles.PASSERBY_F
                    known != null -> known.name
                    else -> { if (sp != Roles.PASSERBY) bad++; Roles.PASSERBY }
                }
                if (known != null) counts[known.name] = (counts[known.name] ?: 0) + 1
                out.add(RoleLine(r.first, r.last + 1, sp, field(3, "emotion").orEmpty().trim().take(40), field(4, "intensity")?.trim()?.toIntOrNull()?.coerceIn(0, 3) ?: -1))
            }
            if (strict && bad >= 2 && bad * 10 > quotes.size * 3) throw MalformedReply()
            members = members.map { m -> counts[m.name]?.let { m.copy(lines = m.lines + it) } ?: m }.toMutableList()
            saveCast(bookId, castFlow(bookId).value.copy(members = members))
            // quotes the model skipped: "某某道" rules rather than a passer-by voice
            val covered = out.map { it.start }.toSet()
            if (quotes.any { it.first !in covered }) {
                // the model's own answers keep a back-and-forth going for the lines it left out
                val answered = out.filter { it.speaker != Roles.NARRATOR && !Roles.isPasserby(it.speaker) }.associate { it.start to it.speaker }
                out.addAll(ruleBased(bookId, body, quotes, answered).filter { it.start !in covered })
            }
            return out.sortedBy { it.start }
        }
    }

    /** Offline tests: answers every model call. */
    internal var fakeLlmForTest: ((String) -> String)? = null

    internal suspend fun analyseForTest(bookId: Long, body: String): List<RoleLine> {
        val quotes = QuoteFinder.find(body)
        return analysePart(bookId, 0, body, body.indices, quotes, withContext = false)
    }

    internal fun ruleBasedForTest(bookId: Long, body: String) = ruleBased(bookId, body, QuoteFinder.find(body))

    /**
     * Chat call to the analysis model, streamed: a long reply arrives piece by piece, so it can never hit
     * the socket read timeout the way a single 2-minute response did (which then retried twice — the
     * "several minutes" wait between chapters).
     */
    /** Test hook: pretend the content filter rejects prompts containing this text. */
    internal var simulateBlock: String? = null

    /** Test hook: pretend Grok cannot be reached. */
    internal var simulateGrokDown = false

    /** Test hook: pretend every 千问 analysis call fails (as when the model is retired). */
    internal var simulateQwenDown = false

    @Volatile private var grokDownUntil = 0L

    private fun grokUsable() = app.keys.grokKey.isNotBlank() && System.currentTimeMillis() > grokDownUntil && !simulateGrokDown

    /** Grok is the chosen analysis model (and reachable). */
    private fun grokMain() = app.settings.tts.value.directorModel == GROK_MODEL && grokUsable()

    /**
     * Runs a model call with the chosen analysis model. When that is Grok but api.x.ai cannot be reached
     * (proxy off), Grok is skipped for 10 minutes and 千问 Max answers instead.
     * @param forceGrok used for parts the Aliyun content filter refused: Grok or nothing.
     */
    private suspend fun callLlm(prompt: String, forceGrok: Boolean = false): String {
        fakeLlmForTest?.let { return it(prompt) }
        val grok = forceGrok || grokMain()
        if (!grok) return callModel(prompt, grok = false)
        return try { callModel(prompt, grok = true) } catch (e: GrokUnreachable) {
            grokDownUntil = System.currentTimeMillis() + 10 * 60_000
            if (forceGrok) throw e
            notifyGrokDown?.invoke()
            callModel(prompt, grok = false)
        }
    }

    /** Called once when Grok is found unreachable and 千问 takes over. */
    var notifyGrokDown: (() -> Unit)? = null

    private suspend fun callModel(prompt: String, grok: Boolean): String = withContext(Dispatchers.IO) {
        if (grok && simulateGrokDown) throw GrokUnreachable("simulated")
        if (!grok && simulateQwenDown) throw TtsException("角色分析失败：Model not exist.（模拟）")
        if (!grok) simulateBlock?.let { if (prompt.contains(it)) throw ContentBlocked("Input data may contain inappropriate content.") }
        val key = if (grok) app.keys.grokKey else app.keys.cosyKey
        if (key.isBlank()) throw TtsException(if (grok) "未填写 Grok API Key" else "多角色需要阿里云 API Key（与 CosyVoice 共用）")
        val qwenModel = app.settings.tts.value.directorModel.let { if (it == GROK_MODEL) "qwen3.8-max" else it }
        val body = buildJsonObject {
            put("model", if (grok) GROK_MODEL else qwenModel)
            putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", prompt) } }
            putJsonObject("response_format") { put("type", "json_object") }
            if (!grok) put("enable_thinking", false)
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
        }.toString()
        val url = if (grok) "https://api.x.ai/v1/chat/completions" else "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        var attempt = 0
        while (true) {
            val sb = StringBuilder()
            var inTok = 0L; var outTok = 0L
            var httpCode = 0; var errBody = ""
            try {
                Http.client.newCall(req).execute().use { r ->
                    httpCode = r.code
                    val src = r.body?.source() ?: throw IOException("empty body")
                    if (!r.isSuccessful) { errBody = src.readUtf8().take(400); return@use }
                    while (!src.exhausted()) {
                        val line = src.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val d = line.removePrefix("data:").trim()
                        if (d == "[DONE]") break
                        val o = runCatching { json.parseToJsonElement(d).jsonObject }.getOrNull() ?: continue
                        o["error"]?.let { e -> errBody = d; httpCode = 400; return@use }
                        o["usage"]?.let { u -> (u as? kotlinx.serialization.json.JsonObject)?.let {
                            inTok = it["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: inTok
                            outTok = it["completion_tokens"]?.jsonPrimitive?.longOrNull ?: outTok
                        } }
                        o["choices"]?.jsonArray?.forEach { c ->
                            c.jsonObject["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.let { sb.append(it) }
                        }
                    }
                }
            } catch (e: IOException) {
                // no proxy: fail fast instead of retrying a blocked host
                if (grok && sb.isEmpty()) throw GrokUnreachable(e.message ?: "连接失败")
                if (attempt++ < 2) { delay(2000L * attempt); continue }
                throw TtsException("角色分析连接失败：${e.message}", skipEngine = false)
            }
            if ((httpCode == 429 || httpCode >= 500) && attempt++ < 3) { delay(3000L * attempt); continue }
            if (httpCode !in 200..299) {
                val err = runCatching { json.parseToJsonElement(errBody).jsonObject["error"]?.jsonObject }.getOrNull()
                val msg = err?.get("message")?.jsonPrimitive?.contentOrNull ?: errBody.take(120)
                val code = err?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (code.contains("data_inspection") || msg.contains("inappropriate", true)) throw ContentBlocked(msg)
                throw TtsException("角色分析失败：$msg")
            }
            if (inTok > 0 || outTok > 0) recordUsage(inTok, outTok, if (grok) GROK_ENGINE else ENGINE)
            if (sb.isEmpty()) { if (attempt++ < 2) continue; throw TtsException("角色分析没有返回内容") }
            return@withContext sb.toString()
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    /** Stored under engine "director" (千问) or "director_grok": chars = input tokens, audioTokens = output tokens. */
    private suspend fun recordUsage(inTokens: Long, outTokens: Long, engine: String) {
        val m = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val dao = app.db.usage()
        val u = dao.get(m, engine) ?: TtsUsage(m, engine, 0, 0, 0, 0)
        dao.put(u.copy(chars = u.chars + inTokens, audioTokens = u.audioTokens + outTokens, requests = u.requests + 1))
    }

    companion object {
        const val PART_QUOTES = 30
        const val PART_CHARS = 3000
        const val ENGINE = "director"
        const val GROK_ENGINE = "director_grok"
        const val GROK_MODEL = "grok-4.20-0309-non-reasoning"
        /** 元 per million tokens ($1.25 / $2.50). */
        const val GROK_IN_PRICE = 9f
        const val GROK_OUT_PRICE = 18f
    }
}
