package com.pribadi.editorvideo.ai

import android.graphics.Bitmap
import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.model.VideoInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
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

/**
 * Pembagian durasi AI menjadi beberapa video seimbang ("durasi sama"):
 *  - [AUTO_1]: 1 video utuh (maks 10.0 dtk).
 *  - [DOUBLE_5]: dibagi rata 2 video, tiap video maksimal 5.0 dtk (total maks 10.0 dtk).
 *  - [TRIPLE_35]: dibagi rata 3 video, tiap video maksimal 3.5 dtk (total maks 10.5 dtk).
 */
enum class AiDurationSplit(
    val id: String,
    val label: String,
    val shortLabel: String,
    val sub: String,
    val maxSegs: Int,
    val maxSegSec: Double
) {
    AUTO_1(
        id = "auto1",
        label = "Mode Tunggal (1 Video)",
        shortLabel = "1 Video",
        sub = "Langsung 1 video utuh · maks 10.0 dtk",
        maxSegs = 1,
        maxSegSec = 10.0
    ),
    DOUBLE_5(
        id = "double5",
        label = "Dobel (maks 5 dtk × 2)",
        shortLabel = "2 × ≤5 dtk",
        sub = "Dibagi rata jadi 2 video, tiap video maks 5.0 dtk · total maks 10.0 dtk",
        maxSegs = 2,
        maxSegSec = 5.0
    ),
    TRIPLE_35(
        id = "triple35",
        label = "Tripel (maks 3.5 dtk × 3)",
        shortLabel = "3 × ≤3.5 dtk",
        sub = "Dibagi rata jadi 3 video, tiap video maks 3.5 dtk · total maks 10.5 dtk",
        maxSegs = 3,
        maxSegSec = 3.5
    );

    /** Total durasi maksimal yang boleh diminta untuk pembagian ini. */
    val maxTotalSec: Double get() = maxSegs * maxSegSec

    /** Mode generasi yang sesuai (Tunggal bila 1 video, Multi-Segmen bila > 1 video). */
    val genMode: AiGenerationMode
        get() = if (maxSegs <= 1) AiGenerationMode.SINGLE else AiGenerationMode.MULTI_SEGMENT

    companion object {
        fun fromGenMode(mode: AiGenerationMode): AiDurationSplit =
            if (mode == AiGenerationMode.SINGLE) AUTO_1 else DOUBLE_5

        fun fromId(id: String?): AiDurationSplit = entries.firstOrNull { it.id == id } ?: AUTO_1
    }
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
    /** Durasi SLOT timeline yang diinginkan pengguna (0.1 dtk grid, hasil splitEvenly). Dipakai UI & sebagai basis hitung kecepatan. */
    val segDurs: List<Double>,
    val targetTotalSec: Double,
    val autoMergeDefault: Boolean,
    val sceneOrigLenSec: Double = 0.0,
    val overZeroGpuLimit: Boolean = false,
    val note: String = "",
    /** Durasi GENERATE yang AMAN untuk Space AI (0.25 dtk grid). Null = sama dengan [segDurs] (tidak perlu penyesuaian). */
    val genDurs: List<Double>? = null,
    /** Pesan penolakan rencana (total generate melebihi batas 1 workflow). Bila tidak null, generate tidak boleh jalan. */
    val blockReason: String? = null
) {
    val count: Int get() = segDurs.size

    /** Durasi generate untuk segmen ke-[index]; null/0 = gunakan slot (tidak ada penyesuaian). */
    fun genAt(index: Int): Double = genDurs?.getOrNull(index) ?: segDurs.getOrNull(index) ?: 0.0

    /** Slot timeline untuk segmen ke-[index]. */
    fun slotAt(index: Int): Double = segDurs.getOrNull(index) ?: 0.0

    /** Kecepatan yang dipakai agar hasil AI mengisi slot timeline tepat (genDur / slotDur). 1.0 = tidak ada penyesuaian. */
    fun speedAt(index: Int): Double {
        val slot = slotAt(index)
        val gen = genAt(index)
        if (slot <= 0.0 || gen <= 0.0) return 1.0
        return gen / slot
    }

    /** Ringkasan kecepatan penyesuai (mis. "3.5→3.3 dtk 1.06x"), kosong bila tidak ada penyesuaian. */
    val speedAdjustNote: String
        get() {
            val parts = ArrayList<String>()
            for (i in segDurs.indices) {
                val sp = speedAt(i)
                if (abs(sp - 1.0) < 0.005) continue
                parts.add("${Fmt.one(genAt(i))}→${Fmt.one(slotAt(i))} dtk ${Fmt.one(sp)}x")
            }
            return parts.joinToString(" · ")
        }
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
    val isFavorite: Boolean = false,
    /** Pembagian durasi yang dipakai (Tunggal / Dobel ≤5×2 / Tripel ≤3.5×3). */
    val split: AiDurationSplit = AiDurationSplit.AUTO_1,
    /** Rencana Replace Multi-Scene Berurutan (null bila hanya 1 scene). */
    val multiScenePlan: AiMultiScenePlan? = null
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

/**
 * Sumber Frame Awal / Frame Akhir AI untuk Mode Multi-Scene & Replace Scene:
 *  - FRAME_SENDIRI: frame dari klip/scene yang sedang diproses (default, seperti perilaku lama).
 *  - SCENE_SEBELUMNYA: frame PALING AKHIR dari scene tepat sebelum scene target (untuk nyambung dari adegan sebelumnya).
 *  - SCENE_BERIKUTNYA: frame PALING AWAL dari scene tepat setelah scene target (untuk nyambung ke adegan berikutnya).
 */
enum class AiEdgeFrameSource(val label: String, val shortLabel: String) {
    FRAME_SENDIRI("Frame Sendiri", "Sendiri"),
    SCENE_SEBELUMNYA("Scene Sebelumnya", "Sblm"),
    SCENE_BERIKUTNYA("Scene Berikutnya", "Brkt")
}

/**
 * Rencana Replace Multi-Scene Berurutan: mengganti beberapa scene sekaligus (berurutan) mulai dari scene terpilih.
 */
data class AiMultiScenePlan(
    val startSegIdx: Int,
    val count: Int,
    val perSceneDurs: List<Double>,
    val split: AiDurationSplit,
    val totalSec: Double,
    val overLimit: Boolean = false,
    val note: String = ""
) {
    val endSegIdx: Int get() = startSegIdx + count - 1
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

    /** Batas total durasi absolut yang bisa diminta AI = 10.5 dtk (batas 3 × 3.5 dtk). */
    const val ABSOLUTE_MAX_TOTAL_SEC = 10.5

    /**
     * Grid durasi AMAN untuk Space Wan 2.2: jumlah frame dihitung `1 + round(detik × 16)` dan harus
     * memenuhi aturan temporal VAE (kelipatan 4 frame + 1). Artinya `round(detik × 16)` wajib habis
     * dibagi 4 → durasi harus kelipatan 0.25 dtk. Contoh: 3.5 / 5.0 / 7.0 aman, 3.3 / 5.2 / 2.4 gagal.
     */
    const val SAFE_DUR_STEP = 0.25

    /** Durasi terdekat ke ATAS yang aman untuk Space AI (kelipatan [SAFE_DUR_STEP] dtk). */
    fun safeDur(dur: Double): Double {
        if (dur <= 0.0) return 0.0
        val steps = kotlin.math.ceil(dur / SAFE_DUR_STEP - 1e-6).toInt()
        return Math.round(steps * SAFE_DUR_STEP * 100.0) / 100.0
    }

    /** True bila [dur] sudah aman untuk Space AI (tidak perlu digenapkan). */
    fun isSafeDur(dur: Double): Boolean = abs(safeDur(dur) - dur) < 0.006

    /** Batas wajar penyesuaian kecepatan agar hasil AI tidak terlihat aneh. */
    const val SPEED_SOFT_MIN = 0.9
    const val SPEED_SOFT_MAX = 1.15

    /**
     * Merencanakan slot durasi timeline [segDurs] menjadi durasi generate yang AMAN untuk Space AI
     * tanpa pernah memotong hasil AI:
     * - tiap slot digenapkan ke atas ke kelipatan 0.25 dtk (grid frame aman),
     * - bila total hasil genap masih muat di 1 workflow, dipakai apa adanya,
     * - bila tidak muat tetapi masih bisa "dipadatkan", tiap segmen dinaikkan semaksimal mungkin
     *   lalu dikompensasi lewat kecepatan (> 1x = agak dipercepat, hasil AI tetap utuh),
     * - bila tetap tidak muat → rencana [blocked] dengan pesan jelas.
     */
    fun planSpeedAdjustedSlots(segDurs: List<Double>): SpeedAdjustedPlan {
        if (segDurs.isEmpty()) return SpeedAdjustedPlan(emptyList(), emptyList(), null)
        val slots = segDurs.map { Math.round(max(ZEROGPU_MIN_REPLACE_SEC, it) * 100.0) / 100.0 }
        var gens = slots.map { safeDur(it) }
        val maxWf = maxWorkflowDurationFor(gens)
        if (gens.sum() <= maxWf + 1e-6) {
            return SpeedAdjustedPlan(gens, slots, null)
        }
        // Padatkan: tiap segmen dapat jatah sama, dibulatkan ke BAWAH ke grid aman (supaya tidak melebihi batas).
        val perSeg = Math.floor((maxWf / slots.size) / SAFE_DUR_STEP + 1e-9) * SAFE_DUR_STEP
        val packedGen = Math.round(perSeg * 100.0) / 100.0
        if (packedGen >= ZEROGPU_MIN_REPLACE_SEC) {
            gens = slots.map { packedGen }
            if (gens.sum() <= maxWf + 1e-6) {
                return SpeedAdjustedPlan(gens, slots, null)
            }
        }
        val total = slots.sum()
        return SpeedAdjustedPlan(
            emptyList(),
            slots,
            "Total ${Fmt.one(total)} dtk tidak bisa dibagi ke ${slots.size} video dalam 1 workflow " +
                "AI (maks ${Fmt.one(maxWf)} dtk). Turunkan total durasi atau jumlah pembagian."
        )
    }

    /** Hasil perencanaan penyesuaian kecepatan (lihat [planSpeedAdjustedSlots]). */
    data class SpeedAdjustedPlan(
        val genDurs: List<Double>,
        val slotDurs: List<Double>,
        val blockReason: String?
    ) {
        val blocked: Boolean get() = blockReason != null
    }

    /**
     * Membagi total durasi menjadi [parts] segmen dengan durasi SAMA (dibulatkan 0.1 dtk, sisa dibagikan
     * ke segmen-segmen awal) dengan batas maksimum per segmen [maxSegSec].
     * Contoh: 10.0 dtk dibagi 2 → 5.0 + 5.0 · 10.5 dtk dibagi 3 → 3.5 + 3.5 + 3.5 · 7.0 dtk dibagi 3 → 2.4/2.3/2.3.
     */
    fun splitEvenly(totalSec: Double, parts: Int, maxSegSec: Double): List<Double> {
        val n = parts.coerceIn(1, 3)
        val safeTotal = min(ABSOLUTE_MAX_TOTAL_SEC, max(1.0, totalSec)).coerceAtMost(n * maxSegSec)
        if (n == 1) return listOf(Math.round(safeTotal * 10.0) / 10.0)
        val baseTenths = Math.floor(safeTotal * 10.0 / n).toInt()
        val remainder = Math.round(safeTotal * 10.0).toInt() - baseTenths * n
        val out = ArrayList<Double>(n)
        for (i in 0 until n) {
            val tenths = baseTenths + if (i < remainder) 1 else 0
            val v = tenths / 10.0
            out.add(Math.round(min(v, maxSegSec) * 10.0) / 10.0)
        }
        return out
    }

    /**
     * Merencanakan pembagian durasi sesuai [split] (Tunggal / Dobel ≤5×2 / Tripel ≤3.5×3) untuk Mode Sisipkan.
     */
    /** Kepala catatan durasi yang menyesuaikan: "Generate 3.5 dtk (slot 3.3 dtk = 1.06x)" bila ada, atau "Generate {dur} dtk" bila tidak. */
    private fun speedAdjustHead(slots: List<Double>, genDurs: List<Double>, split: AiDurationSplit): String {
        if (genDurs.size != slots.size) return ""
        val sb = StringBuilder()
        for (i in slots.indices) {
            val sp = genDurs[i] / slots[i]
            if (abs(sp - 1.0) >= 0.005) {
                sb.append("Generate ${Fmt.one(genDurs[i])} dtk (slot ${Fmt.one(slots[i])} dtk = ${Fmt.one(sp)}x) · ")
            }
        }
        return sb.toString()
    }

    fun planInsertWithSplit(totalSec: Double, split: AiDurationSplit): AiExecutionPlan {
        val slots = splitEvenly(totalSec, split.maxSegs, split.maxSegSec)
        val total = slots.sum()
        val adj = planSpeedAdjustedSlots(slots)
        if (adj.blocked) {
            return AiExecutionPlan(
                workMode = AiWorkMode.INSERT,
                segDurs = slots,
                targetTotalSec = total,
                autoMergeDefault = false,
                note = adj.blockReason,
                genDurs = null,
                blockReason = adj.blockReason
            )
        }
        val note = buildString {
            append(speedAdjustHead(slots, adj.genDurs, split))
            append(" · ")
            append(when (split) {
                AiDurationSplit.AUTO_1 -> "1 video utuh"
                AiDurationSplit.DOUBLE_5 -> "Dobel · ${slots.size} video"
                AiDurationSplit.TRIPLE_35 -> "Tripel · ${slots.size} video"
                else -> "${slots.size} video"
            })
            append(" (${slots.joinToString(" + ") { Fmt.one(it) }} = ${Fmt.one(total)} dtk${if (adj.genDurs != slots) " · generate AI ${adj.genDurs.joinToString(" + ") { Fmt.one(it) }}" else ""})")
        }
        return AiExecutionPlan(
            workMode = AiWorkMode.INSERT,
            segDurs = slots,
            targetTotalSec = total,
            autoMergeDefault = false,
            genDurs = adj.genDurs,
            note = note
        )
    }

    /**
     * Merencanakan pembagian durasi sesuai [split] untuk Mode Replace Scene (target durasi bebas 1.0..10.0 dtk).
     * Mode Dobel/Tripel otomatis membatasi tiap video (maks 5 dtk / 3.5 dtk) dan menyisakan sisa durasi scene
     * bila scene asli lebih panjang dari total yang bisa dibuat AI.
     */
    fun planReplaceWithSplit(
        requestedSec: Double,
        sceneOrigLenSec: Double,
        split: AiDurationSplit
    ): AiExecutionPlan {
        val clamped = min(ABSOLUTE_MAX_TOTAL_SEC, max(ZEROGPU_MIN_REPLACE_SEC, requestedSec))
        val capped = min(clamped, split.maxTotalSec)
        val slots = splitEvenly(capped, split.maxSegs, split.maxSegSec)
        val total = slots.sum()
        val adj = planSpeedAdjustedSlots(slots)
        val note = buildString {
            if (adj.blocked) {
                append(adj.blockReason)
            } else {
                append(speedAdjustHead(slots, adj.genDurs, split))
                when (split) {
                    AiDurationSplit.AUTO_1 -> {
                        if (total < 3.45) {
                            append("Mode Tunggal · 1 video 3.5 dtk generate, dijalankan ${Fmt.one(total)} dtk di timeline")
                        } else {
                            append("Mode Tunggal · 1 video utuh ${Fmt.one(total)} dtk")
                        }
                    }
                    else -> {
                        append(split.label)
                        append(" · ")
                        append(slots.joinToString(" + ") { Fmt.one(it) })
                        append(" = ${Fmt.one(total)} dtk")
                        if (capped + 0.05 < clamped) {
                            append(" (dibatasi maks ${Fmt.one(split.maxTotalSec)} dtk untuk ${split.shortLabel})")
                        }
                        if (adj.genDurs != slots) {
                            append(" · generate AI ${adj.genDurs.joinToString(" + ") { Fmt.one(it) }}")
                        }
                    }
                }
            }
        }
        return AiExecutionPlan(
            workMode = AiWorkMode.REPLACE_SCENE,
            segDurs = slots,
            targetTotalSec = total,
            autoMergeDefault = false,
            sceneOrigLenSec = sceneOrigLenSec,
            overZeroGpuLimit = sceneOrigLenSec > ZERO_GPU_HARD_MAX_SEC + 0.05,
            genDurs = if (adj.blocked) null else adj.genDurs,
            blockReason = adj.blockReason,
            note = note
        )
    }

    /** Batas keras durasi scene yang bisa diganti seluruhnya tanpa menyisakan sisa. */
    const val ZERO_GPU_HARD_MAX_SEC = 10.5

    /**
     * Rencana Replace Multi-Scene berurutan: mengganti [count] scene mulai dari [startSegIdx].
     * Tiap scene memakai pembagian durasi sesuai [split] (maks 5 dtk bila Dobel, 3.5 dtk bila Tripel, 10 dtk bila Tunggal).
     */
    fun planMultiSceneReplace(
        sceneLens: List<Double>,
        startSegIdx: Int,
        count: Int,
        split: AiDurationSplit
    ): AiMultiScenePlan {
        val safeStart = startSegIdx.coerceAtLeast(0)
        val available = (sceneLens.size - safeStart).coerceAtLeast(0)
        val n = count.coerceIn(1, max(1, available))
        val perScene = ArrayList<Double>(n)
        var total = 0.0
        var over = false
        for (i in 0 until n) {
            val len = sceneLens.getOrElse(safeStart + i) { 3.5 }
            val want = min(len, split.maxSegSec)
            if (len > split.maxTotalSec + 0.05) over = true
            val rounded = Math.round(max(ZEROGPU_MIN_REPLACE_SEC, want) * 10.0) / 10.0
            perScene.add(rounded)
            total += rounded
        }
        val note = if (split.maxSegs == 1) {
            "Multi-Scene Berurutan · $n scene × 1 video (maks 10 dtk/scene) = ${Fmt.one(total)} dtk"
        } else {
            "Multi-Scene Berurutan · $n scene × ${split.shortLabel} = ${Fmt.one(total)} dtk"
        }
        return AiMultiScenePlan(
            startSegIdx = safeStart,
            count = n,
            perSceneDurs = perScene,
            split = split,
            totalSec = total,
            overLimit = over,
            note = note
        )
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
