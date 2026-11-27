package com.pribadi.editorvideo.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.pribadi.editorvideo.R
import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.model.Rules
import com.pribadi.editorvideo.model.Seg
import com.pribadi.editorvideo.vm.EditorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Ukuran-ukuran dalam dp.
private const val SEG_TOP = 18f
private const val SEG_H = 48f
private const val SEG_R = 3f
private const val HD_TOP = 21f
private const val HD_W = 18f
private const val HD_H = 42f
private const val HD_R = 6f
private const val HIT_X = 10f
private const val HIT_Y = 6f
private const val RULER_TOP = 84f
private const val HEAD_TOP = 6f
private const val HEAD_BOTTOM_INSET = 30f
private const val TIMES_TOP = 124f

private val RULER_STEPS = doubleArrayOf(0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0)

private class TlPaints(val d: Float, palette: EvPalette) {
    val line = Paint().apply { color = palette.line.toArgb() }
    val segBg = Paint().apply { color = palette.segBg }
    val fzBg = Paint().apply { color = palette.fzBg }
    val tile = Paint(Paint.FILTER_BITMAP_FLAG)
    val tileFz = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = freezeFilter() }
    val ph = Paint()
    val divider = Paint().apply { color = palette.bg.toArgb() }
    val selBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = 0xFFFFFFFF.toInt()
    }
    val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
    val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 9.5f * d
        typeface = Typeface.DEFAULT_BOLD
    }
    val durBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xBF101014.toInt() }
    val durBadgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.yellow.toArgb()
        textSize = 9.5f * d
        typeface = Typeface.DEFAULT_BOLD
    }
    val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    val handleIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() }
    val rulerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.muted.toArgb()
        textSize = 11f * d
        textAlign = Paint.Align.CENTER
    }
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.muted.copy(alpha = 0.65f).toArgb() }
    val head = Paint().apply { color = 0xFFFFFFFF.toInt() }
    val markerPin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFACC15.toInt() }
    val markerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFACC15.toInt()
        strokeWidth = 1.6f * d
    }
    val markerLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111115.toInt()
        textSize = 8f * d
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val curText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.text.toArgb()
        textSize = 11.5f * d
        typeface = Typeface.create(Typeface.DEFAULT, 800, false)
        textAlign = Paint.Align.CENTER
    }
    val clipDurText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.infoText.toArgb()
        textSize = 11f * d
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        textAlign = Paint.Align.LEFT
    }
    val totText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.infoText.toArgb()
        textSize = 11.5f * d
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        textAlign = Paint.Align.RIGHT
    }
    /** Timestamp kecil durasi scene aktif (dari awal scene ke playhead). */
    val seekText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.yellow.toArgb()
        textSize = 8.5f * d
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        textAlign = Paint.Align.CENTER
    }
    val emptyHint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.muted.copy(alpha = 0.75f).toArgb()
        textSize = 12f * d
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        textAlign = Paint.Align.CENTER
    }

    val rect = RectF()
    val rect2 = RectF()
    val clip = Path()
    val tri = Path()

    /** Warna polos pengganti thumbnail, 12 x 3 variasi. */
    private val placeholders = IntArray(36)

    init {
        for (a in 0 until 12) {
            for (b in 0 until 3) {
                val c1 = ColorUtils.HSLToColor(floatArrayOf((200 + a * 8).toFloat(), 0.12f, (78 - b * 5) / 100f))
                val c2 = ColorUtils.HSLToColor(floatArrayOf((210 + a * 6).toFloat(), 0.10f, 0.62f))
                val r = (((c1 shr 16) and 0xFF) + ((c2 shr 16) and 0xFF)) / 2
                val g = (((c1 shr 8) and 0xFF) + ((c2 shr 8) and 0xFF)) / 2
                val bl = ((c1 and 0xFF) + (c2 and 0xFF)) / 2
                placeholders[a * 3 + b] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
    }

    fun placeholder(i: Int): Int {
        val a = ((i % 12) + 12) % 12
        val b = ((i % 3) + 3) % 3
        return placeholders[a * 3 + b]
    }

    private fun freezeFilter(): ColorMatrixColorFilter {
        val m = ColorMatrix()
        m.setSaturation(0.7f)
        val br = ColorMatrix(
            floatArrayOf(
                0.85f, 0f, 0f, 0f, 0f,
                0f, 0.85f, 0f, 0f, 0f,
                0f, 0f, 0.85f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        m.postConcat(br)
        return ColorMatrixColorFilter(m)
    }
}

private class FlingHolder {
    var job: Job? = null
    var lastTag: String = ""
}

/** Kotak timeline 150 dp: kosong di awal; saat video ada menampilkan ruler, potongan + label durasi, handle trim, playhead. */
@Composable
fun Timeline(vm: EditorViewModel, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val d = density.density
    val theme = vm.appTheme
    val paints = remember(d, theme) { TlPaints(d, paletteFor(theme)) }
    val scope = rememberCoroutineScope()
    val holder = remember { FlingHolder() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(158.dp)
            .background(Ev.Bg)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(vm.hasVideo) {
                    if (vm.hasVideo) {
                        timelineGestures(vm, scope, holder)
                    }
                }
        ) {
            val revision = vm.rev
            drawIntoCanvas { c ->
                drawTimeline(c.nativeCanvas, vm, size.width, size.height, paints, revision)
            }
        }

        // Tombol + (impor / tambah video multiseleksi) di kiri atas, di atas timeline.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 15.dp)
                .width(88.dp)
                .height(52.dp)
                .background(
                    Brush.horizontalGradient(
                        0f to Ev.Bg,
                        0.72f to Ev.Bg,
                        1f to Color.Transparent
                    )
                )
                .padding(start = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp, 50.dp)
                    .shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(12.dp),
                        clip = false,
                        ambientColor = Ev.Accent,
                        spotColor = Ev.Accent
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ev.Accent)
                    .clickable(onClick = onImport),
                contentAlignment = Alignment.Center
            ) {
                Ico(R.drawable.ic_plus, Color.White, 28.dp)
            }
        }

        // Label zoom sesaat saat pinch.
        val tag = vm.zoomTag
        if (tag != null) holder.lastTag = tag
        val tagAlpha by animateFloatAsState(
            targetValue = if (tag != null) 1f else 0f,
            animationSpec = tween(200),
            label = "ztag"
        )
        if (tagAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 10.dp)
                    .alpha(tagAlpha)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    text = holder.lastTag,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Geometri
// ---------------------------------------------------------------------------------------------

/** Tepi kiri dan kanan potongan terpilih (px), termasuk perubahan sementara saat handle kiri digeser. */
private fun selEdges(vm: EditorViewModel, cx: Float, ppsPx: Double): FloatArray {
    val core = vm.core
    val segs = core.segs
    if (segs.isEmpty()) return floatArrayOf(cx, cx)
    val i = core.sel.coerceIn(0, segs.size - 1)
    val g = segs[i]
    var left = cx + ((core.lay.off[i] - core.t) * ppsPx).toFloat()
    var width = (g.len * ppsPx).toFloat()
    val ng = core.trimSeg
    if (core.trimmingSide == 1 && core.trimmingIndex == i && ng != null) {
        if (g.fz) {
            left += ((g.d - ng.d) / g.sp * ppsPx).toFloat()
            width = (ng.d / g.sp * ppsPx).toFloat()
        } else {
            left += ((ng.a - g.a) / g.sp * ppsPx).toFloat()
            width = ((g.b - ng.a) / g.sp * ppsPx).toFloat()
        }
    }
    return floatArrayOf(left, left + width)
}

/** 1 = handle kiri, 2 = handle kanan, 0 = bukan handle. */
private fun hitHandle(vm: EditorViewModel, pos: Offset, w: Float, d: Float): Int {
    if (!vm.hasVideo || vm.core.segs.isEmpty()) return 0
    val edges = selEdges(vm, w / 2f, vm.pps * d)
    val top = (HD_TOP - HIT_Y) * d
    val bottom = (HD_TOP + HD_H + HIT_Y) * d
    if (pos.y < top || pos.y > bottom) return 0
    val l0 = edges[0] - (HD_W + 2f + HIT_X) * d
    val l1 = edges[0] - (2f - HIT_X) * d
    val r0 = edges[1] + (2f - HIT_X) * d
    val r1 = edges[1] + (HD_W + 2f + HIT_X) * d
    val inL = pos.x >= l0 && pos.x <= l1
    val inR = pos.x >= r0 && pos.x <= r1
    if (inL && inR) {
        val cl = edges[0] - (HD_W / 2f + 2f) * d
        val cr = edges[1] + (HD_W / 2f + 2f) * d
        return if (abs(pos.x - cl) <= abs(pos.x - cr)) 1 else 2
    }
    if (inL) return 1
    if (inR) return 2
    return 0
}

// ---------------------------------------------------------------------------------------------
// Gestur (mendukung scrub, ketuk, trim handle, dan pinch-to-zoom 2 jari tanpa bentrok handle)
// ---------------------------------------------------------------------------------------------

private suspend fun PointerInputScope.timelineGestures(
    vm: EditorViewModel,
    scope: CoroutineScope,
    holder: FlingHolder
) {
    val slop = viewConfiguration.touchSlop
    val d = density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        holder.job?.cancel()
        if (!vm.hasVideo || vm.core.segs.isEmpty()) return@awaitEachGesture
        val w = size.width.toFloat()

        val hit = hitHandle(vm, down.position, w, d)
        var switchedToPinch = false
        var pinchD0 = 0f
        var pinchP0 = 0.0

        if (hit != 0) {
            down.consume()
            vm.trimBegin(hit)
            var trimFinished = false
            try {
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        vm.trimCancel()
                        trimFinished = true
                        switchedToPinch = true
                        vm.pinchBegin()
                        pinchD0 = (pressed[0].position - pressed[1].position).getDistance()
                        pinchP0 = vm.pps
                        ev.changes.forEach { it.consume() }
                        break
                    }
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) {
                        ch.consume()
                        break
                    }
                    val dx = ch.position.x - down.position.x
                    vm.trimMove(dx.toDouble() / (vm.pps * d))
                    ch.consume()
                }
            } finally {
                if (!trimFinished) {
                    vm.trimEnd()
                }
            }
            if (!switchedToPinch) {
                return@awaitEachGesture
            }
        }

        var panning = false
        var panEnded = false
        var pinching = switchedToPinch
        var tapOk = !switchedToPinch
        var lastX = down.position.x
        val tDown = vm.core.t
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        try {
            while (true) {
                val ev = awaitPointerEvent()
                val pressed = ev.changes.filter { it.pressed }

                if (pressed.size >= 2) {
                    val dist = (pressed[0].position - pressed[1].position).getDistance()
                    if (!pinching || pinchD0 <= 1f) {
                        if (panning && !panEnded) {
                            vm.scrubEnd()
                            panEnded = true
                        }
                        pinching = true
                        tapOk = false
                        vm.pinchBegin()
                        pinchD0 = dist
                        pinchP0 = vm.pps
                    } else {
                        vm.setZoom(pinchP0 * (dist / pinchD0).toDouble())
                    }
                    ev.changes.forEach { it.consume() }
                    continue
                }
                if (pinching) {
                    if (pressed.isEmpty()) break
                    ev.changes.forEach { it.consume() }
                    continue
                }

                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) {
                    if (panning) {
                        val vx = tracker.calculateVelocity().x
                        panEnded = true
                        if (abs(vx) > 300f) {
                            holder.job = scope.launch { fling(vm, vx, d) }
                        } else {
                            vm.scrubEnd()
                        }
                    } else if (tapOk) {
                        val tt = tDown + (down.position.x - w / 2f).toDouble() / (vm.pps * d)
                        vm.tapTimelineAt(tt, onClipArea = down.position.y <= RULER_TOP * d)
                    }
                    break
                }

                tracker.addPosition(ch.uptimeMillis, ch.position)
                if (!panning && abs(ch.position.x - down.position.x) > slop) {
                    panning = true
                    tapOk = false
                    vm.scrubBegin()
                }
                if (panning) {
                    val dx = ch.position.x - lastX
                    lastX = ch.position.x
                    vm.scrubBy(-dx.toDouble() / (vm.pps * d))
                    ch.consume()
                }
            }
        } finally {
            if (panning && !panEnded) vm.scrubEnd()
        }
    }
}

