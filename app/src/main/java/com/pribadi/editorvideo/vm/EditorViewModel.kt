package com.pribadi.editorvideo.vm

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Choreographer
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.pribadi.editorvideo.ai.AiCandidateBatch
import com.pribadi.editorvideo.ai.AiExecutionPlan
import com.pribadi.editorvideo.ai.AiGeneratedSegment
import com.pribadi.editorvideo.ai.AiGenerationMode
import com.pribadi.editorvideo.ai.AiInsertPreset
import com.pribadi.editorvideo.ai.AiMultiStrategy
import com.pribadi.editorvideo.ai.AiPlanner
import com.pribadi.editorvideo.ai.AiPromptItem
import com.pribadi.editorvideo.ai.AiReplaceAudioMode
import com.pribadi.editorvideo.ai.AiRunPhase
import com.pribadi.editorvideo.ai.AiRunnerConfig
import com.pribadi.editorvideo.ai.AiSpaceOption
import com.pribadi.editorvideo.ai.AiTab
import com.pribadi.editorvideo.ai.AiTimelineJoinStyle
import com.pribadi.editorvideo.ai.AiWorkMode
import com.pribadi.editorvideo.ai.DownloadedAiVideo
import com.pribadi.editorvideo.ai.SingleTaskRequest
import com.pribadi.editorvideo.ai.VideoGenerationTimeoutException
import com.pribadi.editorvideo.ai.WanStudioClient
import com.pribadi.editorvideo.ai.WorkerSyncStatus
import com.pribadi.editorvideo.ai.WorkflowWaitTimeoutException
import com.pribadi.editorvideo.export.ByTagExportStyle
import com.pribadi.editorvideo.export.DetectedScene
import com.pribadi.editorvideo.export.EncoderCaps
import com.pribadi.editorvideo.export.ExportCenter
import com.pribadi.editorvideo.export.ExportMath
import com.pribadi.editorvideo.export.ExportMode
import com.pribadi.editorvideo.export.ExportPlan
import com.pribadi.editorvideo.export.ExportPlanner
import com.pribadi.editorvideo.export.ExportRequest
import com.pribadi.editorvideo.export.ExportScaleFit
import com.pribadi.editorvideo.export.ExportSpecRef
import com.pribadi.editorvideo.export.ExportState
import com.pribadi.editorvideo.export.MediaStoreSaver
import com.pribadi.editorvideo.export.ReverseVideoRenderer
import com.pribadi.editorvideo.export.SceneDetector
import com.pribadi.editorvideo.export.SceneMarkerMode
import com.pribadi.editorvideo.export.SceneScanResult
import com.pribadi.editorvideo.export.SourceSpec
import com.pribadi.editorvideo.export.VideoFrames
import com.pribadi.editorvideo.export.VideoProbe
import com.pribadi.editorvideo.model.EditorCore
import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.model.ProjectedMarker
import com.pribadi.editorvideo.model.Rules
import com.pribadi.editorvideo.model.Seg
import com.pribadi.editorvideo.model.TextOverlayConfig
import com.pribadi.editorvideo.model.VideoInfo
import com.pribadi.editorvideo.model.clampD
import com.pribadi.editorvideo.ui.AppTheme
import com.pribadi.editorvideo.ui.Ev
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Alat di bar bawah. */
enum class Tool { SPLIT, MERGE, MARKER, SCENE_DETECT, AI, SPEED, TEXT, REVERSE, DUP, DEL, ROTATE, MIRROR, FREEZE }

/** Kategori catatan (Bug atau Pengembangan). */
enum class NoteType(val label: String) {
    BUG("Bug"),
    DEV("Pengembangan")
}

/** Opsi pengurutan pada Mode Grid. */
enum class GridSortMode(val label: String, val shortLabel: String) {
    TIMELINE_ASC("Urutan Awal (#1 → #N)", "#1→#N"),
    TIMELINE_DESC("Urutan Akhir (#N → #1)", "#N→#1"),
    DUR_DESC("Durasi Terpanjang", "Terpanjang"),
    DUR_ASC("Durasi Terpendek", "Terpendek"),
    TAG_AZ("Nama Tag (A → Z)", "Tag A–Z")
}

/** Satu pilihan warna dari 12 opsi warna untuk Custom Tag pada Mode Grid. */
data class TagColorOption(
    val index: Int,
    val name: String,
    val color: Color
)

/** Kunci cache varian rotasi/cermin untuk thumbnail frame pertama Mode Grid. */
private data class GridTransformKey(
    val baseKey: Long,
    val rot: Int,
    val flip: Boolean
)

/** Spesifikasi satu klip untuk fitur Multiplayer Collage. */
data class CollageClipItem(
    val id: Int,
    val label: String,
    val uri: Uri,
    val startMs: Long,
    val endMs: Long,
    val speed: Float,
    val aspect: Float,
    val rot: Int,
    val flip: Boolean,
    val freeze: Boolean = false
)

/** Satu item catatan bug / ide pengembangan di masa depan. */
data class NoteItem(
    val id: Long,
    val type: NoteType,
    val text: String,
    val done: Boolean = false,
    val createdAt: String = ""
)

/** Satu entri riwayat pembaruan (Updated) di bagian Catatan > Updated. */
data class UpdateLogEntry(
    val version: String,
    val date: String,
    val title: String,
    val items: List<String>
)

/** Satu sumber video yang telah diimpor atau dirender. */
data class MediaSource(
    val uri: Uri,
    val info: VideoInfo
)

/** Data untuk sheet ekspor. */
data class ExportInfo(
    val plan: ExportPlan,
    val mb: Double,
    val subVideo: String,
    val subAll: String,
    val subCurrent: String,
    val subByTag: String,
    val canStart: Boolean = true,
    val hasMixedSpecs: Boolean = false,
    val mixedSpecsSummary: String = "",
    val allSourcesSummary: String = "",
    val supportsPerClipNative: Boolean = false
)

