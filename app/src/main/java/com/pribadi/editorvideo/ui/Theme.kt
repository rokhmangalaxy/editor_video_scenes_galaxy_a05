package com.pribadi.editorvideo.ui

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

/** Warna dari editor_video_ui.html. */
object Ev {
    val Bg = Color(0xFF141416)
    val Panel = Color(0xFF1B1B1E)
    val Text = Color(0xFFF2F2F4)
    val Muted = Color(0xFF8D8D94)
    val Accent = Color(0xFFFF3B5C)
    val Yellow = Color(0xFFFFD60A)
    val Line = Color(0xFF2C2C30)
    val Ghost = Color(0xFF2C2C30)
    val ToolIcon = Color(0xFFE6E6EA)
    val InfoText = Color(0xFFB9B9C0)
    val Scrim = Color(0x99000000)
}

@Composable
fun EditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ev.Accent,
            onPrimary = Color.White,
            background = Ev.Bg,
            onBackground = Ev.Text,
            surface = Ev.Panel,
            onSurface = Ev.Text,
            surfaceVariant = Ev.Line,
            onSurfaceVariant = Ev.Muted
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
