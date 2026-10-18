package com.daoyu.tingshu

import com.daoyu.tingshu.parser.EpubParser
import com.daoyu.tingshu.parser.TxtParser
import com.daoyu.tingshu.tts.Segmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ParserTest {
    private val sample = """
        《剑来》
        作者：烽火戏诸侯

        内容简介：这是一个关于少年的故事，他在小镇上长大，见过了许多人间的风景，也遇到了很多奇怪的事情。从此踏上了一条不一样的道路。

        第一章 惊蛰
        　　二月二，龙抬头。暮色里，小镇名叫泥瓶巷的僻静地方，有位孤苦伶仃的清瘦少年。
        　　“你是谁？”他问道。
        　　“我？”
        　　“对，就是你！”

        第二章 开门
        　　少年推开门，外面下着雨。
        ======
        第三章　第三个标题
        　　正文三。
        3 个人走了过来，这一行不应该被当成章节。
    """.trimIndent()

    @Test fun txtChaptersAndMeta() {
        val gbk = sample.toByteArray(Charset.forName("GBK"))
        val decoded = TxtParser.decode(gbk)
        assertEquals(sample, decoded)
        val b = TxtParser.parseText(decoded, "《剑来》作者：烽火戏诸侯.txt")
        assertEquals("剑来", b.title)
        assertEquals("烽火戏诸侯", b.author)
        b.chapters.forEach { println("[${it.title}] ${it.body.take(40).replace("\n", "⏎")}") }
        assertEquals(listOf("开篇", "第一章 惊蛰", "第二章 开门", "第三章 第三个标题"), b.chapters.map { it.title })
        assertTrue(b.chapters[3].body.contains("3 个人走了过来"))
        assertTrue(!b.chapters[1].body.startsWith("　"))
    }

    @Test fun utf8AndNoChapters() {
        val text = (1..400).joinToString("\n") { "这是第${it}段，内容比较长一些，用来测试没有章节标题时按长度切分。" }
        val b = TxtParser.parseText(TxtParser.decode(text.toByteArray()), "无章节.txt")
        assertTrue(b.chapters.size >= 3)
        assertTrue(b.chapters.all { it.title.startsWith("第") && it.title.endsWith("节") })
    }

    @Test fun fileNames() {
        assertEquals("斗破苍穹" to "天蚕土豆", TxtParser.titleFromFileName("斗破苍穹 作者：天蚕土豆.txt"))
        assertEquals("诡秘之主" to "", TxtParser.titleFromFileName("诡秘之主(精校版).txt"))
    }

    @Test fun segments() {
        val body = "二月二，龙抬头。暮色里，小镇名叫泥瓶巷的僻静地方，有位孤苦伶仃的清瘦少年！\n“你是谁？”\n“我？”\n“对，就是你！”他大声说道。\n……\n" +
            "很长很长的一句话".repeat(40) + "。\n结尾。"
        val segs = Segmenter.split(0, body, 0, 150)
        segs.forEach { println("${it.start}-${it.end}: ${it.text.replace("\n", "⏎")}") }
        assertTrue(segs.all { it.text.length <= 150 })
        assertTrue(segs.none { it.text == "……" })
        // ranges cover text in order without overlap
        segs.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
        segs.forEach { assertEquals(it.text, body.substring(it.start, it.end).trim()) }
        // starting mid-way
        val mid = Segmenter.split(0, body, body.indexOf("对，就是"), 150)
        assertTrue(mid.first().text.startsWith("对，就是你"))
    }

    @Test fun epub() {
        val f = File.createTempFile("test", ".epub")
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>测试之书</dc:title><dc:creator>某作者</dc:creator></metadata>
              <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="c1" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""")
            put("OEBPS/nav.xhtml", """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="text/ch%201.xhtml">第一章 起风</a></li><li><a href="text/ch2.xhtml#x">第二章 落雨</a></li></ol></nav></body></html>""")
            put("OEBPS/text/ch 1.xhtml", "<html><head><title>x</title></head><body><h1>第一章 起风</h1><p>风起了。</p><p>他说：<b>走吧</b>。<br/>新的一行</p></body></html>")
            put("OEBPS/text/ch2.xhtml", "<html><body><div><p>雨落了。</p></div></body></html>")
        }
        val b = EpubParser.parse(f, "t.epub")
        b.chapters.forEach { println("[${it.title}] ${it.body.replace("\n", "⏎")}") }
        assertEquals("测试之书", b.title)
        assertEquals("某作者", b.author)
        assertEquals(listOf("第一章 起风", "第二章 落雨"), b.chapters.map { it.title })
        assertEquals("风起了。\n他说：走吧。\n新的一行", b.chapters[0].body)
        f.delete()
    }
}
