package com.pribadi.editorvideo.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.pribadi.editorvideo.model.VideoInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Merender potongan video menjadi berkas MP4 baru untuk fitur Membalikkan:
 *  - Mode Mundur (includeForwardFirst = false): membalik urutan frame (akhir -> awal).
 *  - Mode Loop   (includeForwardFirst = true) : menggabungkan video Normal (awal -> akhir)
 *                                               diikuti video Mundur (akhir -> awal) menjadi satu klip utuh.
 */
object ReverseVideoRenderer {

    private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val MAX_LONG_SIDE = 720
    private const val MAX_FRAMES_PER_CHUNK = 36

    private class YuvFrame(
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray,
        val width: Int,
        val height: Int
    )

    suspend fun renderClip(
        context: Context,
        srcUri: Uri,
        srcInfo: VideoInfo,
        startSec: Double,
        endSec: Double,
        includeForwardFirst: Boolean,
        outFile: File,
        onProgress: (Float) -> Unit
    ) {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val okGop = try {
            renderViaGopDecode(
                context = context,
                srcUri = srcUri,
                srcInfo = srcInfo,
                startSec = startSec,
                endSec = endSec,
                includeForwardFirst = includeForwardFirst,
                outFile = outFile,
                onProgress = onProgress
            )
        } catch (e: Throwable) {
            false
        }
        if (okGop && outFile.exists() && outFile.length() > 0L) {
            onProgress(1f)
            return
        }

        if (outFile.exists()) outFile.delete()
        renderViaRetriever(
            context = context,
            srcUri = srcUri,
            srcInfo = srcInfo,
            startSec = startSec,
            endSec = endSec,
            includeForwardFirst = includeForwardFirst,
            outFile = outFile,
            onProgress = onProgress
        )
        onProgress(1f)
    }

