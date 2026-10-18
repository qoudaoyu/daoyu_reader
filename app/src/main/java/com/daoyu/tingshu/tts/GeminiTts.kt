package com.daoyu.tingshu.tts

import android.util.Base64
import com.daoyu.tingshu.App
import com.daoyu.tingshu.data.TtsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Google Gemini native TTS.
 *
 * Gemini 3.8 TTS models read every word of the input text aloud, so the narration style must be sent
 * separately as `speech_metadata.style` through the Interactions API. Older models (2.5 / 3.1 preview)
 * only support generateContent, where the style has to be written in front of the text.
 */
object GeminiTts : TtsEngine {
    override val id = Engines.GEMINI
    override val ext = "wav"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    // "v2": audio cached before the style fix may contain the spoken instruction – never reuse it
    override fun voiceKey(s: TtsSettings) = "v2|${s.geminiModel}|${s.geminiVoice}|${cleanStyle(s.geminiStyle).hashCode()}"

    internal fun usesInteractions(model: String) = !(model.startsWith("gemini-2.") || model.startsWith("gemini-3.1-"))

    /** The style is metadata now; phrases written for the old "read the following" prompt are dropped. */
    internal fun cleanStyle(style: String) = style
        .replace(Regex("[，,]?\\s*不要读出(这句|此)?提示[。.]?"), "")
        .replace(Regex("地?朗读下面这段小说"), "")
        .trim().trimEnd('，', ',', '：', ':', '。')

    internal fun interactionsBody(text: String, s: TtsSettings): String = buildJsonObject {
        put("model", s.geminiModel)
        putJsonArray("input") {
            addJsonObject {
                put("type", "user_input")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                        val style = cleanStyle(s.geminiStyle)
                        if (style.isNotEmpty()) putJsonArray("annotations") {
                            addJsonObject { put("type", "speech_metadata"); put("style", style) }
                        }
                    }
                }
            }
        }
        putJsonObject("response_format") { put("type", "audio") }
        putJsonObject("generation_config") {
            putJsonArray("speech_config") { addJsonObject { put("voice", s.geminiVoice) } }
        }
    }.toString()

    internal fun legacyBody(text: String, s: TtsSettings): String = buildJsonObject {
        val style = cleanStyle(s.geminiStyle)
        putJsonArray("contents") {
            addJsonObject {
                putJsonArray("parts") {
                    addJsonObject { put("text", if (style.isEmpty()) text else "$style：\n$text") }
                }
            }
        }
        putJsonObject("generationConfig") {
            putJsonArray("responseModalities") { add("AUDIO") }
            putJsonObject("speechConfig") {
                putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", s.geminiVoice) } }
            }
        }
    }.toString()

    override suspend fun synthesize(text: String, s: TtsSettings, out: File): SynthInfo = withContext(Dispatchers.IO) {
        val key = App.instance.keys.geminiKey
        if (key.isBlank()) throw TtsException("未填写 Gemini 的 API Key")
        val interactions = usesInteractions(s.geminiModel)
        val req = Request.Builder()
            .url(if (interactions) "$BASE/interactions" else "$BASE/models/${s.geminiModel}:generateContent")
            .header("x-goog-api-key", key)
            .post((if (interactions) interactionsBody(text, s) else legacyBody(text, s)).toRequestBody("application/json".toMediaType()))
            .build()

        // Rate limits (429) are usually short; wait and retry before giving up on Gemini.
        var attempt = 0
        while (true) {
            val resp = try {
                Http.client.newCall(req).execute()
            } catch (e: IOException) {
                throw TtsException("Gemini 无法连接（国内需开启代理）", skipEngine = true)
            }
            val code = resp.code
            val raw = resp.use { it.body?.string().orEmpty() }
            if (code == 429 && attempt < 3) {
                attempt++
                delay(8_000L * attempt)
                continue
            }
            if (code !in 200..299) {
                val msg = try { Json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
                throw when (code) {
                    400, 401, 403 -> TtsException("Gemini 拒绝请求：${msg ?: code}", skipEngine = true)
                    429 -> TtsException("Gemini 请求过于频繁或额度已用完", skipEngine = true)
                    else -> TtsException("Gemini 错误 $code：${msg ?: ""}", skipEngine = code == 404)
                }
            }
            val root = Json.parseToJsonElement(raw).jsonObject
            return@withContext if (interactions) parseInteraction(root, out) else parseLegacy(root, out)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** steps[].content[] → {type: audio, mime_type: audio/wav, data: base64 WAV} */
    private fun parseInteraction(root: JsonObject, out: File): SynthInfo {
        val audio = (root["steps"] as? JsonArray).orEmpty()
            .flatMap { (it as? JsonObject)?.get("content") as? JsonArray ?: JsonArray(emptyList()) }
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "audio" }
            ?: throw TtsException("Gemini 没有返回音频", skipEngine = false)
        val bytes = Base64.decode(audio["data"]?.jsonPrimitive?.contentOrNull ?: "", Base64.DEFAULT)
        if (bytes.size < 100) throw TtsException("Gemini 没有返回音频", skipEngine = false)
        val mime = audio["mime_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (mime.contains("wav") || (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte())) out.writeBytes(bytes)
        else writeWav(bytes, Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toInt() ?: 24000, out)
        val tokens = root["usage"]?.jsonObject?.get("total_output_tokens")?.jsonPrimitive?.longOrNull ?: 0
        return SynthInfo(audioTokens = tokens)
    }

    private fun parseLegacy(root: JsonObject, out: File): SynthInfo {
        val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: throw TtsException("Gemini 没有返回音频", skipEngine = false)
        val pcm = ByteArrayOutputStream()
        var rate = 24000
        for (p in parts) {
            val inline = (p as JsonObject)["inlineData"]?.jsonObject ?: continue
            inline["mimeType"]?.jsonPrimitive?.contentOrNull?.let { mt ->
                Regex("rate=(\\d+)").find(mt)?.let { rate = it.groupValues[1].toInt() }
            }
            val data = inline["data"]?.jsonPrimitive?.contentOrNull ?: continue
            pcm.write(Base64.decode(data, Base64.DEFAULT))
        }
        if (pcm.size() == 0) throw TtsException("Gemini 没有返回音频", skipEngine = false)
        writeWav(pcm.toByteArray(), rate, out)
        val tokens = root["usageMetadata"]?.jsonObject?.get("candidatesTokenCount")?.jsonPrimitive?.longOrNull
            ?: (pcm.size() / 2L / rate * 25)
        return SynthInfo(audioTokens = tokens)
    }
}
