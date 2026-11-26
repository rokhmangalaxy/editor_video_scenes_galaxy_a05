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

/** 10 pilihan tema warna aplikasi. */
enum class AppTheme(val id: String, val label: String, val desc: String) {
    MEDIUM_MAROON(
        id = "medium_maroon",
        label = "Medium Maroon",
        desc = "Maroon medium hangat & elegan"
    ),
    DARK(
        id = "dark",
        label = "Gelap Standar",
        desc = "Klasik gelap dengan aksen koral"
    ),
    MIDNIGHT_OCEAN(
        id = "midnight_ocean",
        label = "Midnight Ocean",
        desc = "Biru samudra malam & langit cerah"
    ),
    EMERALD_FOREST(
        id = "emerald_forest",
        label = "Emerald Forest",
        desc = "Hijau zamrud hutan tenang"
    ),
    ROYAL_PURPLE(
        id = "royal_purple",
        label = "Royal Purple",
        desc = "Ungu kerajaan modern & kontras"
    ),
    SUNSET_AMBER(
        id = "sunset_amber",
        label = "Sunset Amber",
        desc = "Jingga senja hangat keemasan"
    ),
    ROSE_GOLD(
        id = "rose_gold",
        label = "Rose Gold",
        desc = "Merah muda mawar lembut mewah"
    ),
    CYBER_TEAL(
        id = "cyber_teal",
        label = "Cyber Teal",
        desc = "Toska futuristik tajam & bersih"
    ),
    CRIMSON_GOLD(
        id = "crimson_gold",
        label = "Crimson Gold",
        desc = "Merah tua pekat berpadu emas"
    ),
    SLATE_MOCHA(
        id = "slate_mocha",
        label = "Slate Mocha",
        desc = "Cokelat moka hangat minimalis"
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

private val MediumMaroonPalette = EvPalette(
    bg = Color(0xFF1D0C13),
    panel = Color(0xFF2B121D),
    text = Color(0xFFFAF0F4),
    muted = Color(0xFFC79EAE),
    accent = Color(0xFFB03060),
    yellow = Color(0xFFFFD86B),
    line = Color(0xFF4A2033),
    ghost = Color(0xFF3E192A),
    toolIcon = Color(0xFFF7E4EC),
    infoText = Color(0xFFE5C3D1),
    scrim = Color(0xA6100408),
    segBg = 0xFF462030.toInt(),
    fzBg = 0xFF3A2846.toInt()
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

private val MidnightOceanPalette = EvPalette(
    bg = Color(0xFF0B1522),
    panel = Color(0xFF122236),
    text = Color(0xFFEFF6FF),
    muted = Color(0xFF8CA8C7),
    accent = Color(0xFF0EA5E9),
    yellow = Color(0xFFFACC15),
    line = Color(0xFF1E3754),
    ghost = Color(0xFF182D47),
    toolIcon = Color(0xFFDCEBFA),
    infoText = Color(0xFFB8D0E8),
    scrim = Color(0xA6040A12),
    segBg = 0xFF1B3552.toInt(),
    fzBg = 0xFF1F425C.toInt()
)

private val EmeraldForestPalette = EvPalette(
    bg = Color(0xFF0B1913),
    panel = Color(0xFF12281F),
    text = Color(0xFFECFDF5),
    muted = Color(0xFF8EB8A5),
    accent = Color(0xFF10B981),
    yellow = Color(0xFFFDE047),
    line = Color(0xFF1F4435),
    ghost = Color(0xFF19382B),
    toolIcon = Color(0xFFD9F7E9),
    infoText = Color(0xFFB7DEC9),
    scrim = Color(0xA6040D09),
    segBg = 0xFF1D4031.toInt(),
    fzBg = 0xFF1E3F42.toInt()
)

private val RoyalPurplePalette = EvPalette(
    bg = Color(0xFF130E22),
    panel = Color(0xFF1D1533),
    text = Color(0xFFF5F3FF),
    muted = Color(0xFFA79BC8),
    accent = Color(0xFF8B5CF6),
    yellow = Color(0xFFFBBF24),
    line = Color(0xFF342759),
    ghost = Color(0xFF2A1F47),
    toolIcon = Color(0xFFE9E3FC),
    infoText = Color(0xFFCCC2EB),
    scrim = Color(0xA6080512),
    segBg = 0xFF312454.toInt(),
    fzBg = 0xFF29325C.toInt()
)

private val SunsetAmberPalette = EvPalette(
    bg = Color(0xFF1B110B),
    panel = Color(0xFF281911),
    text = Color(0xFFFFF7ED),
    muted = Color(0xFFC4A18A),
    accent = Color(0xFFF97316),
    yellow = Color(0xFFFDE047),
    line = Color(0xFF472D1F),
    ghost = Color(0xFF3B2419),
    toolIcon = Color(0xFFFCE8D8),
    infoText = Color(0xFFE6C8B3),
    scrim = Color(0xA60E0704),
    segBg = 0xFF452B1D.toInt(),
    fzBg = 0xFF3B3228.toInt()
)

private val RoseGoldPalette = EvPalette(
    bg = Color(0xFF1E1017),
    panel = Color(0xFF2C1822),
    text = Color(0xFFFDF2F8),
    muted = Color(0xFFC99EB3),
    accent = Color(0xFFEC4899),
    yellow = Color(0xFFFDE68A),
    line = Color(0xFF4D293B),
    ghost = Color(0xFF3F2130),
    toolIcon = Color(0xFFFAE3EE),
    infoText = Color(0xFFE8C2D5),
    scrim = Color(0xA610060B),
    segBg = 0xFF4A2739.toInt(),
    fzBg = 0xFF3D2A4A.toInt()
)

private val CyberTealPalette = EvPalette(
    bg = Color(0xFF09181A),
    panel = Color(0xFF102629),
    text = Color(0xFFF0FDFA),
    muted = Color(0xFF8AB8BC),
    accent = Color(0xFF14B8A6),
    yellow = Color(0xFFFACC15),
    line = Color(0xFF1D4347),
    ghost = Color(0xFF17363A),
    toolIcon = Color(0xFFD8F7F4),
    infoText = Color(0xFFB4DFE0),
    scrim = Color(0xA6030C0D),
    segBg = 0xFF1B3F42.toInt(),
    fzBg = 0xFF1C384D.toInt()
)

private val CrimsonGoldPalette = EvPalette(
    bg = Color(0xFF1A0A0A),
    panel = Color(0xFF281111),
    text = Color(0xFFFEF2F2),
    muted = Color(0xFFC49595),
    accent = Color(0xFFDC2626),
    yellow = Color(0xFFFBBF24),
    line = Color(0xFF4A1F1F),
    ghost = Color(0xFF3B1818),
    toolIcon = Color(0xFFFCE2E2),
    infoText = Color(0xFFE6BDBD),
    scrim = Color(0xA60F0404),
    segBg = 0xFF471E1E.toInt(),
    fzBg = 0xFF3B2638.toInt()
)

private val SlateMochaPalette = EvPalette(
    bg = Color(0xFF171311),
    panel = Color(0xFF231D1A),
    text = Color(0xFFFAF6F2),
    muted = Color(0xFFB5A499),
    accent = Color(0xFFC08457),
    yellow = Color(0xFFFCD34D),
    line = Color(0xFF3D332D),
    ghost = Color(0xFF332A25),
    toolIcon = Color(0xFFF2EAE4),
    infoText = Color(0xFFD9CAC0),
    scrim = Color(0xA60A0807),
    segBg = 0xFF3B312B.toInt(),
    fzBg = 0xFF2E3640.toInt()
)

fun paletteFor(theme: AppTheme): EvPalette = when (theme) {
    AppTheme.MEDIUM_MAROON -> MediumMaroonPalette
    AppTheme.DARK -> DarkPalette
    AppTheme.MIDNIGHT_OCEAN -> MidnightOceanPalette
    AppTheme.EMERALD_FOREST -> EmeraldForestPalette
    AppTheme.ROYAL_PURPLE -> RoyalPurplePalette
    AppTheme.SUNSET_AMBER -> SunsetAmberPalette
    AppTheme.ROSE_GOLD -> RoseGoldPalette
    AppTheme.CYBER_TEAL -> CyberTealPalette
    AppTheme.CRIMSON_GOLD -> CrimsonGoldPalette
    AppTheme.SLATE_MOCHA -> SlateMochaPalette
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
