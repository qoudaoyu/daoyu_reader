package com.daoyu.tingshu

import com.daoyu.tingshu.data.TtsSettings
import com.daoyu.tingshu.tts.CosyVoiceTts
import com.daoyu.tingshu.tts.Engines
import com.daoyu.tingshu.tts.GeminiTts
import com.daoyu.tingshu.tts.QwenTts
import com.daoyu.tingshu.tts.aliyunBillingChars
import com.daoyu.tingshu.tts.TtsEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Live calls against the real services. Skipped unless COSY_KEY / GEMINI_KEY are set in the environment. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudLiveTest {
    private val text = "江南的雨总是来得没有道理。“客官，这天怕是没有船了。”茶摊的老汉一边收着条凳，一边朝他喊，“要不进来喝碗热茶，暖暖身子？”"
    private val outDir = File(System.getenv("TTS_OUT") ?: System.getProperty("java.io.tmpdir")).apply { mkdirs() }

    private fun run(engine: TtsEngine, s: TtsSettings, name: String) = runBlocking {
        val out = File(outDir, "$name.${engine.ext}")
        val t0 = System.currentTimeMillis()
        try {
            val info = engine.synthesize(text, s, out)
            println("LIVE $name OK ${out.length()}B ${System.currentTimeMillis() - t0}ms tokens=${info.audioTokens} billed=${info.billedChars} est=${aliyunBillingChars(text)}")
        } catch (e: Exception) {
            println("LIVE $name FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Test fun cosy() {
        val k = System.getenv("COSY_KEY").orEmpty(); assumeTrue(k.isNotEmpty())
        App.instance.keys.cosyKey = k
        run(CosyVoiceTts, TtsSettings(), "cosy_v3flash_longxiu")
        run(CosyVoiceTts, TtsSettings(cosyVoice = "longmiao_v3"), "cosy_v3flash_longmiao")
    }

    @Test fun gemini() {
        val k = System.getenv("GEMINI_KEY").orEmpty(); assumeTrue(k.isNotEmpty())
        App.instance.keys.geminiKey = k
        run(GeminiTts, TtsSettings(), "gemini_38flash_charon")
        // the style string saved on the phone by version 1.0.1
        run(GeminiTts, TtsSettings(geminiStyle = "用评书艺人说书的口吻，声情并茂、娓娓道来地朗读下面这段小说，对白要有人物的情绪，不要读出这句提示"), "gemini_38flash_oldstyle")
        run(GeminiTts, TtsSettings(geminiModel = "gemini-3.8-flash-lite-tts", geminiVoice = "Sulafat"), "gemini_38lite_sulafat")
    }

    @Test fun qwen() {
        val k = System.getenv("COSY_KEY").orEmpty(); assumeTrue(k.isNotEmpty())
        App.instance.keys.cosyKey = k
        run(QwenTts, TtsSettings(), "qwen_default_instruct")
        run(QwenTts, TtsSettings(qwenVoice = "Dylan"), "qwen_dylan_dialect_noinstruct")
        run(QwenTts, TtsSettings(qwenVoice = "Stella", qwenStyle = ""), "qwen_stella_plain")
    }
}
