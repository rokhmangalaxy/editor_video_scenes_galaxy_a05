package com.pribadi.editorvideo.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.pribadi.editorvideo.model.VideoInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Utilitas mengambil frame dari video (presisi tinggi untuk AI seek, thumbnail, dan frame beku). */
object VideoFrames {

    /**
     * Memastikan bitmap berorientasi tegak sesuai tampilan video (memperhitungkan rotasi metadata).
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

    /**
     * Mengambil frame presisi tinggi tepat pada [timeUs] (mikrodetik) menggunakan MediaExtractor + MediaCodec
     * (decode maju dari keyframe sebelumnya sampai mencapai PTS target), sehingga TIDAK meleset ke keyframe jauh.
     * Bila codec perangkat gagal diinisialisasi, otomatis fallback ke MediaMetadataRetriever OPTION_CLOSEST.
     */
    fun grabExactFrame(context: Context, uri: Uri, timeUs: Long, info: VideoInfo): Bitmap? {
        val maxUs = max(0L, (info.durationMs - 1L) * 1000L)
        val clampedUs = timeUs.coerceIn(0L, maxUs)

        val exactViaCodec = try {
            decodeExactFrameViaMediaCodec(context, uri, clampedUs, info)
        } catch (e: Throwable) {
            null
        }
        if (exactViaCodec != null) return exactViaCodec

        return grabFrame(context, uri, clampedUs / 1000L, info)
    }

    private fun decodeExactFrameViaMediaCodec(
        context: Context,
        uri: Uri,
        targetUs: Long,
        info: VideoInfo
    ): Bitmap? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var videoTrack = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    trackFormat = f
                    break
                }
            }
            if (videoTrack < 0 || trackFormat == null) return null

            extractor.selectTrack(videoTrack)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
            trackFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            val fps = if (info.fps > 1f) info.fps else 30f
            val halfFrameUs = Math.round(500_000.0 / fps.toDouble()).coerceIn(8_000L, 25_000L)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var fallbackBitmap: Bitmap? = null
            var safetyLoops = 0

            while (!outputDone && safetyLoops < 600) {
                safetyLoops++
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(8_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(bufInfo, 8_000L)
                if (outIdx >= 0) {
                    val isEos = (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val pts = bufInfo.presentationTimeUs
                    val reachedTarget = bufInfo.size > 0 && pts >= targetUs - halfFrameUs
                    val nearTargetForFallback = bufInfo.size > 0 && (isEos || abs(pts - targetUs) <= 120_000L)

                    if (reachedTarget || nearTargetForFallback) {
                        val img = decoder.getOutputImage(outIdx)
                        if (img != null) {
                            try {
                                val bmp = yuv420ImageToBitmap(img, info)
                                if (reachedTarget) {
                                    fallbackBitmap?.recycle()
                                    decoder.releaseOutputBuffer(outIdx, false)
                                    return bmp
                                } else {
                                    fallbackBitmap?.recycle()
                                    fallbackBitmap = bmp
                                }
                            } finally {
                                img.close()
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (isEos) {
                        outputDone = true
                    }
                }
            }
            return fallbackBitmap
        } finally {
            try {
                decoder?.stop()
            } catch (e: Exception) {
                // diabaikan
            }
            try {
                decoder?.release()
            } catch (e: Exception) {
                // diabaikan
            }
            try {
                extractor.release()
            } catch (e: Exception) {
                // diabaikan
            }
        }
    }

    private fun yuv420ImageToBitmap(image: Image, info: VideoInfo): Bitmap {
        val crop = image.cropRect
        val cropLeft = crop.left.coerceAtLeast(0)
        val cropTop = crop.top.coerceAtLeast(0)
        val w = (if (crop.width() > 0) crop.width() else image.width).coerceAtLeast(2)
        val h = (if (crop.height() > 0) crop.height() else image.height).coerceAtLeast(2)

        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRowStride = planes[0].rowStride
        val yPixStride = planes[0].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixStride = planes[2].pixelStride

        val pixels = IntArray(w * h)
        var outIdx = 0
        for (row in 0 until h) {
            val yyRow = (cropTop + row) * yRowStride
            val uvRow = (cropTop + row) shr 1
            val uRowOff = uvRow * uRowStride
            val vRowOff = uvRow * vRowStride
            for (col in 0 until w) {
                val x = cropLeft + col
                val yVal = (yBuf.get(yyRow + x * yPixStride).toInt() and 0xFF)
                val uvCol = x shr 1
                val uVal = (uBuf.get(uRowOff + uvCol * uPixStride).toInt() and 0xFF) - 128
                val vVal = (vBuf.get(vRowOff + uvCol * vPixStride).toInt() and 0xFF) - 128

                var r = yVal + ((1436 * vVal) shr 10)
                var g = yVal - ((352 * uVal + 731 * vVal) shr 10)
                var b = yVal + ((1815 * uVal) shr 10)
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                pixels[outIdx++] = (-0x1000000) or (r shl 16) or (g shl 8) or b
            }
        }

        val rawBmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val rotMeta = ((info.rotation % 360) + 360) % 360
        return if (rotMeta != 0) {
            val m = Matrix()
            m.postRotate(rotMeta.toFloat())
            val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
            if (rotated !== rawBmp) rawBmp.recycle()
            rotated
        } else {
            rawBmp
        }
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
        val longSide = min(max(src.width, src.height), Int.MAX_VALUE)
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
