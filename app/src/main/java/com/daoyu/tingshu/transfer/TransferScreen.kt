package com.daoyu.tingshu.transfer

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoyu.tingshu.ui.Palette
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var server by remember { mutableStateOf<TransferServer?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<TransferEvent>() }

    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val s = TransferServer.startAny(context)
        server = s
        val ip = TransferServer.lanAddress()
        if (s == null || ip == null) failed = true else url = "http://$ip:${s.listeningPort}"
        onDispose {
            s?.stop()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(server) { server?.events?.collect { log.add(0, it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi 传书") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            if (failed) {
                Text("没有检测到 WiFi 连接", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("请让手机连上 WiFi（与电脑同一个网络）后再打开本页。", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                return@Column
            }
            Text("在电脑浏览器中打开", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(url ?: "启动中…", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent, modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp))
            }
            Spacer(Modifier.height(22.dp))
            url?.let { u ->
                val qr = remember(u) { qrBitmap(u, 520) }
                Image(qr.asImageBitmap(), "二维码", modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.height(8.dp))
                Text("也可以用平板等设备扫码打开", color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
            }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Step("1", "手机和电脑连接同一个 WiFi")
                Step("2", "在电脑上打开上面的地址，把 TXT / EPUB 拖进网页")
                Step("3", "传输期间请保持本页面打开，书会自动出现在书架")
            }
            Spacer(Modifier.height(18.dp))
            if (log.isNotEmpty()) {
                Text("本次已接收", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(log) { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (e.ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null, tint = if (e.ok) Palette.Gold else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(e.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(e.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(n, color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.size(20.dp).clip(CircleShape).background(Palette.Gold).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF26231F.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
