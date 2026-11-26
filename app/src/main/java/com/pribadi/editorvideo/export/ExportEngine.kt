package com.pribadi.editorvideo.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import com.pribadi.editorvideo.model.Seg
import com.pribadi.editorvideo.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

/** Kegagalan ekspor dengan pesan yang siap ditampilkan ke pengguna (bahasa Indonesia). */
class ExportFailure(message: String) : Exception(message)

/** Hasil ekspor: lokasi file yang tersimpan dan catatan (mis. fallback resolusi/FPS). */
data class ExportOutcome(val files: List<String>, val notes: List<String>)

/**
 * Mesin ekspor berbasis Media3 Transformer.
 *
 * Satu file keluaran = satu Composition berisi urutan potongan:
 *  - klip  : MediaItem dengan ClippingConfiguration (a..b), efek kecepatan video + audio, rotasi, cermin, teks
 *  - beku  : gambar JPEG dari frame di detik a, durasi = d / kecepatan, dengan rotasi, cermin, teks yang sama
 * (Potongan yang sudah dibalik telah dirender menjadi file MP4 terbalik oleh ReverseVideoRenderer,
 *  sehingga diekspor cepat dan mulus sebagai klip standar.)
 */
object ExportEngine {

    private const val MAX_FREEZE_SIDE = 2560
    private const val MIN_ITEM_US = 100_000L

