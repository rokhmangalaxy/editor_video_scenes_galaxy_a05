package com.pribadi.editorvideo.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
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
import com.pribadi.editorvideo.model.clampD
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
import kotlin.math.min

/** Kegagalan ekspor dengan pesan yang siap ditampilkan ke pengguna (bahasa Indonesia). */
class ExportFailure(message: String) : Exception(message)

/** Hasil ekspor: lokasi file yang tersimpan dan catatan (mis. fallback resolusi/FPS). */
data class ExportOutcome(val files: List<String>, val notes: List<String>)

/**
 * Mesin ekspor berbasis Media3 Transformer.
 *
 * Satu file keluaran = satu Composition berisi urutan potongan:
 *  - klip normal  : MediaItem dengan ClippingConfiguration (a..b), efek kecepatan video + audio, rotasi, cermin, teks
 *  - klip terbalik: urutan frame dari detik b mundur ke a melalui ReverseFrameOverlay, dengan rotasi, cermin, teks
 *  - beku         : gambar JPEG dari frame di detik a, durasi = d / kecepatan, dengan rotasi, cermin, teks yang sama
 */
object ExportEngine {

    private const val MAX_FREEZE_SIDE = 2560
    private const val MAX_REVERSE_SIDE = 1280
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
                var prepared: Prepared? = null
                try {
                    prepared = withContext(Dispatchers.IO) {
                        prepare(app, req, plan, job.segs, dir, idx)
                    }

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
                    prepared?.close()
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

    private class Prepared(
        val composition: Composition,
        val overlay: Bitmap?,
        val reverseOverlays: List<ReverseFrameOverlay>
    ) {
        fun close() {
            for (ro in reverseOverlays) {
                ro.close()
            }
            overlay?.recycle()
        }
    }

    /** Overlay dinamis yang mengambil frame mundur (dari seg.b ke seg.a) selama ekspor klip terbalik. */
    private class ReverseFrameOverlay(
        private val context: Context,
        private val srcUri: Uri,
        private val srcInfo: VideoInfo,
        private val seg: Seg,
        private val targetW: Int,
        private val targetH: Int,
        private val stepFps: Double,
        initialBitmap: Bitmap
    ) : BitmapOverlay() {

        private var retriever: MediaMetadataRetriever? = null
        private var currentBmp: Bitmap = initialBitmap
        private var lastStepIndex: Long = Long.MIN_VALUE

        @Synchronized
        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val uSec = presentationTimeUs / 1_000_000.0
            val revSec = clampD(seg.b - uSec * seg.sp, seg.a, seg.b)
            val stepIdx = Math.round(revSec * stepFps)
            if (stepIdx == lastStepIndex) {
                return currentBmp
            }
            lastStepIndex = stepIdx
            try {
                var r = retriever
                if (r == null) {
                    r = MediaMetadataRetriever()
                    r.setDataSource(context, srcUri)
                    retriever = r
                }
                var timeUs = Math.round(revSec * 1_000_000.0)
                val maxUs = max(0L, (srcInfo.durationMs - 1L) * 1000L)
                if (maxUs > 0L && timeUs > maxUs) timeUs = maxUs
                if (timeUs < 0L) timeUs = 0L

                var raw = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (raw == null) raw = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (raw != null) {
                    val up = VideoFrames.upright(raw, srcInfo)
                    val scaled = if (up.width == targetW && up.height == targetH) {
                        up
                    } else {
                        val s = Bitmap.createScaledBitmap(up, targetW, targetH, true)
                        if (s !== up) up.recycle()
                        s
                    }
                    val old = currentBmp
                    currentBmp = scaled
                    if (old !== scaled && !old.isRecycled) {
                        // Biarkan GC atau recycle saat close jika masih direferensikan tekstur sebelumnya
                    }
                }
            } catch (e: Exception) {
                // Gunakan currentBmp terakhir bila gagal mengambil frame tunggal
            }
            return currentBmp
        }

        @Synchronized
        fun close() {
            try {
                retriever?.release()
            } catch (e: Exception) {
                // diabaikan
            }
            retriever = null
            try {
                if (!currentBmp.isRecycled) currentBmp.recycle()
            } catch (e: Exception) {
                // diabaikan
            }
        }
    }

    private fun resolveSource(req: ExportRequest, srcIndex: Int): SourceSpec {
        if (req.sources.isNotEmpty()) {
            return req.sources.getOrElse(srcIndex) { req.sources.first() }
        }
        return SourceSpec(req.sourceUri, req.info)
    }

