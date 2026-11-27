package com.pribadi.editorvideo.export

import com.pribadi.editorvideo.model.Seg
import com.pribadi.editorvideo.model.jsRound
import kotlin.math.max
import kotlin.math.min

/** Pilihan dan rumus ekspor — mendukung resolusi & FPS asli video serta ekspor berdasarkan Tag. */
object ExportMath {

    /** Nilai indeks khusus untuk memakai Kualitas / FPS asli sesuai video sumber. */
    const val ORIGINAL_IDX: Int = -1

    val QUALITY_LABELS: List<String> = listOf("144p", "240p", "360p", "480p", "720p", "1080p", "1440p", "4K")

    /** Angka kualitas = sisi pendek video (piksel). */
    val QUALITY_VALUES: IntArray = intArrayOf(144, 240, 360, 480, 720, 1080, 1440, 2160)

    val FPS_VALUES: IntArray = intArrayOf(15, 24, 30, 60)

    const val DEFAULT_QUALITY: Int = 5
    const val DEFAULT_FPS: Int = 2

    /** Bulatkan ke genap terdekat, minimal 2 (ev() di HTML). */
    fun even(n: Double): Int = max(2.0, jsRound(n / 2.0) * 2.0).toInt()

    /** Ukuran asli tampilan video setelah memperhitungkan rotasi pengguna (dibulatkan ke genap). */
    fun origDims(dispW: Int, dispH: Int, rot: Int): IntArray {
        val r90 = rot % 180 != 0
        val rw = (if (r90) dispH else dispW).toDouble()
        val rh = (if (r90) dispW else dispH).toDouble()
        return intArrayOf(even(rw), even(rh))
    }

    /**
     * Ukuran keluaran [lebar, tinggi] setelah rotasi pengguna (outDims() di HTML).
     * dispW/dispH = ukuran tampilan video sumber (sudah memperhitungkan metadata rotasi).
     */
    fun outDims(dispW: Int, dispH: Int, rot: Int, shortSide: Int): IntArray {
        val r90 = rot % 180 != 0
        val rw = (if (r90) dispH else dispW).toDouble()
        val rh = (if (r90) dispW else dispH).toDouble()
        val sc = shortSide.toDouble() / min(rw, rh)
        return intArrayOf(even(rw * sc), even(rh * sc))
    }

    /** Bitrate video (bit/detik): min(60 Mbps, max(300 kbps, w*h*fps*0.09)). */
    fun bitrate(w: Int, h: Int, fps: Double): Double =
        min(60e6, max(3e5, w.toDouble() * h.toDouble() * fps * 0.09))

    /** Perkiraan ukuran file (MB) untuk durasi tertentu. */
    fun estimateMB(w: Int, h: Int, fps: Double, durSec: Double): Double =
        bitrate(w, h, fps) * durSec / 8.0 / 1e6

    private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

    private fun safeSlug(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^a-zA-Z0-9_-]+"), "_").trim('_')
        return cleaned.ifEmpty { "tag" }.take(24)
    }

    /** Menyusun daftar file keluaran sesuai mode ekspor (termasuk Ekspor Berdasarkan Tag). */
    fun buildJobs(
        mode: ExportMode,
        segs: List<Seg>,
        sel: Int,
        selectedTags: List<String> = emptyList(),
        byTagStyle: ByTagExportStyle = ByTagExportStyle.MERGE_SINGLE
    ): List<OutputJob> {
        if (segs.isEmpty()) return emptyList()
        return when (mode) {
            ExportMode.VIDEO -> listOf(OutputJob("", segs))
            ExportMode.ALL -> segs.mapIndexed { i, s -> OutputJob(pad2(i + 1), listOf(s)) }
            ExportMode.CURRENT -> {
                val i = if (sel in segs.indices) sel else 0
                listOf(OutputJob("potongan" + (i + 1), listOf(segs[i])))
            }
            ExportMode.BY_TAG -> {
                val activeTags = if (selectedTags.isNotEmpty()) {
                    selectedTags
                } else {
                    segs.flatMap { it.tags }.distinct()
                }
                if (activeTags.isEmpty()) return emptyList()

                fun matchesAny(g: Seg): Boolean =
                    g.tags.any { t -> activeTags.any { st -> st.equals(t, ignoreCase = true) } }

                when (byTagStyle) {
                    ByTagExportStyle.MERGE_SINGLE -> {
                        val matched = segs.filter { matchesAny(it) }
                        if (matched.isEmpty()) {
                            emptyList()
                        } else {
                            val suffix = if (activeTags.size == 1) {
                                "tag_" + safeSlug(activeTags.first())
                            } else {
                                "tag_pilihan"
                            }
                            listOf(OutputJob(suffix, matched))
                        }
                    }

                    ByTagExportStyle.SPLIT_CLIPS -> {
                        val list = ArrayList<OutputJob>()
                        for ((idx, g) in segs.withIndex()) {
                            if (matchesAny(g)) {
                                val firstMatch = g.tags.firstOrNull { t ->
                                    activeTags.any { st -> st.equals(t, ignoreCase = true) }
                                } ?: g.tags.firstOrNull() ?: "tag"
                                list.add(
                                    OutputJob(
                                        "tag_" + safeSlug(firstMatch) + "_" + pad2(idx + 1),
                                        listOf(g)
                                    )
                                )
                            }
                        }
                        list
                    }

                    ByTagExportStyle.PER_TAG -> {
                        val list = ArrayList<OutputJob>()
                        for (tag in activeTags) {
                            val tagSegs = segs.filter { g ->
                                g.tags.any { it.equals(tag, ignoreCase = true) }
                            }
                            if (tagSegs.isNotEmpty()) {
                                list.add(OutputJob("tag_" + safeSlug(tag), tagSegs))
                            }
                        }
                        list
                    }
                }
            }
        }
    }
}
