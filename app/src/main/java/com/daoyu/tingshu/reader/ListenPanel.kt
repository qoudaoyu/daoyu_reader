package com.daoyu.tingshu.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoyu.tingshu.App
import com.daoyu.tingshu.data.Book
import com.daoyu.tingshu.tts.Engines
import com.daoyu.tingshu.ui.Palette
import com.daoyu.tingshu.ui.ReaderTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ListenPanel(book: Book?, theme: ReaderTheme, onStart: () -> Unit, onOpenSettings: () -> Unit, onRefresh: () -> Unit, onOpenCast: () -> Unit = {}) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val tts by app.tts.state.collectAsState()
    val s by app.settings.tts.collectAsState()
    val thisBook = book != null && tts.active && tts.bookId == book.id
    var pin by remember(book?.ttsEngine) { mutableStateOf(book?.ttsEngine != null) }
    val selected = book?.ttsEngine ?: s.engine
    val drama = book?.dramaMode == true

    fun chooseEngine(e: String) = scope.launch {
        if (book != null && pin) app.db.books().setTtsEngine(book.id, e) else {
            app.settings.updateTts { it.copy(engine = e) }
            if (book?.ttsEngine != null) app.db.books().setTtsEngine(book.id, null)
        }
        onRefresh()
        app.tts.switchEngine(e)
    }

    Surface(color = theme.bg, shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("听书", color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                val who = if (drama) "多角色" else Engines.label(tts.engine)
                val status = when {
                    !thisBook -> "未开始"
                    tts.status.isNotEmpty() -> tts.status
                    tts.loading -> "正在合成 · $who"
                    tts.playing -> "正在朗读 · $who" + (tts.speaker?.let { "（$it）" } ?: "")
                    else -> "已暂停 · $who"
                }
                Text(status, color = theme.secondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (tts.sleepLabel.isNotEmpty() && thisBook) Text(tts.sleepLabel, color = Palette.Gold, fontSize = 12.sp)
            }

            // mode: single narrator / multiple character voices — switchable while listening
            if (book != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Label("模式", theme)
                Chip("单人说书", !drama, theme) { if (drama) { app.tts.setDramaMode(book.id, false); onRefresh() } }
                Spacer(Modifier.width(10.dp))
                Chip("多角色", drama, theme) {
                    if (!drama) {
                        if (app.keys.cosyKey.isBlank()) android.widget.Toast.makeText(app, "多角色需要先在设置里填写阿里云 API Key", android.widget.Toast.LENGTH_LONG).show()
                        else { app.tts.setDramaMode(book.id, true); onRefresh() }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (drama) Text("角色表 ›", color = Palette.Gold, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onOpenCast).padding(4.dp))
            }

            if (drama) {
                Text("旁白用免费的微软 Edge 语音，对白由通义千问按角色配音（约 0.6 元/万字）。进度与单人说书共用，可随时切换。", color = theme.secondary, fontSize = 12.sp, lineHeight = 17.sp)
                val castNarrator = book?.let { app.director.cast(it.id).collectAsState().value.narratorVoice }
                val narrator = castNarrator ?: s.dramaNarratorVoice
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Label("旁白", theme)
                    Engines.edgeVoices.take(6).forEach { v ->
                        Chip("${v.name}  ${v.desc.substringBefore(' ')}", v.id == narrator, theme) {
                            scope.launch {
                                if (book != null) app.director.setNarrator(book.id, v.id) else app.settings.updateTts { it.copy(dramaNarratorVoice = v.id) }
                                app.tts.onSettingsChanged()
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                SpeedRow("语速", s.edgeRate / 100f, -0.5f..1f, { "${if (it >= 0) "+" else ""}${(it * 100).roundToInt()}%" }, theme) { v ->
                    scope.launch { app.settings.updateTts { it.copy(edgeRate = (v * 100).roundToInt()) }; app.tts.onSettingsChanged() }
                }
            }

            // engines (single-narrator mode)
            if (!drama) Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Engines.all.forEach { e ->
                    Chip(Engines.label(e), selected == e, theme) { chooseEngine(e) }
                    Spacer(Modifier.width(10.dp))
                }
            }
            if (!drama) Text(Engines.hint(selected), color = theme.secondary, fontSize = 12.sp)

            // voices
            val voices = if (drama) emptyList() else Engines.voicesOf(selected)
            if (voices.isNotEmpty()) {
                val cur = when (selected) { Engines.EDGE -> s.edgeVoice; Engines.COSY -> s.cosyVoice; Engines.QWEN -> s.qwenVoice; else -> s.geminiVoice }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    voices.forEach { v ->
                        Chip("${v.name}  ${v.desc.substringBefore(' ')}", v.id == cur, theme) {
                            scope.launch {
                                app.settings.updateTts {
                                    when (selected) {
                                        Engines.EDGE -> it.copy(edgeVoice = v.id)
                                        Engines.COSY -> it.copy(cosyVoice = v.id)
                                        Engines.QWEN -> it.copy(qwenVoice = v.id)
                                        else -> it.copy(geminiVoice = v.id)
                                    }
                                }
                                app.tts.onSettingsChanged()
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }

            // speed
            if (!drama) when (selected) {
                Engines.EDGE, Engines.SYSTEM -> SpeedRow("语速", s.edgeRate / 100f, -0.5f..1f, { "${if (it >= 0) "+" else ""}${(it * 100).roundToInt()}%" }, theme) { v ->
                    scope.launch { app.settings.updateTts { it.copy(edgeRate = (v * 100).roundToInt()) }; app.tts.onSettingsChanged() }
                }
                Engines.COSY -> SpeedRow("语速", s.cosyRate, 0.5f..2f, { String.format("%.1fx", it) }, theme) { v ->
                    scope.launch { app.settings.updateTts { it.copy(cosyRate = (v * 10).roundToInt() / 10f) }; app.tts.onSettingsChanged() }
                }
                Engines.QWEN -> Text("通义千问的语速和语气由「语气指令」控制（方言音色不支持指令），可在设置中修改。", color = theme.secondary, fontSize = 12.sp)
                else -> Text("Gemini 的语速和语气由「说书风格指令」控制，可在设置中修改。", color = theme.secondary, fontSize = 12.sp)
            }

            // sleep timer
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Label("定时", theme)
                listOf(0 to "关闭", 15 to "15分钟", 30 to "30分钟", 60 to "60分钟", -1 to "本章结束").forEach { (m, n) ->
                    Chip(n, tts.sleepChoice == m, theme) { app.tts.setSleep(m) }
                    Spacer(Modifier.width(8.dp))
                }
            }

            if (book != null && !drama) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本书固定使用当前引擎", color = theme.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = pin,
                    onCheckedChange = { on ->
                        pin = on
                        scope.launch { app.db.books().setTtsEngine(book.id, if (on) selected else null); onRefresh() }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Gold)
                )
            }

            // transport
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.tts.prev() }, enabled = thisBook) { Icon(Icons.Default.SkipPrevious, "上一句", tint = theme.text) }
                Spacer(Modifier.width(18.dp))
                FilledIconButton(
                    onClick = { if (thisBook) app.tts.togglePause() else onStart() },
                    modifier = Modifier.size(58.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Gold, contentColor = Color.White)
                ) { Icon(if (thisBook && tts.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放", modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.width(18.dp))
                IconButton(onClick = { app.tts.next() }, enabled = thisBook) { Icon(Icons.Default.SkipNext, "下一句", tint = theme.text) }
                Spacer(Modifier.width(18.dp))
                IconButton(onClick = { app.tts.stop() }, enabled = thisBook) { Icon(Icons.Default.Stop, "停止", tint = theme.secondary) }
            }
            Text("API Key、风格指令、费用统计 ›", color = theme.secondary, fontSize = 12.sp, modifier = Modifier.align(Alignment.End).clickable(onClick = onOpenSettings).padding(4.dp))
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun SpeedRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String, theme: ReaderTheme, onDone: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(label, theme)
        Slider(
            value = v, onValueChange = { v = it }, onValueChangeFinished = { onDone(v) }, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = theme.text, activeTrackColor = theme.text.copy(alpha = 0.7f), inactiveTrackColor = theme.secondary.copy(alpha = 0.3f)),
            modifier = Modifier.weight(1f)
        )
        Text(fmt(v), color = theme.text, fontSize = 13.sp, modifier = Modifier.width(56.dp).padding(start = 8.dp))
    }
}

