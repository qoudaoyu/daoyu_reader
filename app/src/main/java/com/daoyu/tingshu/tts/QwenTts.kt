package com.daoyu.tingshu.tts

import com.daoyu.tingshu.App
import com.daoyu.tingshu.data.TtsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * Alibaba Bailian hosted Qwen3-TTS (same API key as CosyVoice).
 * With a style instruction and a voice that supports it, qwen3-tts-instruct-flash is used;
 * otherwise (no style, or a dialect voice) the plain qwen3-tts-flash model. Both cost the same.
 */
object QwenTts : TtsEngine {
    override val id = Engines.QWEN
    override val ext = "wav"
    private const val URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"

    override fun voiceKey(s: TtsSettings) = "${s.qwenVoice}|${if (useInstruct(s.qwenVoice, s.qwenStyle)) s.qwenStyle.trim().hashCode() else 0}"

    internal fun useInstruct(voice: String, style: String) =
        style.isNotBlank() && !com.daoyu.tingshu.drama.VoicePools.isDesigned(voice) &&
            Engines.qwenVoices.firstOrNull { it.id == voice }?.instruct != false

    internal fun body(text: String, voice: String, style: String): String {
        val instruct = useInstruct(voice, style)
        val model = when {
            com.daoyu.tingshu.drama.VoicePools.isDesigned(voice) -> com.daoyu.tingshu.drama.VoiceDesigner.TARGET_MODEL
            instruct -> "qwen3-tts-instruct-flash"
            else -> "qwen3-tts-flash"
        }
        return buildJsonObject {
            put("model", model)
            putJsonObject("input") {
                put("text", text)
                put("voice", voice)
                put("language_type", "Chinese")
                if (instruct) put("instructions", style.trim())
            }
        }.toString()
    }

    override suspend fun synthesize(text: String, s: TtsSettings, out: File): SynthInfo =
        synthesizeWith(text, s.qwenVoice, s.qwenStyle, out)

    suspend fun synthesizeWith(text: String, voice: String, style: String, out: File): SynthInfo = withContext(Dispatchers.IO) {
        val key = App.instance.keys.cosyKey
        if (key.isBlank()) throw TtsException("未填写阿里云 API Key（与 CosyVoice 共用）")
        val req = Request.Builder().url(URL)
            .header("Authorization", "Bearer $key")
            .post(body(text, voice, style).toRequestBody("application/json".toMediaType()))
            .build()
        var attempt = 0
        while (true) {
            val (code, raw) = try {
                Http.client.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
            } catch (e: IOException) {
                throw TtsException("通义语音连接失败：${e.message ?: "网络错误"}", skipEngine = false)
            }
            if (code == 429 && attempt < 3) { attempt++; delay(3_000L * attempt); continue }
            val root = try { Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) { null }
            if (code !in 200..299 || root == null) {
                val msg = root?.get("message")?.jsonPrimitive?.contentOrNull ?: raw.take(120)
                val c = root?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
                val skip = code == 401 || code == 403 || c.contains("Arrearage") || c.contains("InvalidApiKey") || msg.contains("not supported")
                throw TtsException("通义语音：$msg", skipEngine = skip)
            }
            val url = root["output"]?.jsonObject?.get("audio")?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                ?: throw TtsException("通义语音没有返回音频", skipEngine = false)
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw TtsException("通义语音音频下载失败 ${r.code}", skipEngine = false)
                out.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
            }
            if (out.length() < 100) throw TtsException("通义语音没有返回音频", skipEngine = false)
            val billed = root["usage"]?.jsonObject?.get("characters")?.jsonPrimitive?.longOrNull ?: 0
            return@withContext SynthInfo(billedChars = billed)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
}
