package com.daoyu.tingshu.reader

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.LineHeightSpan
import android.text.style.StyleSpan
import android.graphics.text.LineBreakConfig

/** Everything that influences layout; a change in any field means re-pagination. */
data class LayoutSpec(
    val width: Int,
    val height: Int,
    val textSizePx: Float,
    val lineSpacing: Float,
    val paraSpacingPx: Float,
    val typeface: Typeface,
    val indent: Boolean,
    val bold: Boolean,
)

class Page(
    val chapter: Int,
    val index: Int,
    val startLine: Int,
    val endLine: Int,      // exclusive
    val startBody: Int,    // body offset of first char on page
    val endBody: Int,      // body offset after last char on page
)

/** A whole chapter laid out once; pages are line ranges of a single StaticLayout. */
class ChapterLayout(
    val chapter: Int,
    val title: String,
    val body: String,
    val layout: StaticLayout,
    val pages: List<Page>,
    private val paraBody: IntArray,     // body offset where paragraph i starts
    private val paraLayout: IntArray,   // layout offset where paragraph i starts (at indent)
    private val indentLen: Int,
    private val titleEnd: Int,
) {
    fun bodyToLayout(o: Int): Int {
        if (paraBody.isEmpty()) return titleEnd
        val c = o.coerceIn(0, body.length)
        var i = paraBody.binarySearch(c)
        if (i < 0) i = -i - 2
        i = i.coerceIn(0, paraBody.size - 1)
        return paraLayout[i] + indentLen + (c - paraBody[i])
    }

    fun layoutToBody(l: Int): Int {
        if (paraLayout.isEmpty() || l < paraLayout[0]) return 0
        var i = paraLayout.binarySearch(l)
        if (i < 0) i = -i - 2
        i = i.coerceIn(0, paraLayout.size - 1)
        val d = (l - paraLayout[i] - indentLen).coerceAtLeast(0)
        return (paraBody[i] + d).coerceAtMost(body.length)
    }

    fun pageForBody(offset: Int): Int {
        val lo = bodyToLayout(offset)
        val line = layout.getLineForOffset(lo)
        val idx = pages.indexOfFirst { line >= it.startLine && line < it.endLine }
        return if (idx < 0) pages.size - 1 else idx
    }

    /** Rounded per-line bands (layout coordinates) covering a body range; used for the listening highlight. */
    fun highlightPath(startBody: Int, endBody: Int): Path {
        val p = Path()
        val ls = bodyToLayout(startBody)
        val le = bodyToLayout(endBody)
        if (le <= ls) return p
        val first = layout.getLineForOffset(ls)
        val last = layout.getLineForOffset((le - 1).coerceAtLeast(ls))
        val descent = layout.paint.fontMetrics.descent
        val ascent = -layout.paint.fontMetrics.ascent
        val pad = layout.paint.textSize * 0.18f
        for (line in first..last) {
            val s = maxOf(ls, layout.getLineStart(line))
            val e = minOf(le, layout.getLineVisibleEnd(line))
            if (e <= s) continue
            val left = layout.getPrimaryHorizontal(s) - pad * 0.6f
            val right = if (e >= layout.getLineVisibleEnd(line)) layout.getLineRight(line) + pad * 0.6f else layout.getPrimaryHorizontal(e) + pad * 0.6f
            val base = layout.getLineBaseline(line).toFloat()
            p.addRoundRect(android.graphics.RectF(left, base - ascent - pad * 0.5f, right, base + descent + pad * 0.5f), pad, pad, Path.Direction.CW)
        }
        return p
    }
}

object Paginator {

    private class ParaSpacing(private val extra: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            val spanEnd = (text as Spanned).getSpanEnd(this)
            if (end >= spanEnd) {
                fm.descent += extra
                fm.bottom += extra
            }
        }
    }

    fun layout(chapter: Int, title: String, body: String, spec: LayoutSpec): ChapterLayout {
        val indent = if (spec.indent) "　　" else ""
        val sb = SpannableStringBuilder()
        // Title block
        sb.append(title)
        val titleEnd = sb.length
        sb.setSpan(AbsoluteSizeSpan((spec.textSizePx * 1.32f).toInt()), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append('\n')
        sb.setSpan(ParaSpacing((spec.textSizePx * 1.1f).toInt()), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val paras = body.split('\n')
        val paraBody = IntArray(paras.size)
        val paraLayout = IntArray(paras.size)
        var bodyPos = 0
        val paraExtra = spec.paraSpacingPx.toInt()
        paras.forEachIndexed { i, p ->
            paraBody[i] = bodyPos
            paraLayout[i] = sb.length
            val s = sb.length
            sb.append(indent).append(p)
            if (i < paras.size - 1) sb.append('\n')
            if (paraExtra > 0) sb.setSpan(ParaSpacing(paraExtra), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            bodyPos += p.length + 1
        }

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = spec.textSizePx
            typeface = spec.typeface
            isFakeBoldText = spec.bold
            // a touch of tracking makes CJK body text breathe
            letterSpacing = 0.02f
        }
        val builder = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, spec.width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spec.lineSpacing)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        // Android 15+: justify CJK by distributing space between characters (true 两端对齐)
        if (Build.VERSION.SDK_INT >= 35) builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_CHARACTER)
        else builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        if (Build.VERSION.SDK_INT >= 33) {
            // strict CJK line-break rules: no closing punctuation at line start
            builder.setLineBreakConfig(LineBreakConfig.Builder().setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_STRICT).build())
        }
        val layout = builder.build()

        // Paginate by lines; a line fits if its glyphs (baseline + descent) are inside the page.
        val pages = ArrayList<Page>()
        val descent = paint.fontMetricsInt.descent
        var start = 0
        val n = layout.lineCount
        while (start < n) {
            val top = layout.getLineTop(start)
            var end = start + 1
            while (end < n && layout.getLineBaseline(end) + descent - top <= spec.height) end++
            pages.add(Page(chapter, pages.size, start, end, 0, 0))
            start = end
        }
        val cl = ChapterLayout(chapter, title, body, layout, pages, paraBody, paraLayout, indent.length, titleEnd + 1)
        val fixed = pages.map { p ->
            val sb0 = cl.layoutToBody(layout.getLineStart(p.startLine))
            val eb = if (p.endLine >= n) body.length else cl.layoutToBody(layout.getLineStart(p.endLine))
            Page(p.chapter, p.index, p.startLine, p.endLine, if (p.index == 0) 0 else sb0, eb)
        }
        return ChapterLayout(chapter, title, body, layout, fixed, paraBody, paraLayout, indent.length, titleEnd + 1)
    }
}
