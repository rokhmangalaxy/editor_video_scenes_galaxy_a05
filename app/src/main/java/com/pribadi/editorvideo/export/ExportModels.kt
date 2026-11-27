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

/**
 * Acuan Kualitas & FPS saat timeline berisi campuran klip berbeda (mis. Video Asli 1080p 30/60fps
 * dicampur klip Hasil AI 480p 16/24fps).
 */
enum class ExportSpecRef(val label: String, val sub: String) {
    MAIN_ORIGINAL(
        "Ikuti Video Utama",
        "Kualitas & FPS mengikuti video asli utama; klip AI disesuaikan otomatis ke ukuran utama."
    ),
    HIGHEST(
        "Kualitas Tertinggi",
        "Mengambil resolusi & FPS tertinggi di antara seluruh klip yang ikut diekspor."
    ),
    LOWEST_AI(
        "Terendah / Sesuai AI",
        "Mengikuti resolusi & FPS terendah (klip AI tidak di-upscale, ukuran berkas lebih hemat)."
    ),
    PER_CLIP_NATIVE(
        "Asli Tiap Klip",
        "Khusus ekspor terpisah: tiap file memakai resolusi & FPS asli masing-masing klip."
    )
}

/** Cara menyamakan bingkai ketika rasio klip AI atau scene berotasi berbeda dari kanvas utama. */
enum class ExportScaleFit(val label: String, val sub: String) {
    FIT_LETTERBOX(
        "Fit Utuh",
        "Seluruh gambar utuh tanpa terpotong (bar hitam tipis bila rasio berbeda)."
    ),
    FILL_CROP(
        "Isi Penuh (Crop)",
        "Memenuhi layar penuh tanpa bar hitam (memotong tepi bila rasio berbeda)."
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
    val byTagStyle: ByTagExportStyle = ByTagExportStyle.MERGE_SINGLE,
    val specRef: ExportSpecRef = ExportSpecRef.MAIN_ORIGINAL,
    val scaleFit: ExportScaleFit = ExportScaleFit.FIT_LETTERBOX
)

/** Satu file keluaran: sufiks nama file dan potongan yang dimuat. */
data class OutputJob(val suffix: String, val segs: List<Seg>)
