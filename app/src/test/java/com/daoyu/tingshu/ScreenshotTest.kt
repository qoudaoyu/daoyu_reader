package com.daoyu.tingshu

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import com.daoyu.tingshu.data.BookImporter
import com.daoyu.tingshu.reader.ReaderScreen
import com.daoyu.tingshu.settings.SettingsScreen
import com.daoyu.tingshu.shelf.ShelfScreen
import com.daoyu.tingshu.transfer.TransferScreen
import com.daoyu.tingshu.ui.DaoyuTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h905dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun importSamples(): Long = runBlocking {
        val app = App.instance
        val text = javaClass.getResourceAsStream("/sample.txt")!!.readBytes()
        val r = app.importer.import("《江湖夜雨十年灯》.txt", text.inputStream()) as BookImporter.Result.Ok
        app.importer.import("长安十二时辰.txt", "第一章 子时\n长安城的夜。\n第二章 丑时\n更深了。".toByteArray().inputStream())
        app.importer.import("山海经异闻录 作者：佚名.txt", "第一章 青丘\n有兽焉。\n第二章 昆仑\n其山多玉。".toByteArray().inputStream())
        app.db.books().saveProgress(r.book.id, 0, 0, 0.37f)
        r.book.id
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    @Test fun shelf() {
        importSamples()
        rule.setContent { DaoyuTheme(dark = false) { ShelfScreen({}, {}, {}, {}) } }
        shot("1_shelf")
    }

    @Test fun reader() {
        val id = importSamples()
        rule.setContent { DaoyuTheme(dark = false) { ReaderScreen(id, {}, {}) } }
        rule.waitUntil(5000) { true }
        shot("2_reader")
        rule.onRoot().performTouchInput { click(Offset(width / 2f, height / 2f)) }
        shot("3_reader_menu")
        rule.onNodeWithText("设置").performClick()
        shot("4_reader_style")
    }

    @Test fun readerNight() {
        val id = importSamples()
        runBlocking { App.instance.settings.updateReader { it.copy(theme = 4, font = "serif") } }
        rule.setContent { DaoyuTheme(dark = true) { ReaderScreen(id, {}, {}) } }
        shot("5_reader_night_serif")
        rule.onRoot().performTouchInput { swipeLeft() }
        shot("6_reader_page2")
    }

    @Test fun bookSources() {
        rule.setContent { DaoyuTheme(dark = false) { com.daoyu.tingshu.source.SearchScreen({}, {}, {}) } }
        shot("13_search")
    }

    @Test fun bookSourceList() {
        rule.setContent { DaoyuTheme(dark = false) { com.daoyu.tingshu.source.SourcesScreen({}, {}, {}) } }
        shot("14_sources")
    }

    @Test fun bookSourceAi() {
        rule.setContent { DaoyuTheme(dark = false) { com.daoyu.tingshu.source.SourceAiScreen({}, {}) } }
        shot("16_source_ai")
    }

    @Test fun bookSourceEdit() {
        rule.setContent { DaoyuTheme(dark = false) { com.daoyu.tingshu.source.SourceEditScreen(com.daoyu.tingshu.source.Sources.builtin[0].bookSourceUrl, {}) } }
        shot("15_source_edit")
    }

    @Test fun settings() {
        rule.setContent { DaoyuTheme(dark = false) { SettingsScreen {} } }
        shot("7_settings")
    }

    @Test fun transfer() {
        rule.setContent { DaoyuTheme(dark = false) { TransferScreen {} } }
        shot("8_transfer")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h905dp-xxhdpi")
class ListenPipelineTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun edgeQueueAndPanel() {
        val app = App.instance
        val book = runBlocking {
            val text = javaClass.getResourceAsStream("/sample.txt")!!.readBytes()
            (app.importer.import("《江湖夜雨十年灯》.txt", text.inputStream()) as BookImporter.Result.Ok).book
        }
        val body0 = app.storage.readChapter(book.id, 0)
        println("debug: id=${book.id} body0.len=${body0.length} chapters=${runBlocking { app.db.chapters().list(book.id).size }} segs=${com.daoyu.tingshu.tts.Segmenter.split(0, body0, 0, 150).size}")
        app.tts.clearCache()
        app.tts.skipPlaybackForTest = true
        val msgs = java.util.Collections.synchronizedList(ArrayList<String>())
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { app.tts.messages.collect { msgs.add(it) } }
        app.tts.start(book, 0, 0)
        val t0 = System.currentTimeMillis()
        val deadline = t0 + 120_000
        while (System.currentTimeMillis() < deadline && msgs.isEmpty()) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(100)
        }
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        // Robolectric's MediaPlayer cannot decode audio, so each synthesized segment "plays" instantly (skipPlaybackForTest);
        // reaching the end without any engine error means every segment was synthesized by Edge.
        println("pipeline finished in ${secs}s, messages=$msgs, engine=${app.tts.state.value.engine}")
        job.cancel()
        org.junit.Assert.assertEquals(listOf("全书已读完"), msgs.toList())
        org.junit.Assert.assertTrue(secs > 1.0)
        app.tts.start(book, 0, 0)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        rule.setContent { DaoyuTheme(dark = false) { ReaderScreen(book.id, {}, {}) } }
        rule.waitForIdle(); Thread.sleep(300); rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/9_listening_highlight.png")
        rule.onRoot().performTouchInput { click(Offset(width / 2f, height / 2f)) }
        rule.waitForIdle()
        rule.onNodeWithText("听书").performClick()
        rule.waitForIdle(); Thread.sleep(300); rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/10_listen_panel.png")
        app.tts.stop()
    }
}
