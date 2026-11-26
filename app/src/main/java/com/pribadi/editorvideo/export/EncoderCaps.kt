package com.pribadi.editorvideo.export

import androidx.media3.common.MimeTypes
import androidx.media3.transformer.EncoderUtil
import kotlin.math.max
import kotlin.math.min

/** Memeriksa kemampuan encoder H.264 perangkat (hasil di-cache). */
object EncoderCaps {

    private val cache = HashMap<Long, Boolean>()

    /**
     * Apakah ada encoder H.264 yang mendukung ukuran w x h.
     * Transformer mengenkode dalam orientasi landscape (lebar >= tinggi), jadi ukuran diuji dengan cara yang sama.
     */
    @Synchronized
    fun supportsH264(w: Int, h: Int): Boolean {
        val lw = max(w, h)
        val lh = min(w, h)
        val key = lw.toLong() * 100000L + lh.toLong()
        val cached = cache[key]
        if (cached != null) return cached
        var ok = false
        var queried = false
        try {
            val encoders = EncoderUtil.getSupportedEncoders(MimeTypes.VIDEO_H264)
            for (info in encoders) {
                queried = true
                try {
                    if (EncoderUtil.isSizeSupported(info, MimeTypes.VIDEO_H264, lw, lh)) {
                        ok = true
                        break
                    }
                } catch (e: Exception) {
                    // encoder ini tidak bisa diperiksa, coba yang lain
                }
            }
        } catch (e: Exception) {
            queried = false
        }
        // Bila perangkat tidak bisa ditanya sama sekali, jangan memblokir: Transformer punya fallback sendiri.
        if (!queried) ok = true
        cache[key] = ok
        return ok
    }
}
