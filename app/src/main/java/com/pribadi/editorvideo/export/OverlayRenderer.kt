package com.pribadi.editorvideo.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.pribadi.editorvideo.model.TextOverlayConfig
import kotlin.math.max

/**
 * Menggambar dua layer teks overlay dengan kustomisasi kaya:
 *  - Pilihan font (Tebal, Serif, Monospace, Miring, Medium)
 *  - Ukuran font (sizeScale)
 *  - Posisi bebas X & Y (posX, posY) + perataan (Kiri, Tengah, Kanan)
 *  - Warna isi teks (fillColor)
 *  - Warna & ketebalan outline (strokeColor, strokeScale)
 *  - Kotak latar belakang membulat (bgColor)
 *  - Efek bayangan/glow (shadow)
 *  - Mendukung teks multi-baris
 */
object OverlayRenderer {

    val FONT_LABELS: List<String> = listOf("Tebal", "Serif", "Mono", "Miring", "Medium")

    private val typefaces: Array<Typeface> = arrayOf(
        Typeface.create(Typeface.DEFAULT, 900, false),
        Typeface.create(Typeface.SERIF, Typeface.BOLD),
        Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
        Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC),
        Typeface.create(Typeface.SANS_SERIF, 600, false)
    )

    private val glowDefault: Int = Color.parseColor("#AA000000")

    private fun fontSize(w: Float, cfg: TextOverlayConfig, isTopLayer: Boolean): Float {
        val base = if (isTopLayer) w * 0.095f else w * 0.10f
        return (base * cfg.sizeScale.coerceIn(0.35f, 2.5f)).coerceAtLeast(10f)
    }

    private fun strokeWidth(fs: Float, cfg: TextOverlayConfig): Float {
        if (cfg.strokeColor == 0 || cfg.strokeScale <= 0.01f) return 0f
        return fs * 0.18f * cfg.strokeScale.coerceIn(0f, 2.5f)
    }

    private fun makePaint(fs: Float, cfg: TextOverlayConfig): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = typefaces.getOrElse(cfg.fontIndex) { typefaces[0] }
        p.textSize = fs
        p.textAlign = when (cfg.alignIndex) {
            0 -> Paint.Align.LEFT
            2 -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        return p
    }

    private fun drawOne(canvas: Canvas, w: Float, h: Float, cfg: TextOverlayConfig, isTopLayer: Boolean) {
        if (cfg.text.isEmpty()) return
        val lines = cfg.text.split("\n")
        val fs = fontSize(w, cfg, isTopLayer)
        val sw = strokeWidth(fs, cfg)
        val fp = makePaint(fs, cfg)
        val fm = fp.fontMetrics
        val lineH = (fm.descent - fm.ascent) * 1.12f
        val totalTextH = lineH * lines.size

        var maxLineW = 0f
        for (line in lines) {
            maxLineW = max(maxLineW, fp.measureText(line))
        }

        val cx = (cfg.posX.coerceIn(0.05f, 0.95f)) * w
        val cy = (cfg.posY.coerceIn(0.05f, 0.95f)) * h
        val firstBaseline = cy - totalTextH / 2f - fm.ascent

        val anchorX = when (cfg.alignIndex) {
            0 -> cx - maxLineW / 2f
            2 -> cx + maxLineW / 2f
            else -> cx
        }

        // 1. Kotak latar belakang (bila dipilih)
        if (cfg.bgColor != 0) {
            val padX = fs * 0.38f + sw * 0.5f
            val padY = fs * 0.22f + sw * 0.5f
            val boxRect = RectF(
                cx - maxLineW / 2f - padX,
                cy - totalTextH / 2f - padY,
                cx + maxLineW / 2f + padX,
                cy + totalTextH / 2f + padY
            )
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = cfg.bgColor
            }
            val r = fs * 0.32f
            canvas.drawRoundRect(boxRect, r, r, bgPaint)
        }

        // 2. Outline / Stroke + Shadow
        if (sw > 0.5f) {
            val sp = makePaint(fs, cfg).apply {
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                strokeWidth = sw
                color = cfg.strokeColor
                if (cfg.shadow) {
                    setShadowLayer(w * 0.022f, 0f, w * 0.004f, glowDefault)
                }
            }
            for (i in lines.indices) {
                canvas.drawText(lines[i], anchorX, firstBaseline + i * lineH, sp)
            }
        }

        // 3. Isi Teks (Fill)
        fp.style = Paint.Style.FILL
        fp.color = cfg.fillColor
        if (sw <= 0.5f && cfg.shadow) {
            fp.setShadowLayer(w * 0.022f, 0f, w * 0.004f, glowDefault)
        }
        for (i in lines.indices) {
            canvas.drawText(lines[i], anchorX, firstBaseline + i * lineH, fp)
        }
    }

    /** Menggambar kedua konfigurasi teks pada kanvas berukuran w x h piksel. */
    fun draw(canvas: Canvas, w: Float, h: Float, style1: TextOverlayConfig, style2: TextOverlayConfig) {
        drawOne(canvas, w, h, style1, true)
        drawOne(canvas, w, h, style2, false)
    }

    /** Kotak pembatas teks untuk deteksi ketukan & geser (drag) di pratinjau. */
    fun bounds(w: Float, h: Float, cfg: TextOverlayConfig, isTopLayer: Boolean): RectF {
        val lines = cfg.text.split("\n")
        val fs = fontSize(w, cfg, isTopLayer)
        val sw = strokeWidth(fs, cfg)
        val p = makePaint(fs, cfg)
        var maxLineW = 0f
        for (line in lines) {
            maxLineW = max(maxLineW, p.measureText(line))
        }
        val tw = maxLineW + sw + fs * 0.6f
        val fm = p.fontMetrics
        val lineH = (fm.descent - fm.ascent) * 1.12f
        val th = lineH * lines.size + fs * 0.4f
        val cx = cfg.posX.coerceIn(0.05f, 0.95f) * w
        val cy = cfg.posY.coerceIn(0.05f, 0.95f) * h
        return RectF(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f)
    }

    /** Mengembalikan 1 bila mengenai teks 1, 2 bila mengenai teks 2, 0 bila tidak mengenai keduanya. */
    fun hitTest(
        w: Float,
        h: Float,
        style1: TextOverlayConfig,
        style2: TextOverlayConfig,
        px: Float,
        py: Float,
        pad: Float
    ): Int {
        if (style1.isNotEmpty) {
            val r = bounds(w, h, style1, true)
            if (px >= r.left - pad && px <= r.right + pad && py >= r.top - pad && py <= r.bottom + pad) return 1
        }
        if (style2.isNotEmpty) {
            val r = bounds(w, h, style2, false)
            if (px >= r.left - pad && px <= r.right + pad && py >= r.top - pad && py <= r.bottom + pad) return 2
        }
        return 0
    }

    /** Membuat bitmap transparan berukuran w x h berisi kedua teks (untuk overlay ekspor). */
    fun render(w: Int, h: Int, style1: TextOverlayConfig, style2: TextOverlayConfig): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c, w.toFloat(), h.toFloat(), style1, style2)
        return bmp
    }
}
