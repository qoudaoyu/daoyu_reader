package com.daoyu.tingshu.transfer

import android.content.Context
import com.daoyu.tingshu.App
import com.daoyu.tingshu.data.BookImporter
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder

data class TransferEvent(val name: String, val ok: Boolean, val message: String)

/** Tiny LAN web server: the computer opens http://phone-ip:port and drops books in. */
class TransferServer(private val context: Context, port: Int) : NanoHTTPD(port) {
    val events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 32)
    private val app = App.instance

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                session.method == Method.GET && (uri == "/" || uri == "/index.html") ->
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", context.assets.open("web/index.html").bufferedReader().readText())
                session.method == Method.GET && uri == "/api/books" -> json(listBooks())
                session.method == Method.POST && uri == "/api/upload" -> upload(session)
                session.method == Method.DELETE && uri.startsWith("/api/books/") -> {
                    val id = uri.removePrefix("/api/books/").toLongOrNull() ?: return notFound()
                    runBlocking { app.importer.delete(id) }
                    json(buildJsonObject { put("ok", true) }.toString())
                }
                session.method == Method.GET && uri.startsWith("/api/books/") && uri.endsWith("/download") -> download(uri)
                else -> notFound()
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "error")
        }
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")

    private fun json(s: String) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", s)

    private fun listBooks(): String {
        val books = runBlocking { app.db.books().all() }.filter { it.ready }
        return buildJsonArray {
            books.forEach { b ->
                addJsonObject {
                    put("id", b.id); put("title", b.title); put("author", b.author)
                    put("format", b.format); put("size", b.fileSize); put("progress", b.progress)
                }
            }
        }.toString()
    }

    private fun upload(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing name")
        val len = session.headers["content-length"]?.toLongOrNull() ?: return newFixedLengthResponse(Response.Status.LENGTH_REQUIRED, MIME_PLAINTEXT, "length required")
        val body = LimitedStream(session.inputStream, len)
        val result = runBlocking { app.importer.import(name, body) }
        val out = when (result) {
            is BookImporter.Result.Ok -> {
                events.tryEmit(TransferEvent(name, true, if (result.duplicate) "书架已有此书" else "《${result.book.title}》已加入书架"))
                buildJsonObject { put("ok", true); put("title", result.book.title); put("duplicate", result.duplicate) }
            }
            is BookImporter.Result.Fail -> {
                events.tryEmit(TransferEvent(name, false, result.message))
                buildJsonObject { put("ok", false); put("error", result.message) }
            }
        }
        return json(out.toString())
    }

    private fun download(uri: String): Response {
        val id = uri.removePrefix("/api/books/").removeSuffix("/download").toLongOrNull() ?: return notFound()
        val book = runBlocking { app.db.books().get(id) } ?: return notFound()
        val f: File = app.storage.sourceFile(book)
        if (!f.exists()) return notFound()
        val r = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", f.inputStream(), f.length())
        val fname = URLEncoder.encode(book.sourceName, "UTF-8").replace("+", "%20")
        r.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$fname")
        return r
    }

    /** Reads exactly [remaining] bytes of the request body, never past it (keep-alive safe). */
    private class LimitedStream(inp: InputStream, private var remaining: Long) : FilterInputStream(inp) {
        override fun read(): Int = if (remaining <= 0) -1 else super.read().also { if (it >= 0) remaining-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
        override fun close() {}
    }

    companion object {
        /** Start on the first free port from 8080. */
        fun startAny(context: Context): TransferServer? {
            for (p in 8080..8090) {
                try {
                    val s = TransferServer(context, p)
                    s.start(SOCKET_READ_TIMEOUT, false)
                    return s
                } catch (_: Exception) {
                }
            }
            return null
        }

        fun lanAddress(): String? {
            val all = try { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() } catch (_: Exception) { emptyList() }
            val candidates = all.filter { it.isUp && !it.isLoopback }
                .sortedBy { n -> when { n.name.startsWith("wlan") -> 0; n.name.startsWith("ap") || n.name.startsWith("swlan") -> 1; else -> 2 } }
            for (n in candidates) {
                if (n.name.startsWith("rmnet") || n.name.startsWith("ccmni") || n.name.startsWith("tun")) continue
                n.inetAddresses.toList().firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.let { return it.hostAddress }
            }
            return null
        }
    }
}
