package com.daoyu.tingshu.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * A user-defined book source. Field names follow 阅读 (Legado) so that sources written for it can be
 * pasted in directly, as long as their rules don't use JavaScript (see [RuleEngine] for what's supported).
 *
 * Two kinds:
 *  - [TYPE_TEXT]: the site shows a book as a chapter list; we fetch every chapter and build a book from them.
 *  - [TYPE_FILE]: the detail page links to TXT / EPUB files ([BookInfoRule.downloadUrls]); we download one.
 */
@Serializable
data class BookSource(
    val bookSourceUrl: String = "",
    val bookSourceName: String = "",
    val bookSourceGroup: String = "",
    val bookSourceType: Int = TYPE_TEXT,
    val bookSourceComment: String = "",
    val enabled: Boolean = true,
    /** JSON object of extra request headers, e.g. {"User-Agent":"…"} */
    val header: String = "",
    /** Relative or absolute; {{key}} and {{page}} are substituted. Options may follow: url,{"method":"POST","body":"q={{key}}","charset":"gbk"} */
    val searchUrl: String = "",
    val ruleSearch: SearchRule = SearchRule(),
    val ruleBookInfo: BookInfoRule = BookInfoRule(),
    val ruleToc: TocRule = TocRule(),
    val ruleContent: ContentRule = ContentRule(),
    val customOrder: Int = 0,
) {
    val isFile get() = bookSourceType == TYPE_FILE || ruleBookInfo.downloadUrls.isNotBlank()
    val usesJs get() = listOf(searchUrl, ruleSearch.bookList, ruleSearch.name, ruleSearch.bookUrl, ruleBookInfo.downloadUrls,
        ruleBookInfo.tocUrl, ruleToc.chapterList, ruleToc.chapterUrl, ruleContent.content)
        .any { it.contains("<js>") || it.contains("@js:") || it.trimStart().startsWith("{{") }

    companion object {
        const val TYPE_TEXT = 0
        const val TYPE_FILE = 3
    }
}

@Serializable
data class SearchRule(
    val bookList: String = "",
    val name: String = "",
    val author: String = "",
    val intro: String = "",
    val kind: String = "",
    val lastChapter: String = "",
    val bookUrl: String = "",
    val coverUrl: String = "",
)

@Serializable
data class BookInfoRule(
    val name: String = "",
    val author: String = "",
    val intro: String = "",
    /** Where the chapter list is; empty = the detail page itself */
    val tocUrl: String = "",
    /** File-type sources: links to downloadable TXT / EPUB files */
    val downloadUrls: String = "",
)

@Serializable
data class TocRule(
    val chapterList: String = "",
    val chapterName: String = "",
    val chapterUrl: String = "",
    val nextTocUrl: String = "",
)

@Serializable
data class ContentRule(
    val content: String = "",
    val nextContentUrl: String = "",
    /** Legado-style cleanup: ##regex##replacement##regex2 … */
    val replaceRegex: String = "",
)

object Sources {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true; isLenient = true; coerceInputValues = true }

    /** Parses one source or an array of them (Legado export format). Sources without a URL are dropped. */
    fun parse(text: String): List<BookSource> {
        val el = json.parseToJsonElement(text.trim())
        val objs = when (el) {
            is JsonArray -> el.map { it.jsonObject }
            is JsonObject -> listOf(el)
            else -> emptyList()
        }
        return objs.map { json.decodeFromJsonElement<BookSource>(it) }.filter { it.bookSourceUrl.isNotBlank() }
    }

    fun export(list: List<BookSource>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(BookSource.serializer()), list)

    /** Public-domain example, also a template for writing your own. */
    val builtin = listOf(
        BookSource(
            bookSourceUrl = "https://www.gutenberg.org",
            bookSourceName = "古腾堡计划（公版书）",
            bookSourceGroup = "示例",
            bookSourceType = BookSource.TYPE_FILE,
            bookSourceComment = "Project Gutenberg：版权已过期的公版书，有少量中文古籍（可搜「红楼梦」「西游记」）。下载 EPUB，没有时下载 TXT。",
            searchUrl = "/ebooks/search/?query={{key}}",
            ruleSearch = SearchRule(
                bookList = "li.booklink",
                name = "span.title@text",
                author = "span.subtitle@text",
                bookUrl = "a.link@href",
                coverUrl = "img.cover-thumb@src",
            ),
            ruleBookInfo = BookInfoRule(
                downloadUrls = "a[type^=application/epub]@href||a[type^=text/plain]@href",
            ),
        ),
    )
}
