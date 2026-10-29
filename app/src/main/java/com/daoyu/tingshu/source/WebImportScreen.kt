package com.daoyu.tingshu.source

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.daoyu.tingshu.App
import com.daoyu.tingshu.ui.Palette

/**
 * 网页导入: an in-app browser. Browse any site as usual; a tap on a TXT / EPUB download goes straight to the
 * bookshelf. No rules to write — the site does the searching, we only catch the file.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebImportScreen(onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val tasks by app.sourceDownloads.tasks.collectAsState()
    var recent by remember { mutableStateOf(RecentSites.load(context)) }
    var saved by remember { mutableStateOf(SavedSites.load(context)) }
    var address by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(100) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var showing by remember { mutableStateOf(false) }   // false = start page with recent sites
    var pending by remember { mutableStateOf<String?>(null) }   // first URL, loaded once the WebView exists

    fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        focus.clearFocus()
        address = url
        showing = true
        web?.loadUrl(url) ?: run { pending = url }
        recent = RecentSites.add(context, url)
    }

    BackHandler {
        val w = web
        if (showing && w != null && w.canGoBack()) w.goBack() else if (showing) showing = false else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showing) pageTitle.ifBlank { "网页导入" } else "网页导入", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (showing) {
                        val here = web?.url ?: address
                        val isSaved = saved.any { it.url == here }
                        IconButton(onClick = {
                            saved = if (isSaved) SavedSites.remove(context, here) else SavedSites.add(context, pageTitle, here)
                            Toast.makeText(context, if (isSaved) "已取消收藏" else "已收藏，下次在网页导入首页点一下就能打开", Toast.LENGTH_SHORT).show()
                        }) {
                            if (isSaved) Icon(Icons.Default.Star, "取消收藏", tint = Palette.Gold) else Icon(Icons.Default.StarBorder, "收藏")
                        }
                        IconButton(onClick = { web?.reload() }) { Icon(Icons.Default.Refresh, "刷新") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            // a filled bar rather than an outline: some ROMs draw the outline and placeholder too faintly to see
            TextField(
                address, { address = it },
                label = { Text("网址") },
                placeholder = { Text("如 www.gutenberg.org，输入后点键盘上的「前往」") },
                leadingIcon = { Icon(Icons.Default.Language, null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedLabelColor = Palette.Gold,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    cursorColor = Palette.Gold,
                ),
                trailingIcon = {
                    if (address.isNotEmpty()) Row {
                        if (!showing) IconButton(onClick = { go(address) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "前往", tint = Palette.Gold) }
                        IconButton(onClick = { address = "" }) { Icon(Icons.Default.Close, "清空") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                keyboardActions = KeyboardActions(onGo = { go(address) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
            if (showing && progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Palette.Gold)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                // the WebView only exists while a page is shown: on some devices an idle WebView paints white over
                // the Compose views around it, hiding the address bar
                if (showing) AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { address = url }
                                override fun onPageFinished(view: WebView, url: String) { address = url; pageTitle = view.title.orEmpty() }
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val scheme = request.url.scheme ?: return false
                                    return scheme != "http" && scheme != "https"   // ignore app-store / intent links
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                                override fun onReceivedTitle(view: WebView, title: String?) { pageTitle = title.orEmpty() }
                            }
                            setDownloadListener { url, userAgent, disposition, mime, _ ->
                                if (!url.startsWith("http")) {
                                    Toast.makeText(ctx, "这个网站的下载方式不支持，请用手机浏览器下载后选「用刀鱼听书打开」", Toast.LENGTH_LONG).show()
                                    return@setDownloadListener
                                }
                                val name = URLUtil.guessFileName(url, disposition, mime)
                                app.sourceDownloads.downloadWeb(url, name, CookieManager.getInstance().getCookie(url), userAgent, this.url)
                                Toast.makeText(ctx, "开始下载 $name，完成后自动加入书架", Toast.LENGTH_SHORT).show()
                            }
                            web = this
                            pending?.let { loadUrl(it); pending = null }
                        }
                    },
                    onRelease = { if (web === it) web = null; it.destroy() },
                    modifier = Modifier.fillMaxSize()
                )
                else StartPage(
                    saved, recent, onPick = { go(it) },
                    onUnsave = { saved = SavedSites.remove(context, it) },
                    onSave = { saved = SavedSites.add(context, "", it) },
                    onRemove = { recent = RecentSites.remove(context, it) },
                )
            }

            // downloads started from this page
            val mine = tasks.filter { it.sourceName == "网页导入" }.take(3)
            if (mine.isNotEmpty()) Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                mine.forEach { t -> TaskRow(t, onCancel = { app.sourceDownloads.cancel(t.id) }, onOpen = { onOpenBook(t.bookId) }) }
            }
        }
    }
}

@Composable
private fun StartPage(
    saved: List<SavedSites.Site>, recent: List<String>, onPick: (String) -> Unit,
    onUnsave: (String) -> Unit, onSave: (String) -> Unit, onRemove: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "在上面的「网址」框里输入网站地址，点键盘上的「前往」打开。像平时一样搜书，点到 TXT 或 EPUB 的下载链接时，书会自动下载并加入书架，不用写任何规则。\n" +
                    "需要登录的网站，在这里登录一次就会记住。请只下载你有权获取的正版或公版书。",
                fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (saved.isNotEmpty()) Text("收藏", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            saved.forEach { site ->
                SiteRow(site.title.ifBlank { site.url.short() }, if (site.title.isBlank()) null else site.url.short(), onClick = { onPick(site.url) }) {
                    IconButton(onClick = { onUnsave(site.url) }) { Icon(Icons.Default.Star, "取消收藏", tint = Palette.Gold) }
                }
            }
            val unsaved = recent.filter { r -> saved.none { it.url == r } }
            if (unsaved.isNotEmpty()) Text("最近访问", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            unsaved.forEach { url ->
                SiteRow(url.short(), null, onClick = { onPick(url) }) {
                    IconButton(onClick = { onSave(url) }) { Icon(Icons.Default.StarBorder, "收藏") }
                    IconButton(onClick = { onRemove(url) }) { Icon(Icons.Default.Close, "删除") }
                }
            }
            if (saved.isEmpty()) Text("打开网页后点右上角的 ☆ 就能收藏，下次在这里点一下直接打开。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.heightIn(min = 8.dp))
        }
    }
}

private fun String.short() = substringAfter("://").removePrefix("www.").trimEnd('/')

@Composable
private fun SiteRow(title: String, sub: String?, onClick: () -> Unit, actions: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 14.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                sub?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            actions()
        }
    }
}

/** Pages the user starred in 网页导入, in the order they were saved; stored as "title\turl" lines. */
internal object SavedSites {
    data class Site(val title: String, val url: String)

