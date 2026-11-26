package com.pribadi.editorvideo.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.pribadi.editorvideo.model.VideoInfo

/** Utilitas mengambil frame dari video (untuk thumbnail dan frame beku). */
object VideoFrames {

    /**
     * Memastikan bitmap dari MediaMetadataRetriever berorientasi tegak sesuai tampilan video.
     * Sebagian perangkat mengembalikan frame tanpa rotasi metadata; bila orientasi bitmap
     * tidak cocok dengan orientasi tampilan, bitmap diputar sesuai metadata.
     */
    fun upright(bmp: Bitmap, info: VideoInfo): Bitmap {
        if (info.rotation % 180 != 0) {
            val wantPortrait = info.dispH > info.dispW
            val isPortrait = bmp.height > bmp.width
            if (wantPortrait != isPortrait) {
                val m = Matrix()
                m.postRotate(info.rotation.toFloat())
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) bmp.recycle()
                return rotated
            }
        }
        return bmp
    }

    /** Mengambil satu frame penuh pada waktu tertentu (milidetik). Panggil dari thread IO. */
    fun grabFrame(context: Context, uri: Uri, timeMs: Long, info: VideoInfo): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            var t = timeMs
            val last = info.durationMs - 1L
            if (last > 0L && t > last) t = last
            if (t < 0L) t = 0L
            val us = t * 1000L
            var raw: Bitmap? = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
            if (raw == null) raw = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (raw == null) raw = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
            if (raw == null) return null
            return upright(raw, info)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // diabaikan
            }
        }
    }

    /** Memperkecil bitmap bila sisi terpanjangnya melebihi batas. Bitmap lama di-recycle. */
    fun limitLongSide(src: Bitmap, maxSide: Int): Bitmap {
        val longSide = if (src.width > src.height) src.width else src.height
        if (longSide <= maxSide) return src
        val scale = maxSide.toFloat() / longSide.toFloat()
        var nw = Math.round(src.width * scale)
        var nh = Math.round(src.height * scale)
        if (nw < 2) nw = 2
        if (nh < 2) nh = 2
        val out = Bitmap.createScaledBitmap(src, nw, nh, true)
        if (out !== src) src.recycle()
        return out
    }

    /** Memotong bagian tengah (cover) lalu menskalakan ke w x h. Bitmap sumber di-recycle. */
    fun coverCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcRatio = src.width.toFloat() / src.height.toFloat()
        val dstRatio = w.toFloat() / h.toFloat()
        var sw = src.width
        var sh = src.height
        var sx = 0
        var sy = 0
        if (srcRatio > dstRatio) {
            sw = Math.round(sh * dstRatio)
            sx = (src.width - sw) / 2
        } else {
            sh = Math.round(sw / dstRatio)
            sy = (src.height - sh) / 2
        }
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, w, h), paint)
        src.recycle()
        return out
    }
}
