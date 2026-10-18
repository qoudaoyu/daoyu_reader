package com.daoyu.tingshu.reader

import android.graphics.Paint
import android.os.BatteryManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoyu.tingshu.ui.ReaderTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class PageInsets(val left: Dp, val top: Dp, val right: Dp, val bottom: Dp)

/** Listening highlight: chapter + body range. */
data class Highlight(val chapter: Int, val start: Int, val end: Int)

@Composable
fun PageContent(
    ref: PageRef?,
    theme: ReaderTheme,
    insets: PageInsets,
    highlight: Highlight?,
    progress: Float,
    showInfo: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hlPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val hl = highlight?.takeIf { ref != null && it.chapter == ref.page.chapter && it.end > ref.page.startBody && it.start < ref.page.endBody }
    val path = remember(ref?.key, hl) { if (ref != null && hl != null) ref.layout.highlightPath(hl.start, hl.end) else null }

    Box(modifier.fillMaxSize().background(theme.bg)) {
        if (ref != null) {
            Canvas(Modifier.fillMaxSize()) {
                val left = with(density) { insets.left.toPx() }
                val top = with(density) { insets.top.toPx() }
                val layout = ref.layout.layout
                val p = ref.page
                val lineTop = layout.getLineTop(p.startLine).toFloat()
                val lineBottom = layout.getLineBottom(p.endLine - 1).toFloat()
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.translate(left, top - lineTop)
                    nc.clipRect(-8f, lineTop, layout.width + 8f, lineBottom)
                    if (path != null) {
                        hlPaint.color = theme.text.copy(alpha = if (theme.dark) 0.16f else 0.09f).toArgb()
                        nc.drawPath(path, hlPaint)
                    }
                    layout.paint.color = theme.text.toArgb()
                    layout.draw(nc)
                    nc.restore()
                }
            }
            if (showInfo) {
                // header: chapter title (skip on first page where the big title is shown)
                if (ref.page.index > 0) {
                    Text(
                        ref.layout.title, color = theme.secondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = insets.left, end = insets.right, top = insets.top - 38.dp)
                    )
                }
                InfoFooter(ref, theme, progress, Modifier.align(Alignment.BottomStart).padding(start = insets.left, end = insets.right, bottom = insets.bottom - 30.dp))
            }
        }
    }
}

@Composable
private fun InfoFooter(ref: PageRef, theme: ReaderTheme, progress: Float, modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    var battery by remember { mutableStateOf(100) }
    LaunchedEffect(Unit) {
        val bm = context.getSystemService(BatteryManager::class.java)
        while (true) {
            now = Date()
            battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            delay(30_000)
        }
    }
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(fmt.format(now), color = theme.secondary, fontSize = 11.sp)
        Spacer(Modifier.width(10.dp))
        BatteryGlyph(battery, theme.secondary)
        Spacer(Modifier.weight(1f))
        Text("${ref.page.index + 1}/${ref.layout.pages.size}", color = theme.secondary, fontSize = 11.sp)
        Spacer(Modifier.width(12.dp))
        Text(String.format(Locale.US, "%.1f%%", progress * 100), color = theme.secondary, fontSize = 11.sp)
    }
}

