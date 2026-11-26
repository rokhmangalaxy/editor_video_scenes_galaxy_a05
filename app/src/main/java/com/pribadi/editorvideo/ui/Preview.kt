package com.pribadi.editorvideo.ui

import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pribadi.editorvideo.R
import com.pribadi.editorvideo.export.OverlayRenderer
import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.model.Seg
import com.pribadi.editorvideo.vm.EditorViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min

/**
 * Kotak pratinjau tunggal:
 *  - Saat belum ada video (!vm.hasVideo): menampilkan kotak pratinjau bersih tanpa Readme.
 *  - Saat sudah ada video (vm.hasVideo): menampilkan video + teks overlay kaya, indikator Mundur / Loop,
 *    mendukung pinch-to-zoom 2 jari, ketuk teks untuk mengedit, dan seret 1 jari untuk memindah posisi teks.
 */
@Composable
fun PreviewBox(
    vm: EditorViewModel,
    boxW: Dp,
    boxH: Dp,
    onEditText: (Int) -> Unit,
    onImport: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(boxW, boxH)
            .clip(RoundedCornerShape(10.dp))
            .clipToBounds()
            .background(if (vm.hasVideo) Color.Black else Ev.Panel)
    ) {
        if (vm.hasVideo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = vm.previewZoom
                        scaleY = vm.previewZoom
                        translationX = vm.previewPanX
                        translationY = vm.previewPanY
                    }
            ) {
                VideoSurface(vm, boxW, boxH)
                OverlayLayer(vm, onEditText)
            }

            if (vm.previewZoom > 1.02f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ev.Bg.copy(alpha = 0.82f))
                        .border(1.dp, Ev.Line, RoundedCornerShape(14.dp))
                        .clickable { vm.resetPreviewZoom() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Zoom " + Fmt.one(vm.previewZoom.toDouble()) + "x · Ketuk untuk Reset",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (vm.selReversed && !vm.reverseRendering) {
                val badgeLabel = if (vm.selRevMode == Seg.REV_LOOP) "🔁 Loop" else "⏪ Mundur"
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ev.Accent.copy(alpha = 0.88f))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeLabel,
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (vm.reverseRendering) {
                ReverseProgressOverlay(vm)
            }
        } else {
            EmptyPreviewState(onImport = onImport)
            OverlayLayer(vm, onEditText)
        }
    }
}

/**
 * Mode Grid 3 Kolom: menampilkan video ke dalam susunan Grid 3 Kolom interaktif.
 *  - Mode "Potongan": tiap sel menampilkan 1 potongan video (sel aktif memutar video secara langsung).
 *  - Mode "9 Bagian": membagi rentang waktu video ke dalam 9 sel (3×3) dengan pemutaran langsung di sel aktif.
 */
