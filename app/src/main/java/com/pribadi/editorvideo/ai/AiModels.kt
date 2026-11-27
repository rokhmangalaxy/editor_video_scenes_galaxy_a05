package com.pribadi.editorvideo.ai

import android.graphics.Bitmap
import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.model.VideoInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** 4 tab utama pada Studio Video AI. */
enum class AiTab(val label: String) {
    CREATE("Buat"),
    PROMPTS("Prompt"),
    STATUS("Status"),
    RUNNER("Runner")
}

/** Dua mode kerja utama fitur AI pada timeline. */
enum class AiWorkMode(val label: String, val sub: String) {
    INSERT(
        "Mode Sisipkan",
        "Belah di posisi Seek & sisipkan 1–3 video AI"
    ),
    REPLACE_SCENE(
        "Replace Scene",
        "Ganti klip terpilih (fleksibel ≤10 dtk ZeroGPU)"
    )
}

/** Mode pembuatan video AI: Langsung 1 Video Tunggal (hingga 10 dtk) vs Multi-Segmen (2–3 klip). */
enum class AiGenerationMode(val label: String, val sub: String) {
    SINGLE(
        "Mode Tunggal (1 Video)",
        "Langsung membuat 1 video utuh sesuai durasi (hingga 10.0 dtk, tidak dipecah 5 dtk × 2)."
    ),
    MULTI_SEGMENT(
        "Mode Multi-Segmen",
        "Membuat 2–3 segmen berurutan dalam 1 workflow (cocok untuk prompt bertahap / 3×3.5 dtk)."
    )
}

/** Paket durasi khusus Mode Sisipkan: mendukung paket Multi-Segmen maupun 1 Video Tunggal. */
enum class AiInsertPreset(
    val id: String,
    val label: String,
    val badge: String,
    val sub: String,
    val segDurs: List<Double>,
    val autoMergeDefault: Boolean
) {
    SINGLE_10(
        id = "10_single",
        label = "10 dtk (1 Video)",
        badge = "1 Video",
        sub = "Langsung 1 × 10.0 dtk utuh",
        segDurs = listOf(10.0),
        autoMergeDefault = false
    ),
    SINGLE_7(
        id = "7",
        label = "7 dtk (1 Video)",
        badge = "1 Video",
        sub = "Langsung 1 × 7.0 dtk utuh",
        segDurs = listOf(7.0),
        autoMergeDefault = false
    ),
    THREE_BY_35(
        id = "3x3.5",
        label = "3 × 3.5 dtk",
        badge = "3 Hasil",
        sub = "Total 10.5 dtk · 3 video",
        segDurs = listOf(3.5, 3.5, 3.5),
        autoMergeDefault = false
    ),
    TWO_BY_5(
        id = "2x5",
        label = "2 × 5 dtk",
        badge = "2 Hasil",
        sub = "Total 10.0 dtk · 2 video",
        segDurs = listOf(5.0, 5.0),
        autoMergeDefault = false
    ),
    FULL_10(
        id = "10",
        label = "2×5→10 dtk",
        badge = "2 Seg→10s",
        sub = "2×5 dtk berantai digabung 10s",
        segDurs = listOf(5.0, 5.0),
        autoMergeDefault = true
    )
}

/** Cara mengelola 2 atau 3 hasil video yang dihasilkan dalam satu rencana. */
enum class AiMultiStrategy(val label: String, val sub: String) {
    CHAIN(
        "Sambung Berantai",
        "Frame akhir hasil #1 menjadi frame awal hasil #2 dst. (cerita menyambung)"
    ),
    VARIATIONS(
        "Variasi Take",
        "Semua hasil dibuat dari frame awal sama (pilih take terbaik nanti)"
    )
}

/** Cara memasukkan multi-hasil ke timeline. */
enum class AiTimelineJoinStyle(val label: String) {
    SEPARATE("Klip Terpisah Berurutan"),
    MERGED("Gabung Jadi 1 Klip Utuh")
}

/** Opsi perlakuan audio pada Mode Replace Scene. */
enum class AiReplaceAudioMode(val label: String, val sub: String) {
    VIDEO_ONLY(
        "Video Saja (Audio Asli)",
        "Pertahankan audio dari video scene asli"
    ),
    VIDEO_PLUS_AUDIO(
        "Video + Audio AI",
        "Gunakan video sekaligus audio dari hasil AI"
    )
}

