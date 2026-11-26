package com.pribadi.editorvideo.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Pilihan tema warna aplikasi. */
enum class AppTheme(val id: String, val label: String, val desc: String) {
    DARK(
        id = "dark",
        label = "Gelap Standar",
        desc = "Tema gelap klasik dengan aksen merah koral."
    ),
    MEDIUM_MAROON(
        id = "medium_maroon",
        label = "Medium Maroon",
        desc = "Nuansa maroon medium hangat dengan permukaan maroon gelap."
    );

    companion object {
        fun fromId(id: String?): AppTheme =
            entries.firstOrNull { it.id == id } ?: MEDIUM_MAROON
    }
}

/** Palet warna lengkap untuk satu tema. */
data class EvPalette(
    val bg: Color,
    val panel: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val yellow: Color,
    val line: Color,
    val ghost: Color,
    val toolIcon: Color,
    val infoText: Color,
    val scrim: Color,
    val segBg: Int,
    val fzBg: Int
)

private val DarkPalette = EvPalette(
    bg = Color(0xFF141416),
    panel = Color(0xFF1B1B1E),
    text = Color(0xFFF2F2F4),
    muted = Color(0xFF8D8D94),
    accent = Color(0xFFFF3B5C),
    yellow = Color(0xFFFFD60A),
    line = Color(0xFF2C2C30),
    ghost = Color(0xFF2C2C30),
    toolIcon = Color(0xFFE6E6EA),
    infoText = Color(0xFFB9B9C0),
    scrim = Color(0x99000000),
    segBg = 0xFF333333.toInt(),
    fzBg = 0xFF27394A.toInt()
)

private val MediumMaroonPalette = EvPalette(
    bg = Color(0xFF1D0C13),
    panel = Color(0xFF2B121D),
    text = Color(0xFFFAF0F4),
    muted = Color(0xFFC79EAE),
    accent = Color(0xFFB03060), // Medium Maroon
    yellow = Color(0xFFFFD86B),
    line = Color(0xFF4A2033),
    ghost = Color(0xFF3E192A),
    toolIcon = Color(0xFFF7E4EC),
    infoText = Color(0xFFE5C3D1),
    scrim = Color(0xA6100408),
    segBg = 0xFF462030.toInt(),
    fzBg = 0xFF3A2846.toInt()
)

fun paletteFor(theme: AppTheme): EvPalette = when (theme) {
    AppTheme.DARK -> DarkPalette
    AppTheme.MEDIUM_MAROON -> MediumMaroonPalette
}

/** Warna aktif aplikasi (reaktif terhadap perubahan tema). */
object Ev {
    var activeTheme by mutableStateOf(AppTheme.MEDIUM_MAROON)

    val current: EvPalette get() = paletteFor(activeTheme)

    val Bg: Color get() = current.bg
    val Panel: Color get() = current.panel
    val Text: Color get() = current.text
    val Muted: Color get() = current.muted
    val Accent: Color get() = current.accent
    val Yellow: Color get() = current.yellow
    val Line: Color get() = current.line
    val Ghost: Color get() = current.ghost
    val ToolIcon: Color get() = current.toolIcon
    val InfoText: Color get() = current.infoText
    val Scrim: Color get() = current.scrim
}

@Composable
fun EditorTheme(theme: AppTheme = Ev.activeTheme, content: @Composable () -> Unit) {
    Ev.activeTheme = theme
    val p = paletteFor(theme)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = p.accent,
            onPrimary = Color.White,
            background = p.bg,
            onBackground = p.text,
            surface = p.panel,
            onSurface = p.text,
            surfaceVariant = p.line,
            onSurfaceVariant = p.muted
        ),
        content = content
    )
}

/** Ikon dari drawable vektor, diberi warna dengan tint. */
@Composable
fun Ico(@DrawableRes id: Int, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(id = id),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size)
    )
}
