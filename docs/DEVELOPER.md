# 刀鱼听书 · 开发者说明

> 适用版本：1.4.0（versionCode 20）。本文写给接手这个项目的开发者，介绍怎么构建和签名、代码结构、各模块怎么配合、接入的每个云服务有哪些坑，以及做过哪些实测、得出了哪些结论。最终用户看的说明在根目录 `README.md`。

---

## 1. 项目概况

刀鱼听书是一款自用的安卓电子书阅读器，主打"听书"。

| 能力 | 说明 |
|---|---|
| 书架 | 封面墙、继续阅读卡片、无封面书自动生成封面、长按查看详情或删除 |
| 格式 | TXT（自动识别编码、自动分章）、EPUB（目录、封面、正文）。MOBI/AZW3 还没做 |
| 阅读 | 仿微信读书排版：自定义分页、两端对齐、标点避头尾、多套纸色主题、平移/覆盖翻页、音量键翻页、书签、目录 |
| WiFi 传书 | 手机上起一个局域网 HTTP 服务，电脑浏览器拖拽上传 |
| 单人说书 | 5 个可切换的 TTS 引擎：微软 Edge（免费）、阿里 CosyVoice、通义千问 Qwen3-TTS、Google Gemini、系统 TTS；带失败自动降级、缓存和用量统计 |
| 多角色说书 | 大模型分析每句对白的说话人和语气 → 旁白用 Edge，对白用豆包 2.0（或千问）按角色配音；每本书一张角色表 |

目标机型是 OPPO Find X8 Ultra（ColorOS）。手机不常开代理，所以默认路径上的服务都要能在国内直连。需要代理的服务（Gemini、Grok）一律做成可选项，并带自动降级。

---

## 2. 技术栈

- Kotlin 2.2.10、Jetpack Compose（BOM 2025.08.00）、Material 3
- minSdk 26 / targetSdk 35 / compileSdk 36，JVM 17
- AGP 8.11.1，Gradle Wrapper 8.14.3，KSP 2.2.10-2.0.2
- 数据存储：Room 2.7.2（数据库），DataStore Preferences（设置），EncryptedSharedPreferences（API Key）
- 网络：OkHttp 4.12（HTTP、WebSocket、SSE 流式读取）
- 其他依赖：jsoup（EPUB）、NanoHTTPD（传书服务）、zxing（二维码）、kotlinx-serialization-json
- 测试：JUnit 4、Robolectric 4.16、Roborazzi（Compose 截图，NATIVE 图形模式）

---

## 3. 构建与签名

```bash
export ANDROID_HOME=/path/to/android-sdk     # 或者建一个 local.properties 写 sdk.dir=…
./gradlew assembleDebug                      # app/build/outputs/apk/debug/
./gradlew assembleRelease                    # 有 keystore.properties 才会签名
./gradlew testDebugUnitTest                  # 离线测试；在线测试缺 Key 时自动跳过
```

**签名**：`app/build.gradle.kts` 从工程根目录的 `keystore.properties` 读取签名配置。这个文件和 `keystore/*.jks` 都不进版本库、也不进源码包，格式参照 `keystore.properties.example`。

- 没有这个文件时，release 版只会打成未签名的包。
- ⚠️ 用户手机上装的是用原始 `daoyu.jks` 签名的包。换签名就无法覆盖安装，用户数据会丢。请向项目所有者索取原签名文件，并妥善保存。

**版本号**在 `app/build.gradle.kts` 的 `versionCode` 和 `versionName`。设置页底部还写着一个"刀鱼听书 x.y.z"（`SettingsScreen.kt`），发版时要一起改。

**APK 体积**约 28 MB，大头是两个内置字体。`assets/fonts` 不要设成 `noCompress`，否则体积会超过 30 MB。

---

## 4. 目录结构

