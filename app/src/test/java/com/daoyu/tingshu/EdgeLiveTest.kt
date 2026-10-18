package com.daoyu.tingshu

import com.daoyu.tingshu.data.TtsSettings
import com.daoyu.tingshu.tts.CosyVoiceTts
import com.daoyu.tingshu.tts.EdgeTts
import com.daoyu.tingshu.tts.GeminiTts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EdgeLiveTest {
    @Test fun edgeSynthesizes() = runBlocking {
        val out = File(System.getProperty("java.io.tmpdir"), "edge_test.mp3")
        out.delete()
        EdgeTts.synthesize("二月二，龙抬头。“你是谁？”他问道。", TtsSettings(edgeVoice = "zh-CN-YunxiNeural"), out)
        println("edge mp3 bytes = ${out.length()}  -> ${out.absolutePath}")
        assertTrue(out.length() > 5000)
    }

    @Test fun payloadShapes() {
        println(CosyVoiceTts.runTask("abc", TtsSettings()))
        println(CosyVoiceTts.continueTask("abc", "你好"))
        println(GeminiTts.interactionsBody("你好", TtsSettings()))
        println(GeminiTts.cleanStyle("用评书艺人说书的口吻，声情并茂、娓娓道来地朗读下面这段小说，对白要有人物的情绪，不要读出这句提示"))
    }
}
