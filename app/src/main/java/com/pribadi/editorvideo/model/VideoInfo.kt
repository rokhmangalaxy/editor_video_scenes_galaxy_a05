package com.pribadi.editorvideo.model

/** Informasi video sumber. width/height = ukuran tersimpan; rotation = putaran metadata (derajat searah jarum jam). */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationMs: Long,
    val fps: Float,
    val hasAudio: Boolean
) {
    /** Lebar tampilan (sudah memperhitungkan rotasi metadata). */
    val dispW: Int get() = if (rotation % 180 == 0) width else height

    /** Tinggi tampilan (sudah memperhitungkan rotasi metadata). */
    val dispH: Int get() = if (rotation % 180 == 0) height else width
}
