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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
 *  - Saat belum ada video (!vm.hasVideo): menampilkan README / Panduan penggunaan di pratinjau kosong.
 *  - Saat sudah ada video (vm.hasVideo): menampilkan video + teks overlay, mendukung pinch-to-zoom & ketuk teks.
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
        } else {
            EmptyPreviewReadme(vm = vm, onImport = onImport)
            OverlayLayer(vm, onEditText)
        }
    }
}

/**
 * Readme / Panduan singkat yang ditampilkan di area pratinjau saat belum ada video yang diimpor.
 */
@Composable
fun EmptyPreviewReadme(
    vm: EditorViewModel,
    onImport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ev.Panel)
            .border(1.dp, Ev.Line, RoundedCornerShape(10.dp))
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Ev.Accent)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "README",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Text(
                text = "Editor Video · Pemecah Bagian",
                color = Ev.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "Aplikasi editor video untuk memecah video menjadi beberapa bagian (potongan/scenes), mengedit tiap potongan, dan mengekspornya sekaligus atau terpisah.",
            color = Ev.InfoText,
            fontSize = 11.5.sp,
            lineHeight = 16.sp
        )

        ReadmeBullet(
            num = "1",
            title = "Impor & Multiseleksi (+)",
            desc = "Ketuk tombol + untuk memilih 1 atau beberapa video sekaligus. Ketuk + lagi untuk menambah video ke timeline."
        )
        ReadmeBullet(
            num = "2",
            title = "Potong / Pecah Video",
            desc = "Geser timeline ke waktu yang diinginkan lalu ketuk Potong untuk memecah video menjadi beberapa bagian."
        )
        ReadmeBullet(
            num = "3",
            title = "Membalikkan & Alat Klip",
            desc = "Atur Kecepatan (0.1x–9x), Duplikat, Hapus, Putar, Cermin, Beku (freeze), dan Membalikkan (putar mundur)."
        )
        ReadmeBullet(
            num = "4",
            title = "Overlay Teks (Default Kosong)",
            desc = "Ketuk alat Teks di bar bawah untuk menambah atau mengosongkan teks overlay atas & bawah."
        )
        ReadmeBullet(
            num = "5",
            title = "Pinch untuk Zoom In/Out",
            desc = "Cubit 2 jari di Timeline untuk zoom rentang waktu, atau di Pratinjau untuk memperbesar video."
        )
        ReadmeBullet(
            num = "6",
            title = "Catatan Bug & Pengembangan",
            desc = "Ketuk ikon Catatan di bar atas untuk mencatat temuan bug atau rencana fitur masa depan."
        )
        ReadmeBullet(
            num = "7",
            title = "Ekspor Fleksibel",
            desc = "Simpan sebagai 1 Video Utuh, Semua Potongan (file MP4 terpisah per bagian), atau Potongan Saat Ini."
        )

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ev.Accent)
                    .clickable(onClick = onImport)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+ Pilih Video",
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ev.Ghost)
                    .clickable { vm.noteSheet = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Catatan",
                    color = Ev.Text,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ReadmeBullet(num: String, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Ev.Line),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = num,
                color = Ev.Yellow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Ev.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = desc,
                color = Ev.Muted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
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
 * Dua teks overlay digambar dengan kode yang sama seperti saat ekspor.
 * Mendukung:
 *  - Ketuk 1 jari pada teks overlay untuk langsung mengedit teks tersebut.
 *  - Cubit 2 jari (pinch in/out) pada video untuk memperbesar/memperkecil pratinjau.
 */
@Composable
private fun OverlayLayer(vm: EditorViewModel, onEditText: (Int) -> Unit) {
    val slop = LocalDensity.current.run { 10.dp.toPx() }
    val hitPad = LocalDensity.current.run { 12.dp.toPx() }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(vm.hasVideo) {
                if (!vm.hasVideo) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    var movedMuch = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
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
                                if (vm.previewZoom > 1.02f) {
                                    val pan = ev.calculatePan()
                                    vm.updatePreviewTransform(1f, pan.x, pan.y)
                                    cur.consume()
                                }
                            }
                        }
                        if (pressed.isEmpty()) {
                            if (!multiTouch && !movedMuch) {
                                val hit = OverlayRenderer.hitTest(
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                    vm.text1,
                                    vm.text2,
                                    down.position.x,
                                    down.position.y,
                                    hitPad
                                )
                                if (hit != 0) onEditText(hit)
                            }
                            break
                        }
                    }
                }
            }
    ) {
        val revTrigger = vm.rev
        val t1 = vm.text1
        val t2 = vm.text2
        if (revTrigger >= 0 && (t1.isNotEmpty() || t2.isNotEmpty())) {
            drawIntoCanvas { c ->
                OverlayRenderer.draw(c.nativeCanvas, size.width, size.height, t1, t2)
            }
        }
    }
}
