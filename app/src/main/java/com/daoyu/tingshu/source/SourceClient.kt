package com.daoyu.tingshu.source

import com.daoyu.tingshu.tts.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

data class SearchResult(
    val source: BookSource,
    val name: String,
    val author: String,
    val intro: String,
    val kind: String,
    val lastChapter: String,
    val bookUrl: String,
    val coverUrl: String,
)

data class BookDetail(
    val name: String,
    val author: String,
    val intro: String,
    val tocUrl: String,
    val downloads: List<String>,
)

data class ChapterLink(val name: String, val url: String)

/** A fetched page: [url] is the final URL after redirects, used to resolve relative links. */
class Page(val url: String, val bytes: ByteArray, val contentType: String, val charset: String?) {
    val isJson: Boolean get() = contentType.contains("json") || text.trimStart().let { it.startsWith("{") || it.startsWith("[") }
    val text: String by lazy { String(bytes, charset?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: htmlCharset()) }

    private fun htmlCharset(): Charset {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val name = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType + " " + head)?.groupValues?.get(1)
        return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    fun ctx(): RuleEngine.Ctx =
        if (isJson) RuleEngine.Ctx.Json(Sources.json.parseToJsonElement(text))
        else RuleEngine.Ctx.Html(Jsoup.parse(ByteArrayInputStream(bytes), charset, url))
}

/** One request as the site actually answered it, kept so the source tester can show what came back. */
data class FetchRecord(
    val method: String,
    val url: String,
    val body: String?,
    val headers: Map<String, String>,
    val code: Int,
    val finalUrl: String,
    val contentType: String,
    val size: Int,
    val title: String,
    val preview: String,
    val error: String? = null,
    /** Redirects on the way, as "302 → next url". */
    val hops: List<String> = emptyList(),
) {
    fun describe(): String = buildString {
        append("$method $url\n")
        if (!body.isNullOrEmpty()) append("请求体：$body\n")
        if (headers.isNotEmpty()) append("请求头：" + headers.keys.joinToString("、") + "\n")
        if (hops.isNotEmpty()) {
            append("跳转 ${hops.size} 次：\n")
            (if (hops.size > 6) hops.take(3) + "…" + hops.takeLast(3) else hops).forEach { append("   $it\n") }
        }
        if (error != null) { append("没有连上：$error\n"); return@buildString }
        append("状态 $code · ${contentType.ifBlank { "未知类型" }} · ${size} 字节\n")
        if (finalUrl != url) append("跳转到：$finalUrl\n")
        if (title.isNotBlank()) append("页面标题：$title\n")
        append("内容开头：\n${preview.ifBlank { "（空）" }}")
    }

    companion object {
        const val PREVIEW = 400

        fun of(spec: SourceClient.UrlSpec, code: Int, page: Page, hops: List<String>): FetchRecord {
            val text = runCatching { page.text }.getOrDefault("")
            val html = !page.isJson && text.trimStart().startsWith("<")
            val doc = if (html) runCatching { Jsoup.parse(text) }.getOrNull() else null
            // for HTML the visible text says more than the markup; JSON is shown as is
            val shown = doc?.body()?.text()?.ifBlank { null } ?: text
            return FetchRecord(
                spec.method, spec.url, spec.body, spec.headers, code, page.url, page.contentType, page.bytes.size,
                doc?.title().orEmpty(), shown.replace(Regex("\\s+"), " ").trim().take(PREVIEW), hops = hops,
            )
        }
    }
}

class SourceClient(private val http: OkHttpClient = defaultClient) {
    /** Every request this client made, newest last; the source tester shows them when something fails. */
    val log = mutableListOf<FetchRecord>()
    /** The last page fetched, so a failed search can be explained rule by rule. */
    @Volatile var lastPage: Page? = null


    /** Parsed `url,{"method":"POST","body":"…","charset":"gbk","headers":{…}}` */
    data class UrlSpec(val url: String, val method: String = "GET", val body: String? = null, val charset: String? = null, val headers: Map<String, String> = emptyMap())

