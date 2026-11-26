package com.pribadi.editorvideo.ui

import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pribadi.editorvideo.R
import com.pribadi.editorvideo.export.OverlayRenderer
import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.vm.EditorViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Kotak pratinjau:
 *  - Saat belum ada video (!vm.hasVideo): menampilkan kotak pratinjau bersih tanpa Readme
 *    (Readme & riwayat pembaruan dipindahkan ke menu Catatan > tab Updated).
 *  - Saat sudah ada video (vm.hasVideo): menampilkan video + teks overlay kaya, mendukung
 *    pinch-to-zoom 2 jari, ketuk teks untuk mengedit, dan seret 1 jari untuk memindah posisi teks.
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
                        .background(Color(0xCC141416))
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
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCCFF3B5C))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "⏪ Dibalik",
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (vm.reverseRendering) {
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
                            text = "Membalikkan Urutan Frame… $pct%",
                            color = Ev.Text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
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
        } else {
            EmptyPreviewState(onImport = onImport)
            OverlayLayer(vm, onEditText)
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
 * Mendukung:
 *  - Ketuk 1 jari pada teks overlay untuk langsung membuka editor teks pada layer tersebut.
 *  - Seret (drag) 1 jari pada teks overlay untuk menggeser posisi teks bebas di pratinjau.
 *  - Cubit 2 jari (pinch in/out) pada video untuk memperbesar/memperkecil pratinjau.
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
