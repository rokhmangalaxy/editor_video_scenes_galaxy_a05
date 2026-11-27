package com.pribadi.editorvideo.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pribadi.editorvideo.R

/** 10 pilihan tema warna aplikasi (masing-masing punya versi gelap & terang). */
enum class AppTheme(val id: String, val label: String, val desc: String, val isLight: Boolean) {
    MEDIUM_MAROON(
        id = "medium_maroon",
        label = "Medium Maroon",
        desc = "Maroon medium hangat & elegan",
        isLight = false
    ),
    MEDIUM_MAROON_LIGHT(
        id = "medium_maroon_light",
        label = "Medium Maroon Terang",
        desc = "Maroon medium hangat versi terang",
        isLight = true
    ),
    DARK(
        id = "dark",
        label = "Gelap Standar",
        desc = "Klasik gelap dengan aksen koral",
        isLight = false
    ),
    DARK_LIGHT(
        id = "dark_light",
        label = "Terang Standar",
        desc = "Klasik terang dengan aksen koral",
        isLight = true
    ),
    MIDNIGHT_OCEAN(
        id = "midnight_ocean",
        label = "Midnight Ocean",
        desc = "Biru samudra malam & langit cerah",
        isLight = false
    ),
    MIDNIGHT_OCEAN_LIGHT(
        id = "midnight_ocean_light",
        label = "Ocean Morning",
        desc = "Biru samudra pagi yang cerah",
        isLight = true
    ),
    EMERALD_FOREST(
        id = "emerald_forest",
        label = "Emerald Forest",
        desc = "Hijau zamrud hutan tenang",
        isLight = false
    ),
    EMERALD_FOREST_LIGHT(
        id = "emerald_forest_light",
        label = "Emerald Meadow",
        desc = "Hijau zamrud padang senja",
        isLight = true
    ),
    ROYAL_PURPLE(
        id = "royal_purple",
        label = "Royal Purple",
        desc = "Ungu kerajaan modern & kontras",
        isLight = false
    ),
    ROYAL_PURPLE_LIGHT(
        id = "royal_purple_light",
        label = "Royal Lavender",
        desc = "Ungu lavender modern terang",
        isLight = true
    ),
    SUNSET_AMBER(
        id = "sunset_amber",
        label = "Sunset Amber",
        desc = "Jingga senja hangat keemasan",
        isLight = false
    ),
    SUNSET_AMBER_LIGHT(
        id = "sunset_amber_light",
        label = "Amber Day",
        desc = "Jingga hari hangat keemasan",
        isLight = true
    ),
    ROSE_GOLD(
        id = "rose_gold",
        label = "Rose Gold",
        desc = "Merah muda mawar lembut mewah",
        isLight = false
    ),
    ROSE_GOLD_LIGHT(
        id = "rose_gold_light",
        label = "Rose Dawn",
        desc = "Merah muda mawar pagi lembut",
        isLight = true
    ),
    CYBER_TEAL(
        id = "cyber_teal",
        label = "Cyber Teal",
        desc = "Toska futuristik tajam & bersih",
        isLight = false
    ),
    CYBER_TEAL_LIGHT(
        id = "cyber_teal_light",
        label = "Cyber Mint",
        desc = "Toska mint futuristik terang",
        isLight = true
    ),
    CRIMSON_GOLD(
        id = "crimson_gold",
        label = "Crimson Gold",
        desc = "Merah tua pekat berpadu emas",
        isLight = false
    ),
    CRIMSON_GOLD_LIGHT(
        id = "crimson_gold_light",
        label = "Crimson Blush",
        desc = "Merah muda pekat berpadu emas",
        isLight = true
    ),
    SLATE_MOCHA(
        id = "slate_mocha",
        label = "Slate Mocha",
        desc = "Cokelat moka hangat minimalis",
        isLight = false
    ),
    SLATE_MOCHA_LIGHT(
        id = "slate_mocha_light",
        label = "Mocha Cream",
        desc = "Cokelat moka hangat krim terang",
        isLight = true
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

// === Light Theme Palettes ===

private val MediumMaroonLightPalette = EvPalette(
    bg = Color(0xFFF8F5F6),
    panel = Color(0xFFEFE9EF),
    text = Color(0xFF2B121D),
    muted = Color(0xFF8A6B73),
    accent = Color(0xFFB03060),
    yellow = Color(0xFFC47A3A),
    line = Color(0xFFD4C4C9),
    ghost = Color(0xFFE8E0E4),
    toolIcon = Color(0xFF4A2530),
    infoText = Color(0xFF7A5A63),
    scrim = Color(0x99000000),
    segBg = 0xFFD4C4C9.toInt(),
    fzBg = 0xFFE8E0E4.toInt()
)

private val DarkLightPalette = EvPalette(
    bg = Color(0xFFF5F5F7),
    panel = Color(0xFFECECF0),
    text = Color(0xFF1B1B1E),
    muted = Color(0xFF6B6B74),
    accent = Color(0xFFFF3B5C),
    yellow = Color(0xFFFFC94D),
    line = Color(0xFFD5D5D8),
    ghost = Color(0xFFE8E8EC),
    toolIcon = Color(0xFF4A4A52),
    infoText = Color(0xFF8B8B94),
    scrim = Color(0x99000000),
    segBg = 0xFFD5D5D8.toInt(),
    fzBg = 0xFFE8E8EC.toInt()
)

private val MidnightOceanLightPalette = EvPalette(
    bg = Color(0xFFF0F5FA),
    panel = Color(0xFFE4EDF5),
    text = Color(0xFF122236),
    muted = Color(0xFF6E90AE),
    accent = Color(0xFF0EA5E9),
    yellow = Color(0xFFD4A83A),
    line = Color(0xFFC2D4E3),
    ghost = Color(0xFFE8F0F7),
    toolIcon = Color(0xFF2A4A6A),
    infoText = Color(0xFF8AAABE),
    scrim = Color(0x99000000),
    segBg = 0xFFC2D4E3.toInt(),
    fzBg = 0xFFE4EDF5.toInt()
)

private val EmeraldForestLightPalette = EvPalette(
    bg = Color(0xFFF0F5EF),
    panel = Color(0xFFE4F0E4),
    text = Color(0xFF12281F),
    muted = Color(0xFF6EA594),
    accent = Color(0xFF10B981),
    yellow = Color(0xFFC4B83A),
    line = Color(0xFFC2DED4),
    ghost = Color(0xFFE4F0E8),
    toolIcon = Color(0xFF2A4A3A),
    infoText = Color(0xFF8BB8A0),
    scrim = Color(0x99000000),
    segBg = 0xFFC2DED4.toInt(),
    fzBg = 0xFFE4F0E4.toInt()
)

private val RoyalPurpleLightPalette = EvPalette(
    bg = Color(0xFFF5F2FA),
    panel = Color(0xFFECDEF5),
    text = Color(0xFF1D1533),
    muted = Color(0xFF8778A8),
    accent = Color(0xFF8B5CF6),
    yellow = Color(0xFFFFB842),
    line = Color(0xFFD4CCE8),
    ghost = Color(0xFFE8E2F2),
    toolIcon = Color(0xFF443A6A),
    infoText = Color(0xFFA89CC0),
    scrim = Color(0x99000000),
    segBg = 0xFFD4CCE8.toInt(),
    fzBg = 0xFFECDEF5.toInt()
)

private val SunsetAmberLightPalette = EvPalette(
    bg = Color(0xFFFFF5EC),
    panel = Color(0xFFF0E8DC),
    text = Color(0xFF281911),
    muted = Color(0xFFA88466),
    accent = Color(0xFFF97316),
    yellow = Color(0xFFC4883A),
    line = Color(0xFFE4D0BC),
    ghost = Color(0xFFF4ECDF),
    toolIcon = Color(0xFF5A3A2A),
    infoText = Color(0xFFB89A78),
    scrim = Color(0x99000000),
    segBg = 0xFFE4D0BC.toInt(),
    fzBg = 0xFFF0E8DC.toInt()
)

private val RoseGoldLightPalette = EvPalette(
    bg = Color(0xFFF8F0F4),
    panel = Color(0xFFEDE4EC),
    text = Color(0xFF2C1822),
    muted = Color(0xFFA87E94),
    accent = Color(0xFFEC4899),
    yellow = Color(0xFFFFD08A),
    line = Color(0xFFD8C8D8),
    ghost = Color(0xFFF0E6EB),
    toolIcon = Color(0xFF5A3A4A),
    infoText = Color(0xFFB894A8),
    scrim = Color(0x99000000),
    segBg = 0xFFD8C8D8.toInt(),
    fzBg = 0xFFEDE4EC.toInt()
)

private val CyberTealLightPalette = EvPalette(
    bg = Color(0xFFF0F6F6),
    panel = Color(0xFFE4F0F0),
    text = Color(0xFF102629),
    muted = Color(0xFF6E9E9E),
    accent = Color(0xFF14B8A6),
    yellow = Color(0xFFC4D43A),
    line = Color(0xFFC2DEDE),
    ghost = Color(0xFFE4F2F2),
    toolIcon = Color(0xFF2A5A5A),
    infoText = Color(0xFF8CB8B8),
    scrim = Color(0x99000000),
    segBg = 0xFFC2DEDE.toInt(),
    fzBg = 0xFFE4F0F0.toInt()
)

private val CrimsonGoldLightPalette = EvPalette(
    bg = Color(0xFFFDF5F5),
    panel = Color(0xFFF0E4E4),
    text = Color(0xFF281111),
    muted = Color(0xFFA87E7E),
    accent = Color(0xFFDC2626),
    yellow = Color(0xFFFFB842),
    line = Color(0xFFE4C8C8),
    ghost = Color(0xFFF4ECE8),
    toolIcon = Color(0xFF5A2A2A),
    infoText = Color(0xFFB89494),
    scrim = Color(0x99000000),
    segBg = 0xFFE4C8C8.toInt(),
    fzBg = 0xFFF0E4E4.toInt()
)

private val SlateMochaLightPalette = EvPalette(
    bg = Color(0xFFF8F6F4),
    panel = Color(0xFFF0ECEA),
    text = Color(0xFF231D1A),
    muted = Color(0xFF9E8E7E),
    accent = Color(0xFFC08457),
    yellow = Color(0xFFC8A85A),
    line = Color(0xFFE0D8D0),
    ghost = Color(0xFFF0EAE4),
    toolIcon = Color(0xFF4A3A2E),
    infoText = Color(0xFFB0A090),
    scrim = Color(0x99000000),
    segBg = 0xFFE0D8D0.toInt(),
    fzBg = 0xFFF0ECEA.toInt()
)

fun paletteFor(theme: AppTheme): EvPalette = when (theme) {
    AppTheme.MEDIUM_MAROON -> MediumMaroonPalette
    AppTheme.MEDIUM_MAROON_LIGHT -> MediumMaroonLightPalette
    AppTheme.DARK -> DarkPalette
    AppTheme.DARK_LIGHT -> DarkLightPalette
    AppTheme.MIDNIGHT_OCEAN -> MidnightOceanPalette
    AppTheme.MIDNIGHT_OCEAN_LIGHT -> MidnightOceanLightPalette
    AppTheme.EMERALD_FOREST -> EmeraldForestPalette
    AppTheme.EMERALD_FOREST_LIGHT -> EmeraldForestLightPalette
    AppTheme.ROYAL_PURPLE -> RoyalPurplePalette
    AppTheme.ROYAL_PURPLE_LIGHT -> RoyalPurpleLightPalette
    AppTheme.SUNSET_AMBER -> SunsetAmberPalette
    AppTheme.SUNSET_AMBER_LIGHT -> SunsetAmberLightPalette
    AppTheme.ROSE_GOLD -> RoseGoldPalette
    AppTheme.ROSE_GOLD_LIGHT -> RoseGoldLightPalette
    AppTheme.CYBER_TEAL -> CyberTealPalette
    AppTheme.CYBER_TEAL_LIGHT -> CyberTealLightPalette
    AppTheme.CRIMSON_GOLD -> CrimsonGoldPalette
    AppTheme.CRIMSON_GOLD_LIGHT -> CrimsonGoldLightPalette
    AppTheme.SLATE_MOCHA -> SlateMochaPalette
    AppTheme.SLATE_MOCHA_LIGHT -> SlateMochaLightPalette
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

    /** Toggle antara tema gelap & terang dari keluarga warna yang sama. */
    fun toggleLightDark() {
        val current = activeTheme
        val baseId = current.id.replace("_light", "")
        val base = AppTheme.entries.firstOrNull { it.id == baseId && !it.isLight }
        val light = AppTheme.entries.firstOrNull { it.id == "${baseId}_light" }
        activeTheme = if (current.isLight) base ?: current else light ?: current
    }

    /** Set tema ke varian terang dari keluarga warna yang sama. */
    fun setLightVariant() {
        val baseId = activeTheme.id.replace("_light", "")
        val light = AppTheme.entries.firstOrNull { it.id == "${baseId}_light" }
        if (light != null) activeTheme = light
    }

    /** Set tema ke varian gelap dari keluarga warna yang sama. */
    fun setDarkVariant() {
        val baseId = activeTheme.id.replace("_light", "")
        val dark = AppTheme.entries.firstOrNull { it.id == baseId && !it.isLight }
        if (dark != null) activeTheme = dark
    }
}

@Composable
fun EditorTheme(theme: AppTheme = Ev.activeTheme, content: @Composable () -> Unit) {
    Ev.activeTheme = theme
    val p = paletteFor(theme)
    val colorScheme = if (theme.isLight) {
        androidx.compose.material3.lightColorScheme(
            primary = p.accent,
            onPrimary = Color.White,
            background = p.bg,
            onBackground = p.text,
            surface = p.panel,
            onSurface = p.text,
            surfaceVariant = p.line,
            onSurfaceVariant = p.muted
        )
    } else {
        androidx.compose.material3.darkColorScheme(
            primary = p.accent,
            onPrimary = Color.White,
            background = p.bg,
            onBackground = p.text,
            surface = p.panel,
            onSurface = p.text,
            surfaceVariant = p.line,
            onSurfaceVariant = p.muted
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
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

/** Tombol toggle gelap/terang untuk satu keluarga warna tema. */
@Composable
fun ThemeLightDarkToggle(
    currentTheme: AppTheme = Ev.activeTheme,
    onToggle: () -> Unit = { Ev.toggleLightDark() },
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Panel untuk tombol gelap
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (currentTheme.isLight) Ev.Ghost else Ev.Panel)
                .border(1.dp, if (currentTheme.isLight) Ev.Line else Ev.Accent, RoundedCornerShape(8.dp))
                .clickable { onToggle() }
                .padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🌙",
                color = if (currentTheme.isLight) Ev.Muted else Ev.Text,
                fontSize = 18.sp
            )
        }
        // Label status
        Text(
            text = if (currentTheme.isLight) "Gelap" else "Terang",
            color = Ev.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        // Panel untuk tombol terang
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (currentTheme.isLight) Ev.Accent else Ev.Ghost)
                .border(1.dp, if (currentTheme.isLight) Ev.Line else Ev.Accent, RoundedCornerShape(8.dp))
                .clickable { onToggle() }
                .padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "☀️",
                color = if (currentTheme.isLight) Ev.Text else Ev.Muted,
                fontSize = 18.sp
            )
        }
    }
}
