package com.daoyu.tingshu.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Evaluates book-source rules against an HTML element or a JSON value. A subset of 阅读 (Legado) rule syntax:
 *
 *  - CSS:      `div.list li` / `@css:div.list li`, then `@text` `@ownText` `@textNodes` `@html` `@href` `@<attr>`
 *  - Legado:   `class.list@tag.li.0@tag.a@href` — segments `class.` `id.` `tag.` `text.` `children`, with `.N` (negative ok)
 *              or `!N` (exclude) index lists like `.0:2`; any other segment is treated as a CSS selector
 *  - JSONPath: `$.data.list[*]`, `$.book.name`, `$..id`, `$['a/b']`
 *  - XPath:    `//div[@class='x']/a/@href`, `//p/text()`
 *  - Combine:  `a||b` (first one with a result), `a&&b` (both), `rule##regex##replacement` (cleanup),
 *              `/book/{{$.id}}.html` (embed rule results); a leading `-` reverses a list
 *
 * JavaScript isn't supported.
 */
class RuleEngine(private val baseUrl: String = "") {

    sealed class Ctx {
        class Html(val el: Element) : Ctx()
        class Json(val el: JsonElement) : Ctx()
    }

    // ---------- lists ----------

    fun elements(ctx: Ctx, rule: String): List<Ctx> {
        var r = rule.trim()
        if (r.isEmpty()) return emptyList()
        var reverse = false
        if (r.startsWith("-")) { reverse = true; r = r.substring(1) } else if (r.startsWith("+")) r = r.substring(1)
        val out = alternatives(r) { part ->
            splitTop(part, "&&").flatMap { single -> selectAll(ctx, single) }
        }
        return if (reverse) out.reversed() else out
    }

    private fun selectAll(ctx: Ctx, rule: String): List<Ctx> {
        val (mode, body) = mode(ctx, rule)
        return when (mode) {
            Mode.JSON -> JsonPath.eval(jsonOf(ctx) ?: return emptyList(), body).flatMap { if (it is JsonArray) it.toList() else listOf(it) }.map { Ctx.Json(it) }
            Mode.XPATH -> (ctx as? Ctx.Html)?.el?.selectXpath(body)?.map { Ctx.Html(it) } ?: emptyList()
            Mode.CSS -> {
                val el = (ctx as? Ctx.Html)?.el ?: return emptyList()
                var cur = listOf(el)
                for (seg in splitTop(body, "@")) cur = cur.flatMap { selectSegment(it, seg) }
                cur.map { Ctx.Html(it) }
            }
        }
    }

    // ---------- strings ----------

    fun string(ctx: Ctx, rule: String, keepLines: Boolean = false): String =
        strings(ctx, rule, keepLines).joinToString(if (keepLines) "\n" else " ").trim()

    fun strings(ctx: Ctx, rule: String, keepLines: Boolean = false): List<String> {
        val r = rule.trim()
        if (r.isEmpty()) return emptyList()
        return alternatives(r) { part -> splitTop(part, "&&").flatMap { stringsOne(ctx, it, keepLines) } }
    }

    private fun stringsOne(ctx: Ctx, rule: String, keepLines: Boolean): List<String> {
        val parts = rule.split("##")
        val main = parts[0]
        val values = if (main.contains("{{") && main.contains("}}")) listOf(template(ctx, main)) else extract(ctx, main, keepLines)
        if (parts.size < 2 || parts[1].isEmpty()) return values.filter { it.isNotBlank() }
        val regex = runCatching { Regex(parts[1]) }.getOrNull() ?: return values
        val repl = parts.getOrElse(2) { "" }
        val firstOnly = rule.endsWith("###")
        return values.map { if (firstOnly) regex.replaceFirst(it, repl) else regex.replace(it, repl) }.map { it.trim() }.filter { it.isNotBlank() }
    }

    /** `/book/{{$.id}}/{{@css:a@href}}` — every {{…}} is itself a rule. */
    private fun template(ctx: Ctx, s: String): String = Regex("\\{\\{(.+?)\\}\\}").replace(s) { m -> string(ctx, m.groupValues[1]) }