```
app/src/main/java/com/daoyu/tingshu/
├── App.kt                 Application：全局单例（db / storage / importer / settings / keys / tts / director）+ 一次性迁移
├── MainActivity.kt        导航（shelf / reader/{id} / cast/{id} / transfer / search / sources / sourceEdit / settings）、外部打开文件、音量键
├── data/
│   ├── Entities.kt        Room 实体：Book / Chapter / Bookmark / TtsUsage
│   ├── AppDatabase.kt     DAO + 数据库（version 3，含 1→2、2→3 迁移）
│   ├── BookImporter.kt    导入流程：复制 → 解析 → 分章写盘 → 入库；按"文件名+大小"去重
│   ├── BookStorage.kt     书籍文件布局 filesDir/books/{id}/
│   └── Settings.kt        ReaderSettings / TtsSettings（DataStore，JSON 序列化）+ SecureKeys（加密存 Key）
├── parser/
│   ├── TxtParser.kt       编码识别（UTF-8/GBK/GB18030/Big5）、章节标题正则、无标题时按 ~5000 字切分
│   └── EpubParser.kt      OPF/NCX/nav 解析，jsoup 把 HTML 转成纯文本，提取封面
├── reader/
│   ├── Paginator.kt       StaticLayout 整章排版，页 = 行区间；正文偏移 ↔ 排版偏移换算；听书高亮路径
│   ├── PageView.kt        页面绘制、页眉页脚、翻页手势和动画（slide/cover/none）
│   ├── ReaderViewModel.kt 分页缓存、进度、书签、跳章、听书跟随翻页
│   ├── ReaderScreen.kt    阅读页：菜单、目录、长按菜单（从这里听 / 书签 / 改说话人 / 重新合成）
│   └── ListenPanel.kt     听书面板：模式（单人/多角色）、引擎、音色、语速、定时
├── shelf/ShelfScreen.kt   书架
├── source/                书源搜书（见 §5 末尾）
│   ├── BookSource.kt      书源数据结构（字段名照搬阅读/Legado）、JSON 导入导出、内置示例（古腾堡）
│   ├── RuleEngine.kt      规则求值：CSS / Legado 默认语法 / JSONPath / XPath，|| && ## {{}}
│   ├── SourceClient.kt    请求（搜索地址模板、POST、GBK、请求头）+ 搜索 / 详情 / 目录 / 正文 / 文件下载
│   ├── SourceDownloads.kt 下载任务（app 作用域，离开页面不中断），完成后交给 BookImporter
│   ├── SourceRepo.kt      书源存储 filesDir/book_sources.json
│   ├── SearchScreen.kt    搜书页 + 下载进度
│   └── SourcesScreen.kt   书源列表、粘贴导入、编辑页（含测试）
├── transfer/
│   ├── TransferServer.kt  NanoHTTPD：GET / ；GET /api/books ；POST /api/upload ；DELETE /api/books/{id} ；GET /api/books/{id}/download
│   └── TransferScreen.kt  显示局域网地址和二维码
├── settings/SettingsScreen.kt  所有引擎的 Key、音色、上限，以及用量统计
├── tts/
│   ├── TtsEngine.kt       引擎接口、TtsException(skipEngine)、Http 客户端（读超时 90s）、计费字符规则、写 WAV
│   ├── Voices.kt          Engines 常量、各引擎的音色清单
│   ├── EdgeTts.kt         微软 Edge 大声朗读（非官方 WebSocket）
│   ├── CosyVoiceTts.kt    阿里 CosyVoice（DashScope 双工 WebSocket）
│   ├── QwenTts.kt         通义千问 TTS（HTTP，返回音频 URL）；也负责用"声音设计"做出来的音色合成
│   ├── GeminiTts.kt       Gemini TTS（Interactions API + speech_metadata）
│   ├── DoubaoTts.kt       豆包语音合成 2.0（HTTP 单向流式）——只用于多角色对白
│   ├── SystemTts.kt       系统 TTS，最后一道兜底
│   ├── Segmenter.kt       单人分句；多角色时把引号对白拆出来（splitDrama）
│   ├── TtsController.kt   听书流水线核心（见 §6）
│   └── TtsService.kt      前台服务、MediaSession、通知栏和线控
├── drama/                 多角色子系统（见 §7）
│   ├── Drama.kt           CastMember / Cast / RoleLine / ChapterRoles、QuoteFinder、VoicePools（千问预置音色池）
│   ├── DramaDirector.kt   角色分析（调大模型）、角色表、预分析、内容审核兜底、豆包选音色
│   ├── DoubaoVoices.kt    213 个豆包 2.0 音色目录（含实测表现力分数）
│   ├── VoiceDesigner.kt   千问"声音设计"：按文字描述生成音色，并做音高校验
│   └── CastScreen.kt      角色表页面
└── ui/Theme.kt            配色、阅读主题、字体
app/src/main/assets/
├── fonts/                 霞鹜文楷 GB（由 webfont 分片合并而成）、思源宋体
└── web/index.html         传书网页
```

