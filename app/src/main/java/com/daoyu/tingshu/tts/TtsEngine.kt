package com.daoyu.tingshu.tts

import com.daoyu.tingshu.data.TtsSettings
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** billedChars: characters the provider reports as billed (Aliyun counts each 汉字 as 2). */
class SynthInfo(val audioTokens: Long = 0, val billedChars: Long = 0)

/** Aliyun billing rule: 汉字 = 2 characters, everything else = 1. */
fun aliyunBillingChars(text: String): Long = text.sumOf { c -> if (Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN) 2L else 1L }

/**
 * @param skipEngine true when retrying this engine is pointless right now
 *        (bad key, no network route, quota used up) and the controller should fall back.
 */
class TtsException(message: String, val skipEngine: Boolean = true) : Exception(message)

interface TtsEngine {
    val id: String
    val ext: String
    /** Everything that changes the produced audio, used for the cache key. */
    fun voiceKey(s: TtsSettings): String
    suspend fun synthesize(text: String, s: TtsSettings, out: File): SynthInfo
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

/** Wraps raw 16-bit little-endian mono PCM into a WAV container. */
fun writeWav(pcm: ByteArray, sampleRate: Int, out: File) {
    val dataLen = pcm.size
    val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + dataLen); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
        putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(dataLen)
    }.array()
    out.outputStream().use { it.write(header); it.write(pcm) }
}

/** Evens out the level of separately synthesized clips, so one segment is not suddenly quieter than the next. */
object Loudness {
    private const val FRAME = 480                 // 20 ms at 24 kHz
    private const val SILENCE = 327.68            // -40 dBFS: pauses are left out of the measurement
    private const val TARGET = 3276.8             // -20 dBFS speech level (豆包 clips measured -15 … -28)

    /** 16-bit little-endian mono PCM in, the same scaled to the target level out (gain 0.3–3, never clipping). */
    fun normalize(pcm: ByteArray): ByteArray {
        val n = pcm.size / 2
        if (n < FRAME) return pcm
        val s = ShortArray(n) { ((pcm[2 * it].toInt() and 0xff) or (pcm[2 * it + 1].toInt() shl 8)).toShort() }
        var sum = 0.0
        var count = 0
        var peak = 1
        for (f in 0 until n / FRAME) {
            var e = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) e += s[i].toDouble() * s[i]
            if (kotlin.math.sqrt(e / FRAME) > SILENCE) { sum += e; count += FRAME }
        }
        for (v in s) peak = maxOf(peak, kotlin.math.abs(v.toInt()))
        if (count == 0) return pcm
        val gain = (TARGET / kotlin.math.sqrt(sum / count)).coerceIn(0.3, 3.0).coerceAtMost(0.97 * 32767 / peak)
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (s[i] * gain).toInt().coerceIn(-32768, 32767)
            out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }
}
