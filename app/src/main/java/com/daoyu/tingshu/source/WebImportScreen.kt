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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    var address by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(100) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var showing by remember { mutableStateOf(false) }   // false = start page with recent sites

    fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        focus.clearFocus()
        address = url
        showing = true
        web?.loadUrl(url)
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
                actions = { if (showing) IconButton(onClick = { web?.reload() }) { Icon(Icons.Default.Refresh, "刷新") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            OutlinedTextField(
                address, { address = it },
                placeholder = { Text("输入网址，如 www.gutenberg.org") },
                singleLine = true,
                trailingIcon = { if (address.isNotEmpty()) IconButton(onClick = { address = "" }) { Icon(Icons.Default.Close, "清空") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                keyboardActions = KeyboardActions(onGo = { go(address) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
            if (showing && progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Palette.Gold)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
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
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxSize()
                )
                if (!showing) StartPage(recent, onPick = { go(it) }, onRemove = { recent = RecentSites.remove(context, it) })
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
private fun StartPage(recent: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "在这里像平时一样打开网站、搜书。点到 TXT 或 EPUB 的下载链接时，书会自动下载并加入书架，不用写任何规则。\n" +
                    "需要登录的网站，在这里登录一次就会记住。请只下载你有权获取的正版或公版书。",
                fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (recent.isNotEmpty()) Text("最近访问", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            recent.forEach { url ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable { onPick(url) }) {
                    Row(Modifier.padding(start = 14.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(url.substringAfter("://").trimEnd('/'), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemove(url) }) { Icon(Icons.Default.Close, "删除", Modifier.padding(2.dp)) }
                    }
                }
            }
            Spacer(Modifier.heightIn(min = 8.dp))
        }
    }
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