    private fun extract(ctx: Ctx, rule: String, keepLines: Boolean): List<String> {
        val (mode, body) = mode(ctx, rule)
        return when (mode) {
            Mode.JSON -> JsonPath.eval(jsonOf(ctx) ?: return emptyList(), body).flatMap { if (it is JsonArray) it.toList() else listOf(it) }.mapNotNull { jsonText(it) }
            Mode.XPATH -> {
                val el = (ctx as? Ctx.Html)?.el ?: return emptyList()
                val m = Regex("^(.*)/(@[\\w:-]+|text\\(\\))$").find(body)
                if (m == null) el.selectXpath(body).map { text(it, keepLines) }
                else {
                    val nodes = el.selectXpath(m.groupValues[1])
                    val what = m.groupValues[2]
                    if (what == "text()") nodes.map { it.ownText() } else nodes.map { it.attr(what.substring(1)) }
                }
            }
            Mode.CSS -> {
                val el = (ctx as? Ctx.Html)?.el ?: return emptyList()
                val segs = splitTop(body, "@")
                // a lone extractor (`@href`, `text`) applies to the context element itself
                val last = segs.last()
                val selectors = if (segs.size == 1 && !isExtractor(last)) segs else segs.dropLast(1)
                val extractor = if (segs.size == 1 && !isExtractor(last)) "text" else last
                var cur = listOf(el)
                for (seg in selectors) cur = cur.flatMap { selectSegment(it, seg) }
                cur.map { valueOf(it, extractor, keepLines) }
            }
        }
    }

    private fun isExtractor(s: String) = s in extractors || (s.isNotEmpty() && !s.contains('.') && !s.contains(' ') &&
        !s.contains('[') && !s.contains('#') && !s.contains('>') && !s.contains(':') && s !in htmlTags)

    private fun valueOf(el: Element, what: String, keepLines: Boolean): String = when (what) {
        "text" -> text(el, keepLines)
        "ownText" -> el.ownText()
        "textNodes" -> el.textNodes().joinToString("\n") { it.text().trim() }.trim()
        "html" -> if (keepLines) text(el, true) else el.html()
        "all" -> el.outerHtml()
        else -> el.attr(what)
    }

    /** Text with paragraph breaks kept: <br>, <p>, <div> become new lines. */
    private fun text(el: Element, keepLines: Boolean): String {
        if (!keepLines) return el.text()
        val sb = StringBuilder()
        fun walk(n: Node) {
            when (n) {
                is TextNode -> sb.append(n.wholeText.replace(Regex("[\\r\\n\\t ]+"), " "))
                is Element -> {
                    val tag = n.normalName()
                    if (tag == "script" || tag == "style") return
                    if (tag == "br") { sb.append('\n'); return }
                    val block = tag in blockTags
                    if (block) sb.append('\n')
                    n.childNodes().forEach { walk(it) }
                    if (block) sb.append('\n')
                }
            }
        }
        walk(el)
        return sb.toString().lines().map { it.trim(' ', '\t', '　', ' ') }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun jsonText(e: JsonElement): String? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> e.contentOrNull
        else -> e.toString()
    }

    private fun jsonOf(ctx: Ctx): JsonElement? = (ctx as? Ctx.Json)?.el

    // ---------- selection ----------

    private enum class Mode { CSS, JSON, XPATH }

    private fun mode(ctx: Ctx, rule: String): Pair<Mode, String> {
        val r = rule.trim()
        return when {
            r.startsWith("@css:", true) -> Mode.CSS to r.substring(5)
            r.startsWith("@json:", true) -> Mode.JSON to r.substring(6)
            r.startsWith("@xpath:", true) -> Mode.XPATH to r.substring(7)
            r.startsWith("$.") || r.startsWith("$[") -> Mode.JSON to r
            r.startsWith("//") || r.startsWith("./") -> Mode.XPATH to r
            ctx is Ctx.Json -> Mode.JSON to "$.$r"
            else -> Mode.CSS to r
        }
    }

    private val legadoSeg = Regex("^(class|id|tag|text|children)(?:\\.([^.!]*))?(?:([.!])(-?\\d+(?::-?\\d+)*))?$")

    private fun selectSegment(el: Element, seg: String): List<Element> {
        val s = seg.trim()
        if (s.isEmpty()) return listOf(el)
        val m = legadoSeg.find(s) ?: return el.select(s).toList()
        val (type, name, op, idx) = m.destructured
        val found: List<Element> = when (type) {
            "class" -> el.getElementsByClass(name)
            "id" -> listOfNotNull(el.getElementById(name))
            "tag" -> el.getElementsByTag(name)
            "text" -> el.getElementsContainingOwnText(name)
            else -> el.children()
        }.filter { it !== el || type == "children" }
        if (idx.isEmpty()) return found
        val picks = idx.split(':').mapNotNull { it.toIntOrNull() }.map { if (it < 0) found.size + it else it }.toSet()
        return if (op == "!") found.filterIndexed { i, _ -> i !in picks } else picks.sorted().mapNotNull { found.getOrNull(it) }
    }

