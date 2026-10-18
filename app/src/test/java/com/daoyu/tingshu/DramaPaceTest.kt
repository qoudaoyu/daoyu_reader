package com.daoyu.tingshu

import com.daoyu.tingshu.data.BookImporter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Listens through a long, dialogue-heavy book in multi-role mode and reports how long playback stalls.
 * Playback itself is instant under Robolectric, so the measured stalls are an upper bound of what a listener
 * would hear (in real time the look-ahead has a whole chapter of listening to finish its work).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DramaPaceTest {
    @Test fun longBook() {
        val key = System.getenv("COSY_KEY").orEmpty(); assumeTrue(key.isNotEmpty())
        val app = App.instance
        app.tts.skipPlaybackForTest = true
        app.keys.cosyKey = key
        app.keys.volcKey = System.getenv("VOLC_KEY").orEmpty()
        System.getenv("DIRECTOR_MODEL")?.let { m -> runBlocking { app.settings.updateTts { it.copy(directorModel = m, directorChecked = true) } } }
        app.tts.freeVoicesForTest = true
        val book0 = runBlocking { (app.importer.import("长篇测试.txt", javaClass.getResourceAsStream("/drama_long.txt")!!) as BookImporter.Result.Ok).book }
        runBlocking { app.db.books().setDramaMode(book0.id, true) }
        val book = runBlocking { app.db.books().get(book0.id)!! }
        println("PACE chapters=${book.chapterCount}")
        app.director.onAnalysed = { ch, ms, n -> println("PACE analysed ch$ch in ${ms / 1000.0}s, $n quotes") }
        val stamps = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Int>>())
        val t0 = System.currentTimeMillis()
        app.tts.onPlay = { seg, _ -> stamps.add((System.currentTimeMillis() - t0) to seg.chapter) }
        val msgs = java.util.Collections.synchronizedList(ArrayList<String>())
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { app.tts.messages.collect { msgs.add(it) } }
        app.tts.start(book, 0, 0)
        // stop once the second chapter has been playing for a while: the chapter switch is what we measure
        while (System.currentTimeMillis() - t0 < 1_500_000 && msgs.none { it.contains("全书已读完") } && stamps.count { it.second >= 1 } < 30) {
            ShadowLooper.idleMainLooper(); Thread.sleep(100)
        }
        job.cancel()
        app.tts.stop()
        println("PACE first sound after ${stamps.firstOrNull()?.first?.div(1000.0)}s, segments=${stamps.size}, messages=$msgs")
        stamps.zipWithNext().filter { (a, b) -> a.second != b.second }.forEach { (a, b) ->
            println("PACE ch${a.second}→ch${b.second} gap ${(b.first - a.first) / 1000.0}s")
        }
        println("PACE max gap ${stamps.zipWithNext().maxOfOrNull { (a, b) -> b.first - a.first }?.div(1000.0)}s")
    }
}