    suspend fun run(
        context: Context,
        req: ExportRequest,
        plan: ExportPlan,
        progress: (fileIndex: Int, fileCount: Int, fraction: Float) -> Unit
    ): ExportOutcome {
        val app = context.applicationContext
        val jobs = ExportMath.buildJobs(req.mode, req.segs, req.currentIndex)
        if (jobs.isEmpty()) throw ExportFailure("Tidak ada potongan untuk diekspor.")

        val dir = File(app.cacheDir, "export")
        withContext(Dispatchers.IO) {
            cleanDir(dir)
            dir.mkdirs()
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val notes = ArrayList<String>()
        notes.addAll(plan.notes)
        val saved = ArrayList<String>()

        try {
            for (idx in jobs.indices) {
                val job = jobs[idx]
                currentCoroutineContext().ensureActive()
                progress(idx, jobs.size, 0f)

                val outFile = File(dir, "hasil_" + idx + ".mp4")
                var overlay: Bitmap? = null
                try {
                    val prepared = withContext(Dispatchers.IO) {
                        prepare(app, req, plan, job.segs, dir, idx)
                    }
                    overlay = prepared.overlay

                    runTransformer(app, prepared.composition, outFile.absolutePath, plan, notes) { f ->
                        progress(idx, jobs.size, f)
                    }

                    if (!outFile.exists() || outFile.length() <= 0L) {
                        throw ExportFailure("Berkas hasil ekspor kosong. Coba lagi dengan kualitas yang lebih rendah.")
                    }

                    progress(idx, jobs.size, 1f)
                    val name = "EditorVideo_" + stamp + (if (job.suffix.isEmpty()) "" else "_" + job.suffix) + ".mp4"
                    val where = withContext(Dispatchers.IO) {
                        try {
                            MediaStoreSaver.save(app, outFile, name)
                        } catch (e: ExportFailure) {
                            throw e
                        } catch (e: Exception) {
                            throw ExportFailure("Gagal menyimpan ke folder Movies. Pastikan penyimpanan cukup. (" + (e.message ?: "") + ")")
                        }
                    }
                    saved.add(where)
                } finally {
                    overlay?.recycle()
                    withContext(NonCancellable + Dispatchers.IO) {
                        cleanDir(dir)
                        dir.mkdirs()
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                cleanDir(dir)
            }
        }
        return ExportOutcome(saved, notes.distinct())
    }

    // ---------------------------------------------------------------------------------------------
    // Persiapan: item Composition
    // ---------------------------------------------------------------------------------------------

    private class Prepared(val composition: Composition, val overlay: Bitmap?)

    private fun resolveSource(req: ExportRequest, srcIndex: Int): SourceSpec {
        if (req.sources.isNotEmpty()) {
            return req.sources.getOrElse(srcIndex) { req.sources.first() }
        }
        return SourceSpec(req.sourceUri, req.info)
    }

    /** Dijalankan di thread IO: membuat gambar beku, bitmap teks, dan seluruh EditedMediaItem. */
    private fun prepare(
        app: Context,
        req: ExportRequest,
        plan: ExportPlan,
        segs: List<Seg>,
        dir: File,
        jobIdx: Int
    ): Prepared {
        val hasText = req.style1.isNotEmpty || req.style2.isNotEmpty
        val overlay: Bitmap? =
            if (hasText) OverlayRenderer.render(plan.width, plan.height, req.style1, req.style2) else null
        try {
            val items = ArrayList<EditedMediaItem>()
            for (i in segs.indices) {
                val seg = segs[i]
                val spec = resolveSource(req, seg.src)
                val srcUri = Uri.parse(spec.uri)
                val srcInfo = spec.info
                if (seg.fz) {
                    items.add(freezeItem(app, req, plan, seg, srcUri, srcInfo, dir, "j" + jobIdx + "_" + i, overlay))
                } else {
                    items.add(clipItem(req, plan, seg, srcUri, srcInfo, overlay))
                }
            }
            val sequence = EditedMediaItemSequence(items)
            val builder = Composition.Builder(sequence)
            val anyAudio = if (req.sources.isNotEmpty()) {
                req.sources.any { it.info.hasAudio }
            } else {
                req.info.hasAudio
            }
            builder.experimentalSetForceAudioTrack(anyAudio)
            builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            return Prepared(builder.build(), overlay)
        } catch (e: Throwable) {
            overlay?.recycle()
            throw e
        }
    }

    private fun clipItem(
        req: ExportRequest,
        plan: ExportPlan,
        seg: Seg,
        srcUri: Uri,
        srcInfo: VideoInfo,
        overlay: Bitmap?
    ): EditedMediaItem {
        val startMs = Math.round(seg.a * 1000.0)
        var endMs = Math.round(seg.b * 1000.0)
        if (endMs <= startMs) endMs = startMs + 1L

        val mediaItem = MediaItem.Builder()
            .setUri(srcUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()

        val speed = seg.sp
        val audio: List<AudioProcessor> =
            if (srcInfo.hasAudio && abs(speed - 1.0) > 0.0001) audioSpeed(speed) else emptyList()
        val video = videoEffects(req, plan, speed, false, overlay)

        val b = EditedMediaItem.Builder(mediaItem)
        if (!srcInfo.hasAudio) b.setRemoveAudio(true)
        b.setEffects(Effects(audio, video))
        return b.build()
    }

    private fun freezeItem(
        app: Context,
        req: ExportRequest,
        plan: ExportPlan,
        seg: Seg,
        srcUri: Uri,
        srcInfo: VideoInfo,
        dir: File,
        tag: String,
        overlay: Bitmap?
    ): EditedMediaItem {
        val ms = Math.round(seg.a * 1000.0)
        var bmp = VideoFrames.grabFrame(app, srcUri, ms, srcInfo)
            ?: throw ExportFailure("Gagal mengambil gambar untuk potongan beku. Coba ekspor ulang.")
        bmp = VideoFrames.limitLongSide(bmp, MAX_FREEZE_SIDE)
        val file = File(dir, "beku_" + tag + ".jpg")
        try {
            FileOutputStream(file).use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                    throw ExportFailure("Gagal menyimpan gambar sementara untuk potongan beku.")
                }
            }
        } finally {
            bmp.recycle()
        }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMimeType(MimeTypes.IMAGE_JPEG)
            .build()

        val durationUs = max(MIN_ITEM_US, Math.round(seg.len * 1_000_000.0))
        val frameRate = max(1, Math.round(plan.fps).toInt())

        val video = videoEffects(req, plan, 1.0, true, overlay)
        return EditedMediaItem.Builder(mediaItem)
            .setDurationUs(durationUs)
            .setFrameRate(frameRate)
            .setEffects(Effects(emptyList<AudioProcessor>(), video))
            .build()
    }

    /** Percepatan/perlambatan audio dengan nada tetap. */
    private fun audioSpeed(speed: Double): List<AudioProcessor> {
        val sonic = SonicAudioProcessor()
        sonic.setSpeed(speed.toFloat())
        sonic.setPitch(1f)
        return listOf<AudioProcessor>(sonic)
    }

    /**
     * Rantai efek video per item:
     *   [kecepatan] -> [buang frame] -> [putar] -> [cermin] -> [ukuran akhir] -> [teks]
     */
    private fun videoEffects(
        req: ExportRequest,
        plan: ExportPlan,
        speed: Double,
        isImage: Boolean,
        overlay: Bitmap?
    ): List<Effect> {
        val list = ArrayList<Effect>()
        if (!isImage) {
            if (abs(speed - 1.0) > 0.0001) list.add(SpeedChangeEffect(speed.toFloat()))
            if (plan.dropFrames || speed > 1.0001) {
                list.add(FrameDropEffect.createDefaultFrameDropEffect(plan.fps.toFloat()))
            }
        }
        val ccw = (360 - (req.rot % 360)) % 360
        if (ccw != 0) {
            list.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(ccw.toFloat()).build())
        }
        if (req.flip) {
            list.add(ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build())
        }
        list.add(Presentation.createForWidthAndHeight(plan.width, plan.height, Presentation.LAYOUT_STRETCH_TO_FIT))
        if (overlay != null) {
            list.add(OverlayEffect(ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(overlay))))
        }
        return list
    }