---

## 5. 数据与存储

- **数据库**（Room，`AppDatabase`，version 3）
  - `books`：每本书一行，包含阅读进度（readChapter/readOffset/progress）、每本书固定的引擎 `ttsEngine`、多角色开关 `dramaMode`。
  - `chapters`：(bookId, idx) 主键，记录字数和累计起始字数（用于算全书进度）。
  - `bookmarks`：书签。
  - `tts_usage`：(month, engine) 主键，记录 chars / requests / audioTokens / billedChars。
    - engine 字段除了 TTS 引擎，还用 `director`（千问角色分析）、`director_grok`、`voicedesign`、`doubao` 记各自的用量。
    - 角色分析的行里，`chars` 存的是输入 token，`audioTokens` 存的是输出 token。
- **文件**（`filesDir/books/{id}/`）
  - `source.txt|epub`：原文件
  - `cover.jpg`：封面
  - `chapters/{idx}.txt`：每章正文。阅读和听书都只读这个文件。
  - `drama/ch_{idx}.json`：该章的角色分析结果（ChapterRoles，带 `version`）
  - `drama/cast.json`：角色表
  - `drama/voice_*.wav`：千问声音设计生成的免费试听片段
- **偏移约定**：书里所有位置都用"章节号 + 章内正文字符偏移"表示。分页、高亮、书签、听书进度、角色分析结果都基于这个约定，改动时务必保持一致。
- **设置**：`ReaderSettings` 和 `TtsSettings` 两个 data class，整体序列化成 JSON 存进 DataStore。新增字段只要给默认值就能兼容旧数据。需要一次性迁移旧值时，在 `App.kt` 里加一个 `xxxChecked` 标志，参照 `qwenVoiceChecked` 和 `directorChecked`。
- **API Key**：`SecureKeys` 用 EncryptedSharedPreferences 保存，字段有 cosy（阿里云，CosyVoice/千问/百炼共用）、gemini、volc（火山引擎）、grok。**Key 绝不写进代码或 APK**，每次出包前都要扫一遍（见 §10）。
- **书源**：存在 `filesDir/book_sources.json`（Legado 风格的 JSON 数组），不进数据库，所以加字段不需要 Room 迁移，只要给默认值。第一次打开时写入内置示例。
  - 逐章下载的书：章节直接按目录拆好交给 `BookImporter.importParsed`，不经过 `TxtParser` 的标题识别；同时把合成的 TXT 存成 `source.txt`，WiFi 传书页可以下载。
  - 文件下载的书：按 Content-Disposition、URL、Content-Type、zip 魔数判断 TXT / EPUB，再走普通的 `importFile`。
  - 下载在 App 作用域里跑，没有前台服务；逐章下载 4 并发、每章重试 3 次，失败的章节写"（本章下载失败）"。
  - 不支持 JS 规则（Legado 的 `<js>`、`@js:`），这类书源在列表里标红提示。
- **音频缓存**：`cacheDir/tts/`，文件名是"引擎 + 音色参数 + 文本"的 SHA-1。超过 400 MB 时按最近使用清理到 300 MB。重听不重复收费，用的就是这个缓存。

