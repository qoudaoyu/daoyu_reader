package com.daoyu.tingshu.source

import com.daoyu.tingshu.tts.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 「AI 生成书源」: from a site address and one book title, writes the rules for the user.
 *
 * Each stage fetches the real page, shows the model a slimmed-down copy, asks for the rules as JSON, then
 * runs them with [SourceClient] and feeds any failure back for another try. Nothing is saved unless the
 * search actually finds the book.
 */
class RuleWriter(
    private val client: SourceClient = SourceClient(),
    /** prompt → JSON reply. Production calls 千问 with the user's Aliyun key; tests pass a fake. */
    private val llm: suspend (String) -> String,
    /** A chapter shorter than this means the content rule picked the wrong element. */
    private val minChapter: Int = MIN_CHAPTER,
) {
    class Failed(message: String, val partial: BookSource?) : Exception(message)

    suspend fun write(siteUrl: String, keyword: String, onStep: (String) -> Unit): BookSource {
        val site = normalise(siteUrl)
        var src = BookSource(bookSourceUrl = site, bookSourceName = site.substringAfter("://").substringBefore('/'), bookSourceGroup = "AI 生成")

        // 1. how to search
        onStep("打开网站首页…")
        val home = client.fetchUrl(src, site)
        val homeDoc = Jsoup.parse(home.text, home.url)
        homeDoc.title().takeIf { it.isNotBlank() }?.let { t -> src = src.copy(bookSourceName = t.split(Regex("[-_|｜—]")).first().trim().take(20).ifBlank { src.bookSourceName }) }
        val gbk = Regex("charset=[\"']?(gb[\\w-]*)", RegexOption.IGNORE_CASE).find(home.text.take(4000))?.groupValues?.get(1)?.lowercase()
        val searchUrl = searchFormUrl(homeDoc, home.url, gbk) ?: run {
            onStep("首页没找到搜索框，让 AI 判断搜索地址…")
            ask(searchUrlPrompt(slim(homeDoc), home.url)).string("searchUrl")
        }
        src = src.copy(searchUrl = searchUrl.ifBlank {
            onStep("试常见的搜索地址…")
            probeSearch(src, keyword) ?: throw Failed("没找到这个网站的搜索方式（首页没有搜索框，常见的搜索地址也都不对）", src)
        })

        // 2. search results
        onStep("用「$keyword」搜索…")
        val page = client.fetch(client.buildUrl(src, src.searchUrl, keyword))
        var feedback = ""
        var results: List<SearchResult> = emptyList()
        for (attempt in 1..TRIES) {
            onStep(if (attempt == 1) "AI 分析搜索结果页…" else "规则没取到书，AI 第 $attempt 次重写…")
            val o = ask(searchPrompt(sample(page, keyword), keyword, feedback))
            src = src.copy(ruleSearch = SearchRule(
                bookList = o.string("bookList"), name = o.string("name"), author = o.string("author"),
                bookUrl = o.string("bookUrl"), coverUrl = o.string("coverUrl"), intro = o.string("intro"),
            ))
            results = runCatching { client.parseSearch(src, page) }.getOrDefault(emptyList())
            feedback = when {
                results.isEmpty() -> "上次的规则一条结果都没取到（bookList 或 name 不对）。"
                results.none { matches(it.name, keyword) } -> "上次取到 ${results.size} 条，但书名都不含「$keyword」，前几个是：${results.take(3).joinToString { it.name }}。"
                else -> break
            }
        }
        val hit = results.firstOrNull { matches(it.name, keyword) } ?: throw Failed("搜索结果规则没写成功：$feedback", src)
        onStep("找到《${hit.name}》，打开详情页…")

        // 3. detail page: a file to download, or a chapter list
        val detailPage = client.fetchUrl(src, hit.bookUrl)
        feedback = ""
        var detail: BookDetail? = null
        for (attempt in 1..TRIES) {
            onStep(if (attempt == 1) "AI 分析详情页…" else "AI 第 $attempt 次分析详情页…")
            val o = ask(detailPrompt(sample(detailPage, hit.name), hit.name, feedback))
            val file = o.string("type") == "file"
            src = src.copy(
                bookSourceType = if (file) BookSource.TYPE_FILE else BookSource.TYPE_TEXT,
                ruleBookInfo = BookInfoRule(downloadUrls = if (file) o.string("downloadUrls") else "", tocUrl = if (file) "" else o.string("tocUrl"), intro = o.string("intro")),
            )
            val d = runCatching { client.parseDetail(hit.copy(source = src), detailPage) }.getOrNull()
            feedback = when {
                d == null -> "规则出错了。"
                file && d.downloads.isEmpty() -> "上次的 downloadUrls 没取到任何链接。"
                else -> { detail = d; break }
            }
        }
        val d = detail ?: throw Failed("详情页规则没写成功：$feedback", src)
        if (src.isFile) { onStep("找到 ${d.downloads.size} 个下载链接"); return src }

        // 4. chapter list
        onStep("打开目录…")
        val tocPage = if (d.tocUrl == detailPage.url) detailPage else client.fetchUrl(src, d.tocUrl)
        feedback = ""
        var chapters: List<ChapterLink> = emptyList()
        for (attempt in 1..TRIES) {
            onStep(if (attempt == 1) "AI 分析目录…" else "AI 第 $attempt 次分析目录…")
            val o = ask(tocPrompt(sample(tocPage, null), feedback))
            src = src.copy(ruleToc = TocRule(chapterList = o.string("chapterList"), chapterName = o.string("chapterName"), chapterUrl = o.string("chapterUrl"), nextTocUrl = o.string("nextTocUrl")))
            chapters = runCatching { client.parseToc(src, tocPage).first }.getOrDefault(emptyList())
            feedback = if (chapters.size < 2) "上次只取到 ${chapters.size} 章。" else break
        }
        if (chapters.size < 2) throw Failed("目录规则没写成功：$feedback", src)
        onStep("目录 ${chapters.size} 章，分析正文…")

        // 5. chapter text
        val chPage = client.fetchUrl(src, chapters.first().url)
        feedback = ""
        for (attempt in 1..TRIES) {
            if (attempt > 1) onStep("AI 第 $attempt 次分析正文…")
            val o = ask(contentPrompt(sample(chPage, null), feedback))
            src = src.copy(ruleContent = ContentRule(content = o.string("content"), nextContentUrl = o.string("nextContentUrl"), replaceRegex = o.string("replaceRegex")))
            val text = runCatching { client.parseContent(src, chPage).first }.getOrDefault("")
            feedback = if (text.length < minChapter) "上次正文只取到 ${text.length} 字：${text.take(60)}" else { onStep("第一章取到 ${text.length} 字"); return src }
        }
        throw Failed("正文规则没写成功：$feedback", src)
    }

    // ---------------------------------------------------------------- pages

    /** A GET or POST search form on the home page, as a search URL template. */
    internal fun searchFormUrl(doc: Document, pageUrl: String, pageCharset: String? = null): String? {
        val inputSel = "input[type=search], input[name~=(?i)^(q|s|k|kw|wd|key|keys|keyword|keywords|word|query|search|searchkey|searchword|name|bookname)$], input[type=text]"
        val form = doc.select("form").firstOrNull { it.selectFirst(inputSel) != null } ?: return null
        val box = form.selectFirst(inputSel) ?: return null
        if (box.attr("name").isBlank()) return null
        val params = form.select("input[type=hidden][name]").map { it.attr("name") to it.attr("value") } + (box.attr("name") to KEY)
        val query = params.joinToString("&") { (k, v) -> enc(k) + "=" + if (v == KEY) "{{key}}" else enc(v) }
        val action = Urls.resolve(pageUrl, form.attr("action").ifBlank { pageUrl }).substringBefore('#')
        val charset = form.attr("accept-charset").lowercase().takeIf { it.startsWith("gb") } ?: pageCharset
        val post = form.attr("method").equals("post", true)
        val opts = buildJsonObject {
            if (post) { put("method", "POST"); put("body", query) }
            charset?.let { put("charset", it) }
        }
        val url = if (post) action else action + (if ('?' in action) "&" else "?") + query
        return if (opts.isEmpty()) url else "$url,$opts"
    }

    /** Common search URL shapes; the first whose page links to the book wins. */
    private suspend fun probeSearch(src: BookSource, keyword: String): String? {
        for (t in PROBES) {
            val p = runCatching { client.fetch(client.buildUrl(src, t, keyword)) }.getOrNull() ?: continue
            if (p.isJson) { if (p.text.contains(keyword)) return t else continue }
            val doc = Jsoup.parse(p.text, p.url)
            if (doc.select("a").any { matches(it.text(), keyword) }) return t
        }
        return null
    }

    /** What the model sees: JSON as is (cut), HTML without scripts, styles and most attributes, cut around [focus]. */
    private fun sample(p: Page, focus: String?): String {
        if (p.isJson) return "这是 JSON 接口的返回：\n" + p.text.take(LIMIT)
        return "这是网页 HTML（已精简）：\n" + slim(Jsoup.parse(p.text, p.url), focus)
    }

    internal fun slim(doc: Document, focus: String? = null): String {
        val d = doc.clone()
        d.select("script, style, noscript, svg, iframe, link, meta, head").remove()
        d.getAllElements().forEach { el ->
            el.childNodes().filterIsInstance<Comment>().forEach { it.remove() }
            val keep = el.attributes().filter { it.key in KEEP_ATTRS }
            el.clearAttributes()
            keep.forEach { el.attr(it.key, it.value.take(120)) }
        }
        val html = d.body().html().replace(Regex("\\n\\s*\\n+"), "\n").replace(Regex("[ \\t]{2,}"), " ")
        if (html.length <= LIMIT) return html
        // keep the part around the book title, where the results are
        val at = focus?.let { f -> listOf(f, ChineseScript.other(f)).filterNotNull().map { html.indexOf(it) }.firstOrNull { it >= 0 } } ?: -1
        val start = if (at < 0) 0 else (at - LIMIT / 3).coerceAtLeast(0)
        return html.substring(start, (start + LIMIT).coerceAtMost(html.length))
    }

    private fun matches(name: String, keyword: String) =
        name.contains(keyword, true) || ChineseScript.other(keyword)?.let { name.contains(it, true) } == true

    // ---------------------------------------------------------------- prompts

    private val syntax = """
规则语法（不要用 JavaScript）：
- CSS 选择器后接 @ 取值：h3.title a@text、a.cover@href、img@src、img@data-src、#content@html
  取值：text 文字、ownText 自身文字、html 正文（保留分段）、href/src 或任意属性名
- 多个规则用 || 连接表示前一个取不到就用后一个
- 规则##正则 表示把匹配正则的文字删掉，如 p.author@text##作者[:：]\s*
- JSON 用 JSONPath：$.data.list[*]、$.name；需要拼接时写 /book/{{$.id}}.html
- 列表规则前加 - 表示倒序
- 结果列表/章节列表规则匹配的是「每一项的外层元素」，其余规则在这一项内部取值
- class 名选有意义、稳定的，不要用像 css-1x9a7k 这种自动生成的
只回答一个 JSON 对象，不要解释。""".trimIndent()

    private fun searchUrlPrompt(html: String, url: String) = """
你在帮一个阅读 App 写网站的搜索规则。这是网站首页 $url 的 HTML。请找出搜索书籍的地址。
用 {{key}} 表示关键词，例如 /search?q={{key}}。POST 请求写成 /search.php,{"method":"POST","body":"searchkey={{key}}"}；GBK 编码的网站在花括号里加 "charset":"gbk"。
用 JSON 回答 {"searchUrl": "..."}，找不到就回答 {"searchUrl": ""}。

$html""".trim()

    private fun searchPrompt(page: String, keyword: String, feedback: String) = """
你在帮一个阅读 App 写网站的搜索规则。下面是用「$keyword」搜索后的结果页。
请写出规则，回答 {"bookList":"", "name":"", "author":"", "bookUrl":"", "coverUrl":"", "intro":""}（取不到的字段留空字符串；bookList、name、bookUrl 必填）。
$syntax
${if (feedback.isNotEmpty()) "注意：$feedback 请换一种写法。\n" else ""}
$page""".trim()

    private fun detailPrompt(page: String, title: String, feedback: String) = """
你在帮一个阅读 App 写网站规则。下面是《$title》的详情页。
如果页面上有 TXT 或 EPUB 文件的下载链接，回答 {"type":"file", "downloadUrls":"取下载链接的规则（EPUB 优先，可用 || 连接）", "intro":"简介规则"}。
如果书是按章节在线阅读的，回答 {"type":"text", "tocUrl":"「全部章节/目录」页链接的规则，目录就在本页时留空", "intro":"简介规则"}。
$syntax
${if (feedback.isNotEmpty()) "注意：$feedback 请换一种写法。\n" else ""}
$page""".trim()

    private fun tocPrompt(page: String, feedback: String) = """
你在帮一个阅读 App 写网站规则。下面是一本书的目录页。
回答 {"chapterList":"每一章对应元素的规则", "chapterName":"章节名，列表项就是 a 标签时留空", "chapterUrl":"章节链接，列表项就是 a 标签时留空", "nextTocUrl":"目录有下一页时取下一页链接，没有留空"}。
有「最新章节」和「全部章节」两块时，只取「全部章节」那一块，不要重复。
$syntax
${if (feedback.isNotEmpty()) "注意：$feedback 请换一种写法。\n" else ""}
$page""".trim()

    private fun contentPrompt(page: String, feedback: String) = """
你在帮一个阅读 App 写网站规则。下面是一本书某一章的正文页。
回答 {"content":"正文的规则，用 @html 保留分段", "nextContentUrl":"本章分多页时取「下一页」链接（不是下一章），否则留空", "replaceRegex":"正文里夹带的广告、网址、提示语，写成 ##正则##正则 的形式，没有留空"}。
$syntax
${if (feedback.isNotEmpty()) "注意：$feedback 请换一种写法。\n" else ""}
$page""".trim()

    private suspend fun ask(prompt: String): JsonObject {
        val reply = llm(prompt)
        val a = reply.indexOf('{'); val b = reply.lastIndexOf('}')
        val json = if (a >= 0 && b > a) reply.substring(a, b + 1) else reply
        return runCatching { Sources.json.parseToJsonElement(json).jsonObject }.getOrElse { throw Failed("AI 回复看不懂：${reply.take(80)}", null) }
    }

    private fun JsonObject.string(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty().trim()

    companion object {
        const val TRIES = 3
        private val PROBES = listOf("/search?q={{key}}", "/search?keyword={{key}}", "/search?kw={{key}}", "/s?q={{key}}", "/?s={{key}}",
            "/search.php?searchkey={{key}}", "/search.html?searchkey={{key}}", "/search/{{key}}", "/s/{{key}}")
        internal const val SYSTEM = "你帮阅读 App 给网站写书源规则。只回答一个 json 对象，不要解释。"
        const val LIMIT = 24_000
        const val MIN_CHAPTER = 200
        private const val KEY = "\u0000key"
        private val KEEP_ATTRS = setOf("class", "id", "href", "src", "data-src", "data-original", "type", "name", "title", "action")

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun normalise(u: String): String {
            val t = u.trim().trimEnd('/')
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }

        private val qwenClient by lazy { Http.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build() }

        /** 千问 call with the user's Aliyun key (the one CosyVoice and 角色分析 use). Logged as 角色分析 usage. */
        suspend fun qwen(key: String, onUsage: suspend (Long, Long) -> Unit = { _, _ -> }): suspend (String) -> String = { prompt ->
            withContext(Dispatchers.IO) {
                if (key.isBlank()) throw Failed("需要先在设置里填写阿里云 API Key（和 CosyVoice、角色分析共用）", null)
                val body = buildJsonObject {
                    put("model", "qwen3.8-max")
                    putJsonArray("messages") {
                        // json_object mode is refused unless the messages say "json" somewhere
                        addJsonObject { put("role", "system"); put("content", SYSTEM) }
                        addJsonObject { put("role", "user"); put("content", prompt) }
                    }
                    putJsonObject("response_format") { put("type", "json_object") }
                    put("enable_thinking", false)
                }.toString()
                val req = Request.Builder().url("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                qwenClient.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    val o = runCatching { Sources.json.parseToJsonElement(text).jsonObject }.getOrNull()
                    if (!r.isSuccessful || o == null) {
                        val msg = o?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: text.take(120)
                        throw Failed("千问调用失败（${r.code}）：$msg", null)
                    }
                    o["usage"]?.jsonObject?.let { u -> onUsage(u["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: 0, u["completion_tokens"]?.jsonPrimitive?.longOrNull ?: 0) }
                    o["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                }
            }
        }
    }
}