/** Lanjutan geseran setelah jari diangkat (peluruhan eksponensial). */
private suspend fun CoroutineScope.fling(vm: EditorViewModel, velocityPx: Float, d: Float) {
    var vel = velocityPx
    var last = withFrameNanos { it }
    try {
        while (isActive && abs(vel) > 40f) {
            val now = withFrameNanos { it }
            var dt = (now - last) / 1e9f
            last = now
            if (dt < 0f) dt = 0f
            if (dt > 0.05f) dt = 0.05f
            val move = vel * dt
            vel *= exp(-4.2f * dt)
            val before = vm.core.t
            vm.scrubBy(-move.toDouble() / (vm.pps * d))
            if (vm.core.t == before) break
        }
    } finally {
        vm.scrubEnd()
    }
}

// ---------------------------------------------------------------------------------------------
// Menggambar
// ---------------------------------------------------------------------------------------------

@Suppress("UNUSED_PARAMETER")
private fun drawTimeline(
    cv: android.graphics.Canvas,
    vm: EditorViewModel,
    w: Float,
    h: Float,
    p: TlPaints,
    revision: Int
) {
    val d = p.d
    // garis atas
    cv.drawRect(0f, 0f, w, 1f * d, p.line)

    val core = vm.core
    val segs = core.segs

    // Di awal (sebelum ada video): timeline preview kosong bersih!
    if (!vm.hasVideo || segs.isEmpty()) {
        val fm = p.emptyHint.fontMetrics
        val hintY = (SEG_TOP + SEG_H / 2f) * d - (fm.ascent + fm.descent) / 2f
        val centerAfterPlus = (88f * d + w) / 2f
        cv.drawText("Timeline kosong", centerAfterPlus, hintY, p.emptyHint)
        return
    }

    val off = core.lay.off
    val t = core.t
    val ppsPx = vm.pps * d
    val cx = w / 2f

    drawRuler(cv, p, vm, w)

    val trimSide = core.trimmingSide
    val trimIdx = core.trimmingIndex
    val trimSeg = core.trimSeg
    for (i in segs.indices) {
        val g = segs[i]
        var left = cx + ((off[i] - t) * ppsPx).toFloat()
        var wd = (g.len * ppsPx).toFloat()
        var tg = g
        if (trimSide == 1 && i == trimIdx && trimSeg != null) {
            if (g.fz) {
                left += ((g.d - trimSeg.d) / g.sp * ppsPx).toFloat()
                wd = (trimSeg.d / g.sp * ppsPx).toFloat()
            } else {
                left += ((trimSeg.a - g.a) / g.sp * ppsPx).toFloat()
                wd = ((g.b - trimSeg.a) / g.sp * ppsPx).toFloat()
            }
            tg = trimSeg
        }
        val right = left + wd
        if (right < -4f * d || left > w + 4f * d) continue
        drawSegment(cv, vm, p, g, tg, left, right, i == core.sel, ppsPx, w)
    }

    // handle trim pada potongan terpilih
    val edges = selEdges(vm, cx, ppsPx)
    drawHandles(cv, p, edges[0], edges[1])

    // Tanda (Markers) kuning di atas track & garis penanda vertikal
    val projectedMarkers = vm.timelineProjectedMarkers()
    if (projectedMarkers.isNotEmpty()) {
        val pinTop = 4f * d
        val pinBottom = 15.5f * d
        val segBottom = (SEG_TOP + SEG_H) * d
        for (pm in projectedMarkers) {
            val mx = cx + ((pm.timelineSec - t) * ppsPx).toFloat()
            if (mx < -12f * d || mx > w + 12f * d) continue
            // Garis vertikal tipis melintasi potongan video
            cv.drawLine(mx, pinBottom, mx, segBottom, p.markerLine)
            // Pin / bendera di atas potongan
            p.rect.set(mx - 6.5f * d, pinTop, mx + 6.5f * d, pinBottom)
            cv.drawRoundRect(p.rect, 3f * d, 3f * d, p.markerPin)
            p.tri.reset()
            p.tri.moveTo(mx - 3f * d, pinBottom)
            p.tri.lineTo(mx + 3f * d, pinBottom)
            p.tri.lineTo(mx, pinBottom + 3.5f * d)
            p.tri.close()
            cv.drawPath(p.tri, p.markerPin)
        }
    }

    // playhead tetap di tengah
    cv.drawRect(cx - 1f * d, HEAD_TOP * d, cx + 1f * d, h - HEAD_BOTTOM_INSET * d, p.head)

    // baris label durasi di bawah: kiri = durasi potongan aktif, tengah = posisi waktu, kanan = total durasi
    val fmCur = p.curText.fontMetrics
    val base = TIMES_TOP * d - fmCur.ascent
    val selIdx = core.sel.coerceIn(0, segs.size - 1)
    val activeSeg = if (trimSide != 0 && trimIdx == selIdx && trimSeg != null) trimSeg else segs[selIdx]
    cv.drawText(
        "Klip ${selIdx + 1}/${segs.size} · ${Fmt.durLabel(activeSeg.len)}",
        14f * d,
        base,
        p.clipDurText
    )
    cv.drawText(Fmt.time(t), cx, base, p.curText)

    // Info seek ringkas: timestamp durasi dari AWAL SCENE yang sedang fokus (tanpa teks panjang)
    val seekSegIdx = core.locate(t).i.coerceIn(0, segs.size - 1)
    val seekInsideScene = (t - core.lay.off[seekSegIdx]).coerceAtLeast(0.0)
    cv.drawText(Fmt.time2(seekInsideScene), cx, base + 11f * d, p.seekText)

    cv.drawText("Total " + Fmt.time(core.total), w - 14f * d, base, p.totText)
}

