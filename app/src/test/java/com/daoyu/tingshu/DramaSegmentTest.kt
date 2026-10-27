package com.daoyu.tingshu

import com.daoyu.tingshu.drama.QuoteFinder
import com.daoyu.tingshu.drama.RoleLine
import com.daoyu.tingshu.drama.Roles
import com.daoyu.tingshu.drama.VoicePools
import com.daoyu.tingshu.tts.Segmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DramaSegmentTest {
    private val body = "林小满回头喊：“陆川，你快许愿呀！”\n陆川懒洋洋地说：“许什么愿。”她跺脚，「你才是小孩！」\n书上写着“天下第一”四个字。"

    private fun roles(): List<RoleLine> {
        val q = QuoteFinder.find(body)
        assertEquals(4, q.size)
        val who = listOf("林小满", "陆川", "林小满", Roles.NARRATOR)
        return q.mapIndexed { i, r -> RoleLine(r.first, r.last + 1, who[i]) }
    }

    @Test fun splitsNarrationAndLines() {
        val segs = Segmenter.splitDrama(0, body, 0, roles(), 200)
        segs.forEach { println("${it.start}-${it.end} [${it.role ?: "旁白"}] ${it.text}") }
        assertEquals(listOf(null, "林小满", null, "陆川", null, "林小满", null), segs.map { it.role })
        assertEquals("陆川，你快许愿呀！", segs[1].text)
        assertTrue(segs.last().text.contains("天下第一"))     // a quote marked as narration stays with the narrator
        segs.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
        assertTrue(segs.all { it.drama })
    }

    @Test fun startsInsideAQuote() {
        val r = roles()
        val mid = body.indexOf("快许愿")
        val segs = Segmenter.splitDrama(0, body, mid, r, 200)
        assertEquals("林小满", segs.first().role)
        assertEquals("快许愿呀！", segs.first().text)
    }

    @Test fun voicePools() {
        val v1 = VoicePools.assign("女", "老年", emptyList())
        assertEquals("Bellona", v1)
        assertTrue(VoicePools.poolFor("男", "少年").contains("Moon"))
        assertEquals("十几岁的少女", VoicePools.persona("女", "少年"))
    }

    @Test fun embeddedQuotesAreNarration() {
        val t = "　　三年前他还是人人称道的“天才”，如今只剩“废物”这个称呼。\n　　“爹！”林婉跑进来，喊道：“是真的吗？”\n他应了一声“是”便退下。正所谓“三十年河东，三十年河西”。\n“好”"
        val q = QuoteFinder.find(t)
        val emb = q.map { QuoteFinder.isEmbedded(t, it) }
        println(q.map { t.substring(it.first, it.last + 1) }.zip(emb))
        assertEquals(listOf(true, true, false, false, true, true, false), emb)
    }

    @Test fun maleVoicesStayMale() {
        val m = com.daoyu.tingshu.drama.CastMember("林墨", gender = "男", age = "少年", voiceDesc = "十六岁，清朗微冷的少年音，语调平稳")
        val p = com.daoyu.tingshu.drama.VoiceDesigner.prompt(m)
        println(p)
        assertTrue(p.startsWith("男声") && !p.contains("少年音"))
        assertTrue(VoicePools.persona(m).startsWith("男声"))
        val f = com.daoyu.tingshu.drama.CastMember("柳如烟", gender = "女", age = "青年", voiceDesc = "清冷")
        assertTrue(com.daoyu.tingshu.drama.VoiceDesigner.prompt(f).startsWith("女声"))
    }

    @Test fun pitchEstimate() {
        val rate = 24000
        for (hz in listOf(110.0, 220.0)) {
            val x = DoubleArray(rate) { i -> val t = i.toDouble() / rate; 0.3 * kotlin.math.sin(2 * Math.PI * hz * t) + 0.15 * kotlin.math.sin(4 * Math.PI * hz * t) }
            val est = com.daoyu.tingshu.drama.Pitch.medianHz(x, rate)!!
            println("$hz -> $est")
            assertTrue(kotlin.math.abs(est - hz) / hz < 0.05)
        }
    }

    @Test fun doubaoCatalog() {
        val all = com.daoyu.tingshu.drama.DoubaoVoices.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        for (g in listOf("男", "女")) for (a in listOf("儿童", "少年", "青年", "中年", "老年")) {
            val id = com.daoyu.tingshu.drama.DoubaoVoices.assign(g, a, emptyList())
            assertEquals(g, com.daoyu.tingshu.drama.DoubaoVoices.byId(id)!!.gender)
        }
        assertTrue(com.daoyu.tingshu.drama.DoubaoVoices.acceptable("zh_female_gufengshaoyu_uranus_bigtts", "女"))
        assertTrue(!com.daoyu.tingshu.drama.DoubaoVoices.acceptable("zh_female_gufengshaoyu_uranus_bigtts", "男") && com.daoyu.tingshu.drama.DoubaoVoices.isFlat("zh_female_gaolengyujie_uranus_bigtts"))
        val body = com.daoyu.tingshu.tts.DoubaoTts.body("你好", "zh_female_vv_uranus_bigtts", "用开心的语气说")
        assertTrue(body.contains("context_texts") && body.contains("seed") == false)
    }

    @Test
    fun loudnessEvensOutClips() {
        fun tone(amp: Double, n: Int = 24000) = ByteArray(n * 2).also { b ->
            for (i in 0 until n) {
                // half a second of speech, half a second of pause
                val v = if (i % 24000 < 12000) (amp * kotlin.math.sin(i * 0.05)).toInt() else 0
                b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte()
            }
        }
        fun level(b: ByteArray): Double {
            var e = 0.0; var c = 0
            for (i in 0 until b.size / 2) {
                val v = ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort().toDouble()
                if (v != 0.0) { e += v * v; c++ }
            }
            return 20 * kotlin.math.log10(kotlin.math.sqrt(e / c) / 32768)
        }
        val quiet = com.daoyu.tingshu.tts.Loudness.normalize(tone(1500.0))
        val loud = com.daoyu.tingshu.tts.Loudness.normalize(tone(9000.0))
        assertTrue("${level(quiet)} vs ${level(loud)}", kotlin.math.abs(level(quiet) - level(loud)) < 0.5)
        // a clip with high peaks is only raised as far as it can go without clipping
        val peaky = com.daoyu.tingshu.tts.Loudness.normalize(tone(30000.0))
        assertTrue(level(peaky) <= 0.0)
    }
}
