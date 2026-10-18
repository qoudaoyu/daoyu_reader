package com.daoyu.tingshu.parser

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class ParsedChapter(val title: String, val body: String)

class ParsedBook(
    val title: String,
    val author: String,
    val chapters: List<ParsedChapter>,
    val cover: ByteArray? = null,
)

object TxtParser {

    private const val NUM = "0-9０-９零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟"
    private const val SEP = "\\s:：、.．·\\-—_|"

    private val chapterRegex = Regex(
        "^(?:" +
            // 第一章 标题 / 第12回 / 第三卷 …
            "第\\s*[$NUM]+\\s*[章节回卷集部篇幕话](?:[$SEP]*.{0,40})?" +
            "|" +
            // 序章、楔子、番外三 …
            "(?:序章|序言|序|楔子|引子|引言|前言|尾声|后记|终章|大结局|完本感言|番外篇?)(?:\\s*[$NUM]*)(?:[$SEP]+.{0,40})?" +
            "|" +
            // Chapter 1 / CHAPTER XII
            "(?i:chapter)\\s*[0-9IVXLC]+(?:[$SEP]*.{0,40})?" +
            "|" +
            // 001 标题 / 12、标题（纯数字编号，标题较短）
            "(?:[0-9０-９]{1,4}[、．]|[0-9０-９]{3,4}\\s)\\s*\\S.{0,24}" +
            ")$"
    )

    private val junkLine = Regex("^[=\\-—_*~·.。、\\s]{3,}$")
    private val stripChars = charArrayOf(' ', '\t', '　', ' ', '﻿', '\r')

    fun decode(bytes: ByteArray): String {
        // BOMs
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        // Strict UTF-8 first, then GB18030 (superset of GBK/GB2312), finally Big5.
        for (cs in listOf("UTF-8", "GB18030", "Big5")) {
            try {
                val dec = Charset.forName(cs).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return dec.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
            }
        }
        return String(bytes, Charset.forName("GB18030"))
    }

    fun parse(file: File, fileName: String): ParsedBook = parseText(decode(file.readBytes()), fileName)

    fun parseText(text: String, fileName: String): ParsedBook {
        val (nameTitle, nameAuthor) = titleFromFileName(fileName)
        val lines = text.split('\n').map { it.trim(*stripChars) }

        var author = nameAuthor
        if (author.isEmpty()) {
            lines.take(30).firstOrNull { it.startsWith("作者") && it.length < 30 }?.let {
                author = it.removePrefix("作者").trim(':', '：', ' ', '　')
            }
        }

        val chapters = ArrayList<ParsedChapter>()
        var curTitle: String? = null
        val body = StringBuilder()

        fun flush() {
            val b = body.toString().trimEnd('\n')
            if (curTitle == null) {
                // Preface text before the first chapter heading: keep it only if it is substantial
                if (b.length > 40) chapters.add(ParsedChapter("开篇", b))
            } else if (b.isNotBlank()) {
                chapters.add(ParsedChapter(curTitle!!, b))
            }
            body.setLength(0)
        }

        for (line in lines) {
            if (line.isEmpty() || junkLine.matches(line)) continue
            if (line.length <= 50 && chapterRegex.matches(line)) {
                flush()
                curTitle = line.replace(Regex("[\\s\u3000]+"), " ")
                continue
            }
            body.append(line).append('\n')
        }
        flush()

        val result = if (chapters.size < 2) splitBySize(chapters.joinToString("\n") { it.body }) else chapters
        return ParsedBook(nameTitle, author, result)
    }

    /** No headings found: cut every ~5000 chars at a paragraph boundary. */
    private fun splitBySize(all: String): List<ParsedChapter> {
        val paras = all.split('\n').filter { it.isNotBlank() }
        val out = ArrayList<ParsedChapter>()
        val sb = StringBuilder()
        for (p in paras) {
            sb.append(p).append('\n')
            if (sb.length >= 5000) {
                out.add(ParsedChapter("第${out.size + 1}节", sb.toString().trimEnd('\n'))); sb.setLength(0)
            }
        }
        if (sb.isNotEmpty() || out.isEmpty()) out.add(ParsedChapter("第${out.size + 1}节", sb.toString().trimEnd('\n')))
        return out
    }

    /** 《斗破苍穹》作者：天蚕土豆.txt → (斗破苍穹, 天蚕土豆) */
    fun titleFromFileName(fileName: String): Pair<String, String> {
        var name = fileName.substringBeforeLast('.').trim()
        var author = ""
        Regex("[《](.+?)[》]").find(name)?.let { m ->
            val rest = name.substring(m.range.last + 1)
            name = m.groupValues[1]
            Regex("作者[:：\\s]*([^\\s()（）\\[\\]]+)").find(rest)?.let { author = it.groupValues[1] }
            return name.trim() to author
        }
        Regex("^(.+?)[\\s_\\-]*(?:作者|by)[:：\\s]*(.+)$", RegexOption.IGNORE_CASE).find(name)?.let {
            return it.groupValues[1].trim() to it.groupValues[2].trim()
        }
        name = name.replace(Regex("[(（\\[【].*?[)）\\]】]"), "").trim()
        return (name.ifEmpty { fileName }) to author
    }
}
