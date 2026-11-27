package com.pribadi.editorvideo.export

import com.pribadi.editorvideo.model.Seg
import com.pribadi.editorvideo.model.TextOverlayConfig
import com.pribadi.editorvideo.model.VideoInfo

enum class ExportMode { VIDEO, ALL, CURRENT, BY_TAG }

/** Bentuk keluaran saat mengekspor berdasarkan Tag (ExportMode.BY_TAG). */
enum class ByTagExportStyle(val label: String, val sub: String) {
    MERGE_SINGLE(
        "Gabung 1 Video",
        "Seluruh klip bertag terpilih digabung urut timeline menjadi 1 berkas MP4."
    ),
    SPLIT_CLIPS(
        "Pisah per Klip",
        "Setiap klip bertag terpilih diekspor menjadi berkas MP4 terpisah."
    ),
    PER_TAG(
        "1 File per Tag",
        "Setiap Tag terpilih menghasilkan 1 berkas MP4 gabungan klip tag tersebut."
    )
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