@Composable
private fun BatteryGlyph(level: Int, color: Color) {
    Canvas(Modifier.width(20.dp).padding(vertical = 0.dp)) {
        val w = 18.dp.toPx(); val h = 9.dp.toPx(); val y = (size.height - h) / 2
        val stroke = 1.dp.toPx()
        drawRect(color, topLeft = Offset(0f, y), size = androidx.compose.ui.geometry.Size(w, h), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawRect(color, topLeft = Offset(w, y + h * 0.3f), size = androidx.compose.ui.geometry.Size(1.6.dp.toPx(), h * 0.4f))
        val inner = (w - stroke * 4) * (level.coerceIn(0, 100) / 100f)
        drawRect(color, topLeft = Offset(stroke * 2, y + stroke * 2), size = androidx.compose.ui.geometry.Size(inner, h - stroke * 4))
    }
}

/** Handle for driving page turns from outside (volume keys, listening). */
class TurnController {
    internal var turn: (suspend (Boolean) -> Unit)? = null
    suspend fun animateTurn(forward: Boolean) { turn?.invoke(forward) }
}

/**
 * Hosts current/prev/next pages and performs the page-turn gesture + animation.
 * anim: "slide" | "cover" | "none"
 */
@Composable
fun PageTurner(
    ui: ReaderUi,
    anim: String,
    theme: ReaderTheme,
    controller: TurnController,
    onTurn: (Boolean) -> Boolean,
    onCenterTap: () -> Unit,
    onLongPress: (Offset) -> Unit,
    page: @Composable (PageRef?, Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(theme.bg)) {
        val widthPx = constraints.maxWidth.toFloat()
        val offset = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val slop = LocalViewConfiguration.current.touchSlop
        val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis

        suspend fun finish(forward: Boolean) {
            if (anim == "none") {
                onTurn(forward); offset.snapTo(0f); return
            }
            offset.animateTo(if (forward) -widthPx else widthPx, tween(260))
            onTurn(forward)
            offset.snapTo(0f)
        }

        suspend fun programmatic(forward: Boolean) {
            val target = if (forward) ui.next else ui.prev
            if (target == null || anim == "none") { onTurn(forward); return }
            finish(forward)
        }
        controller.turn = { fwd -> programmatic(fwd) }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(anim, ui.prev != null, ui.next != null, widthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val vt = VelocityTracker()
                        vt.addPosition(down.uptimeMillis, down.position)
                        var dragging = false
                        var dx = 0f
                        var released = false
                        // phase 1: wait for slop / release / long press
                        val decided = withTimeoutOrNull(longPressMs) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                                vt.addPosition(ch.uptimeMillis, ch.position)
                                dx = ch.position.x - down.position.x
                                if (!ch.pressed) { released = true; return@withTimeoutOrNull true }
                                if (abs(dx) > slop && abs(dx) > abs(ch.position.y - down.position.y)) { dragging = true; ch.consume(); return@withTimeoutOrNull true }
                            }
                            @Suppress("UNREACHABLE_CODE") true
                        }
                        if (decided == null) {
                            // long press
                            onLongPress(down.position)
                            while (true) {
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { it.consume() }
                                if (ev.changes.none { it.pressed }) break
                            }
                            return@awaitEachGesture
                        }
                        if (released && !dragging) {
                            val x = down.position.x
                            when {
                                x < widthPx / 3f -> scope.launch { programmatic(false) }
                                x > widthPx * 2f / 3f -> scope.launch { programmatic(true) }
                                else -> onCenterTap()
                            }
                            return@awaitEachGesture
                        }
                        if (!dragging) return@awaitEachGesture
                        // phase 2: dragging
                        val forward = dx < 0
                        val allowed = if (forward) ui.next != null else ui.prev != null
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            vt.addPosition(ch.uptimeMillis, ch.position)
                            dx = ch.position.x - down.position.x
                            ch.consume()
                            if (allowed && anim != "none") {
                                val v = if (forward) dx.coerceIn(-widthPx, 0f) else dx.coerceIn(0f, widthPx)
                                scope.launch { offset.snapTo(v) }
                            }
                            if (!ch.pressed) break
                        }
                        val vel = vt.calculateVelocity().x
                        scope.launch {
                            if (!allowed) {
                                onTurn(forward) // lets the VM show "first/last page" hint
                                offset.animateTo(0f)
                                return@launch
                            }
                            val commit = abs(dx) > widthPx * 0.16f || (abs(vel) > 900 && (vel < 0) == forward)
                            if (commit) finish(forward) else offset.animateTo(0f, tween(180))
                        }
                    }
                }
        ) {
            val o = offset.value
            when (anim) {
                "cover" -> {
                    if (o < 0) {
                        page(ui.next, Modifier)
                        page(ui.cur, Modifier.offset { IntOffset(o.roundToInt(), 0) }.edgeShadow(true))
                    } else if (o > 0) {
                        page(ui.cur, Modifier)
                        page(ui.prev, Modifier.offset { IntOffset((o - widthPx).roundToInt(), 0) }.edgeShadow(true))
                    } else page(ui.cur, Modifier)
                }
                else -> {
                    if (o < 0) page(ui.next, Modifier.offset { IntOffset((o + widthPx).roundToInt(), 0) })
                    if (o > 0) page(ui.prev, Modifier.offset { IntOffset((o - widthPx).roundToInt(), 0) })
                    page(ui.cur, Modifier.offset { IntOffset(o.roundToInt(), 0) })
                }
            }
        }
    }
}

/** soft shadow on the right edge of a page that is sliding over another one */
private fun Modifier.edgeShadow(right: Boolean): Modifier = this.graphicsLayer { clip = false }.drawWithContent {
    drawContent()
    val w = 14.dp.toPx()
    drawRect(
        Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent), startX = size.width, endX = size.width + w),
        topLeft = Offset(size.width, 0f), size = androidx.compose.ui.geometry.Size(w, size.height)
    )
}
