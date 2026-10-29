package com.daoyu.tingshu.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoyu.tingshu.App
import com.daoyu.tingshu.data.TtsSettings
import com.daoyu.tingshu.shelf.formatSize
import com.daoyu.tingshu.tts.Engines
import com.daoyu.tingshu.tts.Voice
import com.daoyu.tingshu.ui.Palette
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val USD_CNY = 7.1f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tts by app.settings.tts.collectAsState()
    val reader by app.settings.reader.collectAsState()
    val usage by app.db.usage().observeAll().collectAsState(initial = emptyList())
    var previewing by remember { mutableStateOf<String?>(null) }
    var cacheSize by remember { mutableLongStateOf(app.tts.cacheSize()) }

    fun upd(f: (TtsSettings) -> TtsSettings) = scope.launch { app.settings.updateTts(f); app.tts.onSettingsChanged() }
    fun preview(engine: String) {
        previewing = engine
        scope.launch {
            val err = app.tts.preview(engine)
            previewing = null
            Toast.makeText(context, err?.let { "试听失败：$it" } ?: "${Engines.label(engine)} 正常", Toast.LENGTH_LONG).show()
        }
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val dir = File(context.filesDir, "fonts").apply { mkdirs() }
            val f = File(dir, "custom_${System.currentTimeMillis()}.ttf")
            try {
                context.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                android.graphics.Typeface.createFromFile(f) // validate
                dir.listFiles()?.filter { it != f }?.forEach { it.delete() }
                app.settings.updateReader { it.copy(font = "custom", customFontPath = f.absolutePath) }
                Toast.makeText(context, "字体已导入", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                f.delete()
                Toast.makeText(context, "不是有效的字体文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // ------------------------------------------------ default engine
            Section("默认听书引擎") {
                Engines.all.forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable { upd { it.copy(engine = e) } }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = tts.engine == e, onClick = { upd { it.copy(engine = e) } }, colors = RadioButtonDefaults.colors(selectedColor = Palette.Gold))
                        Column {
                            Text(Engines.label(e), fontSize = 15.sp)
                            Text(Engines.hint(e), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                Hint("听书时也可以在播放面板里随时切换；每本书可以单独固定引擎。")
            }

            // ------------------------------------------------ Edge
            Section("微软 Edge 语音", action = { PreviewButton(previewing == Engines.EDGE) { preview(Engines.EDGE) } }) {
                VoicePicker(Engines.edgeVoices, tts.edgeVoice) { v -> upd { it.copy(edgeVoice = v) } }
                LabeledSlider("语速", tts.edgeRate / 100f, -0.5f..1f, { "${if (it >= 0) "+" else ""}${(it * 100).roundToInt()}%" }) { v -> upd { it.copy(edgeRate = (v * 100).roundToInt()) } }
                Hint("免费，无需 Key。借用 Edge 浏览器「大声朗读」接口，非官方开放，若失效会自动改用系统朗读。语速设置同时作用于系统朗读。")
            }

            // ------------------------------------------------ CosyVoice
            Section("阿里云 CosyVoice", action = { PreviewButton(previewing == Engines.COSY) { preview(Engines.COSY) } }) {
                SecretField("阿里云 API Key（百炼控制台获取，CosyVoice 与通义千问共用）", app.keys.cosyKey) { app.keys.cosyKey = it }
                Text("模型：cosyvoice-v3-flash · 1 元/万计费字符（汉字算 2 个，约合 2 元/万字）", fontSize = 14.sp)
                VoicePicker(Engines.cosyVoices, tts.cosyVoice) { v -> upd { it.copy(cosyVoice = v) } }
                TextSetting("自定义音色 ID（可选，填写后覆盖上面的选择）", if (Engines.cosyVoices.any { it.id == tts.cosyVoice }) "" else tts.cosyVoice) { v ->
                    if (v.isNotBlank()) upd { it.copy(cosyVoice = v.trim()) }
                }
                LabeledSlider("语速", tts.cosyRate, 0.5f..2f, { String.format("%.1fx", it) }) { v -> upd { it.copy(cosyRate = (v * 10).roundToInt() / 10f) } }
                NumberSetting("每月上限（万字，0 为不限）", tts.cosyMonthlyLimit) { v -> upd { it.copy(cosyMonthlyLimit = v) } }
                TextSetting("接口地址（一般不用改）", tts.cosyEndpoint) { v -> if (v.startsWith("wss://")) upd { it.copy(cosyEndpoint = v.trim()) } }
                Hint("v3.5 系列不支持系统音色，v3 Plus 只有两个非说书音色，所以固定用 v3 Flash。实测 100 字约合成 20 秒语音。达到月上限后自动改用 Edge 免费语音。")
            }

            // ------------------------------------------------ Gemini
            Section("通义千问 Qwen3-TTS", action = { PreviewButton(previewing == Engines.QWEN) { preview(Engines.QWEN) } }) {
                Text("使用上面 CosyVoice 的同一个阿里云 API Key，无需另填。约 0.8 元/万计费字符（汉字算 2 个，约合 1.5 元/万字）。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                VoicePicker(Engines.qwenVoices, tts.qwenVoice) { v -> upd { it.copy(qwenVoice = v) } }
                TextSetting("语气指令（用中文描述，例如：说书人的口吻，从容不迫）", tts.qwenStyle, singleLine = false) { v -> upd { it.copy(qwenStyle = v) } }
                NumberSetting("每月上限（万字，0 为不限）", tts.qwenMonthlyLimit) { v -> upd { it.copy(qwenMonthlyLimit = v) } }
                Hint("方言音色（北京话、四川话、粤语等）不支持语气指令，选用时会自动忽略指令。达到月上限或出错时自动改用 Edge 免费语音。")
            }

            Section("豆包语音（火山引擎）") {
                SecretField("API Key（火山引擎「豆包语音」控制台 → API Key 管理）", app.keys.volcKey) { app.keys.volcKey = it }
                Text("模型：豆包语音合成 2.0 · 资源包约 2.8 元/万字（28 元/10 万字）", fontSize = 14.sp)
                NumberSetting("每月上限（万字，0 为不限）", tts.doubaoMonthlyLimit) { v -> upd { it.copy(doubaoMonthlyLimit = v) } }
                Hint("用于多角色模式的人物对白，中文盲听评测第一。Key 无效或额度用完时，对白自动改用通义千问并提示你。")
            }

            Section("Grok（xAI，角色分析备用）") {
                SecretField("API Key（console.x.ai 获取）", app.keys.grokKey) { app.keys.grokKey = it }
                SwitchRow("千问分析失败或被审核拦下时改用 Grok", tts.grokRescue) { v -> upd { it.copy(grokRescue = v) } }
                Hint("阿里云会对送去分析的文字做内容审核，普通网文偶尔被误拦。Grok 不受这个审核，实测说话人判断同样全部正确、速度更快，但需要开代理，约 24 元 / 百万字（只用于被拦段落时几乎不花钱）。没开代理时自动拆小段重试，最后按\"某某道\"规则判断。")
            }

            Section("多角色说书") {
                Text("旁白用免费的微软 Edge 语音，对白由通义千问按角色配音；谁在说话由通义千问大模型分析（与上面同一个阿里云 Key）。在听书面板里可随时切换单人 / 多角色。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                ChoiceRow("旁白配音", listOf("doubao" to "豆包 2.0", "edge" to "微软 Edge（免费）"), tts.dramaNarratorEngine) { v -> upd { it.copy(dramaNarratorEngine = v) } }
                Hint("微软 Edge 目前拒绝本 App 访问（403），旁白默认改用豆包，按全文字数计费，约 2.8 元/万字。没填火山引擎 Key 时仍用 Edge。")
                Text("新书默认旁白", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tts.dramaNarratorEngine == "doubao") VoicePicker(com.daoyu.tingshu.drama.DoubaoVoices.narrators, tts.dramaNarratorDoubao) { v -> upd { it.copy(dramaNarratorDoubao = v) } }
                else VoicePicker(Engines.edgeVoices.take(6), tts.dramaNarratorVoice) { v -> upd { it.copy(dramaNarratorVoice = v) } }
                ChoiceRow("角色分析模型", listOf("qwen3.8-max" to "千问 3.8 Max（推荐）", "qwen-flash" to "千问 Flash（省钱）", com.daoyu.tingshu.drama.DramaDirector.GROK_MODEL to "Grok（需代理）"), tts.directorModel) { m ->
                    upd { it.copy(directorModel = m, directorInPrice = when (m) { "qwen3.8-max" -> 12f; "qwen-plus" -> 0.8f; "qwen-flash" -> 0.15f; else -> 9f }, directorOutPrice = when (m) { "qwen3.8-max" -> 36f; "qwen-plus" -> 2f; "qwen-flash" -> 1.5f; else -> 18f }) }
                }
                Hint("每章切成小段并行分析，听书时提前分析后面三章；结果按章保存，只收一次费。实测 3.8 Max 在测试书上 41 句只错 1 句，约 30–40 元 / 百万字（价格待在控制台核对）；Flash 最快、约 1–2 元 / 百万字，但偶尔会认错说话人。")
                ChoiceRow("对白配音", listOf("doubao" to "豆包 2.0（推荐）", "qwen" to "通义千问"), tts.dramaVoiceEngine) { v -> upd { it.copy(dramaVoiceEngine = v) } }
                if (tts.dramaVoiceEngine == "doubao" && app.keys.volcKey.isBlank()) Hint("还没填写火山引擎 API Key，暂时用通义千问配对白。")
                SwitchRow("主要角色定制音色（通义千问）", tts.dramaDesignVoices) { v -> upd { it.copy(dramaDesignVoices = v) } }
                Hint("说话 3 句以上的角色，按分析出的年龄、嗓音、性格自动设计一个专属音色（约 0.2 元/个，每本书最多 30 个），比预置音色更贴合人物。关闭后只用预置音色。")
            }

            Section("Google Gemini", action = { PreviewButton(previewing == Engines.GEMINI) { preview(Engines.GEMINI) } }) {
                SecretField("API Key（Google AI Studio 获取）", app.keys.geminiKey) { app.keys.geminiKey = it }
                ChoiceRow("模型", listOf("gemini-3.8-flash-tts" to "3.8 Flash TTS", "gemini-3.8-flash-lite-tts" to "3.8 Flash-Lite TTS"), tts.geminiModel) { m ->
                    upd { it.copy(geminiModel = m, geminiRateUsdPerM = if (m.contains("lite")) 6f else 9f) }
                }
                TextSetting("自定义模型名（可选）", if (tts.geminiModel.startsWith("gemini-3.8-flash")) "" else tts.geminiModel) { v ->
                    if (v.isNotBlank()) upd { it.copy(geminiModel = v.trim()) }
                }
                VoicePicker(Engines.geminiVoices, tts.geminiVoice) { v -> upd { it.copy(geminiVoice = v) } }
                TextSetting("说书风格指令", tts.geminiStyle, singleLine = false) { v -> upd { it.copy(geminiStyle = v) } }
                ChoiceRow("连不上时改用", listOf(Engines.QWEN to "通义千问", Engines.COSY to "CosyVoice", Engines.EDGE to "微软 Edge"), tts.geminiFallback) { v -> upd { it.copy(geminiFallback = v) } }
                NumberSetting("每月上限（万字，0 为不限）", tts.geminiMonthlyLimit) { v -> upd { it.copy(geminiMonthlyLimit = v) } }
                Hint("Gemini 在国内需开启代理。连不上、额度用完或 Key 无效时，会自动改用上面选择的引擎并提示你。按音频时长计费。实测 100 字约 24 秒语音，标准价约 4 元/万字（Lite 约 2.7 元），2027 年起官方翻倍；实际花费见下方用量。")
            }

            // ------------------------------------------------ usage
            Section("本月用量与费用估算") {
                val m = remember { SimpleDateFormat("yyyy-MM", Locale.US).format(Date()) }
                val cosy = usage.firstOrNull { it.month == m && it.engine == Engines.COSY }
                val gem = usage.firstOrNull { it.month == m && it.engine == Engines.GEMINI }
                val qwen = usage.firstOrNull { it.month == m && it.engine == Engines.QWEN }
                UsageRow("CosyVoice", cosy?.chars ?: 0, "约 ¥" + String.format("%.2f", (cosy?.billedChars ?: 0) / 10000f * tts.cosyPricePer10k))
                UsageRow("通义千问", qwen?.chars ?: 0, "约 ¥" + String.format("%.2f", (qwen?.billedChars ?: 0) / 10000f * tts.qwenPricePer10k))
                val dir = usage.firstOrNull { it.month == m && it.engine == com.daoyu.tingshu.drama.DramaDirector.ENGINE }
                if (dir != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("角色分析", fontSize = 15.sp, modifier = Modifier.width(96.dp))
                    Text("${dir.requests} 次", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("约 ¥" + String.format("%.2f", dir.chars / 1e6f * tts.directorInPrice + dir.audioTokens / 1e6f * tts.directorOutPrice), fontSize = 14.sp, color = Palette.Gold)
                }
                val db = usage.firstOrNull { it.month == m && it.engine == Engines.DOUBAO }
                if (db != null) UsageRow("豆包语音", db.chars, "约 ¥" + String.format("%.2f", db.billedChars / 10000f * tts.doubaoPricePer10k))
                val gk = usage.firstOrNull { it.month == m && it.engine == com.daoyu.tingshu.drama.DramaDirector.GROK_ENGINE }
                if (gk != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Grok 分析", fontSize = 15.sp, modifier = Modifier.width(96.dp))
                    Text("${gk.requests} 次", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("约 ¥" + String.format("%.2f", gk.chars / 1e6f * com.daoyu.tingshu.drama.DramaDirector.GROK_IN_PRICE + gk.audioTokens / 1e6f * com.daoyu.tingshu.drama.DramaDirector.GROK_OUT_PRICE), fontSize = 14.sp, color = Palette.Gold)
                }
                val vd = usage.firstOrNull { it.month == m && it.engine == com.daoyu.tingshu.drama.VoiceDesigner.ENGINE }
                if (vd != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("定制音色", fontSize = 15.sp, modifier = Modifier.width(96.dp))
                    Text("${vd.requests} 个", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("约 ¥" + String.format("%.2f", vd.requests * com.daoyu.tingshu.drama.VoiceDesigner.PRICE), fontSize = 14.sp, color = Palette.Gold)
                }
                UsageRow("Gemini", gem?.chars ?: 0, "约 ¥" + String.format("%.2f", (gem?.audioTokens ?: 0) / 1_000_000f * tts.geminiRateUsdPerM * USD_CNY))
                Hint("阿里云按服务端返回的计费字符统计（汉字算 2 个）；听过的内容有缓存，重听不计费。Gemini 按返回的音频 token 估算（不含免费额度抵扣）。")
                val history = usage.filter { it.month != m }.groupBy { it.month }.entries.take(6)
                if (history.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    history.forEach { (month, list) ->
                        Text("$month　" + list.joinToString("　") { "${Engines.label(it.engine)} ${formatChars(it.chars)}" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ------------------------------------------------ reading
            Section("阅读") {
                SwitchRow("屏幕常亮", reader.keepScreenOn) { v -> scope.launch { app.settings.updateReader { it.copy(keepScreenOn = v) } } }
                SwitchRow("显示页眉页脚（章节名、时间、电量、进度）", reader.showInfoBar) { v -> scope.launch { app.settings.updateReader { it.copy(showInfoBar = v) } } }
                SwitchRow("音量键翻页", reader.volumeKeyTurn) { v -> scope.launch { app.settings.updateReader { it.copy(volumeKeyTurn = v) } } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("导入字体", fontSize = 15.sp)
                        Text(if (reader.customFontPath != null) "已导入自定义字体" else "支持 TTF / OTF 文件", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    OutlinedButton(onClick = { fontPicker.launch(arrayOf("*/*")) }) { Text("选择文件") }
                }
            }

            // ------------------------------------------------ background (OPPO / ColorOS)
            Section("后台听书保护") {
                val pm = context.getSystemService(PowerManager::class.java)
                val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("忽略电池优化", fontSize = 15.sp)
                        Text(if (ignoring) "已允许" else "未允许：锁屏久了可能被系统停止", fontSize = 12.sp, color = if (ignoring) Palette.Gold else MaterialTheme.colorScheme.error)
                    }
                    if (!ignoring) OutlinedButton(onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }) { Text("去允许") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("应用耗电管理", fontSize = 15.sp)
                        Text("OPPO / ColorOS：应用信息 → 耗电管理 → 打开「允许后台行为」，并在多任务界面锁定刀鱼听书", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }) { Text("打开") }
                }
            }

            // ------------------------------------------------ cache
            Section("缓存") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("语音缓存 ${formatSize(cacheSize)}", fontSize = 15.sp)
                        Text("超过 400MB 会自动清理最早的部分", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    TextButton(onClick = { app.tts.clearCache(); cacheSize = app.tts.cacheSize() }) { Text("清除") }
                }
            }

            Text("刀鱼听书 1.4.2", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp))
        }
    }
}

private fun formatChars(n: Long) = if (n >= 10000) String.format("%.1f 万字", n / 10000.0) else "$n 字"

@Composable
private fun Section(title: String, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun PreviewButton(busy: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = !busy) {
        Icon(Icons.Default.PlayCircleOutline, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (busy) "合成中…" else "试听")
    }
}

@Composable
private fun Hint(t: String) = Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, lineHeight = 17.sp)

@Composable
private fun VoicePicker(voices: List<Voice>, selected: String, onPick: (String) -> Unit) {
    Column {
        voices.forEach { v ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(v.id) }.padding(vertical = 7.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(v.name, fontSize = 15.sp, modifier = Modifier.width(96.dp))
                Text(v.desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (v.id == selected) Icon(Icons.Default.Check, null, tint = Palette.Gold, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Column {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (k, n) ->
                FilterChip(selected = selected == k, onClick = { onPick(k) }, label = { Text(n) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Gold.copy(alpha = 0.18f), selectedLabelColor = Palette.Gold))
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String, onDone: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.width(48.dp))
        Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onDone(v) }, valueRange = range, modifier = Modifier.weight(1f))
        Text(fmt(v), fontSize = 13.sp, modifier = Modifier.width(56.dp).padding(start = 8.dp))
    }
}

@Composable
private fun SecretField(label: String, initial: String, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onSave(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "显示") } },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun TextSetting(label: String, initial: String, singleLine: Boolean = true, onSave: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    var dirty by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = text, onValueChange = { text = it; dirty = true }, label = { Text(label, fontSize = 12.sp) },
            singleLine = singleLine, minLines = if (singleLine) 1 else 3, modifier = Modifier.fillMaxWidth()
        )
        if (dirty) TextButton(onClick = { onSave(text); dirty = false }, modifier = Modifier.align(Alignment.End)) { Text("保存") }
    }
}

@Composable
private fun NumberSetting(label: String, value: Int, onSave: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = text,
            onValueChange = { t -> text = t.filter { it.isDigit() }.take(5); text.toIntOrNull()?.let(onSave) ?: if (text.isEmpty()) onSave(0) else Unit },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(96.dp)
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Gold))
    }
}

@Composable
private fun UsageRow(name: String, chars: Long, cost: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 15.sp, modifier = Modifier.width(96.dp))
        Text(formatChars(chars), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(cost, fontSize = 14.sp, color = Palette.Gold)
    }
}
