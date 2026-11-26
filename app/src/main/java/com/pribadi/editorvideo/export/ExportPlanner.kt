package com.pribadi.editorvideo.export

import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.model.VideoInfo

/** Hasil perencanaan ekspor: ukuran, fps, bitrate, dan catatan untuk pengguna. */
data class ExportPlan(
    val width: Int,
    val height: Int,
    /** Indeks kualitas yang benar-benar dipakai (bisa lebih rendah dari pilihan bila encoder tidak mendukung). */
    val qualityIndex: Int,
    /** FPS efektif (tidak pernah lebih tinggi dari FPS sumber). */
    val fps: Double,
    /** True bila frame perlu dibuang agar FPS turun ke target. */
    val dropFrames: Boolean,
    val bitrate: Int,
    /** Pesan untuk pengguna (fallback resolusi, batas FPS, dll). */
    val notes: List<String>
)

object ExportPlanner {

    /**
     * Merencanakan ekspor.
     * supports(w, h) = apakah encoder H.264 perangkat mendukung ukuran tersebut.
     */
    fun plan(
        info: VideoInfo,
        rot: Int,
        qIdx: Int,
        fIdx: Int,
        supports: (Int, Int) -> Boolean
    ): ExportPlan {
        val notes = ArrayList<String>()
        val labels = ExportMath.QUALITY_LABELS
        val values = ExportMath.QUALITY_VALUES
        val qi = qIdx.coerceIn(0, values.size - 1)

        // --- resolusi: pakai pilihan pengguna; bila encoder tidak mendukung, turun ke yang tertinggi yang didukung.
        val wanted = ExportMath.outDims(info.dispW, info.dispH, rot, values[qi])
        var used = qi
        var dims = wanted
        if (!supports(wanted[0], wanted[1])) {
            var found = -1
            var k = qi - 1
            while (k >= 0) {
                val d = ExportMath.outDims(info.dispW, info.dispH, rot, values[k])
                if (supports(d[0], d[1])) {
                    found = k
                    break
                }
                k--
            }
            if (found >= 0) {
                val d = ExportMath.outDims(info.dispW, info.dispH, rot, values[found])
                notes.add(
                    "Encoder perangkat tidak mendukung " + labels[qi] + " (" + wanted[0] + "×" + wanted[1] +
                        "). Otomatis memakai " + labels[found] + " (" + d[0] + "×" + d[1] + ")."
                )
                used = found
                dims = d
            } else {
                notes.add(
                    "Encoder perangkat mungkin tidak mendukung " + labels[qi] + " (" + wanted[0] + "×" +
                        wanted[1] + "); ukuran akan disesuaikan otomatis."
                )
            }
        }

        // --- FPS: hanya bisa diturunkan.
        val fpsIdx = fIdx.coerceIn(0, ExportMath.FPS_VALUES.size - 1)
        val target = ExportMath.FPS_VALUES[fpsIdx].toDouble()
        val src = info.fps.toDouble()
        val eff: Double
        var drop = false
        if (target > src + 0.5) {
            eff = src
            notes.add(
                "FPS sumber hanya " + Fmt.fps(src) + ". FPS tidak bisa dinaikkan, jadi hasil tetap " +
                    Fmt.fps(src) + " fps."
            )
        } else {
            eff = target
            drop = target < src - 0.5
        }

        val br = ExportMath.bitrate(dims[0], dims[1], eff).toInt()
        return ExportPlan(dims[0], dims[1], used, eff, drop, br, notes)
    }
}
