package com.daoyu.tingshu.source

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoyu.tingshu.App
import com.daoyu.tingshu.ui.Palette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val sources by app.sources.sources.collectAsState()
    var importing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书源") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { importing = true }) { Icon(Icons.Default.ContentPaste, "粘贴导入") }
                    IconButton(onClick = { copy(context, Sources.export(sources)); Toast.makeText(context, "已复制全部书源 JSON", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.Share, "导出") }
                    IconButton(onClick = { onEdit(null) }) { Icon(Icons.Default.Add, "新建书源") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sources, key = { it.bookSourceUrl }) { s ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable { onEdit(s.bookSourceUrl) }) {
                    Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.bookSourceName.ifBlank { "（未命名）" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.bookSourceUrl, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(if (s.isFile) "文件下载" else "逐章下载", s.bookSourceGroup.ifBlank { null }, if (s.usesJs) "含 JS 规则，可能不可用" else null).joinToString(" · "),
                                fontSize = 11.5.sp, color = if (s.usesJs) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = s.enabled, onCheckedChange = { app.sources.setEnabled(s.bookSourceUrl, it) }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Gold))
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "点右上角 + 新建书源，填写网站地址和规则；也可以复制「阅读」(Legado) 格式的书源 JSON，点粘贴图标导入（支持一次导入多个）。" +
                            "规则支持 CSS 选择器、阅读的默认语法、JSONPath 和 XPath，不支持 JavaScript。",
                        fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.outline
                    )
                    if (Sources.builtin.any { b -> sources.none { it.bookSourceUrl == b.bookSourceUrl } })
                        TextButton(onClick = { app.sources.restoreBuiltin() }) { Text("恢复内置示例书源") }
                }
            }
        }
    }

    if (importing) {
        var text by remember { mutableStateOf(clipboard(context)) }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("导入书源") },
            text = {
                OutlinedTextField(text, { text = it }, label = { Text("书源 JSON（单个或数组）") }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp))
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = runCatching { app.sources.importJson(text) }
                    Toast.makeText(context, n.fold({ if (it > 0) "已导入 $it 个书源" else "没有找到有效书源（需要 bookSourceUrl）" }, { "JSON 格式不对：${it.message}" }), Toast.LENGTH_LONG).show()
                    if (n.getOrDefault(0) > 0) importing = false
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceEditScreen(url: String, onBack: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember(url) { if (url.isBlank()) null else app.sources.get(url) }
    var s by remember(url) { mutableStateOf(original ?: BookSource(searchUrl = "/search?q={{key}}")) }
    var testKey by remember { mutableStateOf("") }
    var testOut by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save(): Boolean {
        val fixed = s.copy(bookSourceUrl = s.bookSourceUrl.trim().trimEnd('/'), bookSourceName = s.bookSourceName.trim())
        if (!fixed.bookSourceUrl.startsWith("http://") && !fixed.bookSourceUrl.startsWith("https://")) {
            Toast.makeText(context, "网站地址要以 http:// 或 https:// 开头", Toast.LENGTH_LONG).show(); return false
        }
        app.sources.save(fixed.copy(bookSourceName = fixed.bookSourceName.ifBlank { fixed.bookSourceUrl.substringAfter("://").substringBefore('/') }), original?.bookSourceUrl)
        return true
    }

    fun test() {
        if (testKey.isBlank()) { testOut = "先填一个搜索关键词"; return }
        testing = true; testOut = null
        scope.launch {
            val c = SourceClient()
            testOut = try {
                val res = c.search(s.copy(bookSourceUrl = s.bookSourceUrl.trim().trimEnd('/')), testKey.trim())
                buildString {
                    append("搜索到 ${res.size} 条结果\n")
                    res.take(5).forEach { append("· ${it.name}  ${it.author}\n   ${it.bookUrl}\n") }
                    res.firstOrNull()?.let { r ->
                        val d = c.detail(r)
                        if (s.isFile) {
                            append("\n第一本的下载链接：${d.downloads.size} 个\n")
                            d.downloads.take(5).forEach { append("   $it\n") }
                        } else {
                            val toc = c.toc(r.source, d.tocUrl)
                            append("\n第一本的目录：${toc.size} 章\n")
                            toc.take(3).forEach { append("· ${it.name}\n") }
                            toc.firstOrNull()?.let { ch ->
                                val text = c.content(r.source, ch.url, toc.getOrNull(1)?.url)
                                append("\n第一章正文 ${text.length} 字：\n${text.take(120)}…")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                "出错：${e.message ?: e.javaClass.simpleName}"
            }
            testing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (original == null) "新建书源" else "编辑书源") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { TextButton(onClick = { if (save()) onBack() }) { Text("保存", color = Palette.Gold) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Block("基本") {
                Field("名称", s.bookSourceName) { s = s.copy(bookSourceName = it) }
                Field("网站地址", s.bookSourceUrl, "例如 https://www.example.com，规则里的相对链接以它为准") { s = s.copy(bookSourceUrl = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("下载方式", fontSize = 13.sp, modifier = Modifier.width(64.dp))
                    FilterChip(selected = !s.isFile, onClick = { s = s.copy(bookSourceType = BookSource.TYPE_TEXT, ruleBookInfo = s.ruleBookInfo.copy(downloadUrls = "")) }, label = { Text("逐章下载") })
                    FilterChip(selected = s.isFile, onClick = { s = s.copy(bookSourceType = BookSource.TYPE_FILE) }, label = { Text("下载文件") })
                }
                Hint(if (s.isFile) "详情页上有 TXT / EPUB 下载链接，填下面的「下载链接」规则。" else "网站按章节展示，按目录逐章抓取正文，合成一本 TXT。")
                Field("分组", s.bookSourceGroup) { s = s.copy(bookSourceGroup = it) }
                Field("请求头（JSON，可选）", s.header, "例如 {\"User-Agent\":\"…\",\"Cookie\":\"…\"}", mono = true) { s = s.copy(header = it) }
                Field("备注", s.bookSourceComment, singleLine = false) { s = s.copy(bookSourceComment = it) }
            }
            Block("搜索") {
                Field("搜索地址", s.searchUrl, "{{key}} 换成关键词，{{page}} 换成页码。POST：/s.php,{\"method\":\"POST\",\"body\":\"q={{key}}\"}；GBK 网站加 \"charset\":\"gbk\"", mono = true) { s = s.copy(searchUrl = it) }
                val r = s.ruleSearch
                Field("结果列表", r.bookList, "每条结果对应的元素，如 div.result-item 或 $.data[*]", mono = true) { s = s.copy(ruleSearch = r.copy(bookList = it)) }
                Field("书名", r.name, "在每条结果里取，如 h3 a@text", mono = true) { s = s.copy(ruleSearch = r.copy(name = it)) }
                Field("作者", r.author, mono = true) { s = s.copy(ruleSearch = r.copy(author = it)) }
                Field("详情页链接", r.bookUrl, "如 h3 a@href", mono = true) { s = s.copy(ruleSearch = r.copy(bookUrl = it)) }
                Field("简介（可选）", r.intro, mono = true) { s = s.copy(ruleSearch = r.copy(intro = it)) }
                Field("封面（可选）", r.coverUrl, mono = true) { s = s.copy(ruleSearch = r.copy(coverUrl = it)) }
                Field("最新章节（可选）", r.lastChapter, mono = true) { s = s.copy(ruleSearch = r.copy(lastChapter = it)) }
            }
            Block("详情页") {
                val r = s.ruleBookInfo
                if (s.isFile) Field("下载链接", r.downloadUrls, "如 a[href$=.epub]@href||a[href$=.txt]@href，找到多个时都列出来", mono = true) { s = s.copy(ruleBookInfo = r.copy(downloadUrls = it)) }
                else Field("目录页链接（可选）", r.tocUrl, "目录不在详情页时填写，如 a.read-btn@href", mono = true) { s = s.copy(ruleBookInfo = r.copy(tocUrl = it)) }
                Field("书名（可选）", r.name, "留空用搜索结果里的", mono = true) { s = s.copy(ruleBookInfo = r.copy(name = it)) }
                Field("作者（可选）", r.author, mono = true) { s = s.copy(ruleBookInfo = r.copy(author = it)) }
                Field("简介（可选）", r.intro, mono = true) { s = s.copy(ruleBookInfo = r.copy(intro = it)) }
            }
            if (!s.isFile) {
                Block("目录") {
                    val r = s.ruleToc
                    Field("章节列表", r.chapterList, "如 #list dd a 或 -ul.chapters li（开头的 - 表示倒序）", mono = true) { s = s.copy(ruleToc = r.copy(chapterList = it)) }
                    Field("章节名", r.chapterName, "留空取链接文字", mono = true) { s = s.copy(ruleToc = r.copy(chapterName = it)) }
                    Field("章节链接", r.chapterUrl, "留空取 href", mono = true) { s = s.copy(ruleToc = r.copy(chapterUrl = it)) }
                    Field("目录下一页（可选）", r.nextTocUrl, mono = true) { s = s.copy(ruleToc = r.copy(nextTocUrl = it)) }
                }
                Block("正文") {
                    val r = s.ruleContent
                    Field("正文", r.content, "如 #content@html（保留分段）", mono = true) { s = s.copy(ruleContent = r.copy(content = it)) }
                    Field("正文下一页（可选）", r.nextContentUrl, "一章分多页时填写，如 a#next@href", mono = true) { s = s.copy(ruleContent = r.copy(nextContentUrl = it)) }
                    Field("净化（可选）", r.replaceRegex, "##正则##替换为，可以连写多组，如 ##本站网址.*##", mono = true) { s = s.copy(ruleContent = r.copy(replaceRegex = it)) }
                }
            }
            Block("测试") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(testKey, { testKey = it }, label = { Text("关键词") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { test() }, enabled = !testing, colors = ButtonDefaults.buttonColors(containerColor = Palette.Gold)) { Text(if (testing) "测试中…" else "测试") }
                }
                testOut?.let { Text(it, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace) }
                Hint("用当前填写的规则（不用先保存）走一遍：搜索 → 第一本的详情 → 下载链接或目录和第一章。")
            }
            Block("规则写法") {
                Hint(RULE_HELP)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { copy(context, Sources.export(listOf(s))); Toast.makeText(context, "已复制 JSON", Toast.LENGTH_SHORT).show() }) { Text("复制 JSON") }
                if (original != null) OutlinedButton(onClick = { confirmDelete = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.width(1.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("删除书源？") },
        text = { Text(s.bookSourceName) },
        confirmButton = { TextButton(onClick = { original?.let { app.sources.delete(it.bookSourceUrl) }; confirmDelete = false; onBack() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
}

private const val RULE_HELP = """· CSS 选择器后接 @ 取值：div.book h3 a@text、a.cover@href、img@src、#content@html
  取值方式：text 文字、ownText 自身文字、html 正文（保留分段）、href/src 等任意属性
· 阅读默认语法：class.item@tag.a.0@href（.0 第一个，.-1 最后一个，!0 排除第一个）
· JSON 接口：$.data.list[*]、$.title、$..id、$['application/epub+zip']
· XPath：//div[@class='list']/a/@href
· 组合：规则1||规则2 取第一个有结果的；规则1&&规则2 两个都要；规则##正则##替换 清理结果；/book/{{$.id}}.html 拼接
· 搜索地址：{{key}} 关键词、{{page}} 页码，可写 {{(page-1)*20}}
· 不支持 JavaScript 规则。"""

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String, hint: String? = null, mono: Boolean = false, singleLine: Boolean = !mono, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label, fontSize = 12.sp) },
        supportingText = hint?.let { { Text(it, fontSize = 11.sp, lineHeight = 14.sp) } },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 4,
        textStyle = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Hint(t: String) = Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, lineHeight = 17.sp)

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("book source", text))
}

private fun clipboard(context: Context): String =
    context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.trimStart().let { t -> t.startsWith("{") || t.startsWith("[") } } ?: ""