    fun buildUrl(source: BookSource, template: String, key: String = "", page: Int = 1): UrlSpec {
        val t = template.trim()
        val comma = Regex(",\\s*\\{").find(t)?.range?.first
        val rawUrl = if (comma != null) t.substring(0, comma) else t
        val opts = comma?.let { runCatching { Sources.json.parseToJsonElement(t.substring(it + 1)) as JsonObject }.getOrNull() }
        val charset = opts?.get("charset")?.jsonPrimitive?.contentOrNull
        val enc = charset?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        fun fill(s: String, encode: Boolean) = Regex("\\{\\{(.+?)\\}\\}").replace(s) { m ->
            val expr = m.groupValues[1].trim()
            when (expr) {
                "key", "searchKey" -> if (encode) URLEncoder.encode(key, enc.name()) else key
                "page", "searchPage" -> page.toString()
                else -> Arith.eval(expr, page)?.toString() ?: m.value
            }
        }
        val headers = buildMap {
            parseHeaders(source.header).forEach { (k, v) -> put(k, v) }
            (opts?.get("headers") as? JsonObject)?.forEach { (k, v) -> v.jsonPrimitive.contentOrNull?.let { put(k, it) } }
        }
        return UrlSpec(
            url = Urls.resolve(source.bookSourceUrl, fill(rawUrl, true)),
            method = opts?.get("method")?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET",
            body = opts?.get("body")?.let { b -> b.jsonPrimitive.contentOrNull ?: b.toString() }?.let { fill(it, true) },
            charset = charset,
            headers = headers,
        )
    }

