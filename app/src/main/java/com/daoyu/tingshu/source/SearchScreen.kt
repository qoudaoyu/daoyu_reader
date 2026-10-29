package com.daoyu.tingshu.source

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoyu.tingshu.App
import com.daoyu.tingshu.ui.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onSources: () -> Unit, onWeb: () -> Unit, onOpenBook: (Long) -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val sources by app.sources.sources.collectAsState()
    val tasks by app.sourceDownloads.tasks.collectAsState()
    val enabled = sources.filter { it.enabled }

    var query by remember { mutableStateOf("") }
    var only by remember { mutableStateOf<String?>(null) }      // a single source's URL, or null for all
    val results = remember { mutableStateListOf<SearchResult>() }
    val errors = remember { mutableStateListOf<String>() }
    var pending by remember { mutableStateOf(0) }
    var searched by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var picked by remember { mutableStateOf<SearchResult?>(null) }

    fun search() {
        val key = query.trim()
        if (key.isEmpty()) return
        focus.clearFocus()
        job?.cancel()
        results.clear(); errors.clear(); searched = true
        val targets = enabled.filter { only == null || it.bookSourceUrl == only }
        if (targets.isEmpty()) { Toast.makeText(context, "没有启用的书源，先去「书源」里添加", Toast.LENGTH_LONG).show(); return }
        pending = targets.size
        job = scope.launch {
            supervisorScope {
                targets.forEach { src ->
                    launch {
                        try {
                            results += SourceClient().search(src, key)
                        } catch (e: Exception) {
                            if (e !is kotlinx.coroutines.CancellationException) errors += "${src.bookSourceName}：${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            pending--
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜书") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = onWeb) { Text("网页导入") }
                    TextButton(onClick = onSources) { Text("书源（${sources.size}）") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("书名或作者") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清空") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (enabled.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = only == null, onClick = { only = null }, label = { Text("全部书源") })
                enabled.forEach { s -> FilterChip(selected = only == s.bookSourceUrl, onClick = { only = s.bookSourceUrl }, label = { Text(s.bookSourceName.ifBlank { s.bookSourceUrl }) }) }
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tasks.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("下载", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            if (tasks.any { it.status != SourceDownloads.Status.RUNNING }) TextButton(onClick = { app.sourceDownloads.clearFinished() }) { Text("清除已完成", fontSize = 13.sp) }
                        }
                    }
                    items(tasks, key = { "t" + it.id }) { t -> TaskRow(t, onCancel = { app.sourceDownloads.cancel(t.id) }, onOpen = { onOpenBook(t.bookId) }) }
                    item { Spacer(Modifier.height(6.dp)) }
                }
                if (pending > 0) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在搜索 $pending 个书源…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(errors.toList()) { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                items(results.toList(), key = { "r" + it.source.bookSourceUrl + it.bookUrl + it.name }) { r -> ResultRow(r) { picked = r } }
                if (searched && pending == 0 && results.isEmpty()) item {
                    Text("没有找到结果。可以换个关键词，或在「书源」里检查搜索规则。", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                }
                if (!searched && tasks.isEmpty()) item {
                    Text(
                        "在已启用的书源里搜索并下载书籍，下载后自动加入书架，可直接阅读和听书。\n\n" +
                            "书源由你自己添加：在「书源」里填写网站地址和搜索、解析规则，也可以粘贴「阅读」(Legado) 格式的书源 JSON。" +
                            "内置的「古腾堡计划」是公版书示例。\n\n" +
                            "不想写规则？点右上角「网页导入」，像用浏览器一样打开网站，点下载链接书就会自动进书架。请只使用你有权下载的正版或公版资源。",
                        fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    picked?.let { r -> DetailDialog(r, onDismiss = { picked = null }) }
}

@Composable
private fun ResultRow(r: SearchResult, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(r.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(r.author, r.kind, r.source.bookSourceName).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.intro.isNotBlank()) Text(r.intro, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.lastChapter.isNotBlank()) Text("最新：${r.lastChapter}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
internal fun TaskRow(t: SourceDownloads.Task, onCancel: () -> Unit, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.message, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (t.status == SourceDownloads.Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                }
                when (t.status) {
                    SourceDownloads.Status.RUNNING -> TextButton(onClick = onCancel) { Text("取消") }
                    SourceDownloads.Status.DONE -> TextButton(onClick = onOpen) { Text("打开", color = Palette.Gold) }
                    else -> {}
                }
            }
            if (t.status == SourceDownloads.Status.RUNNING) {
                if (t.total > 0) LinearProgressIndicator(progress = { t.done.toFloat() / t.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = Palette.Gold)
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp), color = Palette.Gold)
            }
        }
    }
}

@Composable
private fun DetailDialog(r: SearchResult, onDismiss: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    var detail by remember { mutableStateOf<BookDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(r) {
        try { detail = SourceClient().detail(r) } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
    }
    fun started() { Toast.makeText(context, "已开始下载《${detail?.name ?: r.name}》", Toast.LENGTH_SHORT).show(); onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail?.name ?: r.name) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val d = detail
                val author = d?.author ?: r.author
                if (author.isNotBlank()) Text("作者：$author", fontSize = 14.sp)
                Text("书源：${r.source.bookSourceName}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                when {
                    error != null -> Text("打开详情页失败：$error", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    d == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("正在读取详情…", fontSize = 13.sp)
                    }
                    else -> {
                        if (d.intro.isNotBlank()) Text(d.intro, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (r.source.isFile) {
                            if (d.downloads.isEmpty()) Text("详情页里没有找到下载链接，请检查书源的「下载链接」规则。", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            d.downloads.forEachIndexed { i, url ->
                                Button(
                                    onClick = { app.sourceDownloads.downloadFile(r, d, url); started() },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (i == 0) Palette.Gold else MaterialTheme.colorScheme.secondary),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("下载 " + linkLabel(url, i), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        } else {
                            Button(
                                onClick = { app.sourceDownloads.downloadChapters(r, d); started() },
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.Gold),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("下载全部章节到书架") }
                            Text("逐章下载后合成一本书，章节多的书需要几分钟。", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun linkLabel(url: String, i: Int): String {
    val low = url.lowercase()
    val kind = when {
        low.contains("epub") -> "EPUB"
        low.contains(".txt") || low.contains("txt.") || low.contains("/txt") -> "TXT"
        else -> "文件 ${i + 1}"
    }
    return kind + "  " + url.substringAfterLast('/').take(40)
}