/**
 * Jembatan antara UI Compose, logika editor (EditorCore), pemutar pratinjau (ExoPlayer),
 * 10 tema aplikasi, Mode Grid (3–9 kolom vertikal, Tagging, Sort, Filter, Hold Multiseleksi & Multiplayer Collage),
 * UI Fitur AI dengan tangkapan frame 100% identik Preview, detektor scene, perender Mundur & Loop,
 * kustomisasi teks kaya, catatan, dan ekspor.
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    val core = EditorCore()
    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    // ------------------------------------------------------------------ state untuk UI

    var rev by mutableIntStateOf(0)
        private set

    /** Tema aktif dari 10 pilihan tema aplikasi. */
    var appTheme by mutableStateOf(AppTheme.MEDIUM_MAROON)
        private set

    // --- State Mode Grid (3..9 kolom vertikal, Tagging, Sort, Filter, Multiseleksi, Multiplayer Collage) ---
    var gridMode by mutableStateOf(false)
        private set
    var gridSubMode by mutableIntStateOf(0)
    var gridColumns by mutableIntStateOf(3)
        private set
    /** Toggle panel floating input range vertikal kolom (3..9) agar tidak memakan ruang Grid. */
    var gridColSliderOpen by mutableStateOf(false)
    var gridSortMode by mutableStateOf(GridSortMode.TIMELINE_ASC)
        private set
    var gridFilter by mutableStateOf(GRID_FILTER_ALL)
        private set
    var availableTags by mutableStateOf(listOf("Favorit", "Utama", "Highlight", "B-Roll", "Simpan"))
        private set
    /** Peta nama tag (lowercase) -> indeks warna 0..11 pada TAG_COLOR_PALETTE. */
    var tagColorMap by mutableStateOf<Map<String, Int>>(
        mapOf(
            "favorit" to 0,
            "utama" to 1,
            "highlight" to 2,
            "b-roll" to 3,
            "simpan" to 4
        )
    )
        private set
    var gridMultiSelect by mutableStateOf(false)
        private set
    var gridSelectedKeys by mutableStateOf<List<Int>>(emptyList())
        private set
    var gridTagSheet by mutableStateOf(false)
    var gridSortSheet by mutableStateOf(false)
    var collageOpen by mutableStateOf(false)
    var collageItems by mutableStateOf<List<CollageClipItem>>(emptyList())
        private set

    var playing by mutableStateOf(false)
        private set
    /** Toggle mode loop pada pratinjau (memutar/menjeda tetap lewat tombol Play/Pause biasa). */
    var previewLoop by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var rot by mutableIntStateOf(0)
        private set
    var flip by mutableStateOf(false)
        private set
    var selSpeed by mutableDoubleStateOf(1.0)
        private set
    var selReversed by mutableStateOf(false)
        private set
    var selRevMode by mutableIntStateOf(Seg.REV_NONE)
        private set
    var selIndex by mutableIntStateOf(0)
        private set
    var segCount by mutableIntStateOf(0)
        private set
    var sourceCount by mutableIntStateOf(0)
        private set
    var canMergeScene by mutableStateOf(false)
        private set
    var markerCount by mutableIntStateOf(0)
        private set
    var markerNearCurrentPlayhead by mutableStateOf(false)
        private set
    var hasVideo by mutableStateOf(false)
        private set
    var info by mutableStateOf<VideoInfo?>(null)
        private set

    /** Konfigurasi kustomisasi kaya untuk Teks 1 & Teks 2 — default kosong. */
    var style1 by mutableStateOf(TextOverlayConfig.defaultTop())
        private set
    var style2 by mutableStateOf(TextOverlayConfig.defaultBottom())
        private set

    val text1: String get() = style1.text
    val text2: String get() = style2.text

    var activeTextTab by mutableIntStateOf(0)

    /** Zoom timeline (dp per detik). */
    var pps by mutableDoubleStateOf(Rules.PPS0)
        private set
    var zoomTag by mutableStateOf<String?>(null)
        private set

    /** Zoom & pan pratinjau video (pinch in/out di pratinjau). */
    var previewZoom by mutableFloatStateOf(1f)
        private set
    var previewPanX by mutableFloatStateOf(0f)
        private set
    var previewPanY by mutableFloatStateOf(0f)
        private set

    var toastMsg by mutableStateOf<String?>(null)
        private set

    // Floating sheets (semua dibatasi max-height 80% + scroll-y)
    var importSourceSheet by mutableStateOf(false)
    var tagImportReplaceMode by mutableStateOf(false)
    var themeSheet by mutableStateOf(false)
    var aiSheet by mutableStateOf(false)
    var speedSheet by mutableStateOf(false)
    var reverseSheet by mutableStateOf(false)
    var textSheet by mutableStateOf(false)
    var noteSheet by mutableStateOf(false)
    var noteTab by mutableIntStateOf(0)
    var sceneDetectSheet by mutableStateOf(false)
    var confirmText by mutableStateOf<String?>(null)
    var exportSheet by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)

    // --- State Studio Video AI v2 (Mode Sisipkan, Replace Scene, Multi-Hasil, Prompt Mgmt & Runner) ---
    var aiSeekBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var aiFrameLoading by mutableStateOf(false)
        private set
    var aiSeekTimeSec by mutableDoubleStateOf(0.0)
        private set
    var aiSeekSourceSec by mutableDoubleStateOf(0.0)
        private set
    var aiSeekClipIndex by mutableIntStateOf(0)
        private set
    var aiSeekFps by mutableFloatStateOf(30f)
        private set
    var aiCaptureSourceLabel by mutableStateOf("Identik Preview")
        private set
    var aiPrompt by mutableStateOf("")
    var aiPreviewStatus by mutableStateOf<String?>(null)
    private var aiFrameJob: Job? = null

    var aiActiveTab by mutableStateOf(AiTab.CREATE)
    var aiWorkMode by mutableStateOf(AiWorkMode.INSERT)
        private set
    var aiGenMode by mutableStateOf(AiGenerationMode.SINGLE)
        private set
    var aiInsertSingleDurSec by mutableDoubleStateOf(10.0)
        private set
    var aiInsertPreset by mutableStateOf(AiInsertPreset.SINGLE_10)
        private set
    var aiMultiStrategy by mutableStateOf(AiMultiStrategy.CHAIN)
    var aiTimelineJoinStyle by mutableStateOf(AiTimelineJoinStyle.SEPARATE)

    // Mode Sisipkan: Frame Akhir opsional (last_image)
    var aiInsertLastBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var aiInsertLastTimeSec by mutableDoubleStateOf(-1.0)
        private set

    // Mode Replace Scene: input range durasi (1.0 .. 10.0 dtk) + shortcut durasi scene <=10s + opsi Audio
    var aiReplaceTargetDurSec by mutableDoubleStateOf(5.0)
        private set
    var aiReplaceBridgeLastFrame by mutableStateOf(true)
        private set
    var aiReplaceAudioMode by mutableStateOf(AiReplaceAudioMode.VIDEO_ONLY)
    var aiReplaceFirstBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var aiReplaceLastBitmap by mutableStateOf<Bitmap?>(null)
        private set
    private var aiReplaceFrameJob: Job? = null

    // Parameter Space & keamanan
    var aiSpaceOption by mutableStateOf(AiSpaceOption.R3GM)
        private set
    var aiSafeMode by mutableStateOf(true)
    var aiSafetyChecker by mutableStateOf(false)

    // Pengaturan prompt per segmen (slot 0 = sama untuk semua, slot 1..3 = khusus Segmen #1..#3)
    var aiPerSegmentPromptMode by mutableStateOf(false)
        private set
    var aiActivePromptSegSlot by mutableIntStateOf(0)
        private set
    var aiSegmentPrompts by mutableStateOf(
        listOf(
            AiPromptItem.defaultList().first().body,
            AiPromptItem.defaultList().first().body,
            AiPromptItem.defaultList().last().body,
            AiPromptItem.defaultList()[1].body
        )
    )
        private set

    // Tab Management Prompt
    var aiPromptLibrary by mutableStateOf<List<AiPromptItem>>(AiPromptItem.defaultList())
        private set
    var aiPromptFilterCategory by mutableStateOf("ALL")
    var aiPromptSearchQuery by mutableStateOf("")
    var aiEditingPromptId by mutableStateOf<String?>(null)
        private set
    var aiFormPromptTitle by mutableStateOf("")
    var aiFormPromptCategory by mutableStateOf("Kamera")
    var aiFormPromptBody by mutableStateOf("")
    var aiPromptImportMergeMode by mutableStateOf(true)

    // Tab Status: Dual Watchdog Timeout (3m workflow + 3m/video) & Pengatur Multi-Hasil
    var aiRunPhase by mutableStateOf(AiRunPhase.IDLE)
        private set
    var aiWorkflowWaitSec by mutableIntStateOf(0)
        private set
    var aiCurrentVideoNum by mutableIntStateOf(0)
        private set
    var aiTotalVideosInRun by mutableIntStateOf(0)
        private set
    var aiCurrentVideoSec by mutableIntStateOf(0)
        private set
    var aiRunStatusDetail by mutableStateOf("Siap membuat video AI.")
        private set
    var aiGeneratedSegments by mutableStateOf<List<AiGeneratedSegment>>(emptyList())
        private set
    var aiPreviewResultIndex by mutableIntStateOf(0)
    var aiSnapshotWorkMode by mutableStateOf(AiWorkMode.INSERT)
        private set
    var aiSnapshotInsertSeekSec by mutableDoubleStateOf(0.0)
        private set
    var aiSnapshotReplaceSegIdx by mutableIntStateOf(0)
        private set
    var aiSnapshotPlan by mutableStateOf<AiExecutionPlan?>(null)
        private set
    var aiMergingClips by mutableStateOf(false)
        private set
    private var aiRunJob: Job? = null

    // Sistem Kandidat Hasil AI (bisa generate berkali-kali & tambah kandidat untuk memilih hasil terbaik)
    var aiCandidates by mutableStateOf<List<AiCandidateBatch>>(emptyList())
        private set
    var aiActiveCandidateIdx by mutableIntStateOf(0)
        private set
    private var aiNextCandidateNumber: Int = 1

    // Tab Runner: Koneksi Firebase & GitHub + Sinkronisasi Worker v3 + Checklist Repo Runner Clipboards
    var aiRunnerConfig by mutableStateOf(AiRunnerConfig())
        private set
    var aiTestingConnection by mutableStateOf(false)
        private set
    var aiConnectionTestMessage by mutableStateOf<String?>(null)
        private set
    var aiConnectionTestOk by mutableStateOf(false)
        private set
    var aiWorkerSyncStatus by mutableStateOf(WorkerSyncStatus.UNKNOWN)
        private set
    var aiWorkerSyncMessage by mutableStateOf<String?>(null)
        private set
    var aiSyncingWorker by mutableStateOf(false)
        private set
    var aiCopiedRunnerIds by mutableStateOf<Set<String>>(emptySet())
        private set

    private var activeTextureViewRef: WeakReference<TextureView>? = null
    private var renderedFrameSeq by mutableIntStateOf(0)

    // --- State Deteksi Scene + Integrasi Tanda (Markers) + Grid Preview Deteksi ---
    var sceneScanning by mutableStateOf(false)
        private set
    var sceneScanProgress by mutableFloatStateOf(0f)
        private set
    var sceneSensitivity by mutableFloatStateOf(0.55f)
        private set
    var sceneMinDur by mutableDoubleStateOf(0.5)
        private set
    var sceneMaxDurEnabled by mutableStateOf(false)
        private set
    var sceneMaxDur by mutableDoubleStateOf(5.0)
        private set
    var sceneMarkerMode by mutableStateOf(SceneMarkerMode.AUTO_PLUS_MARKERS)
        private set
    var sceneScanCount by mutableIntStateOf(0)
        private set
    var detectedScenes by mutableStateOf<List<DetectedScene>>(emptyList())
        private set
    private var currentScanResult: SceneScanResult? = null
    private var sceneScanJob: Job? = null

    // --- State Render Membalikkan (Mundur & Loop Normal+Mundur) ---
    var reverseRendering by mutableStateOf(false)
        private set
    var reverseRenderingLabel by mutableStateOf("Mundur")
        private set
    var reverseProgress by mutableFloatStateOf(0f)
        private set
    private var reverseJob: Job? = null

    /** Daftar catatan bug / pengembangan masa depan. */
    var notes by mutableStateOf<List<NoteItem>>(emptyList())
        private set

    var exportMode by mutableStateOf(ExportMode.VIDEO)
    var qIdx by mutableIntStateOf(ExportMath.DEFAULT_QUALITY)
    var fIdx by mutableIntStateOf(ExportMath.DEFAULT_FPS)
    var exportSelectedTags by mutableStateOf<List<String>>(emptyList())
        private set
    var exportByTagStyle by mutableStateOf(ByTagExportStyle.MERGE_SINGLE)
    var exportSpecRef by mutableStateOf(ExportSpecRef.MAIN_ORIGINAL)
    var exportScaleFit by mutableStateOf(ExportScaleFit.FIT_LETTERBOX)

    val exportState = ExportCenter.state

    // ------------------------------------------------------------------ internal

    private val sources = ArrayList<MediaSource>()
    private var curSeg = 0
    private var lastNanos = 0L
    private var toastJob: Job? = null
    private var zoomJob: Job? = null
    private var importJob: Job? = null
    private var thumbJob: Job? = null

    private val sourceThumbs = ArrayList<Array<Bitmap?>>()
    private val sourceThumbReady = ArrayList<Int>()

    // Cache khusus Mode Grid: frame pertama tiap klip secara utuh (tanpa crop) agar 100% sesuai autoplay
    private val gridCacheLock = Any()
    private val gridBaseFrameCache = LinkedHashMap<Long, Bitmap>(64, 0.75f, true)
    private val gridTransformedCache = LinkedHashMap<GridTransformKey, Bitmap>(64, 0.75f, true)
    private val pendingGridFrames = LinkedHashSet<Pair<Int, Long>>()
    private var gridThumbWorkerJob: Job? = null

    private val choreographer: Choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback { ts -> tick(ts) }

    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        core.onPause = { pause() }
        player.playWhenReady = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setSeekParameters(SeekParameters.EXACT)
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                renderedFrameSeq++
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    renderedFrameSeq++
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                pause()
                toast("Video tidak bisa diputar")
            }
        })
        viewModelScope.launch {
            ExportCenter.state.collect { st ->
                if (st is ExportState.Cancelled) {
                    toast("Ekspor dibatalkan")
                    ExportCenter.reset()
                }
            }
        }
        loadPrefsAndNotes()
        if (ExportCenter.isRunning()) exportSheet = true
        refreshDerived()
    }

    override fun onCleared() {
        choreographer.removeFrameCallback(frameCallback)
        thumbJob?.cancel()
        gridThumbWorkerJob?.cancel()
        importJob?.cancel()
        sceneScanJob?.cancel()
        reverseJob?.cancel()
        aiFrameJob?.cancel()
        aiReplaceFrameJob?.cancel()
        aiRunJob?.cancel()
        clearGridFrameCaches()
        try {
            player.release()
        } catch (e: Exception) {
            // diabaikan
        }
        super.onCleared()
    }

    private fun bump() {
        rev = rev + 1
    }

    private fun refreshDerived() {
        hasVideo = core.hasVideo && sources.isNotEmpty() && core.segs.isNotEmpty()
        canUndo = core.canUndo
        canRedo = core.canRedo
        rot = core.selRot
        flip = core.flip
        selIndex = core.sel
        selSpeed = core.selSpeed
        selReversed = core.selReversed
        selRevMode = core.selRevMode
        segCount = core.segs.size
        sourceCount = sources.size
        canMergeScene = core.canMergeFocusScene
        markerCount = core.markers.size
        markerNearCurrentPlayhead = core.markerNearPlayhead(0.08) != null
        val activeSrc = core.segs.getOrNull(core.sel)?.src ?: 0
        info = if (hasVideo && sources.isNotEmpty()) {
            sources.getOrElse(activeSrc) { sources.first() }.info
        } else {
            null
        }
        // Bersihkan indeks multiseleksi yang sudah tidak valid bila jumlah potongan berubah
        if (gridSelectedKeys.isNotEmpty()) {
            val filtered = gridSelectedKeys.filter { k ->
                k >= SLICE_KEY_OFFSET || k in core.segs.indices
            }
            if (filtered.size != gridSelectedKeys.size) {
                gridSelectedKeys = filtered
                if (filtered.isEmpty()) gridMultiSelect = false
            }
        }
    }

    /** Aspect ratio tampilan untuk sumber ke-[srcIdx] dengan rotasi potongan [segRot]. */
    fun aspectOfSource(srcIdx: Int, segRot: Int = rot): Float {
        val inf = sources.getOrElse(srcIdx) { sources.firstOrNull() }?.info ?: info
        if (inf == null || inf.dispW <= 0 || inf.dispH <= 0) return 9f / 16f
        return if (segRot % 180 != 0) {
            inf.dispH.toFloat() / inf.dispW.toFloat()
        } else {
            inf.dispW.toFloat() / inf.dispH.toFloat()
        }
    }

    fun toast(msg: String) {
        toastMsg = msg
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(1800)
            toastMsg = null
        }
    }

    // ------------------------------------------------------------------ 10 tema & mode grid (3..9 kolom, tag, sort, filter, collage)

    fun selectTheme(theme: AppTheme) {
        appTheme = theme
        Ev.activeTheme = theme
        prefs.edit().putString(KEY_APP_THEME, theme.id).apply()
        bump()
        toast("Tema: ${theme.label}")
    }

    fun toggleGridMode() {
        gridMode = !gridMode
        if (!gridMode) {
            gridColSliderOpen = false
            clearGridMultiSelect()
            refreshFrame()
        }
        toast(if (gridMode) "Mode Grid (${gridColumns} Kolom) aktif" else "Mode Pratinjau Tunggal")
    }

    fun toggleGridColSlider() {
        gridColSliderOpen = !gridColSliderOpen
    }

    fun updateGridColumns(cols: Int) {
        val c = cols.coerceIn(3, 9)
        if (c == gridColumns) return
        gridColumns = c
        prefs.edit().putInt(KEY_GRID_COLS, c).apply()
        bump()
    }

    fun selectGridSortMode(mode: GridSortMode) {
        gridSortMode = mode
        gridSortSheet = false
        bump()
        toast("Urutan Grid: ${mode.label}")
    }

    fun cycleGridSortMode() {
        val all = GridSortMode.entries
        val next = all[(all.indexOf(gridSortMode) + 1) % all.size]
        gridSortMode = next
        bump()
        toast("Urutan: ${next.label}")
    }

    fun selectGridFilter(filterKey: String) {
        gridFilter = filterKey
        bump()
    }

    /** Indeks warna (0..11) untuk sebuah nama tag. */
    fun tagColorIndex(tag: String): Int {
        val key = tag.trim().lowercase(Locale.ROOT)
        val stored = tagColorMap[key]
        if (stored != null) return stored.coerceIn(0, TAG_COLOR_PALETTE.lastIndex)
        return (key.hashCode() and 0x7FFFFFFF) % TAG_COLOR_PALETTE.size
    }

    /** Warna dari 12 opsi warna untuk sebuah nama tag. */
    fun tagColor(tag: String): Color =
        TAG_COLOR_PALETTE[tagColorIndex(tag)].color

    /** Mengubah warna salah satu tag ke salah satu dari 12 opsi warna dan menyimpannya. */
    fun assignTagColor(tag: String, colorIdx: Int) {
        val key = tag.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return
        val safeIdx = colorIdx.coerceIn(0, TAG_COLOR_PALETTE.lastIndex)
        val updated = LinkedHashMap(tagColorMap)
        updated[key] = safeIdx
        tagColorMap = updated
        saveTagColors(updated)
        bump()
        toast("Warna tag \"$tag\": ${TAG_COLOR_PALETTE[safeIdx].name}")
    }

    fun addCustomTag(rawName: String, colorIdx: Int = 0) {
        val cleaned = rawName.trim().take(24)
        if (cleaned.isEmpty()) return
        val safeColorIdx = colorIdx.coerceIn(0, TAG_COLOR_PALETTE.lastIndex)
        val existing = availableTags.firstOrNull { it.equals(cleaned, ignoreCase = true) }
        val finalTag = existing ?: cleaned
        if (existing == null) {
            val next = availableTags + finalTag
            availableTags = next
            saveCustomTags(next)
        }
        val updatedColors = LinkedHashMap(tagColorMap)
        updatedColors[finalTag.lowercase(Locale.ROOT)] = safeColorIdx
        tagColorMap = updatedColors
        saveTagColors(updatedColors)
        if (hasVideo && core.segs.isNotEmpty()) {
            toggleTagForCurrentTarget(finalTag)
        } else {
            bump()
            toast("Custom tag \"$finalTag\" ditambahkan")
        }
    }

    private fun saveCustomTags(tags: List<String>) {
        try {
            val arr = JSONArray()
            for (t in tags) arr.put(t)
            prefs.edit().putString(KEY_CUSTOM_TAGS, arr.toString()).apply()
        } catch (e: Exception) {
            // diabaikan
        }
    }

    private fun saveTagColors(map: Map<String, Int>) {
        try {
            val obj = JSONObject()
            for ((k, v) in map) {
                obj.put(k, v)
            }
            prefs.edit().putString(KEY_TAG_COLORS_JSON, obj.toString()).apply()
        } catch (e: Exception) {
            // diabaikan
        }
    }

    /** Memulai multiseleksi saat item di-hold (long-press) pada Mode Grid. */
    fun startGridMultiSelect(itemKey: Int) {
        pause()
        gridMultiSelect = true
        if (itemKey !in gridSelectedKeys) {
            gridSelectedKeys = gridSelectedKeys + itemKey
        }
        bump()
    }

    /** Menambah / melepas item pada Mode Multiseleksi Grid (mempertahankan urutan pilih). */
    fun toggleGridSelectKey(itemKey: Int) {
        if (!gridMultiSelect) {
            gridMultiSelect = true
        }
        gridSelectedKeys = if (itemKey in gridSelectedKeys) {
            gridSelectedKeys.filterNot { it == itemKey }
        } else {
            gridSelectedKeys + itemKey
        }
        if (gridSelectedKeys.isEmpty()) {
            gridMultiSelect = false
        }
        bump()
    }

    fun selectAllGridKeys(keys: List<Int>) {
        if (keys.isEmpty()) return
        gridMultiSelect = true
        gridSelectedKeys = if (gridSelectedKeys.containsAll(keys)) {
            emptyList()
        } else {
            keys.distinct()
        }
        if (gridSelectedKeys.isEmpty()) gridMultiSelect = false
        bump()
    }

    fun clearGridMultiSelect() {
        gridMultiSelect = false
        gridSelectedKeys = emptyList()
        bump()
    }

    /** Indeks potongan (Seg) yang menjadi target pemberian Tag (multiseleksi atau potongan aktif). */
    fun targetTagSegmentIndices(): Set<Int> {
        val fromMulti = gridSelectedKeys.filter { it in core.segs.indices }.toSet()
        if (fromMulti.isNotEmpty()) return fromMulti
        return if (selIndex in core.segs.indices) setOf(selIndex) else emptySet()
    }

    fun openGridTagManager() {
        if (hasVideo) pause()
        gridTagSheet = true
    }

    // ------------------------------------------------------------------ Eksport & Import Tagging (Mode Grid)

    fun defaultTaggingFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "tagging_video_$stamp.json"
    }

    /** Membangun JSON lengkap berisi daftar tag, warna 12 opsi, dan pemetaan tag tiap klip. */
    fun buildTaggingExportJson(): String {
        val root = JSONObject()
        root.put("format", "editor_video_tagging_v1")
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))

        // Gabungkan semua nama tag yang terdaftar maupun yang terpasang di klip
        val allTagNames = LinkedHashSet<String>()
        for (t in availableTags) {
            val clean = t.trim()
            if (clean.isNotEmpty()) allTagNames.add(clean)
        }
        for (g in core.segs) {
            for (t in g.tags) {
                val clean = t.trim()
                if (clean.isNotEmpty()) allTagNames.add(clean)
            }
        }

        val tagsArr = JSONArray()
        for (tagName in allTagNames) {
            val cIdx = tagColorIndex(tagName)
            val cOpt = TAG_COLOR_PALETTE.getOrElse(cIdx) { TAG_COLOR_PALETTE.first() }
            val tObj = JSONObject()
            tObj.put("name", tagName)
            tObj.put("colorIndex", cIdx)
            tObj.put("colorName", cOpt.name)
            tagsArr.put(tObj)
        }
        root.put("availableTags", tagsArr)

        val segsArr = JSONArray()
        for ((idx, g) in core.segs.withIndex()) {
            val sObj = JSONObject()
            sObj.put("index", idx)
            sObj.put("clipNumber", idx + 1)
            sObj.put("src", g.src)
            sObj.put("startSec", g.a)
            sObj.put("endSec", g.b)
            val gTags = JSONArray()
            for (t in g.tags) gTags.put(t)
            sObj.put("tags", gTags)
            segsArr.put(sObj)
        }
        root.put("segments", segsArr)
        return root.toString(2)
    }

    fun exportTaggingToUri(uri: Uri) {
        val jsonText = buildTaggingExportJson()
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                app.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(jsonText.toByteArray(Charsets.UTF_8))
                    out.flush()
                    true
                } ?: false
            } catch (e: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                if (ok) {
                    val taggedClips = core.segs.count { it.tags.isNotEmpty() }
                    toast("Eksport tagging berhasil (${availableTags.size} tag, $taggedClips klip bertag)")
                } else {
                    toast("Gagal menyimpan berkas tagging")
                }
            }
        }
    }

    fun importTaggingFromUri(uri: Uri) {
        val app = getApplication<Application>()
        val replaceMode = tagImportReplaceMode
        viewModelScope.launch(Dispatchers.IO) {
            val rawText = try {
                app.contentResolver.openInputStream(uri)?.use { inp ->
                    inp.readBytes().toString(Charsets.UTF_8)
                }
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (rawText.isNullOrBlank()) {
                    toast("Berkas tagging kosong atau tidak bisa dibaca")
                } else {
                    importTaggingFromJson(rawText, replaceMode)
                }
            }
        }
    }

    fun importTaggingFromJson(rawJson: String, replaceExisting: Boolean = tagImportReplaceMode): Boolean {
        return try {
            val root = JSONObject(rawJson.trim())
            val mergedTags = ArrayList(availableTags)
            val mergedColors = LinkedHashMap(tagColorMap)
            var importedDefCount = 0

            fun registerTagDef(nameRaw: String, colorIdxOpt: Int?) {
                val clean = nameRaw.trim().take(24)
                if (clean.isEmpty()) return
                val existing = mergedTags.firstOrNull { it.equals(clean, ignoreCase = true) }
                val canonical = existing ?: clean
                if (existing == null) {
                    mergedTags.add(canonical)
                }
                if (colorIdxOpt != null && colorIdxOpt in 0..TAG_COLOR_PALETTE.lastIndex) {
                    mergedColors[canonical.lowercase(Locale.ROOT)] = colorIdxOpt
                }
                importedDefCount++
            }

            val availArr = root.optJSONArray("availableTags")
            if (availArr != null) {
                for (i in 0 until availArr.length()) {
                    val item = availArr.opt(i)
                    when (item) {
                        is JSONObject -> {
                            val nm = item.optString("name", "")
                            val hasCol = item.has("colorIndex")
                            val cIdx = if (hasCol) item.optInt("colorIndex", 0) else null
                            registerTagDef(nm, cIdx)
                        }
                        is String -> registerTagDef(item, null)
                    }
                }
            }

            val segTagsMap = LinkedHashMap<Int, List<String>>()
            val segsArr = root.optJSONArray("segments")
            if (segsArr != null && hasVideo && core.segs.isNotEmpty()) {
                val curSegs = core.segs
                for (i in 0 until segsArr.length()) {
                    val sObj = segsArr.optJSONObject(i) ?: continue
                    val rawIdx = sObj.optInt("index", -1)
                    val src = sObj.optInt("src", 0)
                    val startSec = sObj.optDouble("startSec", Double.NaN)
                    val endSec = sObj.optDouble("endSec", Double.NaN)
                    val tArr = sObj.optJSONArray("tags") ?: continue

                    val parsedTags = ArrayList<String>()
                    for (j in 0 until tArr.length()) {
                        val rawT = tArr.optString(j, "").trim().take(24)
                        if (rawT.isNotEmpty()) {
                            registerTagDef(rawT, null)
                            val canonical = mergedTags.firstOrNull { it.equals(rawT, ignoreCase = true) } ?: rawT
                            if (canonical !in parsedTags) parsedTags.add(canonical)
                        }
                    }

                    // Cocokkan klip: indeks + waktu -> rentang waktu -> indeks klip
                    val matchedIdx: Int = when {
                        rawIdx in curSegs.indices && !startSec.isNaN() && !endSec.isNaN() &&
                            abs(curSegs[rawIdx].a - startSec) < 0.08 &&
                            abs(curSegs[rawIdx].b - endSec) < 0.08 -> rawIdx
                        !startSec.isNaN() && !endSec.isNaN() -> {
                            val byTime = curSegs.indexOfFirst { g ->
                                g.src == src && abs(g.a - startSec) < 0.08 && abs(g.b - endSec) < 0.08
                            }
                            if (byTime >= 0) byTime else if (rawIdx in curSegs.indices) rawIdx else -1
                        }
                        rawIdx in curSegs.indices -> rawIdx
                        else -> -1
                    }

                    if (matchedIdx in curSegs.indices) {
                        val prev = segTagsMap[matchedIdx] ?: emptyList()
                        segTagsMap[matchedIdx] = (prev + parsedTags).distinct()
                    }
                }
            }

            if (importedDefCount == 0 && segTagsMap.isEmpty()) {
                toast("Format JSON tagging tidak dikenali atau kosong")
                return false
            }

            availableTags = mergedTags
            tagColorMap = mergedColors
            saveCustomTags(mergedTags)
            saveTagColors(mergedColors)

            val changedSegs = if (hasVideo && core.segs.isNotEmpty() && (segTagsMap.isNotEmpty() || replaceExisting)) {
                core.applyImportedSegmentTags(segTagsMap, replaceExisting)
            } else {
                0
            }

            refreshDerived()
            bump()
            if (hasVideo && core.segs.isNotEmpty()) {
                toast("Import tagging sukses: ${mergedTags.size} tag, $changedSegs klip diperbarui")
            } else {
                toast("Import daftar tag & warna berhasil (${mergedTags.size} tag)")
            }
            true
        } catch (e: Exception) {
            toast("Gagal membaca format JSON tagging")
            false
        }
    }

    fun toggleTagForCurrentTarget(tag: String) {
        val targets = targetTagSegmentIndices()
        if (targets.isEmpty()) {
            toast("Pilih potongan terlebih dahulu")
            return
        }
        val msg = core.toggleTagOnSegments(targets, tag)
        refreshDerived()
        bump()
        toast(msg)
    }

    fun clearTagsForCurrentTarget() {
        val targets = targetTagSegmentIndices()
        if (targets.isEmpty()) return
        val msg = core.clearTagsOnSegments(targets)
        refreshDerived()
        bump()
        toast(msg)
    }

    fun deleteGridSelectedSegments() {
        val segTargets = gridSelectedKeys.filter { it in core.segs.indices }.toSet()
        if (segTargets.isEmpty()) {
            toast("Pilih potongan klip untuk dihapus")
            return
        }
        val msg = core.deleteSegments(segTargets)
        clearGridMultiSelect()
        refreshDerived()
        setSeekFast(false)
        seekVideoToEdit(forceExact = true)
        bump()
        toast(msg)
    }

    /** Menggabungkan 2+ scene bersebelahan yang dipilih di Mode Grid tanpa render ulang. */
    fun mergeGridSelectedSegments() {
        val segTargets = gridSelectedKeys.filter { it in core.segs.indices }.toSet()
        if (segTargets.size < 2) {
            toast("Pilih minimal 2 scene bersebelahan untuk digabung")
            return
        }
        val msg = core.mergeSegmentsGroup(segTargets)
        clearGridMultiSelect()
        refreshDerived()
        setSeekFast(false)
        seekVideoToEdit(forceExact = true)
        bump()
        toast(msg)
    }

    // ------------------------------------------------------------------ kontrol Tanda (Markers) & Split by Tanda

    fun timelineProjectedMarkers(): List<ProjectedMarker> = core.projectedMarkers()

    fun markersInActiveSceneCount(): Int =
        if (hasVideo && core.sel in core.segs.indices) core.markerCutSecsInSegment(core.sel).size else 0

    fun toggleMarkerAtPlayhead() {
        val msg = core.toggleMarkerAtPlayhead()
        refreshDerived()
        bump()
        toast(msg)
    }

    fun jumpToMarker(forward: Boolean) {
        val hit = core.jumpToAdjacentMarker(forward)
        if (hit == null) {
            toast("Belum ada tanda di timeline")
            return
        }
        refreshDerived()
        setSeekFast(false)
        seekVideoToEdit(forceExact = true)
        bump()
        toast("📍 ${hit.label.ifEmpty { "Tanda" }} · ${Fmt.time(hit.timelineSec)}")
    }

    fun clearAllMarkers() {
        val msg = core.clearAllMarkers()
        refreshDerived()
        if (sceneDetectSheet && currentScanResult != null) {
            rebuildDetectedScenesFromCache()
        }
        bump()
        toast(msg)
    }

    fun splitByMarkersNow(onlyActiveClip: Boolean) {
        val targetIdx = if (onlyActiveClip) core.sel.coerceIn(0, max(0, core.segs.lastIndex)) else null
        val msg = core.splitByMarkers(onlySegIdx = targetIdx, clearUsedMarkers = false)
        if (onlyActiveClip) {
            sceneDetectSheet = false
            currentScanResult = null
        }
        refreshDerived()
        setSeekFast(false)
        seekVideoToEdit(forceExact = true)
        bump()
        toast(msg)
    }

    fun splitCurrentClipForGrid(parts: Int) {
        if (!hasVideo || core.segs.isEmpty()) return
        val idx = selIndex.coerceIn(0, core.segs.size - 1)
        val msg = core.splitIntoEqualParts(idx, parts)
        gridSubMode = 1
        refreshDerived()
        setSeekFast(false)
        seekVideoToEdit(forceExact = true)
        bump()
        toast(msg)
    }

    /** Membuka Multiplayer Collage dari item-item yang dipilih lewat Hold Multiseleksi. */
    fun openMultiplayerCollage() {
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) {
            toast("Impor video terlebih dahulu")
            return
        }
        val keys = gridSelectedKeys
        if (keys.isEmpty()) {
            toast("Hold item pada Grid untuk memilih klip Collage")
            return
        }
        pause()
        val total = core.total.coerceAtLeast(0.1)
        val sliceCount = max(9, gridColumns * 3)
        val sliceDur = total / sliceCount.toDouble()

        val built = ArrayList<CollageClipItem>()
        for ((orderIdx, key) in keys.withIndex()) {
            if (built.size >= MAX_COLLAGE_TILES) break
            if (key >= SLICE_KEY_OFFSET) {
                val sIdx = key - SLICE_KEY_OFFSET
                val tStart = (sIdx * sliceDur).coerceIn(0.0, max(0.0, total - 0.05))
                val tEnd = ((sIdx + 1) * sliceDur).coerceIn(tStart + 0.05, total)
                val locStart = core.locate(tStart)
                val g = core.segs.getOrElse(locStart.i) { core.segs.first() }
                val src = sources.getOrElse(g.src) { sources.first() }
                val srcStartMs = Math.round(max(0.0, locStart.s) * 1000.0)
                val durSrcMs = Math.round((tEnd - tStart) * g.sp * 1000.0).coerceAtLeast(150L)
                val srcEndMs = (srcStartMs + durSrcMs).coerceAtMost(max(srcStartMs + 100L, src.info.durationMs))
                built.add(
                    CollageClipItem(
                        id = orderIdx,
                        label = "Bagian #${sIdx + 1}",
                        uri = src.uri,
                        startMs = srcStartMs,
                        endMs = srcEndMs,
                        speed = g.sp.toFloat().coerceIn(0.2f, 4f),
                        aspect = aspectOfSource(g.src, g.rot),
                        rot = g.rot,
                        flip = flip,
                        freeze = g.fz
                    )
                )
            } else if (key in core.segs.indices) {
                val g = core.segs[key]
                val src = sources.getOrElse(g.src) { sources.first() }
                val startMs = Math.round(max(0.0, g.a) * 1000.0)
                val endMs = if (g.fz) {
                    (startMs + 200L).coerceAtMost(max(startMs + 50L, src.info.durationMs))
                } else {
                    Math.round(max(g.a + 0.05, g.b) * 1000.0)
                        .coerceAtMost(max(startMs + 100L, src.info.durationMs))
                }
                built.add(
                    CollageClipItem(
                        id = orderIdx,
                        label = "Klip #${key + 1}",
                        uri = src.uri,
                        startMs = startMs,
                        endMs = endMs,
                        speed = g.sp.toFloat().coerceIn(0.2f, 4f),
                        aspect = aspectOfSource(g.src, g.rot),
                        rot = g.rot,
                        flip = flip,
                        freeze = g.fz
                    )
                )
            }
        }

        if (built.isEmpty()) {
            toast("Pilih minimal 1 klip untuk Multiplayer Collage")
            return
        }
        collageItems = built
        collageOpen = true
        if (keys.size > MAX_COLLAGE_TILES) {
            toast("Menampilkan $MAX_COLLAGE_TILES klip pertama secara bersamaan")
        }
    }

    fun closeMultiplayerCollage() {
        collageOpen = false
        collageItems = emptyList()
        refreshFrame()
    }

    // ------------------------------------------------------------------ Registrasi TextureView & UI Fitur AI Presisi Tinggi

    fun attachPreviewTextureView(tv: TextureView) {
        activeTextureViewRef = WeakReference(tv)
    }

    fun detachPreviewTextureView(tv: TextureView) {
        if (activeTextureViewRef?.get() === tv) {
            activeTextureViewRef = null
        }
    }

    private fun transformPreviewBitmap(raw: Bitmap, curRot: Int, curFlip: Boolean): Bitmap {
        val normRot = ((curRot % 360) + 360) % 360
        if (normRot == 0 && !curFlip) return raw
        val m = Matrix()
        if (normRot != 0) m.postRotate(normRot.toFloat())
        if (curFlip) m.postScale(-1f, 1f)
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    /**
     * Mengambil bitmap langsung dari buffer GPU TextureView Preview yang sedang tampil di layar.
     * Ini menjamin gambar 100% IDENTIK pixel-per-pixel dengan yang terlihat di Preview.
     */
    private fun grabLivePreviewBitmap(): Bitmap? {
        val tv = activeTextureViewRef?.get() ?: return null
        if (!tv.isAvailable || tv.width <= 4 || tv.height <= 4) return null
        return try {
            val raw = tv.bitmap ?: return null
            transformPreviewBitmap(raw, rot, flip)
        } catch (e: Throwable) {
            null
        }
    }

    fun openAiPanel() {
        pause() // pause() juga menyinkronkan core.t tepat ke milidetik posisi frame di Preview
        aiPreviewStatus = null
        if (aiPrompt.isBlank()) {
            aiPrompt = aiSegmentPrompts.firstOrNull().orEmpty()
        }
        aiSheet = true
        captureAiSeekFrame(waitSeekSettle = false)
        if (aiWorkMode == AiWorkMode.REPLACE_SCENE) {
            syncReplaceSceneSelection(autoMatchDurationIfEligible = false)
        }
    }

    /** Menggeser posisi seek maju/mundur per frame video secara presisi dari dalam panel AI. */
    fun stepAiFrame(deltaFrames: Int) {
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) return
        pause()
        val loc = core.locate(core.t)
        val seg = core.segs[loc.i]
        val src = sources.getOrElse(seg.src) { sources.first() }
        val fps = (if (src.info.fps > 1f) src.info.fps else 30f).toDouble()
        val oneFrameTimelineSec = (1.0 / fps) / max(0.1, seg.sp)
        val nextT = clampD(core.t + deltaFrames * oneFrameTimelineSec, 0.0, core.total)
        setSeekFast(false)
        core.setTime(nextT)
        seekVideoToEdit(forceExact = true)
        refreshDerived()
        bump()
        captureAiSeekFrame(waitSeekSettle = true)
    }

    /**
     * Menangkap frame pada posisi seek saat ini dengan akurasi maksimal:
     *  1. Memastikan ExoPlayer berada pada mode SeekParameters.EXACT tepat di waktu sumber.
     *  2. Membaca langsung buffer TextureView Preview (100% identik dengan tampilan Preview).
     *  3. Bila sedang di Mode Grid sel kecil (lebar < 280px) atau TextureView belum tersedia,
     *     melakukan decode presisi mikrodetik via MediaCodec (VideoFrames.grabExactFrame).
     */
    fun captureAiSeekFrame(waitSeekSettle: Boolean = false) {
        aiFrameJob?.cancel()
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) {
            aiSeekBitmap = null
            aiFrameLoading = false
            aiSeekTimeSec = 0.0
            aiSeekSourceSec = 0.0
            aiSeekClipIndex = 0
            return
        }

        val loc = core.locate(core.t)
        val seg = core.segs[loc.i]
        val src = sources.getOrElse(seg.src) { sources.first() }
        val targetMs = Math.round(max(0.0, loc.s) * 1000.0)
        val targetUs = Math.round(max(0.0, loc.s) * 1_000_000.0)

        aiSeekTimeSec = core.t
        aiSeekSourceSec = loc.s
        aiSeekClipIndex = loc.i
        aiSeekFps = if (src.info.fps > 1f) src.info.fps else 30f

        // Pastikan player di-seek EXACT bila posisinya meleset lebih dari 10ms
        val needExactSeek = waitSeekSettle ||
            player.currentMediaItemIndex != seg.src.coerceIn(0, sources.size - 1) ||
            abs(player.currentPosition - targetMs) > 10L ||
            player.playbackState == Player.STATE_BUFFERING

        if (needExactSeek) {
            setSeekFast(false)
            seekVideoToEdit(forceExact = true)
        } else {
            // Langsung ambil dari TextureView Preview saat ini (0ms, 100% sama persis dengan Preview!)
            val instantLive = grabLivePreviewBitmap()
            if (instantLive != null) {
                aiSeekBitmap = instantLive
                aiCaptureSourceLabel = "Identik Preview"
                val tvW = activeTextureViewRef?.get()?.width ?: 0
                if (!gridMode && tvW >= 260) {
                    aiFrameLoading = false
                    return
                }
            }
        }

        aiFrameLoading = true
        val curRot = rot
        val curFlip = flip
        val startSeq = renderedFrameSeq

        aiFrameJob = viewModelScope.launch {
            if (needExactSeek) {
                // Tunggu sampai ExoPlayer selesai merender frame EXACT baru ke TextureView (maks ~420ms)
                var waited = 0
                while (waited < 420) {
                    if (player.playbackState == Player.STATE_READY &&
                        !player.isLoading &&
                        renderedFrameSeq > startSeq
                    ) {
                        delay(24) // beri 1 vsync agar buffer SurfaceTexture terbarui penuh
                        break
                    }
                    delay(20)
                    waited += 20
                }
            }

            // Ambil lagi dari TextureView setelah frame EXACT ter-render di Preview
            val liveAfterSettle = grabLivePreviewBitmap()
            val tvWidth = activeTextureViewRef?.get()?.width ?: 0
            if (liveAfterSettle != null) {
                aiSeekBitmap = liveAfterSettle
                aiCaptureSourceLabel = "Identik Preview"
                if (!gridMode && tvWidth >= 260) {
                    aiFrameLoading = false
                    return@launch
                }
            }

            // Fallback / peningkatan resolusi bila di Mode Grid sel kecil atau TextureView belum siap:
            // Decode tepat pada PTS mikrodetik menggunakan MediaCodec (bukan keyframe jauh!)
            val ctx = getApplication<Application>()
            val exactDecoded = withContext(Dispatchers.IO) {
                val rawExact = VideoFrames.grabExactFrame(ctx, src.uri, targetUs, src.info)
                    ?: return@withContext null
                val sized = VideoFrames.limitLongSide(rawExact, 1080)
                transformPreviewBitmap(sized, curRot, curFlip)
            }
            if (exactDecoded != null) {
                aiSeekBitmap = exactDecoded
                aiCaptureSourceLabel = if (liveAfterSettle != null) "Presisi Frame HD" else "Presisi PTS"
            }
            aiFrameLoading = false
        }
    }

    val aiIsRunning: Boolean
        get() = aiRunPhase == AiRunPhase.UPLOADING ||
            aiRunPhase == AiRunPhase.WAITING_WORKFLOW ||
            aiRunPhase == AiRunPhase.GENERATING_VIDEO ||
            aiRunPhase == AiRunPhase.DOWNLOADING

    /** Durasi scene aktif di timeline (detik). */
    fun selectedSceneDurationSec(): Double =
        core.segs.getOrNull(core.sel)?.len ?: 5.0

    /** Apakah shortcut "Sesuai Durasi Scene" dapat diaktifkan (aktif bila durasi scene <= 10.0 detik). */
    fun canUseSceneDurationShortcut(): Boolean =
        hasVideo &&
            core.segs.isNotEmpty() &&
            selectedSceneDurationSec() <= AiPlanner.ZEROGPU_MAX_REPLACE_SEC + 0.001

    /** Apakah nilai range slider saat ini sudah sama dengan durasi scene terpilih. */
    fun isSceneDurationShortcutActive(): Boolean =
        canUseSceneDurationShortcut() &&
            abs(aiReplaceTargetDurSec - (Math.round(selectedSceneDurationSec() * 10.0) / 10.0)) < 0.06

    /** Shortcut 1-ketuk untuk menyamakan range durasi Replace Scene dengan durasi scene terpilih (<=10 dtk). */
    fun applySceneDurationShortcut() {
        if (!canUseSceneDurationShortcut()) {
            toast("Durasi scene melebihi batas 10.0 detik ZeroGPU")
            return
        }
        val exact = clampD(
            Math.round(selectedSceneDurationSec() * 10.0) / 10.0,
            AiPlanner.ZEROGPU_MIN_REPLACE_SEC,
            AiPlanner.ZEROGPU_MAX_REPLACE_SEC
        )
        aiReplaceTargetDurSec = exact
        captureReplaceSceneFrames()
        bump()
        toast("Durasi replace disetel sesuai scene (${Fmt.one(exact)} dtk)")
    }

    /** Mengubah nilai slider input range durasi pada Mode Replace Scene (1.0 .. 10.0 dtk). */
    fun updateReplaceTargetDuration(sec: Double, refreshEndFrame: Boolean = false) {
        val clamped = clampD(
            Math.round(sec * 10.0) / 10.0,
            AiPlanner.ZEROGPU_MIN_REPLACE_SEC,
            AiPlanner.ZEROGPU_MAX_REPLACE_SEC
        )
        aiReplaceTargetDurSec = clamped
        if (refreshEndFrame) {
            captureReplaceSceneFrames()
        }
        bump()
    }

    fun toggleReplaceBridgeLastFrame() {
        aiReplaceBridgeLastFrame = !aiReplaceBridgeLastFrame
        if (aiReplaceBridgeLastFrame) {
            captureReplaceSceneFrames()
        }
        bump()
    }

    /** Mengganti Mode Kerja AI (Mode Sisipkan vs Replace Scene). */
    fun selectAiWorkMode(mode: AiWorkMode) {
        aiWorkMode = mode
        if (mode == AiWorkMode.REPLACE_SCENE) {
            syncReplaceSceneSelection(autoMatchDurationIfEligible = true)
        } else {
            captureAiSeekFrame(waitSeekSettle = false)
        }
        bump()
    }

    /** Berpindah ke klip/scene sebelumnya atau sesudahnya untuk Mode Replace Scene. */
    fun stepReplaceTargetScene(deltaClip: Int) {
        if (!hasVideo || core.segs.isEmpty()) return
        pause()
        val nextIdx = (core.sel + deltaClip).coerceIn(0, core.segs.lastIndex)
        core.sel = nextIdx
        val targetStart = core.lay.off.getOrElse(nextIdx) { 0.0 }
        setSeekFast(false)
        core.setTime(targetStart)
        seekVideoToEdit(forceExact = true)
        refreshDerived()
        syncReplaceSceneSelection(autoMatchDurationIfEligible = true)
        bump()
    }

    private fun syncReplaceSceneSelection(autoMatchDurationIfEligible: Boolean) {
        if (!hasVideo || core.segs.isEmpty()) return
        val sceneLen = selectedSceneDurationSec()
        if (autoMatchDurationIfEligible) {
            aiReplaceTargetDurSec = clampD(
                Math.round(min(AiPlanner.ZEROGPU_MAX_REPLACE_SEC, sceneLen) * 10.0) / 10.0,
                AiPlanner.ZEROGPU_MIN_REPLACE_SEC,
                AiPlanner.ZEROGPU_MAX_REPLACE_SEC
            )
        } else {
            aiReplaceTargetDurSec = clampD(
                aiReplaceTargetDurSec,
                AiPlanner.ZEROGPU_MIN_REPLACE_SEC,
                AiPlanner.ZEROGPU_MAX_REPLACE_SEC
            )
        }
        captureReplaceSceneFrames()
    }

    /** Menangkap Frame Awal Scene dan Frame Akhir bagian yang akan di-replace pada Mode Replace Scene. */
    fun captureReplaceSceneFrames() {
        aiReplaceFrameJob?.cancel()
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) {
            aiReplaceFirstBitmap = null
            aiReplaceLastBitmap = null
            return
        }
        val idx = core.sel.coerceIn(0, core.segs.lastIndex)
        val g = core.segs[idx]
        val src = sources.getOrElse(g.src) { sources.first() }
        val curRot = g.rot
        val curFlip = flip
        val startSrcSec = g.a
        val replaceSpanSec = min(g.len, aiReplaceTargetDurSec)
        val endSrcSec = if (g.fz) g.a else clampD(g.a + max(0.0, replaceSpanSec - 0.04) * g.sp, g.a, g.b)
        val wantLast = aiReplaceBridgeLastFrame

        aiReplaceFrameJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val pair = withContext(Dispatchers.IO) {
                val startUs = Math.round(max(0.0, startSrcSec) * 1_000_000.0)
                val rawFirst = VideoFrames.grabExactFrame(ctx, src.uri, startUs, src.info)
                    ?: VideoFrames.grabFrame(ctx, src.uri, Math.round(max(0.0, startSrcSec) * 1000.0), src.info)
                val firstBmp = rawFirst?.let {
                    transformPreviewBitmap(VideoFrames.limitLongSide(it, 1080), curRot, curFlip)
                }
                val lastBmp = if (wantLast) {
                    val endUs = Math.round(max(0.0, endSrcSec) * 1_000_000.0)
                    val rawLast = VideoFrames.grabExactFrame(ctx, src.uri, endUs, src.info)
                        ?: VideoFrames.grabFrame(ctx, src.uri, Math.round(max(0.0, endSrcSec) * 1000.0), src.info)
                    rawLast?.let {
                        transformPreviewBitmap(VideoFrames.limitLongSide(it, 1080), curRot, curFlip)
                    }
                } else {
                    null
                }
                firstBmp to lastBmp
            }
            aiReplaceFirstBitmap = pair.first
            aiReplaceLastBitmap = pair.second
        }
    }

    /** Mengunci frame pada posisi seek saat ini sebagai Frame Akhir opsional untuk Mode Sisipkan. */
    fun captureInsertLastFrame() {
        val curBmp = aiSeekBitmap ?: grabLivePreviewBitmap()
        if (curBmp == null || curBmp.isRecycled) {
            toast("Frame pratinjau belum siap")
            return
        }
        aiInsertLastBitmap = curBmp.copy(Bitmap.Config.ARGB_8888, false)
        aiInsertLastTimeSec = aiSeekTimeSec
        bump()
        toast("Frame Akhir dikunci pada ${Fmt.preciseTime(aiInsertLastTimeSec)}")
    }

    fun clearInsertLastFrame() {
        aiInsertLastBitmap = null
        aiInsertLastTimeSec = -1.0
        bump()
    }

    /** memilih paket durasi Mode Sisipkan (3×3.5 dtk, 2×5 dtk, 7 dtk, 10 dtk). */
    fun selectAiGenMode(mode: AiGenerationMode) {
        aiGenMode = mode
        val plan = currentAiExecutionPlan()
        if (plan.count <= 1) {
            aiPerSegmentPromptMode = false
            aiActivePromptSegSlot = 0
            aiPrompt = aiSegmentPrompts[0]
        } else if (aiActivePromptSegSlot > plan.count) {
            aiActivePromptSegSlot = 1
            aiPrompt = aiSegmentPrompts[1]
        }
        bump()
    }

    fun updateAiInsertSingleDur(sec: Double) {
        aiInsertSingleDurSec = clampD(
            Math.round(sec * 10.0) / 10.0,
            AiPlanner.ZEROGPU_MIN_REPLACE_SEC,
            AiPlanner.MAX_WORKFLOW_SEC
        )
        bump()
    }

    fun selectAiInsertPreset(preset: AiInsertPreset) {
        aiInsertPreset = preset
        // Bila memilih preset 1 Video, otomatis selaraskan durasi single; bila memilih multi-segmen, aktifkan MULTI_SEGMENT
        if (preset.segDurs.size == 1) {
            aiInsertSingleDurSec = preset.segDurs.first()
        } else {
            aiGenMode = AiGenerationMode.MULTI_SEGMENT
        }
        aiTimelineJoinStyle = if (preset.autoMergeDefault) {
            AiTimelineJoinStyle.MERGED
        } else {
            AiTimelineJoinStyle.SEPARATE
        }
        val segCount = currentAiExecutionPlan().count
        if (segCount <= 1) {
            aiPerSegmentPromptMode = false
            aiActivePromptSegSlot = 0
            aiPrompt = aiSegmentPrompts[0]
        } else if (aiActivePromptSegSlot > segCount) {
            aiActivePromptSegSlot = 1
            aiPrompt = aiSegmentPrompts[1]
        }
        bump()
    }

    fun selectAiSpace(space: AiSpaceOption) {
        aiSpaceOption = space
        if (!space.supportsSafetyChecker) {
            aiSafetyChecker = false
        }
        bump()
    }

    fun currentAiExecutionPlan(): AiExecutionPlan =
        if (aiWorkMode == AiWorkMode.INSERT) {
            AiPlanner.planInsert(aiInsertPreset, aiGenMode, aiInsertSingleDurSec)
        } else {
            AiPlanner.planReplace(aiReplaceTargetDurSec, selectedSceneDurationSec(), aiGenMode)
        }

    // --- Pengaturan Prompt Tunggal / Per-Segmen (Segmen 1..3) ---

    fun selectPromptSegmentSlot(slot: Int) {
        if (slot <= 0) {
            aiPerSegmentPromptMode = false
            aiActivePromptSegSlot = 0
            aiPrompt = aiSegmentPrompts[0]
        } else {
            val safeSlot = slot.coerceIn(1, 3)
            aiPerSegmentPromptMode = true
            aiActivePromptSegSlot = safeSlot
            aiPrompt = aiSegmentPrompts[safeSlot]
        }
        bump()
    }

    fun updateActiveAiPromptText(text: String) {
        aiPrompt = text
        aiPreviewStatus = null
        val mutable = aiSegmentPrompts.toMutableList()
        while (mutable.size < 4) mutable.add("")
        if (!aiPerSegmentPromptMode || aiActivePromptSegSlot == 0) {
            mutable[0] = text
            mutable[1] = text
            mutable[2] = text
            mutable[3] = text
        } else {
            mutable[aiActivePromptSegSlot.coerceIn(1, 3)] = text
        }
        aiSegmentPrompts = mutable
    }

    private fun promptForSegmentOneBased(segNum: Int): String {
        return if (!aiPerSegmentPromptMode) {
            aiPrompt.trim().ifEmpty { aiSegmentPrompts.getOrElse(0) { "" }.trim() }
        } else {
            aiSegmentPrompts.getOrElse(segNum.coerceIn(1, 3)) { aiPrompt }.trim()
                .ifEmpty { aiPrompt.trim() }
        }
    }

    // --- Tab Management Prompt (Simpan, Edit, Hapus, Favorit, Kategori, JSON/Clipboard) ---

    fun filteredAiPrompts(): List<AiPromptItem> {
        val q = aiPromptSearchQuery.trim().lowercase(Locale.ROOT)
        return aiPromptLibrary.filter { item ->
            val catOk = when (aiPromptFilterCategory) {
                "ALL" -> true
                "FAV" -> item.favorite
                else -> item.category.equals(aiPromptFilterCategory, ignoreCase = true)
            }
            val qOk = q.isEmpty() ||
                item.title.lowercase(Locale.ROOT).contains(q) ||
                item.body.lowercase(Locale.ROOT).contains(q) ||
                item.category.lowercase(Locale.ROOT).contains(q)
            catOk && qOk
        }
    }

    fun fillPromptFormFromActiveEditor() {
        aiFormPromptBody = aiPrompt
        if (aiFormPromptTitle.isBlank()) {
            aiFormPromptTitle = "Prompt " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }
        toast("Isi editor prompt disalin ke form")
    }

    fun startEditingPromptItem(item: AiPromptItem) {
        aiEditingPromptId = item.id
        aiFormPromptTitle = item.title
        aiFormPromptCategory = item.category
        aiFormPromptBody = item.body
    }

    fun cancelEditingPromptItem() {
        aiEditingPromptId = null
        aiFormPromptTitle = ""
        aiFormPromptBody = ""
    }

    fun savePromptLibraryEntry() {
        val title = aiFormPromptTitle.trim()
        val body = aiFormPromptBody.trim()
        val cat = aiFormPromptCategory.trim().ifEmpty { "Kustom" }
        if (title.isEmpty() || body.isEmpty()) {
            toast("Isi judul dan teks prompt terlebih dahulu")
            return
        }
        val editId = aiEditingPromptId
        val nextList = if (editId != null) {
            aiPromptLibrary.map {
                if (it.id == editId) it.copy(title = title, category = cat, body = body) else it
            }
        } else {
            listOf(
                AiPromptItem(
                    id = "pm_" + System.currentTimeMillis(),
                    title = title,
                    category = cat,
                    body = body,
                    favorite = false
                )
            ) + aiPromptLibrary
        }
        aiPromptLibrary = nextList
        persistPromptLibrary()
        aiEditingPromptId = null
        aiFormPromptTitle = ""
        aiFormPromptBody = ""
        toast(if (editId != null) "Prompt diperbarui" else "Prompt disimpan ke Management Prompt")
    }

    fun toggleFavoritePrompt(id: String) {
        aiPromptLibrary = aiPromptLibrary.map {
            if (it.id == id) it.copy(favorite = !it.favorite) else it
        }
        persistPromptLibrary()
    }

    fun deletePromptLibraryEntry(id: String) {
        aiPromptLibrary = aiPromptLibrary.filterNot { it.id == id }
        persistPromptLibrary()
        if (aiEditingPromptId == id) cancelEditingPromptItem()
        toast("Prompt dihapus")
    }

    /** Memasang item koleksi prompt ke semua segmen (slot=0) atau ke segmen tertentu (slot=1..3). */
    fun applyLibraryPromptToSegment(item: AiPromptItem, slot: Int) {
        val mutable = aiSegmentPrompts.toMutableList()
        while (mutable.size < 4) mutable.add("")
        if (slot <= 0) {
            aiPerSegmentPromptMode = false
            aiActivePromptSegSlot = 0
            mutable[0] = item.body
            mutable[1] = item.body
            mutable[2] = item.body
            mutable[3] = item.body
            aiPrompt = item.body
            toast("Prompt \"${item.title}\" dipasang ke semua segmen")
        } else {
            val s = slot.coerceIn(1, 3)
            aiPerSegmentPromptMode = true
            aiActivePromptSegSlot = s
            mutable[s] = item.body
            aiPrompt = item.body
            toast("Prompt \"${item.title}\" dipasang ke Segmen #$s")
        }
        aiSegmentPrompts = mutable
        aiActiveTab = AiTab.CREATE
        bump()
    }

    private fun persistPromptLibrary() {
        prefs.edit().putString(KEY_AI_PROMPTS_JSON, AiPromptItem.toJsonString(aiPromptLibrary)).apply()
    }

    fun defaultPromptsFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "koleksi_prompt_ai_$stamp.json"
    }

    fun buildPromptsExportJson(): String = AiPromptItem.toJsonString(aiPromptLibrary)

    fun exportPromptsToUri(uri: Uri) {
        val payload = buildPromptsExportJson()
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                    out.flush()
                } != null
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                toast(
                    if (ok) "Koleksi ${aiPromptLibrary.size} prompt berhasil diekspor (.json)"
                    else "Gagal menyimpan berkas prompt"
                )
            }
        }
    }

    fun importPromptsFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (text.isNullOrBlank()) {
                    toast("Berkas JSON prompt kosong atau tidak terbaca")
                } else {
                    importPromptsFromRawJson(text)
                }
            }
        }
    }

    fun importPromptsFromRawJson(rawJson: String) {
        val parsed = AiPromptItem.parseJsonString(rawJson)
        if (parsed.isNullOrEmpty()) {
            toast("Format JSON koleksi prompt tidak valid")
            return
        }
        aiPromptLibrary = if (aiPromptImportMergeMode) {
            val existingBodies = aiPromptLibrary.map { it.body.trim().lowercase(Locale.ROOT) }.toHashSet()
            val fresh = parsed.filter { it.body.trim().lowercase(Locale.ROOT) !in existingBodies }
            fresh + aiPromptLibrary
        } else {
            parsed
        }
        persistPromptLibrary()
        toast("${parsed.size} prompt berhasil diimpor")
    }

    // --- Tab Runner: Koneksi Firebase & GitHub + Repo Runner Clipboards ---

    fun updateAiRunnerConfigField(
        firebaseUrl: String = aiRunnerConfig.firebaseUrl,
        githubOwner: String = aiRunnerConfig.githubOwner,
        githubRepo: String = aiRunnerConfig.githubRepo,
        githubBranch: String = aiRunnerConfig.githubBranch,
        workflowFile: String = aiRunnerConfig.workflowFile,
        githubToken: String = aiRunnerConfig.githubToken
    ) {
        aiRunnerConfig = AiRunnerConfig(
            firebaseUrl = firebaseUrl,
            githubOwner = githubOwner,
            githubRepo = githubRepo,
            githubBranch = githubBranch,
            workflowFile = workflowFile,
            githubToken = githubToken
        )
        aiConnectionTestMessage = null
    }

    fun saveAiRunnerConfig() {
        val obj = JSONObject()
            .put("firebaseUrl", aiRunnerConfig.firebaseUrl.trim())
            .put("githubOwner", aiRunnerConfig.githubOwner.trim())
            .put("githubRepo", aiRunnerConfig.githubRepo.trim())
            .put("githubBranch", aiRunnerConfig.githubBranch.trim().ifEmpty { "main" })
            .put("workflowFile", aiRunnerConfig.workflowFile.trim().ifEmpty { "wanvideo-r3gm-test.yml" })
            .put("githubToken", aiRunnerConfig.githubToken.trim())
        prefs.edit().putString(KEY_AI_RUNNER_CFG, obj.toString()).apply()
        toast("Pengaturan koneksi Firebase & GitHub disimpan")
    }

    fun runAiConnectionTest() {
        if (aiTestingConnection) return
        saveAiRunnerConfig()
        aiTestingConnection = true
        aiConnectionTestMessage = "Menghubungi Firebase & GitHub API…"
        viewModelScope.launch {
            val res = WanStudioClient.testConnection(aiRunnerConfig)
            val (wStat, wMsg) = if (res.isSuccess) {
                WanStudioClient.ensureLatestWorkerOnGitHub(aiRunnerConfig, attemptAutoPush = true)
            } else {
                WorkerSyncStatus.UNKNOWN to null
            }
            aiTestingConnection = false
            aiWorkerSyncStatus = wStat
            aiWorkerSyncMessage = wMsg
            res.fold(
                onSuccess = { msg ->
                    aiConnectionTestOk = true
                    aiConnectionTestMessage = if (!wMsg.isNullOrEmpty()) "$msg\n$wMsg" else msg
                    toast("Tes koneksi berhasil!")
                },
                onFailure = { err ->
                    aiConnectionTestOk = false
                    aiConnectionTestMessage = err.message ?: "Koneksi gagal."
                    toast("Tes koneksi gagal")
                }
            )
        }
    }

    /** Memeriksa & memperbarui otomatis script firebase_worker.py v3 ke repo GitHub pengguna. */
    fun syncRunnerWorkerToGitHub() {
        if (aiSyncingWorker) return
        saveAiRunnerConfig()
        aiSyncingWorker = true
        aiWorkerSyncStatus = WorkerSyncStatus.CHECKING
        aiWorkerSyncMessage = "Memeriksa & menyinkronkan firebase_worker.py v3 ke repo GitHub…"
        viewModelScope.launch {
            val (st, msg) = WanStudioClient.ensureLatestWorkerOnGitHub(aiRunnerConfig, attemptAutoPush = true)
            aiSyncingWorker = false
            aiWorkerSyncStatus = st
            aiWorkerSyncMessage = msg
            toast(
                when (st) {
                    WorkerSyncStatus.UP_TO_DATE -> "Worker GitHub sudah v3 terbaru ✓"
                    WorkerSyncStatus.AUTO_UPDATED -> "firebase_worker.py berhasil diupdate ke v3 ✓"
                    else -> "Salin firebase_worker.py v3 dari bawah"
                }
            )
        }
    }

    fun markRunnerClipboardCopied(itemId: String, label: String) {
        val updated = aiCopiedRunnerIds + itemId
        aiCopiedRunnerIds = updated
        prefs.edit().putStringSet(KEY_AI_COPIED_CLIPS, updated).apply()
        toast("$label disalin ke clipboard ✓")
    }

    fun resetRunnerClipboardChecklist() {
        aiCopiedRunnerIds = emptySet()
        prefs.edit().remove(KEY_AI_COPIED_CLIPS).apply()
        toast("Checklist 0/4 direset")
    }

    // --- Sistem Kandidat Hasil AI (Generate Berkali-kali & Pilih Hasil Terbaik) ---

    fun selectAiCandidate(index: Int) {
        if (index !in aiCandidates.indices) return
        aiActiveCandidateIdx = index
        val cand = aiCandidates[index]
        aiGeneratedSegments = cand.segments
        aiSnapshotWorkMode = cand.workMode
        aiSnapshotPlan = cand.plan
        aiSnapshotInsertSeekSec = cand.snapshotInsertSeekSec
        aiSnapshotReplaceSegIdx = cand.snapshotReplaceSegIdx
        aiPreviewResultIndex = 0
        if (cand.plan.autoMergeDefault) {
            aiTimelineJoinStyle = AiTimelineJoinStyle.MERGED
        }
        bump()
    }

    fun toggleFavoriteAiCandidate(index: Int) {
        if (index !in aiCandidates.indices) return
        val cur = aiCandidates[index]
        val nextList = aiCandidates.toMutableList()
        nextList[index] = cur.copy(isFavorite = !cur.isFavorite)
        aiCandidates = nextList
        toast(if (!cur.isFavorite) "⭐ Kandidat #${cur.candidateNumber} ditandai Terbaik" else "Tanda Terbaik dilepas")
    }

    fun deleteAiCandidate(index: Int) {
        if (aiIsRunning || index !in aiCandidates.indices) return
        val removed = aiCandidates[index]
        val nextList = aiCandidates.toMutableList().also { it.removeAt(index) }
        aiCandidates = nextList
        if (nextList.isEmpty()) {
            aiActiveCandidateIdx = 0
            aiGeneratedSegments = emptyList()
            aiPreviewResultIndex = 0
        } else {
            val nextIdx = index.coerceIn(0, nextList.lastIndex)
            selectAiCandidate(nextIdx)
        }
        toast("Kandidat #${removed.candidateNumber} dihapus")
    }

    fun clearAllAiCandidates() {
        if (aiIsRunning) return
        aiCandidates = emptyList()
        aiActiveCandidateIdx = 0
        aiGeneratedSegments = emptyList()
        aiPreviewResultIndex = 0
        toast("Daftar kandidat dibersihkan")
    }

    private fun syncActiveCandidateInPool(updatedSegs: List<AiGeneratedSegment>) {
        if (aiActiveCandidateIdx !in aiCandidates.indices) return
        val nextList = aiCandidates.toMutableList()
        nextList[aiActiveCandidateIdx] = nextList[aiActiveCandidateIdx].copy(segments = updatedSegs)
        aiCandidates = nextList
    }

    private fun upsertCandidateById(batch: AiCandidateBatch) {
        val nextList = aiCandidates.toMutableList()
        val existingIdx = nextList.indexOfFirst { it.id == batch.id }
        if (existingIdx >= 0) {
            nextList[existingIdx] = batch
            aiCandidates = nextList
            aiActiveCandidateIdx = existingIdx
        } else {
            nextList.add(batch)
            aiCandidates = nextList
            aiActiveCandidateIdx = nextList.lastIndex
        }
    }

    // --- Eksekusi Generasi AI, Kandidat Baru, Mode Tunggal, Berantai & Dual Timeout ---

    fun onAiGenerateClicked() {
        startAiGeneration()
    }

    fun startAiGeneration() {
        if (aiIsRunning) {
            toast("Generasi AI sedang berjalan")
            return
        }
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) {
            toast("Impor video terlebih dahulu untuk mengambil frame sumber")
            return
        }
        val plan = currentAiExecutionPlan()
        for (i in 1..plan.count) {
            if (promptForSegmentOneBased(i).isBlank()) {
                toast("Isi teks prompt untuk Segmen #$i terlebih dahulu")
                return
            }
        }
        if (!aiRunnerConfig.isComplete) {
            aiActiveTab = AiTab.RUNNER
            toast("Lengkapi koneksi Firebase & GitHub Runner terlebih dahulu")
            return
        }

        val initialFirstBmp: Bitmap? = if (aiWorkMode == AiWorkMode.INSERT) {
            aiSeekBitmap ?: grabLivePreviewBitmap()
        } else {
            aiReplaceFirstBitmap ?: aiSeekBitmap ?: grabLivePreviewBitmap()
        }
        if (initialFirstBmp == null || initialFirstBmp.isRecycled) {
            toast("Frame sumber belum siap, ketuk Sinkron terlebih dahulu")
            return
        }
        val finalTargetLastBmp: Bitmap? = if (aiWorkMode == AiWorkMode.INSERT) {
            aiInsertLastBitmap?.takeIf { !it.isRecycled }
        } else if (aiReplaceBridgeLastFrame) {
            aiReplaceLastBitmap?.takeIf { !it.isRecycled }
        } else {
            null
        }

        saveAiRunnerConfig()

        val snapMode = aiWorkMode
        val snapGenMode = aiGenMode
        val snapInsertSeekSec = aiSeekTimeSec
        val snapReplaceSegIdx = core.sel.coerceIn(0, core.segs.lastIndex)
        aiSnapshotWorkMode = snapMode
        aiSnapshotInsertSeekSec = snapInsertSeekSec
        aiSnapshotReplaceSegIdx = snapReplaceSegIdx
        aiSnapshotPlan = plan
        if (plan.autoMergeDefault) {
            aiTimelineJoinStyle = AiTimelineJoinStyle.MERGED
        }

        // Buat slot Kandidat Baru tanpa menghapus kandidat-kandidat yang sudah ada sebelumnya!
        val candNum = aiNextCandidateNumber++
        val candId = "cand_${System.currentTimeMillis()}_$candNum"
        val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val modeShort = if (snapMode == AiWorkMode.REPLACE_SCENE) "Replace" else "Sisip"
        val durShort = if (plan.count == 1) {
            "1×${Fmt.one(plan.targetTotalSec)}s"
        } else {
            "${plan.count}seg (${Fmt.one(plan.targetTotalSec)}s)"
        }
        val summaryLbl = "$modeShort · $durShort · ${aiSpaceOption.shortName}"

        aiGeneratedSegments = emptyList()
        aiPreviewResultIndex = 0
        aiWorkflowWaitSec = 0
        aiCurrentVideoNum = 1
        aiTotalVideosInRun = plan.count
        aiCurrentVideoSec = 0
        aiRunPhase = AiRunPhase.UPLOADING
        aiRunStatusDetail = "Menyiapkan Kandidat #$candNum (${plan.count} video)…"
        aiActiveTab = AiTab.STATUS

        val useChain = plan.count >= 2 &&
            (snapMode == AiWorkMode.REPLACE_SCENE ||
                aiInsertPreset == AiInsertPreset.FULL_10 ||
                aiMultiStrategy == AiMultiStrategy.CHAIN)
        val cfgSnap = aiRunnerConfig
        val spaceSnap = aiSpaceOption
        val safeSnap = aiSafeMode
        val safetySnap = aiSafetyChecker
        val promptsSnap = (1..plan.count).map { promptForSegmentOneBased(it) }

        fun publishCandidateProgress(segsNow: List<AiGeneratedSegment>) {
            aiGeneratedSegments = segsNow
            if (segsNow.isNotEmpty()) {
                upsertCandidateById(
                    AiCandidateBatch(
                        id = candId,
                        candidateNumber = candNum,
                        createdAtLabel = timeLabel,
                        workMode = snapMode,
                        genMode = snapGenMode,
                        spaceLabel = spaceSnap.shortName,
                        summaryLabel = summaryLbl,
                        plan = plan,
                        snapshotInsertSeekSec = snapInsertSeekSec,
                        snapshotReplaceSegIdx = snapReplaceSegIdx,
                        segments = segsNow
                    )
                )
            }
        }

        aiRunJob?.cancel()
        aiRunJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val collected = ArrayList<AiGeneratedSegment>()
            try {
                // Pastikan firebase_worker.py di repo GitHub sudah v3 (mendukung Mode Tunggal 10s & Berantai 1-Workflow)
                val (workerSt, workerMsg) = WanStudioClient.ensureLatestWorkerOnGitHub(
                    cfg = cfgSnap,
                    attemptAutoPush = true
                )
                aiWorkerSyncStatus = workerSt
                aiWorkerSyncMessage = workerMsg
                val remoteWorkerSupportsV3 =
                    workerSt == WorkerSyncStatus.UP_TO_DATE || workerSt == WorkerSyncStatus.AUTO_UPDATED

                if (useChain && !remoteWorkerSupportsV3) {
                    // Fallback Cerdas bila token GitHub pengguna read-only (belum update firebase_worker.py ke v3):
                    // Jalankan berantai dengan mengekstrak langsung frame PALING AKHIR dari MP4 hasil AI #1 di Android
                    // untuk dijadikan input_image AI #2 — menjamin 100% TIDAK memakai frame dari scene asli!
                    var rollingFirstBmp: Bitmap = initialFirstBmp
                    for (idx in plan.segDurs.indices) {
                        val segNum = idx + 1
                        val isLastSeg = idx == plan.segDurs.lastIndex
                        val segLastBmp = if (isLastSeg) finalTargetLastBmp else null
                        val singleReq = SingleTaskRequest(
                            taskKey = "task-1",
                            order = 1,
                            space = spaceSnap,
                            prompt = promptsSnap[idx],
                            durationSec = plan.segDurs[idx],
                            safeMode = safeSnap,
                            enableSafetyChecker = safetySnap,
                            firstBmp = rollingFirstBmp,
                            lastBmp = segLastBmp,
                            chainFromPrev = false
                        )
                        val outs = WanStudioClient.executeProjectBatch(
                            context = ctx,
                            cfg = cfgSnap,
                            tasks = listOf(singleReq),
                            videoIndexOffset = idx,
                            overallTotalVideos = plan.count
                        ) { tick ->
                            launch(Dispatchers.Main) { applyProgressTick(tick) }
                        }
                        val dl = outs.first()
                        val segObj = dl.toGeneratedSegment(segNum)
                        collected.add(segObj)
                        publishCandidateProgress(collected.toList())

                        if (!isLastSeg) {
                            val nextStart = withContext(Dispatchers.IO) {
                                WanStudioClient.extractLastFrameOfMp4(ctx, dl.file, dl.info)
                            }
                            if (nextStart != null) {
                                rollingFirstBmp = nextStart
                            }
                        }
                    }
                } else {
                    // 1 Workflow Tunggal (maks 10.0 dtk, atau maks 10.5 dtk khusus 3 × 3.5 dtk):
                    // - Mode Tunggal: 1 video utuh langsung (mis. 10.0 dtk langsung 1 MP4)
                    // - Mode Berantai v3: begitu Video #1 selesai, firebase_worker.py mengekstrak frame paling akhir
                    //   hasil AI #1 sebagai input Video #2 tepat di dalam workflow yang sama!
                    val batchReqs = plan.segDurs.mapIndexed { idx, dur ->
                        val isLastSeg = idx == plan.segDurs.lastIndex
                        val segLastBmp = if (!useChain) {
                            finalTargetLastBmp
                        } else if (isLastSeg) {
                            finalTargetLastBmp
                        } else {
                            null
                        }
                        SingleTaskRequest(
                            taskKey = "task-${idx + 1}",
                            order = idx + 1,
                            space = spaceSnap,
                            prompt = promptsSnap[idx],
                            durationSec = dur,
                            safeMode = safeSnap,
                            enableSafetyChecker = safetySnap,
                            firstBmp = initialFirstBmp,
                            lastBmp = segLastBmp,
                            chainFromPrev = useChain && idx > 0
                        )
                    }

                    val outs = WanStudioClient.executeProjectBatch(
                        context = ctx,
                        cfg = cfgSnap,
                        tasks = batchReqs,
                        videoIndexOffset = 0,
                        overallTotalVideos = plan.count,
                        onSegmentReady = { dl ->
                            val segObj = dl.toGeneratedSegment(dl.order)
                            launch(Dispatchers.Main) {
                                if (collected.none { it.segNumber == segObj.segNumber }) {
                                    collected.add(segObj)
                                    collected.sortBy { it.segNumber }
                                    publishCandidateProgress(collected.toList())
                                }
                            }
                        }
                    ) { tick ->
                        launch(Dispatchers.Main) { applyProgressTick(tick) }
                    }

                    for (dl in outs) {
                        if (collected.none { it.segNumber == dl.order }) {
                            collected.add(dl.toGeneratedSegment(dl.order))
                        }
                    }
                    collected.sortBy { it.segNumber }
                }

                publishCandidateProgress(collected.toList())
                aiRunPhase = AiRunPhase.COMPLETED
                aiRunStatusDetail = "Kandidat #$candNum selesai! (${aiCandidates.size} kandidat tersedia — pilih yang terbaik atau generate kandidat lagi)."
                toast("Kandidat #$candNum selesai dibuat!")
            } catch (ce: CancellationException) {
                aiRunPhase = AiRunPhase.IDLE
                aiRunStatusDetail = "Proses AI dibatalkan oleh pengguna."
                if (aiCandidates.isNotEmpty() && collected.isEmpty()) {
                    selectAiCandidate(aiActiveCandidateIdx.coerceIn(0, aiCandidates.lastIndex))
                }
                toast("Generasi AI dibatalkan")
            } catch (wte: WorkflowWaitTimeoutException) {
                aiWorkflowWaitSec = AiPlanner.WORKFLOW_WAIT_TIMEOUT_SEC
                aiRunPhase = AiRunPhase.WORKFLOW_TIMEOUT
                aiRunStatusDetail = wte.message ?: "Timeout menunggu workflow (3 menit)."
                if (collected.isNotEmpty()) {
                    populateRemainingFailedSlots(plan, promptsSnap, initialFirstBmp, finalTargetLastBmp, collected, wte.message)
                    publishCandidateProgress(collected.toList())
                } else if (aiCandidates.isNotEmpty()) {
                    selectAiCandidate(aiActiveCandidateIdx.coerceIn(0, aiCandidates.lastIndex))
                }
                toast("Timeout 3 menit menunggu workflow aktif")
            } catch (vte: VideoGenerationTimeoutException) {
                for (dl in vte.partialDownloaded) {
                    if (collected.none { it.segNumber == dl.order }) {
                        collected.add(dl.toGeneratedSegment(dl.order))
                    }
                }
                collected.sortBy { it.segNumber }
                aiCurrentVideoNum = vte.timedOutVideoNumber
                aiCurrentVideoSec = AiPlanner.VIDEO_GEN_TIMEOUT_SEC
                aiRunPhase = AiRunPhase.VIDEO_TIMEOUT
                aiRunStatusDetail = vte.message ?: "Timeout 3 menit pembuatan video."
                populateRemainingFailedSlots(
                    plan = plan,
                    prompts = promptsSnap,
                    fallbackFirst = initialFirstBmp,
                    fallbackLast = finalTargetLastBmp,
                    collected = collected,
                    errMsg = vte.message ?: "Melewati batas 3 menit/video"
                )
                publishCandidateProgress(collected.toList())
                toast("Timeout 3 menit pada Video #${vte.timedOutVideoNumber}")
            } catch (e: Exception) {
                aiRunPhase = AiRunPhase.FAILED
                val extraWorkerHint = if (!aiGenMode.equals(AiGenerationMode.MULTI_SEGMENT) &&
                    plan.targetTotalSec !in listOf(3.5, 5.0, 7.0) &&
                    aiWorkerSyncStatus == WorkerSyncStatus.NEEDS_MANUAL_COPY
                ) {
                    " (Pastikan juga firebase_worker.py di repo GitHub sudah diperbarui ke v3 lewat Tab Runner untuk durasi ${Fmt.one(plan.targetTotalSec)} dtk tunggal)."
                } else {
                    ""
                }
                aiRunStatusDetail = (e.message ?: "Generasi AI gagal.") + extraWorkerHint
                if (collected.isNotEmpty()) {
                    populateRemainingFailedSlots(plan, promptsSnap, initialFirstBmp, finalTargetLastBmp, collected, e.message)
                    publishCandidateProgress(collected.toList())
                } else if (aiCandidates.isNotEmpty()) {
                    selectAiCandidate(aiActiveCandidateIdx.coerceIn(0, aiCandidates.lastIndex))
                }
                toast("Generasi AI gagal")
            }
        }
    }

    private fun DownloadedAiVideo.toGeneratedSegment(segNumber: Int): AiGeneratedSegment =
        AiGeneratedSegment(
            id = "aiseg_${System.currentTimeMillis()}_$segNumber",
            segNumber = segNumber,
            requestedDurSec = requestedDurSec,
            actualDurSec = actualDurSec,
            mp4File = file,
            info = info,
            previewBmp = previewBmp ?: firstBmp,
            seed = seed,
            promptUsed = promptUsed,
            inputFirstBmp = firstBmp,
            inputLastBmp = lastBmp,
            selected = true,
            failed = false,
            errorMessage = null
        )

    private fun populateRemainingFailedSlots(
        plan: AiExecutionPlan,
        prompts: List<String>,
        fallbackFirst: Bitmap,
        fallbackLast: Bitmap?,
        collected: MutableList<AiGeneratedSegment>,
        errMsg: String?
    ) {
        val ctx = getApplication<Application>()
        while (collected.size < plan.count) {
            val nextIdx = collected.size
            val prevSeg = collected.lastOrNull { !it.failed && it.mp4File != null && it.info != null }
            val nextFirst = if (prevSeg?.mp4File != null && prevSeg.info != null) {
                WanStudioClient.extractLastFrameOfMp4(ctx, prevSeg.mp4File, prevSeg.info) ?: fallbackFirst
            } else {
                fallbackFirst
            }
            collected.add(
                AiGeneratedSegment(
                    id = "aiseg_err_${System.currentTimeMillis()}_${nextIdx + 1}",
                    segNumber = nextIdx + 1,
                    requestedDurSec = plan.segDurs[nextIdx],
                    actualDurSec = plan.segDurs[nextIdx],
                    mp4File = null,
                    info = null,
                    previewBmp = nextFirst,
                    seed = "-",
                    promptUsed = prompts.getOrElse(nextIdx) { aiPrompt },
                    inputFirstBmp = nextFirst,
                    inputLastBmp = if (nextIdx == plan.segDurs.lastIndex) fallbackLast else null,
                    selected = false,
                    failed = true,
                    errorMessage = errMsg ?: "Gagal / Timeout — ketuk ↻ untuk mengulang segmen ini"
                )
            )
        }
        aiGeneratedSegments = collected.toList()
    }

    private fun applyProgressTick(tick: com.pribadi.editorvideo.ai.LiveProgressTick) {
        aiRunPhase = tick.phase
        aiWorkflowWaitSec = tick.workflowWaitSec
        aiCurrentVideoNum = tick.currentVideoNumber
        aiTotalVideosInRun = tick.totalVideos
        aiCurrentVideoSec = tick.currentVideoSec
        aiRunStatusDetail = tick.statusDetail
    }

    fun cancelAiGeneration() {
        aiRunJob?.cancel()
        aiRunPhase = AiRunPhase.IDLE
        aiRunStatusDetail = "Dibatalkan."
    }

    /** Regenerate hanya 1 segmen tertentu (hemat waktu & kuota ZeroGPU bila segmen lain sudah bagus). */
    fun regenerateSingleAiSegment(index: Int) {
        if (aiIsRunning) {
            toast("Tunggu proses saat ini selesai")
            return
        }
        val target = aiGeneratedSegments.getOrNull(index) ?: return
        val firstBmp = target.inputFirstBmp ?: aiSeekBitmap ?: grabLivePreviewBitmap()
        if (firstBmp == null || firstBmp.isRecycled) {
            toast("Frame referensi segmen tidak tersedia")
            return
        }
        if (!aiRunnerConfig.isComplete) {
            aiActiveTab = AiTab.RUNNER
            toast("Lengkapi pengaturan koneksi terlebih dahulu")
            return
        }

        val cfgSnap = aiRunnerConfig
        val spaceSnap = aiSpaceOption
        val safeSnap = aiSafeMode
        val safetySnap = aiSafetyChecker
        val promptUse = target.promptUsed.ifBlank { promptForSegmentOneBased(target.segNumber) }

        aiWorkflowWaitSec = 0
        aiCurrentVideoNum = target.segNumber
        aiTotalVideosInRun = aiGeneratedSegments.size
        aiCurrentVideoSec = 0
        aiRunPhase = AiRunPhase.UPLOADING
        aiRunStatusDetail = "Mengulang Segmen #${target.segNumber}…"

        aiRunJob?.cancel()
        aiRunJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            try {
                val req = SingleTaskRequest(
                    taskKey = "task-1",
                    order = 1,
                    space = spaceSnap,
                    prompt = promptUse,
                    durationSec = target.requestedDurSec,
                    safeMode = safeSnap,
                    enableSafetyChecker = safetySnap,
                    firstBmp = firstBmp,
                    lastBmp = target.inputLastBmp
                )
                val outs = WanStudioClient.executeProjectBatch(
                    context = ctx,
                    cfg = cfgSnap,
                    tasks = listOf(req),
                    videoIndexOffset = target.segNumber - 1,
                    overallTotalVideos = aiGeneratedSegments.size
                ) { tick ->
                    launch(Dispatchers.Main) { applyProgressTick(tick) }
                }
                val fresh = outs.first().toGeneratedSegment(target.segNumber)
                val updated = aiGeneratedSegments.toMutableList()
                if (index in updated.indices) {
                    updated[index] = fresh
                }
                aiGeneratedSegments = updated
                syncActiveCandidateInPool(updated)
                aiPreviewResultIndex = index
                aiRunPhase = AiRunPhase.COMPLETED
                aiRunStatusDetail = "Segmen #${target.segNumber} berhasil dibuat ulang!"
                toast("Segmen #${target.segNumber} diperbarui ✓")
            } catch (ce: CancellationException) {
                aiRunPhase = AiRunPhase.IDLE
                aiRunStatusDetail = "Regenerate dibatalkan."
            } catch (wte: WorkflowWaitTimeoutException) {
                aiWorkflowWaitSec = AiPlanner.WORKFLOW_WAIT_TIMEOUT_SEC
                aiRunPhase = AiRunPhase.WORKFLOW_TIMEOUT
                aiRunStatusDetail = wte.message ?: "Timeout menunggu workflow (3 menit)."
                toast("Timeout 3 menit menunggu workflow")
            } catch (vte: VideoGenerationTimeoutException) {
                aiCurrentVideoSec = AiPlanner.VIDEO_GEN_TIMEOUT_SEC
                aiRunPhase = AiRunPhase.VIDEO_TIMEOUT
                aiRunStatusDetail = vte.message ?: "Timeout 3 menit pembuatan video."
                toast("Timeout 3 menit pada Segmen #${target.segNumber}")
            } catch (e: Exception) {
                aiRunPhase = AiRunPhase.FAILED
                aiRunStatusDetail = e.message ?: "Gagal mengulang segmen."
                toast("Gagal mengulang Segmen #${target.segNumber}")
            }
        }
    }

    fun toggleSelectAiResult(index: Int) {
        val cur = aiGeneratedSegments.getOrNull(index) ?: return
        if (cur.failed || cur.mp4File == null) return
        val updated = aiGeneratedSegments.toMutableList()
        updated[index] = cur.copy(selected = !cur.selected)
        aiGeneratedSegments = updated
        syncActiveCandidateInPool(updated)
    }

    fun moveAiResult(index: Int, delta: Int) {
        val next = index + delta
        if (index !in aiGeneratedSegments.indices || next !in aiGeneratedSegments.indices) return
        val updated = aiGeneratedSegments.toMutableList()
        val tmp = updated[index]
        updated[index] = updated[next]
        updated[next] = tmp
        aiGeneratedSegments = updated
        syncActiveCandidateInPool(updated)
        aiPreviewResultIndex = next
    }

    /** Menyimpan seluruh hasil MP4 terpilih ke folder Galeri (Movies/EditorVideo). */
    fun saveSelectedAiResultsToGallery() {
        val valid = aiGeneratedSegments.filter { it.selected && !it.failed && it.mp4File != null }
        if (valid.isEmpty()) {
            toast("Pilih minimal 1 hasil video AI yang selesai")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            var savedCount = 0
            for ((idx, sg) in valid.withIndex()) {
                val f = sg.mp4File ?: continue
                try {
                    val name = if (valid.size == 1) {
                        "AI_Wan_${stamp}.mp4"
                    } else {
                        "AI_Wan_${stamp}_${idx + 1}.mp4"
                    }
                    MediaStoreSaver.save(ctx, f, name)
                    savedCount++
                } catch (_: Exception) {
                }
            }
            withContext(Dispatchers.Main) {
                toast(
                    if (savedCount > 0) "$savedCount video AI disimpan ke Movies/EditorVideo"
                    else "Gagal menyimpan ke Galeri"
                )
            }
        }
    }

    /** Mengembalikan (Uri sumber asli, Seg scene target, VideoInfo) untuk pratinjau kiri-kanan Replace Scene. */
    fun resolveSnapshotReplaceTarget(): Triple<Uri, Seg, VideoInfo>? {
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) return null
        val idx = aiSnapshotReplaceSegIdx.coerceIn(0, core.segs.lastIndex)
        val g = core.segs[idx]
        val ms = sources.getOrElse(g.src) { sources.first() }
        return Triple(ms.uri, g, ms.info)
    }

    /**
     * Memasukkan hasil video AI terpilih ke Timeline (mendukung Mode Sisipkan & Mode Replace Scene,
     * baik sebagai Klip Terpisah Berurutan maupun Gabung Jadi 1 Klip Utuh, serta opsi Video Saja / Video+Audio).
     */
    fun applyAiResultsToTimeline(joinStyle: AiTimelineJoinStyle) {
        if (aiMergingClips) return
        val chosen = aiGeneratedSegments.filter {
            it.selected && !it.failed && it.mp4File != null && it.info != null
        }
        if (chosen.isEmpty()) {
            toast("Pilih minimal 1 hasil video AI terlebih dahulu")
            return
        }

        pause()
        val mode = aiSnapshotWorkMode
        val plan = aiSnapshotPlan ?: currentAiExecutionPlan()
        val audioModeSnap = aiReplaceAudioMode

        // Hitung durasi pakai per segmen agar pada Mode Replace Scene totalnya presisi sesuai slider/scene!
        val targetCap = if (mode == AiWorkMode.REPLACE_SCENE) plan.targetTotalSec else Double.MAX_VALUE
        var remainingAllowance = targetCap
        val clippedChosen = ArrayList<Triple<File, VideoInfo, Double>>()
        for (sg in chosen) {
            if (remainingAllowance < Rules.MIN) break
            val useSec = min(sg.actualDurSec, remainingAllowance)
            clippedChosen.add(Triple(sg.mp4File!!, sg.info!!, max(Rules.MIN, useSec)))
            remainingAllowance -= useSec
        }
        if (clippedChosen.isEmpty()) {
            toast("Durasi hasil tidak mencukupi")
            return
        }

        aiMergingClips = true
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            try {
                // Pastikan tag "AI" terdaftar di daftar tag aplikasi agar siap dipakai Filter Grid & Ekspor By Tag
                if (availableTags.none { it.equals("AI", ignoreCase = true) }) {
                    val nextTags = availableTags + "AI"
                    availableTags = nextTags
                    val arr = JSONArray()
                    nextTags.forEach { arr.put(it) }
                    prefs.edit().putString(KEY_CUSTOM_TAGS, arr.toString()).apply()
                }

                val outDir = File(ctx.filesDir, "ai_videos")
                if (!outDir.exists()) outDir.mkdirs()

                val baseFiles: List<Triple<File, VideoInfo, Double>> =
                    if (joinStyle == AiTimelineJoinStyle.MERGED && clippedChosen.size >= 2) {
                        val mergedFile = File(outDir, "ai_merged_${System.currentTimeMillis()}.mp4")
                        val ok = WanStudioClient.mergeSegmentsToSingleMp4(ctx, clippedChosen, mergedFile)
                        val mergedInfo = if (ok) {
                            withContext(Dispatchers.IO) { VideoProbe.probe(ctx, Uri.fromFile(mergedFile)) }
                        } else {
                            null
                        }
                        if (mergedInfo != null && mergedInfo.durationMs > 0L) {
                            val dur = max(Rules.MIN, mergedInfo.durationMs / 1000.0)
                            listOf(Triple(mergedFile, mergedInfo, dur))
                        } else {
                            clippedChosen
                        }
                    } else {
                        clippedChosen
                    }

                // Bila Mode Replace Scene & opsi "Video Saja (Audio Asli)", pasangkan trek audio asli scene!
                val finalFilesToRegister: List<Triple<File, VideoInfo, Double>> =
                    if (mode == AiWorkMode.REPLACE_SCENE &&
                        audioModeSnap == AiReplaceAudioMode.VIDEO_ONLY &&
                        hasVideo &&
                        sources.isNotEmpty() &&
                        core.segs.isNotEmpty()
                    ) {
                        val segIdx = aiSnapshotReplaceSegIdx.coerceIn(0, core.segs.lastIndex)
                        val origSeg = core.segs[segIdx]
                        val origSrc = sources.getOrElse(origSeg.src) { sources.first() }
                        if (!origSeg.fz) {
                            var consumedTimelineSec = 0.0
                            val withOrigAudio = ArrayList<Triple<File, VideoInfo, Double>>(baseFiles.size)
                            for ((pieceIdx, triple) in baseFiles.withIndex()) {
                                val (vidFile, vidInfo, useDur) = triple
                                val origSliceStartSec = clampD(
                                    origSeg.a + consumedTimelineSec * origSeg.sp,
                                    origSeg.a,
                                    max(origSeg.a, origSeg.b - 0.02)
                                )
                                val muxOut = File(
                                    outDir,
                                    "ai_origaud_${System.currentTimeMillis()}_${pieceIdx + 1}.mp4"
                                )
                                val resultFile = WanStudioClient.attachOriginalAudioToAiClip(
                                    context = ctx,
                                    aiVideoFile = vidFile,
                                    aiVideoInfo = vidInfo,
                                    clipDurSec = useDur,
                                    origSourceUri = origSrc.uri,
                                    origInfo = origSrc.info,
                                    origStartSec = origSliceStartSec,
                                    origSpeed = origSeg.sp,
                                    outFile = muxOut
                                )
                                val resultInfo = if (resultFile === vidFile) {
                                    vidInfo
                                } else {
                                    withContext(Dispatchers.IO) {
                                        VideoProbe.probe(ctx, Uri.fromFile(resultFile))
                                    } ?: vidInfo
                                }
                                withOrigAudio.add(Triple(resultFile, resultInfo, useDur))
                                consumedTimelineSec += useDur
                            }
                            withOrigAudio
                        } else {
                            baseFiles
                        }
                    } else {
                        baseFiles
                    }

                val firstNewSrcIdx = sources.size
                val addedDurPairs = ArrayList<Pair<Int, Double>>()
                val segSpecs = ArrayList<Triple<Int, Double, Double>>()

                // Tentukan orientasi tegak/mendatar yang diharapkan dari referensi klip/scene agar 100% bebas auto-rotate!
                val expectedPortrait: Boolean? = if (mode == AiWorkMode.REPLACE_SCENE &&
                    hasVideo &&
                    sources.isNotEmpty() &&
                    core.segs.isNotEmpty()
                ) {
                    val segIdx = aiSnapshotReplaceSegIdx.coerceIn(0, core.segs.lastIndex)
                    val origSeg = core.segs[segIdx]
                    val origInfo = sources.getOrElse(origSeg.src) { sources.first() }.info
                    val effDims = ExportMath.origDims(origInfo.dispW, origInfo.dispH, origSeg.rot)
                    effDims[1] > effDims[0]
                } else {
                    aiSeekBitmap?.takeIf { !it.isRecycled }?.let { it.height > it.width }
                        ?: chosen.firstOrNull()?.info?.let { it.dispH > it.dispW }
                }

                for ((fRaw, infRaw, useDur) in finalFilesToRegister) {
                    val (f, inf) = WanStudioClient.ensureExpectedOrientation(
                        context = ctx,
                        mp4File = fRaw,
                        probedInfo = infRaw,
                        expectedPortrait = expectedPortrait,
                        outDir = outDir
                    )
                    val newIdx = sources.size
                    val fullDur = max(Rules.MIN, inf.durationMs / 1000.0)
                    sources.add(MediaSource(Uri.fromFile(f), inf))
                    val uniq = min(150, max(10, ceil(fullDur * 3.0).toInt()))
                    sourceThumbs.add(arrayOfNulls(uniq))
                    sourceThumbReady.add(0)
                    addedDurPairs.add(newIdx to fullDur)
                    segSpecs.add(Triple(newIdx, fullDur, min(fullDur, useDur)))
                }

                core.registerAddedSourceDurs(addedDurPairs)

                val msg = if (mode == AiWorkMode.REPLACE_SCENE && hasVideo && core.segs.isNotEmpty()) {
                    val segIdx = aiSnapshotReplaceSegIdx.coerceIn(0, core.segs.lastIndex)
                    val origSceneLen = core.segs[segIdx].len
                    val keepTailFrom = if (origSceneLen > AiPlanner.ZEROGPU_MAX_REPLACE_SEC + 0.05) {
                        min(origSceneLen, plan.targetTotalSec)
                    } else {
                        null
                    }
                    core.replaceSceneWithAiSegments(
                        targetSegIdx = segIdx,
                        newSegSpecs = segSpecs,
                        keepOverflowFromSec = keepTailFrom,
                        tagName = "AI"
                    )
                } else {
                    core.insertAiSegmentsAtTime(
                        insertTimeSec = aiSnapshotInsertSeekSec,
                        newSegSpecs = segSpecs,
                        tagName = "AI"
                    )
                }

                setSeekFast(false)
                player.setMediaItems(sources.map { MediaItem.fromUri(it.uri) })
                player.prepare()
                player.pause()
                seekVideoToEdit(forceExact = true)
                refreshDerived()
                aiMergingClips = false
                aiSheet = false
                bump()
                toast(msg)
                startThumbsFrom(firstNewSrcIdx)
            } catch (e: Exception) {
                aiMergingClips = false
                toast("Gagal menerapkan video AI: " + (e.message ?: "Error"))
            }
        }
    }

    // ------------------------------------------------------------------ pemutar

    private fun seekPlayerTo(srcIdx: Int, sec: Double, tol: Double, forceExact: Boolean = false) {
        if (!hasVideo || sources.isEmpty()) return
        val validSrc = srcIdx.coerceIn(0, sources.size - 1)
        val targetMs = Math.round(max(0.0, sec) * 1000.0)
        if (forceExact) {
            player.setSeekParameters(SeekParameters.EXACT)
            player.seekTo(validSrc, targetMs)
            return
        }
        if (player.currentMediaItemIndex != validSrc) {
            player.seekTo(validSrc, targetMs)
        } else {
            val cur = player.currentPosition / 1000.0
            if (abs(cur - sec) > tol) player.seekTo(targetMs)
        }
    }

    private fun seekVideoToEdit(forceExact: Boolean = false) {
        if (!hasVideo || core.segs.isEmpty()) return
        val l = core.locate(core.t)
        curSeg = l.i
        val g = core.segs.getOrNull(l.i) ?: return
        rot = g.rot
        canMergeScene = core.canMergeFocusScene
        markerNearCurrentPlayhead = core.markerNearPlayhead(0.08) != null
        info = sources.getOrElse(g.src) { sources.first() }.info
        seekPlayerTo(g.src, l.s, 0.015, forceExact = forceExact)
    }

    private fun setSeekFast(fast: Boolean) {
        player.setSeekParameters(if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
    }

    fun refreshFrame() {
        if (hasVideo && !playing) {
            try {
                player.setSeekParameters(SeekParameters.EXACT)
                seekVideoToEdit(forceExact = true)
            } catch (e: Exception) {
                // diabaikan
            }
        }
    }

    /** Mengaktifkan/mematikan mode Loop Scene pada pratinjau (mengulang klip/scene terpilih, bukan seluruh video). */
    fun togglePreviewLoop() {
        previewLoop = !previewLoop
        prefs.edit().putBoolean(KEY_PREVIEW_LOOP, previewLoop).apply()
        bump()
        toast(if (previewLoop) "Loop Scene aktif (mengulang scene terpilih)" else "Loop Scene nonaktif")
    }

    fun togglePlay() {
        if (playing) pause() else play()
    }

    fun play() {
        if (playing) return
        if (!hasVideo || core.segs.isEmpty()) {
            toast("Impor video dulu")
            return
        }
        if (previewLoop && core.sel in core.segs.indices) {
            val selIdx = core.sel
            val selStart = core.lay.off[selIdx]
            val selEnd = selStart + core.segs[selIdx].len
            if (core.t < selStart - 0.01 || core.t >= selEnd - 0.03) {
                core.t = selStart
            }
        } else if (core.t >= core.total - 0.02) {
            core.t = 0.0
        }
        val l = core.locate(core.t)
        val g = core.segs[l.i]
        curSeg = l.i
        rot = g.rot
        info = sources.getOrElse(g.src) { sources.first() }.info
        playing = true
        setSeekFast(false)
        seekPlayerTo(g.src, l.s, 0.02, forceExact = true)
        if (g.fz) {
            player.pause()
        } else {
            player.setPlaybackSpeed(g.sp.toFloat())
            player.play()
        }
        lastNanos = System.nanoTime()
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallback(frameCallback)
        bump()
    }

    fun pause() {
        if (!playing) return
        playing = false
        choreographer.removeFrameCallback(frameCallback)
        if (hasVideo && core.segs.isNotEmpty()) {
            setSeekFast(false)
            player.pause()
            // Sinkronkan core.t tepat ke frame yang berhenti di ExoPlayer agar 100% sama dengan Preview
            val idx = curSeg.coerceIn(0, core.segs.size - 1)
            val g = core.segs[idx]
            if (!g.fz && player.currentMediaItemIndex == g.src) {
                val s = player.currentPosition / 1000.0
                val clampedS = clampD(s, g.a, g.b)
                core.t = core.lay.off[idx] + (clampedS - g.a) / g.sp
                core.sync()
                refreshDerived()
            }
        }
        bump()
    }

    private fun endPlay() {
        playing = false
        choreographer.removeFrameCallback(frameCallback)
        if (hasVideo) {
            setSeekFast(false)
            player.pause()
        }
        core.t = min(core.t, core.total)
        core.sync()
        refreshDerived()
        bump()
    }

    private fun enterSeg(i: Int) {
        if (!hasVideo || i !in core.segs.indices) return
        curSeg = i
        val g = core.segs[i]
        rot = g.rot
        info = sources.getOrElse(g.src) { sources.first() }.info
        if (g.fz) {
            setSeekFast(false)
            player.pause()
            seekPlayerTo(g.src, g.a, 0.02, forceExact = true)
            return
        }
        setSeekFast(false)
        player.setPlaybackSpeed(g.sp.toFloat())
        seekPlayerTo(g.src, g.a, 0.05, forceExact = true)
        player.play()
    }

    private fun tick(ts: Long) {
        if (!playing) return
        val segs = core.segs
        if (!hasVideo || segs.isEmpty()) {
            endPlay()
            return
        }
        var dt = (ts - lastNanos) / 1e9
        lastNanos = ts
        if (dt < 0.0) dt = 0.0
        if (dt > 0.1) dt = 0.1
        if (curSeg >= segs.size) curSeg = segs.size - 1
        val g = segs[curSeg]
        var adv = false
        if (g.fz) {
            core.t = core.t + dt
            if (core.t >= core.lay.off[curSeg] + g.len - 1e-6) adv = true
        } else {
            if (player.currentMediaItemIndex != g.src) {
                seekPlayerTo(g.src, g.a, 0.02, forceExact = true)
            }
            val s = player.currentPosition / 1000.0
            if (s >= g.b - 0.03 || player.playbackState == Player.STATE_ENDED) {
                adv = true
            } else {
                core.t = core.lay.off[curSeg] + max(0.0, s - g.a) / g.sp
            }
        }
        if (adv) {
            if (previewLoop) {
                // Loop Scene: ulangi scene/klip yang sedang diputar (curSeg), bukan lanjut ke seluruh video
                enterSeg(curSeg)
                core.t = core.lay.off[curSeg]
            } else if (curSeg < segs.size - 1) {
                enterSeg(curSeg + 1)
                core.t = core.lay.off[curSeg]
            } else {
                core.t = core.total
                endPlay()
                return
            }
        }
        core.sync()
        refreshDerived()
        bump()
        choreographer.postFrameCallback(frameCallback)
    }

    // ------------------------------------------------------------------ timeline: scrub, ketuk, zoom, trim

    fun scrubBegin() {
        if (!hasVideo || core.segs.isEmpty()) return
        pause()
        setSeekFast(true)
    }

    fun scrubBy(dtSeconds: Double) {
        if (!hasVideo || core.segs.isEmpty()) return
        core.setTime(core.t + dtSeconds)
        seekVideoToEdit(forceExact = false)
        refreshDerived()
        bump()
    }

    fun scrubEnd() {
        if (!hasVideo || core.segs.isEmpty()) return
        setSeekFast(false)
        // Wajib forceExact = true supaya setelah CLOSEST_SYNC selesai, ExoPlayer me-render frame EXACT!
        seekVideoToEdit(forceExact = true)
        refreshDerived()
        bump()
    }

    fun tapAt(tt: Double) {
        if (!hasVideo || core.segs.isEmpty()) return
        if (tt < 0.0 || tt > core.total) return
        pause()
        setSeekFast(false)
        core.setTime(tt)
        seekVideoToEdit(forceExact = true)
        refreshDerived()
        bump()
    }

    /**
     * Fitur Seek Auto Seleksi pada Timeline:
     *  - Bila ada beberapa klip dan seek sedang berada di klip ke-[curIdx]:
     *    • Mengklik klip sesudahnya (targetIdx > curIdx) -> posisi seek melompat ke FRAME AWAL klip target
     *      sekaligus meng-auto-seleksi klip target.
     *    • Mengklik klip sebelumnya (targetIdx < curIdx) -> posisi seek melompat ke AKHIR FRAME klip target
     *      sekaligus meng-auto-seleksi klip target.
     *  - Mengklik di dalam klip yang sudah aktif saat ini (targetIdx == curIdx) atau di area ruler
     *    akan memindahkan posisi seek langsung ke waktu ketukan [tt].
     */
    fun tapTimelineAt(tt: Double, onClipArea: Boolean) {
        if (!hasVideo || core.segs.isEmpty()) return
        if (tt < 0.0 || tt > core.total) return
        pause()
        setSeekFast(false)

        val segs = core.segs
        val curIdx = core.sel.coerceIn(0, segs.size - 1)
        val targetIdx = core.locate(tt).i

        if (onClipArea && segs.size >= 2 && targetIdx != curIdx) {
            if (targetIdx > curIdx) {
                // Klip sesudahnya -> pindah ke frame awal dari klip target
                val startOfTarget = core.lay.off[targetIdx]
                core.sel = targetIdx
                core.setTime(startOfTarget)
            } else {
                // Klip sebelumnya -> pindah ke akhir frame dari klip target (tetap di dalam klip target)
                val g = segs[targetIdx]
                val fps = (sources.getOrElse(g.src) { sources.first() }.info.fps.takeIf { it > 1f } ?: 30f).toDouble()
                val oneFrameTimeline = (1.0 / fps) / max(0.1, g.sp)
                val inset = min(oneFrameTimeline * 0.85, g.len * 0.25).coerceAtLeast(0.002)
                val endOfTarget = core.lay.off[targetIdx] + max(0.0, g.len - inset)
                core.sel = targetIdx
                core.setTime(endOfTarget)
            }
        } else {
            core.setTime(tt)
        }

        seekVideoToEdit(forceExact = true)
        refreshDerived()
        bump()
    }

    /** Memilih potongan ke-[segIdx] dari Mode Grid, melompat ke frame pertama, dan langsung memutarnya (autoplay). */
    fun selectSegmentFromGrid(segIdx: Int, autoPlay: Boolean = true) {
        if (!hasVideo || segIdx !in core.segs.indices) return
        val startT = core.lay.off[segIdx]
        pause()
        setSeekFast(false)
        core.sel = segIdx
        core.setTime(startT + 0.001)
        seekVideoToEdit(forceExact = true)
        refreshDerived()
        if (autoPlay && !core.segs[segIdx].fz) {
            play()
        } else {
            bump()
        }
    }

    /** Memilih bagian waktu pada Mode Grid dan langsung memutar mulai dari frame pertamanya. */
    fun selectSliceFromGrid(startT: Double) {
        if (!hasVideo || core.segs.isEmpty()) return
        val clampedT = clampD(startT + 0.001, 0.0, core.total)
        pause()
        setSeekFast(false)
        core.setTime(clampedT)
        seekVideoToEdit(forceExact = true)
        refreshDerived()
        play()
    }

    fun setZoom(p: Double) {
        val np = clampD(p, Rules.PPS_MIN, Rules.PPS_MAX)
        if (np == pps) return
        pps = np
        zoomTag = "Zoom " + Fmt.one(np / Rules.PPS0) + "x"
        zoomJob?.cancel()
        zoomJob = viewModelScope.launch {
            delay(800)
            zoomTag = null
        }
        bump()
    }

    fun pinchBegin() {
        pause()
    }

    fun updatePreviewTransform(zoomChange: Float, panXChange: Float, panYChange: Float) {
        val newZoom = (previewZoom * zoomChange).coerceIn(1f, 4f)
        previewZoom = newZoom
        if (newZoom <= 1.01f) {
            previewPanX = 0f
            previewPanY = 0f
        } else {
            previewPanX += panXChange
            previewPanY += panYChange
        }
    }

    fun resetPreviewZoom() {
        previewZoom = 1f
        previewPanX = 0f
        previewPanY = 0f
    }

    fun trimBegin(side: Int) {
        if (!hasVideo || core.segs.isEmpty()) return
        pause()
        setSeekFast(true)
        core.trimBegin(side)
        refreshDerived()
        bump()
    }

    fun trimMove(dxSeconds: Double) {
        if (!hasVideo || core.segs.isEmpty()) return
        core.trimMove(dxSeconds)
        val g = core.trimSeg
        if (g != null && !g.fz) {
            val targetSec = if (core.trimmingSide == 1) g.a else max(g.b - 0.05, g.a)
            seekPlayerTo(g.src, targetSec, 0.02, forceExact = false)
        }
        bump()
    }

    fun trimCancel() {
        core.trimCancel()
        setSeekFast(false)
        refreshDerived()
        bump()
    }

    fun trimEnd() {
        core.trimEnd()
        setSeekFast(false)
        seekVideoToEdit(forceExact = true)
        refreshDerived()
        bump()
    }

    // ------------------------------------------------------------------ riwayat

    fun undo() {
        if (core.undo()) afterHistory()
    }

    fun redo() {
        if (core.redo()) afterHistory()
    }

    private fun afterHistory() {
        refreshDerived()
        setSeekFast(false)
        seekVideoToEdit(forceExact = true)
        bump()
    }

    // ------------------------------------------------------------------ alat

    fun onTool(tool: Tool) {
        when (tool) {
            Tool.SPLIT -> {
                val msg = core.split()
                refreshDerived()
                bump()
                if (msg != null) toast(msg)
            }

            Tool.MERGE -> {
                val msg = core.mergeFocusScene()
                refreshDerived()
                setSeekFast(false)
                seekVideoToEdit(forceExact = true)
                bump()
                toast(msg)
            }

            Tool.MARKER -> {
                toggleMarkerAtPlayhead()
            }

            Tool.SCENE_DETECT -> {
                openSceneDetector()
            }

            Tool.AI -> {
                openAiPanel()
            }

            Tool.SPEED -> {
                if (!hasVideo || core.segs.isEmpty()) {
                    toast("Impor video dulu")
                    return
                }
                refreshDerived()
                speedSheet = true
            }

            Tool.TEXT -> {
                pause()
                textSheet = true
            }

            Tool.REVERSE -> {
                openReverseSheet()
            }

            Tool.DUP -> {
                val msg = core.duplicate()
                refreshDerived()
                bump()
                if (msg != null) toast(msg)
            }

            Tool.DEL -> {
                val msg = core.delete()
                refreshDerived()
                setSeekFast(false)
                seekVideoToEdit(forceExact = true)
                bump()
                if (msg != null) toast(msg)
            }

            Tool.ROTATE -> {
                val msg = core.rotate()
                refreshDerived()
                bump()
                if (msg != null) toast(msg)
            }

            Tool.MIRROR -> {
                val msg = core.mirror()
                refreshDerived()
                bump()
                toast(msg)
            }

            Tool.FREEZE -> {
                val msg = core.freezeFrame()
                refreshDerived()
                setSeekFast(false)
                seekVideoToEdit(forceExact = true)
                bump()
                if (msg != null) toast(msg)
            }
        }
    }

    // ------------------------------------------------------------------ deteksi scene

    fun openSceneDetector() {
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) {
            toast("Impor video dulu untuk deteksi scene")
            return
        }
        val segIdx = core.sel.coerceIn(0, core.segs.size - 1)
        val g = core.segs[segIdx]
        if (g.fz) {
            toast("Potongan beku tidak bisa dideteksi scene-nya")
            return
        }
        if (g.b - g.a < Rules.MIN * 2) {
            toast("Durasi potongan terlalu pendek untuk deteksi scene")
            return
        }
        pause()
        sceneDetectSheet = true

        val cached = currentScanResult
        if (cached != null &&
            cached.srcIndex == g.src &&
            abs(cached.startSec - g.a) < 0.01 &&
            abs(cached.endSec - g.b) < 0.01 &&
            cached.samples.isNotEmpty()
        ) {
            sceneScanning = false
            detectedScenes = SceneDetector.buildScenes(
                scan = cached,
                sensitivity = sceneSensitivity,
                minSceneDurSec = sceneMinDur,
                maxSceneDurSec = if (sceneMaxDurEnabled) sceneMaxDur else null,
                markerMode = sceneMarkerMode,
                manualMarkerSrcSecs = core.markerCutSecsInSegment(cached.segIndex)
            )
            return
        }

        startSceneScan(segIdx, g, attempt = 0)
    }

    fun selectSceneMarkerMode(mode: SceneMarkerMode) {
        sceneMarkerMode = mode
        rebuildDetectedScenesFromCache()
    }

    private fun rebuildDetectedScenesFromCache() {
        val scan = currentScanResult ?: return
        detectedScenes = SceneDetector.buildScenes(
            scan = scan,
            sensitivity = sceneSensitivity,
            minSceneDurSec = sceneMinDur,
            maxSceneDurSec = if (sceneMaxDurEnabled) sceneMaxDur else null,
            markerMode = sceneMarkerMode,
            manualMarkerSrcSecs = core.markerCutSecsInSegment(scan.segIndex)
        )
        bump()
    }

    fun rescanScenes() {
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) return
        val segIdx = core.sel.coerceIn(0, core.segs.size - 1)
        val g = core.segs[segIdx]
        if (g.fz) return
        sceneScanCount += 1
        currentScanResult = null
        startSceneScan(segIdx, g, attempt = sceneScanCount)
    }

    private fun startSceneScan(segIdx: Int, g: Seg, attempt: Int) {
        val src = sources.getOrElse(g.src) { sources.first() }
        sceneScanJob?.cancel()
        sceneScanning = true
        sceneScanProgress = 0f
        detectedScenes = emptyList()
        sceneScanJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val scan = withContext(Dispatchers.IO) {
                SceneDetector.scanClip(
                    context = ctx,
                    srcUri = src.uri,
                    srcInfo = src.info,
                    srcIndex = g.src,
                    segIndex = segIdx,
                    startSec = g.a,
                    endSec = g.b,
                    speed = g.sp,
                    scanAttempt = attempt
                ) { p ->
                    viewModelScope.launch(Dispatchers.Main) {
                        sceneScanProgress = p
                    }
                }
            }
            currentScanResult = scan
            detectedScenes = SceneDetector.buildScenes(
                scan = scan,
                sensitivity = sceneSensitivity,
                minSceneDurSec = sceneMinDur,
                maxSceneDurSec = if (sceneMaxDurEnabled) sceneMaxDur else null,
                markerMode = sceneMarkerMode,
                manualMarkerSrcSecs = core.markerCutSecsInSegment(scan.segIndex)
            )
            sceneScanning = false
            if (attempt > 0) {
                toast("Deteksi ulang selesai: ${detectedScenes.size} scene")
            }
        }
    }

    fun updateSceneSettings(
        newSensitivity: Float = sceneSensitivity,
        newMinDur: Double = sceneMinDur,
        newMaxEnabled: Boolean = sceneMaxDurEnabled,
        newMaxDur: Double = sceneMaxDur
    ) {
        val sens = ((newSensitivity * 100f).roundToInt() / 100f).coerceIn(0.1f, 1.0f)
        val minD = ((newMinDur * 10.0).roundToInt() / 10.0).coerceIn(0.1, 5.0)
        val maxD = max(minD, ((newMaxDur * 10.0).roundToInt() / 10.0).coerceIn(0.5, 20.0))
        sceneSensitivity = sens
        sceneMinDur = minD
        sceneMaxDurEnabled = newMaxEnabled
        sceneMaxDur = maxD
        prefs.edit()
            .putFloat(KEY_SCENE_SENS, sens)
            .putFloat(KEY_SCENE_MINDUR, minD.toFloat())
            .putBoolean(KEY_SCENE_MAXDUR_ENABLED, newMaxEnabled)
            .putFloat(KEY_SCENE_MAXDUR, maxD.toFloat())
            .apply()
        rebuildDetectedScenesFromCache()
    }

    fun toggleDetectedScene(sceneIndex: Int) {
        detectedScenes = detectedScenes.map {
            if (it.index == sceneIndex) it.copy(selected = !it.selected) else it
        }
    }

    fun selectAllDetectedScenes(selectAll: Boolean) {
        detectedScenes = detectedScenes.map { it.copy(selected = selectAll) }
    }

    fun previewDetectedScene(scene: DetectedScene) {
        val scan = currentScanResult ?: return
        if (scan.segIndex !in core.segs.indices) return
        val g = core.segs[scan.segIndex]
        val offsetInSeg = (scene.startSec - g.a) / g.sp
        val timelineT = core.lay.off[scan.segIndex] + max(0.0, offsetInSeg)
        tapAt(timelineT)
    }

    fun applyDetectedScenes(onlySelected: Boolean) {
        val scan = currentScanResult ?: return
        val chosen = if (onlySelected) {
            detectedScenes.filter { it.selected }
        } else {
            detectedScenes
        }
        if (chosen.isEmpty()) {
            toast("Pilih minimal 1 scene pada grid")
            return
        }
        val ranges = chosen.map { it.startSec to it.endSec }
        val msg = core.applySceneSplits(scan.segIndex, ranges)
        sceneDetectSheet = false
        refreshDerived()
        setSeekFast(false)
        seekVideoToEdit(forceExact = true)
        bump()
        toast(msg)
    }

    // ------------------------------------------------------------------ membalikkan: Mundur & Loop

    private fun openReverseSheet() {
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) {
            toast("Impor video dulu sebelum membalikkan klip")
            return
        }
        if (reverseRendering) {
            toast("Sedang merender video…")
            return
        }
        val segIdx = core.sel.coerceIn(0, core.segs.size - 1)
        val g = core.segs[segIdx]
        if (g.fz) {
            toast("Potongan beku tidak perlu dibalik")
            return
        }
        pause()
        refreshDerived()
        reverseSheet = true
    }

    fun applyReverseMode(targetMode: Int) {
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) return
        val segIdx = core.sel.coerceIn(0, core.segs.size - 1)
        val g = core.segs[segIdx]
        if (g.fz) return

        reverseSheet = false

        if (targetMode == Seg.REV_NONE) {
            val msg = core.restoreFromReverse(segIdx)
            refreshDerived()
            setSeekFast(false)
            seekVideoToEdit(forceExact = true)
            bump()
            toast(msg)
            return
        }

        val baseSrcIdx = if (g.origSrc in sources.indices) g.origSrc else g.src
        val baseA = if (g.origSrc in sources.indices) g.origA else g.a
        val baseB = if (g.origSrc in sources.indices && g.origB > g.origA) g.origB else g.b
        val src = sources.getOrElse(baseSrcIdx) { sources.first() }
        val isLoop = targetMode == Seg.REV_LOOP

        pause()
        reverseRendering = true
        reverseRenderingLabel = if (isLoop) "Loop (Normal + Mundur)" else "Mundur"
        reverseProgress = 0f
        reverseJob?.cancel()
        reverseJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            try {
                val revDir = File(ctx.cacheDir, "reversed")
                val prefix = if (isLoop) "loop_" else "rev_"
                val outFile = File(revDir, prefix + System.currentTimeMillis() + ".mp4")
                withContext(Dispatchers.Default) {
                    ReverseVideoRenderer.renderClip(
                        context = ctx,
                        srcUri = src.uri,
                        srcInfo = src.info,
                        startSec = baseA,
                        endSec = baseB,
                        includeForwardFirst = isLoop,
                        outFile = outFile
                    ) { p ->
                        viewModelScope.launch(Dispatchers.Main) {
                            reverseProgress = p
                        }
                    }
                }

                val revUri = Uri.fromFile(outFile)
                val revInfo = withContext(Dispatchers.IO) { VideoProbe.probe(ctx, revUri) }
                if (revInfo == null || revInfo.durationMs <= 0L) {
                    reverseRendering = false
                    toast("Gagal merender video")
                    return@launch
                }

                val newSrcIdx = sources.size
                sources.add(MediaSource(revUri, revInfo))
                val revDur = revInfo.durationMs / 1000.0
                val uniq = min(150, max(10, ceil(revDur * 3.0).toInt()))
                sourceThumbs.add(arrayOfNulls(uniq))
                sourceThumbReady.add(0)

                val msg = core.applyRenderedReverse(segIdx, newSrcIdx, revDur, targetMode)
                setSeekFast(false)
                player.setMediaItems(sources.map { MediaItem.fromUri(it.uri) })
                player.prepare()
                player.pause()
                seekVideoToEdit(forceExact = true)
                refreshDerived()
                reverseRendering = false
                bump()
                toast(msg)
                startThumbsFrom(newSrcIdx)
            } catch (e: CancellationException) {
                reverseRendering = false
                toast("Proses dibatalkan")
            } catch (e: Exception) {
                reverseRendering = false
                toast("Gagal merender: " + (e.message ?: "Error"))
            }
        }
    }

    fun cancelReverseRender() {
        reverseJob?.cancel()
        reverseRendering = false
    }

    // ------------------------------------------------------------------ kecepatan

    fun setSpeed(v: Double, commit: Boolean) {
        val msg = core.setSpeed(v, commit)
        if (playing && curSeg == core.sel && hasVideo) {
            player.setPlaybackSpeed(core.selSpeed.toFloat())
        }
        refreshDerived()
        bump()
        if (msg != null) toast(msg)
    }

    fun commitSpeed() {
        core.commitIfChanged()
        refreshDerived()
    }

    fun askApplyAll() {
        if (core.segs.size < 2) {
            toast("Hanya ada satu potongan")
            return
        }
        confirmText = core.applyAllText()
    }

    fun confirmApplyAll() {
        val msg = core.applySpeedAll()
        confirmText = null
        refreshDerived()
        setSeekFast(false)
        seekVideoToEdit(forceExact = true)
        bump()
        toast(msg)
    }

    // ------------------------------------------------------------------ kustomisasi teks overlay kaya

    fun openTextEditor(whichLayer: Int) {
        pause()
        activeTextTab = if (whichLayer == 2) 1 else 0
        textSheet = true
    }

    fun updateTextConfig(whichLayer: Int, cfg: TextOverlayConfig) {
        if (whichLayer == 1) {
            style1 = cfg
        } else if (whichLayer == 2) {
            style2 = cfg
        }
        bump()
    }

    fun moveOverlayText(whichLayer: Int, dxNorm: Float, dyNorm: Float) {
        if (whichLayer == 1) {
            style1 = style1.copy(
                posX = (style1.posX + dxNorm).coerceIn(0.06f, 0.94f),
                posY = (style1.posY + dyNorm).coerceIn(0.06f, 0.94f)
            )
        } else if (whichLayer == 2) {
            style2 = style2.copy(
                posX = (style2.posX + dxNorm).coerceIn(0.06f, 0.94f),
                posY = (style2.posY + dyNorm).coerceIn(0.06f, 0.94f)
            )
        }
        bump()
    }

    fun clearAllTexts() {
        style1 = TextOverlayConfig.defaultTop()
        style2 = TextOverlayConfig.defaultBottom()
        bump()
    }

    // ------------------------------------------------------------------ preferensi & catatan

    val updateHistory: List<UpdateLogEntry> = listOf(
        UpdateLogEntry(
            version = "v1.0.19 (Terbaru)",
            date = "27/11/2026",
            title = "Kandidat AI, Mode Tunggal 10s, Fix Berantai & Rotasi AI, Tanda (Markers) + Gabung Scene Instan",
            items = listOf(
                "✓ [Pengembangan] Sistem Kandidat Hasil AI: generate berkali-kali bahkan setelah hasil sudah jadi (Kandidat #1, #2, dst.), bandingkan tiap kandidat, tandai ⭐ Terbaik, dan pilih kandidat terbaik untuk timeline.",
                "✓ [Pengembangan] Mode Tunggal AI: generate langsung 1 video utuh (mis. 10.0 dtk langsung 1 video, tidak harus dipecah 5 dtk × 2) atau beralih ke Multi-Segmen kapan saja.",
                "✓ [Bug Fixed] Mode Berantai (Chained): diperbaiki agar Segmen #2 selalu memakai frame paling akhir dari hasil AI Segmen #1 (disertai auto-sinkron firebase_worker.py v3 & fallback ekstraksi otomatis di HP).",
                "✓ [Bug Fixed] Diperbaiki bug rotasi otomatis 90° saat menerapkan hasil AI (terutama saat gabung klip & saat mempertahankan audio asli scene).",
                "✓ [Pengembangan] Fitur Tanda (Markers) pada timeline & pratinjau + integrasi langsung ke Deteksi Scene (Auto + Tanda / Split by Tanda).",
                "✓ [Pengembangan] Gabung Scene Instan Tanpa Render Ulang (Zero-Reencode): menyatukan kembali potongan scene berdampingan secara instan dengan memperluas rentang klip (mendukung Undo/Redo penuh).",
                "✓ [Pengembangan] Panel penjelasan & kontrol langsung pada tombol \"Sesuai Video Asli\" di Ekspor untuk menangani perbedaan kualitas & FPS antara video asli dan klip AI."
            )
        ),
        UpdateLogEntry(
            version = "v1.0.17",
            date = "27/11/2026",
            title = "Ekspor Campuran Asli + AI, Putar Khusus Scene Fokus & 1-Workflow Multi-Segmen AI",
            items = listOf(
                "✓ [Pengembangan] Penanganan Ekspor Campuran Video Asli + Hasil AI: deteksi otomatis perbedaan resolusi/FPS disertai pilihan Acuan Spesifikasi (Ikuti Video Utama, Kualitas Tertinggi, Terendah/Sesuai AI, atau Asli Tiap Klip) dan penyesuaian bingkai Fit Utuh / Isi Penuh (Crop).",
                "✓ [Bug Fixed] Tombol Putar (Rotate) kini hanya memutar scene/potongan yang sedang fokus (tidak memutar seluruh scene lain di timeline, Mode Grid, Collage, maupun hasil Ekspor).",
                "✓ [Pengembangan] Generasi Multi-Segmen AI dalam 1 Workflow Tunggal (maks 10.0 dtk, khusus durasi 3.5 dtk maks 10.5 dtk): begitu Video #1 berhasil, Video berikutnya langsung digenerate berurutan di dalam workflow yang sama dan tampil live di Tab Status."
            )
        ),
        UpdateLogEntry(
            version = "v1.0.15",
            date = "27/11/2026",
            title = "Pratinjau Kiri-Kanan Replace AI, Audio Asli Replace, Maks Durasi Scene, Loop Scene & Share Video",
            items = listOf(
                "✓ [Pengembangan] Pratinjau hasil AI pada Mode Replace Scene ditampilkan Kiri-Kanan berdampingan dengan video scene asli yang akan di-replace.",
                "✓ [Pengembangan] Deteksi Scene kini memiliki opsi Durasi Maksimal Scene (default OFF).",
                "✓ [Pengembangan] Mode Replace Scene mendukung opsi \"Video Saja (Pertahankan Audio Asli)\" atau \"Video + Audio AI\".",
                "✓ [Bug Fixed] Toggle Loop pada Pratinjau kini mengaktifkan Loop Scene (mengulang klip/scene terpilih), bukan mengulang seluruh video.",
                "✓ [Pengembangan] Menerima Share / Bagikan video dari aplikasi apa pun langsung menjadi input di Editor Video."
            )
        ),
        UpdateLogEntry(
            version = "v1.0.14",
            date = "27/11/2026",
            title = "Studio Video AI v2: Mode Sisipkan & Replace Scene, Multi-Hasil, Management Prompt & Runner",
            items = listOf(
                "✓ [Pengembangan] Dua Mode Kerja AI: Mode Sisipkan di titik Seek (paket durasi 3×3.5 dtk, 2×5 dtk, 7 dtk, 10 dtk) & Mode Replace Scene (input range durasi 1.0–10.0 dtk + shortcut \"Sesuai Durasi Scene\" bila durasi scene ≤10 dtk).",
                "✓ [Pengembangan] Pengatur 2 atau 3 Hasil Video AI: opsi Sambung Berantai / Variasi Take, prompt per segmen, pilih/urutkan/regenerate satuan, serta sisip berurutan atau gabung 1 klip.",
                "✓ [Pengembangan] Tab Management Prompt (simpan, kategori, favorit, eksport/import JSON & Clipboard) serta Tab Koneksi & Repo Runner Clipboards dengan watchdog timeout 3 menit menunggu workflow dan 3 menit per video."
            )
        ),
        UpdateLogEntry(
            version = "v1.0.12",
            date = "27/11/2026",
            title = "Ekspor Berdasarkan Tag, Toggle Loop Preview & Shortcut Sesuai Video Asli",
            items = listOf(
                "✓ [Pengembangan] Mode Ekspor Berdasarkan Tag (By Tag): pilih satu/beberapa Tag dengan opsi keluaran Gabung 1 Video, Pisah per Klip, atau 1 File per Tag.",
                "✓ [Pengembangan] Toggle Loop pada kontrol Pratinjau untuk mengaktifkan/mematikan pengulangan otomatis tanpa mengubah fungsi tombol Play/Pause biasa.",
                "✓ [Pengembangan] Shortcut \"Sesuai Video Asli\" di menu Ekspor untuk langsung menyamakan resolusi (Kualitas) dan FPS dengan video sumber asli."
            )
        ),
        UpdateLogEntry(
            version = "v1.0.10",
            date = "27/11/2026",
            title = "Opsi Upload Galeri / File Manager, Seek Auto Seleksi Timeline & Eksport/Import Tagging",
            items = listOf(
                "✓ [Pengembangan] Placeholder pratinjau kosong menampilkan opsi Upload dari Galeri atau File Manager.",
                "✓ [Pengembangan] Seek Auto Seleksi di Timeline (klik klip sesudahnya -> frame awal, klik klip sebelumnya -> akhir frame).",
                "✓ [Pengembangan] Eksport & Import Tagging (.json File Manager & Clipboard)."
            )
        )
    )

    private fun loadPrefsAndNotes() {
        val savedThemeId = prefs.getString(KEY_APP_THEME, AppTheme.MEDIUM_MAROON.id)
        appTheme = AppTheme.fromId(savedThemeId)
        Ev.activeTheme = appTheme

        gridColumns = prefs.getInt(KEY_GRID_COLS, 3).coerceIn(3, 9)
        previewLoop = prefs.getBoolean(KEY_PREVIEW_LOOP, false)

        // Muat koleksi Management Prompt
        val savedPromptsRaw = prefs.getString(KEY_AI_PROMPTS_JSON, null)
        if (!savedPromptsRaw.isNullOrEmpty()) {
            val parsedPrompts = AiPromptItem.parseJsonString(savedPromptsRaw)
            if (!parsedPrompts.isNullOrEmpty()) {
                aiPromptLibrary = parsedPrompts
            }
        }
        val firstPromptBody = aiPromptLibrary.firstOrNull()?.body ?: ""
        if (aiPrompt.isEmpty() && firstPromptBody.isNotEmpty()) {
            aiPrompt = firstPromptBody
        }

        // Muat konfigurasi koneksi Firebase & GitHub Runner
        val savedRunnerRaw = prefs.getString(KEY_AI_RUNNER_CFG, null)
        if (!savedRunnerRaw.isNullOrEmpty()) {
            try {
                val ro = JSONObject(savedRunnerRaw)
                aiRunnerConfig = AiRunnerConfig(
                    firebaseUrl = ro.optString("firebaseUrl", ""),
                    githubOwner = ro.optString("githubOwner", ""),
                    githubRepo = ro.optString("githubRepo", ""),
                    githubBranch = ro.optString("githubBranch", "main").ifEmpty { "main" },
                    workflowFile = ro.optString("workflowFile", "wanvideo-r3gm-test.yml")
                        .ifEmpty { "wanvideo-r3gm-test.yml" },
                    githubToken = ro.optString("githubToken", "")
                )
            } catch (_: Exception) {
            }
        }

        val savedCopied = prefs.getStringSet(KEY_AI_COPIED_CLIPS, null)
        if (savedCopied != null) {
            aiCopiedRunnerIds = savedCopied.toSet()
        }

        val savedTagsJson = prefs.getString(KEY_CUSTOM_TAGS, null)
        if (!savedTagsJson.isNullOrEmpty()) {
            try {
                val arr = JSONArray(savedTagsJson)
                val loaded = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotEmpty() && s !in loaded) loaded.add(s)
                }
                if (loaded.isNotEmpty()) availableTags = loaded
            } catch (e: Exception) {
                // diabaikan
            }
        }

        val savedTagColors = prefs.getString(KEY_TAG_COLORS_JSON, null)
        if (!savedTagColors.isNullOrEmpty()) {
            try {
                val obj = JSONObject(savedTagColors)
                val merged = LinkedHashMap(tagColorMap)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next().trim().lowercase(Locale.ROOT)
                    if (k.isNotEmpty()) {
                        merged[k] = obj.optInt(k, 0).coerceIn(0, TAG_COLOR_PALETTE.lastIndex)
                    }
                }
                tagColorMap = merged
            } catch (e: Exception) {
                // diabaikan
            }
        }

        sceneSensitivity = prefs.getFloat(KEY_SCENE_SENS, 0.55f).coerceIn(0.1f, 1.0f)
        sceneMinDur = ((prefs.getFloat(KEY_SCENE_MINDUR, 0.5f).toDouble() * 10.0).roundToInt() / 10.0)
            .coerceIn(0.1, 5.0)
        // Opsi maksimal durasi deteksi scene default OFF (false)
        sceneMaxDurEnabled = prefs.getBoolean(KEY_SCENE_MAXDUR_ENABLED, false)
        sceneMaxDur = max(
            sceneMinDur,
            ((prefs.getFloat(KEY_SCENE_MAXDUR, 5.0f).toDouble() * 10.0).roundToInt() / 10.0)
                .coerceIn(0.5, 20.0)
        )

        val raw = prefs.getString(KEY_NOTES_JSON, null)
        if (raw == null) {
            notes = emptyList()
            return
        }
        try {
            val arr = JSONArray(raw)
            val list = ArrayList<NoteItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optLong("id", System.currentTimeMillis() + i)
                val typeStr = o.optString("type", NoteType.BUG.name)
                val type = try {
                    NoteType.valueOf(typeStr)
                } catch (e: Exception) {
                    NoteType.BUG
                }
                val text = o.optString("text", "")
                val done = o.optBoolean("done", false)
                val createdAt = o.optString("createdAt", "")
                if (text.isNotEmpty()) {
                    list.add(NoteItem(id, type, text, done, createdAt))
                }
            }
            notes = list
        } catch (e: Exception) {
            // diabaikan
        }
    }

    private fun saveNotes(list: List<NoteItem>) {
        notes = list
        try {
            val arr = JSONArray()
            for (item in list) {
                val o = JSONObject()
                o.put("id", item.id)
                o.put("type", item.type.name)
                o.put("text", item.text)
                o.put("done", item.done)
                o.put("createdAt", item.createdAt)
                arr.put(o)
            }
            prefs.edit().putString(KEY_NOTES_JSON, arr.toString()).apply()
        } catch (e: Exception) {
            // diabaikan
        }
    }

    fun addNote(type: NoteType, text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val item = NoteItem(
            id = System.currentTimeMillis(),
            type = type,
            text = cleaned,
            done = false,
            createdAt = stamp
        )
        saveNotes(listOf(item) + notes)
        toast("Catatan disimpan")
    }

    fun updateNote(id: Long, type: NoteType, text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        saveNotes(notes.map { if (it.id == id) it.copy(type = type, text = cleaned) else it })
        toast("Catatan diperbarui")
    }

    fun toggleNoteDone(id: Long) {
        saveNotes(notes.map { if (it.id == id) it.copy(done = !it.done) else it })
    }

    fun deleteNote(id: Long) {
        saveNotes(notes.filterNot { it.id == id })
        toast("Catatan dihapus")
    }

    // ------------------------------------------------------------------ impor (mendukung multiseleksi)

    fun importVideo(uri: Uri) {
        importVideos(listOf(uri))
    }

    /**
     * Mengimpor video yang dibagikan lewat menu Share (ACTION_SEND / ACTION_SEND_MULTIPLE / ACTION_VIEW)
     * dari aplikasi lain. Bila URI bersifat sementara (mis. FileProvider WhatsApp/TikTok/Telegram),
     * aliran berkas disalin otomatis ke cache lokal aplikasi agar pratinjau & ekspor 100% stabil.
     */
    fun importSharedVideos(uris: List<Uri>, persistFlags: Map<Uri, Boolean>) {
        if (uris.isEmpty()) return
        importSourceSheet = false
        aiSheet = false
        sceneDetectSheet = false
        exportSheet = false
        noteSheet = false
        themeSheet = false

        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val resolved = withContext(Dispatchers.IO) {
                val shareDir = File(ctx.cacheDir, "shared_inputs")
                if (!shareDir.exists()) shareDir.mkdirs()
                // Bersihkan salinan share lama (>24 jam) agar ruang cache tetap hemat
                val cutoff = System.currentTimeMillis() - 24L * 3600L * 1000L
                shareDir.listFiles()?.forEach { old ->
                    if (old.isFile && old.lastModified() < cutoff) {
                        try { old.delete() } catch (_: Exception) {}
                    }
                }

                val out = ArrayList<Uri>(uris.size)
                for ((idx, u) in uris.withIndex()) {
                    val persisted = persistFlags[u] == true
                    val isDirectFile = u.scheme == "file"
                    if (persisted || isDirectFile) {
                        val directOk = VideoProbe.probe(ctx, u) != null
                        if (directOk) {
                            out.add(u)
                            continue
                        }
                    }
                    // Salin stream sementara ke berkas lokal di cacheDir/shared_inputs
                    try {
                        val dest = File(shareDir, "shared_${System.currentTimeMillis()}_$idx.mp4")
                        val copiedOk = ctx.contentResolver.openInputStream(u)?.use { input ->
                            dest.outputStream().use { output ->
                                input.copyTo(output, 256 * 1024)
                            }
                            true
                        } ?: false
                        if (copiedOk && dest.exists() && dest.length() > 0L) {
                            out.add(Uri.fromFile(dest))
                        } else {
                            out.add(u)
                        }
                    } catch (_: Exception) {
                        out.add(u)
                    }
                }
                out
            }
            importVideos(resolved)
        }
    }

    fun importVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            pause()
            val ctx = getApplication<Application>()
            val probed = withContext(Dispatchers.IO) {
                val res = ArrayList<MediaSource>()
                for (u in uris) {
                    val inf = VideoProbe.probe(ctx, u)
                    if (inf != null && inf.durationMs > 0L) {
                        res.add(MediaSource(u, inf))
                    }
                }
                res
            }
            if (probed.isEmpty()) {
                toast("Durasi video tidak terbaca")
                return@launch
            }

            val wasEmpty = !hasVideo || sources.isEmpty() || core.segs.isEmpty()
            val startSrcIndex = if (wasEmpty) 0 else sources.size
            val newDurs = probed.map { it.info.durationMs / 1000.0 }

            if (wasEmpty) {
                gridThumbWorkerJob?.cancel()
                clearGridFrameCaches()
                sources.clear()
                sourceThumbs.clear()
                sourceThumbReady.clear()
                sources.addAll(probed)
                for (ms in probed) {
                    val dur = ms.info.durationMs / 1000.0
                    val uniq = min(150, max(10, ceil(dur * 3.0).toInt()))
                    sourceThumbs.add(arrayOfNulls(uniq))
                    sourceThumbReady.add(0)
                }
                core.loadVideos(newDurs)
                pps = Rules.PPS0
                resetPreviewZoom()
                clearGridMultiSelect()
                gridFilter = GRID_FILTER_ALL
                curSeg = 0
                currentScanResult = null
            } else {
                sources.addAll(probed)
                for (ms in probed) {
                    val dur = ms.info.durationMs / 1000.0
                    val uniq = min(150, max(10, ceil(dur * 3.0).toInt()))
                    sourceThumbs.add(arrayOfNulls(uniq))
                    sourceThumbReady.add(0)
                }
                core.appendVideos(newDurs)
            }

            refreshDerived()
            setSeekFast(false)
            player.setMediaItems(sources.map { MediaItem.fromUri(it.uri) })
            player.prepare()
            player.pause()
            seekVideoToEdit(forceExact = true)
            bump()

            val count = probed.size
            if (wasEmpty) {
                toast(if (count == 1) "Video diimpor" else "$count video diimpor ke timeline")
            } else {
                toast(if (count == 1) "1 video ditambahkan ke timeline" else "$count video ditambahkan ke timeline")
            }
            startThumbsFrom(startSrcIndex)
        }
    }

    fun resetMedia() {
        pause()
        thumbJob?.cancel()
        gridThumbWorkerJob?.cancel()
        importJob?.cancel()
        sceneScanJob?.cancel()
        reverseJob?.cancel()
        aiFrameJob?.cancel()
        clearGridFrameCaches()
        reverseRendering = false
        sceneScanning = false
        currentScanResult = null
        aiSeekBitmap = null
        gridColSliderOpen = false
        collageOpen = false
        collageItems = emptyList()
        clearGridMultiSelect()
        gridFilter = GRID_FILTER_ALL
        sources.clear()
        sourceThumbs.clear()
        sourceThumbReady.clear()
        player.clearMediaItems()
        resetPreviewZoom()
        core.clear()
        refreshDerived()
        bump()
        toast("Timeline dikosongkan")
    }

    private fun startThumbsFrom(fromSrcIndex: Int) {
        thumbJob?.cancel()
        val snapshot = sources.mapIndexed { idx, ms -> Triple(idx, ms.uri, ms.info) }
        thumbJob = viewModelScope.launch(Dispatchers.IO) {
            for ((srcIdx, uri, inf) in snapshot) {
                if (srcIdx < fromSrcIndex && (sourceThumbReady.getOrNull(srcIdx) ?: 0) > 0) continue
                ensureActive()
                val arr = sourceThumbs.getOrNull(srcIdx) ?: continue
                val uniq = arr.size
                val dur = inf.durationMs / 1000.0
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(getApplication<Application>(), uri)
                    var prev: Bitmap? = null
                    for (i in 0 until uniq) {
                        ensureActive()
                        val sec = min(dur - 0.05, (i + 0.5) / uniq * dur)
                        val us = Math.round(max(0.0, sec) * 1_000_000.0)
                        var tile: Bitmap? = null
                        try {
                            val raw = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            if (raw != null) {
                                val up = VideoFrames.upright(raw, inf)
                                tile = VideoFrames.coverCrop(up, THUMB_W, THUMB_H)
                            }
                        } catch (e: Exception) {
                            tile = null
                        }
                        if (tile == null) tile = prev
                        arr[i] = tile
                        if (tile != null) prev = tile
                        if (i % 6 == 5 || i == uniq - 1) {
                            withContext(Dispatchers.Main) {
                                if (srcIdx < sourceThumbReady.size) {
                                    sourceThumbReady[srcIdx] = i + 1
                                }
                                bump()
                            }
                        }
                    }
                } catch (e: Exception) {
                    // diabaikan
                } finally {
                    try {
                        r.release()
                    } catch (e: Exception) {
                        // diabaikan
                    }
                }
            }
        }
    }

    fun thumbAt(src: Int, time: Double): Bitmap? {
        val arr = sourceThumbs.getOrNull(src) ?: return null
        val ready = sourceThumbReady.getOrNull(src) ?: 0
        if (arr.isEmpty() || ready == 0) return null
        val dur = core.durOf(src)
        if (dur <= 0.0) return null
        var k = floor(time / dur * arr.size).toInt()
        if (k < 0) k = 0
        if (k > arr.size - 1) k = arr.size - 1
        val direct = arr[k]
        if (direct != null) return direct
        var d = 1
        while (d < arr.size) {
            val a = k - d
            if (a >= 0) {
                val ba = arr[a]
                if (ba != null) return ba
            }
            val b = k + d
            if (b < arr.size) {
                val bb = arr[b]
                if (bb != null) return bb
            }
            d++
        }
        return null
    }

    // ------------------------------------------------------------------ thumbnail frame pertama utuh khusus Mode Grid

    private fun clearGridFrameCaches() {
        synchronized(gridCacheLock) {
            pendingGridFrames.clear()
            gridTransformedCache.clear()
            gridBaseFrameCache.clear()
        }
    }

    private fun copyWithTransform(srcBmp: Bitmap, normRot: Int, curFlip: Boolean): Bitmap {
        if (normRot == 0 && !curFlip) return srcBmp
        val m = Matrix()
        if (normRot != 0) m.postRotate(normRot.toFloat())
        if (curFlip) m.postScale(-1f, 1f)
        return Bitmap.createBitmap(srcBmp, 0, 0, srcBmp.width, srcBmp.height, m, true)
    }

    /**
     * Mengembalikan bitmap frame pertama klip secara utuh (tanpa crop, sudah mengikuti rotasi scene & cermin)
     * agar tampilan thumbnail di Mode Grid 100% sesuai dengan tampilan video saat autoplay.
     */
    fun gridFirstFrameAt(src: Int, firstFrameSec: Double, segRot: Int = rot): Bitmap? {
        if (!hasVideo || src !in sources.indices) return null
        val bucket10ms = Math.round(max(0.0, firstFrameSec) * 1000.0) / 10L
        val baseKey = (src.toLong() shl 40) or (bucket10ms and 0xFFFFFFFFFFL)
        val normRot = ((segRot % 360) + 360) % 360
        val curFlip = flip

        synchronized(gridCacheLock) {
            val base = gridBaseFrameCache[baseKey]
            if (base == null || base.isRecycled) return null
            if (normRot == 0 && !curFlip) return base

            val tKey = GridTransformKey(baseKey, normRot, curFlip)
            val cachedT = gridTransformedCache[tKey]
            if (cachedT != null && !cachedT.isRecycled) return cachedT

            val transformed = copyWithTransform(base, normRot, curFlip)
            gridTransformedCache[tKey] = transformed
            while (gridTransformedCache.size > MAX_GRID_FRAME_CACHE) {
                val eldestKey = gridTransformedCache.entries.firstOrNull()?.key ?: break
                gridTransformedCache.remove(eldestKey)
            }
            return transformed
        }
    }

    /** Meminta decode asinkron frame pertama utuh pada [firstFrameSec] bila belum ada di cache. */
    fun ensureGridFirstFrame(src: Int, firstFrameSec: Double) {
        if (!hasVideo || src !in sources.indices) return
        val bucket10ms = Math.round(max(0.0, firstFrameSec) * 1000.0) / 10L
        val baseKey = (src.toLong() shl 40) or (bucket10ms and 0xFFFFFFFFFFL)
        val needDecode = synchronized(gridCacheLock) {
            val existing = gridBaseFrameCache[baseKey]
            if (existing != null && !existing.isRecycled) {
                false
            } else {
                pendingGridFrames.add(src to bucket10ms)
                true
            }
        }
        if (needDecode) {
            launchGridThumbWorkerIfNeeded()
        }
    }

    private fun launchGridThumbWorkerIfNeeded() {
        if (gridThumbWorkerJob?.isActive == true) return
        gridThumbWorkerJob = viewModelScope.launch(Dispatchers.IO) {
            var decodedSinceBump = 0
            while (true) {
                while (true) {
                    ensureActive()
                    val batch: Pair<Int, List<Long>>? = synchronized(gridCacheLock) {
                        val first = pendingGridFrames.firstOrNull() ?: return@synchronized null
                        val targetSrc = first.first
                        val sameSrc = pendingGridFrames.filter { it.first == targetSrc }.take(16)
                        pendingGridFrames.removeAll(sameSrc.toSet())
                        targetSrc to sameSrc.map { it.second }
                    }
                    if (batch == null) break

                    val (srcIdx, buckets) = batch
                    val mediaSrc = withContext(Dispatchers.Main) {
                        sources.getOrNull(srcIdx)
                    } ?: continue

                    val app = getApplication<Application>()
                    val retriever = MediaMetadataRetriever()
                    var retrieverReady = false
                    try {
                        retriever.setDataSource(app, mediaSrc.uri)
                        retrieverReady = true
                    } catch (e: Exception) {
                        retrieverReady = false
                    }

                    try {
                        for (bucket in buckets) {
                            ensureActive()
                            val baseKey = (srcIdx.toLong() shl 40) or (bucket and 0xFFFFFFFFFFL)
                            val already = synchronized(gridCacheLock) {
                                gridBaseFrameCache[baseKey]?.isRecycled == false
                            }
                            if (already) continue

                            val maxUs = max(0L, (mediaSrc.info.durationMs - 1L) * 1000L)
                            val targetUs = (bucket * 10_000L).coerceIn(0L, maxUs)

                            var decodedBmp: Bitmap? = null
                            if (retrieverReady) {
                                try {
                                    val raw = retriever.getFrameAtTime(
                                        targetUs,
                                        MediaMetadataRetriever.OPTION_CLOSEST
                                    )
                                    if (raw != null) {
                                        val up = VideoFrames.upright(raw, mediaSrc.info)
                                        decodedBmp = VideoFrames.limitLongSide(up, GRID_FIRST_FRAME_MAX_SIDE)
                                    }
                                } catch (e: Exception) {
                                    decodedBmp = null
                                }
                            }

                            if (decodedBmp == null) {
                                decodedBmp = try {
                                    val exact = VideoFrames.grabExactFrame(app, mediaSrc.uri, targetUs, mediaSrc.info)
                                    if (exact != null) VideoFrames.limitLongSide(exact, GRID_FIRST_FRAME_MAX_SIDE) else null
                                } catch (e: Throwable) {
                                    null
                                }
                            }

                            if (decodedBmp != null) {
                                synchronized(gridCacheLock) {
                                    gridBaseFrameCache[baseKey] = decodedBmp
                                    while (gridBaseFrameCache.size > MAX_GRID_FRAME_CACHE) {
                                        val eldestKey = gridBaseFrameCache.entries.firstOrNull()?.key ?: break
                                        gridBaseFrameCache.remove(eldestKey)
                                    }
                                }
                                decodedSinceBump++
                                if (decodedSinceBump >= 2) {
                                    decodedSinceBump = 0
                                    withContext(Dispatchers.Main) { bump() }
                                }
                            }
                        }
                    } finally {
                        try {
                            retriever.release()
                        } catch (e: Exception) {
                            // diabaikan
                        }
                    }
                }

                val moreArrived = withContext(Dispatchers.Main) {
                    if (decodedSinceBump > 0) {
                        decodedSinceBump = 0
                        bump()
                    }
                    synchronized(gridCacheLock) { pendingGridFrames.isNotEmpty() }
                }
                if (!moreArrived) break
            }
        }
    }

    // ------------------------------------------------------------------ layar penuh

    fun toggleFullscreen() {
        fullscreen = !fullscreen
    }

    // ------------------------------------------------------------------ ekspor

    private fun demoInfo(): VideoInfo = VideoInfo(1080, 1920, 0, 0L, 30f, false)

    val isOriginalExportSpec: Boolean
        get() = qIdx == ExportMath.ORIGINAL_IDX && fIdx == ExportMath.ORIGINAL_IDX

    /** Shortcut untuk mengatur Kualitas & FPS agar sama persis dengan video asli. */
    fun applyOriginalExportSpec() {
        qIdx = ExportMath.ORIGINAL_IDX
        fIdx = ExportMath.ORIGINAL_IDX
        bump()
        toast("Kualitas & FPS disetel sesuai video asli")
    }

    /** Ringkasan resolusi & FPS asli dari acuan ekspor yang aktif (sudah memperhitungkan rotasi scene). */
    fun originalSpecLabel(): String {
        val resolved = resolveExportReference()
        if (exportSpecRef == ExportSpecRef.PER_CLIP_NATIVE && resolved.supportsPerClipNative) {
            return "Asli tiap klip masing-masing"
        }
        val inf = resolved.refInfo
        val dims = ExportMath.origDims(inf.dispW, inf.dispH, resolved.refRot)
        val fpsVal = if (inf.fps > 1f) inf.fps.toDouble() else 30.0
        return "${dims[0]}×${dims[1]} · ${Fmt.fps(fpsVal)} fps"
    }

    /** Daftar Tag yang benar-benar terpasang pada klip di timeline beserta jumlah klipnya. */
    fun usedTagsInTimeline(): List<Pair<String, Int>> {
        val segs = core.segs
        if (segs.isEmpty()) return emptyList()
        val ordered = LinkedHashSet<String>()
        for (t in availableTags) ordered.add(t)
        for (g in segs) {
            for (t in g.tags) ordered.add(t)
        }
        val res = ArrayList<Pair<String, Int>>()
        for (tag in ordered) {
            val cnt = segs.count { g -> g.tags.any { it.equals(tag, ignoreCase = true) } }
            if (cnt > 0) res.add(tag to cnt)
        }
        return res
    }

    fun selectExportByTagMode() {
        exportMode = ExportMode.BY_TAG
        ensureValidExportTags()
        bump()
    }

    private fun ensureValidExportTags() {
        val usedNames = usedTagsInTimeline().map { it.first }
        val validCurrent = exportSelectedTags.filter { sel ->
            usedNames.any { it.equals(sel, ignoreCase = true) }
        }
        exportSelectedTags = if (validCurrent.isNotEmpty()) {
            validCurrent
        } else {
            usedNames
        }
    }

    fun toggleExportTag(tag: String) {
        exportSelectedTags = if (exportSelectedTags.any { it.equals(tag, ignoreCase = true) }) {
            exportSelectedTags.filterNot { it.equals(tag, ignoreCase = true) }
        } else {
            exportSelectedTags + tag
        }
        bump()
    }

    fun toggleAllExportTags() {
        val allUsed = usedTagsInTimeline().map { it.first }
        exportSelectedTags = if (allUsed.isNotEmpty() && exportSelectedTags.size >= allUsed.size) {
            emptyList()
        } else {
            allUsed
        }
        bump()
    }

    fun openExport() {
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) {
            toast("Impor video dulu sebelum ekspor")
            return
        }
        pause()
        ensureValidExportTags()
        exportSheet = true
    }

    fun closeExport() {
        if (ExportCenter.isRunning()) {
            toast("Ekspor sedang berjalan")
            return
        }
        exportSheet = false
        ExportCenter.reset()
    }

    private data class ResolvedExportRef(
        val refInfo: VideoInfo,
        val refRot: Int,
        val hasMixedSpecs: Boolean,
        val mixedSummary: String,
        val supportsPerClipNative: Boolean,
        val extraNote: String? = null
    )

    private fun resolveExportReference(): ResolvedExportRef {
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) {
            return ResolvedExportRef(
                refInfo = info ?: demoInfo(),
                refRot = core.selRot,
                hasMixedSpecs = false,
                mixedSummary = "",
                supportsPerClipNative = false
            )
        }
        val activeJobs = ExportMath.buildJobs(
            exportMode,
            core.segs,
            core.sel,
            exportSelectedTags,
            exportByTagStyle
        )
        val activeSegs = activeJobs.flatMap { it.segs }.ifEmpty { core.segs }
        val supportsPerClip = activeJobs.size > 1 && activeJobs.all { it.segs.size == 1 }

        data class ClipEffectiveSpec(
            val w: Int,
            val h: Int,
            val fps: Float,
            val hasAudio: Boolean,
            val durationMs: Long
        )

        val clipSpecs = activeSegs.map { g ->
            val ms = sources.getOrElse(g.src) { sources.first() }
            val dims = ExportMath.origDims(ms.info.dispW, ms.info.dispH, g.rot)
            val fpsVal = if (ms.info.fps > 1f) ms.info.fps else 30f
            ClipEffectiveSpec(dims[0], dims[1], fpsVal, ms.info.hasAudio, ms.info.durationMs)
        }

        val signatures = clipSpecs.map { cs ->
            val roundedFps = Math.round(cs.fps * 10f) / 10f
            "${cs.w}×${cs.h} @ ${Fmt.fps(roundedFps.toDouble())}fps"
        }.distinct()
        val mixed = signatures.size > 1
        val summary = if (mixed) {
            "Klip campuran: " + signatures.take(3).joinToString(" · ") +
                (if (signatures.size > 3) " (+${signatures.size - 3} lainnya)" else "")
        } else {
            ""
        }

        // Bila hanya mengekspor 1 potongan aktif (Mode CURRENT), gunakan sumber & rotasi scene aktif tersebut!
        if (exportMode == ExportMode.CURRENT) {
            val curSegObj = core.segs.getOrElse(core.sel) { core.segs.first() }
            val curSrc = sources.getOrElse(curSegObj.src) { sources.first() }
            return ResolvedExportRef(
                refInfo = curSrc.info,
                refRot = curSegObj.rot,
                hasMixedSpecs = false,
                mixedSummary = "",
                supportsPerClipNative = false
            )
        }

        val primarySrc = sources.first()
        val primaryRot = activeSegs.firstOrNull { it.src == 0 }?.rot ?: activeSegs.first().rot
        val primaryDims = ExportMath.origDims(
            primarySrc.info.dispW,
            primarySrc.info.dispH,
            primaryRot
        )
        val primaryLandscape = primaryDims[0] >= primaryDims[1]

        return when (exportSpecRef) {
            ExportSpecRef.MAIN_ORIGINAL -> {
                val note = if (mixed) {
                    "Campuran video asli & AI disatukan mengikuti spesifikasi Video Utama (${primaryDims[0]}×${primaryDims[1]} · ${Fmt.fps((if (primarySrc.info.fps > 1f) primarySrc.info.fps else 30f).toDouble())} fps)."
                } else null
                ResolvedExportRef(primarySrc.info, primaryRot, mixed, summary, supportsPerClip, note)
            }

            ExportSpecRef.HIGHEST -> {
                val bestPixels = clipSpecs.maxByOrNull { it.w.toLong() * it.h.toLong() } ?: clipSpecs.first()
                val maxFps = clipSpecs.maxOf { it.fps }
                val longSide = max(bestPixels.w, bestPixels.h)
                val shortSide = min(bestPixels.w, bestPixels.h)
                val outW = if (primaryLandscape) longSide else shortSide
                val outH = if (primaryLandscape) shortSide else longSide
                val synth = VideoInfo(
                    width = outW,
                    height = outH,
                    rotation = 0,
                    durationMs = primarySrc.info.durationMs,
                    fps = maxFps,
                    hasAudio = clipSpecs.any { it.hasAudio }
                )
                val note = if (mixed) {
                    "Acuan Kualitas Tertinggi dipilih (${outW}×${outH} · ${Fmt.fps(maxFps.toDouble())} fps); klip resolusi lebih rendah disesuaikan ke kanvas ini."
                } else null
                ResolvedExportRef(synth, 0, mixed, summary, supportsPerClip, note)
            }

            ExportSpecRef.LOWEST_AI -> {
                val minPixels = clipSpecs.minByOrNull { it.w.toLong() * it.h.toLong() } ?: clipSpecs.first()
                val minFps = clipSpecs.minOf { it.fps }
                val longSide = max(minPixels.w, minPixels.h)
                val shortSide = min(minPixels.w, minPixels.h)
                val outW = if (primaryLandscape) longSide else shortSide
                val outH = if (primaryLandscape) shortSide else longSide
                val synth = VideoInfo(
                    width = outW,
                    height = outH,
                    rotation = 0,
                    durationMs = primarySrc.info.durationMs,
                    fps = minFps,
                    hasAudio = clipSpecs.any { it.hasAudio }
                )
                val note = if (mixed) {
                    "Acuan Terendah / Sesuai AI dipilih (${outW}×${outH} · ${Fmt.fps(minFps.toDouble())} fps) tanpa meng-upscale klip AI."
                } else null
                ResolvedExportRef(synth, 0, mixed, summary, supportsPerClip, note)
            }

            ExportSpecRef.PER_CLIP_NATIVE -> {
                val note = if (supportsPerClip) {
                    "Mode Asli Tiap Klip aktif: setiap berkas MP4 diekspor memakai resolusi, rotasi, & FPS asli klipnya masing-masing."
                } else {
                    "Karena beberapa klip digabung ke 1 berkas MP4, kanvas mengikuti Video Utama (1 file MP4 hanya mendukung 1 ukuran frame)."
                }
                ResolvedExportRef(primarySrc.info, primaryRot, mixed, summary, supportsPerClip, note)
            }
        }
    }

    fun exportInfo(): ExportInfo {
        val _rev = rev
        val resolved = resolveExportReference()
        val basePlan = ExportPlanner.plan(resolved.refInfo, resolved.refRot, qIdx, fIdx) { w, h ->
            EncoderCaps.supportsH264(w, h)
        }
        val mergedNotes = if (resolved.extraNote != null) {
            listOf(resolved.extraNote) + basePlan.notes
        } else {
            basePlan.notes
        }
        val plan = basePlan.copy(notes = mergedNotes)
        val cur = core.segs.getOrNull(core.sel)?.len ?: 0.0

        val usedTags = usedTagsInTimeline()
        val byTagJobs = ExportMath.buildJobs(
            ExportMode.BY_TAG,
            core.segs,
            core.sel,
            exportSelectedTags,
            exportByTagStyle
        )
        val byTagDur = byTagJobs.sumOf { job -> job.segs.sumOf { it.len } }
        val subByTag = when {
            usedTags.isEmpty() -> "Belum ada klip bertag (tambahkan lewat Mode Grid)"
            exportSelectedTags.isEmpty() -> "Pilih minimal 1 Tag di bawah (${usedTags.size} tag tersedia)"
            byTagJobs.isEmpty() -> "Tidak ada klip cocok untuk Tag terpilih"
            else -> "${byTagJobs.size} file · " + Fmt.time(byTagDur) +
                (if (byTagJobs.size > 1) " total" else "")
        }

        val dur = when (exportMode) {
            ExportMode.CURRENT -> cur
            ExportMode.BY_TAG -> byTagDur
            else -> core.total
        }
        val canStart = when (exportMode) {
            ExportMode.BY_TAG -> exportSelectedTags.isNotEmpty() && byTagJobs.isNotEmpty()
            else -> true
        }
        // Hitung juga ringkasan seluruh sumber di timeline (mis. saat mode CURRENT dipilih tapi timeline punya klip AI)
        val allTimelineSpecs = core.segs.map { g ->
            val ms = sources.getOrElse(g.src) { sources.firstOrNull() ?: MediaSource(Uri.EMPTY, demoInfo()) }
            val d = ExportMath.origDims(ms.info.dispW, ms.info.dispH, g.rot)
            val fpsVal = if (ms.info.fps > 1f) ms.info.fps.toDouble() else 30.0
            "${d[0]}×${d[1]} @ ${Fmt.fps(fpsVal)}fps"
        }.distinct()
        val allTimelineMixed = allTimelineSpecs.size > 1

        val mb = ExportMath.estimateMB(plan.width, plan.height, plan.fps, dur)
        return ExportInfo(
            plan = plan,
            mb = mb,
            subVideo = "1 file · " + Fmt.time(core.total),
            subAll = core.segs.size.toString() + " file · " + Fmt.time(core.total) + " total",
            subCurrent = "1 file · " + Fmt.time(cur),
            subByTag = subByTag,
            canStart = canStart,
            hasMixedSpecs = resolved.hasMixedSpecs || allTimelineMixed,
            mixedSpecsSummary = resolved.mixedSummary.ifEmpty { allTimelineSpecs.joinToString(" + ") },
            allSourcesSummary = allTimelineSpecs.joinToString(" + "),
            supportsPerClipNative = resolved.supportsPerClipNative
        )
    }

    fun startExport() {
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) {
            toast("Impor video dulu sebelum ekspor")
            return
        }
        if (ExportCenter.isRunning()) return
        if (exportMode == ExportMode.BY_TAG) {
            val testJobs = ExportMath.buildJobs(
                ExportMode.BY_TAG,
                core.segs,
                core.sel,
                exportSelectedTags,
                exportByTagStyle
            )
            if (exportSelectedTags.isEmpty() || testJobs.isEmpty()) {
                toast("Pilih minimal 1 Tag yang memiliki klip sebelum mengekspor")
                return
            }
        }
        val primary = sources.first()
        val resolved = resolveExportReference()
        val specs = sources.map { SourceSpec(it.uri.toString(), it.info) }
        val req = ExportRequest(
            sourceUri = primary.uri.toString(),
            info = resolved.refInfo,
            sources = specs,
            segs = core.segs,
            currentIndex = core.sel,
            rot = resolved.refRot,
            flip = core.flip,
            style1 = style1,
            style2 = style2,
            qIdx = qIdx,
            fIdx = fIdx,
            mode = exportMode,
            selectedExportTags = exportSelectedTags,
            byTagStyle = exportByTagStyle,
            specRef = exportSpecRef,
            scaleFit = exportScaleFit
        )
        val basePlan = ExportPlanner.plan(resolved.refInfo, resolved.refRot, qIdx, fIdx) { w, h ->
            EncoderCaps.supportsH264(w, h)
        }
        val plan = if (resolved.extraNote != null) {
            basePlan.copy(notes = listOf(resolved.extraNote) + basePlan.notes)
        } else {
            basePlan
        }
        ExportCenter.start(getApplication<Application>(), req, plan)
    }

    fun cancelExport() {
        ExportCenter.cancel()
    }

    companion object {
        const val THUMB_W = 128
        const val THUMB_H = 96
        const val GRID_FIRST_FRAME_MAX_SIDE = 384
        const val MAX_GRID_FRAME_CACHE = 96
        const val SLICE_KEY_OFFSET = 10_000
        const val MAX_COLLAGE_TILES = 9
        const val GRID_FILTER_ALL = "__ALL__"
        const val GRID_FILTER_TAGGED = "__TAGGED__"
        const val GRID_FILTER_UNTAGGED = "__UNTAGGED__"

        val DEFAULT_TAGS = listOf("Favorit", "Utama", "Highlight", "B-Roll", "Simpan")

        /** 12 opsi warna kontras untuk Custom Tag pada Mode Grid. */
        val TAG_COLOR_PALETTE: List<TagColorOption> = listOf(
            TagColorOption(0, "Kuning", Color(0xFFFACC15)),
            TagColorOption(1, "Merah", Color(0xFFF43F5E)),
            TagColorOption(2, "Oranye", Color(0xFFF97316)),
            TagColorOption(3, "Biru Muda", Color(0xFF38BDF8)),
            TagColorOption(4, "Hijau", Color(0xFF10B981)),
            TagColorOption(5, "Ungu", Color(0xFFA855F7)),
            TagColorOption(6, "Pink", Color(0xFFEC4899)),
            TagColorOption(7, "Toska", Color(0xFF14B8A6)),
            TagColorOption(8, "Lime", Color(0xFF84CC16)),
            TagColorOption(9, "Indigo", Color(0xFF6366F1)),
            TagColorOption(10, "Amber", Color(0xFFF59E0B)),
            TagColorOption(11, "Putih", Color(0xFFE2E8F0))
        )

        private const val PREFS_NAME = "editor_video_notes"
        private const val KEY_NOTES_JSON = "notes_json"
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_GRID_COLS = "grid_columns"
        private const val KEY_PREVIEW_LOOP = "preview_loop_enabled"
        private const val KEY_CUSTOM_TAGS = "custom_tags_json"
        private const val KEY_TAG_COLORS_JSON = "custom_tag_colors_json"
        private const val KEY_SCENE_SENS = "scene_sensitivity"
        private const val KEY_SCENE_MINDUR = "scene_min_dur"
        private const val KEY_SCENE_MAXDUR_ENABLED = "scene_max_dur_enabled"
        private const val KEY_SCENE_MAXDUR = "scene_max_dur"
        private const val KEY_AI_PROMPTS_JSON = "ai_prompt_library_json"
        private const val KEY_AI_RUNNER_CFG = "ai_runner_config_json"
        private const val KEY_AI_COPIED_CLIPS = "ai_runner_copied_ids"
    }
}
