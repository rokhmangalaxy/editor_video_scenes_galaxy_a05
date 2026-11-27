package com.pribadi.editorvideo.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.pribadi.editorvideo.export.VideoFrames
import com.pribadi.editorvideo.export.VideoProbe
import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class WorkflowWaitTimeoutException(message: String) : Exception(message)

class VideoGenerationTimeoutException(
    val timedOutVideoNumber: Int,
    val partialDownloaded: List<DownloadedAiVideo>,
    message: String
) : Exception(message)

data class SingleTaskRequest(
    val taskKey: String,
    val order: Int,
    val space: AiSpaceOption,
    val prompt: String,
    val durationSec: Double,
    val safeMode: Boolean,
    val enableSafetyChecker: Boolean,
    val firstBmp: Bitmap,
    val lastBmp: Bitmap?,
    val chainFromPrev: Boolean = false
)

data class DownloadedAiVideo(
    val taskKey: String,
    val order: Int,
    val requestedDurSec: Double,
    val actualDurSec: Double,
    val file: File,
    val info: VideoInfo,
    val previewBmp: Bitmap?,
    val seed: String,
    val promptUsed: String,
    val firstBmp: Bitmap,
    val lastBmp: Bitmap?
)

data class LiveProgressTick(
    val phase: AiRunPhase,
    val workflowWaitSec: Int,
    val currentVideoNumber: Int,
    val totalVideos: Int,
    val currentVideoSec: Int,
    val statusDetail: String
)

/**
 * Klien REST murni untuk menghubungkan APK dengan Firebase Realtime Database
 * dan GitHub Actions Runner (wanvideo-r3gm-test.yml + firebase_worker.py).
 */
object WanStudioClient {

    private val PUSH_CHARS = "-_0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val rng = SecureRandom()
    private val FIREBASE_HOST_REGEX =
        Regex("^[a-z0-9][a-z0-9-]*\\.(?:firebaseio\\.com|[a-z0-9-]+\\.firebasedatabase\\.app)$")

    /** Memvalidasi & menormalkan URL Firebase persis sesuai aturan firebase_worker.py. */
    fun normalizeFirebaseUrl(raw: String): String {
        val cleaned = raw.trim().trimEnd('/')
        if (cleaned.isEmpty()) throw IllegalArgumentException("URL Firebase belum diisi.")
        val uri = try {
            URI(cleaned)
        } catch (e: Exception) {
            throw IllegalArgumentException("Format URL Firebase tidak valid.")
        }
        val host = uri.host ?: ""
        if (uri.scheme != "https" ||
            uri.userInfo != null ||
            uri.port != -1 ||
            (uri.path != null && uri.path.isNotEmpty() && uri.path != "/") ||
            uri.query != null ||
            uri.fragment != null ||
            !FIREBASE_HOST_REGEX.matches(host)
        ) {
            throw IllegalArgumentException(
                "URL Firebase harus berupa https://<nama>.firebaseio.com atau https://<nama>.firebasedatabase.app tanpa path tambahan."
            )
        }
        return cleaned
    }

    /** Menghasilkan ID project tepat 20 karakter sesuai validasi [-_A-Za-z0-9]{20} di firebase_worker.py. */
    fun generateProjectId20(): String {
        val sb = StringBuilder(20)
        repeat(20) {
            sb.append(PUSH_CHARS[rng.nextInt(PUSH_CHARS.length)])
        }
        return sb.toString()
    }

