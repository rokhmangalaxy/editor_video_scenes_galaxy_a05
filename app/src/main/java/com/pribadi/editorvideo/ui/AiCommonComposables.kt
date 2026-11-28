package com.pribadi.editorvideo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pribadi.editorvideo.ui.Theme.ev

// =============================================================================================
// KOMPONEN UMUM AI — digunakan oleh banyak tab
// =============================================================================================

@Composable
internal fun AiSectionLabel(text: String) {
    Text(
        text = text,
        color = ev.Muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
internal fun AiModeCard(
    modifier: Modifier,
    active: Boolean,
    title: String,
    sub: String,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (active) ev.Accent.copy(alpha = 0.22f) else ev.Bg)
            .border(
                width = if (active) 1.5.dp else 1.dp,
                color = if (active) ev.Accent else ev.Line,
                shape = RoundedCornerShape(11.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(
            text = title,
            color = if (active) Color.White else ev.Text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = sub,
            color = if (active) ev.Yellow else ev.Muted,
            fontSize = 10.sp
        )
    }
}

@Composable
internal fun AiMiniBtn(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(8.dp))
            .background(ev.Ghost)
            .border(1.dp, ev.Line, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = ev.Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = com.pribadi.editorvideo.ui.TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun AiSmallChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) ev.Accent else ev.Ghost)
            .border(1.dp, if (active) ev.Accent else ev.Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
internal fun AiTinyIconLabel(
    symbol: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .background(ev.Ghost)
            .border(1.dp, ev.Line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = ev.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
