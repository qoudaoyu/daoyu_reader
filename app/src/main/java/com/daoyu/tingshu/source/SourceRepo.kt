package com.daoyu.tingshu.source

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** User book sources, kept as a Legado-style JSON array in filesDir/book_sources.json. */
class SourceRepo(context: Context) {
    private val file = File(context.filesDir, "book_sources.json")
    private val _sources = MutableStateFlow(load())
    val sources: StateFlow<List<BookSource>> = _sources

    private fun load(): List<BookSource> {
        if (!file.exists()) return Sources.builtin.also { write(it) }
        return runCatching { Sources.parse(file.readText()) }.getOrElse { emptyList() }
    }

    private fun write(list: List<BookSource>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Sources.export(list))
        tmp.renameTo(file)
    }

    @Synchronized private fun set(list: List<BookSource>) {
        write(list)
        _sources.value = list
    }

    fun get(url: String) = _sources.value.firstOrNull { it.bookSourceUrl == url }

    /** Adds or replaces (by URL). [oldUrl] lets an edit change a source's URL. */
    fun save(source: BookSource, oldUrl: String? = null) {
        val key = oldUrl ?: source.bookSourceUrl
        // an edit that renames onto another source's URL replaces that one
        val list = _sources.value.filterNot { it.bookSourceUrl == source.bookSourceUrl && it.bookSourceUrl != key }.toMutableList()
        val i = list.indexOfFirst { it.bookSourceUrl == key }
        if (i >= 0) list[i] = source else list += source
        set(list)
    }

    /** Pasted JSON (one source or an array). Returns how many were added or updated. */
    fun importJson(text: String): Int {
        val incoming = Sources.parse(text)
        val list = _sources.value.toMutableList()
        for (s in incoming) {
            val i = list.indexOfFirst { it.bookSourceUrl == s.bookSourceUrl }
            if (i >= 0) list[i] = s else list += s
        }
        set(list)
        return incoming.size
    }

    fun delete(url: String) = set(_sources.value.filterNot { it.bookSourceUrl == url })

    fun setEnabled(url: String, enabled: Boolean) = set(_sources.value.map { if (it.bookSourceUrl == url) it.copy(enabled = enabled) else it })

    fun restoreBuiltin() {
        val list = _sources.value.toMutableList()
        Sources.builtin.forEach { b -> if (list.none { it.bookSourceUrl == b.bookSourceUrl }) list += b }
        set(list)
    }
}
