package com.daoyu.tingshu.data

import android.content.Context
import android.graphics.BitmapFactory
import com.daoyu.tingshu.parser.EpubParser
import com.daoyu.tingshu.parser.ParsedBook
import com.daoyu.tingshu.parser.TxtParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class BookImporter(
    private val context: Context,
    private val db: AppDatabase,
    private val storage: BookStorage,
) {
    private val lock = Mutex()

    sealed class Result {
        data class Ok(val book: Book, val duplicate: Boolean = false) : Result()
        data class Fail(val message: String) : Result()
    }

    fun supported(name: String): Boolean = formatOf(name) != null

    private fun formatOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "txt" -> "txt"
        "epub" -> "epub"
        else -> null
    }

    /** Copies the stream to a temp file and imports it. */
    suspend fun import(name: String, input: InputStream): Result = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("imp", ".bin", storage.tmpDir)
        try {
            input.use { inp -> tmp.outputStream().use { inp.copyTo(it, 256 * 1024) } }
            importFile(name, tmp)
        } finally {
            tmp.delete()
        }
    }

    suspend fun importFile(name: String, file: File): Result = withContext(Dispatchers.IO) {
        lock.withLock {
            var format = formatOf(name)
            if (format == null) {
                // sniff: zip magic → epub
                val head = file.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
                format = if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) "epub" else null
            }
            if (format == null) return@withContext Result.Fail("暂不支持该格式：$name（第一期支持 TXT、EPUB）")

            db.books().findSame(name, file.length())?.let { return@withContext Result.Ok(it, duplicate = true) }

            val parsed: ParsedBook = try {
                if (format == "txt") TxtParser.parse(file, name) else EpubParser.parse(file, name)
            } catch (e: Throwable) {
                return@withContext Result.Fail("解析失败：${e.message ?: e.javaClass.simpleName}")
            }

            val id = db.books().insert(Book(title = parsed.title, author = parsed.author, format = format, sourceName = name, fileSize = file.length()))
            try {
                val dir = storage.dir(id).apply { mkdirs() }
                file.copyTo(File(dir, "source.$format"), overwrite = true)
                val chDir = File(dir, "chapters").apply { mkdirs() }
                var start = 0L
                val rows = parsed.chapters.mapIndexed { i, c ->
                    File(chDir, "$i.txt").writeText(c.body)
                    Chapter(id, i, c.title, c.body.length, start).also { start += c.body.length }
                }
                db.chapters().insertAll(rows)

                var coverPath: String? = null
                parsed.cover?.let { bytes ->
                    // downscale large covers to keep the shelf smooth
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (opts.outWidth > 0) {
                        var sample = 1
                        while (opts.outWidth / (sample * 2) >= 360) sample *= 2
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        if (bmp != null) {
                            val f = File(dir, "cover.jpg")
                            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                            coverPath = f.absolutePath
                        }
                    }
                }
                val book = db.books().get(id)!!.copy(
                    chapterCount = rows.size, totalChars = start, coverPath = coverPath, ready = true
                )
                db.books().update(book)
                Result.Ok(book)
            } catch (e: Throwable) {
                db.books().delete(id)
                storage.deleteBook(id)
                Result.Fail("导入失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    suspend fun delete(bookId: Long) = withContext(Dispatchers.IO) {
        db.books().delete(bookId)
        db.chapters().deleteFor(bookId)
        db.bookmarks().deleteFor(bookId)
        storage.deleteBook(bookId)
    }
}
