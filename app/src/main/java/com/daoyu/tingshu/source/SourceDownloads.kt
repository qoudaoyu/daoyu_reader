package com.daoyu.tingshu.source

import com.daoyu.tingshu.data.BookImporter
import com.daoyu.tingshu.data.BookStorage
import com.daoyu.tingshu.parser.ParsedBook
import com.daoyu.tingshu.parser.ParsedChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads books found through a book source and hands them to [BookImporter]. Runs in the app scope,
 * so leaving the search page doesn't stop a download.
 */
class SourceDownloads(
    private val scope: CoroutineScope,
    private val importer: BookImporter,
    private val storage: BookStorage,
    private val client: SourceClient = SourceClient(),
) {
    enum class Status { RUNNING, DONE, FAILED, CANCELLED }

    data class Task(
        val id: Int,
        val title: String,
        val sourceName: String,
        val status: Status = Status.RUNNING,
        val message: String = "准备中…",
        val done: Int = 0,
        val total: Int = 0,
        val bookId: Long = -1,
    )

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks
    private val jobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()
    private val ids = AtomicInteger()

    private fun update(id: Int, f: (Task) -> Task) = _tasks.update { list -> list.map { if (it.id == id) f(it) else it } }

    /** Downloads a TXT / EPUB file link. */
    fun downloadFile(result: SearchResult, detail: BookDetail, url: String) = launch(result, detail) { id ->
        importLink(id, result.source, url) { ext -> fileName(detail, ext) }
    }

    /**
     * A download started in the in-app browser. [cookies] and [userAgent] come from the WebView so that
     * sites behind a login hand over the same file they would give the browser.
     */
    fun downloadWeb(url: String, suggestedName: String, cookies: String?, userAgent: String, referer: String?) {
        val base = suggestedName.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "未命名" }
        val headers = buildJsonObject {
            put("User-Agent", userAgent)
            if (!cookies.isNullOrBlank()) put("Cookie", cookies)
            if (!referer.isNullOrBlank()) put("Referer", referer)
        }
        val src = BookSource(bookSourceUrl = url, bookSourceName = "网页导入", header = headers.toString())
        launch(base, src.bookSourceName) { id -> importLink(id, src, url) { ext -> "$base.$ext" } }
    }

    private suspend fun importLink(id: Int, src: BookSource, url: String, name: (String) -> String): BookImporter.Result {
        val tmp = File.createTempFile("dl_", ".bin", storage.tmpDir)
        try {
            update(id) { it.copy(message = "正在下载…") }
            val ext = client.download(src, url, tmp) { done, total ->
                update(id) { it.copy(message = "正在下载 " + sizeText(done) + if (total > 0) " / " + sizeText(total) else "", done = done.toInt(), total = total.toInt().coerceAtLeast(0)) }
            } ?: throw SourceException("下载的不是 TXT 或 EPUB 文件")
            update(id) { it.copy(message = "正在导入…") }
            return importer.importFile(name(ext), tmp)
        } finally {
            tmp.delete()
        }
    }

    /** Fetches every chapter of a text-type source and imports them as one book. */
    fun downloadChapters(result: SearchResult, detail: BookDetail) = launch(result, detail) { id ->
        val src = result.source
        update(id) { it.copy(message = "正在获取目录…") }
        val toc = client.toc(src, detail.tocUrl)
        if (toc.isEmpty()) throw SourceException("目录为空：请检查书源的目录规则")
        update(id) { it.copy(message = "正在下载章节", done = 0, total = toc.size) }
        val bodies = arrayOfNulls<String>(toc.size)
        val counter = AtomicInteger()
        val gate = Semaphore(CONCURRENCY)
        coroutineScope {
            toc.mapIndexed { i, ch ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        bodies[i] = fetchChapter(src, ch.url, toc.getOrNull(i + 1)?.url)
                        val n = counter.incrementAndGet()
                        update(id) { it.copy(message = "正在下载章节 $n / ${toc.size}", done = n) }
                    }
                }
            }.awaitAll()
        }
        val failed = bodies.count { it == null }
        if (failed == toc.size) throw SourceException("章节全部下载失败：请检查书源的正文规则")
        val chapters = toc.mapIndexed { i, ch -> ParsedChapter(ch.name, bodies[i] ?: "（本章下载失败）") }
        val cover = result.coverUrl.takeIf { it.isNotBlank() }?.let { url ->
            runCatching {
                val f = File.createTempFile("cover", ".img", storage.tmpDir)
                try { client.download(src, url, f); f.readBytes() } finally { f.delete() }
            }.getOrNull()
        }
        val name = fileName(detail, "txt")
        val tmp = File.createTempFile("dl_", ".txt", storage.tmpDir)
        try {
            tmp.bufferedWriter().use { w ->
                w.write("《${detail.name}》\n")
                if (detail.author.isNotBlank()) w.write("作者：${detail.author}\n")
                if (detail.intro.isNotBlank()) w.write("\n${detail.intro}\n")
                chapters.forEach { w.write("\n${it.title}\n${it.body}\n") }
            }
            update(id) { it.copy(message = "正在导入…") }
            val res = importer.importParsed(name, tmp, ParsedBook(detail.name, detail.author, chapters, cover))
            if (failed > 0 && res is BookImporter.Result.Ok) update(id) { it.copy(message = "已导入，$failed 章下载失败") }
            res
        } finally {
            tmp.delete()
        }
    }

    private suspend fun fetchChapter(src: BookSource, url: String, next: String?): String? {
        repeat(RETRIES) { attempt ->
            try {
                return client.content(src, url, next)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(800L * (attempt + 1))
            }
        }
        return null
    }

    private fun launch(result: SearchResult, detail: BookDetail, work: suspend (Int) -> BookImporter.Result) =
        launch(detail.name.ifBlank { result.name }, result.source.bookSourceName, work)

    private fun launch(title: String, sourceName: String, work: suspend (Int) -> BookImporter.Result) {
        val id = ids.incrementAndGet()
        _tasks.update { listOf(Task(id, title, sourceName)) + it }
        jobs[id] = scope.launch(Dispatchers.IO) {
            try {
                when (val r = work(id)) {
                    is BookImporter.Result.Ok -> update(id) {
                        it.copy(status = Status.DONE, bookId = r.book.id,
                            message = if (r.duplicate) "已在书架" else if (it.message.startsWith("已导入")) it.message else "已导入书架")
                    }
                    is BookImporter.Result.Fail -> update(id) { it.copy(status = Status.FAILED, message = r.message) }
                }
            } catch (e: CancellationException) {
                update(id) { it.copy(status = Status.CANCELLED, message = "已取消") }
            } catch (e: Exception) {
                update(id) { it.copy(status = Status.FAILED, message = e.message ?: e.javaClass.simpleName) }
            } finally {
                jobs.remove(id)
            }
        }
    }

    fun cancel(id: Int) { jobs[id]?.cancel() }

    fun clearFinished() = _tasks.update { list -> list.filter { it.status == Status.RUNNING } }

    private fun fileName(d: BookDetail, ext: String): String {
        val title = d.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "未命名" }
        return (if (d.author.isNotBlank()) "$title 作者：${d.author.replace(Regex("[\\\\/:*?\"<>|]"), "_")}" else title) + ".$ext"
    }

    private fun sizeText(n: Long) = if (n > 1048576) String.format("%.1f MB", n / 1048576.0) else "${(n / 1024).coerceAtLeast(1)} KB"

    private companion object {
        const val CONCURRENCY = 4
        const val RETRIES = 3
    }
}
