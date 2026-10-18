package com.daoyu.tingshu.drama

import android.util.Base64
import com.daoyu.tingshu.App
import com.daoyu.tingshu.data.TtsUsage
import com.daoyu.tingshu.tts.Http
import com.daoyu.tingshu.tts.TtsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Qwen voice design: turns a text description ("四十多岁，嗓音低沉浑厚，说话沉稳威严") into a custom voice.
 * 0.2 元 per voice; synthesis with it costs the same as the preset voices. The voice follows the character's
 * timbre far better than the handful of preset voices can, but ignores per-line style instructions
 * (emotion then comes from the text itself).
 */
object VoiceDesigner {
    const val TARGET_MODEL = "qwen3-tts-vd-2026-01-26"
    const val ENGINE = "voicedesign"
    const val PRICE = 0.2
    private const val URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/customization"

    private val AGE_M = mapOf("儿童" to "七八岁的小男孩", "少年" to "约十八岁的年轻男性", "青年" to "二十多岁的年轻男性", "中年" to "四十多岁的中年男性", "老年" to "六七十岁的老年男性")
    private val AGE_F = mapOf("儿童" to "七八岁的小女孩", "少年" to "十五六岁的少女", "青年" to "二十多岁的年轻女性", "中年" to "四十多岁的中年女性", "老年" to "六七十岁的老年女性")

    /**
     * Voice-design prompt. Measured: a teenage boy described as "少年音" drifts into a female-sounding voice on
     * some lines, so male voices are pinned to a clearly male, mid-low register and such words are dropped.
     */
    fun prompt(m: CastMember, strict: Boolean = false): String {
        val a = VoicePools.ageKey(m.age)
        var d = m.voiceDesc.trim().trimEnd('。')
        return if (m.gender.startsWith("女")) "女声，${AGE_F[a]}。$d。普通话。".take(300)
        else if (a == "儿童") "男童声，${AGE_M[a]}。$d。普通话。".take(300)
        else {
            d = VoicePools.maleSafe(d)
            val reg = if (strict) "低沉的男中音" else "中低音"
            "男声，${AGE_M[a]}，$reg，一听就是男性，不要女性化或童声。$d。普通话。".take(300)
        }
    }

    /**
     * Creates the voice and checks its pitch on the free preview clip: a "male" voice that comes out in the
     * female range (or the other way round) is designed again once with a stricter prompt.
     */
    suspend fun design(m: CastMember, previewText: String, preview: File): String {
        val v = create(m, previewText, preview, strict = false)
        val f0 = Pitch.medianHz(preview) ?: return v
        val female = m.gender.startsWith("女")
        val child = VoicePools.ageKey(m.age) == "儿童"
        val wrong = if (female) f0 < 160 else !child && f0 > 175
        if (!wrong) return v
        return create(m, previewText, preview, strict = true)
    }

    private suspend fun create(m: CastMember, previewText: String, preview: File?, strict: Boolean): String = withContext(Dispatchers.IO) {
        val key = App.instance.keys.cosyKey
        if (key.isBlank()) throw TtsException("需要阿里云 API Key")
        val body = buildJsonObject {
            put("model", "qwen-voice-design")
            putJsonObject("input") {
                put("action", "create")
                put("target_model", TARGET_MODEL)
                put("voice_prompt", prompt(m, strict))
                put("preview_text", previewText.ifBlank { "今天的事，咱们慢慢说。" }.take(60))
                put("preferred_name", "dy" + Integer.toHexString(m.name.hashCode()).take(8))
            }
            putJsonObject("parameters") { put("sample_rate", 24000); put("response_format", "wav") }
        }.toString()
        val req = Request.Builder().url(URL).header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        var attempt = 0
        while (true) {
            val (code, raw) = try {
                Http.client.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
            } catch (e: IOException) {
                if (attempt++ < 2) { delay(2000L * attempt); continue }
                throw TtsException("音色设计连接失败：${e.message}", skipEngine = false)
            }
            if ((code == 429 || code >= 500) && attempt++ < 3) { delay(3000L * attempt); continue }
            val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            val out = root?.get("output")?.jsonObject
            val voice = out?.get("voice")?.jsonPrimitive?.contentOrNull
            if (code !in 200..299 || voice.isNullOrEmpty()) {
                val msg = root?.get("message")?.jsonPrimitive?.contentOrNull ?: raw.take(120)
                throw TtsException("音色设计失败：$msg")
            }
            if (preview != null) runCatching {
                val data = out["preview_audio"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
                if (data != null) preview.writeBytes(Base64.decode(data, Base64.DEFAULT))
            }
            recordUsage()
            return@withContext voice
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    private suspend fun recordUsage() {
        val m = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val dao = App.instance.db.usage()
        val u = dao.get(m, ENGINE) ?: TtsUsage(m, ENGINE, 0, 0, 0, 0)
        dao.put(u.copy(requests = u.requests + 1))
    }
}

/** Median fundamental frequency of a 16-bit PCM WAV, by autocorrelation on voiced frames. */
object Pitch {
    fun medianHz(f: File): Double? = runCatching {
        val b = f.readBytes()
        if (b.size < 1000 || String(b, 0, 4) != "RIFF") return null
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val rate = bb.getInt(24)
        // find "data" chunk
        var p = 12
        while (p + 8 <= b.size && String(b, p, 4) != "data") p += 8 + bb.getInt(p + 4)
        if (p + 8 > b.size) return null
        val start = p + 8
        val n = (b.size - start) / 2
        val x = DoubleArray(n) { bb.getShort(start + it * 2).toDouble() / 32768.0 }
        medianHz(x, rate)
    }.getOrNull()

    fun medianHz(x: DoubleArray, rate: Int): Double? {
        val frame = rate / 25          // 40 ms
        val minLag = rate / 450; val maxLag = rate / 70
        val out = ArrayList<Double>()
        var i = 0
        while (i + frame + maxLag < x.size) {
            var energy = 0.0
            for (k in 0 until frame) energy += x[i + k] * x[i + k]
            if (energy / frame > 1e-4) {
                var best = 0.0; var bestLag = 0
                for (lag in minLag..maxLag) {
                    var c = 0.0; var e2 = 0.0
                    for (k in 0 until frame) { c += x[i + k] * x[i + k + lag]; e2 += x[i + k + lag] * x[i + k + lag] }
                    val r = c / kotlin.math.sqrt(energy * e2 + 1e-12)
                    if (r > best) { best = r; bestLag = lag }
                }
                if (best > 0.75 && bestLag > 0) out.add(rate.toDouble() / bestLag)
            }
            i += frame / 2
        }
        if (out.size < 5) return null
        out.sort()
        return out[out.size / 2]
    }
}
