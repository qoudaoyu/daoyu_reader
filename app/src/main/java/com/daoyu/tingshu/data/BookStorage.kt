package com.daoyu.tingshu.data

import android.content.Context
import java.io.File

/** Files of one book live in filesDir/books/{id}/ : source.*, cover.jpg, chapters/{idx}.txt */
class BookStorage(context: Context) {
    private val root = File(context.filesDir, "books").apply { mkdirs() }
    val tmpDir = File(context.cacheDir, "import").apply { mkdirs() }

    fun dir(bookId: Long) = File(root, bookId.toString())
    fun chapterFile(bookId: Long, idx: Int) = File(dir(bookId), "chapters/$idx.txt")
    fun sourceFile(book: Book) = File(dir(book.id), "source." + book.format)

    fun readChapter(bookId: Long, idx: Int): String {
        val f = chapterFile(bookId, idx)
        return if (f.exists()) f.readText() else ""
    }

    fun deleteBook(bookId: Long) {
        dir(bookId).deleteRecursively()
    }
}
