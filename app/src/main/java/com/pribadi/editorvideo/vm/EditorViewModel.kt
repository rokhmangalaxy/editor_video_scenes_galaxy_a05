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
import com.pribadi.editorvideo.export.DetectedScene
import com.pribadi.editorvideo.export.EncoderCaps
import com.pribadi.editorvideo.export.ExportCenter
import com.pribadi.editorvideo.export.ExportMath
import com.pribadi.editorvideo.export.ExportMode
import com.pribadi.editorvideo.export.ExportPlan
import com.pribadi.editorvideo.export.ExportPlanner
import com.pribadi.editorvideo.export.ExportRequest
import com.pribadi.editorvideo.export.ExportState
import com.pribadi.editorvideo.export.ReverseVideoRenderer
import com.pribadi.editorvideo.export.SceneDetector
import com.pribadi.editorvideo.export.SceneScanResult
import com.pribadi.editorvideo.export.SourceSpec
import com.pribadi.editorvideo.export.VideoFrames
import com.pribadi.editorvideo.export.VideoProbe
import com.pribadi.editorvideo.model.EditorCore
import com.pribadi.editorvideo.model.Fmt
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
enum class Tool { SPLIT, SCENE_DETECT, AI, SPEED, TEXT, REVERSE, DUP, DEL, ROTATE, MIRROR, FREEZE }

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
    val subCurrent: String
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

    // --- State UI Fitur AI (gambar presisi tinggi 100% sama dengan Preview) ---
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

    private var activeTextureViewRef: WeakReference<TextureView>? = null
    private var renderedFrameSeq by mutableIntStateOf(0)

    // --- State Deteksi Scene + Grid Preview Deteksi ---
    var sceneScanning by mutableStateOf(false)
        private set
    var sceneScanProgress by mutableFloatStateOf(0f)
        private set
    var sceneSensitivity by mutableFloatStateOf(0.55f)
        private set
    var sceneMinDur by mutableDoubleStateOf(0.5)
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
        rot = core.rot
        flip = core.flip
        selIndex = core.sel
        selSpeed = core.selSpeed
        selReversed = core.selReversed
        selRevMode = core.selRevMode
        segCount = core.segs.size
        sourceCount = sources.size
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

    /** Aspect ratio tampilan untuk sumber ke-[srcIdx] (sudah memperhitungkan rotasi global). */
    fun aspectOfSource(srcIdx: Int): Float {
        val inf = sources.getOrElse(srcIdx) { sources.firstOrNull() }?.info ?: info
        if (inf == null || inf.dispW <= 0 || inf.dispH <= 0) return 9f / 16f
        return if (rot % 180 != 0) {
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
        toggleTagForCurrentTarget(finalTag)
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
        if (!hasVideo || core.segs.isEmpty()) {
            toast("Impor video terlebih dahulu")
            return
        }
        // Bila masih mode 9 Bagian Waktu pada 1 klip utuh, beri tahu atau tag klip #1
        pause()
        gridTagSheet = true
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
                        aspect = aspectOfSource(g.src),
                        rot = rot,
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
                        aspect = aspectOfSource(g.src),
                        rot = rot,
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
        aiSheet = true
        captureAiSeekFrame(waitSeekSettle = false)
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

    fun onAiGenerateClicked() {
        val promptClean = aiPrompt.trim()
        if (promptClean.isEmpty()) {
            toast("Masukkan prompt terlebih dahulu")
            return
        }
        aiPreviewStatus = "Prompt siap diselipkan tepat di posisi seek ${Fmt.preciseTime(aiSeekTimeSec)} (Klip #${aiSeekClipIndex + 1}) — pemrosesan backend AI segera hadir."
        toast("UI AI siap di posisi ${Fmt.preciseTime(aiSeekTimeSec)}")
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

    fun togglePlay() {
        if (playing) pause() else play()
    }

    fun play() {
        if (playing) return
        if (!hasVideo || core.segs.isEmpty()) {
            toast("Impor video dulu")
            return
        }
        if (core.t >= core.total - 0.02) core.t = 0.0
        val l = core.locate(core.t)
        val g = core.segs[l.i]
        curSeg = l.i
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
            if (curSeg < segs.size - 1) {
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
            detectedScenes = SceneDetector.buildScenes(cached, sceneSensitivity, sceneMinDur)
            return
        }

        startSceneScan(segIdx, g, attempt = 0)
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
            detectedScenes = SceneDetector.buildScenes(scan, sceneSensitivity, sceneMinDur)
            sceneScanning = false
            if (attempt > 0) {
                toast("Deteksi ulang selesai: ${detectedScenes.size} scene")
            }
        }
    }

    fun updateSceneSettings(newSensitivity: Float, newMinDur: Double) {
        val sens = ((newSensitivity * 100f).roundToInt() / 100f).coerceIn(0.1f, 1.0f)
        val minD = ((newMinDur * 10.0).roundToInt() / 10.0).coerceIn(0.1, 5.0)
        sceneSensitivity = sens
        sceneMinDur = minD
        prefs.edit()
            .putFloat(KEY_SCENE_SENS, sens)
            .putFloat(KEY_SCENE_MINDUR, minD.toFloat())
            .apply()
        val scan = currentScanResult ?: return
        detectedScenes = SceneDetector.buildScenes(scan, sceneSensitivity, sceneMinDur)
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
            version = "v1.0.9 (Terbaru)",
            date = "27/11/2026",
            title = "Frame Pertama Utuh di Mode Grid, Floating Range Kolom & 12 Warna Custom Tag Transparan",
            items = listOf(
                "✓ [Bug Fixed] Thumbnail setiap item di Mode Grid kini menampilkan frame pertama klip secara utuh (tanpa terpotong/crop dan konsisten rasio/rotasi/cermin dengan tampilan autoplay saat diklik).",
                "✓ [Pengembangan] Input range vertikal kolom (3–9) kini tampil sebagai panel floating saat simbol kostum kolom di-toggle, sehingga tidak memakan ruang Grid.",
                "✓ [Pengembangan] Custom Tag dilengkapi 12 opsi warna yang dapat dipilih/diubah kapan saja, serta seluruh label pada item Grid diberi efek transparansi."
            )
        ),
        UpdateLogEntry(
            version = "v1.0.8",
            date = "27/11/2026",
            title = "Presisi Frame AI 100% Identik Preview & Mode Grid (3–9 Kolom, Tag/Sort/Filter, Multiplayer Collage)",
            items = listOf(
                "✓ [Bug Fixed] Gambar di panel AI 100% sama persis dengan tampilan Preview + step ±1 frame.",
                "✓ [Pengembangan] Opsi range vertikal 3–9 kolom, Tagging, Sort, Filter, serta Hold Multiseleksi & Multiplayer Collage."
            )
        ),
        UpdateLogEntry(
            version = "v1.0.5",
            date = "27/11/2026",
            title = "Mode Grid, UI Fitur AI & 10 Pilihan Tema Mini Preview",
            items = listOf(
                "✓ Mode Grid interaktif, menu Fitur AI (UI), serta 10 pilihan tema dengan Grid 2 Kolom Mini Preview."
            )
        )
    )

    private fun loadPrefsAndNotes() {
        val savedThemeId = prefs.getString(KEY_APP_THEME, AppTheme.MEDIUM_MAROON.id)
        appTheme = AppTheme.fromId(savedThemeId)
        Ev.activeTheme = appTheme

        gridColumns = prefs.getInt(KEY_GRID_COLS, 3).coerceIn(3, 9)

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
     * Mengembalikan bitmap frame pertama klip secara utuh (tanpa crop, sudah mengikuti rotasi & cermin)
     * agar tampilan thumbnail di Mode Grid 100% sesuai dengan tampilan video saat autoplay.
     */
    fun gridFirstFrameAt(src: Int, firstFrameSec: Double): Bitmap? {
        if (!hasVideo || src !in sources.indices) return null
        val bucket10ms = Math.round(max(0.0, firstFrameSec) * 1000.0) / 10L
        val baseKey = (src.toLong() shl 40) or (bucket10ms and 0xFFFFFFFFFFL)
        val normRot = ((rot % 360) + 360) % 360
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

    fun openExport() {
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) {
            toast("Impor video dulu sebelum ekspor")
            return
        }
        pause()
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

    fun exportInfo(): ExportInfo {
        val inf = sources.firstOrNull()?.info ?: info ?: demoInfo()
        val plan = ExportPlanner.plan(inf, rot, qIdx, fIdx) { w, h -> EncoderCaps.supportsH264(w, h) }
        val cur = core.segs.getOrNull(core.sel)?.len ?: 0.0
        val dur = if (exportMode == ExportMode.CURRENT) cur else core.total
        val mb = ExportMath.estimateMB(plan.width, plan.height, plan.fps, dur)
        return ExportInfo(
            plan,
            mb,
            "1 file · " + Fmt.time(core.total),
            core.segs.size.toString() + " file · " + Fmt.time(core.total) + " total",
            "1 file · " + Fmt.time(cur)
        )
    }

    fun startExport() {
        if (!hasVideo || sources.isEmpty() || core.segs.isEmpty()) {
            toast("Impor video dulu sebelum ekspor")
            return
        }
        if (ExportCenter.isRunning()) return
        val primary = sources.first()
        val specs = sources.map { SourceSpec(it.uri.toString(), it.info) }
        val req = ExportRequest(
            sourceUri = primary.uri.toString(),
            info = primary.info,
            sources = specs,
            segs = core.segs,
            currentIndex = core.sel,
            rot = core.rot,
            flip = core.flip,
            style1 = style1,
            style2 = style2,
            qIdx = qIdx,
            fIdx = fIdx,
            mode = exportMode
        )
        val plan = ExportPlanner.plan(primary.info, core.rot, qIdx, fIdx) { w, h -> EncoderCaps.supportsH264(w, h) }
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
        private const val KEY_CUSTOM_TAGS = "custom_tags_json"
        private const val KEY_TAG_COLORS_JSON = "custom_tag_colors_json"
        private const val KEY_SCENE_SENS = "scene_sensitivity"
        private const val KEY_SCENE_MINDUR = "scene_min_dur"
    }
}
