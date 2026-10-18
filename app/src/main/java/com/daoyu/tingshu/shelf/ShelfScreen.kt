package com.daoyu.tingshu.shelf

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoyu.tingshu.App
import com.daoyu.tingshu.data.Book
import com.daoyu.tingshu.data.BookImporter
import com.daoyu.tingshu.tts.Engines
import com.daoyu.tingshu.ui.Fonts
import com.daoyu.tingshu.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(onOpen: (Long) -> Unit, onTransfer: () -> Unit, onSettings: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by app.db.books().observeShelf().collectAsState(initial = null)
    val tts by app.tts.state.collectAsState()
    var importing by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<Book?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing += uris.size
            for (u in uris) {
                val name = displayName(context, u)
                val res = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(u)?.let { app.importer.import(name, it) } ?: BookImporter.Result.Fail("无法读取文件")
                    } catch (e: Exception) { BookImporter.Result.Fail(e.message ?: "导入失败") }
                }
                importing--
                val msg = when (res) {
                    is BookImporter.Result.Ok -> if (res.duplicate) "《${res.book.title}》已在书架" else "已导入《${res.book.title}》"
                    is BookImporter.Result.Fail -> res.message
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // header
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("书架", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, fontFamily = remember { Fonts.family(context, "serif") }, letterSpacing = 2.sp)
                Spacer(Modifier.width(10.dp))
                books?.let { Text("${it.size} 本", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onTransfer) { Icon(Icons.Default.Wifi, "WiFi 传书") }
                IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "导入") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") }
            }
            if (importing > 0) Row(Modifier.padding(horizontal = 22.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在导入 $importing 本…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val list = books
            when {
                list == null -> {}
                list.isEmpty() -> EmptyShelf(onTransfer) { picker.launch(arrayOf("*/*")) }
                else -> {
                    val recent = list.firstOrNull { it.lastReadAt > 0 }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(100.dp),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 110.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (recent != null) item(span = { GridItemSpan(maxLineSpan) }) {
                            ContinueCard(recent, listening = tts.active && tts.bookId == recent.id, playing = tts.playing, onOpen = { onOpen(recent.id) },
                                onListen = {
                                    if (tts.active && tts.bookId == recent.id) app.tts.togglePause()
                                    else app.tts.start(recent, recent.readChapter, recent.readOffset)
                                })
                        }
                        items(list, key = { it.id }) { b ->
                            Column(Modifier.combinedClickable(onClick = { onOpen(b.id) }, onLongClick = { detail = b })) {
                                Cover(b, Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                Text(b.title, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                                Text(
                                    when { b.progress >= 0.995f -> "已读完"; b.lastReadAt == 0L -> "未读"; else -> "读到 ${(b.progress * 100).roundToInt().coerceAtLeast(1)}%" },
                                    fontSize = 11.5.sp, color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }

        if (tts.active) {
            Surface(
                shape = RoundedCornerShape(26.dp), shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp, start = 20.dp, end = 20.dp)
                    .clickable { onOpen(tts.bookId) }
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Headphones, null, tint = Palette.Gold, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f, fill = false).width(220.dp)) {
                        Text(tts.chapterTitle.ifEmpty { tts.bookTitle }, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${tts.bookTitle} · ${Engines.label(tts.engine)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { app.tts.togglePause() }) { Icon(if (tts.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放/暂停") }
                    IconButton(onClick = { app.tts.stop() }) { Icon(Icons.Default.Close, "停止", modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }

    detail?.let { b ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(b.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (b.author.isNotEmpty()) Text("作者：${b.author}")
                    Text("格式：${b.format.uppercase()} · ${formatSize(b.fileSize)}")
                    Text("章节：${b.chapterCount} 章 · 约 ${formatWords(b.totalChars)}")
                    Text("文件：${b.sourceName}", color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                    b.ttsEngine?.let { Text("固定听书引擎：${Engines.label(it)}", fontSize = 13.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { onOpen(b.id); detail = null }) { Text("打开") } },
            dismissButton = {
                TextButton(onClick = {
                    if (tts.bookId == b.id) app.tts.stop()
                    scope.launch { app.importer.delete(b.id) }; detail = null
                }) { Text("从书架删除", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}

@Composable
private fun ContinueCard(b: Book, listening: Boolean, playing: Boolean, onOpen: () -> Unit, onListen: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(b, Modifier.width(58.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("继续阅读", fontSize = 11.5.sp, color = Palette.Gold, fontWeight = FontWeight.Medium)
                Text(b.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("已读 ${(b.progress * 100).roundToInt()}% · 共 ${b.chapterCount} 章", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
            Button(
                onClick = onListen,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Gold),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(if (listening && playing) Icons.Default.Pause else Icons.Default.Headphones, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (listening && playing) "暂停" else "听书", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun EmptyShelf(onTransfer: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("书架空空如也", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("用电脑通过 WiFi 传书，或从手机里导入 TXT / EPUB", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 14.sp)
        Spacer(Modifier.height(26.dp))
        Button(onClick = onTransfer, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent), modifier = Modifier.width(200.dp)) {
            Icon(Icons.Default.Wifi, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("WiFi 传书")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onImport, modifier = Modifier.width(200.dp)) { Text("从手机导入") }
    }
}

private val coverPalettes = listOf(
    listOf(Color(0xFF3E6A8A), Color(0xFF2C4C63)),
    listOf(Color(0xFF8A5A3E), Color(0xFF65412C)),
    listOf(Color(0xFF4F7A52), Color(0xFF38583A)),
    listOf(Color(0xFF7A4F6E), Color(0xFF583950)),
    listOf(Color(0xFF6B6A3E), Color(0xFF4D4C2B)),
    listOf(Color(0xFF3E5E7A), Color(0xFF2A4256)),
    listOf(Color(0xFF8A3E4A), Color(0xFF632C35)),
)

@Composable
fun Cover(b: Book, modifier: Modifier) {
    val context = LocalContext.current
    val img by produceState<ImageBitmap?>(null, b.coverPath) {
        value = withContext(Dispatchers.IO) { b.coverPath?.let { p -> if (File(p).exists()) BitmapFactory.decodeFile(p)?.asImageBitmap() else null } }
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier.aspectRatio(0.7f).shadow(3.dp, shape).clip(shape)
            .border(0.5.dp, Color.Black.copy(alpha = 0.06f), shape)
    ) {
        if (img != null) {
            Image(img!!, b.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val pal = coverPalettes[abs(b.title.hashCode()) % coverPalettes.size]
            val serif = remember { Fonts.family(context, "serif") }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(pal))) {
                val small = maxWidth < 80.dp
                // spine line, like a cloth-bound book
                Box(Modifier.padding(start = 6.dp).fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = 0.12f)))
                Column(Modifier.fillMaxSize().padding(horizontal = if (small) 5.dp else 10.dp, vertical = if (small) 8.dp else 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.weight(0.6f))
                    Text(
                        b.title, color = Color(0xFFF6EFE2), fontFamily = serif, fontSize = if (small) 10.sp else 14.sp, lineHeight = if (small) 13.sp else 19.sp,
                        textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(18.dp).height(1.dp).background(Color(0xFFE0B872)))
                    Spacer(Modifier.weight(1f))
                    if (b.author.isNotEmpty() && !small) Text(b.author, color = Color(0xFFF6EFE2).copy(alpha = 0.75f), fontSize = 9.5.sp, maxLines = 1)
                }
            }
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.let { return it }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "book.txt"
}

fun formatSize(n: Long) = if (n > 1048576) String.format("%.1f MB", n / 1048576.0) else "${(n / 1024).coerceAtLeast(1)} KB"
fun formatWords(n: Long) = if (n >= 10000) String.format("%.1f 万字", n / 10000.0) else "$n 字"
