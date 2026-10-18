package com.daoyu.tingshu.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File

object Palette {
    val Paper = Color(0xFFFBF8F3)
    val PaperDeep = Color(0xFFF3EEE5)
    val Ink = Color(0xFF26231F)
    val InkSoft = Color(0xFF6E675E)
    val InkFaint = Color(0xFFA59D92)
    val Accent = Color(0xFF3E6A8A)       // 缥色蓝
    val Gold = Color(0xFFC08A3E)         // 听书金
    val Line = Color(0xFFE7E1D7)
}

private val LightScheme = lightColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    secondary = Palette.Gold,
    background = Palette.Paper,
    onBackground = Palette.Ink,
    surface = Palette.Paper,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.PaperDeep,
    onSurfaceVariant = Palette.InkSoft,
    surfaceContainer = Color(0xFFF7F3EC),
    surfaceContainerHigh = Color(0xFFF3EEE6),
    surfaceContainerHighest = Color(0xFFEEE8DE),
    surfaceContainerLow = Color(0xFFFAF7F1),
    outline = Palette.InkFaint,
    outlineVariant = Palette.Line,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8DB3CF),
    onPrimary = Color(0xFF10212D),
    secondary = Color(0xFFD9AE6E),
    background = Color(0xFF151515),
    onBackground = Color(0xFFD8D3CB),
    surface = Color(0xFF151515),
    onSurface = Color(0xFFD8D3CB),
    surfaceVariant = Color(0xFF232221),
    onSurfaceVariant = Color(0xFF9E978D),
    surfaceContainer = Color(0xFF1D1C1B),
    surfaceContainerHigh = Color(0xFF242322),
    surfaceContainerHighest = Color(0xFF2B2A28),
    surfaceContainerLow = Color(0xFF191818),
    outline = Color(0xFF6F6A62),
    outlineVariant = Color(0xFF2E2C2A),
)

@Composable
fun DaoyuTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = base.copy(
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}

/** Reading page themes, modelled after paper tones. */
data class ReaderTheme(val name: String, val bg: Color, val text: Color, val secondary: Color, val dark: Boolean = false)

val ReaderThemes = listOf(
    ReaderTheme("素白", Color(0xFFFFFFFF), Color(0xFF262626), Color(0xFFA0A0A0)),
    ReaderTheme("米黄", Color(0xFFF6F0E3), Color(0xFF3A3125), Color(0xFFA89C88)),
    ReaderTheme("豆绿", Color(0xFFE2ECDC), Color(0xFF2A3726), Color(0xFF8D9E87)),
    ReaderTheme("淡蓝", Color(0xFFE6EDF2), Color(0xFF263039), Color(0xFF8E9BA6)),
    ReaderTheme("深夜", Color(0xFF1B1B1D), Color(0xFF9C9A96), Color(0xFF5E5C59), dark = true),
    ReaderTheme("纯黑", Color(0xFF000000), Color(0xFF7F7D79), Color(0xFF4A4946), dark = true),
)
const val NIGHT_THEME = 4

object Fonts {
    private val cache = HashMap<String, Typeface>()

    val choices = listOf("wenkai" to "霞鹜文楷", "serif" to "思源宋体", "system" to "系统黑体", "custom" to "自定义")

    fun typeface(context: Context, key: String, customPath: String?): Typeface = cache.getOrPut(key + (customPath ?: "")) {
        try {
            when (key) {
                "wenkai" -> Typeface.createFromAsset(context.assets, "fonts/LXGWWenKaiGB.ttf")
                "serif" -> Typeface.createFromAsset(context.assets, "fonts/NotoSerifSC.ttf")
                "custom" -> customPath?.let { File(it) }?.takeIf { it.exists() }?.let { Typeface.createFromFile(it) } ?: Typeface.DEFAULT
                else -> Typeface.DEFAULT
            }
        } catch (_: Exception) {
            Typeface.DEFAULT
        }
    }

    fun family(context: Context, key: String): FontFamily =
        FontFamily(androidx.compose.ui.text.font.Typeface(typeface(context, key, null)))
}

val SerifTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