---

## 6. 听书流水线（TtsController）

```
start(book, ch, offset)
 └─ appendChapter(ch, offset) ──► 切成段（Seg）放进 queue
        单人：Segmenter.split（按引擎定长度上限：Gemini 260 / 千问 200 / Cosy 180 / 其他 150）
        多角色：先拿到本章 ChapterRoles（见 §7），再 Segmenter.splitDrama
 └─ launchPipeline()
        producer：在播放位置之前预先合成（单人按 settings.prefetch 段，多角色 8 段，3 个并发）；
                  快到队尾时自动 append 下一章
        player  ：依次等待每段的合成结果 → MediaPlayer 播放 → 更新 state（高亮、进度）
```

- **降级链**（`fallbackOf`）：
  - Gemini → 设置里指定的备用引擎（默认 CosyVoice）
  - CosyVoice → Edge
  - 千问 → Edge
  - Edge → 系统 TTS

  `TtsException(skipEngine=true)` 表示 Key 无效或额度用完，本次听书期间直接跳过该引擎。达到月用量上限也会触发降级。每个提示通过 `notifyOnce` 只弹一次。
- **跳转**：`jump(index)` 会取消生产者和播放器，从当前句重新切段。切换引擎、模式、音色或语速时都走这个函数，所以"随时切换"不会丢进度。
- **多角色合成**（`synthesizeDrama`）：
  - 对白：用该角色**已保存**在 `cast.json` 里的音色（豆包，或没有豆包 Key 时用千问）；没有千问音色的配角按性别用 Edge。
  - 旁白：Edge。
  - **不做跨引擎、跨音色的自动降级**（1.3.6 起，用户确认的规则）：合成失败先重试两次（Key 无效、额度用完不重试），仍失败就在这一句暂停并提示原因。用户点播放重试原音色，或去角色表换音色（换音色会 `jump` 重新合成，旧的预取结果作废）。
  - 新角色第一次开口前先 `ensureDoubaoVoices` 选好并保存音色，保存失败就暂停，不用临时音色。自动挑选过的音色不会再被自动替换。
  - `cast.json` 先写临时文件再改名替换；读不出来时另存为 `cast.json.broken` 并提示，不会悄悄覆盖。
  - 播放器打不开某段音频时（缓存损坏等），删掉重新合成一次；还不行就暂停，不跳过这一句。
  - 缓存键前缀：`db|`（豆包）、`dq|`（千问）、`de|`（Edge）
- **测试钩子**（`internal`）：`onPlay`（记录每段实际播放的音频）、`freeVoicesForTest`（对白也用免费的 Edge，测节奏时不花钱）。

---

## 7. 多角色子系统（drama/）

### 7.1 流程

1. **找引号**：`QuoteFinder.find` 找出 “” 「」 『』 "" 引号（不跨行）。`isEmbedded` 把句中嵌入的强调词、牌匾、俗语（如 所谓的“天才”）直接判给旁白，不送去分析。
2. **切段**：`DramaDirector.splitParts` 按段落把一章切成小段，每段最多 30 句对白（`PART_QUOTES`）、3000 字（`PART_CHARS`）。第二段起会带上前文约 400 字，标明"仅供理解，不用标注"。
3. **并行分析**：每段调一次大模型（`callLlm`，流式 SSE）。
   - 提示词要求返回 `characters`（标准名、别称、性别、年龄、嗓音描述）和 `lines`：`{"id","head","speaker","emotion","intensity"}`。1.3.11 之前用数组 `[编号, 前4字, 说话人, 语气, 强度]`，qwen3.8-max 偶尔整段漏掉说话人一栏（语气错位成说话人，整段变路人）；现在改成带字段名的对象，解析仍兼容数组。一段回复里超过三成的说话人缺失或对不上角色表时重问一次，第二次仍不行，缺的句子按"某某道"、冒号前的名字和对话轮流推断。
   - 规则写在提示词里：先看"某某道"这类提示语；没提示语时按对话双方轮流推断；动作描写里出现的人不一定是说话人；心里话算该角色的；无名人物填"路人"；非对白填"旁白"。
