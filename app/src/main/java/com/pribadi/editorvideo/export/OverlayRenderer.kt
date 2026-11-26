package com.pribadi.editorvideo.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Menggambar dua teks overlay (atas dan bawah) dengan gaya seperti di HTML.
 * Fungsi yang sama dipakai oleh preview dan ekspor, sehingga hasil ekspor sama dengan yang terlihat di layar.
 *
 * Gaya (dari CSS):
 *  - teks atas : kuning #ffe45c, outline #4a2fb0 (0,2 x ukuran font), glow #7b5cff, top 7%, font 10% lebar
 *  - teks bawah: putih, outline #f2c22a (0,17 x ukuran font), bottom 8%, font 10,8% lebar
 */
object OverlayRenderer {

    private val typeface: Typeface = Typeface.create(Typeface.DEFAULT, 900, false)

    private val fillTop: Int = Color.parseColor("#FFE45C")
    private val strokeTop: Int = Color.parseColor("#4A2FB0")
    private val glowTop: Int = Color.parseColor("#7B5CFF")
    private val fillBottom: Int = Color.WHITE
    private val strokeBottom: Int = Color.parseColor("#F2C22A")

    private fun fontSize(w: Float, top: Boolean): Float = if (top) w * 0.1f else w * 0.108f

    private fun strokeWidth(fs: Float, top: Boolean): Float = fs * (if (top) 0.2f else 0.17f)

    private fun makePaint(fs: Float): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = typeface
        p.textSize = fs
        p.textAlign = Paint.Align.CENTER
        return p
    }

    /** Posisi garis dasar teks: teks atas menempel 7% dari atas, teks bawah 8% dari bawah. */
    private fun baselineY(p: Paint, h: Float, top: Boolean): Float {
        val fm = p.fontMetrics
        return if (top) h * 0.07f - fm.ascent else h * 0.92f - fm.descent
    }

    private fun drawOne(canvas: Canvas, w: Float, h: Float, text: String, top: Boolean) {
        if (text.isEmpty()) return
        val fs = fontSize(w, top)
        val x = w / 2f

        // Urutan lukis seperti CSS "paint-order: stroke fill": outline dulu, lalu isi di atasnya.
        val sp = makePaint(fs)
        sp.style = Paint.Style.STROKE
        sp.strokeJoin = Paint.Join.ROUND
        sp.strokeWidth = strokeWidth(fs, top)
        sp.color = if (top) strokeTop else strokeBottom
        if (top) sp.setShadowLayer(w * 0.025f, 0f, 0f, glowTop)
        val y = baselineY(sp, h, top)
        canvas.drawText(text, x, y, sp)

        val fp = makePaint(fs)
        fp.style = Paint.Style.FILL
        fp.color = if (top) fillTop else fillBottom
        canvas.drawText(text, x, y, fp)
    }

    /** Menggambar kedua teks pada kanvas berukuran w x h piksel. */
    fun draw(canvas: Canvas, w: Float, h: Float, text1: String, text2: String) {
        drawOne(canvas, w, h, text1, true)
        drawOne(canvas, w, h, text2, false)
    }

    /** Kotak pembatas teks (untuk deteksi ketukan). */
    fun bounds(w: Float, h: Float, text: String, top: Boolean): RectF {
        val fs = fontSize(w, top)
        val p = makePaint(fs)
        val tw = p.measureText(text) + strokeWidth(fs, top)
        val fm = p.fontMetrics
        val y = baselineY(p, h, top)
        val x = w / 2f
        return RectF(x - tw / 2f, y + fm.ascent, x + tw / 2f, y + fm.descent)
    }

    /** Mengembalikan 1 bila ketukan mengenai teks atas, 2 bila teks bawah, 0 bila tidak ada. */
    fun hitTest(w: Float, h: Float, text1: String, text2: String, px: Float, py: Float, pad: Float): Int {
        if (text1.isNotEmpty()) {
            val r = bounds(w, h, text1, true)
            if (px >= r.left - pad && px <= r.right + pad && py >= r.top - pad && py <= r.bottom + pad) return 1
        }
        if (text2.isNotEmpty()) {
            val r = bounds(w, h, text2, false)
            if (px >= r.left - pad && px <= r.right + pad && py >= r.top - pad && py <= r.bottom + pad) return 2
        }
        return 0
    }

    /** Membuat bitmap transparan berukuran w x h berisi kedua teks (untuk overlay ekspor). */
    fun render(w: Int, h: Int, text1: String, text2: String): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c, w.toFloat(), h.toFloat(), text1, text2)
        return bmp
    }
}
