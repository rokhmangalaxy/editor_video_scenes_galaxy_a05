package com.pribadi.editorvideo.export

import com.pribadi.editorvideo.model.Seg
import com.pribadi.editorvideo.model.TextOverlayConfig
import com.pribadi.editorvideo.model.VideoInfo

enum class ExportMode { VIDEO, ALL, CURRENT, BY_TAG }

/** Bentuk keluaran saat mengekspor berdasarkan Tag (ExportMode.BY_TAG). */
enum class ByTagExportStyle(val label: String) {
    MERGE_SINGLE("Gabung 1 Video"),
    SPLIT_CLIPS("Pisah per Klip"),
    PER_TAG("1 File per Tag")
}

/** Spesifikasi satu sumber video yang diimpor. */
data class SourceSpec(
    val uri: String,
    val info: VideoInfo
)

/** Semua yang dibutuhkan untuk mengekspor (snapshot dari editor saat tombol Mulai Ekspor ditekan). */
data class ExportRequest(
    val sourceUri: String,
    val info: VideoInfo,
    val sources: List<SourceSpec>,
    val segs: List<Seg>,
    val currentIndex: Int,
    val rot: Int,
    val flip: Boolean,
    val style1: TextOverlayConfig,
    val style2: TextOverlayConfig,
    val qIdx: Int,
    val fIdx: Int,
    val mode: ExportMode,
    val selectedExportTags: List<String> = emptyList(),
    val byTagStyle: ByTagExportStyle = ByTagExportStyle.MERGE_SINGLE
)

/** Satu file keluaran: sufiks nama file dan potongan yang dimuat. */
data class OutputJob(val suffix: String, val segs: List<Seg>)
