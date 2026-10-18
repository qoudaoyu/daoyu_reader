package com.daoyu.tingshu.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

object EpubParser {

    private val blockTags = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre",
        "section", "article", "tr", "dt", "dd", "header", "footer", "aside", "figcaption", "table"
    )

    fun parse(file: File, fileName: String): ParsedBook = ZipFile(file).use { zip ->
        fun read(path: String): String? = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
        fun readBytes(path: String): ByteArray? = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

        val container = read("META-INF/container.xml") ?: error("不是有效的 EPUB 文件")
        val opfPath = Jsoup.parse(container, "", Parser.xmlParser())
            .getElementsByTag("rootfile").firstOrNull()?.attr("full-path") ?: error("EPUB 缺少 OPF")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = Jsoup.parse(read(opfPath) ?: error("EPUB 缺少 OPF"), "", Parser.xmlParser())

        fun resolve(base: String, href: String): String {
            val clean = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
            val parts = ArrayList<String>()
            if (base.isNotEmpty()) parts.addAll(base.split('/'))
            for (seg in clean.split('/')) when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
            return parts.joinToString("/")
        }

        val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()?.trim().orEmpty()
            .ifEmpty { TxtParser.titleFromFileName(fileName).first }
        val author = opf.getElementsByTag("dc:creator").firstOrNull()?.text()?.trim().orEmpty()

        data class Item(val id: String, val href: String, val type: String, val props: String)
        val items = opf.getElementsByTag("item").map {
            Item(it.attr("id"), resolve(opfDir, it.attr("href")), it.attr("media-type"), it.attr("properties"))
        }
        val byId = items.associateBy { it.id }

        // ---- cover
        val coverItem = opf.getElementsByTag("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")?.let { byId[it] }
            ?: items.firstOrNull { it.props.contains("cover-image") }
            ?: items.firstOrNull { it.type.startsWith("image/") && it.id.contains("cover", true) }
            ?: items.firstOrNull { it.type.startsWith("image/") && it.href.contains("cover", true) }
        val cover = coverItem?.takeIf { it.type.startsWith("image/") }?.let { readBytes(it.href) }

        // ---- toc titles: file path -> title (first entry wins)
        val tocTitles = LinkedHashMap<String, String>()
        items.firstOrNull { it.props.split(' ').contains("nav") }?.let { nav ->
            read(nav.href)?.let { html ->
                val doc = Jsoup.parse(html)
                val tocNav = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.selectFirst("nav")
                tocNav?.select("a[href]")?.forEach { a ->
                    val p = resolve(nav.href.substringBeforeLast('/', ""), a.attr("href"))
                    val t = a.text().trim()
                    if (t.isNotEmpty()) tocTitles.putIfAbsent(p, t)
                }
            }
        }
        if (tocTitles.isEmpty()) {
            val ncxId = opf.getElementsByTag("spine").firstOrNull()?.attr("toc")
            val ncx = byId[ncxId] ?: items.firstOrNull { it.type == "application/x-dtbncx+xml" }
            ncx?.let { n ->
                read(n.href)?.let { xml ->
                    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
                    doc.getElementsByTag("navPoint").forEach { np ->
                        val label = np.getElementsByTag("navLabel").firstOrNull()?.text()?.trim().orEmpty()
                        val src = np.getElementsByTag("content").firstOrNull()?.attr("src").orEmpty()
                        if (label.isNotEmpty() && src.isNotEmpty())
                            tocTitles.putIfAbsent(resolve(n.href.substringBeforeLast('/', ""), src), label)
                    }
                }
            }
        }

        // ---- spine
        val chapters = ArrayList<ParsedChapter>()
        val spine = opf.getElementsByTag("itemref").filter { it.attr("linear") != "no" }.mapNotNull { byId[it.attr("idref")] }
        for (item in spine) {
            if (!item.type.contains("html")) continue
            val html = read(item.href) ?: continue
            val doc = Jsoup.parse(html)
            var text = htmlToText(doc)
            if (text.isBlank()) continue
            val heading = doc.body()?.selectFirst("h1,h2,h3")?.text()?.trim()
            var t = tocTitles[item.href]
                ?: heading?.takeIf { it.isNotEmpty() && it.length <= 40 }
                ?: doc.title().trim().takeIf { it.isNotEmpty() && it != title }
                ?: "第${chapters.size + 1}节"
            t = t.replace(Regex("[\\s\u3000]+"), " ")
            // avoid duplicate heading line inside the body
            val firstLine = text.substringBefore('\n')
            if (firstLine.replace(" ", "") == t.replace(" ", "") || (heading != null && firstLine == heading && heading.length <= 40)) {
                text = text.substringAfter('\n', "")
            }
            // heading-only pages (e.g. part titles) carry no readable body; skip them
            if (text.isBlank()) continue
            chapters.add(ParsedChapter(t, text))
        }
        if (chapters.isEmpty()) error("EPUB 中没有找到正文")
        ParsedBook(title, author, chapters, cover)
    }

    fun htmlToText(doc: Document): String {
        val body: Element = doc.body() ?: return ""
        body.select("script,style,rt,rp,img,svg,head").remove()
        val sb = StringBuilder()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> sb.append(node.text())
                    is Element -> when {
                        node.normalName() == "br" -> sb.append('\n')
                        node.normalName() in blockTags -> if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                    }
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in blockTags && sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
            }
        }, body)
        return sb.toString().split('\n')
            .map { it.trim(' ', '\t', '　', ' ') }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
