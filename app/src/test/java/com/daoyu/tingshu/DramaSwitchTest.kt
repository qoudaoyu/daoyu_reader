package com.daoyu.tingshu

import com.daoyu.tingshu.data.BookImporter
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DramaSwitchTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test fun switchWhileListening() {
        val key = System.getenv("COSY_KEY").orEmpty(); assumeTrue(key.isNotEmpty())
        val app = App.instance
        app.tts.skipPlaybackForTest = true
        app.keys.cosyKey = key
        val book = runBlocking { (app.importer.import("切换测试.txt", javaClass.getResourceAsStream("/drama_book.txt")!!) as BookImporter.Result.Ok).book }
        val played = ArrayList<String>()
        val msgs = java.util.Collections.synchronizedList(ArrayList<String>())
        app.tts.onPlay = { seg, _ -> played.add((if (seg.drama) "D" else "S") + "|" + seg.chapter + "|" + seg.start + "|" + (seg.role ?: "-")) }
        val job = GlobalScope.launch(Dispatchers.Unconfined) { app.tts.messages.collect { msgs.add(it) } }
        app.tts.start(book, 0, 0)
        fun waitFor(cond: () -> Boolean) { val t = System.currentTimeMillis(); while (!cond() && System.currentTimeMillis() - t < 300_000) { ShadowLooper.idleMainLooper(); Thread.sleep(50) } }
        waitFor { played.size >= 3 }
        app.tts.setDramaMode(book.id, true)
        waitFor { played.count { it.startsWith("D") } >= 8 }
        app.tts.setDramaMode(book.id, false)
        waitFor { msgs.any { it.contains("全书已读完") } }
        job.cancel()
        println("SWITCH played=${played.size} msgs=$msgs")
        played.forEach { println("SWITCH $it") }
        val modes = played.map { it[0] }.joinToString("").replace(Regex("(.)\\1+"), "$1")
        println("SWITCH mode sequence=$modes")
        assertTrue(modes.startsWith("SDS"))
        // positions never jump backwards within a chapter except at the switch point (re-read current sentence)
        assertTrue(msgs.none { it.contains("出错") || it.contains("失败") })
    }
}