private fun drawRuler(cv: android.graphics.Canvas, p: TlPaints, vm: EditorViewModel, w: Float) {
    val d = p.d
    val core = vm.core
    val t = core.t
    val pps = vm.pps
    val ppsPx = pps * d
    val total = core.total
    val cx = w / 2f

    var step = 600.0
    for (c in RULER_STEPS) {
        if (c * pps >= 70.0) {
            step = c
            break
        }
    }
    val hs = step / 2.0
    val tMin = t - cx / ppsPx
    val tMax = t + (w - cx) / ppsPx
    var k0 = floor(tMin / hs).toInt() - 1
    if (k0 < 0) k0 = 0
    val k1 = min(floor((total + 1e-6) / hs).toInt(), ceil(tMax / hs).toInt() + 1)
    val decimals = step < 1.0
    val fm = p.rulerText.fontMetrics
    val baseline = RULER_TOP * d - fm.ascent
    var k = k0
    while (k <= k1) {
        val s = k * hs
        if (s > total + 1e-6) break
        val x = cx + ((s - t) * ppsPx).toFloat()
        if (k % 2 == 0) {
            cv.drawText(Fmt.ruler(s, decimals), x, baseline, p.rulerText)
        } else {
            cv.drawCircle(x, (RULER_TOP + 6f + 1.5f) * d, 1.5f * d, p.dot)
        }
        k++
    }
}