4. **对齐**：模型返回的编号不一定可靠，所以用"前 4 字"核对。处理了模型多抄到引号外的字、抄错一两个字的情况，见 `matches` / `loose`。没被覆盖到的引号一律判为"路人"（由旁白声音读）。
5. **合入角色表**：新角色按"标准名 + 别称"合并进 `cast.json`，同时统计每个角色的台词句数。
6. **配音色**：
   - 豆包模式（默认）：`ensureDoubaoVoices` 把同性别、表现力 ≥ 4 的音色目录和新角色的描述交给模型挑选，挑出的音色再做性别校验。挑不出时用规则兜底：同性别、同年龄段、用得最少、表现力最高。
   - 千问模式：台词 ≥ 3 句的角色调用 `VoiceDesigner` 生成定制音色。每个 0.2 元，每本书最多 30 个。
7. **保存**：每章结果存为 `drama/ch_N.json`，`version = ROLES_VERSION`（当前是 3）。版本落后的章节在播放时会重新分析，同时保留用户手动改过的说话人（`manual = true`）。

### 7.2 调度

- 前台优先：播放正在等的章节（`urgent`）最多 8 路并发（`fgSlots`）；后台预分析共用 2 路（`bgActive`）。
- 后台预分析：开始播一章时，同时预分析后面 3 章。角色表页可以手动"后面 20 章 / 剩余全部"，走的是 `preanalyze`。
- 同一章只会有一次分析在进行（`inflight`），重复请求会等待同一个结果。

### 7.3 模型与审核兜底

- **默认模型 `qwen3.8-max`**（阿里云百炼，与 CosyVoice 共用 Key；`qwen3-max` 已于 2026-10 前下线，1.3.6 起一次性迁移）。设置里还可以选 `qwen-flash`（省钱）和 Grok。
- **流式调用**：大章节一次性返回要 2 分钟以上，会撞上 OkHttp 90 秒的读超时，导致整章失败。这就是 1.3.3 之前"章节之间要等几分钟"的根源。改成 SSE 流式之后，只要还在持续收数据就不会超时。
- **阿里云内容审核误拦**：错误码 `data_inspection_failed`，提示 "Input data may contain inappropriate content"，程序里抛 `ContentBlocked`。普通网文也会触发。处理顺序（`analyseRobust`）：
  1. 如果填了 Grok Key 且开着"改用 Grok"开关，整段交给 Grok（`grok-4.20-0309-non-reasoning`，api.x.ai，需要代理）。
  2. Grok 不可用时，对半拆开重试（不带前文），一直拆到 ≤1 句或 <150 字。
  3. 仍被拦的句子用 `ruleBased` 规则判断：在引号前后 30 字内找已知角色名 + 说话动词。
- **千问本身出错时**（模型下线、额度用完、连续超时、返回内容坏掉，重试一次仍失败）：同样由 Grok 接手这一段（受同一个开关控制，需代理），并提示一次原因；Grok 也不行时这一章照旧退回旁白朗读。测试用 `SIM_QWEN_DOWN=1` 模拟。
- **Grok 当主模型时**：如果连不上（`GrokUnreachable`，多半是没开代理），10 分钟内改用千问 Max，并提示一次。
- **测试钩子**：`onAnalysed`、`onReply`（导出原始提示词和回复）、`simulateBlock`（模拟审核拦截）、`simulateGrokDown`。

### 7.4 用户可做的修正

- **角色表**（`CastScreen`）：改旁白音色；换角色音色（豆包列表按同性别、同年龄、表现力高的排在前面）；改嗓音描述并重新设计（千问模式）；"是同一人？合并…"；预分析后续章节。
- **阅读页长按**：改说话人（`overrideSpeaker`，标为 manual）；"这一句读得不对，重新合成"（删掉缓存后重合成）。