    suspend fun fetch(spec: UrlSpec): Page = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(spec.url)
        rb.header("User-Agent", UA)
        spec.headers.forEach { (k, v) -> rb.header(k, v) }
        if (spec.method == "POST") {
            val body = spec.body ?: ""
            val type = spec.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                ?: if (body.trimStart().startsWith("{")) "application/json; charset=utf-8" else "application/x-www-form-urlencoded"
            rb.post(if (type.startsWith("application/x-www-form-urlencoded") && body.isEmpty()) FormBody.Builder().build() else body.toRequestBody(type.toMediaTypeOrNull()))
        }
        val hops = java.util.Collections.synchronizedList(mutableListOf<String>())
        val traced = http.newBuilder().addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).also { r -> if (r.isRedirect) hops += "${r.code} → ${r.header("Location") ?: "?"}" }
        }.build()
        val call = try {
            traced.newCall(rb.build()).execute()
        } catch (e: java.io.IOException) {
            val msg = e.message ?: e.javaClass.simpleName
            synchronized(log) { log += FetchRecord(spec.method, spec.url, spec.body, spec.headers, 0, spec.url, "", 0, "", "", msg, hops.toList()) }
            if (msg.startsWith("Too many follow-up requests")) throw SourceException("网站一直在跳转（转了 ${hops.size} 次还没到头）：通常是要先登录或通过人机验证，或者网站不接受 App 发出的请求")
            throw e
        }
        call.use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val ct = resp.header("Content-Type") ?: ""
            val page = Page(resp.request.url.toString(), bytes, ct, spec.charset ?: resp.body?.contentType()?.charset()?.name())
            synchronized(log) { log += FetchRecord.of(spec, resp.code, page, hops.toList()) }
            lastPage = page
            if (!resp.isSuccessful) throw SourceException("网站返回 ${resp.code}：${spec.url}")
            page
        }
    }

    suspend fun fetchUrl(source: BookSource, url: String): Page = fetch(buildUrl(source, url).let { if (it.url.isEmpty()) it.copy(url = url) else it })

    suspend fun search(source: BookSource, key: String, page: Int = 1): List<SearchResult> {
        if (source.searchUrl.isBlank()) throw SourceException("「${source.bookSourceName}」没有填写搜索地址")
        val first = parseSearch(source, fetch(buildUrl(source, source.searchUrl, key, page)))
        if (first.isNotEmpty()) return first
        // many sites (e.g. Gutenberg's Chinese classics) only list one script: retry in the other one
        val other = ChineseScript.other(key) ?: return first
        return parseSearch(source, fetch(buildUrl(source, source.searchUrl, other, page)))
    }

    fun parseSearch(source: BookSource, p: Page): List<SearchResult> {
        val eng = RuleEngine(p.url)
        val r = source.ruleSearch
        return eng.elements(p.ctx(), r.bookList).mapNotNull { item ->
            val name = eng.string(item, r.name)
            if (name.isBlank()) return@mapNotNull null
            SearchResult(
                source = source,
                name = name,
                author = eng.string(item, r.author).removePrefix("作者：").removePrefix("作者:").trim(),
                intro = eng.string(item, r.intro),
                kind = eng.string(item, r.kind),
                lastChapter = eng.string(item, r.lastChapter),
                bookUrl = eng.strings(item, r.bookUrl).firstOrNull()?.let { Urls.resolve(p.url, it) } ?: p.url,
                coverUrl = eng.strings(item, r.coverUrl).firstOrNull()?.let { Urls.resolve(p.url, it) } ?: "",
            )
        }.distinctBy { it.bookUrl + "|" + it.name }
    }

    /** Why [parseSearch] found nothing on [p]: which of the list / name rules came up empty. */
    fun explainSearch(source: BookSource, p: Page): String {
        val eng = RuleEngine(p.url)
        val r = source.ruleSearch
        val ctx = runCatching { p.ctx() }.getOrElse { return "返回内容按${if (p.isJson) " JSON" else "网页"}解析失败：${it.message}" }
        val kind = if (p.isJson) "JSON" else "网页"
        if (r.bookList.isBlank()) return "「结果列表」规则是空的"
        val items = runCatching { eng.elements(ctx, r.bookList) }.getOrElse { return "「结果列表」规则出错：${it.message}" }
        if (items.isEmpty()) return "返回的是$kind，「结果列表」${r.bookList} 一个都没匹配到"
        val named = items.count { eng.string(it, r.name).isNotBlank() }
        return "「结果列表」匹配到 ${items.size} 个，其中 $named 个取到了书名" +
            if (named == 0) "：「书名」${r.name} 不对" else ""
    }

    suspend fun detail(result: SearchResult): BookDetail {
        val p = fetchUrl(result.source, result.bookUrl)
        return parseDetail(result, p)
    }

    fun parseDetail(result: SearchResult, p: Page): BookDetail {
        val eng = RuleEngine(p.url)
        val r = result.source.ruleBookInfo
        val ctx = p.ctx()
        return BookDetail(
            name = eng.string(ctx, r.name).ifBlank { result.name },
            author = eng.string(ctx, r.author).removePrefix("作者：").removePrefix("作者:").trim().ifBlank { result.author },
            intro = eng.string(ctx, r.intro, keepLines = true).ifBlank { result.intro },
            tocUrl = eng.strings(ctx, r.tocUrl).firstOrNull()?.let { Urls.resolve(p.url, it) } ?: p.url,
            downloads = eng.strings(ctx, r.downloadUrls).map { Urls.resolve(p.url, it) }.distinct(),
        )
    }

    /** Chapter list, following [TocRule.nextTocUrl] for paged lists. */
    suspend fun toc(source: BookSource, tocUrl: String, firstPage: Page? = null): List<ChapterLink> {
        val seen = mutableSetOf<String>()
        val out = LinkedHashMap<String, ChapterLink>()
        var url: String? = tocUrl
        var page = firstPage?.takeIf { it.url == tocUrl }
        while (url != null && seen.add(url) && seen.size <= MAX_TOC_PAGES) {
            val p = page ?: fetchUrl(source, url)
            page = null
            val (chapters, next) = parseToc(source, p)
            chapters.forEach { out.putIfAbsent(it.url, it) }
            url = next.firstOrNull { it !in seen }
        }
        return out.values.toList()
    }

    fun parseToc(source: BookSource, p: Page): Pair<List<ChapterLink>, List<String>> {
        val eng = RuleEngine(p.url)
        val r = source.ruleToc
        val ctx = p.ctx()
        val list = eng.elements(ctx, r.chapterList).mapNotNull { item ->
            val name = eng.string(item, r.chapterName.ifBlank { "text" })
            val url = eng.strings(item, r.chapterUrl.ifBlank { "href||a@href" }).firstOrNull()?.let { Urls.resolve(p.url, it) }
            if (name.isBlank() || url.isNullOrBlank()) null else ChapterLink(name, url)
        }
        val next = if (r.nextTocUrl.isBlank()) emptyList() else eng.strings(ctx, r.nextTocUrl).map { Urls.resolve(p.url, it) }.filter { it != p.url }
        return list to next
    }

    /** One chapter's text, following [ContentRule.nextContentUrl] while it doesn't lead to [nextChapterUrl]. */
    suspend fun content(source: BookSource, url: String, nextChapterUrl: String? = null): String {
        val parts = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var cur: String? = url
        while (cur != null && seen.add(cur) && seen.size <= MAX_CONTENT_PAGES) {
            val p = fetchUrl(source, cur)
            val (text, next) = parseContent(source, p)
            parts += text
            cur = next.firstOrNull { it !in seen && it != nextChapterUrl && it.substringBefore('#') != nextChapterUrl }
        }
        return parts.filter { it.isNotBlank() }.joinToString("\n")
    }

    fun parseContent(source: BookSource, p: Page): Pair<String, List<String>> {
        val eng = RuleEngine(p.url)
        val r = source.ruleContent
        val ctx = p.ctx()
        var text = eng.string(ctx, r.content.ifBlank { "body@html" }, keepLines = true)
        if (r.replaceRegex.isNotBlank()) {
            val parts = r.replaceRegex.split("##").drop(if (r.replaceRegex.startsWith("##")) 1 else 0)
            parts.chunked(2).forEach { pair ->
                runCatching { Regex(pair[0]) }.getOrNull()?.let { text = it.replace(text, pair.getOrElse(1) { "" }) }
            }
        }
        val next = if (r.nextContentUrl.isBlank()) emptyList() else eng.strings(ctx, r.nextContentUrl).map { Urls.resolve(p.url, it) }.filter { it != p.url }
        return text.lines().map { it.trim(' ', '\t', '　', ' ') }.filter { it.isNotEmpty() }.joinToString("\n") to next
    }

    /** Downloads [url] into [dest]; returns the file extension (txt / epub) or null when it's neither. */
    suspend fun download(source: BookSource, url: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): String? = withContext(Dispatchers.IO) {
        val spec = buildUrl(source, url)
        val rb = Request.Builder().url(spec.url).header("User-Agent", UA)
        spec.headers.forEach { (k, v) -> rb.header(k, v) }
        http.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw SourceException("下载失败：网站返回 ${resp.code}")
            val body = resp.body ?: throw SourceException("下载失败：内容为空")
            val total = body.contentLength()
            body.byteStream().use { inp ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            val disposition = resp.header("Content-Disposition") ?: ""
            formatOf(disposition, resp.request.url.encodedPath, resp.header("Content-Type") ?: "", dest)
        }
    }

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        const val MAX_TOC_PAGES = 100
        const val MAX_CONTENT_PAGES = 20

        val defaultClient: OkHttpClient by lazy {
            Http.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).followRedirects(true).followSslRedirects(true)
                .addNetworkInterceptor(SiteCookies).build()
        }

        fun parseHeaders(s: String): Map<String, String> {
            if (s.isBlank()) return emptyMap()
            val obj = runCatching { Sources.json.parseToJsonElement(s) as JsonObject }.getOrNull() ?: return emptyMap()
            return obj.mapNotNull { (k, v) -> runCatching { v.jsonPrimitive.contentOrNull }.getOrNull()?.let { k to it } }.toMap()
        }

        /** Works out txt / epub from the response; EPUB is a zip, so the magic bytes settle it when headers don't. */
        fun formatOf(disposition: String, path: String, contentType: String, file: File): String? {
            val names = listOf(Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1) ?: "", path)
            for (n in names) {
                val low = java.net.URLDecoder.decode(n, "UTF-8").lowercase()
                if (low.endsWith(".epub") || low.contains(".epub")) return "epub"
                if (low.endsWith(".txt")) return "txt"
            }
            val ct = contentType.lowercase()
            if (ct.contains("epub")) return "epub"
            val head = file.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
            if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return "epub"
            if (ct.startsWith("text/plain")) return "txt"
            return null
        }
    }
}

