package com.daoyu.tingshu.tts

data class Voice(val id: String, val name: String, val desc: String, val instruct: Boolean = true)

object Engines {
    const val EDGE = "edge"
    const val COSY = "cosy"
    const val GEMINI = "gemini"
    const val SYSTEM = "system"
    const val QWEN = "qwen"
    const val DOUBAO = "doubao"          // multi-role dialogue only

    val all = listOf(EDGE, COSY, QWEN, GEMINI, SYSTEM)

    fun label(e: String) = when (e) {
        EDGE -> "微软 Edge"
        COSY -> "CosyVoice"
        GEMINI -> "Gemini"
        QWEN -> "通义千问"
        SYSTEM -> "系统朗读"
        DOUBAO -> "豆包语音"
        "director" -> "角色分析"
        "voicedesign" -> "定制音色"
        else -> e
    }

    fun hint(e: String) = when (e) {
        EDGE -> "免费 · 在线神经语音"
        COSY -> "阿里云 · 常规主力"
        GEMINI -> "Google · 精品说书"
        QWEN -> "阿里云 · 与 CosyVoice 同一个 Key · 可调语气"
        SYSTEM -> "离线 · 手机自带"
        else -> ""
    }

    val edgeVoices = listOf(
        Voice("zh-CN-XiaoxiaoNeural", "晓晓", "女声 · 温暖自然"),
        Voice("zh-CN-YunxiNeural", "云希", "男声 · 阳光，适合小说"),
        Voice("zh-CN-YunjianNeural", "云健", "男声 · 激昂，适合爽文"),
        Voice("zh-CN-XiaoyiNeural", "晓伊", "女声 · 活泼"),
        Voice("zh-CN-YunyangNeural", "云扬", "男声 · 播音腔"),
        Voice("zh-CN-YunxiaNeural", "云夏", "童声"),
        Voice("zh-CN-liaoning-XiaobeiNeural", "晓北", "东北话"),
        Voice("zh-CN-shaanxi-XiaoniNeural", "晓妮", "陕西话"),
        Voice("zh-TW-HsiaoChenNeural", "曉臻", "台湾腔 女声"),
        Voice("zh-HK-HiuMaanNeural", "曉曼", "粤语 女声"),
    )

    /** System voices of cosyvoice-v3-flash / v3-plus (v3.5 models only accept cloned/designed voices). */
    val cosyVoices = listOf(
        Voice("longxiu_v3", "龙修", "男声 · 博学说书人"),
        Voice("longsanshu_v3", "龙三叔", "男声 · 沉稳有质感"),
        Voice("longmiao_v3", "龙妙", "女声 · 抑扬顿挫有声书"),
        Voice("longyue_v3", "龙悦", "女声 · 温暖磁性"),
        Voice("longyuan_v3", "龙媛", "女声 · 温暖治愈"),
        Voice("longwanjun_v3", "龙婉君", "女声 · 细腻柔和"),
        Voice("longnan_v3", "龙楠", "男声 · 睿智青年"),
        Voice("longyichen_v3", "龙逸尘", "男声 · 洒脱活力"),
        Voice("longlaobo_v3", "龙老伯", "男声 · 沧桑老者"),
        Voice("longlaoyi_v3", "龙老姨", "女声 · 沉稳长者"),
    )

    /**
     * Qwen3-TTS voices. Gender/age were checked by listening to a sample of each voice, not taken from names.
     * instruct = false: the voice cannot take a style instruction (dialects).
     */
    val qwenVoices = listOf(
        Voice("Vincent", "田叔", "男声 · 中年，浑厚有叙事感（推荐旁白）"),
        Voice("Eldric Sage", "沧明子", "男声 · 老者，沙哑沧桑"),
        Voice("Arthur", "徐大爷", "男声 · 老者，低沉苍老"),
        Voice("Neil", "阿闻", "男声 · 青年，新闻播音"),
        Voice("Ethan", "晨煦", "男声 · 青年，阳光温暖"),
        Voice("Moon", "月白", "男声 · 青年，率性帅气"),
        Voice("Kai", "凯", "男声 · 青年，低沉轻柔"),
        Voice("Elias", "墨讲师", "女声 · 青年，柔美叙述"),
        Voice("Serena", "苏瑶", "女声 · 青年，温柔"),
        Voice("Cherry", "芊悦", "女声 · 青年，阳光亲切"),
        Voice("Maia", "四月", "女声 · 青年，知性"),
        Voice("Seren", "小婉", "女声 · 青年，温和舒缓"),
        Voice("Mia", "乖小妹", "女声 · 青年，温顺"),
        Voice("Vivian", "十三", "女声 · 青年，可爱小暴躁"),
        Voice("Bellona", "燕铮莺", "女声 · 中老年，低沉沉稳"),
        Voice("Stella", "少女阿月", "女声 · 稚嫩少女"),
        Voice("Nini", "邻家妹妹", "女声 · 稚嫩甜糯"),
        Voice("Bunny", "萌小姬", "童声 · 小女孩"),
        Voice("Mochi", "沙小弥", "童声 · 小女孩，聪慧"),
        Voice("Pip", "顽屁小孩", "童声 · 小男孩，调皮"),
        Voice("Dylan", "晓东", "北京话 · 男声青年", instruct = false),
        Voice("Marcus", "秦川", "陕西话 · 男声", instruct = false),
        Voice("Eric", "程川", "四川话 · 男声中年", instruct = false),
        Voice("Sunny", "晴儿", "四川话 · 女声青年", instruct = false),
        Voice("Jada", "阿珍", "上海话 · 女声（偏稚嫩）", instruct = false),
        Voice("Peter", "李彼得", "天津话 · 男声", instruct = false),
        Voice("Rocky", "阿强", "粤语 · 男声中年", instruct = false),
        Voice("Kiki", "阿清", "粤语 · 女声", instruct = false),
    )

    val geminiVoices = listOf(
        Voice("Charon", "Charon", "男声 · 沉稳讲述"),
        Voice("Sadaltager", "Sadaltager", "男声 · 博学"),
        Voice("Algenib", "Algenib", "男声 · 沙哑磁性"),
        Voice("Fenrir", "Fenrir", "男声 · 激昂"),
        Voice("Orus", "Orus", "男声 · 坚定"),
        Voice("Iapetus", "Iapetus", "男声 · 清晰"),
        Voice("Puck", "Puck", "男声 · 轻快"),
        Voice("Kore", "Kore", "女声 · 坚定"),
        Voice("Sulafat", "Sulafat", "女声 · 温暖"),
        Voice("Vindemiatrix", "Vindemiatrix", "女声 · 温柔"),
        Voice("Aoede", "Aoede", "女声 · 轻盈"),
        Voice("Gacrux", "Gacrux", "女声 · 成熟"),
        Voice("Achernar", "Achernar", "女声 · 柔和"),
        Voice("Leda", "Leda", "女声 · 年轻"),
    )

    fun voicesOf(e: String) = when (e) {
        EDGE -> edgeVoices
        COSY -> cosyVoices
        GEMINI -> geminiVoices
        QWEN -> qwenVoices
        else -> emptyList()
    }
}