---

## 8. 外部服务一览

| 服务 | 用途 | 接口 / 认证 | 计费 | 坑与注意 |
|---|---|---|---|---|
| 微软 Edge 大声朗读 | 单人默认引擎、多角色旁白 | `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`，需要 `Sec-MS-GEC` 令牌（Windows 纪元时间按 5 分钟取整 + 固定 token 做 SHA-256） | 免费 | 非官方接口，随时可能失效；`CHROMIUM` 版本号必要时要跟着更新 |
| 阿里 CosyVoice | 单人 | DashScope 双工 WebSocket（run-task / continue-task / finish-task） | 1 元/万计费字符；**汉字算 2 个**，约 2 元/万字 | v3-plus 不支持系统音色（报错 418），所以固定用 `cosyvoice-v3-flash` |
| 通义千问 Qwen3-TTS | 单人；多角色备选 | `…/aigc/multimodal-generation/generation`；带指令时用 `qwen3-tts-instruct-flash`，不带用 `qwen3-tts-flash`，设计音色用 `qwen3-tts-vd-2026-01-26` | 0.8 元/万计费字符（约 1.5 元/万字） | 音色性别不能按名字猜：Elias"墨讲师"其实是女声。方言音色不支持指令 |
| 千问声音设计 | 多角色定制音色 | `…/audio/tts/customization`，model `qwen-voice-design` | 0.2 元/个 | 描述里写"少年音"的男声有些句子会听着像女声，所以男声固定"中低音"，并用试听片段测音高（男声 > 175 Hz 就重新设计）。设计出的音色**不接受**逐句指令 |
| Google Gemini TTS | 单人（精品书） | Interactions API（`/v1beta/interactions`），风格放在 `speech_metadata.style`；2.x/3.1 模型走 generateContent | 3.8 Flash TTS 每百万音频 token 9 美元（约 3.8 元/万字），2027 年起翻倍 | **把风格写进正文会被念出来**，必须走 speech_metadata。Tier 1 和 Tier 2 实际都限 100 次/天。国内需要代理 |
| 豆包语音合成 2.0 | 多角色对白（默认） | `https://openspeech.bytedance.com/api/v3/tts/unidirectional`，请求头 `X-Api-Key` + `X-Api-Resource-Id: seed-tts-2.0`；请求 `format: pcm`，返回逐行 JSON，里面是 base64 PCM，存成 WAV 前用 `Loudness.normalize` 拉到 -20 dBFS（同一句每次合成音量差 3～7 dB、音高差可达 30%，属服务端随机波动）。`context_texts` 里放别的角色台词会让声音向那句台词偏移，所以旁白不带上文、对白只带前面的旁白 | 资源包约 2.8 元/万字 | 音色 ID 规律：`zh_{male\|female}_{拼音}_uranus_bigtts` 和 `ICL_uranus_zh_{male\|female}_{拼音}_tob`。语气指令放在 `additions.context_texts[0]`（不计费），**效果有限**，情绪主要由台词本身决定。`audio_params.emotion` 实测无效。写进正文的 `[#指令]` 会被念出来 |
| 阿里百炼大模型 | 角色分析 | OpenAI 兼容 `/compatible-mode/v1/chat/completions`，`response_format=json_object`，`enable_thinking=false`，`stream=true` | Max 2.5/10、Plus 0.8/2、Flash 0.15/1.5（元/百万 token，输入/输出） | 平台级输入审核会误拦，见 §7.3 |
| xAI Grok | 角色分析（可选、兜底） | `https://api.x.ai/v1/chat/completions` | 1.25/2.5 美元/百万 token | 国内需要代理；连不上时快速失败并降级 |
| 系统 TTS | 最后兜底 | Android TextToSpeech | 免费 | 部分 ColorOS 机型没装中文引擎 |

---

## 9. 测试