/** Simplified ↔ traditional via Android's ICU transliterators (API 29+); null when nothing changes or ICU lacks them. */
internal object ChineseScript {
    private val toHant by lazy { runCatching { android.icu.text.Transliterator.getInstance("Hans-Hant") }.getOrNull() }
    private val toHans by lazy { runCatching { android.icu.text.Transliterator.getInstance("Hant-Hans") }.getOrNull() }

    fun other(s: String): String? {
        if (android.os.Build.VERSION.SDK_INT < 29 || s.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return null
        val hant = runCatching { toHant?.transliterate(s) }.getOrNull()
        if (hant != null && hant != s) return hant
        val hans = runCatching { toHans?.transliterate(s) }.getOrNull()
        return hans?.takeIf { it != s }
    }
}

class SourceException(message: String) : Exception(message)

/** Integer arithmetic for search URLs like {{(page-1)*20}}. */
internal object Arith {
    fun eval(expr: String, page: Int): Int? = runCatching {
        val s = expr.replace("searchPage", "page").replace("page", page.toString()).replace(" ", "")
        if (!s.all { it.isDigit() || it in "+-*/()" }) return null
        var i = 0
        fun parseExpr(): Int {
            fun factor(): Int {
                if (s[i] == '(') { i++; val v = parseExpr(); i++; return v }
                if (s[i] == '-') { i++; return -factor() }
                val st = i
                while (i < s.length && s[i].isDigit()) i++
                return s.substring(st, i).toInt()
            }
            fun term(): Int {
                var v = factor()
                while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = factor(); v = if (op == '*') v * r else v / r }
                return v
            }
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        parseExpr().takeIf { i == s.length }
    }.getOrNull()
}

/**
 * Keeps the cookies sites hand out (session ids, "you passed the check" markers) for as long as the app runs, the way
 * a browser would; many sites redirect in circles until they get them back. Lives in a network interceptor rather
 * than a CookieJar so a Cookie the user wrote into the source's headers is merged instead of replaced, and wins.
 */
internal object SiteCookies : okhttp3.Interceptor {
    private val jar = mutableListOf<okhttp3.Cookie>()

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        val now = System.currentTimeMillis()
        val stored = synchronized(jar) { jar.removeAll { it.expiresAt < now }; jar.filter { it.matches(req.url) } }
        val own = req.header("Cookie").orEmpty()
        val ownNames = own.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.toSet()
        val merged = (listOf(own).filter { it.isNotBlank() } + stored.filter { it.name !in ownNames }.map { "${it.name}=${it.value}" }).joinToString("; ")
        val resp = chain.proceed(if (merged.isEmpty()) req else req.newBuilder().header("Cookie", merged).build())
        val fresh = okhttp3.Cookie.parseAll(req.url, resp.headers)
        if (fresh.isNotEmpty()) synchronized(jar) {
            for (c in fresh) {
                jar.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                if (c.expiresAt >= now) jar += c
            }
        }
        return resp
    }

    fun clear() = synchronized(jar) { jar.clear() }
}
