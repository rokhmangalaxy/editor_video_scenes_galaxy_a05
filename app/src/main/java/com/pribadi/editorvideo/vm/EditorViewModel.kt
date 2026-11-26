package com.pribadi.editorvideo.vm

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.pribadi.editorvideo.export.EncoderCaps
import com.pribadi.editorvideo.export.ExportCenter
import com.pribadi.editorvideo.export.ExportMath
import com.pribadi.editorvideo.export.ExportMode
import com.pribadi.editorvideo.export.ExportPlan
import com.pribadi.editorvideo.export.ExportPlanner
import com.pribadi.editorvideo.export.ExportRequest
import com.pribadi.editorvideo.export.ExportState
import com.pribadi.editorvideo.export.SourceSpec
import com.pribadi.editorvideo.export.VideoFrames
import com.pribadi.editorvideo.export.VideoProbe
import com.pribadi.editorvideo.model.EditorCore
import com.pribadi.editorvideo.model.Fmt
import com.pribadi.editorvideo.model.Rules
import com.pribadi.editorvideo.model.VideoInfo
import com.pribadi.editorvideo.model.clampD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Alat di bar bawah. */
enum class Tool { SPLIT, SPEED, DUP, DEL, ROTATE, MIRROR, FREEZE, REVERSE, TEXT }

/** Kategori catatan (Bug atau Pengembangan). */
enum class NoteType(val label: String) {
    BUG("Bug"),
    DEV("Pengembangan")
}

/** Satu item catatan bug / ide pengembangan di masa depan. */
data class NoteItem(
    val id: Long,
    val type: NoteType,
    val text: String,
    val done: Boolean = false,
    val createdAt: String = ""
)

