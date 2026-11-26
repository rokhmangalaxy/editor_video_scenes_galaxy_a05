package com.pribadi.editorvideo.ui

import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.pribadi.editorvideo.R
import com.pribadi.editorvideo.vm.CollageClipItem

/**
 * Layar Multiplayer Collage (Collase): memutar beberapa klip hasil multiseleksi Mode Grid
 * secara bersamaan dalam satu kanvas grid kolase interaktif.
 */
@Composable
fun MultiplayerCollageOverlay(
    items: List<CollageClipItem>,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var allPlaying by remember { mutableStateOf(true) }
    var audioMode by remember { mutableIntStateOf(1) } // 0 = Bisu, 1 = Klip #1, 2 = Semua Klip
    var colChoice by remember { mutableIntStateOf(0) } // 0 = Otomatis, 2 = 2 Kolom, 3 = 3 Kolom
    var canvasRatioIdx by remember { mutableIntStateOf(0) } // 0 = Penuh, 1 = 9:16, 2 = 1:1, 3 = 16:9
    var gapIdx by remember { mutableIntStateOf(1) } // 0 = 0dp, 1 = 3dp, 2 = 7dp
    var showLabels by remember { mutableStateOf(true) }

    val players = remember(items) {
        items.mapIndexed { idx, clip ->
            val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 1600, 200, 400)
                .build()
            ExoPlayer.Builder(context, renderers)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    val clipping = MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs)
                        .setEndPositionMs(clip.endMs)
                        .build()
                    val mediaItem = MediaItem.Builder()
                        .setUri(clip.uri)
                        .setClippingConfiguration(clipping)
                        .build()
                    setMediaItem(mediaItem)
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = if (idx == 0) 1f else 0f
                    prepare()
                    if (clip.freeze) {
                        playWhenReady = false
                        seekTo(0L)
                    } else {
                        setPlaybackSpeed(clip.speed)
                        playWhenReady = true
                    }
                }
        }
    }

    LaunchedEffect(allPlaying) {
        players.forEachIndexed { idx, p ->
            val clip = items.getOrNull(idx)
            if (clip != null && !clip.freeze) {
                if (allPlaying) p.play() else p.pause()
            }
        }
    }

    LaunchedEffect(audioMode) {
        players.forEachIndexed { idx, p ->
            p.volume = when (audioMode) {
                0 -> 0f
                1 -> if (idx == 0) 1f else 0f
                else -> 1f / players.size.coerceAtLeast(1).toFloat()
            }
        }
    }

    DisposableEffect(players) {
        onDispose {
            for (p in players) {
                try {
                    p.stop()
                    p.release()
                } catch (e: Exception) {
                    // diabaikan
                }
            }
        }
    }

    val cols = when {
        colChoice == 2 -> 2
        colChoice == 3 -> 3
        items.size <= 1 -> 1
        items.size <= 4 -> 2
        else -> 3
    }
    val gapDp = when (gapIdx) {
        0 -> 0.dp
        1 -> 3.dp
        else -> 7.dp
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .background(Ev.Bg)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Header Multiplayer Collage
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ev.Panel)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Ico(R.drawable.ic_collage, Ev.Yellow, 21.dp)
                    Column {
                        Text(
                            text = "Multiplayer Collage (${items.size} Klip)",
                            color = Ev.Text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Memutar semua klip terpilih secara serentak",
                            color = Ev.Muted,
                            fontSize = 11.sp
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Ev.Ghost)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Ico(R.drawable.ic_close, Ev.Text, 20.dp)
                }
            }

            // Opsi Layout Collage (Kolom, Rasio Kanvas, Jarak, Label)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ev.Panel)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CollageOptChip("Otomatis", colChoice == 0) { colChoice = 0 }
                CollageOptChip("2 Kolom", colChoice == 2) { colChoice = 2 }
                CollageOptChip("3 Kolom", colChoice == 3) { colChoice = 3 }
                Spacer(modifier = Modifier.width(4.dp))
                val ratioLabel = when (canvasRatioIdx) {
                    1 -> "Rasio 9:16"
                    2 -> "Rasio 1:1"
                    3 -> "Rasio 16:9"
                    else -> "Rasio Penuh"
                }
                CollageOptChip(ratioLabel, canvasRatioIdx != 0) {
                    canvasRatioIdx = (canvasRatioIdx + 1) % 4
                }
                val gapLabel = when (gapIdx) {
                    0 -> "Rapat"
                    1 -> "Jarak 3dp"
                    else -> "Jarak 7dp"
                }
                CollageOptChip(gapLabel, false) {
                    gapIdx = (gapIdx + 1) % 3
                }
                CollageOptChip(if (showLabels) "Label: On" else "Label: Off", showLabels) {
                    showLabels = !showLabels
                }
            }

            // Area Kanvas Collage Video Serentak
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                val targetAspect: Float? = when (canvasRatioIdx) {
                    1 -> 9f / 16f
                    2 -> 1f
                    3 -> 16f / 9f
                    else -> null
                }
                val canvasW: Dp
                val canvasH: Dp
                if (targetAspect == null) {
                    canvasW = maxWidth
                    canvasH = maxHeight
                } else {
                    var w = maxWidth
                    var h = w / targetAspect
                    if (h > maxHeight) {
                        h = maxHeight
                        w = h * targetAspect
                    }
                    canvasW = w
                    canvasH = h
                }

                val rows = items.indices.toList().chunked(cols)
                Column(
                    modifier = Modifier
                        .size(canvasW, canvasH)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
                        .padding(gapDp),
                    verticalArrangement = Arrangement.spacedBy(gapDp)
                ) {
                    for (rowIndices in rows) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(gapDp)
                        ) {
                            for (i in rowIndices) {
                                val clip = items[i]
                                val tilePlayer = players[i]
                                CollageTilePlayer(
                                    clip = clip,
                                    player = tilePlayer,
                                    orderNumber = i + 1,
                                    showLabel = showLabels,
                                    rounded = gapIdx > 0,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    onTap = {
                                        allPlaying = !allPlaying
                                    }
                                )
                            }
                            repeat(cols - rowIndices.size) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(Color(0xFF0A0A0C))
                                )
                            }
                        }
                    }
                }
            }

            // Kontrol Bawah Serentak (Putar/Jeda Semua, Sinkron Awal, Mode Audio)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ev.Panel)
                    .border(1.dp, Ev.Line)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ev.Accent)
                        .clickable { allPlaying = !allPlaying }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Ico(
                            id = if (allPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                            tint = Color.White,
                            size = 18.dp
                        )
                        Text(
                            text = if (allPlaying) "Jeda Semua" else "Putar Semua",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ev.Ghost)
                        .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
                        .clickable {
                            players.forEachIndexed { idx, p ->
                                p.seekTo(0L)
                                val clip = items.getOrNull(idx)
                                if (clip != null && !clip.freeze) {
                                    p.play()
                                }
                            }
                            allPlaying = true
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "↺ Sinkron Awal",
                        color = Ev.Text,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }

                val audioText = when (audioMode) {
                    0 -> "🔇 Bisu"
                    1 -> "🔊 Klip #1"
                    else -> "🔊 Campur"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ev.Ghost)
                        .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
                        .clickable { audioMode = (audioMode + 1) % 3 }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = audioText,
                        color = Ev.Yellow,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CollageOptChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Ev.Accent else Ev.Ghost)
            .border(1.dp, if (active) Ev.Accent else Ev.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = if (active) Color.White else Ev.InfoText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CollageTilePlayer(
    clip: CollageClipItem,
    player: ExoPlayer,
    orderNumber: Int,
    showLabel: Boolean,
    rounded: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(if (rounded) 7.dp else 0.dp))
            .clipToBounds()
            .background(Color.Black)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        // Cover-fill agar setiap kotak kolase terisi rapi tanpa celah hitam
        val tileW = maxWidth
        val tileH = maxHeight
        val ar = clip.aspect.coerceIn(0.25f, 4f)
        var vw = tileW
        var vh = vw / ar
        if (vh < tileH) {
            vh = tileH
            vw = vh * ar
        }

        val r90 = clip.rot % 180 != 0
        val preW = if (r90) vh else vw
        val preH = if (r90) vw else vh
        val density = LocalDensity.current
        val wPx = with(density) { preW.roundToPx() }.coerceAtLeast(2)
        val hPx = with(density) { preH.roundToPx() }.coerceAtLeast(2)

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val frame = FrameLayout(ctx)
                frame.clipChildren = false
                frame.clipToPadding = false
                val tv = TextureView(ctx)
                frame.addView(tv, FrameLayout.LayoutParams(wPx, hPx, Gravity.CENTER))
                player.setVideoTextureView(tv)
                frame
            },
            update = { frame ->
                val tv = frame.getChildAt(0) as TextureView
                val lp = tv.layoutParams as FrameLayout.LayoutParams
                if (lp.width != wPx || lp.height != hPx) {
                    lp.width = wPx
                    lp.height = hPx
                    tv.layoutParams = lp
                }
                tv.rotation = clip.rot.toFloat()
                tv.scaleX = if (clip.flip && !r90) -1f else 1f
                tv.scaleY = if (clip.flip && r90) -1f else 1f
            },
            onRelease = { frame ->
                player.clearVideoTextureView(frame.getChildAt(0) as TextureView)
            }
        )

        if (showLabel) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "$orderNumber · ${clip.label}",
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
