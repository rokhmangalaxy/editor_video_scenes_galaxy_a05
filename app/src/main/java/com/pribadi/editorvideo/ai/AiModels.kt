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

/** Paket durasi khusus Mode Sisipkan sesuai spesifikasi: 3×3.5 dtk, 2×5 dtk, 7 dtk, 10 dtk. */
enum class AiInsertPreset(
    val id: String,
    val label: String,
    val badge: String,
    val sub: String,
    val segDurs: List<Double>,
    val autoMergeDefault: Boolean
) {
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
    SINGLE_7(
        id = "7",
        label = "7 dtk",
        badge = "1 Hasil",
        sub = "Single 7.0 dtk · 1 MP4",
        segDurs = listOf(7.0),
        autoMergeDefault = false
    ),
    FULL_10(
        id = "10",
        label = "10 dtk",
        badge = "2 Seg→10s",
        sub = "Auto 2×5 dtk digabung 10s",
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

/** Perencana durasi & aturan timeout ZeroGPU. */
object AiPlanner {
    /** Batas keras durasi Replace Scene pada Space ZeroGPU: maksimal 10.0 detik. */
    const val ZEROGPU_MAX_REPLACE_SEC = 10.0
    const val ZEROGPU_MIN_REPLACE_SEC = 1.0

    /** Timeout menunggu workflow aktif = 3 menit (180 detik). */
    const val WORKFLOW_WAIT_TIMEOUT_SEC = 180

    /** Timeout buat video = 3 menit (180 detik) per video. */
    const val VIDEO_GEN_TIMEOUT_SEC = 180

    fun planInsert(preset: AiInsertPreset): AiExecutionPlan {
        val total = preset.segDurs.sum()
        val note = when (preset) {
            AiInsertPreset.THREE_BY_35 -> "3 hasil × 3.5 dtk (total 10.5 dtk)"
            AiInsertPreset.TWO_BY_5 -> "2 hasil × 5.0 dtk (total 10.0 dtk)"
            AiInsertPreset.SINGLE_7 -> "1 hasil × 7.0 dtk"
            AiInsertPreset.FULL_10 -> "2 segmen berantai × 5.0 dtk → digabung 10.0 dtk"
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
     * Merencanakan segmen ZeroGPU (pilihan valid Space: 3.5s, 5.0s, 7.0s)
     * untuk Mode Replace Scene dengan target durasi fleksibel [1.0 .. 10.0] detik.
     */
    fun planReplace(requestedSec: Double, sceneOrigLenSec: Double): AiExecutionPlan {
        val clamped = min(ZEROGPU_MAX_REPLACE_SEC, max(ZEROGPU_MIN_REPLACE_SEC, requestedSec))
        val rounded = Math.round(clamped * 10.0) / 10.0
        val segs: List<Double>
        val note: String
        when {
            rounded <= 3.5 -> {
                segs = listOf(3.5)
                note = if (rounded < 3.45) {
                    "1 × 3.5 dtk → auto-trim presisi ke ${Fmt.one(rounded)} dtk"
                } else {
                    "1 × 3.5 dtk pas"
                }
            }
            rounded <= 5.0 -> {
                segs = listOf(5.0)
                note = if (rounded < 4.95) {
                    "1 × 5.0 dtk → auto-trim presisi ke ${Fmt.one(rounded)} dtk"
                } else {
                    "1 × 5.0 dtk pas"
                }
            }
            rounded <= 7.0 -> {
                segs = listOf(7.0)
                note = if (rounded < 6.95) {
                    "1 × 7.0 dtk → auto-trim presisi ke ${Fmt.one(rounded)} dtk"
                } else {
                    "1 × 7.0 dtk pas"
                }
            }
            rounded <= 8.5 -> {
                segs = listOf(5.0, 3.5)
                note = if (rounded < 8.45) {
                    "2 segmen berantai (5.0s + 3.5s) → auto-trim ke ${Fmt.one(rounded)} dtk"
                } else {
                    "2 segmen berantai (5.0s + 3.5s = 8.5 dtk)"
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
