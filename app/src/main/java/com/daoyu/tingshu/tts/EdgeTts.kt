package com.daoyu.tingshu.tts

import com.daoyu.tingshu.data.TtsSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Microsoft Edge "Read Aloud" neural voices (free, unofficial endpoint, same protocol as the
 * open-source edge-tts project). It can stop working if Microsoft changes the service.
 */
object EdgeTts : TtsEngine {
    override val id = Engines.EDGE
    override val ext = "mp3"

    private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM = "143.0.3650.75"
    private const val BASE = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private val muid = UUID.randomUUID().toString().replace("-", "").uppercase()

    override fun voiceKey(s: TtsSettings) = "${s.edgeVoice}|${s.edgeRate}|${s.edgePitch}"

    /** Sec-MS-GEC: SHA-256 of Windows-epoch ticks (rounded to 5 min) + token. */
    internal fun secMsGec(nowMillis: Long = System.currentTimeMillis()): String {
        var seconds = nowMillis / 1000 + 11644473600L
        seconds -= seconds % 300
        val ticks = seconds * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256").digest("$ticks$TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun timestamp(): String = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    internal fun escape(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    internal fun voiceFullName(v: String): String {
        val locale = v.substringBeforeLast('-')
        val name = v.substringAfterLast('-')
        return "Microsoft Server Speech Text to Speech Voice ($locale, $name)"
    }

    override suspend fun synthesize(text: String, s: TtsSettings, out: File): SynthInfo = withTimeout(40_000) {
        suspendCancellableCoroutine { cont ->
            val connId = UUID.randomUUID().toString().replace("-", "")
            val url = "$BASE?TrustedClientToken=$TOKEN&ConnectionId=$connId&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=1-$CHROMIUM"
            val req = Request.Builder().url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROMIUM.substringBefore('.')}.0.0.0 Safari/537.36 Edg/${CHROMIUM.substringBefore('.')}.0.0.0")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=$muid;")
                .build()
            val audio = ByteArrayOutputStream()
            val ws = Http.client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val ts = timestamp()
                    webSocket.send(
                        "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                    )
                    val rate = (if (s.edgeRate >= 0) "+" else "") + s.edgeRate + "%"
                    val pitch = (if (s.edgePitch >= 0) "+" else "") + s.edgePitch + "Hz"
                    val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                        "<voice name='${voiceFullName(s.edgeVoice)}'><prosody pitch='$pitch' rate='$rate' volume='+0%'>" +
                        escape(text) + "</prosody></voice></speak>"
                    webSocket.send(
                        "X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\nContent-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n$ssml"
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, null)
                        if (audio.size() == 0) {
                            if (cont.isActive) cont.resumeWithException(TtsException("Edge 没有返回音频", skipEngine = false))
                        } else {
                            out.writeBytes(audio.toByteArray())
                            if (cont.isActive) cont.resume(SynthInfo())
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val b = bytes.toByteArray()
                    if (b.size < 2) return
                    val headLen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                    if (2 + headLen > b.size) return
                    val head = String(b, 2, headLen, Charsets.US_ASCII)
                    if (head.contains("Path:audio")) audio.write(b, 2 + headLen, b.size - 2 - headLen)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val msg = when (response?.code) {
                        403 -> "Edge 语音拒绝访问（接口可能已变更）"
                        null -> "Edge 语音连接失败：${t.message ?: "网络错误"}"
                        else -> "Edge 语音错误 ${response.code}"
                    }
                    if (cont.isActive) cont.resumeWithException(TtsException(msg, skipEngine = response?.code == 403))
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }
    }
}