@Composable
fun VideoGridStage(
    vm: EditorViewModel,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val _rev = vm.rev
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        if (!vm.hasVideo || vm.core.segs.isEmpty()) {
            // Grid 3 kolom kosong sebelum video diimpor
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ev.Panel)
                    .border(1.dp, Ev.Line, RoundedCornerShape(12.dp))
                    .clickable(onClick = onImport)
                    .padding(12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                for (r in 0 until 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (c in 0 until 3) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(0.85f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Ev.Bg)
                                    .border(1.dp, Ev.Line, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (r == 0 && c == 1) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(Ev.Accent),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Ico(R.drawable.ic_plus, Color.White, 18.dp)
                                    }
                                } else {
                                    Text(
                                        text = "#${r * 3 + c + 1}",
                                        color = Ev.Muted.copy(alpha = 0.45f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                    if (r == 0) Spacer(modifier = Modifier.height(8.dp))
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Mode Grid 3 Kolom · Ketuk untuk Impor Video",
                    color = Ev.Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            val segs = vm.core.segs
            val useSegGrid = when (vm.gridSubMode) {
                1 -> true
                2 -> false
                else -> segs.size >= 2
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // Header kecil Mode Grid 3 Kolom
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MODE GRID · 3 KOLOM",
                        color = Ev.Yellow,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GridFilterPill(
                            label = "Potongan (${segs.size})",
                            active = useSegGrid
                        ) { vm.gridSubMode = 1 }
                        GridFilterPill(
                            label = "9 Bagian",
                            active = !useSegGrid
                        ) { vm.gridSubMode = 2 }
                    }
                }

                // Isi Grid 3 Kolom (scrollable)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (useSegGrid) {
                        val rows = segs.indices.toList().chunked(3)
                        for (rowIndices in rows) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (idx in rowIndices) {
                                    val g = segs[idx]
                                    val isActive = idx == vm.selIndex
                                    val midSrcTime = if (g.fz) g.a else (g.a + g.b) * 0.5
                                    val cellAspect = vm.aspectOfSource(g.src).coerceIn(0.68f, 1.35f)
                                    val tagRight = Fmt.durLabel(g.len)
                                    val statusPrefix = when {
                                        g.fz -> "❄ "
                                        g.revMode == Seg.REV_LOOP -> "🔁 "
                                        g.rev -> "⏪ "
                                        else -> ""
                                    }
                                    GridThreeColCell(
                                        vm = vm,
                                        cellKey = idx,
                                        isActive = isActive,
                                        srcIndex = g.src,
                                        thumbTimeSec = midSrcTime,
                                        topBadge = "$statusPrefix#${idx + 1}",
                                        bottomBadge = tagRight,
                                        cellAspect = cellAspect,
                                        modifier = Modifier.weight(1f),
                                        onTap = {
                                            if (isActive) {
                                                vm.togglePlay()
                                            } else {
                                                vm.selectSegmentFromGrid(idx)
                                            }
                                        }
                                    )
                                }
                                repeat(3 - rowIndices.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        // 9 Bagian Waktu dalam Grid 3x3 (3 kolom)
                        val total = vm.core.total.coerceAtLeast(0.1)
                        val sliceDur = total / 9.0
                        val activeSlice = ((vm.core.t / sliceDur).toInt()).coerceIn(0, 8)
                        val cellAspect = vm.aspectOfSource(0).coerceIn(0.68f, 1.35f)
                        val rows = (0 until 9).toList().chunked(3)
                        for (rowSlices in rows) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (sIdx in rowSlices) {
                                    val startT = sIdx * sliceDur
                                    val midT = min(total, startT + sliceDur * 0.5)
                                    val loc = vm.core.locate(midT)
                                    val g = segs.getOrElse(loc.i) { segs.first() }
                                    val isActive = sIdx == activeSlice
                                    GridThreeColCell(
                                        vm = vm,
                                        cellKey = 100 + sIdx,
                                        isActive = isActive,
                                        srcIndex = g.src,
                                        thumbTimeSec = loc.s,
                                        topBadge = "#${sIdx + 1}",
                                        bottomBadge = Fmt.time(startT),
                                        cellAspect = cellAspect,
                                        modifier = Modifier.weight(1f),
                                        onTap = {
                                            if (isActive) {
                                                vm.togglePlay()
                                            } else {
                                                vm.tapAt(startT + 0.001)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (vm.reverseRendering) {
                ReverseProgressOverlay(vm)
            }
        }
    }
}

@Composable
private fun GridFilterPill(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Ev.Accent else Ev.Ghost)
            .border(1.dp, if (active) Ev.Accent else Ev.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = if (active) Color.White else Ev.InfoText,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun GridThreeColCell(
    vm: EditorViewModel,
    cellKey: Int,
    isActive: Boolean,
    srcIndex: Int,
    thumbTimeSec: Double,
    topBadge: String,
    bottomBadge: String,
    cellAspect: Float,
    modifier: Modifier = Modifier,
    onTap: () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(cellAspect)
            .clip(RoundedCornerShape(9.dp))
            .background(Color.Black)
            .border(
                width = if (isActive) 2.dp else 1.dp,
                color = if (isActive) Ev.Accent else Ev.Line,
                shape = RoundedCornerShape(9.dp)
            )
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        val cellW = maxWidth
        val cellH = maxHeight
        if (isActive) {
            // Sel aktif memutar video secara langsung di dalam kotak grid
            val videoAr = vm.aspectOfSource(srcIndex)
            var vw = cellW
            var vh = vw / videoAr
            if (vh > cellH) {
                vh = cellH
                vw = vh * videoAr
            }
            LaunchedEffect(cellKey) {
                delay(120)
                vm.refreshFrame()
            }
            Box(modifier = Modifier.size(vw, vh)) {
                VideoSurface(vm, vw, vh)
            }
        } else {
            val bmp = vm.thumbAt(srcIndex, thumbTimeSec)
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = topBadge,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Ev.Panel),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = topBadge, color = Ev.Muted, fontSize = 11.sp)
                }
            }
        }

        // Lencana kiri atas (# nomor sel)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (isActive) Ev.Accent else Color(0xBF000000))
                .padding(horizontal = 5.dp, vertical = 1.5.dp)
        ) {
            Text(
                text = topBadge,
                color = Color.White,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }

        // Lencana kanan bawah (durasi / waktu)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0xBF000000))
                .padding(horizontal = 5.dp, vertical = 1.5.dp)
        ) {
            Text(
                text = bottomBadge,
                color = Ev.Yellow,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ReverseProgressOverlay(vm: EditorViewModel) {
    val pct = (vm.reverseProgress * 100f).toInt().coerceIn(0, 100)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xBF000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Ev.Panel)
                .border(1.dp, Ev.Line, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Merender ${vm.reverseRenderingLabel}… $pct%",
                color = Ev.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { vm.reverseProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .width(190.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Ev.Accent,
                trackColor = Ev.Ghost
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Ev.Ghost)
                    .clickable { vm.cancelReverseRender() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Batalkan",
                    color = Ev.InfoText,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Tampilan pratinjau awal yang bersih ketika belum ada video yang dipilih (tanpa menampilkan Readme).
 */
@Composable
private fun EmptyPreviewState(onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ev.Panel)
            .border(1.dp, Ev.Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onImport)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Ev.Accent),
            contentAlignment = Alignment.Center
        ) {
            Ico(R.drawable.ic_plus, Color.White, 28.dp)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Impor Video",
            color = Ev.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Ketuk untuk memilih satu atau beberapa video",
            color = Ev.Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * TextureView dibuat seukuran video sebelum diputar, lalu diputar/dicermin lewat properti View.
 */
@Composable
private fun VideoSurface(vm: EditorViewModel, boxW: Dp, boxH: Dp) {
    val rot = vm.rot
    val flip = vm.flip
    val r90 = rot % 180 != 0
    val preW = if (r90) boxH else boxW
    val preH = if (r90) boxW else boxH
    val density = LocalDensity.current
    val wPx = with(density) { preW.roundToPx() }
    val hPx = with(density) { preH.roundToPx() }

    LaunchedEffect(Unit) {
        delay(250)
        vm.refreshFrame()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val frame = FrameLayout(ctx)
            frame.clipChildren = false
            frame.clipToPadding = false
            val tv = TextureView(ctx)
            frame.addView(tv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
            vm.player.setVideoTextureView(tv)
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
            tv.rotation = rot.toFloat()
            tv.scaleX = if (flip && !r90) -1f else 1f
            tv.scaleY = if (flip && r90) -1f else 1f
        },
        onRelease = { frame ->
            vm.player.clearVideoTextureView(frame.getChildAt(0) as TextureView)
        }
    )
}

/**
 * Dua layer teks overlay digambar dengan kode yang sama persis seperti saat ekspor.
 */
@Composable
private fun OverlayLayer(vm: EditorViewModel, onEditText: (Int) -> Unit) {
    val slop = LocalDensity.current.run { 8.dp.toPx() }
    val hitPad = LocalDensity.current.run { 14.dp.toPx() }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(vm.hasVideo) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    var movedMuch = false
                    val hitOnDown = OverlayRenderer.hitTest(
                        size.width.toFloat(),
                        size.height.toFloat(),
                        vm.style1,
                        vm.style2,
                        down.position.x,
                        down.position.y,
                        hitPad
                    )
                    var prevPos = down.position

                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2 && vm.hasVideo) {
                            multiTouch = true
                            val zoomChange = ev.calculateZoom()
                            val panChange = ev.calculatePan()
                            val centroidSize = ev.calculateCentroidSize(useCurrent = false)
                            if (centroidSize > 0f && (abs(zoomChange - 1f) > 0.002f || panChange.getDistance() > 0.5f)) {
                                vm.updatePreviewTransform(zoomChange, panChange.x, panChange.y)
                            }
                            ev.changes.forEach { it.consume() }
                        } else if (!multiTouch && pressed.size == 1) {
                            val cur = pressed[0]
                            val d = (cur.position - down.position).getDistance()
                            if (d > slop) {
                                movedMuch = true
                            }
                            if (movedMuch) {
                                val dx = cur.position.x - prevPos.x
                                val dy = cur.position.y - prevPos.y
                                prevPos = cur.position
                                if (hitOnDown != 0 && size.width > 0 && size.height > 0) {
                                    vm.moveOverlayText(
                                        hitOnDown,
                                        dx / size.width.toFloat(),
                                        dy / size.height.toFloat()
                                    )
                                    cur.consume()
                                } else if (vm.hasVideo && vm.previewZoom > 1.02f) {
                                    vm.updatePreviewTransform(1f, dx, dy)
                                    cur.consume()
                                }
                            }
                        }
                        if (pressed.isEmpty()) {
                            if (!multiTouch && !movedMuch) {
                                if (hitOnDown != 0) {
                                    onEditText(hitOnDown)
                                } else if (vm.hasVideo) {
                                    vm.togglePlay()
                                }
                            }
                            break
                        }
                    }
                }
            }
    ) {
        val revTrigger = vm.rev
        val s1 = vm.style1
        val s2 = vm.style2
        if (revTrigger >= 0 && (s1.isNotEmpty || s2.isNotEmpty)) {
            drawIntoCanvas { c ->
                OverlayRenderer.draw(c.nativeCanvas, size.width, size.height, s1, s2)
            }
        }
    }
}