    /**
     * Jalur 1: Decode per-GOP menggunakan MediaExtractor + MediaCodec,
     * lalu tulis ke MediaCodec H.264 encoder + MediaMuxer:
     *  - bila includeForwardFirst == true (Loop): encode semua GOP secara maju dulu, lalu semua GOP secara mundur.
     *  - bila includeForwardFirst == false (Mundur): encode semua GOP secara mundur.
     */
    private suspend fun renderViaGopDecode(
        context: Context,
        srcUri: Uri,
        srcInfo: VideoInfo,
        startSec: Double,
        endSec: Double,
        includeForwardFirst: Boolean,
        outFile: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        val startUs = Math.round(max(0.0, startSec) * 1_000_000.0)
        val endUs = Math.round(max(startSec + 0.1, endSec) * 1_000_000.0)

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            extractor.setDataSource(context, srcUri, null)
            var videoTrack = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    trackFormat = f
                    break
                }
            }
            if (videoTrack < 0 || trackFormat == null) return false
            extractor.selectTrack(videoTrack)

            // 1. Pindai posisi keyframe (sync sample) di rentang [startUs, endUs]
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val syncPoints = ArrayList<Long>()
            var lastSync = -1L
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0L || sampleTime > endUs) break
                val flags = extractor.sampleFlags
                if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0 || syncPoints.isEmpty()) {
                    if (sampleTime != lastSync) {
                        syncPoints.add(sampleTime)
                        lastSync = sampleTime
                    }
                }
                if (!extractor.advance()) break
            }
            if (syncPoints.isEmpty()) return false

            val gopBounds = ArrayList<LongArray>(syncPoints.size)
            for (i in syncPoints.indices) {
                val gs = syncPoints[i]
                val ge = if (i + 1 < syncPoints.size) syncPoints[i + 1] else (endUs + 1L)
                if (ge > startUs && gs <= endUs) {
                    gopBounds.add(longArrayOf(gs, ge))
                }
            }
            if (gopBounds.isEmpty()) return false

            val srcW = max(16, srcInfo.width)
            val srcH = max(16, srcInfo.height)
            val scale = min(1.0, MAX_LONG_SIDE.toDouble() / max(srcW, srcH).toDouble())
            val encW = max(16, (Math.round(srcW * scale / 16.0).toInt() * 16))
            val encH = max(16, (Math.round(srcH * scale / 16.0).toInt() * 16))
            val targetFps = min(24, max(12, Math.round(srcInfo.fps).coerceIn(15, 24)))
            val frameStepUs = 1_000_000L / targetFps
            val minSampleGapUs = (1_000_000.0 / (targetFps + 2.0)).toLong()

            val encFormat = MediaFormat.createVideoFormat(MIME_AVC, encW, encH).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, max(600_000, encW * encH * targetFps / 4))
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MIME_AVC)
            encoder = enc
            enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            val srcMime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return false
            trackFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            val dec = MediaCodec.createDecoderByType(srcMime)
            decoder = dec
            dec.configure(trackFormat, null, null, 0)
            dec.start()

            val mux = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            if (srcInfo.rotation % 360 != 0) {
                mux.setOrientationHint((srcInfo.rotation % 360 + 360) % 360)
            }

            var muxTrack = -1
            var outPtsUs = 0L
            var encodedFrames = 0
            val bufInfo = MediaCodec.BufferInfo()
            val decInfo = MediaCodec.BufferInfo()

            fun drainEncoder(eos: Boolean) {
                if (eos) {
                    val inIdx = enc.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        enc.queueInputBuffer(
                            inIdx, 0, 0, outPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                }
                while (true) {
                    val outIdx = enc.dequeueOutputBuffer(bufInfo, if (eos) 10_000L else 0L)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxTrack = mux.addTrack(enc.outputFormat)
                                mux.start()
                                muxerStarted = true
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = enc.getOutputBuffer(outIdx)
                            if (outBuf != null && (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufInfo.size > 0 && muxerStarted) {
                                outBuf.position(bufInfo.offset)
                                outBuf.limit(bufInfo.offset + bufInfo.size)
                                mux.writeSampleData(muxTrack, outBuf, bufInfo)
                            }
                            val isEos = (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            enc.releaseOutputBuffer(outIdx, false)
                            if (isEos) break
                        }
                    }
                }
            }

            suspend fun decodeSingleGop(gs: Long, ge: Long): ArrayList<YuvFrame> {
                dec.flush()
                extractor.seekTo(gs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val gopFrames = ArrayList<YuvFrame>()
                var inputDone = false
                var outputDone = false
                var lastAcceptedPts = Long.MIN_VALUE

                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val inIdx = dec.dequeueInputBuffer(5_000L)
                        if (inIdx >= 0) {
                            val inBuf = dec.getInputBuffer(inIdx)!!
                            val sampleTime = extractor.sampleTime
                            if (sampleTime < 0L || sampleTime >= ge) {
                                dec.queueInputBuffer(
                                    inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val sz = extractor.readSampleData(inBuf, 0)
                                if (sz < 0) {
                                    dec.queueInputBuffer(
                                        inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    dec.queueInputBuffer(inIdx, 0, sz, sampleTime, extractor.sampleFlags)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = dec.dequeueOutputBuffer(decInfo, 5_000L)
                    if (outIdx >= 0) {
                        val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val pts = decInfo.presentationTimeUs
                        val inRange = pts >= max(startUs, gs) && pts <= min(endUs, ge - 1L)
                        val gapOk = lastAcceptedPts == Long.MIN_VALUE || (pts - lastAcceptedPts) >= minSampleGapUs

                        if (decInfo.size > 0 && inRange && gapOk) {
                            val img = dec.getOutputImage(outIdx)
                            if (img != null) {
                                val copied = extractScaledYuv(img, encW, encH)
                                if (copied != null) {
                                    if (gopFrames.size >= MAX_FRAMES_PER_CHUNK) {
                                        gopFrames.removeAt(0)
                                    }
                                    gopFrames.add(copied)
                                    lastAcceptedPts = pts
                                }
                                img.close()
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (isEos || pts >= ge) {
                            outputDone = true
                        }
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                        outputDone = true
                    }
                }
                return gopFrames
            }

            suspend fun queueFrameToEncoder(frame: YuvFrame) {
                currentCoroutineContext().ensureActive()
                var queued = false
                var tries = 0
                while (!queued && tries < 40) {
                    drainEncoder(false)
                    val inIdx = enc.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inImg = enc.getInputImage(inIdx)
                        if (inImg != null) {
                            fillInputImage(inImg, frame)
                            inImg.close()
                            enc.queueInputBuffer(
                                inIdx, 0, encW * encH * 3 / 2, outPtsUs, 0
                            )
                            outPtsUs += frameStepUs
                            encodedFrames++
                            queued = true
                        } else {
                            enc.queueInputBuffer(inIdx, 0, 0, outPtsUs, 0)
                            break
                        }
                    }
                    tries++
                }
            }

            val totalGops = gopBounds.size
            val totalPasses = if (includeForwardFirst) totalGops * 2 else totalGops
            var doneSteps = 0

            // Bagian A (Khusus Loop): encode video NORMAL (awal -> akhir) terlebih dahulu
            if (includeForwardFirst) {
                for (gIdx in 0 until totalGops) {
                    currentCoroutineContext().ensureActive()
                    val gopFrames = decodeSingleGop(gopBounds[gIdx][0], gopBounds[gIdx][1])
                    for (fIdx in 0 until gopFrames.size) {
                        queueFrameToEncoder(gopFrames[fIdx])
                    }
                    gopFrames.clear()
                    doneSteps++
                    onProgress((doneSteps.toFloat() / totalPasses.toFloat()).coerceIn(0.04f, 0.50f))
                }
            }

            // Bagian B (Mundur & Loop): encode video MUNDUR (akhir -> awal)
            for (gIdx in totalGops - 1 downTo 0) {
                currentCoroutineContext().ensureActive()
                val gopFrames = decodeSingleGop(gopBounds[gIdx][0], gopBounds[gIdx][1])
                val startFrameIdx = if (includeForwardFirst && gIdx == totalGops - 1 && gopFrames.size > 1) {
                    gopFrames.size - 2 // hindari duplikasi frame titik balik
                } else {
                    gopFrames.size - 1
                }
                for (fIdx in startFrameIdx downTo 0) {
                    queueFrameToEncoder(gopFrames[fIdx])
                }
                gopFrames.clear()
                doneSteps++
                onProgress((doneSteps.toFloat() / totalPasses.toFloat()).coerceIn(0.05f, 0.96f))
            }

            if (encodedFrames < 2) return false
            drainEncoder(true)
            return muxerStarted
        } finally {
            try {
                decoder?.stop()
            } catch (e: Exception) {
            }
            try {
                decoder?.release()
            } catch (e: Exception) {
            }
            try {
                encoder?.stop()
            } catch (e: Exception) {
            }
            try {
                encoder?.release()
            } catch (e: Exception) {
            }
            try {
                if (muxerStarted) muxer?.stop()
            } catch (e: Exception) {
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
            }
            try {
                extractor.release()
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Jalur 2 (Cadangan): Mengambil frame melalui MediaMetadataRetriever lalu mengenkodekannya ke MP4.
     */
    private suspend fun renderViaRetriever(
        context: Context,
        srcUri: Uri,
        srcInfo: VideoInfo,
        startSec: Double,
        endSec: Double,
        includeForwardFirst: Boolean,
        outFile: File,
        onProgress: (Float) -> Unit
    ) {
        val dur = max(0.1, endSec - startSec)
        val fps = 15
        val oneWayFrames = max(2, min(240, Math.round(dur * fps).toInt()))
        val stepSec = dur / oneWayFrames.toDouble()
        val frameStepUs = 1_000_000L / fps

        val dispW = max(16, srcInfo.dispW)
        val dispH = max(16, srcInfo.dispH)
        val scale = min(1.0, 640.0 / max(dispW, dispH).toDouble())
        val encW = max(16, (Math.round(dispW * scale / 16.0).toInt() * 16))
        val encH = max(16, (Math.round(dispH * scale / 16.0).toInt() * 16))

        // Susun daftar timestamp yang akan di-encode (Maju+Mundur untuk Loop, atau Mundur saja)
        val timesSec = ArrayList<Double>(if (includeForwardFirst) oneWayFrames * 2 else oneWayFrames)
        if (includeForwardFirst) {
            for (i in 0 until oneWayFrames) {
                timesSec.add(min(endSec, startSec + (i + 0.5) * stepSec))
            }
        }
        val revStartIdx = if (includeForwardFirst && oneWayFrames > 1) 1 else 0
        for (i in revStartIdx until oneWayFrames) {
            timesSec.add(max(startSec, endSec - (i + 0.5) * stepSec))
        }

        val retriever = MediaMetadataRetriever()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            retriever.setDataSource(context, srcUri)
            val encFormat = MediaFormat.createVideoFormat(MIME_AVC, encW, encH).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, max(600_000, encW * encH * fps / 4))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MIME_AVC)
            encoder = enc
            enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            val mux = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            var muxTrack = -1
            var outPtsUs = 0L
            val bufInfo = MediaCodec.BufferInfo()
            var lastYuv: YuvFrame? = null
            val argb = IntArray(encW * encH)

            fun drain(eos: Boolean) {
                if (eos) {
                    val inIdx = enc.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        enc.queueInputBuffer(
                            inIdx, 0, 0, outPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                }
                while (true) {
                    val outIdx = enc.dequeueOutputBuffer(bufInfo, if (eos) 10_000L else 0L)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxTrack = mux.addTrack(enc.outputFormat)
                                mux.start()
                                muxerStarted = true
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = enc.getOutputBuffer(outIdx)
                            if (outBuf != null && (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufInfo.size > 0 && muxerStarted) {
                                outBuf.position(bufInfo.offset)
                                outBuf.limit(bufInfo.offset + bufInfo.size)
                                mux.writeSampleData(muxTrack, outBuf, bufInfo)
                            }
                            val isEos = (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            enc.releaseOutputBuffer(outIdx, false)
                            if (isEos) break
                        }
                    }
                }
            }

            val totalOut = timesSec.size
            for (i in 0 until totalOut) {
                currentCoroutineContext().ensureActive()
                val us = Math.round(timesSec[i] * 1_000_000.0)
                var raw = try {
                    retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, encW, encH)
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
                if (raw != null) {
                    val scaled = if (raw.width == encW && raw.height == encH) {
                        raw
                    } else {
                        val s = Bitmap.createScaledBitmap(raw, encW, encH, true)
                        if (s !== raw) raw.recycle()
                        s
                    }
                    scaled.getPixels(argb, 0, encW, 0, 0, encW, encH)
                    scaled.recycle()
                    lastYuv = argbToYuv(argb, encW, encH)
                }

                val frame = lastYuv ?: continue
                var queued = false
                var tries = 0
                while (!queued && tries < 40) {
                    drain(false)
                    val inIdx = enc.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inImg = enc.getInputImage(inIdx)
                        if (inImg != null) {
                            fillInputImage(inImg, frame)
                            inImg.close()
                            enc.queueInputBuffer(inIdx, 0, encW * encH * 3 / 2, outPtsUs, 0)
                            outPtsUs += frameStepUs
                            queued = true
                        } else {
                            break
                        }
                    }
                    tries++
                }
                onProgress(((i + 1).toFloat() / totalOut.toFloat()).coerceIn(0.05f, 0.98f))
            }

            drain(true)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
            try {
                encoder?.stop()
            } catch (e: Exception) {
            }
            try {
                encoder?.release()
            } catch (e: Exception) {
            }
            try {
                if (muxerStarted) muxer?.stop()
            } catch (e: Exception) {
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
            }
        }
    }

    /** Menyalin & menskalakan Image YUV_420_888 dari decoder ke ukuran target (dstW x dstH). */
    private fun extractScaledYuv(img: Image, dstW: Int, dstH: Int): YuvFrame? {
        if (img.format != ImageFormat.YUV_420_888 || img.planes.size < 3) return null
        val srcW = img.width
        val srcH = img.height
        if (srcW <= 0 || srcH <= 0) return null

        val pY = img.planes[0]
        val pU = img.planes[1]
        val pV = img.planes[2]

        val bufY = pY.buffer
        val bufU = pU.buffer
        val bufV = pV.buffer

        val rsY = pY.rowStride
        val psY = pY.pixelStride
        val rsU = pU.rowStride
        val psU = pU.pixelStride
        val rsV = pV.rowStride
        val psV = pV.pixelStride

        val yOut = ByteArray(dstW * dstH)
        val cw = dstW / 2
        val ch = dstH / 2
        val uOut = ByteArray(cw * ch)
        val vOut = ByteArray(cw * ch)

        val limY = bufY.limit()
        var outIdx = 0
        for (dy in 0 until dstH) {
            val sy = (dy * srcH) / dstH
            val rowBase = sy * rsY
            for (dx in 0 until dstW) {
                val sx = (dx * srcW) / dstW
                val pos = rowBase + sx * psY
                yOut[outIdx++] = if (pos in 0 until limY) bufY.get(pos) else 0
            }
        }

        val srcCW = srcW / 2
        val srcCH = srcH / 2
        val limU = bufU.limit()
        val limV = bufV.limit()
        var cIdx = 0
        for (dy in 0 until ch) {
            val sy = (dy * srcCH) / ch
            val rowU = sy * rsU
            val rowV = sy * rsV
            for (dx in 0 until cw) {
                val sx = (dx * srcCW) / cw
                val posU = rowU + sx * psU
                val posV = rowV + sx * psV
                uOut[cIdx] = if (posU in 0 until limU) bufU.get(posU) else 128.toByte()
                vOut[cIdx] = if (posV in 0 until limV) bufV.get(posV) else 128.toByte()
                cIdx++
            }
        }
        return YuvFrame(yOut, uOut, vOut, dstW, dstH)
    }

    /** Konversi piksel ARGB ke YuvFrame (BT.601). */
    private fun argbToYuv(argb: IntArray, w: Int, h: Int): YuvFrame {
        val yOut = ByteArray(w * h)
        val cw = w / 2
        val ch = h / 2
        val uOut = ByteArray(cw * ch)
        val vOut = ByteArray(cw * ch)

        var yIdx = 0
        for (j in 0 until h) {
            for (i in 0 until w) {
                val px = argb[yIdx]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yOut[yIdx] = y.coerceIn(0, 255).toByte()
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val cIdx = (j shr 1) * cw + (i shr 1)
                    uOut[cIdx] = u.coerceIn(0, 255).toByte()
                    vOut[cIdx] = v.coerceIn(0, 255).toByte()
                }
                yIdx++
            }
        }
        return YuvFrame(yOut, uOut, vOut, w, h)
    }

    /** Menulis YuvFrame ke Image input milik MediaCodec encoder (mendukung I420, NV12, NV21). */
    private fun fillInputImage(img: Image, frame: YuvFrame) {
        val w = frame.width
        val h = frame.height
        val pY = img.planes[0]
        val bufY = pY.buffer
        val rsY = pY.rowStride
        val psY = pY.pixelStride
        val limY = bufY.limit()

        for (row in 0 until h) {
            val srcOff = row * w
            val dstRow = row * rsY
            if (psY == 1 && dstRow + w <= limY) {
                bufY.position(dstRow)
                bufY.put(frame.y, srcOff, w)
            } else {
                for (col in 0 until w) {
                    val pos = dstRow + col * psY
                    if (pos < limY) bufY.put(pos, frame.y[srcOff + col])
                }
            }
        }

        val cw = w / 2
        val ch = h / 2
        val pU = img.planes[1]
        val pV = img.planes[2]
        val bufU = pU.buffer
        val bufV = pV.buffer
        val rsU = pU.rowStride
        val psU = pU.pixelStride
        val rsV = pV.rowStride
        val psV = pV.pixelStride
        val limU = bufU.limit()
        val limV = bufV.limit()

        for (row in 0 until ch) {
            val srcOff = row * cw
            val dstRowU = row * rsU
            val dstRowV = row * rsV
            for (col in 0 until cw) {
                val posU = dstRowU + col * psU
                val posV = dstRowV + col * psV
                if (posU < limU) bufU.put(posU, frame.u[srcOff + col])
                if (posV < limV) bufV.put(posV, frame.v[srcOff + col])
            }
        }
    }
}
