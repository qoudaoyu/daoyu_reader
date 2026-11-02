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
