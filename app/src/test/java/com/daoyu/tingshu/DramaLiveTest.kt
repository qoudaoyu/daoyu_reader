package com.daoyu.tingshu

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.daoyu.tingshu.data.BookImporter
import com.daoyu.tingshu.drama.CastScreen
import com.daoyu.tingshu.reader.ListenPanel
import com.daoyu.tingshu.ui.DaoyuTheme
import com.daoyu.tingshu.ui.ReaderThemes
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** End-to-end multi-role run against the real services. Needs COSY_KEY; writes the played audio list to TTS_OUT. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h905dp-xxhdpi")
class DramaLiveTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun fullBook() {
        val key = System.getenv("COSY_KEY").orEmpty(); assumeTrue(key.isNotEmpty())
        val out = File(System.getenv("TTS_OUT") ?: "build/drama").apply { mkdirs() }
        val app = App.instance
        app.tts.skipPlaybackForTest = true
        app.keys.cosyKey = key
        app.keys.volcKey = System.getenv("VOLC_KEY").orEmpty()
        System.getenv("DIRECTOR_MODEL")?.let { m -> runBlocking { app.settings.updateTts { it.copy(directorModel = m, directorChecked = true) } } }
        app.tts.freeVoicesForTest = System.getenv("FREE_VOICES") != null
        app.keys.grokKey = System.getenv("GROK_KEY").orEmpty()
        app.director.simulateGrokDown = System.getenv("SIM_GROK_DOWN") != null
        app.director.simulateQwenDown = System.getenv("SIM_QWEN_DOWN") != null
        app.director.simulateBlock = System.getenv("SIMULATE_BLOCK_B64")?.let { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) }
        System.getenv("DUMP_REPLY")?.let { path -> var n = 0; app.director.onReply = { p, r -> File(path).apply { mkdirs() }.resolve("part${n++}.txt").writeText(p + "\n=====\n" + r) } }
        System.getenv("DRAMA_ENGINE")?.let { e -> runBlocking { app.settings.updateTts { it.copy(dramaVoiceEngine = e) } } }
        val book0 = runBlocking {
            val res = System.getenv("DRAMA_RES") ?: "drama_book.txt"
            (app.importer.import("测试-$res", javaClass.getResourceAsStream("/$res")!!) as BookImporter.Result.Ok).book
        }
        println("chapters=${book0.chapterCount}")
        runBlocking { app.db.books().setDramaMode(book0.id, true) }
        val book = runBlocking { app.db.books().get(book0.id)!! }

        val played = ArrayList<String>()
        val msgs = java.util.Collections.synchronizedList(ArrayList<String>())
        app.tts.onPlay = { seg, f ->
            val copy = File(out, String.format("%03d.%s", played.size + 1, f.extension)); f.copyTo(copy, overwrite = true)
            played.add("${copy.absolutePath}\t${seg.chapter}\t${seg.role ?: "旁白"}\t${seg.text.replace('\n', ' ')}")
        }
        val job = kotlinx.coroutines.GlobalScope.launch0 { app.tts.messages.collect { msgs.add(it) } }
        val t0 = System.currentTimeMillis()
        app.tts.start(book, 0, 0)
        while (System.currentTimeMillis() - t0 < 480_000 && msgs.none { it.contains("全书已读完") }) {
            ShadowLooper.idleMainLooper(); Thread.sleep(100)
        }
        job.cancel()
        println("DRAMA finished in ${(System.currentTimeMillis() - t0) / 1000}s, messages=$msgs, segments=${played.size}")
        File(out, "played.tsv").writeText(played.joinToString("\n"))
        val cast = app.director.cast(book.id).value
        cast.members.forEach { println("CAST ${it.name} ${it.gender}/${it.age} voice=${it.voice} lines=${it.lines} aliases=${it.aliases} db=${it.doubaoVoice} desc=${it.voiceDesc}") }
        (0 until book.chapterCount).forEach { ch ->
            app.director.cached(book.id, ch)?.lines?.forEach { l -> println("ROLE ch$ch ${l.speaker}\t${book.let { app.storage.readChapter(it.id, ch).substring(l.start, l.end) }}") }
        }
        println("BLOCKED parts=${app.director.blockedParts.get()} grokRescued=${app.director.grokRescued.get()} grokFallback=${app.director.grokFallback.get()} sim=${app.director.simulateBlock}")
        val usage = runBlocking { app.db.usage().observeAllOnce() }
        usage.forEach { println("USAGE ${it.engine} chars=${it.chars} billed=${it.billedChars} tokens=${it.audioTokens} req=${it.requests}") }

        // UI: listen panel in multi-role mode and the cast page
        val page = androidx.compose.runtime.mutableIntStateOf(0)
        val b2 = runBlocking { app.db.books().get(book.id) }
        rule.setContent { DaoyuTheme(dark = false) { if (page.intValue == 0) ListenPanel(b2, ReaderThemes[1], {}, {}, {}) else CastScreen(book.id) {} } }
        rule.waitForIdle(); rule.onRoot().captureRoboImage("build/shots/11_listen_drama.png")
        page.intValue = 1
        rule.waitForIdle(); Thread.sleep(500); rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/12_cast.png")
        app.tts.stop()
    }
}

private fun kotlinx.coroutines.GlobalScope.launch0(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class) kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined, block = block)
