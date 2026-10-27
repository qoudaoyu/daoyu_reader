package com.daoyu.tingshu.drama

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoyu.tingshu.App
import com.daoyu.tingshu.data.Book
import com.daoyu.tingshu.data.Chapter
import com.daoyu.tingshu.tts.Engines
import com.daoyu.tingshu.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastScreen(bookId: Long, onBack: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cast by app.director.cast(bookId).collectAsState()
    val pre by app.director.preanalyzeState(bookId).collectAsState()
    val settings by app.settings.tts.collectAsState()
    val book by produceState<Book?>(null, bookId) { value = withContext(Dispatchers.IO) { app.db.books().get(bookId) } }
    val chapters by produceState<List<Chapter>>(emptyList(), bookId) { value = withContext(Dispatchers.IO) { app.db.chapters().list(bookId) } }
    var picking by remember { mutableStateOf<CastMember?>(null) }
    var merging by remember { mutableStateOf<CastMember?>(null) }
    var editing by remember { mutableStateOf<CastMember?>(null) }
    var pickingDb by remember { mutableStateOf<CastMember?>(null) }
    val doubao = settings.dramaVoiceEngine == "doubao" && app.keys.volcKey.isNotBlank()
    var designTick by remember { mutableStateOf(0) }   // recompose while a design runs
    fun redesign(m: CastMember, desc: String) {
        scope.launch {
            designTick++
            Toast.makeText(context, "正在为「${m.name}」设计音色…", Toast.LENGTH_SHORT).show()
            app.director.setVoiceDesc(bookId, m.name, desc)
            val ok = app.director.designFor(bookId, m.name)
            designTick++
            if (ok) {
                app.director.cast(bookId).value.members.firstOrNull { it.name == m.name }?.designedVoice?.takeIf { it.isNotEmpty() }?.let {
                    app.director.setVoice(bookId, m.name, it); app.tts.onSettingsChanged()
                }
                app.director.designedPreview(bookId, m.name)?.let { app.tts.previewFile(it) }
            } else Toast.makeText(context, "设计失败：${app.director.lastError ?: "网络错误"}", Toast.LENGTH_LONG).show()
        }
    }
    var analysed by remember { mutableStateOf(0) }
    LaunchedEffect(chapters, pre.done, pre.running) {
        analysed = withContext(Dispatchers.IO) { chapters.count { app.director.isCurrent(bookId, it.idx) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("角色表", fontSize = 18.sp); book?.let { Text(it.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) } } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        val members = cast.members.filter { it.mergedInto == null }.sortedByDescending { it.lines }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // narrator
            item {
                val doubao = app.director.narratesWithDoubao()
                Card(if (doubao) "旁白（豆包 2.0）" else "旁白（免费 · 微软 Edge）") {
                    val cur = if (doubao) cast.narratorDoubao ?: settings.dramaNarratorDoubao else cast.narratorVoice ?: settings.dramaNarratorVoice
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (if (doubao) DoubaoVoices.narrators else Engines.edgeVoices.take(6)).forEach { v ->
                            FilterChip(selected = v.id == cur, onClick = { scope.launch { if (doubao) app.director.setNarratorDoubao(bookId, v.id) else app.director.setNarrator(bookId, v.id); app.tts.onSettingsChanged() } },
                                label = { Text("${v.name} ${v.desc.substringBefore(' ')}") },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Gold.copy(alpha = 0.18f), selectedLabelColor = Palette.Gold))
                        }
                    }
                }
            }
            // pre-analysis
            item {
                Card("角色分析") {
                    val total = chapters.size
                    Text("已分析 $analysed / $total 章。听书时会自动分析当前章和后面两章；也可以提前分析，结果永久保存、不重复收费。",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                    if (pre.running) {
                        LinearProgressIndicator(progress = { if (pre.total == 0) 0f else pre.done / pre.total.toFloat() }, modifier = Modifier.fillMaxWidth(), color = Palette.Gold)
                        Text("正在分析 ${pre.done}/${pre.total} 章…（可离开本页）", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    } else {
                        pre.error?.let { Text("中途停止：$it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                        val from = book?.readChapter ?: 0
                        fun estimate(n: Int): String {
                            val chars = chapters.drop(from).take(n).sumOf { it.charCount.toLong() }
                            // measured (compact replies, 30-quote parts): ≈1.2 input + ≈0.7 output tokens per 汉字 for dialogue-heavy text
                            val yuan = chars * (1.2 * settings.directorInPrice + 0.7 * settings.directorOutPrice) / 1_000_000
                            return if (yuan < 0.1) "不到 1 角" else String.format("约 %.1f 元", yuan)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { app.director.preanalyze(bookId, from, 20, total) }) { Text("后面 20 章（${estimate(20)}）", fontSize = 13.sp) }
                            OutlinedButton(onClick = { app.director.preanalyze(bookId, from, total, total) }) { Text("剩余全部（${estimate(total)}）", fontSize = 13.sp) }
                        }
                    }
                }
            }
            item {
                Text("角色 ${members.size}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                if (members.isEmpty()) Text("还没有角色。开启多角色模式听书时会自动识别，或在上面提前分析。", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(4.dp))
            }
            items(members, key = { it.name }) { m ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            val aliases = m.aliases.take(4).joinToString("、")
                            Text("${m.gender} · ${m.age} · ${m.lines} 句" + if (aliases.isNotEmpty()) "　又称 $aliases" else "",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (m.voiceDesc.isNotEmpty()) "声音：${m.voiceDesc}" else "声音：未描述，点此填写",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp,
                                modifier = Modifier.clickable { editing = m }.padding(top = 2.dp))
                            Text("是同一人？合并…", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { merging = m }.padding(top = 4.dp))
                        }
                        val v = Engines.qwenVoices.firstOrNull { it.id == m.voice }
                        val busy = designTick >= 0 && app.director.isDesigning(bookId, m.name)
                        val label = when {
                            doubao -> DoubaoVoices.byId(m.doubaoVoice)?.name ?: "待分配"
                            busy -> "设计中…"
                            VoicePools.isDesigned(m.voice) -> "定制音色"
                            else -> v?.name ?: m.voice
                        }
                        Surface(shape = RoundedCornerShape(16.dp), color = Palette.Gold.copy(alpha = 0.12f), modifier = Modifier.clickable { if (doubao) pickingDb = m else picking = m }) {
                            Text(label, color = Palette.Gold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        }
    }

    picking?.let { m ->
        VoiceDialog(m, onPreview = { voice ->
            scope.launch {
                val saved = if (VoicePools.isDesigned(voice)) app.director.designedPreview(bookId, m.name) else null
                val err = if (saved != null) app.tts.previewFile(saved)
                    else app.tts.previewQwen(voice, if (VoicePools.isDesigned(voice)) "" else VoicePools.persona(m), "我是${m.name}。今天的事，咱们慢慢说。")
                if (err != null) Toast.makeText(context, "试听失败：$err", Toast.LENGTH_LONG).show()
            }
        }, onDesign = { picking = null; editing = m }, onPick = { voice ->
            scope.launch { app.director.setVoice(bookId, m.name, voice); app.tts.onSettingsChanged() }
            picking = null
        }, onDismiss = { picking = null })
    }
    pickingDb?.let { m ->
        DoubaoVoiceDialog(m, onPreview = { voice ->
            scope.launch {
                val err = app.tts.previewDoubao(voice, "我是${m.name}。今天的事，咱们慢慢说。")
                if (err != null) Toast.makeText(context, "试听失败：$err", Toast.LENGTH_LONG).show()
            }
        }, onPick = { voice ->
            scope.launch { app.director.setDoubaoVoice(bookId, m.name, voice); app.tts.onSettingsChanged() }
            pickingDb = null
        }, onDismiss = { pickingDb = null })
    }
    editing?.let { m ->
        var desc by remember(m.name) { mutableStateOf(m.voiceDesc) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("「${m.name}」的声音") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("描述这个角色听起来应该是什么样：年龄、嗓音、性格气质。定制音色会按这段描述生成。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                    OutlinedTextField(desc, { desc = it.take(80) }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                        placeholder = { Text("如：四十多岁，嗓音低沉浑厚，说话沉稳威严") })
                }
            },
            confirmButton = {
                if (!doubao) TextButton(enabled = desc.isNotBlank(), onClick = { editing = null; redesign(m, desc) }) { Text("按描述设计音色（约 0.2 元）") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null; scope.launch { app.director.setVoiceDesc(bookId, m.name, desc); app.tts.onSettingsChanged() } }) { Text("仅保存") }
            }
        )
    }
    merging?.let { m ->
        val others = cast.members.filter { it.mergedInto == null && it.name != m.name }.sortedByDescending { it.lines }
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("「${m.name}」其实是谁？") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(others, key = { it.name }) { o ->
                        Text(o.name + if (o.aliases.isNotEmpty()) "（${o.aliases.take(2).joinToString("、")}）" else "", fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch { app.director.merge(bookId, m.name, o.name); app.tts.onSettingsChanged() }
                                merging = null
                            }.padding(vertical = 12.dp))
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { merging = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun VoiceDialog(m: CastMember, onPreview: (String) -> Unit, onDesign: () -> Unit, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val pool = VoicePools.poolFor(m.gender, m.age).toSet()
    // suitable voices first, dialect voices last
    val voices = Engines.qwenVoices.sortedBy { if (it.id in pool) 0 else if (it.instruct) 1 else 2 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给「${m.name}」选声音") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                item {
                    if (m.designedVoice.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().clickable { onPick(m.designedVoice) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("定制音色", fontSize = 15.sp)
                                    Text("  按角色描述生成", fontSize = 11.sp, color = Palette.Gold)
                                }
                                Text(m.voiceDesc.ifEmpty { "${m.gender} · ${m.age}" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 2)
                            }
                            if (m.voice == m.designedVoice) Icon(Icons.Default.Check, null, tint = Palette.Gold, modifier = Modifier.size(18.dp))
                            IconButton(onClick = { onPreview(m.designedVoice) }) { Icon(Icons.Default.PlayCircleOutline, "试听") }
                        }
                    }
                    Text(if (m.designedVoice.isEmpty()) "＋ 按角色描述设计专属音色（约 0.2 元）" else "重新设计…", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable { onDesign() }.padding(vertical = 10.dp))
                    HorizontalDivider()
                }
                items(voices, key = { it.id }) { v ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(v.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(v.name, fontSize = 15.sp)
                                if (v.id in pool) Text("  推荐", fontSize = 11.sp, color = Palette.Gold)
                            }
                            Text(v.desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        if (v.id == m.voice) Icon(Icons.Default.Check, null, tint = Palette.Gold, modifier = Modifier.size(18.dp))
                        IconButton(onClick = { onPreview(v.id) }) { Icon(Icons.Default.PlayCircleOutline, "试听") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun DoubaoVoiceDialog(m: CastMember, onPreview: (String) -> Unit, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val female = m.gender.startsWith("女")
    val age = VoicePools.ageKey(m.age)
    // same gender first, same age band on top
    val voices = DoubaoVoices.all.sortedWith(compareBy<DbVoice> { v -> (if ((v.gender == "女") == female) 0 else 2) + (if (v.age == age) 0 else 1) }.thenByDescending { it.expr })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给「${m.name}」选豆包音色") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(voices, key = { it.id }) { v ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(v.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(v.name, fontSize = 15.sp)
                                Text("  ${v.gender} · ${v.age}", fontSize = 11.sp, color = if ((v.gender == "女") == female && v.age == age) Palette.Gold else MaterialTheme.colorScheme.outline)
                            }
                            Text("${v.desc} · 表现力 ${v.expr}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        if (v.id == m.doubaoVoice) Icon(Icons.Default.Check, null, tint = Palette.Gold, modifier = Modifier.size(18.dp))
                        IconButton(onClick = { onPreview(v.id) }) { Icon(Icons.Default.PlayCircleOutline, "试听") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
