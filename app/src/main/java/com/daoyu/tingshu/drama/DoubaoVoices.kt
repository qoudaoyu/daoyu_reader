package com.daoyu.tingshu.drama

/**
 * 豆包语音 2.0 voices usable for characters (seed-tts-2.0). IDs were verified against the API; names follow the
 * Volcengine voice library. Cartoon / customer-service style voices are left out.
 * `expr` is a measured expressiveness score (0–10): the same angry / grieving / overjoyed lines were rendered with
 * every voice and rated blind. Some voices stay flat whatever the text says, so automatic picks avoid them.
 */
data class DbVoice(val id: String, val name: String, val gender: String, val age: String, val desc: String, val expr: Float = 5f)

object DoubaoVoices {
    private fun b(p: String) = "zh_${p}_uranus_bigtts"
    private fun r(p: String) = "ICL_uranus_zh_${p}_tob"

    val all: List<DbVoice> = listOf(
        // ---- 男 · 中年 / 老年
        DbVoice(b("male_yuanboxiaoshu"), "渊博小叔", "男", "中年", "低沉温和，学识渊博的中年男人", 3.8f),
        DbVoice(b("male_baqiqingshu"), "霸气青叔", "男", "中年", "霸气威严的大叔", 7.2f),
        DbVoice(r("male_shenchenzongcai"), "深沉总裁", "男", "中年", "低沉深沉，城府很深的上位者", 6.8f),
        DbVoice(r("male_lengjunshangsi"), "冷峻上司", "男", "中年", "冷峻严厉，不苟言笑", 4.8f),
        DbVoice(b("male_aojiaobazong"), "傲娇霸总", "男", "中年", "低沉成熟，强势霸道", 6.7f),
        DbVoice(r("male_ruyacaijun"), "儒雅才俊", "男", "中年", "稳重儒雅的大叔，温润谦虚", 4.5f),
        DbVoice(r("male_youmoshushu"), "幽默叔叔", "男", "中年", "风趣幽默的叔叔", 3.7f),
        DbVoice(b("male_qingcang"), "擎苍", "男", "中年", "醇厚沉稳，有江湖气", 5.3f),
        DbVoice(b("male_gaolengchenwen"), "高冷沉稳", "男", "中年", "低沉冷静，沉稳寡言", 6.2f),
        DbVoice(b("male_cixingjieshuonan"), "磁性解说", "男", "中年", "磁性低沉的成熟男声", 3.3f),
        DbVoice(b("male_dongfanghaoran"), "东方浩然", "男", "老年", "低沉微哑，年长持重", 3.7f),
        DbVoice(b("male_xuanyijieshuo"), "悬疑解说", "男", "老年", "低沉沙哑，阴郁神秘", 5.0f),
        DbVoice(b("male_shenyeboke"), "深夜播客", "男", "老年", "低沉舒缓，阅历丰富", 6.7f),
        // ---- 男 · 青年
        DbVoice(r("male_zhengzhiqingnian"), "正直青年", "男", "青年", "性格稳重的理想主义青年，天真刚正", 4.2f),
        DbVoice(r("male_lengkugege"), "冷酷哥哥", "男", "青年", "冷酷寡言", 5.3f),
        DbVoice(r("male_lengmoxiongzhang"), "冷漠兄长", "男", "青年", "低沉冷漠的兄长", 5.8f),
        DbVoice(r("male_lengdanshuli"), "冷淡疏离", "男", "青年", "冷淡疏离，惜字如金", 4.8f),
        DbVoice(r("male_fuheigongzi"), "腹黑公子", "男", "青年", "表面温和，内心腹黑", 5.7f),
        DbVoice(r("male_guaogongzi"), "孤傲公子", "男", "青年", "孤傲清高的公子", 5.2f),
        DbVoice(r("male_pianpiangongzi"), "翩翩公子", "男", "青年", "温润如玉的翩翩公子", 4.7f),
        DbVoice(r("male_xiaosasuixing"), "潇洒随性", "男", "青年", "爽朗通透，洒脱豁达", 3.2f),
        DbVoice(r("male_youroubangzhu"), "优柔帮主", "男", "青年", "气泡音少帮主，优柔寡断", 5.0f),
        DbVoice(r("male_lvchaxiaoge"), "绿茶小哥", "男", "青年", "温柔呆萌，表面无害内心深沉", 5.7f),
        DbVoice(r("male_nuanxintitie"), "暖心体贴", "男", "青年", "温柔帅气，细腻体贴", 6.0f),
        DbVoice(r("male_wenrouxuezhang"), "温柔学长", "男", "青年", "温柔的学长", 4.0f),
        DbVoice(r("male_bingjiaonanyou"), "病娇男友", "男", "青年", "偏执病娇", 5.0f),
        DbVoice(r("male_kailangqingnian"), "开朗青年", "男", "青年", "开朗热情", 6.2f),
        DbVoice(r("male_badaoshaoye"), "霸道少爷", "男", "青年", "霸道张扬的少爷", 6.2f),
        DbVoice(r("male_aomanshaoye"), "傲慢少爷", "男", "青年", "傲慢无礼的纨绔少爷", 4.2f),
        DbVoice(r("male_aojiaogongzi"), "傲娇公子", "男", "青年", "傲娇别扭", 5.7f),
        DbVoice(r("male_shaonianjiangjun"), "少年将军", "男", "青年", "英武果决的年轻将军", 5.0f),
        DbVoice(r("male_sajiaonianren"), "撒娇粘人", "男", "青年", "温柔羞怯的小奶狗", 5.5f),
        DbVoice(b("male_ruyayichen"), "儒雅逸辰", "男", "青年", "轻柔温润，沉静内敛", 8.8f),
        DbVoice(b("male_yangguangqingnian"), "阳光青年", "男", "青年", "温润亲切", 4.7f),
        DbVoice(b("male_wenrouxiaoge"), "温柔小哥", "男", "青年", "温和醇厚", 3.5f),
        DbVoice(b("male_m191"), "云舟", "男", "青年", "温和低沉，从容", 4.7f),
        DbVoice(b("male_taocheng"), "小天", "男", "青年", "温润亲切", 4.7f),
        DbVoice(b("male_liufei"), "刘飞", "男", "青年", "低沉温和，儒雅", 7.2f),
        DbVoice(b("male_dayi"), "大壹", "男", "青年", "沉稳亲切", 4.3f),
        // ---- 男 · 少年 / 儿童
        DbVoice(b("male_shaonianzixin"), "少年梓辛", "男", "少年", "清澈温和的少年", 4.7f),
        DbVoice(r("male_qingsexiaosheng"), "青涩小生", "男", "少年", "稚气未脱的少年郎，赤诚热忱", 4.2f),
        DbVoice(r("male_shuanglangshaonian"), "爽朗少年", "男", "少年", "爽朗明快", 6.8f),
        DbVoice(r("male_fengfashaonian"), "风发少年", "男", "少年", "意气风发", 6.2f),
        DbVoice(r("male_chunzhenxuedi"), "纯真学弟", "男", "少年", "纯真腼腆", 5.0f),
        DbVoice(r("male_bingruoshaonian"), "病弱少年", "男", "少年", "体弱温和", 8.0f),
        DbVoice(r("male_tiancaitongzhuo"), "天才同桌", "男", "少年", "聪明冷静", 7.0f),
        DbVoice(r("male_mengdongqingnian"), "懵懂青年", "男", "少年", "懵懂单纯", 7.2f),
        DbVoice(r("male_shuaizhenxiaohuo"), "率真小伙", "男", "少年", "暴躁心软，率真坦诚", 3.0f),
        DbVoice(b("male_linjiananhai"), "邻家男孩", "男", "少年", "邻家男孩", 6.5f),
        DbVoice(b("male_naiqimengwa"), "奶气萌娃", "男", "儿童", "奶声奶气的小孩", 3.2f),
        // ---- 女
        DbVoice(b("female_gaolengyujie"), "高冷御姐", "女", "青年", "高冷御姐", 2.5f),
        DbVoice(r("female_xingganyujie"), "性感御姐", "女", "青年", "妩媚性感", 3.5f),
        DbVoice(r("female_chengshujiejie"), "成熟姐姐", "女", "青年", "成熟知性", 3.5f),
        DbVoice(r("female_bingjiaojiejie"), "病娇姐姐", "女", "青年", "偏执病娇", 1.8f),
        DbVoice(r("female_aojiaonvyou"), "傲娇女友", "女", "青年", "傲娇", 7.0f),
        DbVoice(r("female_tiexinnvyou"), "贴心女友", "女", "青年", "温柔贴心", 2.2f),
        DbVoice(r("female_nuanxinxuejie"), "暖心学姐", "女", "青年", "温暖亲切", 3.5f),
        DbVoice(b("female_meilinvyou"), "魅力女友", "女", "青年", "温和轻柔", 7.5f),
        DbVoice(b("female_roumeinvyou"), "柔美女友", "女", "青年", "轻柔清甜", 5.5f),
        DbVoice(b("female_gufengshaoyu"), "古风少御", "女", "青年", "清澈甜美，古风", 7.2f),
        DbVoice(b("female_shuangkuaisisi"), "爽快思思", "女", "青年", "爽快利落", 6.0f),
        DbVoice(b("female_cancan"), "灿灿", "女", "青年", "温婉柔和", 6.2f),
        DbVoice(b("female_vv"), "Vivi", "女", "青年", "知性亲切", 6.3f),
        DbVoice(b("female_xiaohe"), "小何", "女", "青年", "温和舒缓", 6.2f),
        DbVoice(b("female_kailangjiejie"), "开朗姐姐", "女", "青年", "开朗大方", 4.8f),
        DbVoice(b("female_wenroushunv"), "温柔淑女", "女", "青年", "温柔端庄", 4.5f),
        DbVoice(b("female_qingchezizi"), "清澈梓梓", "女", "青年", "清澈甜美", 3.3f),
        DbVoice(b("female_linjianvhai"), "邻家女孩", "女", "青年", "柔和温润", 4.5f),
        DbVoice(b("female_tianmeiyueyue"), "甜美悦悦", "女", "青年", "温润柔和", 5.2f),
        DbVoice(b("female_wenrouxiaoya"), "温柔小雅", "女", "中年", "温和轻柔，成熟沉静", 8.3f),
        DbVoice(b("female_shaoergushi"), "少儿故事", "女", "中年", "温润和缓，像妈妈讲故事", 3.7f),
        DbVoice(r("female_chunzhenshaonv"), "纯真少女", "女", "少年", "天真烂漫的少女", 4.5f),
        DbVoice(r("female_tiaopigongzhu"), "调皮公主", "女", "少年", "娇俏调皮", 6.5f),
        DbVoice(r("female_jiaoruoluoli"), "娇弱萝莉", "女", "少年", "娇弱稚嫩", 2.5f),
        DbVoice(b("female_tianmeixiaoyuan"), "甜美小源", "女", "少年", "甜美柔和", 4.5f),
        DbVoice(b("female_mizai"), "咪仔", "女", "少年", "清亮活泼", 6.0f),
        DbVoice(b("female_mengyatou"), "萌丫头", "女", "儿童", "尖亮俏皮的小丫头", 6.7f),
        // ---- 2.0 角色扮演 / 通用 / 有声阅读（第二批，按控制台音色库补齐）
        DbVoice(r("female_linjuayi"), "邻居阿姨", "女", "中年", "温暖成熟的中年阿姨，兼具知性气质，待人耐心和善", 6.7f),
        DbVoice(r("female_xiemeinvwang"), "邪魅女王", "女", "青年", "邪魅残酷的病娇女孩，气质魅惑", 8.3f),
        DbVoice(r("female_bingruoshaonv"), "病弱少女", "女", "少年", "稚气可爱的妹妹，懦弱谦虚，语气轻柔", 7.3f),
        DbVoice(r("female_chuliannvyou"), "初恋女友", "女", "少年", "元气明亮的女生，邻家妹妹般灵动甜美", 6.2f),
        DbVoice(r("female_yuanqitianmei"), "元气甜妹", "女", "少年", "可爱的元气甜妹，性格活泼", 6.2f),
        DbVoice(r("female_tianmeijiaoqiao"), "甜美娇俏", "女", "少年", "声线甜美娇俏的少女，灵动直率", 6.0f),
        DbVoice(r("female_huopodiaoman"), "活泼刁蛮", "女", "少年", "傲娇酷拽的小公主，明眸皓齿，天真灵动", 5.7f),
        DbVoice(r("female_jinglingxiangdao"), "精灵向导", "女", "少年", "元气带夹子音的少女，甜美可爱", 5.0f),
        DbVoice(r("female_qingxinshaonv"), "倾心少女", "女", "少年", "说话软乎乎的乖巧女孩，天真呆萌很讨喜", 4.8f),
        DbVoice(r("female_huoponvhai"), "活泼女孩", "女", "少年", "明亮天真的甜美女友，阳光活泼，满是活力", 4.7f),
        DbVoice(r("female_tiexinmeimei"), "贴心妹妹", "女", "少年", "声线甜美有活力的妹妹，活泼开朗", 4.5f),
        DbVoice(r("female_keainvsheng"), "可爱女生", "女", "少年", "甜美的漫画少女，天真活泼，满是热情", 4.2f),
        DbVoice(r("female_jiaxiaozi"), "假小子", "女", "少年", "直率果敢的校园假小子，豪爽大胆超有范儿", 4.2f),
        DbVoice(r("female_tianmeihuopo"), "甜美活泼", "女", "少年", "声线清甜灵动的少女，活泼乐观", 3.3f),
        DbVoice(r("female_heainainai"), "和蔼奶奶", "女", "老年", "慈祥的老奶奶，耐心亲切，散发着岁月沉淀的温柔", 5.7f),
        DbVoice(r("female_wenrounvshen"), "温柔女神", "女", "青年", "声线知性温柔的女神，温暖知性", 7.8f),
        DbVoice(r("female_xingganmeihuo"), "性感魅惑", "女", "青年", "性感的魅惑姐姐，明艳动人，待人热情", 7.8f),
        DbVoice(r("female_bingjiaomengmei"), "病娇萌妹", "女", "青年", "破碎感美人，偏执但纯真呆萌", 7.7f),
        DbVoice(r("female_tiexinguimi"), "贴心闺蜜", "女", "青年", "温暖治愈的女生，贴心的闺蜜", 7.0f),
        DbVoice(r("female_chunchenvsheng"), "纯澈女生", "女", "青年", "声线温柔成熟的女生，性格治愈", 6.7f),
        DbVoice(r("female_xiuliqianqian"), "秀丽倩倩", "女", "青年", "成熟稳重的温柔学姐，性格严谨亲切", 6.5f),
        DbVoice(r("female_nuanxinqianqian"), "暖心茜茜", "女", "青年", "开朗的女生，贴心优雅，温暖治愈", 6.5f),
        DbVoice(r("female_kailangtingting"), "开朗婷婷", "女", "青年", "阳光温暖的女生，大方开朗又自信", 6.5f),
        DbVoice(r("female_xiemeiyujie"), "邪魅御姐", "女", "青年", "性感优雅且风情万种的御姐，嚣张自信，成熟妩媚", 6.2f),
        DbVoice(r("female_lixingyuanzi"), "理性圆子", "女", "青年", "清冷干脆的直爽女生，性格果断理性", 6.0f),
        DbVoice(r("female_qingxixiaoxue"), "清晰小雪", "女", "青年", "性格细致理性又耐心女生，做事沉稳可靠", 5.8f),
        DbVoice(r("female_lingdongxinxin"), "灵动欣欣", "女", "青年", "稚气干净的女教师，性格内敛温暖", 5.7f),
        DbVoice(r("female_wenroubaiyueguang"), "温柔白月光", "女", "青年", "成熟温柔的邻家大姐姐，平易近人", 5.5f),
        DbVoice(r("female_tianmeixiaoju"), "甜美小橘", "女", "青年", "温柔知性的辅导员，善解人意", 5.3f),
        DbVoice(r("female_wumeiyujie"), "妩媚御姐", "女", "青年", "妩媚美人，风情万种超迷人", 5.2f),
        DbVoice(r("female_rouguhunshi"), "柔骨魂师", "女", "青年", "柔骨铮铮的魂师，专情柔韧", 5.0f),
        DbVoice(r("female_jiaohannvwang"), "娇憨女王", "女", "青年", "腹黑性感女王，又是傲娇任性的笨蛋美人", 4.8f),
        DbVoice(r("female_wumeikeren"), "妩媚可人", "女", "青年", "性感妩媚的姐姐，性格邪魅成熟", 4.8f),
        DbVoice(r("female_qingtiantaotao"), "清甜桃桃", "女", "青年", "给人温暖的甜美女生，心思细腻温柔", 4.8f),
        DbVoice(r("female_qinglenggaoya"), "清冷高雅", "女", "青年", "清冷高雅的隐士，淡泊疏离", 4.7f),
        DbVoice(r("female_zhixinjiejie"), "知心姐姐", "女", "青年", "优雅的知心姐姐，温柔治愈、善解人意", 4.7f),
        DbVoice(r("female_ruanmengtangtang"), "软萌糖糖", "女", "青年", "无私奉献的青年教师，甜美清新，充满热情", 4.7f),
        DbVoice(r("female_kaixinxiaohong"), "开心小鸿", "女", "青年", "明亮干净的博学良师，教学沉稳扎实", 4.7f),
        DbVoice(r("female_chengshuwenrou"), "成熟温柔", "女", "青年", "声线温润成熟的陪伴者，温柔有魅力", 4.5f),
        DbVoice(r("female_aomanjiaosheng"), "傲慢娇声", "女", "青年", "腹黑傲娇的大小姐，娇气自满，自带优越感", 4.5f),
        DbVoice(r("female_tianmeixiaoyu"), "甜美小雨", "女", "青年", "温柔清澈的青年老师，性格理性自信", 4.0f),
        DbVoice(r("female_qingtianmeimei"), "清甜莓莓", "女", "青年", "明亮阳光的女生，性格理性自信", 3.8f),
        DbVoice(r("female_wenrouwenya"), "温柔文雅", "女", "青年", "儒雅古风大小姐，温婉柔和，举手投足尽显典雅", 3.5f),
        DbVoice(r("female_guaiqiaokeer"), "乖巧可儿", "女", "青年", "低沉贴心的班主任，温柔睿智又理性", 3.3f),
        DbVoice(r("female_ruanmengtuanzi"), "软萌团子", "女", "青年", "负责任的教师，做事认真，对学生有耐心", 3.2f),
        DbVoice(r("female_zhixingwenwan"), "知性温婉", "女", "青年", "秀外慧中的美女姐姐，性格恬静淡然", 2.7f),
        DbVoice(r("male_younidashu"), "油腻大叔", "男", "中年", "沧桑的大叔，性格张扬，十分自信爱吹牛", 7.8f),
        DbVoice(r("male_huzishushu"), "胡子叔叔", "男", "中年", "历经风雨后变得沉稳的大叔，做事果敢，让人信赖", 6.7f),
        DbVoice(r("male_guiyishenmi"), "诡异神秘", "男", "中年", "诡异神秘的大叔，气质沧桑，自带压迫感", 6.3f),
        DbVoice(r("male_wenrunxuezhe"), "温润学者", "男", "中年", "专业的纪录片解说员，沉稳从容，声音浑厚", 6.3f),
        DbVoice(r("male_shenmifashi"), "神秘法师", "男", "中年", "声音低沉浑厚的大叔，满是慈爱，让人信赖", 5.7f),
        DbVoice(r("male_chenwenmingzai"), "沉稳明仔", "男", "中年", "磁性低沉的中年男老师，博学多才且严厉", 2.7f),
        DbVoice(r("male_bingjiaoshaonian"), "病娇少年", "男", "少年", "腹黑的病娇男，性格偏执，情绪容易极端", 9.0f),
        DbVoice(r("male_cujingnanyou"), "醋精男友", "男", "少年", "有少年感、气质干净的爱吃醋撒娇年下男", 8.8f),
        DbVoice(r("male_yiqishaonian"), "意气少年", "男", "少年", "直率带少年感的有志青年，性格坚定积极", 7.7f),
        DbVoice(r("male_naiqixiaosheng"), "奶气小生", "男", "少年", "少年感阳光奶狗高中生，阳光直率", 7.5f),
        DbVoice(r("male_xiaohouye"), "小侯爷", "男", "少年", "充满少年感的小公子，丰神俊朗，阳光潇洒又直率", 7.2f),
        DbVoice(r("male_bingruogongzi"), "病弱公子", "男", "少年", "带少年感的病快快公子，沉稳温柔，自带易碎感", 6.7f),
        DbVoice(r("male_chanruoshaoye"), "孱弱少爷", "男", "少年", "清澈少年感与温润气质的病快快公子，温柔体贴", 5.8f),
        DbVoice(r("male_yuanqishaonian"), "元气少年", "男", "少年", "极具少年感阳光男大，声音清澈，性格直爽乐观", 5.7f),
        DbVoice(r("male_wenrounantongzhuo"), "温柔男同桌", "男", "少年", "温暖帅气的翩翩少年，性格温柔善良", 5.7f),
        DbVoice(r("male_ganjingshaonian"), "干净少年", "男", "少年", "声音沉稳的阳光青年，稳重乐观", 5.2f),
        DbVoice(r("male_qinqiexiaozhuo"), "亲切小卓", "男", "少年", "帅气少年感的青年教师，性格负责细心", 5.2f),
        DbVoice(r("male_rexueshaonian"), "热血少年", "男", "少年", "带少年感且清澈的阳光青年，性格开朗自信", 5.0f),
        DbVoice(r("male_qingshuangshaonian"), "清爽少年", "男", "少年", "清爽少年感的清爽少年，性格乐观阳光", 5.0f),
        DbVoice(r("male_neiliancaijun"), "内敛才俊", "男", "少年", "细心又贴心的少年，性格温暖又内敛", 4.8f),
        DbVoice(r("male_yangguangyangyang"), "阳光洋洋", "男", "少年", "有少年气的新老师，性格赤诚热忱", 4.8f),
        DbVoice(r("male_xuebatongzhuo"), "学霸同桌", "男", "少年", "低沉浑厚的暖心学霸，性格乐观积极", 4.7f),
        DbVoice(r("male_xuebanantongzhuo"), "学霸男同桌", "男", "少年", "清爽男高兼学霸同桌，木讷内敛，却很靠谱", 4.0f),
        DbVoice(r("male_wennuanshaonian"), "温暖少年", "男", "少年", "温柔清澈的体贴少年，待人谦和有礼", 3.7f),
        DbVoice(r("male_qingxinbobo"), "清新波波", "男", "少年", "温柔少年音的年轻男生，贴心温暖", 3.7f),
        DbVoice(r("male_wenshunshaonian"), "温顺少年", "男", "少年", "带少年感且温暖的乖巧少年，温顺斯文", 3.5f),
        DbVoice(r("male_chengshuzongcai"), "成熟总裁", "男", "老年", "声音苍老浑厚的董事长，沉稳可靠，自带长者威严", 7.3f),
        DbVoice(r("male_youmodaye"), "幽默大爷", "男", "老年", "矍铄沧桑的乐观爷爷，通透豁达又从容", 4.2f),
        DbVoice(r("male_xiaozhangxiaoge"), "嚣张小哥", "男", "青年", "张扬跋扈的小哥，嚣张自信，性格有些傲慢", 8.7f),
        DbVoice(r("male_gugaogongzi"), "孤高公子", "男", "青年", "腹黑果决的傲娇公子，直爽潇洒", 8.7f),
        DbVoice(r("male_paoxiaoxiaoge"), "咆哮小哥", "男", "青年", "凶冷低沉的咆哮青年，性格冷酷带侵略性", 8.7f),
        DbVoice(r("male_aomanqingnian"), "傲慢青年", "男", "青年", "明亮张扬的富家公子哥，性格傲慢嚣张", 8.7f),
        DbVoice(r("male_lanyincaohunshi"), "蓝银草魂师", "男", "青年", "忠诚坚韧，默默为你遮风挡雨的蓝银草魂师", 8.2f),
        DbVoice(r("male_jilingxiaohuo"), "机灵小伙", "男", "青年", "音色成熟磁性的腹黑青年，心思缜密", 8.2f),
        DbVoice(r("male_zhangjianjunzi"), "仗剑君子", "男", "青年", "声音低沉的热心公子，果敢直爽，沉稳坚定", 8.0f),
        DbVoice(r("male_tiexinnanyou"), "贴心男友", "男", "青年", "邻家小哥哥，骑士男二型，超会照顾人", 8.0f),
        DbVoice(r("male_cujingnansheng"), "醋精男生", "男", "青年", "清亮干净的年下男，醋意十足爱撒娇", 7.8f),
        DbVoice(r("male_anrenqinzhu"), "黯刃秦主", "男", "青年", "低沉帅气的林霸主，性格坚定深情", 7.8f),
        DbVoice(r("male_bujiqingnian"), "不羁青年", "男", "青年", "干净阳光的乐观青年，性格阳光积极", 7.7f),
        DbVoice(r("male_zhongerqingnian"), "中二青年", "男", "青年", "张扬清亮的自信青年，性格自信傲慢", 7.5f),
        DbVoice(r("male_lengmonanyou"), "冷漠男友", "男", "青年", "浑厚磁性的冷漠男，酷拽高冷，处事格外内敛", 7.5f),
        DbVoice(r("male_sajiaonanyou"), "撒娇男友", "男", "青年", "干净清澈的撒娇奶狗，敏感粘人又热情", 7.2f),
        DbVoice(r("male_ruyajunzi"), "儒雅君子", "男", "青年", "儒雅温柔的温文青年，性格温柔细心，为人内敛谦和", 7.2f),
        DbVoice(r("male_sajiaonansheng"), "撒娇男生", "男", "青年", "奶狗型男生，干净爱撒娇，敏感粘人", 6.8f),
        DbVoice(r("male_cixingnansang"), "磁性男嗓", "男", "青年", "磁性浑厚的贴心男友，性格酷拽沉稳", 6.8f),
        DbVoice(r("male_bingjiaodidi"), "病娇弟弟", "男", "青年", "腹黑残暴的绿茶弟弟，缺爱且占有欲极强", 6.8f),
        DbVoice(r("male_zifuqingnian"), "自负青年", "男", "青年", "张扬明亮的富家公子哥，性格傲慢嚣张", 6.5f),
        DbVoice(r("male_aojiaojingying"), "傲娇精英", "男", "青年", "高冷沉稳的傲娇男，身为精英却口是心非", 6.5f),
        DbVoice(r("male_qingseqingnian"), "青涩青年", "男", "青年", "冷酷低沉的青涩小奶狗，性格理性霸道，却很直爽", 6.2f),
        DbVoice(r("male_lenglianxueba"), "冷脸学霸", "男", "青年", "沉稳低沉的高冷侦探，性格从容包容", 6.2f),
        DbVoice(r("male_chenwenyouya"), "沉稳优雅", "男", "青年", "睿智稳重、醇厚从容的优雅绅士", 6.2f),
        DbVoice(r("male_zhishuaiqingnian"), "直率青年", "男", "青年", "清澈干练的直爽青年，性格理性坚定", 6.0f),
        DbVoice(r("male_satuoqingnian"), "洒脱青年", "男", "青年", "清亮阳光的元气青年，性格积极向上", 6.0f),
        DbVoice(r("male_bingjiaogege"), "病娇哥哥", "男", "青年", "腹黑傲娇的病娇哥哥，偏执敏感，多疑又易碎", 6.0f),
        DbVoice(r("male_wenrounanyou"), "温柔男友", "男", "青年", "低沉儒雅的引导型年上男，坚定温柔", 5.8f),
        DbVoice(r("male_guayanxiaoge"), "寡言小哥", "男", "青年", "寡言少语但温柔贴心的男生", 5.8f),
        DbVoice(r("male_guzhibingjiao"), "固执病娇", "男", "青年", "腹黑的病娇男主，偏执乖戾，占有欲强", 5.7f),
        DbVoice(r("male_zhenbiandiyu"), "枕边低语", "男", "青年", "温暖深沉的帅气青年，温柔细心又沉稳可靠", 5.7f),
        DbVoice(r("male_lingyunqingnian"), "凌云青年", "男", "青年", "低沉浑厚的冷酷总裁，性格高冷理性", 5.7f),
        DbVoice(r("male_lengjungaozhi"), "冷峻高智", "男", "青年", "张扬直爽的高冷侦探，性格耐心温柔", 5.7f),
        DbVoice(r("male_dichenqianquan"), "低沉缱绻", "男", "青年", "声线低沉缱绻的恋人，深情专注", 5.7f),
        DbVoice(r("male_yangyang"), "炀炀", "男", "青年", "阳光爽朗的元气小哥，语气明快有活力", 5.7f),
        DbVoice(r("male_badaozongcai"), "霸道总裁", "男", "青年", "沉稳冷静的霸道总裁，性格魅惑成熟，气场强大", 5.7f),
        DbVoice(r("male_tianxinanyou"), "甜系男友", "男", "青年", "低沉温暖的贴心男友，暖心温柔", 5.5f),
        DbVoice(r("male_zhangjianxiake"), "仗剑侠客", "男", "青年", "清亮坚决的侠客，乐于助人，行事果敢", 5.3f),
        DbVoice(r("male_siwenqingnian"), "斯文青年", "男", "青年", "腹黑果决的温文青年，性格温柔细心", 5.3f),
        DbVoice(r("male_qinqieqingnian"), "亲切青年", "男", "青年", "干净清亮的男友，善解人意，温暖斯文又亲切", 5.3f),
        DbVoice(r("male_lengaozongcai"), "冷傲总裁", "男", "青年", "高冷毒蛇总裁，性格孤傲，冷酷理性", 5.2f),
        DbVoice(r("male_huoponanyou"), "活泼男友", "男", "青年", "活泼开朗的活力青年，乐观热情", 5.2f),
        DbVoice(r("male_diyinchenyu"), "低音沉郁", "男", "青年", "高冷沉稳又成熟的霸道总裁，气质酷拽", 5.2f),
        DbVoice(r("male_ruyazongcai"), "儒雅总裁", "男", "青年", "浑厚磁性的儒雅上司，性格耐心温柔，做事坚定可靠", 5.0f),
        DbVoice(r("male_jingyingqingnian"), "精英青年", "男", "青年", "稳重磁性的高冷总裁，性格坚定理性", 5.0f),
        DbVoice(r("male_gaolengzongcai"), "高冷总裁", "男", "青年", "酷拽又成熟的霸道总裁", 5.0f),
        DbVoice(r("male_ruyagongzi"), "儒雅公子", "男", "青年", "风度翩翩的温文公子，性格坚定又沉稳", 5.0f),
        DbVoice(r("male_chunhoudiyin"), "醇厚低音", "男", "青年", "儒雅青年，性格耐心温柔，待人谦和有书卷气", 5.0f),
        DbVoice(r("male_qingyisugan"), "清逸苏感", "男", "青年", "清润苏感的陪伴者，温柔松弛", 4.8f),
        DbVoice(r("male_hanhoudunshi"), "憨厚敦实", "男", "青年", "呆萌喜感的憨厚大哥，质朴又善良", 4.5f),
        DbVoice(r("male_bingjiaobailian"), "病娇白莲", "男", "青年", "有点偏执的白月光男生，高颜值腹黑帅哥", 4.5f),
        DbVoice(r("male_menyoupingxiaoge"), "闷油瓶小哥", "男", "青年", "沉稳成熟的学长，性格内敛理性", 4.5f),
        DbVoice(r("male_qinglengjingui"), "清冷矜贵", "男", "青年", "清冷矜贵的智者，通透淡然", 4.5f),
        DbVoice(r("male_shuanglangxiaoyang"), "爽朗小阳", "男", "青年", "理性爽朗的男生，超有责任感", 4.5f),
        DbVoice(r("male_zixinqingnian"), "自信青年", "男", "青年", "稳重成熟的自信青年，性格温柔体贴，为人孤傲冷漠", 4.2f),
        DbVoice(r("male_yourougongzi"), "优柔公子", "男", "青年", "低沉带气泡感的温润小哥，难掩如玉气质", 4.2f),
        DbVoice(r("male_wenrouneilian"), "温柔内敛", "男", "青年", "柔和细腻的守护者，懂你的欲言又止", 4.2f),
        DbVoice(r("male_junyigongzi"), "俊逸公子", "男", "青年", "风度翩翩的公子，为人潇洒直率，举手投足尽显优雅", 3.8f),
        DbVoice(r("male_huoposhuanglang"), "活泼爽朗", "男", "青年", "阳光帅气的男生，性格热情豁达", 3.7f),
        DbVoice(r("male_lenglianxiongzhang"), "冷脸兄长", "男", "青年", "低沉稳重的冷脸兄长，性格高冷却很细心", 3.7f),
        DbVoice(r("male_aoqilingren"), "傲气凌人", "男", "青年", "腹黑酷拽的男三号，倨傲无礼，行事残暴", 3.3f),
        DbVoice(r("male_kailangqingkuai"), "开朗轻快", "男", "青年", "充满活力的新时代打工人，开朗乐观", 3.0f),
        DbVoice(r("male_qinglangwenrun"), "清朗温润", "男", "青年", "为人温暖亲切、气质清澈的男生", 2.8f),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** Unnamed speakers (路人 / 路人女): ordinary voices kept apart from the narrator list. */
    const val PASSERBY_M = "zh_male_yangguangqingnian_uranus_bigtts"
    const val PASSERBY_F = "zh_female_kailangjiejie_uranus_bigtts"

    /** Calm, even voices offered for narration (not yet blind-tested as narrators). */
    val narrators: List<com.daoyu.tingshu.tts.Voice> = listOf(
        b("male_m191"), r("male_wenrunxuezhe"), b("male_cixingjieshuonan"), b("male_shenyeboke"),
        b("female_xiaohe"), b("female_wenrouxiaoya"),
    ).map { id -> byId(id)!!.let { com.daoyu.tingshu.tts.Voice(it.id, it.name, "${it.gender}声 · ${it.desc}") } }

    /** Voices below this are only used when the user picks them. */
    const val MIN_EXPR = 4f

    /** Rule-based choice: same gender and age band, expressive voices first, least used so far. */
    fun assign(gender: String, age: String, cast: List<CastMember>, avoid: String? = null): String {
        val female = gender.startsWith("女")
        val used = cast.filter { it.mergedInto == null && it.doubaoVoice.isNotEmpty() }.groupingBy { it.doubaoVoice }.eachCount()
        val a = VoicePools.ageKey(age)
        val order = when (a) {
            "老年" -> listOf("老年", "中年")
            "中年" -> listOf("中年", "老年", "青年")
            "少年" -> listOf("少年", "青年")
            "儿童" -> listOf("儿童", "少年")
            else -> listOf("青年", "少年", "中年")
        }
        for (band in order) {
            val pool = all.filter { (it.gender == "女") == female && it.age == band && it.expr >= MIN_EXPR && it.id != avoid }
            if (pool.isNotEmpty()) return pool.sortedWith(compareBy<DbVoice> { used[it.id] ?: 0 }.thenByDescending { it.expr }).first().id
        }
        return all.filter { (it.gender == "女") == female }.maxByOrNull { it.expr }!!.id
    }

    /** Catalog lines for the director prompt: key|name|gender|age|desc. */
    fun catalogText(genders: Set<String> = setOf("男", "女")): String = all.filter { it.expr >= MIN_EXPR && it.gender in genders }
        .sortedWith(compareBy<DbVoice>({ it.gender }, { it.age }).thenByDescending { it.expr })
        .joinToString("\n") { "${it.id}|${it.name}|${it.gender}|${it.age}|${it.desc}|表现力${it.expr}" }

    /** True when [id] is a known voice of the right gender. */
    fun acceptable(id: String, gender: String): Boolean = byId(id)?.let { (it.gender == "女") == gender.startsWith("女") && it.expr >= MIN_EXPR } == true

    /** An automatic pick from an earlier version that turned out to be a flat voice. */
    fun isFlat(id: String) = byId(id)?.let { it.expr < MIN_EXPR } ?: false
}