/** Satu sumber video yang telah diimpor. */
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
 * manajemen catatan (Note), dan ekspor. Semua fungsi dipanggil dari thread utama.
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    val core = EditorCore()
    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    // ------------------------------------------------------------------ state untuk UI

    /** Pemicu gambar ulang Canvas (timeline, teks overlay). */
    var rev by mutableIntStateOf(0)
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
    var selIndex by mutableIntStateOf(0)
        private set
    var segCount by mutableIntStateOf(1)
        private set
    var sourceCount by mutableIntStateOf(0)
        private set
    var hasVideo by mutableStateOf(false)
        private set
    var hint by mutableStateOf(false)
        private set
    var info by mutableStateOf<VideoInfo?>(null)
        private set

    /** Teks overlay atas dan bawah — default kosong sesuai permintaan. */
    var text1 by mutableStateOf("")
        private set
    var text2 by mutableStateOf("")
        private set

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

    var speedSheet by mutableStateOf(false)
    var textSheet by mutableStateOf(false)
    var noteSheet by mutableStateOf(false)
    var readmeSheet by mutableStateOf(false)
    var confirmText by mutableStateOf<String?>(null)
    var exportSheet by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)

    /** 0 = tidak ada, 1 = teks atas, 2 = teks bawah. */
    var editingText by mutableIntStateOf(0)

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
    private var lastRevSeekNanos = 0L
    private var toastJob: Job? = null
    private var zoomJob: Job? = null
    private var importJob: Job? = null
    private var thumbJob: Job? = null

    private val sourceThumbs = ArrayList<Array<Bitmap?>>()
    private val sourceThumbReady = ArrayList<Int>()

    private val choreographer: Choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback { ts -> tick(ts) }

    private val prefs = app.getSharedPreferences(PREFS_NOTES, Context.MODE_PRIVATE)

    init {
        core.onPause = { pause() }
        player.playWhenReady = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.addListener(object : Player.Listener {
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
        loadNotes()
        if (ExportCenter.isRunning()) exportSheet = true
        refreshDerived()
    }

    override fun onCleared() {
        choreographer.removeFrameCallback(frameCallback)
        thumbJob?.cancel()
        importJob?.cancel()
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
        canUndo = core.canUndo
        canRedo = core.canRedo
        rot = core.rot
        flip = core.flip
        selIndex = core.sel
        selSpeed = core.selSpeed
        selReversed = core.selReversed
        segCount = core.segs.size
        sourceCount = sources.size
        val activeSrc = core.segs.getOrNull(core.sel)?.src ?: 0
        if (sources.isNotEmpty()) {
            info = sources.getOrElse(activeSrc) { sources.first() }.info
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

    // ------------------------------------------------------------------ pemutar

    private fun seekPlayerTo(srcIdx: Int, sec: Double, tol: Double) {
        if (!hasVideo || sources.isEmpty()) return
        val validSrc = srcIdx.coerceIn(0, sources.size - 1)
        val targetMs = Math.round(max(0.0, sec) * 1000.0)
        if (player.currentMediaItemIndex != validSrc) {
            player.seekTo(validSrc, targetMs)
        } else {
            val cur = player.currentPosition / 1000.0
            if (abs(cur - sec) > tol) player.seekTo(targetMs)
        }
    }

    /** Seek ke posisi playhead saat ini. */
    private fun seekVideoToEdit() {
        val l = core.locate(core.t)
        curSeg = l.i
        val g = core.segs.getOrNull(l.i)
        val srcIdx = g?.src ?: 0
        seekPlayerTo(srcIdx, l.s, 0.03)
    }

    private fun setSeekFast(fast: Boolean) {
        player.setSeekParameters(if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
    }

    /** Menggambar ulang frame pratinjau (setelah permukaan video dibuat ulang). */
    fun refreshFrame() {
        if (hasVideo && !playing) {
            try {
                player.seekTo(player.currentMediaItemIndex, player.currentPosition)
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
        if (core.t >= core.total - 0.02) core.t = 0.0
        val l = core.locate(core.t)
        val g = core.segs[l.i]
        curSeg = l.i
        playing = true
        hint = false
        if (hasVideo) {
            if (g.fz) {
                setSeekFast(false)
                seekPlayerTo(g.src, l.s, 0.03)
                player.pause()
            } else if (g.rev) {
                setSeekFast(true)
                seekPlayerTo(g.src, l.s, 0.02)
                player.pause()
                lastRevSeekNanos = System.nanoTime()
            } else {
                setSeekFast(false)
                seekPlayerTo(g.src, l.s, 0.03)
                player.setPlaybackSpeed(g.sp.toFloat())
                player.play()
            }
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
        if (hasVideo) {
            setSeekFast(false)
            player.pause()
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
        curSeg = i
        val g = core.segs[i]
        if (!hasVideo) return
        if (g.fz) {
            setSeekFast(false)
            player.pause()
            seekPlayerTo(g.src, g.a, 0.03)
            return
        }
        if (g.rev) {
            setSeekFast(true)
            player.pause()
            seekPlayerTo(g.src, g.b, 0.02)
            lastRevSeekNanos = System.nanoTime()
            return
        }
        setSeekFast(false)
        player.setPlaybackSpeed(g.sp.toFloat())
        seekPlayerTo(g.src, g.a, 0.08)
        player.play()
    }

    private fun tick(ts: Long) {
        if (!playing) return
        var dt = (ts - lastNanos) / 1e9
        lastNanos = ts
        if (dt < 0.0) dt = 0.0
        if (dt > 0.1) dt = 0.1
        val segs = core.segs
        if (curSeg >= segs.size) curSeg = segs.size - 1
        val g = segs[curSeg]
        var adv = false
        if (g.fz || !hasVideo) {
            core.t = core.t + dt
            if (core.t >= core.lay.off[curSeg] + g.len - 1e-6) adv = true
        } else if (g.rev) {
            core.t = core.t + dt
            val u = core.t - core.lay.off[curSeg]
            if (u >= g.len - 1e-6) {
                adv = true
            } else {
                val revS = clampD(g.b - u * g.sp, g.a, g.b)
                if (ts - lastRevSeekNanos >= 42_000_000L) {
                    lastRevSeekNanos = ts
                    seekPlayerTo(g.src, revS, 0.02)
                }
            }
        } else {
            if (player.currentMediaItemIndex != g.src) {
                seekPlayerTo(g.src, g.a, 0.02)
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

    /** Awal geseran timeline oleh jari: hentikan putar, pakai seek cepat. */
    fun scrubBegin() {
        pause()
        setSeekFast(true)
    }

    /** Menggeser playhead sebanyak dtSeconds detik timeline. */
    fun scrubBy(dtSeconds: Double) {
        core.setTime(core.t + dtSeconds)
        seekVideoToEdit()
        refreshDerived()
        bump()
    }

    /** Akhir geseran/fling: seek presisi ke posisi akhir. */
    fun scrubEnd() {
        setSeekFast(false)
        seekVideoToEdit()
        refreshDerived()
        bump()
    }

    /** Ketuk di timeline pada waktu tt (detik timeline). */
    fun tapAt(tt: Double) {
        if (tt < 0.0 || tt > core.total) return
        pause()
        setSeekFast(false)
        core.setTime(tt)
        seekVideoToEdit()
        refreshDerived()
        bump()
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

    /** Zoom & pan pratinjau dengan 2 jari (pinch in/out di pratinjau). */
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
        pause()
        setSeekFast(true)
        core.trimBegin(side)
        refreshDerived()
        bump()
    }

    fun trimMove(dxSeconds: Double) {
        core.trimMove(dxSeconds)
        if (hasVideo) {
            val g = core.trimSeg
            if (g != null && !g.fz) {
                val targetSec = if (core.trimmingSide == 1) {
                    if (g.rev) max(g.b - 0.05, g.a) else g.a
                } else {
                    if (g.rev) g.a else max(g.b - 0.05, g.a)
                }
                seekPlayerTo(g.src, targetSec, 0.02)
            }
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
        seekVideoToEdit()
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
        seekVideoToEdit()
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

            Tool.SPEED -> {
                refreshDerived()
                speedSheet = true
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
                seekVideoToEdit()
                bump()
                if (msg != null) toast(msg)
            }

            Tool.ROTATE -> {
                core.rotate()
                refreshDerived()
                bump()
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
                seekVideoToEdit()
                bump()
                if (msg != null) toast(msg)
            }

            Tool.REVERSE -> {
                val msg = core.reverse()
                refreshDerived()
                setSeekFast(false)
                seekVideoToEdit()
                bump()
                toast(msg)
            }

            Tool.TEXT -> {
                pause()
                textSheet = true
            }
        }
    }

    // ------------------------------------------------------------------ kecepatan

    fun setSpeed(v: Double, commit: Boolean) {
        val msg = core.setSpeed(v, commit)
        if (playing && curSeg == core.sel && hasVideo && !core.selReversed) {
            player.setPlaybackSpeed(core.selSpeed.toFloat())
        }
        refreshDerived()
        bump()
        if (msg != null) toast(msg)
    }

    /** Slider selesai digeser: simpan ke riwayat bila ada perubahan. */
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
        seekVideoToEdit()
        bump()
        toast(msg)
    }

    // ------------------------------------------------------------------ teks overlay

    /** Mengubah satu teks overlay (boleh dikosongkan). */
    fun applyText(which: Int, v: String) {
        val cleaned = v.trim()
        if (which == 1) text1 = cleaned else if (which == 2) text2 = cleaned
        bump()
    }

    /** Mengubah kedua teks overlay sekaligus dari sheet Teks Overlay. */
    fun applyBothTexts(newText1: String, newText2: String) {
        text1 = newText1.trim()
        text2 = newText2.trim()
        bump()
    }

    // ------------------------------------------------------------------ catatan (bug & pengembangan)

    private fun loadNotes() {
        val raw = prefs.getString(KEY_NOTES_JSON, null) ?: return
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
            // diabaikan bila format lama tidak valid
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

            val wasEmpty = !hasVideo || sources.isEmpty()
            val startSrcIndex = if (wasEmpty) 0 else sources.size
            val newDurs = probed.map { it.info.durationMs / 1000.0 }

            if (wasEmpty) {
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
                info = sources.first().info
                core.loadVideos(newDurs)
                pps = Rules.PPS0
                resetPreviewZoom()
                hasVideo = true
                hint = false
                curSeg = 0
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

            setSeekFast(false)
            player.setMediaItems(sources.map { MediaItem.fromUri(it.uri) })
            player.prepare()
            player.pause()
            seekVideoToEdit()
            refreshDerived()
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

    /** Mengosongkan semua media dari timeline (kembali ke tampilan awal). */
    fun resetMedia() {
        pause()
        thumbJob?.cancel()
        importJob?.cancel()
        sources.clear()
        sourceThumbs.clear()
        sourceThumbReady.clear()
        player.clearMediaItems()
        hasVideo = false
        info = null
        resetPreviewZoom()
        core.loadVideo(Rules.DEMO_DUR)
        // Kembalikan status belum ada video
        try {
            val f = EditorCore::class.java.getDeclaredField("hasVideo")
            f.isAccessible = true
            f.setBoolean(core, false)
        } catch (e: Exception) {
            // diabaikan
        }
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
                    // thumbnail gagal: timeline tetap bisa dipakai dengan warna polos
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

    /** Thumbnail terdekat untuk sumber [src] dan waktu sumber tertentu (detik), atau null bila belum siap. */
    fun thumbAt(src: Int, time: Double): Bitmap? {
        val arr = sourceThumbs.getOrNull(src) ?: return null
        val ready = sourceThumbReady.getOrNull(src) ?: 0
        if (arr.isEmpty() || ready == 0) return null
        val dur = core.durOf(src)
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

    // ------------------------------------------------------------------ layar penuh

    fun toggleFullscreen() {
        fullscreen = !fullscreen
    }

    // ------------------------------------------------------------------ ekspor

    private fun demoInfo(): VideoInfo = VideoInfo(9, 16, 0, 12200L, 30f, false)

    fun openExport() {
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

    /** Ringkasan untuk sheet ekspor (ukuran, FPS efektif, perkiraan ukuran file, catatan fallback). */
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
        if (!hasVideo || sources.isEmpty()) {
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
            text1 = text1,
            text2 = text2,
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
        private const val PREFS_NOTES = "editor_video_notes"
        private const val KEY_NOTES_JSON = "notes_json"
    }
}
