package com.daoyu.tingshu.tts

import com.daoyu.tingshu.App
import com.daoyu.tingshu.data.TtsSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Aliyun Bailian (DashScope) CosyVoice over the duplex WebSocket API. */
object CosyVoiceTts : TtsEngine {
    override val id = Engines.COSY
    override val ext = "mp3"

    override fun voiceKey(s: TtsSettings) = "${s.cosyModel}|${s.cosyVoice}|${s.cosyRate}"

    private fun header(action: String, taskId: String) = buildJsonObject {
        put("action", action); put("task_id", taskId); put("streaming", "duplex")
    }

    internal fun runTask(taskId: String, s: TtsSettings) = buildJsonObject {
        put("header", header("run-task", taskId))
        putJsonObject("payload") {
            put("task_group", "audio"); put("task", "tts"); put("function", "SpeechSynthesizer")
            put("model", s.cosyModel)
            putJsonObject("parameters") {
                put("text_type", "PlainText")
                put("voice", s.cosyVoice)
                put("format", "mp3")
                put("sample_rate", 24000)
                put("volume", 50)
                put("rate", s.cosyRate)
                put("pitch", 1.0)
            }
            putJsonObject("input") {}
        }
    }.toString()

    internal fun continueTask(taskId: String, text: String) = buildJsonObject {
        put("header", header("continue-task", taskId))
        putJsonObject("payload") { putJsonObject("input") { put("text", text) } }
    }.toString()

    internal fun finishTask(taskId: String) = buildJsonObject {
        put("header", header("finish-task", taskId))
        putJsonObject("payload") { putJsonObject("input") {} }
    }.toString()

    override suspend fun synthesize(text: String, s: TtsSettings, out: File): SynthInfo {
        val key = App.instance.keys.cosyKey
        if (key.isBlank()) throw TtsException("未填写 CosyVoice 的 API Key")
        return withTimeout(60_000) {
            suspendCancellableCoroutine { cont ->
                val taskId = UUID.randomUUID().toString().replace("-", "")
                val req = Request.Builder().url(s.cosyEndpoint)
                    .header("Authorization", "Bearer $key")
                    .header("user-agent", "daoyu-tingshu/1.0")
                    .build()
                val audio = ByteArrayOutputStream()
                val ws = Http.client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(runTask(taskId, s))
                    }

                    override fun onMessage(webSocket: WebSocket, text0: String) {
                        val obj = try { Json.parseToJsonElement(text0).jsonObject } catch (_: Exception) { return }
                        val h = obj["header"]?.jsonObject ?: return
                        when (h["event"]?.jsonPrimitive?.content) {
                            "task-started" -> {
                                webSocket.send(continueTask(taskId, text))
                                webSocket.send(finishTask(taskId))
                            }
                            "task-finished" -> {
                                webSocket.close(1000, null)
                                if (audio.size() == 0) {
                                    if (cont.isActive) cont.resumeWithException(TtsException("CosyVoice 没有返回音频", skipEngine = false))
                                } else {
                                    out.writeBytes(audio.toByteArray())
                                    val billed = try {
                                        obj["payload"]?.jsonObject?.get("usage")?.jsonObject?.get("characters")?.jsonPrimitive?.content?.toLong() ?: 0L
                                    } catch (_: Exception) { 0L }
                                    if (cont.isActive) cont.resume(SynthInfo(billedChars = billed))
                                }
                            }
                            "task-failed" -> {
                                webSocket.close(1000, null)
                                val code = h["error_code"]?.jsonPrimitive?.content.orEmpty()
                                val msg = h["error_message"]?.jsonPrimitive?.content.orEmpty()
                                val skip = code.contains("Arrearage") || code.contains("InvalidApiKey") || code.contains("AccessDenied") ||
                                    code.contains("ModelNotFound") || msg.contains("voice", true)
                                if (cont.isActive) cont.resumeWithException(TtsException("CosyVoice：$code $msg".trim(), skipEngine = skip))
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        audio.write(bytes.toByteArray())
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val (msg, skip) = when (response?.code) {
                            401, 403 -> "CosyVoice API Key 无效或无权限" to true
                            null -> "CosyVoice 连接失败：${t.message ?: "网络错误"}" to false
                            else -> "CosyVoice 错误 ${response.code}" to false
                        }
                        if (cont.isActive) cont.resumeWithException(TtsException(msg, skipEngine = skip))
                    }
                })
                cont.invokeOnCancellation { ws.cancel() }
            }
        }
    }
}
