package com.pribadi.editorvideo.export

import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.model.VideoInfo

/** Hasil perencanaan ekspor: ukuran, fps, bitrate, dan catatan untuk pengguna. */
data class ExportPlan(
    val width: Int,
    val height: Int,
    /** Indeks kualitas yang benar-benar dipakai (-1 bila memakai ukuran asli video sumber). */
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
     * Mendukung ukuran & FPS asli video sumber ketika [qIdx] / [fIdx] bernilai [ExportMath.ORIGINAL_IDX].
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

        val used: Int
        val dims: IntArray

        if (qIdx == ExportMath.ORIGINAL_IDX) {
            val wantedOrig = ExportMath.origDims(info.dispW, info.dispH, rot)
            if (supports(wantedOrig[0], wantedOrig[1])) {
                used = ExportMath.ORIGINAL_IDX
                dims = wantedOrig
            } else {
                var found = -1
                var k = values.size - 1
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
                        "Encoder perangkat tidak mendukung resolusi asli (" + wantedOrig[0] + "×" + wantedOrig[1] +
                            "). Otomatis memakai " + labels[found] + " (" + d[0] + "×" + d[1] + ")."
                    )
                    used = found
                    dims = d
                } else {
                    used = ExportMath.ORIGINAL_IDX
                    dims = wantedOrig
                }
            }
        } else {
            val qi = qIdx.coerceIn(0, values.size - 1)
            val wanted = ExportMath.outDims(info.dispW, info.dispH, rot, values[qi])
            var chosenIdx = qi
            var chosenDims = wanted
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
                    chosenIdx = found
                    chosenDims = d
                } else {
                    notes.add(
                        "Encoder perangkat mungkin tidak mendukung " + labels[qi] + " (" + wanted[0] + "×" +
                            wanted[1] + "); ukuran akan disesuaikan otomatis."
                    )
                }
            }
            used = chosenIdx
            dims = chosenDims
        }

        // --- FPS: gunakan FPS asli sumber bila fIdx == ORIGINAL_IDX, selain itu hanya bisa diturunkan.
        val src = if (info.fps > 1f) info.fps.toDouble() else 30.0
        val eff: Double
        var drop = false
        if (fIdx == ExportMath.ORIGINAL_IDX) {
            eff = src
            drop = false
        } else {
            val fpsIdx = fIdx.coerceIn(0, ExportMath.FPS_VALUES.size - 1)
            val target = ExportMath.FPS_VALUES[fpsIdx].toDouble()
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
        }

        val br = ExportMath.bitrate(dims[0], dims[1], eff).toInt()
        return ExportPlan(dims[0], dims[1], used, eff, drop, br, notes)
    }
}
