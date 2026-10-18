package com.daoyu.tingshu.reader

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import com.daoyu.tingshu.ui.Palette
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daoyu.tingshu.App
import com.daoyu.tingshu.KeyEvents
import com.daoyu.tingshu.data.ReaderSettings
import com.daoyu.tingshu.ui.Fonts
import com.daoyu.tingshu.ui.NIGHT_THEME
import com.daoyu.tingshu.ui.ReaderTheme
import com.daoyu.tingshu.ui.ReaderThemes
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Panel { None, Menu, Toc, Style, Listen }

@Composable
fun ReaderScreen(bookId: Long, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenCast: () -> Unit = {}) {
    val vm: ReaderViewModel = viewModel(key = "reader$bookId", factory = ReaderViewModel.Factory(bookId))
    val app = App.instance
    val context = LocalContext.current
    val activity = context as Activity
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val settings by app.settings.reader.collectAsState()
    val ui by vm.ui.collectAsState()
    val book by vm.book.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val tts by app.tts.state.collectAsState()

    var panel by remember { mutableStateOf(Panel.None) }
    var lastDayTheme by remember { mutableIntStateOf(if (settings.theme == NIGHT_THEME) 1 else settings.theme) }
    var longPressAt by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val themeIdx = if (settings.followSystemDark && systemDark && !ReaderThemes[settings.theme].dark) NIGHT_THEME else settings.theme
    val theme = ReaderThemes[themeIdx.coerceIn(ReaderThemes.indices)]

    // ---- window: immersive bars, keep screen on, brightness
    DisposableEffect(panel == Panel.None, theme.dark) {
        val window = activity.window
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.isAppearanceLightStatusBars = !theme.dark
        ctl.isAppearanceLightNavigationBars = !theme.dark
        if (panel == Panel.None) ctl.hide(WindowInsetsCompat.Type.systemBars()) else ctl.show(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(settings.keepScreenOn, settings.brightness) {
        val window = activity.window
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = if (settings.brightness < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else settings.brightness.coerceIn(0.01f, 1f)
        window.attributes = lp
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val l2 = window.attributes
            l2.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = l2
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = activity.window
            val ctl = WindowCompat.getInsetsController(window, window.decorView)
            ctl.show(WindowInsetsCompat.Type.systemBars())
            ctl.isAppearanceLightStatusBars = !systemDark
            ctl.isAppearanceLightNavigationBars = !systemDark
            KeyEvents.interceptVolume = false
        }
    }

    LaunchedEffect(Unit) { vm.toast.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    LaunchedEffect(Unit) { app.tts.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }

    val turner = remember { TurnController() }
    KeyEvents.interceptVolume = settings.volumeKeyTurn && panel == Panel.None
    LaunchedEffect(Unit) {
        KeyEvents.volume.collect { forward -> if (panel == Panel.None) turner.animateTurn(forward) }
    }

    BackHandler(panel != Panel.None) { panel = if (panel == Panel.Menu) Panel.None else Panel.Menu }

    val cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val insets = PageInsets(
        left = settings.marginH.dp, right = settings.marginH.dp,
        top = maxOf(cutoutTop, 18.dp) + 44.dp, bottom = 48.dp
    )

    Box(Modifier.fillMaxSize().background(theme.bg)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val wPx = constraints.maxWidth
            val hPx = constraints.maxHeight
            LaunchedEffect(wPx, hPx, settings) {
                with(density) {
                    vm.setSpec(
                        LayoutSpec(
                            width = wPx - (insets.left + insets.right).roundToPx(),
                            height = hPx - (insets.top + insets.bottom).roundToPx(),
                            textSizePx = settings.fontSizeSp.sp.toPx(),
                            lineSpacing = settings.lineSpacing,
                            paraSpacingPx = settings.paraSpacing * settings.fontSizeSp.sp.toPx(),
                            typeface = Fonts.typeface(context, settings.font, settings.customFontPath),
                            indent = settings.indent,
                            bold = settings.boldText,
                        )
                    )
                }
            }
        }

        val highlight = if (tts.active && tts.bookId == bookId) Highlight(tts.chapter, tts.segStart, tts.segEnd) else null

        PageTurner(
            ui = ui,
            anim = settings.pageAnim,
            theme = theme,
            controller = turner,
            onTurn = { fwd -> vm.turn(fwd) },
            onCenterTap = { panel = if (panel == Panel.None) Panel.Menu else Panel.None },
            onLongPress = { pos ->
                val cur = ui.cur ?: return@PageTurner
                with(density) {
                    val l = cur.layout.layout
                    val y = pos.y - insets.top.toPx() + l.getLineTop(cur.page.startLine)
                    val line = l.getLineForVertical(y.toInt()).coerceIn(cur.page.startLine, cur.page.endLine - 1)
                    val off = l.getOffsetForHorizontal(line, pos.x - insets.left.toPx())
                    longPressAt = cur.page.chapter to cur.layout.layoutToBody(off)
                }
            },
        ) { ref, mod ->
            PageContent(ref, theme, insets, highlight, vm.progressOf(ref), settings.showInfoBar, mod)
        }

        if (ui.loading) {
            Text("正在排版…", color = theme.secondary, modifier = Modifier.align(Alignment.Center))
        }

        // mini player while listening
        AnimatedVisibility(
            visible = tts.active && tts.bookId == bookId && panel == Panel.None,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 54.dp)
        ) {
            MiniPlayer(theme, tts.playing, tts.loading, tts.status.ifEmpty { if (tts.drama) (tts.speaker ?: "旁白") else com.daoyu.tingshu.tts.Engines.label(tts.engine) },
                onToggle = { app.tts.togglePause() }, onOpen = { panel = Panel.Listen }, onStop = { app.tts.stop() })
        }

        // tap-catcher behind menus
        if (panel != Panel.None) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (panel == Panel.Menu) 0f else 0.28f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { panel = Panel.None }
            )
        }

        // ---- top bar
        AnimatedVisibility(panel == Panel.Menu, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(color = theme.bg, shadowElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = theme.text) }
                    Text(book?.title ?: "", color = theme.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.addBookmark() }) { Icon(Icons.Default.BookmarkAdd, "书签", tint = theme.text) }
                    IconButton(onClick = { panel = Panel.Listen; if (!(tts.active && tts.bookId == bookId)) vm.startListening() }) {
                        Icon(Icons.Default.Headphones, "听书", tint = theme.text)
                    }
                }
            }
        }

        // ---- bottom menu
        AnimatedVisibility(panel == Panel.Menu, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            BottomMenu(
                theme = theme,
                chapterIdx = ui.cur?.page?.chapter ?: 0,
                chapterCount = chapters.size,
                chapterTitle = ui.cur?.layout?.title ?: "",
                nightOn = theme.dark,
                onJump = { vm.jumpToChapter(it) },
                onToc = { panel = Panel.Toc },
                onListen = { panel = Panel.Listen; if (!(tts.active && tts.bookId == bookId)) vm.startListening() },
                onNight = {
                    scope.launch {
                        if (theme.dark) app.settings.updateReader { it.copy(theme = lastDayTheme, followSystemDark = false) }
                        else { lastDayTheme = settings.theme; app.settings.updateReader { it.copy(theme = NIGHT_THEME, followSystemDark = false) } }
                    }
                },
                onStyle = { panel = Panel.Style },
            )
        }

        // ---- table of contents (slides from the left)
        AnimatedVisibility(panel == Panel.Toc, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }, modifier = Modifier.align(Alignment.CenterStart)) {
            TocPanel(vm, theme, ui.cur?.page?.chapter ?: 0) { ch, off -> vm.jumpToChapter(ch, off); panel = Panel.None }
        }

        AnimatedVisibility(panel == Panel.Style, enter = slideInVertically { it }, exit = slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
            StylePanel(settings, theme, onChange = { f -> scope.launch { app.settings.updateReader(f) } }, onMore = onOpenSettings)
        }

        AnimatedVisibility(panel == Panel.Listen, enter = slideInVertically { it }, exit = slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
            ListenPanel(book, theme, onStart = { vm.startListening() }, onOpenSettings = onOpenSettings, onRefresh = { vm.refreshBook() }, onOpenCast = onOpenCast)
        }

        var speakerPick by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }
        longPressAt?.let { (ch, off) ->
            val drama = book?.dramaMode == true
            val line = if (drama) app.director.lineAt(bookId, ch, off) else null
            val listening = tts.active && tts.bookId == bookId
            LongPressDialog(theme,
                speaker = line?.speaker,
                canResynth = listening,
                onListen = { vm.listenFrom(ch, off); longPressAt = null },
                onBookmark = { vm.addBookmark(); longPressAt = null },
                onChangeSpeaker = { speakerPick = Triple(ch, off, line?.speaker ?: ""); longPressAt = null },
                onResynth = {
                    val ok = app.tts.resynthesizeAt(ch, off)
                    Toast.makeText(context, if (ok) "正在重新合成这一句" else "这一句不在当前听书队列中", Toast.LENGTH_SHORT).show()
                    longPressAt = null
                },
                onDismiss = { longPressAt = null })
        }
        speakerPick?.let { (ch, off, current) ->
            val cast by app.director.cast(bookId).collectAsState()
            SpeakerDialog(theme, current, cast.members.filter { it.mergedInto == null }.sortedByDescending { it.lines }.map { it.name },
                onPick = { name ->
                    scope.launch {
                        if (app.director.overrideSpeaker(bookId, ch, off, name)) app.tts.refreshAt(ch, off)
                    }
                    speakerPick = null
                },
                onDismiss = { speakerPick = null })
        }
    }
}

