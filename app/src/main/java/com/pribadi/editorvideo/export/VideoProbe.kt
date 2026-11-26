package com.pribadi.editorvideo.export

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.pribadi.editorvideo.model.VideoInfo

object VideoProbe {

    /** Membaca info video. Mengembalikan null bila video tidak bisa dibaca. Panggil dari thread IO. */
    fun probe(context: Context, uri: Uri): VideoInfo? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rawRot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val rot = ((rawRot % 360) + 360) % 360
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
            var fps = 0f
            if (frames != null && frames > 1 && dur > 0) {
                fps = frames * 1000f / dur
            }
            if (fps < 1f) fps = extractorFps(context, uri)
            if (fps < 1f) fps = 30f
            return VideoInfo(w, h, rot, dur, fps, hasAudio)
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

    private fun extractorFps(context: Context, uri: Uri): Float {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) continue
                return try {
                    f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                } catch (e: Exception) {
                    try {
                        f.getFloat(MediaFormat.KEY_FRAME_RATE)
                    } catch (e2: Exception) {
                        0f
                    }
                }
            }
        } catch (e: Exception) {
            // diabaikan
        } finally {
            try {
                ex.release()
            } catch (e: Exception) {
                // diabaikan
            }
        }
        return 0f
    }
}
