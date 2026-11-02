package com.daoyu.tingshu.tts

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Loudness of synthesized clips, measured the way listeners hear it (ITU-R BS.1770 K-weighting with gating, i.e. LUFS).
 *
 * Two problems are handled here:
 *  - Every 豆包 / 千问 request comes back at its own level (3–7 dB apart), so each clip is scaled to the same loudness
 *    before it is cached ([normalize], [normalizeWavFile]).
 *  - A phone's built-in speaker barely reproduces anything below ~500 Hz. Deep, warm male voices keep much of their
 *    energy down there, so at equal loudness they sound 3–8 dB quieter than other voices through the speaker
 *    (measured over the 豆包 catalogue: the drop ranges from 0 to 12 dB, median 5.5). [speakerOffsetDb] tells how much
 *    a clip needs to be raised (or lowered) when it plays through the speaker; headphones need no correction.
 */
object Loudness {
    /** Target loudness of a cached clip. About the level the 1.3.10 RMS normalisation produced, so nothing jumps. */
    const val TARGET_LUFS = -21.0
    /** Typical speaker loss of the 豆包 voices (catalogue median); clips losing more than this get raised. */
    const val SPEAKER_REF_DB = 5.5
    private const val SPEAKER_HP_HZ = 500.0

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun run(x: DoubleArray): DoubleArray {
            val y = DoubleArray(x.size)
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in x.indices) {
                val v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x[i]; y2 = y1; y1 = v; y[i] = v
            }
            return y
        }
    }

    /** BS.1770 pre-filter (high shelf) and RLB high-pass, for any sample rate (coefficients as in libebur128). */
    private fun kWeighting(rate: Int): List<Biquad> {
        var f0 = 1681.974450955533; val g = 3.999843853973347; var q = 0.7071752369554196
        var k = tan(PI * f0 / rate)
        val vh = 10.0.pow(g / 20); val vb = vh.pow(0.4996667741545416)
        var a0 = 1 + k / q + k * k
        val shelf = Biquad((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        f0 = 38.13547087602444; q = 0.5003270373238773
        k = tan(PI * f0 / rate)
        a0 = 1 + k / q + k * k
        val hp = Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        return listOf(shelf, hp)
    }

    /** 2nd-order Butterworth high-pass (RBJ cookbook). */
    private fun highPass(f: Double, rate: Int): Biquad {
        val w = 2 * PI * f / rate; val alpha = sin(w) / (2 * 0.7071067811865476); val c = cos(w); val a0 = 1 + alpha
        return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }

    /** Gated loudness (LUFS) of [x] (samples in -1..1); null when the clip is silent. */
    private fun gated(x: DoubleArray, rate: Int): Double? {
        val block = (rate * 0.4).toInt(); val step = (rate * 0.1).toInt()
        val powers = ArrayList<Double>()
        if (x.size <= block) {
            powers.add(x.sumOf { it * it } / x.size.coerceAtLeast(1))
        } else {
            var s = 0
            while (s + block <= x.size) {
                var e = 0.0
                for (i in s until s + block) e += x[i] * x[i]
                powers.add(e / block); s += step
            }
        }
        fun lufs(p: Double) = -0.691 + 10 * log10(p)
        val abs = powers.filter { it > 0 && lufs(it) > -70 }
        if (abs.isEmpty()) return null
        val rel = lufs(abs.average()) - 10
        val kept = abs.filter { lufs(it) > rel }
        return lufs(kept.average())
    }

    private fun toDouble(s: ShortArray) = DoubleArray(s.size) { s[it] / 32768.0 }

    /** Integrated loudness (LUFS) of 16-bit mono PCM samples. */
    fun lufs(s: ShortArray, rate: Int): Double? {
        var x = toDouble(s)
        for (f in kWeighting(rate)) x = f.run(x)
        return gated(x, rate)
    }

    /** Loudness as heard through a phone speaker: K-weighted with everything below ~500 Hz cut (24 dB/oct). */
    fun speakerLufs(s: ShortArray, rate: Int): Double? {
        var x = toDouble(s)
        val hp = highPass(SPEAKER_HP_HZ, rate)
        x = hp.run(hp.run(x))
        for (f in kWeighting(rate)) x = f.run(x)
        return gated(x, rate)
    }

    /**
     * How many dB [s] should be raised (positive) or lowered (negative) when it plays through the built-in speaker,
     * relative to a typical voice. 0 when it cannot be measured.
     */
    fun speakerOffsetDb(s: ShortArray, rate: Int): Double {
        val full = lufs(s, rate) ?: return 0.0
        val spk = speakerLufs(s, rate) ?: return 0.0
        return full - spk - SPEAKER_REF_DB
    }

    /** 16-bit little-endian mono PCM in, the same scaled to [TARGET_LUFS] out (gain 0.25–4, never clipping). */
    fun normalize(pcm: ByteArray, rate: Int = 24000): ByteArray {
        val n = pcm.size / 2
        if (n < rate / 50) return pcm
        val s = ShortArray(n) { ((pcm[2 * it].toInt() and 0xff) or (pcm[2 * it + 1].toInt() shl 8)).toShort() }
        val out = ByteArray(n * 2)
        scale(s, rate) ?: return pcm
        for (i in 0 until n) { out[2 * i] = s[i].toByte(); out[2 * i + 1] = (s[i].toInt() shr 8).toByte() }
        return out
    }

    /** Scales [s] in place to the target loudness; null when it is silent and was left alone. */
    private fun scale(s: ShortArray, rate: Int): Double? {
        val l = lufs(s, rate) ?: return null
        var peak = 1
        for (v in s) peak = maxOf(peak, kotlin.math.abs(v.toInt()))
        val gain = 10.0.pow((TARGET_LUFS - l) / 20).coerceIn(0.25, 4.0).coerceAtMost(0.97 * 32767 / peak)
        for (i in s.indices) s[i] = (s[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        return gain
    }

    /** A 16-bit mono PCM WAV: its sample rate, where the samples start and the samples. Null for anything else. */
    class Wav(val rate: Int, val dataOffset: Long, val samples: ShortArray)

    fun readWav(f: File): Wav? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val head = ByteArray(12); raf.readFully(head)
            if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return null
            var rate = 0; var ok = false
            while (raf.filePointer + 8 <= raf.length()) {
                val ch = ByteArray(8); raf.readFully(ch)
                val id = String(ch, 0, 4)
                val len = ByteBuffer.wrap(ch, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                if (id == "fmt ") {
                    val fmt = ByteArray(16); raf.readFully(fmt)
                    val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    val format = b.short.toInt(); val channels = b.short.toInt(); rate = b.int; b.int; b.short; val bits = b.short.toInt()
                    ok = format == 1 && channels == 1 && bits == 16
                    raf.seek(raf.filePointer + len - 16 + (len and 1))
                } else if (id == "data") {
                    if (!ok || rate <= 0) return null
                    val offset = raf.filePointer
                    // streamed WAVs may carry a placeholder length; take what is actually there
                    val real = minOf(len, raf.length() - offset).toInt() and 1.inv()
                    val bytes = ByteArray(real); raf.readFully(bytes)
                    val s = ShortArray(real / 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
                    return Wav(rate, offset, s)
                } else raf.seek(raf.filePointer + len + (len and 1))
            }
            null
        }
    }.getOrNull()

    /** Scales a downloaded WAV (千问) to the target loudness in place. Files it cannot read are left as they are. */
    fun normalizeWavFile(f: File) {
        val w = readWav(f) ?: return
        if (w.samples.size < w.rate / 50) return
        scale(w.samples, w.rate) ?: return
        val bytes = ByteArray(w.samples.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(w.samples)
        RandomAccessFile(f, "rw").use { it.seek(w.dataOffset); it.write(bytes) }
    }
}