| 测试 | 类型 | 说明 |
|---|---|---|
| `ParserTest` | 离线 | TXT 编码识别、分章、标题，EPUB 解析 |
| `BookSourceTest` | 离线 | 书源规则语法、Legado JSON 导入、古腾堡页面结构；用本地 NanoHTTPD 假站点跑通 GBK 逐章下载和 EPUB 文件下载到入库 |
| `DramaSegmentTest` | 离线 | 多角色切段、嵌入引号判定、男声提示词、音高估计、豆包音色目录 |
| `ScreenshotTest` | 离线 | Roborazzi 截图，输出到 `app/build/shots/` |
| `EdgeLiveTest` | 在线 | Edge 合成，需要网络 |
| `CloudLiveTest` | 在线 | CosyVoice / Gemini / 千问，需要 `COSY_KEY` / `GEMINI_KEY` |
| `DramaLiveTest` | 在线 | 端到端多角色：导入测试书 → 播完全书 → 打印角色表和每句的说话人 |
| `DramaSwitchTest` | 在线 | 单人 ↔ 多角色来回切换，检查不漏读、不重复 |
| `DramaPaceTest` | 在线 | 长篇书连续播放，统计章节之间的停顿 |

在线测试用到的环境变量（缺 Key 时 `assumeTrue` 自动跳过）：

```
COSY_KEY  GEMINI_KEY  VOLC_KEY  GROK_KEY     各家 API Key（只放环境变量，不进仓库）
DRAMA_RES=drama_trap.txt                     DramaLiveTest 用哪本测试书（默认 drama_book.txt）
TTS_OUT=/path                                把实际播放的每段音频和 played.tsv 拷出来
FREE_VOICES=1                                对白也用 Edge（不花豆包和千问的钱）
DIRECTOR_MODEL=qwen3.8-max|qwen-plus|qwen-flash|grok-…   临时指定分析模型
SIMULATE_BLOCK_B64=<base64(触发文本)>         模拟阿里云审核拦截（用 base64 是因为环境变量里的中文会被弄乱）
SIM_GROK_DOWN=1                              模拟 Grok 连不上
SIM_QWEN_DOWN=1                              模拟千问分析全部失败（检验 Grok 接手）
DUMP_REPLY=/path                             导出每段的提示词和模型回复
```

测试用文本（`app/src/test/resources/`）：

- `drama_trap.txt`：原创陷阱章，41 句对白，标准答案在 `drama_trap_truth.tsv`。覆盖强调词、牌匾、俗语、心里话、动作描写里的旁人、连续无提示对白、称呼反推等情况。
- `drama_book.txt`：两章，26 句对白，有别称、轮流对话。
- `drama_long.txt`：大模型生成的 4 章长篇，每章 5800–8400 字、170–300 句对白，用来测速度和停顿。
- 以上都是原创或生成的文本，可以放心放进仓库。

注意：Robolectric 下 MediaPlayer 会"瞬间播完"，所以 `DramaPaceTest` 测出的停顿是**上限**。真机上每章有十几分钟的收听时间，留给后台预分析的余量要大得多。

---

## 10. 发版流程

1. 改 `versionCode`、`versionName`，以及 `SettingsScreen.kt` 页脚的版本号。
2. 运行 `./gradlew testDebugUnitTest`。如有改动涉及多角色，再带 Key 跑 `DramaLiveTest`：用 trap 书跑 3 次，再跑一次 `drama_book.txt`，用 `played.tsv` 和标准答案核对说话人。
3. 运行 `./gradlew assembleRelease`。
4. **扫 Key**：把 APK 解压，再用每个 Key 的原文对解压目录和 `app/src` 跑 `grep -rF`，结果必须为空。
5. 在 `README.md` 里补一节更新说明。
6. 直接覆盖安装，数据保留（前提是用同一签名）。

---

## 11. 关键设计决策与实测结论

下面这些决定都有实测依据，改动前建议先复测。