    // ---------- helpers ----------

    private inline fun <T> alternatives(rule: String, eval: (String) -> List<T>): List<T> {
        for (alt in splitTop(rule, "||")) {
            val r = eval(alt)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    /** Splits on [sep] outside [...] and (...) and {{...}}. */
    internal fun splitTop(s: String, sep: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '[' || c == '(' || c == '{' -> depth++
                c == ']' || c == ')' || c == '}' -> depth = (depth - 1).coerceAtLeast(0)
                depth == 0 && s.startsWith(sep, i) -> {
                    out += s.substring(start, i); i += sep.length; start = i; continue
                }
            }
            i++
        }
        out += s.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() || sep == "@" }.ifEmpty { listOf("") }
    }

    fun absolute(url: String, base: String = baseUrl): String = Urls.resolve(base, url)

    companion object {
        private val extractors = setOf("text", "ownText", "textNodes", "html", "all", "href", "src", "content", "value", "title", "alt")
        private val blockTags = setOf("p", "div", "section", "article", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "tr", "dd", "dt")
        private val htmlTags = setOf("a", "p", "div", "span", "li", "ul", "ol", "dl", "dd", "dt", "td", "tr", "table", "img", "h1", "h2", "h3", "h4", "b", "i", "em", "strong", "section", "article", "body", "head")
    }
}

/** Minimal JSONPath: `$.a.b`, `$.a[0]`, `$.a[-1]`, `$.a[*]`, `$.a.*`, `$..key`, `$['key with/slash']`. */
object JsonPath {
    fun eval(root: JsonElement, path: String): List<JsonElement> {
        var p = path.trim()
        if (p.startsWith("$")) p = p.substring(1)
        var cur = listOf(root)
        var i = 0
        while (i < p.length && cur.isNotEmpty()) {
            when {
                p.startsWith("..", i) -> {
                    i += 2
                    val (key, next) = readKey(p, i); i = next
                    cur = cur.flatMap { deep(it, key) }
                }
                p[i] == '.' -> {
                    i++
                    val (key, next) = readKey(p, i); i = next
                    cur = if (key == "*") cur.flatMap { children(it) } else cur.mapNotNull { (it as? JsonObject)?.get(key) }
                }
                p[i] == '[' -> {
                    val end = p.indexOf(']', i).takeIf { it > 0 } ?: p.length
                    val inside = p.substring(i + 1, end).trim()
                    i = end + 1
                    cur = when {
                        inside == "*" -> cur.flatMap { children(it) }
                        inside.startsWith("'") || inside.startsWith("\"") -> {
                            val key = inside.trim('\'', '"')
                            cur.mapNotNull { (it as? JsonObject)?.get(key) }
                        }
                        else -> {
                            val idx = inside.split(',').mapNotNull { it.trim().toIntOrNull() }
                            cur.flatMap { e -> (e as? JsonArray)?.let { a -> idx.mapNotNull { a.getOrNull(if (it < 0) a.size + it else it) } } ?: emptyList() }
                        }
                    }
                }
                else -> {
                    val (key, next) = readKey(p, i); i = next
                    cur = cur.mapNotNull { (it as? JsonObject)?.get(key) }
                }
            }
        }
        return cur.filter { it !is JsonNull }
    }

    private fun readKey(p: String, from: Int): Pair<String, Int> {
        var j = from
        while (j < p.length && p[j] != '.' && p[j] != '[') j++
        return p.substring(from, j) to j
    }

    private fun children(e: JsonElement): List<JsonElement> = when (e) {
        is JsonArray -> e.toList()
        is JsonObject -> e.values.toList()
        else -> emptyList()
    }

    private fun deep(e: JsonElement, key: String): List<JsonElement> = when (e) {
        is JsonObject -> listOfNotNull(e[key]) + e.values.flatMap { deep(it, key) }
        is JsonArray -> e.flatMap { deep(it, key) }
        else -> emptyList()
    }
}

object Urls {
    fun resolve(base: String, url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return base
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return (if (base.startsWith("http://")) "http:" else "https:") + u
        return runCatching { java.net.URI(base.ifEmpty { "https://localhost/" }).resolve(u.replace(" ", "%20")).toString() }.getOrDefault(u)
    }
}
