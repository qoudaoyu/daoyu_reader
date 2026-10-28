package com.daoyu.tingshu

import com.daoyu.tingshu.data.BookImporter
import com.daoyu.tingshu.source.BookInfoRule
import com.daoyu.tingshu.source.BookSource
import com.daoyu.tingshu.source.ContentRule
import com.daoyu.tingshu.source.Page
import com.daoyu.tingshu.source.RuleEngine
import com.daoyu.tingshu.source.SearchRule
import com.daoyu.tingshu.source.SourceClient
import com.daoyu.tingshu.source.SourceDownloads
import com.daoyu.tingshu.source.Sources
import com.daoyu.tingshu.source.TocRule
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Book sources: rule syntax, Legado import, and an end-to-end search → download → import against a local fake site. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookSourceTest {

    // ---------------------------------------------------------------- rules

    private val html = """
        <html><body>
        <div class="list">
          <div class="item"><h3><a href="/book/1">长安夜雨</a></h3><span class="author">作者：李白</span><p class="intro">一段简介</p></div>
          <div class="item"><h3><a href="/book/2">江湖远</a></h3><span class="author">杜甫</span></div>
          <div class="item ad"><h3><a href="/ad">广告</a></h3></div>
        </div>
        <div id="content">第一段。<br>第二段。<p>第三段。</p><script>var x=1;</script>本站网址 abc.com</div>
        </body></html>
    """.trimIndent()

    private fun ctx() = RuleEngine.Ctx.Html(Jsoup.parse(html, "https://example.com/s?q=x"))

    @Test fun cssRules() {
        val e = RuleEngine()
        val items = e.elements(ctx(), "div.item:not(.ad)")
        assertEquals(2, items.size)
        assertEquals("长安夜雨", e.string(items[0], "h3 a@text"))
        assertEquals("/book/1", e.string(items[0], "h3 a@href"))
        assertEquals("李白", e.string(items[0], "span.author@text##作者："))
        assertEquals("", e.string(items[1], "p.intro@text"))
        assertEquals("杜甫", e.string(items[1], "p.intro@text||span.author@text"))
        // reversed list
        assertEquals("广告", e.string(e.elements(ctx(), "-div.item")[0], "a@text"))
    }

    @Test fun legadoDefaultSyntax() {
        val e = RuleEngine()
        assertEquals(3, e.elements(ctx(), "class.list@class.item").size)
        assertEquals("江湖远", e.string(ctx(), "class.item.1@tag.a@text"))
        assertEquals("广告", e.string(ctx(), "class.item.-1@tag.a@text"))
        assertEquals(listOf("江湖远", "广告"), e.elements(ctx(), "class.item!0").map { e.string(it, "tag.a@text") })
        assertEquals("/book/1", e.string(ctx(), "class.item.0@tag.a@href"))
    }

    @Test fun contentKeepsParagraphs() {
        val e = RuleEngine()
        val text = e.string(ctx(), "#content@html##本站网址.*", keepLines = true)
        assertEquals("第一段。\n第二段。\n第三段。", text)
    }

    @Test fun xpathRules() {
        val e = RuleEngine()
        assertEquals(listOf("/book/1", "/book/2", "/ad"), e.strings(ctx(), "//div[@class='list']//h3/a/@href"))
        assertEquals("长安夜雨", e.strings(ctx(), "//h3/a/text()").first())
    }

    @Test fun jsonRules() {
        val json = """{"code":0,"data":{"list":[{"id":7,"title":"山海经","authors":[{"name":"佚名"}],"formats":{"application/epub+zip":"https://x/7.epub"}},{"id":8,"title":"水经注","authors":[]}]}}"""
        val e = RuleEngine()
        val root = RuleEngine.Ctx.Json(Sources.json.parseToJsonElement(json))
        val items = e.elements(root, "$.data.list[*]")
        assertEquals(2, items.size)
        assertEquals("山海经", e.string(items[0], "$.title"))
        assertEquals("山海经", e.string(items[0], "title"))   // bare key in JSON context
        assertEquals("佚名", e.string(items[0], "$.authors[0].name"))
        assertEquals("https://x/7.epub", e.string(items[0], "$.formats['application/epub+zip']"))
        assertEquals("/book/7.html", e.string(items[0], "/book/{{$.id}}.html"))
        assertEquals(listOf("7", "8"), e.strings(root, "$..id"))
        assertEquals(2, e.elements(root, "$.data.list").size)   // an array result expands into its items
    }

    @Test fun searchUrlTemplates() {
        val c = SourceClient()
        val src = BookSource(bookSourceUrl = "https://example.com")
        assertEquals("https://example.com/s?q=%E7%BA%A2%E6%A5%BC&p=2", c.buildUrl(src, "/s?q={{key}}&p={{page}}", "红楼", 2).url)
        assertEquals("https://example.com/s?start=40", c.buildUrl(src, "/s?start={{(page-1)*20}}", "x", 3).url)
        val post = c.buildUrl(src.copy(header = """{"Referer":"https://example.com/"}"""), """/search.php,{"method":"POST","body":"searchkey={{key}}","charset":"gbk"}""", "红楼")
        assertEquals("POST", post.method)
        assertEquals("searchkey=%BA%EC%C2%A5", post.body)
        assertEquals("https://example.com/", post.headers["Referer"])
        assertEquals("https://other.org/a", c.buildUrl(src, "https://other.org/a").url)
    }

    @Test fun legadoJsonImport() {
        val legado = """[{"bookSourceName":"某站","bookSourceUrl":"https://a.example","bookSourceType":0,"enabled":true,"searchUrl":"/s?q={{key}}",
            "ruleSearch":{"bookList":"class.item","name":"tag.h3@text","bookUrl":"tag.a@href","checkKeyWord":"我的"},
            "ruleToc":{"chapterList":"id.list@tag.dd","chapterName":"tag.a@text","chapterUrl":"tag.a@href"},
            "ruleContent":{"content":"id.content@html","nextContentUrl":"text.下一页@href"},"respondTime":180000,"weight":0},
            {"bookSourceName":"没网址"}]"""
        val list = Sources.parse(legado)
        assertEquals(1, list.size)
        assertEquals("id.list@tag.dd", list[0].ruleToc.chapterList)
        assertTrue(!list[0].isFile && !list[0].usesJs)
        // round trip
        assertEquals(list, Sources.parse(Sources.export(list)))
        assertTrue(Sources.parse("""{"bookSourceUrl":"https://b","ruleSearch":{"bookList":"<js>x</js>"}}""")[0].usesJs)
    }

    /** Markup shaped like gutenberg.org's search and book pages, for the built-in source's rules. */
    @Test fun builtinGutenbergRules() {
        val src = Sources.builtin.first()
        val search = """<ul class="results"><li class="booklink"><a class="link" href="/ebooks/24264" accesskey="0">
            <span class="cell leftcell with-cover"><img class="cover-thumb" src="/cache/epub/24264/pg24264.cover.small.jpg" alt=""></span>
            <span class="cell content"><span class="title">紅樓夢</span><span class="subtitle">Xueqin Cao</span><span class="extra">1234 downloads</span></span></a></li>
            <li class="booklink"><a class="link" href="/ebooks/23962"><span class="cell content"><span class="title">西遊記</span><span class="subtitle">Cheng'en Wu</span></span></a></li></ul>"""
        val c = SourceClient()
        val res = c.parseSearch(src, Page("https://www.gutenberg.org/ebooks/search/?query=x", search.toByteArray(), "text/html; charset=utf-8", null))
        assertEquals(listOf("紅樓夢", "西遊記"), res.map { it.name })
        assertEquals("Xueqin Cao", res[0].author)
        assertEquals("https://www.gutenberg.org/ebooks/24264", res[0].bookUrl)
        assertEquals("https://www.gutenberg.org/cache/epub/24264/pg24264.cover.small.jpg", res[0].coverUrl)
        val detail = """<table class="files"><tr><td><a href="/ebooks/24264.html.images" type="text/html" class="link">Read online</a></td></tr>
            <tr><td><a href="/ebooks/24264.epub3.images" type="application/epub+zip" class="link" title="Download">EPUB3</a></td></tr>
            <tr><td><a href="/ebooks/24264.epub.noimages" type="application/epub+zip" class="link">EPUB (no images)</a></td></tr>
            <tr><td><a href="/ebooks/24264.txt.utf-8" type="text/plain; charset=utf-8" class="link">Plain Text UTF-8</a></td></tr></table>"""
        val d = c.parseDetail(res[0], Page(res[0].bookUrl, detail.toByteArray(), "text/html", null))
        assertEquals(listOf("https://www.gutenberg.org/ebooks/24264.epub3.images", "https://www.gutenberg.org/ebooks/24264.epub.noimages"), d.downloads)
        assertTrue(src.isFile)
    }

    // ---------------------------------------------------------------- end to end

    /** A tiny GBK novel site with a paged chapter list and a chapter split over two pages, plus a file site. */
    private class FakeSite : NanoHTTPD("127.0.0.1", 0) {
        val gbk = charset("GBK")
        val chapters = (1..5).map { "第${it}章 第${it}回" to "这是第${it}章的正文。\n他说：“你好。”" }
        override fun serve(s: IHTTPSession): Response {
            val q = s.parms["q"]?.let { it } ?: ""
            fun page(body: String) = newFixedLengthResponse(Response.Status.OK, "text/html", "<html><head><meta charset=\"gbk\"></head><body>$body</body></html>".toByteArray(gbk).inputStream(), -1)
                .also { it.addHeader("Content-Type", "text/html") }
            return when {
                s.uri == "/search" -> page(if (URLDecoder.decode(s.queryParameterString?.substringAfter("q=") ?: "", "GBK") == "夜雨" || q.isNotEmpty())
                    """<div class="r"><a href="/book/9/">夜雨十年</a><em>佚名</em></div>""" else "")
                s.uri == "/book/9/" -> page("""<h1>夜雨十年</h1><p class="intro">简介<br>第二行</p><dl id="list">""" +
                    chapters.take(3).mapIndexed { i, c -> """<dd><a href="/book/9/${i + 1}.html">${c.first}</a></dd>""" }.joinToString("") +
                    """</dl><a class="next" href="/book/9/index_2.html">下一页</a>""")
                s.uri == "/book/9/index_2.html" -> page("""<dl id="list">""" +
                    chapters.drop(3).mapIndexed { i, c -> """<dd><a href="/book/9/${i + 4}.html">${c.first}</a></dd>""" }.joinToString("") + "</dl>")
                s.uri.matches(Regex("/book/9/\\d+(_2)?\\.html")) -> {
                    val n = s.uri.substringAfterLast('/').substringBefore('.').substringBefore('_').toInt()
                    val second = s.uri.contains("_2")
                    val lines = chapters[n - 1].second.lines()
                    val body = if (second) lines[1] else lines[0]
                    page("""<div id="content">${body}<br>本站网址 fake.site</div>""" +
                        (if (!second) """<a id="next" href="/book/9/${n}_2.html">下一页</a>""" else """<a id="next" href="/book/9/${n + 1}.html">下一章</a>"""))
                }
                s.uri == "/f/search" -> newFixedLengthResponse(Response.Status.OK, "application/json", """{"items":[{"t":"山海经","a":"佚名","id":3}]}""")
                s.uri == "/f/book/3" -> newFixedLengthResponse(Response.Status.OK, "text/html", """<a class="dl" href="/f/get?id=3">下载 EPUB</a>""")
                s.uri == "/f/get" -> newFixedLengthResponse(Response.Status.OK, "application/octet-stream", epub().inputStream(), -1)
                    .also { it.addHeader("Content-Disposition", "attachment; filename=\"shanhai.epub\"") }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "nope")
            }
        }

        fun epub(): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { z ->
                fun put(n: String, t: String) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
                put("mimetype", "application/epub+zip")
                put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
                put("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>山海经</dc:title><dc:creator>佚名</dc:creator></metadata>
                    <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""")
                put("OEBPS/c1.xhtml", """<html><body><h1>南山经</h1><p>南山经之首曰䧿山。</p></body></html>""")
            }
            return bos.toByteArray()
        }
    }

    private lateinit var site: FakeSite
    private val base get() = "http://127.0.0.1:${site.listeningPort}"

    @Before fun up() { site = FakeSite().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) } }
    @After fun down() { site.stop() }

    private fun textSource() = BookSource(
        bookSourceUrl = base, bookSourceName = "测试站",
        searchUrl = """/search?q={{key}},{"charset":"gbk"}""",
        ruleSearch = SearchRule(bookList = "div.r", name = "a@text", author = "em@text", bookUrl = "a@href"),
        ruleBookInfo = BookInfoRule(intro = "p.intro@html"),
        ruleToc = TocRule(chapterList = "#list dd a", nextTocUrl = "a.next@href"),
        ruleContent = ContentRule(content = "#content@html", nextContentUrl = "#next@href", replaceRegex = "##本站网址.*"),
    )

    private fun awaitTask(d: SourceDownloads): SourceDownloads.Task {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 30_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            d.tasks.value.firstOrNull()?.takeIf { it.status != SourceDownloads.Status.RUNNING }?.let { return it }
            Thread.sleep(50)
        }
        error("download did not finish: ${d.tasks.value}")
    }

    @Test fun chapterSiteEndToEnd() = runBlocking {
        val src = textSource()
        val c = SourceClient()
        val res = c.search(src, "夜雨")
        assertEquals(1, res.size)
        assertEquals("佚名", res[0].author)
        val detail = c.detail(res[0])
        assertEquals("简介\n第二行", detail.intro)
        val toc = c.toc(src, detail.tocUrl)
        assertEquals((1..5).map { "第${it}章 第${it}回" }, toc.map { it.name })
        assertEquals("这是第2章的正文。\n他说：“你好。”", c.content(src, toc[1].url, toc[2].url))

        val app = App.instance
        val dl = SourceDownloads(app.appScope, app.importer, app.storage)
        dl.downloadChapters(res[0], detail)
        val task = awaitTask(dl)
        assertEquals(task.message, SourceDownloads.Status.DONE, task.status)
        val book = app.db.books().get(task.bookId)!!
        assertEquals("夜雨十年", book.title)
        assertEquals("佚名", book.author)
        assertEquals(5, book.chapterCount)
        assertEquals("第3章 第3回", app.db.chapters().list(book.id)[2].title)
        assertEquals("这是第3章的正文。\n他说：“你好。”", app.storage.readChapter(book.id, 2))
        assertTrue(app.storage.sourceFile(book).readText().contains("第5章 第5回"))
    }

    @Test fun fileSiteEndToEnd() = runBlocking {
        val src = BookSource(
            bookSourceUrl = base, bookSourceName = "文件站", bookSourceType = BookSource.TYPE_FILE,
            searchUrl = "/f/search?q={{key}}",
            ruleSearch = SearchRule(bookList = "$.items[*]", name = "$.t", author = "$.a", bookUrl = "/f/book/{{$.id}}"),
            ruleBookInfo = BookInfoRule(downloadUrls = "a.dl@href"),
        )
        val c = SourceClient()
        val res = c.search(src, "山海")
        assertEquals("$base/f/book/3", res.single().bookUrl)
        val detail = c.detail(res[0])
        assertEquals(listOf("$base/f/get?id=3"), detail.downloads)

        val app = App.instance
        val dl = SourceDownloads(app.appScope, app.importer, app.storage)
        dl.downloadFile(res[0], detail, detail.downloads[0])
        val task = awaitTask(dl)
        assertEquals(task.message, SourceDownloads.Status.DONE, task.status)
        val book = app.db.books().get(task.bookId)!!
        assertEquals("epub", book.format)
        assertEquals("山海经", book.title)
        assertTrue(app.storage.readChapter(book.id, 0).contains("䧿山"))
    }

    @Test fun badRulesFailClearly() = runBlocking {
        val app = App.instance
        val src = textSource().copy(ruleToc = TocRule(chapterList = "#nothing a"))
        val res = SourceClient().search(src, "夜雨")
        val detail = SourceClient().detail(res[0])
        val dl = SourceDownloads(app.appScope, app.importer, app.storage)
        dl.downloadChapters(res[0], detail)
        val task = awaitTask(dl)
        assertEquals(SourceDownloads.Status.FAILED, task.status)
        assertTrue(task.message, task.message.contains("目录"))
        Unit
    }
}
