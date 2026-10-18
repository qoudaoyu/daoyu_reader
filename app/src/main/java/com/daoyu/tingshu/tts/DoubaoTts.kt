package com.daoyu.tingshu.tts

import android.util.Base64
import com.daoyu.tingshu.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 豆包语音合成 2.0 (Volcengine seed-tts-2.0), HTTP one-way streaming API with a new-console API key.
 * The reply is a stream of JSON lines carrying base64 MP3 chunks. A free-form instruction
 * ("用压抑着怒火的语气说") goes into additions.context_texts and is not billed.
 */
object DoubaoTts {
    private const val URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
    const val RESOURCE = "seed-tts-2.0"

    internal fun body(text: String, speaker: String, instruction: String): String = buildJsonObject {
        putJsonObject("user") { put("uid", "daoyu") }
        putJsonObject("req_params") {
            put("text", text)
            put("speaker", speaker)
            putJsonObject("audio_params") { put("format", "mp3"); put("sample_rate", 24000) }
            if (instruction.isNotBlank()) {
                put("additions", buildJsonObject {
                    put("context_texts", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(instruction.take(240))) })
                }.toString())
            }
        }
    }.toString()

    /** Synthesizes [text] into [out] (MP3). Returns the number of characters billed (the text length). */
    suspend fun synthesize(text: String, speaker: String, instruction: String, out: File): SynthInfo = withContext(Dispatchers.IO) {
        val key = App.instance.keys.volcKey
        if (key.isBlank()) throw TtsException("未填写火山引擎 API Key")
        var attempt = 0
        while (true) {
            val req = Request.Builder().url(URL)
                .header("X-Api-Key", key)
                .header("X-Api-Resource-Id", RESOURCE)
                .header("X-Api-Request-Id", UUID.randomUUID().toString())
                .post(body(text, speaker, instruction).toRequestBody("application/json".toMediaType()))
                .build()
            var errMsg: String? = null
            var errCode = 0
            try {
                Http.client.newCall(req).execute().use { r ->
                    val src = r.body?.source() ?: throw TtsException("豆包语音没有返回内容", skipEngine = false)
                    if (!r.isSuccessful) {
                        val raw = src.readUtf8().take(300)
                        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                        errCode = o?.get("code")?.jsonPrimitive?.intOrNull ?: r.code
                        errMsg = o?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP ${r.code}"
                        if (r.code == 429 || r.code >= 500) errCode = -r.code
                        return@use
                    }
                    out.outputStream().use { os ->
                        while (!src.exhausted()) {
                            val line = src.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                            val code = o["code"]?.jsonPrimitive?.intOrNull ?: 0
                            val data = o["data"]?.jsonPrimitive?.contentOrNull
                            if (!data.isNullOrEmpty()) os.write(Base64.decode(data, Base64.DEFAULT))
                            if (code != 0 && code != 20000000) { errCode = code; errMsg = o["message"]?.jsonPrimitive?.contentOrNull ?: "错误 $code" }
                        }
                    }
                }
            } catch (e: IOException) {
                if (attempt++ < 2) { delay(1500L * attempt); continue }
                throw TtsException("豆包语音连接失败：${e.message ?: "网络错误"}", skipEngine = false)
            }
            if (errMsg != null) {
                if (errCode < 0 && attempt++ < 3) { delay(2500L * attempt); continue }
                val m = errMsg!!
                // key / quota / account problems: stop using the engine for this session
                val skip = errCode == 401 || errCode == 403 || m.contains("quota", true) || m.contains("balance", true) ||
                    m.contains("Unauthorized", true) || m.contains("api key", true) || m.contains("resource", true) || m.contains("余额") || m.contains("额度")
                throw TtsException("豆包语音：$m", skipEngine = skip)
            }
            if (out.length() < 200) throw TtsException("豆包语音没有返回音频", skipEngine = false)
            return@withContext SynthInfo(billedChars = text.length.toLong())
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
}
