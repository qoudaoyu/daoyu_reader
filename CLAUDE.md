# CLAUDE.md

刀鱼听书：安卓电子书阅读和听书 App，使用 Kotlin 与 Jetpack Compose。只供项目所有者自用。项目所有者用中文交流，回复请用中文。

## 先读这些
- `docs/DEVELOPER.md`：完整的开发者说明，包括架构、多角色流水线、各云服务的坑、实测结论和发版流程。**改动多角色、TTS 或分析相关代码之前必须先读。**
- `README.md`：面向用户的功能说明，以及每个版本的更新记录。

## 硬性规则
- **API Key 绝不写进代码、测试资源或 APK。** Key 只存在手机的 `SecureKeys`（加密存储）里。在线测试通过环境变量 `COSY_KEY` / `GEMINI_KEY` / `VOLC_KEY` / `GROK_KEY` 拿 Key。每次出包前，用每个 Key 的原文对解压后的 APK 和 `app/src` 运行 `grep -rF`，结果必须为空。
- **签名**：release 签名从根目录的 `keystore.properties` 读取，它指向 `keystore/daoyu.jks`。这两个文件都不进版本库（已写在 `.gitignore` 里）。用户手机上装的是用这个原签名打的包，**换签名就无法覆盖安装**。没有签名文件时，向项目所有者索取，不要自己新建一个。
- **用户数据兼容**：
  - Room 数据库升级必须写 Migration，不能用 destructive 迁移。
  - `TtsSettings` / `ReaderSettings` 新增字段必须带默认值。
  - 角色分析结果的格式变了，就把 `drama/Drama.kt` 里的 `ROLES_VERSION` 加一。旧结果会在播放时重新分析，用户手动改过的说话人会被保留。
- **位置约定**：书里的位置一律用"章节号 + 章内正文字符偏移"表示，分页、高亮、书签、听书进度、角色分析都基于它。
- **设置**：引擎和模型的默认值改了，需要在 `App.kt` 里写一次性迁移，参照 `directorChecked` 的写法。

## 常用命令
```bash
./gradlew testDebugUnitTest                 # 离线测试；在线测试缺 Key 会自动跳过
./gradlew assembleRelease                   # 签名的 release 包（需要 keystore.properties）
# 多角色准确率回归测试（需要 Key）：用 trap 书跑 3 次，再用 drama_book.txt 跑 1 次，按 drama_trap_truth.tsv 核对说话人
COSY_KEY=… VOLC_KEY=… FREE_VOICES=1 DRAMA_RES=drama_trap.txt TTS_OUT=/tmp/out \
  ./gradlew testDebugUnitTest --tests 'com.daoyu.tingshu.DramaLiveTest' --rerun -i
```
注意：Gradle 不会因为环境变量变了就重新跑测试，要加 `--rerun`。环境变量里的中文会被弄乱，所以 `SIMULATE_BLOCK` 要用 base64 形式（`SIMULATE_BLOCK_B64`）。

## 发版
改 `app/build.gradle.kts` 的 `versionCode` 和 `versionName`，同时改 `SettingsScreen.kt` 页脚的版本号；在 `README.md` 里补一节更新说明；跑测试；出包；扫 Key。

## 工作方式
- 涉及音质、准确率、速度的改动要拿数据说话，在测试书上实测，或者让 Gemini 转写或盲评。之前的结论都记在 `docs/DEVELOPER.md` 第 11 节。
- 花钱的在线测试（豆包、千问 TTS、Gemini 每天只有 100 次）要节制。只测节奏时，用 `FREE_VOICES=1` 让对白也走免费的 Edge。
- `TtsController.kt` 和 `DramaDirector.kt` 都已经很大了，大改之前优先考虑拆分。