    // ---------------------------------------------------------------------------------------------
    // Transformer
    // ---------------------------------------------------------------------------------------------

    private suspend fun runTransformer(
        app: Context,
        composition: Composition,
        outPath: String,
        plan: ExportPlan,
        notes: MutableList<String>,
        onProgress: (Float) -> Unit
    ) {
        withContext(Dispatchers.Main) {
            coroutineScope {
                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                var transformer: Transformer? = null

                val poller = launch {
                    while (true) {
                        delay(300)
                        val t = transformer
                        if (t != null) {
                            val state = t.getProgress(holder)
                            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                                var f = holder.progress / 100f
                                if (f < 0f) f = 0f
                                if (f > 0.99f) f = 0.99f
                                onProgress(f)
                            }
                        }
                    }
                }

                try {
                    suspendCancellableCoroutine<Unit> { cont ->
                        val listener = object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                if (cont.isActive) cont.resumeWithException(ExportFailure(describeError(exportException)))
                            }

                            override fun onFallbackApplied(
                                composition: Composition,
                                originalTransformationRequest: TransformationRequest,
                                fallbackTransformationRequest: TransformationRequest
                            ) {
                                val msg = "Perangkat menyesuaikan pengaturan encoder secara otomatis (ukuran atau format)."
                                if (!notes.contains(msg)) notes.add(msg)
                            }
                        }

                        try {
                            val encoderFactory = DefaultEncoderFactory.Builder(app)
                                .setRequestedVideoEncoderSettings(
                                    VideoEncoderSettings.Builder().setBitrate(plan.bitrate).build()
                                )
                                .setEnableFallback(true)
                                .build()
                            val t = Transformer.Builder(app)
                                .setVideoMimeType(MimeTypes.VIDEO_H264)
                                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                                .setEncoderFactory(encoderFactory)
                                .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
                                .addListener(listener)
                                .build()
                            transformer = t
                            cont.invokeOnCancellation {
                                if (Looper.myLooper() == Looper.getMainLooper()) {
                                    safeCancel(t)
                                } else {
                                    handler.post { safeCancel(t) }
                                }
                            }
                            t.start(composition, outPath)
                        } catch (e: Exception) {
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    ExportFailure("Gagal memulai ekspor. (" + (e.message ?: e.javaClass.simpleName) + ")")
                                )
                            }
                        }
                    }
                } finally {
                    poller.cancel()
                }
            }
        }
    }

    private fun safeCancel(t: Transformer) {
        try {
            t.cancel()
        } catch (e: Exception) {
            // diabaikan: ekspor sudah berhenti
        }
    }

    private fun describeError(e: ExportException): String {
        val base = when (e.errorCode) {
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_DECODER_INIT_FAILED ->
                "Format video sumber tidak bisa dibaca oleh perangkat ini."

            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED ->
                "Encoder perangkat tidak mendukung pengaturan ini. Coba turunkan kualitas atau FPS."

            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ExportException.ERROR_CODE_IO_NO_PERMISSION ->
                "Video sumber tidak bisa dibuka lagi. Impor ulang videonya."

            ExportException.ERROR_CODE_MUXING_FAILED,
            ExportException.ERROR_CODE_MUXING_TIMEOUT ->
                "Gagal menulis berkas hasil. Pastikan penyimpanan cukup."

            else -> "Ekspor gagal."
        }
        return base + " (" + e.errorCodeName + ")"
    }

    private fun cleanDir(dir: File) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                cleanDir(f)
            }
            try {
                f.delete()
            } catch (e: Exception) {
                // diabaikan
            }
        }
    }
}