- **多角色的组合**：旁白用免费的 Edge，对白用付费的豆包，这样成本可控：对白约占网文的 35–40%，百万字约 100 元对白费，加上约 10 元分析费。
- **对白引擎选豆包**：豆包和千问定制音色做盲听对比，用户明确选了豆包（"音色更丰富"）。Gemini 效果最好，但 Tier 1 和 Tier 2 实际都只有 100 次/天，一句一请求，每天只够听 2 章左右，所以没有采用。把多句合进一次请求再按静音切开，实测切不准，也放弃了。
- **影响"平不平"的头号因素是音色本身**：77 个音色读同样的愤怒、悲伤、惊喜台词，表现力从 1.8 分到 8.8 分不等。语气指令的提升有限（两两比较新旧版，新版只赢了一半左右）。所以给音色目录打了表现力分数，`MIN_EXPR = 4` 以下的音色不参与自动挑选。
- **分析模型选千问 Max**：在 30 句的一段上，Max 约 16 秒，Plus 约 40 秒；两者在测试集上都全对。Flash 约 10 秒，但在 trap 章的"你胡说！"这句上**每次都**认错（判成男主）。Grok 4.20 也全对、约 9 秒，但需要代理。
- **紧凑输出格式**：从"对象 + 判断依据字段"改成数组格式，输出 token 减半，准确率不变。
- **切段并行 + 流式**：197 句的一章，从"一次请求超时、重试后要等几分钟"缩短到 30 秒。章节切换实测无停顿。
- **男声定制音色**：描述里有"少年音"时，部分句子会听着像女声，所以统一加"男声、中低音"，并做音高校验。

---

## 12. 已知问题与待办

- **嵌套引号**：`QuoteFinder` 不跨行。对白跨段落、只在最后一段闭合引号的写法，前面几段会被当成旁白。
- **别称污染**：模型偶尔把别人对他的称呼（如"小丫头"）记成该角色的别称。因为模型通常直接返回标准名，目前影响很小。
- **审核兜底不精确**：没有 Grok 时，被拦的句子靠规则判断；找不到角色名的（例如只写"他冷笑道"）由旁白读。
- **豆包语气指令效果有限**：如果想要更强的表演，可以考虑接 MiniMax Speech-2.8（可在百炼开通，同一个阿里云 Key），这个方案还没有做过实测对比。
- **Edge 接口非官方**：失效时单人模式会降级到系统 TTS，多角色的旁白也会受影响。
- **书源**：内置的古腾堡规则是按它的页面结构写的，开发环境连不上 gutenberg.org，没有在线实测过；网站改版后要调规则。逐章下载没有前台服务，下载很长的书时退到后台可能被系统杀掉。
- **计划中的功能**：MOBI/AZW3（无 DRM）、离线语音模型、仿真翻页、上下滚动、笔记导出、阅读统计。
- **代码层面**：`TtsController` 和 `DramaDirector` 都已有 700 行左右。继续扩展前，建议把"多角色合成"和"分析调度/审核兜底"拆成独立类。

---

## 13. 排查速查

| 现象 | 先查 |
|---|---|
| 多角色提示"本章角色分析失败" | 设置页"本月用量"里有没有角色分析记录；阿里云 Key 和余额；用 `DUMP_REPLY` 导出原始回复看看 |
| 某几句总由旁白读 | 该章 `drama/ch_N.json` 里这些句的 speaker 是否为"路人"/"旁白"（可能是审核兜底或对齐失败）；也可以在阅读页长按改说话人 |
| 角色声音性别不对 | 角色表里的性别是否判错；豆包音色目录里的性别（有些 ID 名字里写 male，实际是女声，已经从目录里剔除） |
| 章节之间卡顿 | 后台预分析是否失败（看 `onAnalysed` 的耗时）；`fgSlots`、`bgActive` 的并发数 |
| Gemini 开头念出风格指令 | 模型是否走了 Interactions API（`usesInteractions`） |
| Gemini 报 429 | 每天 100 次的限额，等第二天，或者换 Lite 模型 |
| 后台听书被杀 | ColorOS：忽略电池优化 + 允许后台行为 + 在多任务界面锁定应用 |