    /** Mengubah Bitmap menjadi JPEG Base64 (maks 1280px sisi panjang agar tajam & hemat kuota <8 MiB). */
    fun encodeBitmapJpegBase64(bmp: Bitmap): String {
        val w = bmp.width
        val h = bmp.height
        val maxSide = max(w, h)
        val scaled = if (maxSide > 1280) {
            val ratio = 1280.0 / maxSide.toDouble()
            val nw = max(2, Math.round(w * ratio).toInt())
            val nh = max(2, Math.round(h * ratio).toInt())
            Bitmap.createScaledBitmap(bmp, nw, nh, true)
        } else {
            bmp
        }
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 92, bos)
        if (scaled !== bmp) {
            scaled.recycle()
        }
        val bytes = bos.toByteArray()
        if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) {
            throw IllegalArgumentException("Ukuran gambar melebihi batas 8 MB.")
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun firebaseEndpoint(root: String, projectId: String, subPath: String): String =
        "$root/ai/wanstudio/$projectId/$subPath.json"

    private fun httpRequest(
        method: String,
        urlStr: String,
        headers: Map<String, String> = emptyMap(),
        bodyBytes: ByteArray? = null,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 35_000
    ): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection)
        conn.instanceFollowRedirects = false
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        for ((k, v) in headers) {
            conn.setRequestProperty(k, v)
        }
        if (bodyBytes != null) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bodyBytes.size)
            conn.outputStream.use { it.write(bodyBytes) }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    /** Mengetes koneksi Firebase Realtime Database & GitHub Repo Runner secara nyata. */
    suspend fun testConnection(cfg: AiRunnerConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            val root = normalizeFirebaseUrl(cfg.firebaseUrl)
            val owner = cfg.githubOwner.trim()
            val repo = cfg.githubRepo.trim()
            val branch = cfg.githubBranch.trim().ifEmpty { "main" }
            val wf = cfg.workflowFile.trim().ifEmpty { "wanvideo-r3gm-test.yml" }
            val token = cfg.githubToken.trim()

            if (owner.isEmpty() || repo.isEmpty() || token.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Isi GitHub Owner, Repo Runner, dan GitHub Token terlebih dahulu.")
                )
            }

            // 1. Cek baca Firebase RTDB
            val (fbCode, _) = httpRequest(
                method = "GET",
                urlStr = "$root/ai/wanstudio/_ping_check.json"
            )
            if (fbCode !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("Firebase menolak akses (HTTP $fbCode). Periksa URL & Rules Realtime Database.")
                )
            }

            // 2. Cek workflow pada repo GitHub Runner
            val ghUrl = "https://api.github.com/repos/$owner/$repo/actions/workflows/$wf"
            val (ghCode, ghBody) = httpRequest(
                method = "GET",
                urlStr = ghUrl,
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Accept" to "application/vnd.github+json",
                    "X-GitHub-Api-Version" to "2022-11-28",
                    "User-Agent" to "EditorVideo-A05"
                )
            )
            if (ghCode !in 200..299) {
                val hint = when (ghCode) {
                    401 -> "Token GitHub tidak valid atau kedaluwarsa (401)."
                    403 -> "Token tidak punya izin Actions Read/Write pada repo $owner/$repo (403)."
                    404 -> "Workflow $wf atau repo $owner/$repo tidak ditemukan (404). Salin 2 file dari tab Runner dulu."
                    else -> "GitHub mengembalikan HTTP $ghCode."
                }
                return@withContext Result.failure(IllegalStateException(hint))
            }

            val wfName = try {
                JSONObject(ghBody).optString("name", wf)
            } catch (_: Exception) {
                wf
            }
            Result.success("✓ Koneksi sukses! Firebase siap & workflow \"$wfName\" ditemukan di $owner/$repo@$branch.")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Menjalankan 1 project Firebase (berisi 1..N item) melalui GitHub Actions workflow_dispatch,
     * lengkap dengan:
     *  - Watchdog Timeout Menunggu Workflow = 3 menit (180 detik)
     *  - Watchdog Timeout Buat Video = 3 menit (180 detik) per video (di-reset setiap berganti item)
     */
    suspend fun executeProjectBatch(
        context: Context,
        cfg: AiRunnerConfig,
        tasks: List<SingleTaskRequest>,
        videoIndexOffset: Int = 0,
        overallTotalVideos: Int = tasks.size,
        onSegmentReady: ((DownloadedAiVideo) -> Unit)? = null,
        onTick: (LiveProgressTick) -> Unit
    ): List<DownloadedAiVideo> = withContext(Dispatchers.IO) {
        require(tasks.isNotEmpty()) { "Daftar tugas AI kosong." }
        val totalRequestedSec = tasks.sumOf { it.durationSec }
        val maxWorkflowSec = AiPlanner.maxWorkflowDurationFor(tasks.map { it.durationSec })
        if (totalRequestedSec > maxWorkflowSec + 0.05) {
            throw IllegalArgumentException(
                "Total durasi 1 workflow (${Fmt.one(totalRequestedSec)} dtk) melebihi batas maksimal ${Fmt.one(maxWorkflowSec)} dtk."
            )
        }
        val root = normalizeFirebaseUrl(cfg.firebaseUrl)
        val owner = cfg.githubOwner.trim()
        val repo = cfg.githubRepo.trim()
        val branch = cfg.githubBranch.trim().ifEmpty { "main" }
        val wf = cfg.workflowFile.trim().ifEmpty { "wanvideo-r3gm-test.yml" }
        val token = cfg.githubToken.trim()
        if (owner.isEmpty() || repo.isEmpty() || token.isEmpty()) {
            throw IllegalArgumentException("Lengkapi pengaturan Koneksi GitHub & Firebase di Tab Runner terlebih dahulu.")
        }

        val projectId = generateProjectId20()
        val nowMs = System.currentTimeMillis()

        onTick(
            LiveProgressTick(
                phase = AiRunPhase.UPLOADING,
                workflowWaitSec = 0,
                currentVideoNumber = videoIndexOffset + 1,
                totalVideos = overallTotalVideos,
                currentVideoSec = 0,
                statusDetail = "Mengunggah gambar frame & parameter tugas ke Firebase…"
            )
        )

        // 1. Unggah gambar-gambar unik & susun objek items
        val itemsJson = JSONObject()
        var firstImgCacheId: String? = null
        var firstImgRefBmp: Bitmap? = null

        for ((i, t) in tasks.withIndex()) {
            currentCoroutineContext().ensureActive()
            val firstId: String
            if (firstImgRefBmp === t.firstBmp && firstImgCacheId != null) {
                firstId = firstImgCacheId
            } else {
                firstId = "img_first_${i + 1}"
                val b64 = encodeBitmapJpegBase64(t.firstBmp)
                val imgPayload = JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("base64", b64)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                val (c1, _) = httpRequest(
                    method = "PUT",
                    urlStr = firebaseEndpoint(root, projectId, "images/$firstId"),
                    headers = mapOf("Content-Type" to "application/json"),
                    bodyBytes = imgPayload
                )
                if (c1 !in 200..299) {
                    throw IllegalStateException("Gagal mengunggah gambar awal ke Firebase (HTTP $c1).")
                }
                if (i == 0) {
                    firstImgRefBmp = t.firstBmp
                    firstImgCacheId = firstId
                }
            }

            var lastId: String? = null
            if (t.lastBmp != null && !t.lastBmp.isRecycled) {
                lastId = "img_last_${i + 1}"
                val lastB64 = encodeBitmapJpegBase64(t.lastBmp)
                val lastPayload = JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("base64", lastB64)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                val (c2, _) = httpRequest(
                    method = "PUT",
                    urlStr = firebaseEndpoint(root, projectId, "images/$lastId"),
                    headers = mapOf("Content-Type" to "application/json"),
                    bodyBytes = lastPayload
                )
                if (c2 !in 200..299) {
                    throw IllegalStateException("Gagal mengunggah gambar akhir ke Firebase (HTTP $c2).")
                }
            }

            val durNumber: Number = if (t.durationSec == 3.5) 3.5 else t.durationSec.toInt()
            val safety = if (t.space.supportsSafetyChecker) t.enableSafetyChecker else false
            val itemObj = JSONObject()
                .put("order", t.order)
                .put("space", t.space.spaceId)
                .put("prompt", t.prompt.trim().take(2000))
                .put("duration_seconds", durNumber)
                .put("safe_mode", t.safeMode)
                .put("enable_safety_checker", safety)
                .put("input_image", firstId)
                .put("created_at_ms", nowMs)
            if (lastId != null) {
                itemObj.put("last_image", lastId)
            }
            if (t.chainFromPrev) {
                itemObj.put("chain_from_prev", true)
            }
            itemsJson.put(t.taskKey, itemObj)
        }

        // Tulis items & status awal "queued"
        val (ci, _) = httpRequest(
            method = "PUT",
            urlStr = firebaseEndpoint(root, projectId, "items"),
            headers = mapOf("Content-Type" to "application/json"),
            bodyBytes = itemsJson.toString().toByteArray(Charsets.UTF_8)
        )
        if (ci !in 200..299) {
            throw IllegalStateException("Gagal menyimpan daftar tugas ke Firebase (HTTP $ci).")
        }

        val queuedStatus = JSONObject()
            .put("state", "queued")
            .put("created_at_ms", nowMs)
            .toString()
            .toByteArray(Charsets.UTF_8)
        httpRequest(
            method = "PUT",
            urlStr = firebaseEndpoint(root, projectId, "status"),
            headers = mapOf("Content-Type" to "application/json"),
            bodyBytes = queuedStatus
        )

        // 2. Trigger GitHub Actions workflow_dispatch
        val dispatchPayload = JSONObject()
            .put("ref", branch)
            .put(
                "inputs",
                JSONObject()
                    .put("mode", "firebase-single")
                    .put("firebase_url", root)
                    .put("task_id", projectId)
            )
            .toString()
            .toByteArray(Charsets.UTF_8)

        val dispatchUrl = "https://api.github.com/repos/$owner/$repo/actions/workflows/$wf/dispatches"
        val (ghCode, ghErr) = httpRequest(
            method = "POST",
            urlStr = dispatchUrl,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/vnd.github+json",
                "Content-Type" to "application/json",
                "X-GitHub-Api-Version" to "2022-11-28",
                "User-Agent" to "EditorVideo-A05"
            ),
            bodyBytes = dispatchPayload
        )
        if (ghCode !in 200..299) {
            throw IllegalStateException(
                "Gagal men-trigger GitHub Runner (HTTP $ghCode). Periksa Token, branch \"$branch\", dan file workflow $wf. ${ghErr.take(120)}"
            )
        }

        // 3. Poll status dengan 2 Watchdog Terpisah:
        //    - Menunggu Workflow: maks 180 detik (3 menit)
        //    - Buat Video: maks 180 detik (3 menit) per video!
        val wfStartElapsed = SystemClock.elapsedRealtime()
        var runnerPickedUp = false
        var finalWorkflowWaitSec = 0
        var activeLocalIdx = 1
        var videoTimerStartElapsed = SystemClock.elapsedRealtime()
        var finalStatusJson: JSONObject? = null
        val streamedMap = LinkedHashMap<String, DownloadedAiVideo>()

        while (true) {
            currentCoroutineContext().ensureActive()
            delay(1500L)

            val (stCode, stRaw) = try {
                httpRequest("GET", firebaseEndpoint(root, projectId, "status"))
            } catch (_: Exception) {
                0 to ""
            }

            val stObj = if (stCode in 200..299 && stRaw.trim().startsWith("{")) {
                try {
                    JSONObject(stRaw)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val stateStr = stObj?.optString("state", "queued") ?: "queued"

            if (!runnerPickedUp) {
                val wfElapsedSec = ((SystemClock.elapsedRealtime() - wfStartElapsed) / 1000L).toInt()
                if (stateStr == "queued" || stateStr.isEmpty()) {
                    if (wfElapsedSec >= AiPlanner.WORKFLOW_WAIT_TIMEOUT_SEC) {
                        throw WorkflowWaitTimeoutException(
                            "Menunggu workflow aktif melewati batas 3 menit (180 detik). Periksa apakah GitHub Actions di $owner/$repo sedang penuh atau tertahan."
                        )
                    }
                    onTick(
                        LiveProgressTick(
                            phase = AiRunPhase.WAITING_WORKFLOW,
                            workflowWaitSec = wfElapsedSec,
                            currentVideoNumber = videoIndexOffset + 1,
                            totalVideos = overallTotalVideos,
                            currentVideoSec = 0,
                            statusDetail = "Menunggu GitHub Runner mengambil 1 workflow (${tasks.size} segmen)… (${wfElapsedSec}s / 180s)"
                        )
                    )
                    continue
                } else {
                    runnerPickedUp = true
                    finalWorkflowWaitSec = wfElapsedSec
                    activeLocalIdx = 1
                    videoTimerStartElapsed = SystemClock.elapsedRealtime()
                }
            }

            // Runner sudah aktif ("running", "completed", atau "failed")
            if (stateStr == "running") {
                val reportedIdx = stObj?.optInt("current_index", 1)?.coerceIn(1, tasks.size) ?: 1
                // Jika workflow sudah menyelesaikan segmen sebelumnya dan lanjut men-generate segmen berikutnya,
                // unduh segera segmen yang sudah selesai agar langsung tampil di Tab Status!
                if (reportedIdx > 1) {
                    val finishedBeforeCurrent = tasks.take(reportedIdx - 1)
                        .filter { it.taskKey !in streamedMap }
                    if (finishedBeforeCurrent.isNotEmpty()) {
                        val newlyReady = downloadAvailableResults(
                            context = context,
                            root = root,
                            projectId = projectId,
                            tasks = finishedBeforeCurrent,
                            statusSummary = stObj
                        )
                        for (dl in newlyReady) {
                            streamedMap[dl.taskKey] = dl
                            onSegmentReady?.invoke(dl)
                        }
                    }
                }
                if (reportedIdx != activeLocalIdx) {
                    activeLocalIdx = reportedIdx
                    // Reset timer 3 menit setiap kali berpindah ke video berikutnya di dalam 1 workflow yang sama!
                    videoTimerStartElapsed = SystemClock.elapsedRealtime()
                }
                val vidElapsedSec = ((SystemClock.elapsedRealtime() - videoTimerStartElapsed) / 1000L).toInt()
                val globalVidNum = videoIndexOffset + activeLocalIdx
                if (vidElapsedSec >= AiPlanner.VIDEO_GEN_TIMEOUT_SEC) {
                    // Selamatkan video-video sebelumnya yang sudah selesai bila ada
                    val remainingDone = tasks.take(max(0, activeLocalIdx - 1))
                        .filter { it.taskKey !in streamedMap }
                    val newlySaved = downloadAvailableResults(
                        context = context,
                        root = root,
                        projectId = projectId,
                        tasks = remainingDone,
                        statusSummary = stObj
                    )
                    for (dl in newlySaved) {
                        streamedMap[dl.taskKey] = dl
                    }
                    throw VideoGenerationTimeoutException(
                        timedOutVideoNumber = globalVidNum,
                        partialDownloaded = streamedMap.values.sortedBy { it.order },
                        message = "Pembuatan Video #$globalVidNum melewati batas 3 menit (180 detik) per video."
                    )
                }
                val doneSoFar = streamedMap.size
                val statusPrefix = if (doneSoFar > 0) {
                    "Video #1..#$doneSoFar berhasil ✓ · lanjut membuat Video #$globalVidNum/$overallTotalVideos"
                } else {
                    "ZeroGPU sedang membuat Video #$globalVidNum/$overallTotalVideos"
                }
                onTick(
                    LiveProgressTick(
                        phase = AiRunPhase.GENERATING_VIDEO,
                        workflowWaitSec = finalWorkflowWaitSec,
                        currentVideoNumber = globalVidNum,
                        totalVideos = overallTotalVideos,
                        currentVideoSec = vidElapsedSec,
                        statusDetail = "$statusPrefix… (${vidElapsedSec}s / 180s)"
                    )
                )
            } else if (stateStr == "completed" || stateStr == "failed") {
                finalStatusJson = stObj
                break
            }
        }

        // 4. Unduh berkas MP4 hasil akhir yang belum terunduh saat live streaming
        onTick(
            LiveProgressTick(
                phase = AiRunPhase.DOWNLOADING,
                workflowWaitSec = finalWorkflowWaitSec,
                currentVideoNumber = videoIndexOffset + tasks.size,
                totalVideos = overallTotalVideos,
                currentVideoSec = 0,
                statusDetail = "Mengunduh berkas MP4 hasil AI dari Firebase…"
            )
        )

        val remainingTasks = tasks.filter { it.taskKey !in streamedMap }
        val newlyDownloaded = downloadAvailableResults(
            context = context,
            root = root,
            projectId = projectId,
            tasks = remainingTasks,
            statusSummary = finalStatusJson
        )
        for (dl in newlyDownloaded) {
            streamedMap[dl.taskKey] = dl
            onSegmentReady?.invoke(dl)
        }
        val downloaded = streamedMap.values.sortedBy { it.order }

        // Bersihkan node sementara di Firebase secara best-effort agar hemat ruang DB
        try {
            httpRequest("DELETE", "$root/ai/wanstudio/$projectId.json")
        } catch (_: Exception) {
        }

        if (downloaded.isEmpty()) {
            val errMsg = finalStatusJson?.optString("error")?.takeIf { it.isNotEmpty() }
                ?: "Generasi AI gagal tanpa berkas keluaran. Periksa kuota Space ZeroGPU."
            throw IllegalStateException(errMsg)
        }
        downloaded
    }

    private fun downloadAvailableResults(
        context: Context,
        root: String,
        projectId: String,
        tasks: List<SingleTaskRequest>,
        statusSummary: JSONObject? = null
    ): List<DownloadedAiVideo> {
        if (tasks.isEmpty()) return emptyList()
        val outDir = File(context.filesDir, "ai_videos")
        if (!outDir.exists()) outDir.mkdirs()

        val itemResultsObj = statusSummary?.optJSONObject("item_results")
        val results = ArrayList<DownloadedAiVideo>()

        for (t in tasks) {
            try {
                val (code, raw) = httpRequest(
                    method = "GET",
                    urlStr = firebaseEndpoint(root, projectId, "results/${t.taskKey}"),
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = 60_000
                )
                if (code !in 200..299 || !raw.trim().startsWith("{")) continue
                val obj = JSONObject(raw)
                val b64 = obj.optString("base64", "")
                if (b64.isEmpty()) continue
                val mp4Bytes = Base64.decode(b64, Base64.DEFAULT)
                if (mp4Bytes.isEmpty()) continue

                val file = File(outDir, "ai_${System.currentTimeMillis()}_${t.taskKey}.mp4")
                FileOutputStream(file).use { it.write(mp4Bytes) }

                val uri = Uri.fromFile(file)
                val info = VideoProbe.probe(context, uri) ?: continue
                val actualDur = max(0.1, info.durationMs / 1000.0)
                val prevBmp = VideoFrames.grabFrame(context, uri, 80L, info)?.let {
                    VideoFrames.limitLongSide(it, 720)
                }

                val itemMeta = itemResultsObj?.optJSONObject(t.taskKey) ?: statusSummary
                val seedStr = itemMeta?.opt("seed")?.toString()?.takeIf { it != "null" && it.isNotEmpty() } ?: "-"

                results.add(
                    DownloadedAiVideo(
                        taskKey = t.taskKey,
                        order = t.order,
                        requestedDurSec = t.durationSec,
                        actualDurSec = actualDur,
                        file = file,
                        info = info,
                        previewBmp = prevBmp,
                        seed = seedStr,
                        promptUsed = t.prompt,
                        firstBmp = t.firstBmp,
                        lastBmp = t.lastBmp
                    )
                )
            } catch (_: Exception) {
                // Lanjutkan ke item berikutnya bila satu item gagal diunduh
            }
        }
        return results
    }

    /**
     * Mengekstrak frame paling akhir dari berkas MP4 hasil segmen sebelumnya
     * untuk dipakai sebagai input_image pada mode Sambung Berantai (Chained Continuity).
     */
    fun extractLastFrameOfMp4(context: Context, file: File, info: VideoInfo): Bitmap? {
        val uri = Uri.fromFile(file)
        val endUs = max(0L, (info.durationMs - 45L) * 1000L)
        val exact = VideoFrames.grabExactFrame(context, uri, endUs, info)
            ?: VideoFrames.grabFrame(context, uri, max(0L, info.durationMs - 50L), info)
        return exact?.let { VideoFrames.limitLongSide(it, 1080) }
    }

    /**
     * Menggabungkan 2..3 berkas MP4 hasil AI menjadi 1 berkas MP4 utuh menggunakan Media3 Transformer
     * (mendukung batas potong akhir per item untuk presisi durasi Replace Scene).
     */
    suspend fun mergeSegmentsToSingleMp4(
        context: Context,
        pieces: List<Triple<File, VideoInfo, Double>>,
        outFile: File
    ): Boolean = withContext(Dispatchers.Main) {
        if (pieces.isEmpty()) return@withContext false
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val editedItems = ArrayList<EditedMediaItem>(pieces.size)
        var anyAudio = false

        for ((f, inf, clipDurSec) in pieces) {
            if (inf.hasAudio) anyAudio = true
            val endMs = max(100L, min(inf.durationMs, Math.round(clipDurSec * 1000.0)))
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(f))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0L)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()
            val eb = EditedMediaItem.Builder(mediaItem)
            if (!inf.hasAudio) eb.setRemoveAudio(true)
            editedItems.add(eb.build())
        }

        val seq = EditedMediaItemSequence(editedItems)
        val comp = Composition.Builder(seq)
            .experimentalSetForceAudioTrack(anyAudio)
            .build()

        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val encoderFactory = DefaultEncoderFactory.Builder(app)
                    .setEnableFallback(true)
                    .build()
                val transformer = Transformer.Builder(app)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()

                cont.invokeOnCancellation {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        try { transformer.cancel() } catch (_: Exception) {}
                    } else {
                        handler.post { try { transformer.cancel() } catch (_: Exception) {} }
                    }
                }
                if (outFile.exists()) outFile.delete()
                transformer.start(comp, outFile.absolutePath)
            }
            outFile.exists() && outFile.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Untuk Mode Replace Scene "Video Saja (Pertahankan Audio Asli)":
     * Mengambil visual dari berkas AI [aiVideoFile] sepanjang [clipDurSec] detik dan memasangkan
     * trek audio asli dari [origSourceUri] mulai detik [origStartSec] (dengan kecepatan [origSpeed]).
     */
    suspend fun attachOriginalAudioToAiClip(
        context: Context,
        aiVideoFile: File,
        aiVideoInfo: VideoInfo,
        clipDurSec: Double,
        origSourceUri: Uri,
        origInfo: VideoInfo,
        origStartSec: Double,
        origSpeed: Double,
        outFile: File
    ): File {
        // Bila video asli memang bisu dan video AI juga bisu, langsung pakai berkas AI apa adanya
        if (!origInfo.hasAudio && !aiVideoInfo.hasAudio) {
            return aiVideoFile
        }

        // Jalur cepat tanpa re-encode (MediaExtractor + MediaMuxer) bila kecepatan asli normal (1.0x)
        if (abs(origSpeed - 1.0) < 0.01) {
            val fastOk = withContext(Dispatchers.IO) {
                remuxVideoWithOrigAudioFast(
                    context = context,
                    aiVideoFile = aiVideoFile,
                    clipDurSec = clipDurSec,
                    origSourceUri = if (origInfo.hasAudio) origSourceUri else null,
                    origStartSec = origStartSec,
                    outFile = outFile
                )
            }
            if (fastOk && outFile.exists() && outFile.length() > 0L) {
                return outFile
            }
        }

        // Fallback via Media3 Transformer (mendukung perubahan pitch-preserving speed & transkode audio)
        val transOk = muxViaTransformer(
            context = context,
            aiVideoFile = aiVideoFile,
            aiVideoInfo = aiVideoInfo,
            clipDurSec = clipDurSec,
            origSourceUri = origSourceUri,
            origInfo = origInfo,
            origStartSec = origStartSec,
            origSpeed = origSpeed,
            outFile = outFile
        )
        return if (transOk && outFile.exists() && outFile.length() > 0L) outFile else aiVideoFile
    }

    private fun remuxVideoWithOrigAudioFast(
        context: Context,
        aiVideoFile: File,
        clipDurSec: Double,
        origSourceUri: Uri?,
        origStartSec: Double,
        outFile: File
    ): Boolean {
        val vidEx = MediaExtractor()
        val audEx = if (origSourceUri != null) MediaExtractor() else null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            vidEx.setDataSource(aiVideoFile.absolutePath)
            var vidTrackIn = -1
            var vidFormat: MediaFormat? = null
            for (i in 0 until vidEx.trackCount) {
                val fmt = vidEx.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    vidTrackIn = i
                    vidFormat = fmt
                    break
                }
            }
            if (vidTrackIn < 0 || vidFormat == null) return false
            vidEx.selectTrack(vidTrackIn)

            var audTrackIn = -1
            var audFormat: MediaFormat? = null
            if (audEx != null && origSourceUri != null) {
                audEx.setDataSource(context, origSourceUri, null)
                for (i in 0 until audEx.trackCount) {
                    val fmt = audEx.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audTrackIn = i
                        audFormat = fmt
                        break
                    }
                }
                if (audTrackIn >= 0) {
                    audEx.selectTrack(audTrackIn)
                }
            }

            outFile.parentFile?.mkdirs()
            if (outFile.exists()) outFile.delete()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxVidTrack = muxer.addTrack(vidFormat)
            val muxAudTrack = if (audTrackIn >= 0 && audFormat != null) {
                muxer.addTrack(audFormat)
            } else {
                -1
            }
            muxer.start()
            muxerStarted = true

            val maxDurUs = max(100_000L, Math.round(clipDurSec * 1_000_000.0))
            val buf = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            // Salin paket video AI tanpa re-encode
            while (true) {
                buf.clear()
                val sampleSize = vidEx.readSampleData(buf, 0)
                if (sampleSize < 0) break
                val sampleTimeUs = vidEx.sampleTime
                if (sampleTimeUs > maxDurUs + 40_000L) break
                val exFlags = vidEx.sampleFlags
                var flags = 0
                if ((exFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                info.set(0, sampleSize, max(0L, sampleTimeUs), flags)
                muxer.writeSampleData(muxVidTrack, buf, info)
                vidEx.advance()
            }

            // Salin paket audio asli mulai origStartSec sepanjang maxDurUs
            if (audEx != null && muxAudTrack >= 0) {
                val origStartUs = max(0L, Math.round(origStartSec * 1_000_000.0))
                val origEndUs = origStartUs + maxDurUs
                audEx.seekTo(origStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                var lastAudPts = -1L
                while (true) {
                    buf.clear()
                    val sampleSize = audEx.readSampleData(buf, 0)
                    if (sampleSize < 0) break
                    val rawPts = audEx.sampleTime
                    if (rawPts > origEndUs) break
                    if (rawPts >= origStartUs - 25_000L) {
                        var shiftedPts = max(0L, rawPts - origStartUs)
                        if (shiftedPts <= lastAudPts) {
                            shiftedPts = lastAudPts + 1L
                        }
                        lastAudPts = shiftedPts
                        val exFlags = audEx.sampleFlags
                        var flags = 0
                        if ((exFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }
                        info.set(0, sampleSize, shiftedPts, flags)
                        muxer.writeSampleData(muxAudTrack, buf, info)
                    }
                    audEx.advance()
                }
            }

            muxer.stop()
            muxerStarted = false
            return true
        } catch (_: Exception) {
            if (outFile.exists()) outFile.delete()
            return false
        } finally {
            try { vidEx.release() } catch (_: Exception) {}
            try { audEx?.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer?.stop()
            } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private suspend fun muxViaTransformer(
        context: Context,
        aiVideoFile: File,
        aiVideoInfo: VideoInfo,
        clipDurSec: Double,
        origSourceUri: Uri,
        origInfo: VideoInfo,
        origStartSec: Double,
        origSpeed: Double,
        outFile: File
    ): Boolean = withContext(Dispatchers.Main) {
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val vidEndMs = max(100L, min(aiVideoInfo.durationMs, Math.round(clipDurSec * 1000.0)))

        val vidMediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(aiVideoFile))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0L)
                    .setEndPositionMs(vidEndMs)
                    .build()
            )
            .build()
        val vidEdited = EditedMediaItem.Builder(vidMediaItem)
            .setRemoveAudio(true)
            .build()
        val vidSeq = EditedMediaItemSequence(listOf(vidEdited))

        val sequences = ArrayList<EditedMediaItemSequence>(2)
        sequences.add(vidSeq)

        if (origInfo.hasAudio) {
            val sp = max(0.1, origSpeed)
            val audStartMs = max(0L, Math.round(origStartSec * 1000.0))
            val audSpanMs = max(100L, Math.round(clipDurSec * sp * 1000.0))
            val audEndMs = min(origInfo.durationMs, max(audStartMs + 50L, audStartMs + audSpanMs))
            val audMediaItem = MediaItem.Builder()
                .setUri(origSourceUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(audStartMs)
                        .setEndPositionMs(audEndMs)
                        .build()
                )
                .build()
            val audProcessors = if (abs(sp - 1.0) > 0.01) {
                val sonic = SonicAudioProcessor()
                sonic.setSpeed(sp.toFloat())
                sonic.setPitch(1f)
                listOf<AudioProcessor>(sonic)
            } else {
                emptyList()
            }
            val audEdited = EditedMediaItem.Builder(audMediaItem)
                .setRemoveVideo(true)
                .setEffects(Effects(audProcessors, emptyList<Effect>()))
                .build()
            sequences.add(EditedMediaItemSequence(listOf(audEdited)))
        }

        val comp = Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(origInfo.hasAudio)
            .build()

        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val encoderFactory = DefaultEncoderFactory.Builder(app)
                    .setEnableFallback(true)
                    .build()
                val transformer = Transformer.Builder(app)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()

                cont.invokeOnCancellation {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        try { transformer.cancel() } catch (_: Exception) {}
                    } else {
                        handler.post { try { transformer.cancel() } catch (_: Exception) {} }
                    }
                }
                outFile.parentFile?.mkdirs()
                if (outFile.exists()) outFile.delete()
                transformer.start(comp, outFile.absolutePath)
            }
            outFile.exists() && outFile.length() > 0L
        } catch (_: Exception) {
            false
        }
    }
}
