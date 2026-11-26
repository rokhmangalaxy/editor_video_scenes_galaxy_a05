package com.pribadi.editorvideo.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.pribadi.editorvideo.model.VideoInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Satu titik sampel hasil pemindaian frame untuk deteksi pergantian scene. */
data class SceneSample(
    val timeSec: Double,
    val diffScore: Float,
    val thumb: Bitmap?
)

/** Hasil pemindaian satu klip video (disimpan di memori agar slider instan, dan bisa dipindai ulang). */
data class SceneScanResult(
    val srcIndex: Int,
    val segIndex: Int,
    val startSec: Double,
    val endSec: Double,
    val speed: Double,
    val scanAttempt: Int,
    val samples: List<SceneSample>
)

/** Satu bagian scene yang terdeteksi untuk ditampilkan di Grid Preview Deteksi. */
data class DetectedScene(
    val index: Int,
    val startSec: Double,
    val endSec: Double,
    val thumb: Bitmap?,
    val diffScore: Float,
    val selected: Boolean = true
) {
    val durationSec: Double get() = max(0.0, endSec - startSec)
}

/**
 * Pendeteksi pergantian adegan (Scene Cut Detector) berbasis perbandingan blok warna & histogram
 * antar-frame presisi (OPTION_CLOSEST), mendukung nilai minimal 0.1 dan deteksi ulang (rescan).
 */
object SceneDetector {

    private const val SIG_W = 16
    private const val SIG_H = 16
    private const val GRID_THUMB_W = 160
    private const val GRID_THUMB_H = 110