    private const val KEY = "web_import_saved"
    private fun prefs(c: Context) = c.getSharedPreferences("web_import", Context.MODE_PRIVATE)

    fun load(c: Context): List<Site> = prefs(c).getString(KEY, "")!!.split('\n').filter { it.isNotBlank() }.map {
        if ('\t' in it) Site(it.substringBefore('\t'), it.substringAfter('\t')) else Site("", it)
    }

    private fun store(c: Context, list: List<Site>): List<Site> {
        prefs(c).edit().putString(KEY, list.joinToString("\n") { it.title.replace(Regex("[\t\n]"), " ").trim() + "\t" + it.url }).apply()
        return list
    }

    fun add(c: Context, title: String, url: String): List<Site> = store(c, load(c).filter { it.url != url } + Site(title.trim(), url))

    fun remove(c: Context, url: String): List<Site> = store(c, load(c).filter { it.url != url })
}

/** Sites opened in 网页导入, newest first. */
private object RecentSites {
    private const val KEY = "web_import_recent"
    private fun prefs(c: Context) = c.getSharedPreferences("web_import", Context.MODE_PRIVATE)

    fun load(c: Context): List<String> = prefs(c).getString(KEY, "")!!.split('\n').filter { it.isNotBlank() }

    fun add(c: Context, url: String): List<String> {
        val host = url.substringAfter("://").substringBefore('/')
        val list = (listOf(url) + load(c).filter { it.substringAfter("://").substringBefore('/') != host }).take(8)
        prefs(c).edit().putString(KEY, list.joinToString("\n")).apply()
        return list
    }

    fun remove(c: Context, url: String): List<String> {
        val list = load(c) - url
        prefs(c).edit().putString(KEY, list.joinToString("\n")).apply()
        return list
    }
}