    /** Dijalankan di thread IO: membuat gambar beku, klip terbalik, bitmap teks, dan seluruh EditedMediaItem. */
    private fun prepare(
        app: Context,
        req: ExportRequest,
        plan: ExportPlan,
        segs: List<Seg>,
        dir: File,
        jobIdx: Int
    ): Prepared {
        val hasText = req.text1.isNotEmpty() || req.text2.isNotEmpty()
        val overlay: Bitmap? =
            if (hasText) OverlayRenderer.render(plan.width, plan.height, req.text1, req.text2) else null
        val revOverlays = ArrayList<ReverseFrameOverlay>()
        try {
            val items = ArrayList<EditedMediaItem>()
            for (i in segs.indices) {
                val seg = segs[i]
                val spec = resolveSource(req, seg.src)
                val srcUri = Uri.parse(spec.uri)
                val srcInfo = spec.info
                when {
                    seg.fz -> items.add(
                        freezeItem(app, req, plan, seg, srcUri, srcInfo, dir, "j" + jobIdx + "_" + i, overlay)
                    )
                    seg.rev -> items.add(
                        reverseItem(app, req, plan, seg, srcUri, srcInfo, dir, "r" + jobIdx + "_" + i, overlay, revOverlays)
                    )
                    else -> items.add(
                        clipItem(req, plan, seg, srcUri, srcInfo, overlay)
                    )
                }
            }
            val sequence = EditedMediaItemSequence(items)
            val builder = Composition.Builder(sequence)
            val anyAudio = if (req.sources.isNotEmpty()) {
                req.sources.any { it.info.hasAudio }
            } else {
                req.info.hasAudio
            }
            // Potongan beku/terbalik tidak punya audio: minta Transformer membuat audio senyap supaya urutan tetap utuh.
            builder.experimentalSetForceAudioTrack(anyAudio)
            // Video HDR dari ponsel lain diubah ke SDR agar encoder A05 tidak gagal.
            builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            return Prepared(builder.build(), overlay, revOverlays)
        } catch (e: Throwable) {
            for (ro in revOverlays) ro.close()
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
        val video = videoEffects(req, plan, speed, false, overlay, null)

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

        val video = videoEffects(req, plan, 1.0, true, overlay, null)
        return EditedMediaItem.Builder(mediaItem)
            .setDurationUs(durationUs)
            .setFrameRate(frameRate)
            .setEffects(Effects(emptyList<AudioProcessor>(), video))
            .build()
    }

    private fun reverseItem(
        app: Context,
        req: ExportRequest,
        plan: ExportPlan,
        seg: Seg,
        srcUri: Uri,
        srcInfo: VideoInfo,
        dir: File,
        tag: String,
        overlay: Bitmap?,
        revOverlays: MutableList<ReverseFrameOverlay>
    ): EditedMediaItem {
        val startMs = Math.round(seg.b * 1000.0)
        var bmp = VideoFrames.grabFrame(app, srcUri, startMs, srcInfo)
            ?: throw ExportFailure("Gagal mengambil frame untuk potongan terbalik. Coba ekspor ulang.")
        bmp = VideoFrames.limitLongSide(bmp, MAX_REVERSE_SIDE)
        val baseW = bmp.width
        val baseH = bmp.height
        val file = File(dir, "rev_" + tag + ".jpg")
        FileOutputStream(file).use { out ->
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                bmp.recycle()
                throw ExportFailure("Gagal menyiapkan frame awal potongan terbalik.")
            }
        }

        val effFps = min(20.0, max(10.0, plan.fps))
        val revOverlay = ReverseFrameOverlay(app, srcUri, srcInfo, seg, baseW, baseH, effFps, bmp)
        revOverlays.add(revOverlay)

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMimeType(MimeTypes.IMAGE_JPEG)
            .build()

        val durationUs = max(MIN_ITEM_US, Math.round(seg.len * 1_000_000.0))
        val frameRate = max(1, Math.round(plan.fps).toInt())

        val video = videoEffects(req, plan, 1.0, true, overlay, revOverlay)
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
     *   [frame terbalik (bila ada)] -> [kecepatan] -> [buang frame] -> [putar] -> [cermin] -> [ukuran akhir] -> [teks]
     */
    private fun videoEffects(
        req: ExportRequest,
        plan: ExportPlan,
        speed: Double,
        isImage: Boolean,
        overlay: Bitmap?,
        reverseOverlay: ReverseFrameOverlay?
    ): List<Effect> {
        val list = ArrayList<Effect>()
        if (reverseOverlay != null) {
            list.add(OverlayEffect(ImmutableList.of<TextureOverlay>(reverseOverlay)))
        }
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