    suspend fun scanClip(
        context: Context,
        srcUri: Uri,
        srcInfo: VideoInfo,
        srcIndex: Int,
        segIndex: Int,
        startSec: Double,
        endSec: Double,
        speed: Double,
        scanAttempt: Int = 0,
        onProgress: (Float) -> Unit
    ): SceneScanResult {
        val dur = max(0.2, endSec - startSec)
        // Pada deteksi ulang (scanAttempt > 0), tingkatkan kepadatan sampel + geser fase titik uji
        val densityPerSec = if (scanAttempt > 0) 6.0 else 4.5
        val maxSamples = if (scanAttempt > 0) 140 else 110
        val sampleCount = min(maxSamples, max(20, Math.round(dur * densityPerSec).toInt()))
        val step = dur / sampleCount.toDouble()
        val phaseShift = if (scanAttempt > 0) ((scanAttempt % 4) * 0.22 * step) else 0.0

        val samples = ArrayList<SceneSample>(sampleCount)
        val retriever = MediaMetadataRetriever()
        val sigPixels = IntArray(SIG_W * SIG_H)
        var prevR: FloatArray? = null
        var prevG: FloatArray? = null
        var prevB: FloatArray? = null
        var prevHist: FloatArray? = null
        var lastThumb: Bitmap? = null

        try {
            retriever.setDataSource(context, srcUri)
            for (i in 0 until sampleCount) {
                currentCoroutineContext().ensureActive()
                val rawT = if (i == 0) startSec else startSec + i * step + phaseShift
                val tSec = min(endSec - 0.01, max(startSec, rawT))
                val us = Math.round(tSec * 1_000_000.0)

                // Gunakan getScaledFrameAtTime + OPTION_CLOSEST agar frame akurat hingga skala 0.1 detik
                var raw = try {
                    retriever.getScaledFrameAtTime(
                        us,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        GRID_THUMB_W * 2,
                        GRID_THUMB_H * 2
                    )
                } catch (e: Exception) {
                    null
                }
                if (raw == null) {
                    raw = try {
                        retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (raw == null) {
                    raw = try {
                        retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (e: Exception) {
                        null
                    }
                }

                var diff = 0f
                var thumb: Bitmap? = lastThumb

                if (raw != null) {
                    val sigBmp = Bitmap.createScaledBitmap(raw, SIG_W, SIG_H, true)
                    sigBmp.getPixels(sigPixels, 0, SIG_W, 0, 0, SIG_W, SIG_H)
                    if (sigBmp !== raw) sigBmp.recycle()

                    val curR = FloatArray(SIG_W * SIG_H)
                    val curG = FloatArray(SIG_W * SIG_H)
                    val curB = FloatArray(SIG_W * SIG_H)
                    val curHist = FloatArray(24)
                    val invN = 1f / (SIG_W * SIG_H).toFloat()

                    for (k in sigPixels.indices) {
                        val px = sigPixels[k]
                        val r = ((px shr 16) and 0xFF) / 255f
                        val g = ((px shr 8) and 0xFF) / 255f
                        val b = (px and 0xFF) / 255f
                        curR[k] = r
                        curG[k] = g
                        curB[k] = b
                        val br = ((r * 7.99f).toInt()).coerceIn(0, 7)
                        val bg = ((g * 7.99f).toInt()).coerceIn(0, 7)
                        val bb = ((b * 7.99f).toInt()).coerceIn(0, 7)
                        curHist[br] += invN
                        curHist[8 + bg] += invN
                        curHist[16 + bb] += invN
                    }

                    val pR = prevR
                    val pG = prevG
                    val pB = prevB
                    val pH = prevHist
                    if (pR != null && pG != null && pB != null && pH != null) {
                        var blockDiff = 0f
                        for (k in curR.indices) {
                            blockDiff += (abs(curR[k] - pR[k]) + abs(curG[k] - pG[k]) + abs(curB[k] - pB[k])) / 3f
                        }
                        blockDiff /= curR.size.toFloat()

                        var histDiff = 0f
                        for (k in curHist.indices) {
                            histDiff += abs(curHist[k] - pH[k])
                        }
                        histDiff /= 6f

                        diff = (blockDiff * 0.55f + histDiff * 0.45f).coerceIn(0f, 1f)
                    }

                    prevR = curR
                    prevG = curG
                    prevB = curB
                    prevHist = curHist

                    thumb = VideoFrames.coverCrop(raw, GRID_THUMB_W, GRID_THUMB_H)
                    lastThumb = thumb
                }

                samples.add(SceneSample(tSec, diff, thumb))
                onProgress(((i + 1).toFloat() / sampleCount.toFloat()).coerceIn(0.05f, 1f))
            }
        } catch (e: Exception) {
            // bila gagal sebagian, gunakan sampel yang sudah terkumpul
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }

        return SceneScanResult(
            srcIndex = srcIndex,
            segIndex = segIndex,
            startSec = startSec,
            endSec = endSec,
            speed = speed,
            scanAttempt = scanAttempt,
            samples = samples
        )
    }

    /**
     * Menyusun daftar DetectedScene dari hasil pemindaian berdasarkan sensitivitas (min 0.1 .. 1.0)
     * dan durasi minimal per scene (min 0.1 detik).
     */
    fun buildScenes(
        scan: SceneScanResult,
        sensitivity: Float,
        minSceneDurSec: Double
    ): List<DetectedScene> {
        val samples = scan.samples
        if (samples.isEmpty()) {
            return listOf(
                DetectedScene(0, scan.startSec, scan.endSec, null, 1f, true)
            )
        }

        val minDur = minSceneDurSec.coerceIn(0.1, 10.0)
        val normSens = sensitivity.coerceIn(0.1f, 1.0f)

        val diffs = samples.drop(1).map { it.diffScore }.sorted()
        val maxDiff = diffs.lastOrNull() ?: 0f
        val medianDiff = if (diffs.isNotEmpty()) diffs[diffs.size / 2] else 0f

        val rawThreshold = max(
            0.025f,
            medianDiff + (maxDiff - medianDiff) * (1.0f - normSens * 0.90f)
        )

        val cuts = ArrayList<Pair<Double, Float>>()
        var lastCutSec = scan.startSec
        for (i in 1 until samples.size) {
            val s = samples[i]
            val sinceLast = s.timeSec - lastCutSec
            val leftRem = scan.endSec - s.timeSec
            if (s.diffScore >= rawThreshold && sinceLast >= minDur && leftRem >= minDur) {
                cuts.add(s.timeSec to s.diffScore)
                lastCutSec = s.timeSec
            }
        }

        if (cuts.isEmpty() && samples.size >= 4 && scan.endSec - scan.startSec >= minDur * 2.0) {
            val candidates = samples.drop(1)
                .filter { it.timeSec - scan.startSec >= minDur && scan.endSec - it.timeSec >= minDur }
                .sortedByDescending { it.diffScore }
            val wantPeaks = max(1, Math.round(normSens * 3f).toInt())
            val picked = ArrayList<Pair<Double, Float>>()
            for (cand in candidates) {
                if (cand.diffScore <= 0.004f) break
                if (picked.all { abs(it.first - cand.timeSec) >= minDur }) {
                    picked.add(cand.timeSec to cand.diffScore)
                    if (picked.size >= wantPeaks) break
                }
            }
            picked.sortBy { it.first }
            cuts.addAll(picked)
        }

        val result = ArrayList<DetectedScene>(cuts.size + 1)
        var curStart = scan.startSec
        var curDiff = 1f
        for (idx in 0..cuts.size) {
            val curEnd = if (idx < cuts.size) cuts[idx].first else scan.endSec
            val midSec = (curStart + curEnd) * 0.5
            val bestSample = samples.minByOrNull { abs(it.timeSec - midSec) }
                ?: samples.firstOrNull { it.timeSec >= curStart }
            result.add(
                DetectedScene(
                    index = idx,
                    startSec = curStart,
                    endSec = curEnd,
                    thumb = bestSample?.thumb,
                    diffScore = curDiff,
                    selected = true
                )
            )
            if (idx < cuts.size) {
                curStart = cuts[idx].first
                curDiff = cuts[idx].second
            }
        }
        return result
    }
}