/** Pilihan HuggingFace Space sesuai firebase_worker.py. */
enum class AiSpaceOption(
    val spaceId: String,
    val shortName: String,
    val displayLabel: String,
    val supportsSafetyChecker: Boolean
) {
    R3GM(
        spaceId = "r3gm/wan2-2-fp8da-aoti-preview",
        shortName = "r3gm",
        displayLabel = "r3gm / wan2.2-fp8da",
        supportsSafetyChecker = true
    ),
    KULKAS(
        spaceId = "kulkas2pintu/wan777",
        shortName = "kulkas",
        displayLabel = "kulkas2pintu / wan777",
        supportsSafetyChecker = false
    ),
    JCISHERE(
        spaceId = "JCisHERE/wan2-2-i2v-v3",
        shortName = "jcishere",
        displayLabel = "JCisHERE / i2v-v3",
        supportsSafetyChecker = false
    );

    companion object {
        fun fromId(id: String?): AiSpaceOption =
            entries.firstOrNull { it.spaceId == id } ?: R3GM
    }
}

/** Satu entri pada Tab Management Prompt. */
data class AiPromptItem(
    val id: String,
    val title: String,
    val category: String,
    val body: String,
    val favorite: Boolean = false
) {
    companion object {
        val CATEGORIES = listOf("Kamera", "Transisi", "Sinematik", "Aksi", "Kustom")

        fun defaultList(): List<AiPromptItem> = listOf(
            AiPromptItem(
                id = "pm_dolly_warm",
                title = "Dolly-In Sinematik Hangat",
                category = "Kamera",
                body = "Kamera dolly-in perlahan mendekati subjek utama, pencahayaan golden hour hangat, depth of field sinematik 35mm, gerakan natural dan tajam.",
                favorite = true
            ),
            AiPromptItem(
                id = "pm_smooth_bridge",
                title = "Transisi Halus Antar Adegan",
                category = "Transisi",
                body = "Gerakan kamera mengalir mulus dari komposisi frame awal menuju komposisi frame akhir tanpa patah, pencahayaan beradaptasi alami.",
                favorite = true
            ),
            AiPromptItem(
                id = "pm_orbit_slow",
                title = "Orbit Heroik Slow-Motion",
                category = "Aksi",
                body = "Kamera mengorbit 45 derajat mengelilingi subjek dalam gerakan slow-motion halus, partikel cahaya lembut mengambang di udara.",
                favorite = false
            ),
            AiPromptItem(
                id = "pm_dramatic_atmo",
                title = "Atmosfer Sinematik Dramatis",
                category = "Sinematik",
                body = "Kontras bayangan sinematik lembut, pantulan cahaya hangat realistis, hembusan angin pelan menggerakkan latar belakang secara hidup.",
                favorite = false
            ),
            AiPromptItem(
                id = "pm_chain_cont",
                title = "Lanjutan Cerita (Segmen 2/3)",
                category = "Kamera",
                body = "Melanjutkan gerakan kamera sebelumnya secara konsisten, kamera sedikit naik memperlihatkan suasana sekitar yang lebih luas.",
                favorite = false
            )
        )

        fun toJsonString(items: List<AiPromptItem>): String {
            val root = JSONObject()
            root.put("schema", "editorvideo_prompts_v1")
            val arr = JSONArray()
            for (it in items) {
                val o = JSONObject()
                o.put("id", it.id)
                o.put("title", it.title)
                o.put("category", it.category)
                o.put("body", it.body)
                o.put("favorite", it.favorite)
                arr.put(o)
            }
            root.put("prompts", arr)
            return root.toString(2)
        }

        fun parseJsonString(raw: String): List<AiPromptItem>? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            return try {
                val arr: JSONArray = if (trimmed.startsWith("[")) {
                    JSONArray(trimmed)
                } else {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("prompts") ?: return null
                }
                val out = ArrayList<AiPromptItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("title").trim()
                    val body = o.optString("body").trim()
                    if (title.isEmpty() || body.isEmpty()) continue
                    val cat = o.optString("category", "Kustom").ifBlank { "Kustom" }
                    val id = o.optString("id").ifBlank { "pm_${System.currentTimeMillis()}_$i" }
                    out.add(
                        AiPromptItem(
                            id = id,
                            title = title.take(80),
                            category = cat.take(30),
                            body = body.take(2000),
                            favorite = o.optBoolean("favorite", false)
                        )
                    )
                }
                out
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Konfigurasi koneksi Firebase Realtime Database & GitHub Runner. */
data class AiRunnerConfig(
    val firebaseUrl: String = "",
    val githubOwner: String = "",
    val githubRepo: String = "",
    val githubBranch: String = "main",
    val workflowFile: String = "wanvideo-r3gm-test.yml",
    val githubToken: String = ""
) {
    val isComplete: Boolean
        get() = firebaseUrl.trim().isNotEmpty() &&
            githubOwner.trim().isNotEmpty() &&
            githubRepo.trim().isNotEmpty() &&
            githubBranch.trim().isNotEmpty() &&
            githubToken.trim().isNotEmpty()
}

/** Rencana eksekusi segmen ZeroGPU untuk Mode Sisipkan maupun Mode Replace Scene. */
data class AiExecutionPlan(
    val workMode: AiWorkMode,
    val segDurs: List<Double>,
    val targetTotalSec: Double,
    val autoMergeDefault: Boolean,
    val sceneOrigLenSec: Double = 0.0,
    val overZeroGpuLimit: Boolean = false,
    val note: String = ""
) {
    val count: Int get() = segDurs.size
}

/** Fase progres eksekusi beserta status timeout terpisah. */
enum class AiRunPhase {
    IDLE,
    UPLOADING,
    WAITING_WORKFLOW,
    GENERATING_VIDEO,
    DOWNLOADING,
    COMPLETED,
    WORKFLOW_TIMEOUT,
    VIDEO_TIMEOUT,
    FAILED
}

/** Satu hasil segmen video AI yang sudah diunduh (atau gagal/timeout untuk di-regenerate satuan). */
data class AiGeneratedSegment(
    val id: String,
    val segNumber: Int,
    val requestedDurSec: Double,
    val actualDurSec: Double,
    val mp4File: File?,
    val info: VideoInfo?,
    val previewBmp: Bitmap?,
    val seed: String,
    val promptUsed: String,
    val inputFirstBmp: Bitmap?,
    val inputLastBmp: Bitmap?,
    val selected: Boolean = true,
    val failed: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Satu Kandidat Hasil Generate AI (menyimpan seluruh segmen, snapshot posisi timeline,
 * dan metadata run agar pengguna bisa generate berkali-kali lalu memilih kandidat terbaik).
 */
data class AiCandidateBatch(
    val id: String,
    val candidateNumber: Int,
    val createdAtLabel: String,
    val workMode: AiWorkMode,
    val genMode: AiGenerationMode,
    val spaceLabel: String,
    val summaryLabel: String,
    val plan: AiExecutionPlan,
    val snapshotInsertSeekSec: Double,
    val snapshotReplaceSegIdx: Int,
    val segments: List<AiGeneratedSegment>,
    val isFavorite: Boolean = false
)

/** Status sinkronisasi firebase_worker.py v3 di repositori GitHub Runner pengguna. */
enum class WorkerSyncStatus {
    UNKNOWN,
    CHECKING,
    UP_TO_DATE,
    AUTO_UPDATED,
    NEEDS_MANUAL_COPY,
    ERROR
}

/** Perencana durasi & aturan timeout ZeroGPU. */
object AiPlanner {
    /** Batas keras durasi Replace Scene pada Space ZeroGPU: maksimal 10.0 detik. */
    const val ZEROGPU_MAX_REPLACE_SEC = 10.0
    const val ZEROGPU_MIN_REPLACE_SEC = 1.0

    /**
     * Aturan 1 Workflow:
     * - Maksimal total durasi dalam 1 workflow adalah 10.0 detik.
     * - Khusus jika seluruh segmen memakai durasi 3.5 detik, batas maksimal 1 workflow menjadi 10.5 detik (3 × 3.5 dtk).
     */
    const val MAX_WORKFLOW_SEC = 10.0
    const val MAX_WORKFLOW_35_SEC = 10.5

    fun maxWorkflowDurationFor(segDurs: List<Double>): Double {
        val all35 = segDurs.isNotEmpty() && segDurs.all { kotlin.math.abs(it - 3.5) < 0.01 }
        return if (all35) MAX_WORKFLOW_35_SEC else MAX_WORKFLOW_SEC
    }

    /** Timeout menunggu workflow aktif = 3 menit (180 detik). */
    const val WORKFLOW_WAIT_TIMEOUT_SEC = 180

    /** Timeout buat video = 3 menit (180 detik) per video. */
    const val VIDEO_GEN_TIMEOUT_SEC = 180

    fun planInsert(
        preset: AiInsertPreset,
        genMode: AiGenerationMode = AiGenerationMode.MULTI_SEGMENT,
        singleDurSec: Double = 10.0
    ): AiExecutionPlan {
        if (genMode == AiGenerationMode.SINGLE) {
            val clamped = min(MAX_WORKFLOW_SEC, max(ZEROGPU_MIN_REPLACE_SEC, singleDurSec))
            val rounded = Math.round(clamped * 10.0) / 10.0
            return AiExecutionPlan(
                workMode = AiWorkMode.INSERT,
                segDurs = listOf(rounded),
                targetTotalSec = rounded,
                autoMergeDefault = false,
                note = "Mode Tunggal · 1 Video utuh langsung (${Fmt.one(rounded)} dtk, tanpa dipecah)"
            )
        }
        val total = preset.segDurs.sum()
        val note = when (preset) {
            AiInsertPreset.SINGLE_10 -> "Mode Tunggal · 1 × 10.0 dtk langsung (1 video utuh)"
            AiInsertPreset.SINGLE_7 -> "1 Workflow · 1 × 7.0 dtk utuh"
            AiInsertPreset.THREE_BY_35 -> "1 Workflow · 3 × 3.5 dtk berurutan (maks 10.5 dtk)"
            AiInsertPreset.TWO_BY_5 -> "1 Workflow · 2 × 5.0 dtk berurutan (total 10.0 dtk)"
            AiInsertPreset.FULL_10 -> "1 Workflow · 2 segmen berantai × 5.0 dtk → digabung 10.0 dtk"
        }
        return AiExecutionPlan(
            workMode = AiWorkMode.INSERT,
            segDurs = preset.segDurs,
            targetTotalSec = total,
            autoMergeDefault = preset.autoMergeDefault,
            note = note
        )
    }

    /**
     * Merencanakan segmen ZeroGPU untuk Mode Replace Scene dengan target durasi fleksibel [1.0 .. 10.0] detik:
     * - Bila [genMode] == SINGLE: langsung membuat 1 video tunggal sesuai durasi target (hingga 10.0 dtk, bukan 5 dtk × 2)!
     * - Bila [genMode] == MULTI_SEGMENT: memecah durasi > 7.0 dtk menjadi 2 segmen berantai dalam 1 workflow.
     */
    fun planReplace(
        requestedSec: Double,
        sceneOrigLenSec: Double,
        genMode: AiGenerationMode = AiGenerationMode.SINGLE
    ): AiExecutionPlan {
        val clamped = min(ZEROGPU_MAX_REPLACE_SEC, max(ZEROGPU_MIN_REPLACE_SEC, requestedSec))
        val rounded = Math.round(clamped * 10.0) / 10.0
        if (genMode == AiGenerationMode.SINGLE) {
            val reqDur = max(3.5, rounded)
            val note = if (rounded < 3.45) {
                "Mode Tunggal · 1 × 3.5 dtk → auto-trim presisi ke ${Fmt.one(rounded)} dtk"
            } else {
                "Mode Tunggal · 1 Video langsung (${Fmt.one(rounded)} dtk utuh, bukan 2 segmen)"
            }
            return AiExecutionPlan(
                workMode = AiWorkMode.REPLACE_SCENE,
                segDurs = listOf(reqDur),
                targetTotalSec = rounded,
                autoMergeDefault = false,
                sceneOrigLenSec = sceneOrigLenSec,
                overZeroGpuLimit = sceneOrigLenSec > ZEROGPU_MAX_REPLACE_SEC + 0.05,
                note = note
            )
        }
        val segs: List<Double>
        val note: String
        when {
            rounded <= 3.5 -> {
                segs = listOf(3.5)
                note = if (rounded < 3.45) {
                    "1 Workflow · 1 × 3.5 dtk → auto-trim presisi ke ${Fmt.one(rounded)} dtk"
                } else {
                    "1 Workflow · 1 × 3.5 dtk pas"
                }
            }
            rounded <= 5.0 -> {
                segs = listOf(5.0)
                note = if (rounded < 4.95) {
                    "1 Workflow · 1 × 5.0 dtk → auto-trim presisi ke ${Fmt.one(rounded)} dtk"
                } else {
                    "1 Workflow · 1 × 5.0 dtk pas"
                }
            }
            rounded <= 7.0 -> {
                segs = listOf(7.0)
                note = if (rounded < 6.95) {
                    "1 Workflow · 1 × 7.0 dtk → auto-trim presisi ke ${Fmt.one(rounded)} dtk"
                } else {
                    "1 Workflow · 1 × 7.0 dtk pas"
                }
            }
            rounded <= 8.5 -> {
                segs = listOf(5.0, 3.5)
                note = if (rounded < 8.45) {
                    "1 Workflow · 2 segmen berurutan (5.0s + 3.5s) → auto-trim ke ${Fmt.one(rounded)} dtk"
                } else {
                    "1 Workflow · 2 segmen berurutan (5.0s + 3.5s = 8.5 dtk)"
                }
            }
            else -> {
                segs = listOf(5.0, 5.0)
                note = if (rounded < 9.95) {
                    "2 segmen berantai (2 × 5.0s) → auto-trim ke ${Fmt.one(rounded)} dtk"
                } else {
                    "2 segmen berantai (2 × 5.0s = 10.0 dtk · batas maks ZeroGPU)"
                }
            }
        }
        return AiExecutionPlan(
            workMode = AiWorkMode.REPLACE_SCENE,
            segDurs = segs,
            targetTotalSec = rounded,
            autoMergeDefault = false,
            sceneOrigLenSec = sceneOrigLenSec,
            overZeroGpuLimit = sceneOrigLenSec > ZEROGPU_MAX_REPLACE_SEC + 0.05,
            note = note
        )
    }
}