@Composable
private fun MiniPlayer(theme: ReaderTheme, playing: Boolean, loading: Boolean, engine: String, onToggle: () -> Unit, onOpen: () -> Unit, onStop: () -> Unit) {
    val bg = if (theme.dark) Color(0xFF2A2A2C) else Color.White
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(bg).border(0.5.dp, theme.secondary.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen).padding(start = 14.dp, end = 4.dp).height(42.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Headphones, null, tint = com.daoyu.tingshu.ui.Palette.Gold, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (loading) "合成中…" else engine, color = theme.text, fontSize = 13.sp)
        IconButton(onClick = onToggle) {
            Icon(if (playing) androidx.compose.material.icons.Icons.Default.Pause else androidx.compose.material.icons.Icons.Default.PlayArrow, "播放/暂停", tint = theme.text)
        }
        IconButton(onClick = onStop) { Icon(androidx.compose.material.icons.Icons.Default.Close, "停止", tint = theme.secondary, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun BottomMenu(
    theme: ReaderTheme, chapterIdx: Int, chapterCount: Int, chapterTitle: String, nightOn: Boolean,
    onJump: (Int) -> Unit, onToc: () -> Unit, onListen: () -> Unit, onNight: () -> Unit, onStyle: () -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Surface(color = theme.bg, shadowElevation = 6.dp, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                if (dragging != null) "第 ${dragging!!.roundToInt() + 1} / $chapterCount 章" else chapterTitle,
                color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (chapterIdx > 0) onJump(chapterIdx - 1) }) { Text("上一章", color = theme.text, fontSize = 13.sp) }
                Slider(
                    value = dragging ?: chapterIdx.toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { onJump(it.roundToInt()) }; dragging = null },
                    valueRange = 0f..(chapterCount - 1).coerceAtLeast(1).toFloat(),
                    colors = SliderDefaults.colors(thumbColor = theme.text, activeTrackColor = theme.text.copy(alpha = 0.7f), inactiveTrackColor = theme.secondary.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { if (chapterIdx < chapterCount - 1) onJump(chapterIdx + 1) }) { Text("下一章", color = theme.text, fontSize = 13.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
                MenuItem(Icons.AutoMirrored.Filled.List, "目录", theme, onToc)
                MenuItem(Icons.Default.Headphones, "听书", theme, onListen)
                MenuItem(if (nightOn) Icons.Default.LightMode else Icons.Default.DarkMode, if (nightOn) "日间" else "夜间", theme, onNight)
                MenuItem(Icons.Default.TextFields, "设置", theme, onStyle)
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, theme: ReaderTheme, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = theme.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = theme.text, fontSize = 11.sp)
    }
}

@Composable
private fun TocPanel(vm: ReaderViewModel, theme: ReaderTheme, current: Int, onPick: (Int, Int) -> Unit) {
    val chapters by vm.chapters.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState(initial = emptyList())
    val book by vm.book.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
    Surface(color = theme.bg, modifier = Modifier.fillMaxHeight().fillMaxWidth(0.84f), shadowElevation = 8.dp) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(book?.title ?: "", color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!book?.author.isNullOrEmpty()) Text(book!!.author, color = theme.secondary, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row {
                    listOf("目录 ${chapters.size}", "书签 ${bookmarks.size}").forEachIndexed { i, t ->
                        Text(
                            t, color = if (tab == i) theme.text else theme.secondary, fontSize = 14.sp,
                            fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.clickable { tab = i }.padding(end = 20.dp, top = 4.dp, bottom = 4.dp)
                        )
                    }
                }
            }
            HorizontalDivider(color = theme.secondary.copy(alpha = 0.2f))
            if (tab == 0) {
                LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(chapters, key = { _, c -> c.idx }) { i, c ->
                        Text(
                            c.title, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (i == current) com.daoyu.tingshu.ui.Palette.Gold else theme.text,
                            fontWeight = if (i == current) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(i, 0) }.padding(horizontal = 20.dp, vertical = 13.dp)
                        )
                    }
                }
            } else {
                if (bookmarks.isEmpty()) Text("还没有书签。阅读时点顶部书签按钮，或长按正文添加。", color = theme.secondary, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
                LazyColumn {
                    itemsIndexed(bookmarks, key = { _, b -> b.id }) { _, b ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(b.chapter, b.offset) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(b.chapterTitle, color = theme.secondary, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                Text("删除", color = theme.secondary, fontSize = 12.sp, modifier = Modifier.clickable { vm.deleteBookmark(b) }.padding(4.dp))
                            }
                            Text(b.snippet, color = theme.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StylePanel(s: ReaderSettings, theme: ReaderTheme, onChange: ((ReaderSettings) -> ReaderSettings) -> Unit, onMore: () -> Unit) {
    var brightness by remember(s.brightness) { mutableFloatStateOf(if (s.brightness < 0) 0.5f else s.brightness) }
    Surface(color = theme.bg, shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // brightness
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LightMode, null, tint = theme.secondary, modifier = Modifier.size(18.dp))
                Slider(
                    value = brightness, onValueChange = { brightness = it; onChange { st -> st.copy(brightness = it) } },
                    colors = SliderDefaults.colors(thumbColor = theme.text, activeTrackColor = theme.text.copy(alpha = 0.7f), inactiveTrackColor = theme.secondary.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Chip("系统", s.brightness < 0, theme) { onChange { it.copy(brightness = if (it.brightness < 0) brightness else -1f) } }
            }
            // font size
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("字号", theme)
                Chip("A-", false, theme) { onChange { it.copy(fontSizeSp = (it.fontSizeSp - 1).coerceAtLeast(12f)) } }
                Text("${s.fontSizeSp.roundToInt()}", color = theme.text, fontSize = 15.sp, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Chip("A+", false, theme) { onChange { it.copy(fontSizeSp = (it.fontSizeSp + 1).coerceAtMost(36f)) } }
                Spacer(Modifier.weight(1f))
                Chip("加粗", s.boldText, theme) { onChange { it.copy(boldText = !it.boldText) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("字体", theme)
                Fonts.choices.filter { it.first != "custom" || s.customFontPath != null }.forEach { (k, n) ->
                    Chip(n, s.font == k, theme) { onChange { it.copy(font = k) } }; Spacer(Modifier.width(8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("行距", theme)
                listOf(1.5f to "紧凑", 1.75f to "适中", 2.05f to "宽松").forEach { (v, n) ->
                    Chip(n, kotlin.math.abs(s.lineSpacing - v) < 0.01f, theme) { onChange { it.copy(lineSpacing = v) } }; Spacer(Modifier.width(8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("边距", theme)
                listOf(16f to "窄", 26f to "中", 38f to "宽").forEach { (v, n) ->
                    Chip(n, kotlin.math.abs(s.marginH - v) < 0.1f, theme) { onChange { it.copy(marginH = v) } }; Spacer(Modifier.width(8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("翻页", theme)
                listOf("slide" to "平移", "cover" to "覆盖", "none" to "无动画").forEach { (v, n) ->
                    Chip(n, s.pageAnim == v, theme) { onChange { it.copy(pageAnim = v) } }; Spacer(Modifier.width(8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("背景", theme)
                ReaderThemes.forEachIndexed { i, t ->
                    Box(
                        Modifier.padding(end = 10.dp).size(32.dp).clip(CircleShape).background(t.bg)
                            .border(if (s.theme == i) 2.dp else 0.8.dp, if (s.theme == i) com.daoyu.tingshu.ui.Palette.Gold else theme.secondary.copy(alpha = 0.4f), CircleShape)
                            .clickable { onChange { it.copy(theme = i, followSystemDark = if (t.dark) false else it.followSystemDark) } }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip("音量键翻页", s.volumeKeyTurn, theme) { onChange { it.copy(volumeKeyTurn = !it.volumeKeyTurn) } }
                Spacer(Modifier.width(8.dp))
                Chip("首行缩进", s.indent, theme) { onChange { it.copy(indent = !it.indent) } }
                Spacer(Modifier.width(8.dp))
                Chip("跟随系统深色", s.followSystemDark, theme) { onChange { it.copy(followSystemDark = !it.followSystemDark) } }
            }
            Text("更多设置 ›", color = theme.secondary, fontSize = 13.sp, modifier = Modifier.align(Alignment.End).clickable(onClick = onMore).padding(4.dp))
        }
    }
}

@Composable
internal fun Label(t: String, theme: ReaderTheme) {
    Text(t, color = theme.secondary, fontSize = 13.sp, modifier = Modifier.width(44.dp))
}

@Composable
internal fun Chip(text: String, selected: Boolean, theme: ReaderTheme, onClick: () -> Unit) {
    val sel = com.daoyu.tingshu.ui.Palette.Gold
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) sel.copy(alpha = 0.14f) else theme.text.copy(alpha = 0.05f))
            .border(0.8.dp, if (selected) sel else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = if (selected) sel else theme.text, fontSize = 13.sp)
    }
}

@Composable
private fun LongPressDialog(
    theme: ReaderTheme, speaker: String?, canResynth: Boolean,
    onListen: () -> Unit, onBookmark: () -> Unit, onChangeSpeaker: () -> Unit, onResynth: () -> Unit, onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(color = theme.bg, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.width(260.dp).padding(vertical = 8.dp)) {
                @Composable fun item(t: String, on: () -> Unit) =
                    Text(t, color = theme.text, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = on).padding(horizontal = 20.dp, vertical = 14.dp))
                item("从这里开始听书", onListen)
                if (speaker != null) item("改说话人（当前：$speaker）", onChangeSpeaker)
                if (canResynth) item("这一句读得不对，重新合成", onResynth)
                item("在本页添加书签", onBookmark)
            }
        }
    }
}

@Composable
private fun SpeakerDialog(theme: ReaderTheme, current: String, names: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(color = theme.bg, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.width(280.dp).padding(vertical = 12.dp)) {
                Text("这句话是谁说的？", color = theme.secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items((names + listOf(com.daoyu.tingshu.drama.Roles.PASSERBY, com.daoyu.tingshu.drama.Roles.PASSERBY_F, com.daoyu.tingshu.drama.Roles.NARRATOR)).distinct()) { n ->
                        Text(
                            if (n == com.daoyu.tingshu.drama.Roles.NARRATOR) "旁白（不是对白）" else n,
                            color = if (n == current) Palette.Gold else theme.text, fontSize = 15.sp,
                            fontWeight = if (n == current) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(n) }.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
