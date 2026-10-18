package com.daoyu.tingshu.tts

/** A piece of chapter text sent to the TTS engine in one request. */
data class Seg(
    val chapter: Int, val start: Int, val end: Int, val text: String,
    val drama: Boolean = false,          // multi-role mode segment
    val role: String? = null,            // speaker for a quoted line; null = narration
    val emotion: String = "",
)

object Segmenter {
    private const val ENDERS = "。！？!?；;…"
    private const val CLOSERS = "”’」』）)\"'》】"
    private const val SOFT = "，,、：:"
    private val speakable = Regex("[\\p{L}\\p{N}]")

    /**
     * Splits [body] starting at [from] into segments of at most [maxLen] chars.
     * Sentences are kept whole; short neighbouring paragraphs (dialogue lines) are merged.
     */
    fun split(chapter: Int, body: String, from: Int, maxLen: Int, minLen: Int = 36): List<Seg> {
        val sentences = sentences(body, from.coerceIn(0, body.length), maxLen)
        val out = ArrayList<Seg>()
        var gs = -1; var ge = -1
        fun emit() {
            if (gs >= 0) {
                val t = body.substring(gs, ge).trim()
                if (speakable.containsMatchIn(t)) out.add(Seg(chapter, gs, ge, t))
            }
            gs = -1; ge = -1
        }
        for ((s, e) in sentences) {
            if (gs < 0) { gs = s; ge = e; continue }
            val crossesPara = body.indexOf('\n', ge).let { it in ge until s }
            val fits = e - gs <= maxLen
            if (fits && (!crossesPara || ge - gs < minLen)) ge = e else { emit(); gs = s; ge = e }
        }
        emit()
        return out
    }

    /**
     * Multi-role split: narration between quotes is cut like [split]; every quoted line becomes its own
     * segment carrying the speaker. Lines marked as narration stay with the narrator.
     */
    fun splitDrama(chapter: Int, body: String, from: Int, lines: List<com.daoyu.tingshu.drama.RoleLine>, maxLen: Int): List<Seg> {
        val out = ArrayList<Seg>()
        var pos = from.coerceIn(0, body.length)
        val dialogue = lines.filter { it.end > pos && it.speaker != com.daoyu.tingshu.drama.Roles.NARRATOR }.sortedBy { it.start }
        fun narration(until: Int) {
            if (until <= pos) return
            split(chapter, body.substring(0, until), pos, maxLen).forEach { out.add(it.copy(drama = true)) }
        }
        for (l in dialogue) {
            narration(l.start)                                          // no-op when we start inside this quote
            val innerStart = maxOf(l.start + 1, pos)
            val innerEnd = (l.end - 1).coerceAtLeast(innerStart)
            val inner = body.substring(innerStart, innerEnd)
            if (inner.length <= maxLen) {
                if (speakable.containsMatchIn(inner)) out.add(Seg(chapter, if (innerStart == l.start + 1) l.start else innerStart, l.end, inner.trim(), true, l.speaker, l.emotion))
            } else {
                split(chapter, body.substring(0, innerEnd), innerStart, maxLen).forEach { out.add(it.copy(drama = true, role = l.speaker, emotion = l.emotion)) }
            }
            pos = l.end
        }
        narration(body.length)
        return out
    }

    /** (start, end) of each sentence; '\n' is never inside a sentence. */
    private fun sentences(body: String, from: Int, maxLen: Int): List<Pair<Int, Int>> {
        val res = ArrayList<Pair<Int, Int>>()
        var i = from
        val n = body.length
        while (i < n) {
            while (i < n && (body[i] == '\n' || body[i] == ' ' || body[i] == '　')) i++
            if (i >= n) break
            val s = i
            var lastSoft = -1
            while (i < n && body[i] != '\n') {
                val c = body[i]
                i++
                if (c in ENDERS) {
                    while (i < n && (body[i] in ENDERS || body[i] in CLOSERS)) i++
                    break
                }
                if (c in SOFT) lastSoft = i
                if (i - s >= maxLen) {
                    // overly long sentence: cut at the last comma, or hard-cut
                    if (lastSoft > s + maxLen / 3) i = lastSoft
                    break
                }
            }
            res.add(s to i)
        }
        return res
    }
}
