package com.daoyu.tingshu.drama

import kotlinx.serialization.Serializable

/** One character of a book. `name` is canonical; `aliases` are other ways the text refers to them. */
@Serializable
data class CastMember(
    val name: String,
    val aliases: List<String> = emptyList(),
    val gender: String = "男",          // 男 / 女
    val age: String = "青年",           // 儿童 / 少年 / 青年 / 中年 / 老年
    val voice: String = "",             // Qwen voice id
    val lines: Int = 0,                 // dialogue lines seen so far (importance)
    val mergedInto: String? = null,     // set when the user merged this name into another character
    val userVoice: Boolean = false,     // voice chosen by the user, never reassigned automatically
    val voiceDesc: String = "",         // how this character should sound (from the director, user-editable)
    val designedVoice: String = "",     // Qwen voice-design id made from voiceDesc ("" = not designed yet)
    val designFailed: Boolean = false,  // last design attempt failed; retried only on request
    val doubaoVoice: String = "",       // 豆包 2.0 voice id ("" = not chosen yet)
    val doubaoUser: Boolean = false,    // doubao voice chosen by the user
)

@Serializable
data class Cast(
    val members: List<CastMember> = emptyList(),
    val narratorVoice: String? = null,  // Edge voice for narration; null = settings default
    val narratorDoubao: String? = null, // 豆包 voice for narration; null = settings default
)

/** A quoted line inside a chapter body: [start, end) covers the quote marks too. */
@Serializable
data class RoleLine(
    val start: Int,
    val end: Int,
    val speaker: String,                // canonical name, "旁白", "路人" or "路人女"
    val emotion: String = "",
    val manual: Boolean = false,        // corrected by the user
)

@Serializable
data class ChapterRoles(val version: Int = 1, val lines: List<RoleLine> = emptyList())

/** Results older than this are re-analysed (keeping the user's manual corrections). */
const val ROLES_VERSION = 4   // 4: unnamed female speakers are "路人女"

object Roles {
    const val NARRATOR = "旁白"
    const val PASSERBY = "路人"         // unnamed speaker, male or unknown
    const val PASSERBY_F = "路人女"     // unnamed female speaker

    fun isPasserby(s: String?) = s == PASSERBY || s == PASSERBY_F
}

/** Finds quoted dialogue: “…” 「…」 『…』 and straight "…" pairs, never across a line break. */
object QuoteFinder {
    private val pattern = Regex("“[^”\\n]{1,600}”|「[^」\\n]{1,600}」|『[^』\\n]{1,600}』|\"[^\"\\n]{1,600}\"")
    private val speakable = Regex("[\\p{L}\\p{N}]")

    fun find(body: String): List<IntRange> = pattern.findAll(body)
        .filter { speakable.containsMatchIn(it.value) }
        .map { it.range }
        .toList()

    private const val SPEECH_END = "。！？!?…~～—"
    private const val INTRO = "：:，,\n　 \t"

    /**
     * Quotes that obviously are not speech: a short word or phrase with no sentence punctuation, embedded in
     * the middle of a narrative sentence — 所谓的“天才”、门匾上的“林府”、正所谓“三十年河东”. These are read by
     * the narrator without asking the model.
     */
    fun isEmbedded(body: String, r: IntRange): Boolean {
        val inner = body.substring(r.first + 1, r.last)
        if (inner.length > 12 || inner.any { it in SPEECH_END }) return false
        val prev = if (r.first > 0) body[r.first - 1] else '\n'
        return prev !in INTRO
    }
}

/** Qwen voices grouped by gender and age; ordered by preference. */
object VoicePools {
    private val pools: Map<Pair<String, String>, List<String>> = mapOf(
        ("男" to "儿童") to listOf("Pip"),
        ("男" to "少年") to listOf("Moon", "Kai", "Ethan"),
        ("男" to "青年") to listOf("Moon", "Kai", "Neil"),
        ("男" to "中年") to listOf("Kai", "Vincent", "Neil"),
        ("男" to "老年") to listOf("Eldric Sage", "Arthur", "Vincent"),
        ("女" to "儿童") to listOf("Bunny", "Mochi"),
        ("女" to "少年") to listOf("Stella", "Nini", "Mia"),
        ("女" to "青年") to listOf("Serena", "Cherry", "Maia", "Seren", "Vivian", "Elias"),
        ("女" to "中年") to listOf("Maia", "Elias", "Bellona"),
        ("女" to "老年") to listOf("Bellona", "Maia"),
    )

    fun poolFor(gender: String, age: String): List<String> {
        val g = if (gender.startsWith("女")) "女" else "男"
        val a = ageKey(age)
        return pools[g to a] ?: pools[g to "青年"]!!
    }

    /** Pick the voice in the pool used by the fewest characters so far (main characters arrive first). */
    fun assign(gender: String, age: String, cast: List<CastMember>): String {
        val pool = poolFor(gender, age)
        val used = cast.filter { it.mergedInto == null }.groupingBy { it.voice }.eachCount()
        return pool.minByOrNull { used[it] ?: 0 } ?: pool.first()
    }

    /** 儿童/少年/青年/中年/老年; the model sometimes answers 少女、孩童、老人… */
    fun ageKey(age: String): String = when {
        age.contains("儿童") || age.contains("孩") || age.contains("童") -> "儿童"
        age.contains("少") -> "少年"
        age.contains("中年") -> "中年"
        age.contains("老") -> "老年"
        else -> "青年"
    }

    fun isDesigned(voice: String) = voice.startsWith("qwen-tts-vd")

    private val girlish = Regex("少年音|少年感|童声|稚嫩|奶声奶气|雌雄莫辨|中性|阴柔")

    /** Drops words that pull a male voice towards a female-sounding one. */
    fun maleSafe(desc: String) = desc.replace(girlish, "").replace(Regex("[，,]{2,}"), "，").trim('，', ',', ' ')

    /** Instruction for a preset voice: age/gender plus the character's own voice description when known. */
    fun persona(m: CastMember): String {
        val female = m.gender.startsWith("女")
        val base = persona(m.gender, m.age)
        var d = m.voiceDesc.trim().trimEnd('。')
        if (!female) d = maleSafe(d)
        val head = if (female || ageKey(m.age) == "儿童") base else "男声，$base"
        return if (d.isEmpty()) head else "$head，${d.take(40)}"
    }

    /** Short persona sent with every line so a shared voice still acts its age. */
    fun persona(gender: String, age: String): String {
        val f = gender.startsWith("女")
        val age = ageKey(age)
        return when {
            age.contains("儿童") -> if (f) "七八岁的小女孩" else "七八岁的小男孩"
            age.contains("少年") -> if (f) "十几岁的少女" else "十几岁的少年"
            age.contains("中年") -> if (f) "中年女性" else "中年男人"
            age.contains("老年") -> if (f) "上了年纪的老妇人" else "上了年纪的老人"
            else -> if (f) "年轻女子" else "年轻男子"
        }
    }
}
