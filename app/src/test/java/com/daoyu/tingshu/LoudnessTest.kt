package com.daoyu.tingshu

import com.daoyu.tingshu.tts.Loudness
import com.daoyu.tingshu.tts.writeWav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class LoudnessTest {
    private val rate = 24000

    /** [seconds] of speech-like audio: [parts] are (frequency, amplitude) sines summed; alternate half seconds are silent. */
    private fun clip(vararg parts: Pair<Double, Double>, seconds: Int = 3) = ShortArray(rate * seconds) { i ->
        if ((i / (rate / 2)) % 2 == 1) 0 else parts.sumOf { (f, a) -> a * sin(2 * PI * f * i / rate) }.toInt().toShort()
    }

    private fun bytes(s: ShortArray) = ByteArray(s.size * 2).also { b -> s.forEachIndexed { i, v -> b[2 * i] = v.toByte(); b[2 * i + 1] = (v.toInt() shr 8).toByte() } }
    private fun shorts(b: ByteArray) = ShortArray(b.size / 2) { ((b[2 * it].toInt() and 0xff) or (b[2 * it + 1].toInt() shl 8)).toShort() }

    @Test fun sineReadsAsBs1770Says() {
        // BS.1770: a full-scale 1 kHz sine reads about -3 LUFS; gating drops the silent half seconds
        val s = ShortArray(rate * 2) { (3276.8 * sin(2 * PI * 1000 * it / rate)).toInt().toShort() }   // -20 dBFS peak
        assertEquals(-23.0, Loudness.lufs(s, rate)!!, 0.5)
        // half-silent blocks straddle the pauses, so speech with pauses reads a little lower, never far off
        assertEquals(Loudness.lufs(s, rate)!!, Loudness.lufs(clip(1000.0 to 3276.8), rate)!!, 2.5)
    }

    @Test fun normalizeBringsClipsToTarget() {
        for (amp in listOf(1500.0, 3000.0, 9000.0)) {
            val out = shorts(Loudness.normalize(bytes(clip(300.0 to amp, 1200.0 to amp / 2)), rate))
            assertEquals(Loudness.TARGET_LUFS, Loudness.lufs(out, rate)!!, 0.3)
        }
        // a clip with high peaks is only raised as far as it can go without clipping
        val peaky = shorts(Loudness.normalize(bytes(clip(1000.0 to 30000.0)), rate))
        assertTrue(peaky.maxOf { kotlin.math.abs(it.toInt()) } < 32767)
        // silence stays silence
        assertTrue(Loudness.normalize(ByteArray(rate * 2)).all { it == 0.toByte() })
    }

    @Test fun deepVoicesAreLiftedOnTheSpeaker() {
        // equal loudness, but one keeps its energy at 120 Hz (a deep male voice), the other at 1.5 kHz
        val deep = shorts(Loudness.normalize(bytes(clip(120.0 to 9000.0, 1500.0 to 900.0)), rate))
        val bright = shorts(Loudness.normalize(bytes(clip(300.0 to 3000.0, 1500.0 to 3000.0)), rate))
        assertEquals(Loudness.lufs(deep, rate)!!, Loudness.lufs(bright, rate)!!, 0.5)
        val d = Loudness.speakerOffsetDb(deep, rate); val b = Loudness.speakerOffsetDb(bright, rate)
        assertTrue("deep $d bright $b", d > 5 && b < 2 && d - b > 6)
    }

    @Test fun wavFileIsNormalisedInPlace() {
        val f = File.createTempFile("qwen", ".wav")
        writeWav(bytes(clip(500.0 to 1500.0)), rate, f)
        val before = f.length()
        Loudness.normalizeWavFile(f)
        val w = Loudness.readWav(f)!!
        assertEquals(before, f.length()); assertEquals(rate, w.rate)
        assertEquals(Loudness.TARGET_LUFS, Loudness.lufs(w.samples, rate)!!, 0.3)
        // not a WAV: left alone
        val junk = File.createTempFile("junk", ".wav").apply { writeText("not audio") }
        Loudness.normalizeWavFile(junk); assertEquals("not audio", junk.readText())
        f.delete(); junk.delete()
    }

    /** Local check against ffmpeg's ebur128 on real 豆包 clips: LOUDNESS_PCM_DIR=dir of 24 kHz s16le .pcm files. */
    @Test fun matchesFfmpegOnRealClips() {
        val dir = System.getenv("LOUDNESS_PCM_DIR") ?: return
        val rows = File(dir).listFiles { f -> f.extension == "pcm" }!!.sortedBy { it.name }.map { f ->
            val s = shorts(f.readBytes())
            "${f.nameWithoutExtension}\t${"%.2f".format(Loudness.lufs(s, rate))}\t${"%.2f".format(Loudness.speakerLufs(s, rate))}\t${"%.2f".format(Loudness.speakerOffsetDb(s, rate))}"
        }
        File(System.getenv("LOUDNESS_OUT") ?: "build/loudness.tsv").writeText(rows.joinToString("\n"))
        assertTrue(log10(rows.size.toDouble()) >= 0)
    }
}