private fun drawSegment(
    cv: android.graphics.Canvas,
    vm: EditorViewModel,
    p: TlPaints,
    g: Seg,
    tg: Seg,
    left: Float,
    right: Float,
    selected: Boolean,
    ppsPx: Double,
    screenW: Float
) {
    val d = p.d
    val top = SEG_TOP * d
    val bottom = top + SEG_H * d
    p.rect.set(left, top, right, bottom)

    cv.save()
    p.clip.reset()
    p.clip.addRoundRect(p.rect, SEG_R * d, SEG_R * d, Path.Direction.CW)
    cv.clipPath(p.clip)
    cv.drawRect(p.rect, if (g.fz) p.fzBg else p.segBg)

    val tilePx = Rules.TILE * d
    if (tg.fz) {
        val n = max(1, ceil(tg.len * ppsPx / tilePx).toInt())
        val bmp = vm.thumbAt(tg.src, tg.a)
        val seed = Math.round(tg.a * 3.0).toInt() + tg.src * 7
        var kFirst = floor((0f - left) / tilePx).toInt()
        if (kFirst < 0) kFirst = 0
        var kLast = ceil((screenW - left) / tilePx).toInt()
        if (kLast > n - 1) kLast = n - 1
        var k = kFirst
        while (k <= kLast) {
            val tx = left + (k * tilePx).toFloat()
            drawTile(cv, p, bmp, tx, top, tilePx.toFloat(), bottom, seed, true)
            k++
        }
    } else {
        val kRate = ppsPx / tg.sp
        val i0 = floor(tg.a * kRate / tilePx).toInt()
        val i1 = ceil(tg.b * kRate / tilePx).toInt()
        val vis0 = floor((0.0 - left + tg.a * kRate) / tilePx).toInt()
        val vis1 = ceil((screenW - left + tg.a * kRate) / tilePx).toInt()
        val from = max(i0, vis0)
        val to = min(i1 - 1, vis1)
        var i = from
        while (i <= to) {
            val tx = left + (i * tilePx - tg.a * kRate).toFloat()
            val srcTime = (i + 0.5) * tilePx / kRate
            drawTile(cv, p, vm.thumbAt(tg.src, srcTime), tx, top, tilePx.toFloat(), bottom, i + tg.src * 7, false)
            i++
        }
    }

    // pembatas 2 px di sisi kanan
    cv.drawRect(right - 2f * d, top, right, bottom, p.divider)

    // 1) Lencana status di kiri atas potongan (beku / nomor sumber / mundur / loop / kecepatan)
    val statusBadge: String? = if (g.fz) {
        "❄ Beku" + (if (g.sp != 1.0) " · " + Fmt.one(g.sp) + "x" else "")
    } else {
        val parts = ArrayList<String>(3)
        if (vm.sourceCount > 1) parts.add("V" + (g.src + 1))
        if (g.revMode == Seg.REV_LOOP) {
            parts.add("🔁 Loop")
        } else if (g.rev) {
            parts.add("⏪ Mundur")
        }
        if (g.sp != 1.0) parts.add(Fmt.one(g.sp) + "x")
        if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    val safeVisibleLeft = max(left + 4f * d, 84f * d)
    if (statusBadge != null && right - left >= 28f * d) {
        val tw = p.badgeText.measureText(statusBadge)
        val pillW = tw + 8f * d
        val fm = p.badgeText.fontMetrics
        val bx = min(max(left + 4f * d, safeVisibleLeft), max(left + 2f * d, right - pillW - 3f * d))
        val bt = top + 3f * d
        val bh = (fm.descent - fm.ascent) + 2f * d
        p.rect2.set(bx, bt, bx + pillW, bt + bh)
        cv.drawRoundRect(p.rect2, 5f * d, 5f * d, p.badgeBg)
        cv.drawText(statusBadge, bx + 4f * d, bt + 1f * d - fm.ascent, p.badgeText)
    }

    // 2) Label durasi pada setiap potongan di kiri bawah
    val segWidth = right - left
    if (segWidth >= 24f * d) {
        val durStr = Fmt.durLabel(tg.len)
        val dw = p.durBadgeText.measureText(durStr)
        val pillW = dw + 8f * d
        val fmD = p.durBadgeText.fontMetrics
        val bh = (fmD.descent - fmD.ascent) + 2f * d
        val bx = min(max(left + 4f * d, safeVisibleLeft), max(left + 2f * d, right - pillW - 3f * d))
        val bt = bottom - bh - 3f * d
        p.rect2.set(bx, bt, bx + pillW, bt + bh)
        cv.drawRoundRect(p.rect2, 5f * d, 5f * d, p.durBadgeBg)
        cv.drawText(durStr, bx + 4f * d, bt + 1f * d - fmD.ascent, p.durBadgeText)
    }

    cv.restore()

    if (selected) {
        p.rect2.set(left + 1f * d, top + 1f * d, right - 1f * d, bottom - 1f * d)
        cv.drawRoundRect(p.rect2, (SEG_R - 1f) * d, (SEG_R - 1f) * d, p.selBorder)
    }
}

private fun drawTile(
    cv: android.graphics.Canvas,
    p: TlPaints,
    bmp: Bitmap?,
    x: Float,
    top: Float,
    wTile: Float,
    bottom: Float,
    seed: Int,
    frozen: Boolean
) {
    if (bmp != null) {
        p.rect2.set(x, top, x + wTile, bottom)
        cv.drawBitmap(bmp, null, p.rect2, if (frozen) p.tileFz else p.tile)
    } else {
        p.ph.color = p.placeholder(seed)
        cv.drawRect(x, top, x + wTile, bottom, p.ph)
    }
}

private fun drawHandles(cv: android.graphics.Canvas, p: TlPaints, left: Float, right: Float) {
    val d = p.d
    val top = HD_TOP * d
    val bottom = top + HD_H * d
    val cy = (top + bottom) / 2f

    // kiri: batang vertikal 3 x 16
    p.rect.set(left - (HD_W + 2f) * d, top, left - 2f * d, bottom)
    cv.drawRoundRect(p.rect, HD_R * d, HD_R * d, p.handle)
    val cl = (p.rect.left + p.rect.right) / 2f
    p.rect2.set(cl - 1.5f * d, cy - 8f * d, cl + 1.5f * d, cy + 8f * d)
    cv.drawRoundRect(p.rect2, 2f * d, 2f * d, p.handleIcon)

    // kanan: segitiga menghadap kanan 5 x 10
    p.rect.set(right + 2f * d, top, right + (HD_W + 2f) * d, bottom)
    cv.drawRoundRect(p.rect, HD_R * d, HD_R * d, p.handle)
    val cr = (p.rect.left + p.rect.right) / 2f
    p.tri.reset()
    p.tri.moveTo(cr - 2.5f * d, cy - 5f * d)
    p.tri.lineTo(cr + 2.5f * d, cy)
    p.tri.lineTo(cr - 2.5f * d, cy + 5f * d)
    p.tri.close()
    cv.drawPath(p.tri, p.handleIcon)
}
