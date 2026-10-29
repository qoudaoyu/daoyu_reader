package com.daoyu.tingshu.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoyu.tingshu.App
import com.daoyu.tingshu.data.TtsUsage
import com.daoyu.tingshu.ui.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「AI 添加书源」: a site address and one title in, a tested book source out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceAiScreen(onBack: () -> Unit, onEdit: (String) -> Unit) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var site by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    val steps = remember { mutableStateListOf<String>() }
    var job by remember { mutableStateOf<Job?>(null) }
    var made by remember { mutableStateOf<BookSource?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var partial by remember { mutableStateOf<BookSource?>(null) }
    val running = job?.isActive == true

    fun start() {
        if (site.isBlank() || title.isBlank()) return
        focus.clearFocus()
        steps.clear(); made = null; error = null; partial = null
        job = scope.launch {
            val llm = RuleWriter.qwen(app.keys.cosyKey) { inTok, outTok -> recordUsage(inTok, outTok) }
            try {
                made = RuleWriter(llm = llm).write(site, title.trim()) { steps += it }
            } catch (e: RuleWriter.Failed) {
                error = e.message; partial = e.partial?.takeIf { it.searchUrl.isNotBlank() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            job = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 添加书源") },
                navigationIcon = { IconButton(onClick = { job?.cancel(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("填网站地址和一本这个网站上有的书，AI 会自己读网页、写好搜索和下载规则，并实际搜一遍确认能用。",
                fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(site, { site = it }, label = { Text("网站地址") }, placeholder = { Text("如 www.example.com") }, singleLine = true, enabled = !running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(title, { title = it }, label = { Text("一本能搜到的书名") }, singleLine = true, enabled = !running, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { if (running) job?.cancel() else start() },
                enabled = running || (site.isNotBlank() && title.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = if (running) MaterialTheme.colorScheme.secondary else Palette.Gold),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (running) "停止" else "开始") }
            Text("用设置里的阿里云 Key 调用千问，一次大约几分钱，记在「本月用量」的角色分析里。", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.outline)

            if (steps.isNotEmpty() || running) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (i == steps.lastIndex && running) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            else Icon(Icons.Default.CheckCircle, null, tint = Palette.Gold, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s, fontSize = 13.sp)
                        }
                    }
                }
            }

            made?.let { s ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("书源已写好", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("${s.bookSourceName} · ${if (s.isFile) "下载文件" else "逐章下载"}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { app.sources.save(s); onBack() }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Gold)) { Text("保存") }
                            OutlinedButton(onClick = { app.sources.save(s); onEdit(s.bookSourceUrl) }) { Text("保存并查看规则") }
                        }
                    }
                }
            }

            error?.let { e ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("没写成", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                        Text(e, fontSize = 13.sp, lineHeight = 19.sp)
                        Text("可以换一本书名再试；网站要登录、有人机验证，或者结果靠 JavaScript 加载时，AI 也写不出来。", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        partial?.let { p -> OutlinedButton(onClick = { app.sources.save(p.copy(enabled = false)); onEdit(p.bookSourceUrl) }) { Text("保存已写好的部分，手动改") } }
                    }
                }
            }
        }
    }
}

/** Logged with 角色分析 (engine "director"): same key, same model, same price table. */
private suspend fun recordUsage(inTokens: Long, outTokens: Long) {
    val dao = App.instance.db.usage()
    val m = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
    val u = dao.get(m, "director") ?: TtsUsage(m, "director", 0, 0, 0, 0)
    dao.put(u.copy(chars = u.chars + inTokens, audioTokens = u.audioTokens + outTokens, requests = u.requests + 1))
}
