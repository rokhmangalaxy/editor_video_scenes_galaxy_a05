package com.pribadi.editorvideo.model

import kotlin.math.max
import kotlin.math.min

/**
 * Logika inti editor (tanpa Android/Compose):
 * potongan (mendukung multi-video sumber), playhead, pilihan potongan, riwayat undo/redo,
 * potong, deteksi scene otomatis, beku, kecepatan, duplikat, hapus, putar, cermin,
 * membalikkan (reverse frame render), dan geser handle trim.
 */
class EditorCore {

    /** Daftar durasi tiap video sumber (detik). */
    var srcDurs: List<Double> = listOf(Rules.DEMO_DUR)
        private set

    /** Durasi video sumber pertama (kompatibilitas). */
    val fullDur: Double get() = srcDurs.firstOrNull() ?: Rules.DEMO_DUR

    /** Durasi video sumber ke-[src]. */
    fun durOf(src: Int): Double = srcDurs.getOrElse(src) { fullDur }

    var segs: List<Seg> = listOf(Seg(0.0, Rules.DEMO_DUR))
        private set

    var lay: SegLayout = SegLayout(segs)
        private set

    /** Indeks potongan aktif (yang berada di bawah playhead). */
    var sel: Int = 0

    /** Posisi playhead pada waktu timeline (detik). */
    var t: Double = 0.0

    var rot: Int = 0
        private set

    var flip: Boolean = false
        private set

    var hasVideo: Boolean = false
        private set

    private val hist = ArrayList<Snap>()
    private var hi = -1

    val canUndo: Boolean get() = hi > 0
    val canRedo: Boolean get() = hi < hist.size - 1

    /** Dipanggil saat pemutaran harus dijeda. */
    var onPause: () -> Unit = {}

    // Status geser handle trim.
    private var trimSide = 0
    private var trimIdx = -1
    private var trimOrig: Seg? = null

    /** Potongan yang sedang digeser (hanya bermakna selama handle kiri/kanan diseret). */
    var trimSeg: Seg? = null
        private set

    /** 0 = tidak ada, 1 = handle kiri, 2 = handle kanan. */
    val trimmingSide: Int get() = trimSide
    val trimmingIndex: Int get() = trimIdx

    init {
        calc()
        push()
    }

    val total: Double get() = lay.total

    // ------------------------------------------------------------------ model

    fun calc() {
        lay = SegLayout(segs)
    }

    fun locate(tt: Double): Located = lay.locate(tt)

    private fun layoutSel() {
        if (sel >= segs.size) sel = max(0, segs.size - 1)
    }

    private fun updateSel() {
        val i = locate(t).i
        if (i != sel) {
            sel = i
            layoutSel()
        }
    }

    /** Jepit t lalu perbarui potongan aktif. */
    fun sync() {
        t = clampD(t, 0.0, lay.total)
        updateSel()
    }

    private fun build() {
        calc()
        layoutSel()
        sync()
    }

    // ------------------------------------------------------------------ riwayat

    private fun snap(): Snap = Snap(segs, rot, flip)

    private fun push() {
        while (hist.size > hi + 1) hist.removeAt(hist.size - 1)
        hist.add(snap())
        hi = hist.size - 1
    }

    private fun apply(s: Snap) {
        segs = s.segs
        rot = s.rot
        flip = s.flip
        calc()
        t = min(t, lay.total)
        build()
    }

    fun undo(): Boolean {
        if (hi > 0) {
            onPause()
            hi--
            apply(hist[hi])
            return true
        }
        return false
    }

    fun redo(): Boolean {
        if (hi < hist.size - 1) {
            onPause()
            hi++
            apply(hist[hi])
            return true
        }
        return false
    }

    /** Mengganti isi editor dengan satu video baru. */
    fun loadVideo(durSec: Double) {
        loadVideos(listOf(durSec))
    }

    /** Mengganti isi editor dengan satu atau beberapa video baru sekaligus (multiseleksi). */
    fun loadVideos(durs: List<Double>) {
        if (durs.isEmpty()) return
        srcDurs = ArrayList(durs)
        segs = durs.mapIndexed { idx, dur -> Seg(0.0, dur, src = idx) }
        hasVideo = true
        t = 0.0
        sel = 0
        rot = 0
        flip = false
        hist.clear()
        hi = -1
        push()
        build()
    }

    /** Menambahkan satu atau beberapa video baru ke akhir timeline yang sudah ada. */
    fun appendVideos(newDurs: List<Double>) {
        if (newDurs.isEmpty()) return
        if (!hasVideo) {
            loadVideos(newDurs)
            return
        }
        onPause()
        val baseSrc = srcDurs.size
        val updatedDurs = ArrayList(srcDurs)
        updatedDurs.addAll(newDurs)
        srcDurs = updatedDurs

        val firstNewSegIdx = segs.size
        val updatedSegs = ArrayList(segs)
        for (i in newDurs.indices) {
            updatedSegs.add(Seg(0.0, newDurs[i], src = baseSrc + i))
        }
        segs = updatedSegs
        calc()
        sel = firstNewSegIdx
        t = lay.off[firstNewSegIdx]
        push()
        build()
    }

    // ------------------------------------------------------------------ alat

    fun split(): String? {
        val L = locate(t)
        val g = segs[L.i]
        if (g.fz) return "Potongan beku tidak bisa dipotong"
        if (L.s - g.a < 0.1 * g.sp || g.b - L.s < 0.1 * g.sp) return "Geser playhead ke dalam klip dulu"
        onPause()
        val n = ArrayList(segs)
        n[L.i] = g.copy(b = L.s)
        n.add(L.i + 1, g.copy(a = L.s))
        segs = n
        sel = L.i + 1
        push()
        build()
        return "Klip dipotong di " + Fmt.time(t)
    }

    /**
     * Memecah potongan ke-[segIdx] menjadi beberapa bagian sesuai hasil Deteksi Scene.
     */
    fun applySceneSplits(segIdx: Int, ranges: List<Pair<Double, Double>>): String {
        if (segIdx !in segs.indices || ranges.isEmpty()) return "Tidak ada scene untuk diterapkan"
        val g = segs[segIdx]
        if (g.fz) return "Potongan beku tidak bisa dipecah"
        onPause()
        val created = ranges.mapNotNull { (sa, sb) ->
            val a = clampD(sa, g.a, g.b)
            val b = clampD(sb, g.a, g.b)
            if (b - a >= Rules.MIN) g.copy(a = a, b = b) else null
        }
        if (created.isEmpty()) return "Rentang scene terlalu pendek"
        val n = ArrayList<Seg>(segs.size - 1 + created.size)
        for (i in segs.indices) {
            if (i == segIdx) {
                n.addAll(created)
            } else {
                n.add(segs[i])
            }
        }
        segs = n
        calc()
        sel = segIdx.coerceIn(0, segs.size - 1)
        t = lay.off[sel]
        push()
        build()
        return "Video dipecah menjadi ${created.size} bagian scene"
    }

    fun freezeFrame(): String? {
        onPause()
        val L = locate(t)
        val g = segs[L.i]
        if (g.fz) return "Playhead berada di potongan beku"
        val s = L.s
        val edge = 0.1 * g.sp
        val fz = Seg.freezeAt(s, g.src)
        val n = ArrayList(segs)
        val idx: Int
        if (s - g.a < edge) {
            n.add(L.i, fz)
            idx = L.i
        } else if (g.b - s < edge) {
            n.add(L.i + 1, fz)
            idx = L.i + 1
        } else {
            n[L.i] = g.copy(b = s)
            n.add(L.i + 1, fz)
            n.add(L.i + 2, g.copy(a = s))
            idx = L.i + 1
        }
        segs = n
        sel = idx
        calc()
        t = lay.off[idx]
        push()
        build()
        return "Frame dibekukan " + Fmt.jsNum(Rules.FZ_DUR) + " detik"
    }

    fun delete(): String? {
        if (segs.size < 2) return "Tidak bisa menghapus satu-satunya klip"
        onPause()
        val n = ArrayList(segs)
        n.removeAt(sel)
        segs = n
        calc()
        sel = min(sel, segs.size - 1)
        t = lay.off[sel]
        push()
        build()
        return "Potongan dihapus"
    }

    fun duplicate(): String? {
        onPause()
        val n = ArrayList(segs)
        n.add(sel + 1, segs[sel])
        segs = n
        push()
        build()
        return "Potongan diduplikat"
    }

    fun rotate() {
        rot = (rot + 90) % 360
        push()
    }

    fun mirror(): String {
        flip = !flip
        push()
        return if (flip) "Cermin kiri-kanan aktif" else "Cermin dimatikan"
    }

    /** Status apakah potongan terpilih saat ini adalah hasil balik urutan frame. */
    val selReversed: Boolean get() = segs.getOrNull(sel)?.rev ?: false

    /**
     * Mengganti potongan ke-[segIdx] dengan sumber video baru hasil render balik urutan frame.
     */
    fun applyRenderedReverse(segIdx: Int, newSrcIdx: Int, revDurSec: Double): String {
        if (segIdx !in segs.indices) return "Potongan tidak ditemukan"
        onPause()
        val g = segs[segIdx]
        val updatedDurs = ArrayList(srcDurs)
        while (updatedDurs.size <= newSrcIdx) {
            updatedDurs.add(revDurSec)
        }
        updatedDurs[newSrcIdx] = revDurSec
        srcDurs = updatedDurs

        val revSeg = Seg(
            a = 0.0,
            b = revDurSec,
            sp = g.sp,
            fz = false,
            d = 0.0,
            rev = true,
            src = newSrcIdx,
            origSrc = if (g.origSrc >= 0) g.origSrc else g.src,
            origA = if (g.origSrc >= 0) g.origA else g.a,
            origB = if (g.origSrc >= 0) g.origB else g.b
        )
        val n = ArrayList(segs)
        n[segIdx] = revSeg
        segs = n
        calc()
        sel = segIdx
        t = lay.off[segIdx]
        push()
        build()
        return "Urutan frame potongan berhasil dibalik"
    }

    /** Mengembalikan potongan yang sudah dibalik ke video sumber aslinya (arah normal). */
    fun restoreFromReverse(segIdx: Int): String {
        if (segIdx !in segs.indices) return "Potongan tidak ditemukan"
        val g = segs[segIdx]
        if (!g.rev || g.origSrc < 0) return "Potongan sudah normal"
        onPause()
        val restored = Seg(
            a = g.origA,
            b = if (g.origB > g.origA) g.origB else durOf(g.origSrc),
            sp = g.sp,
            fz = false,
            d = 0.0,
            rev = false,
            src = g.origSrc
        )
        val n = ArrayList(segs)
        n[segIdx] = restored
        segs = n
        calc()
        sel = segIdx
        t = lay.off[segIdx]
        push()
        build()
        return "Arah potongan dikembalikan ke normal"
    }

    // ------------------------------------------------------------------ kecepatan

    /** Kecepatan potongan aktif. */
    val selSpeed: Double get() = segs.getOrNull(sel)?.sp ?: 1.0

    fun setSpeed(v0: Double, commit: Boolean): String? {
        val g = segs.getOrNull(sel) ?: return null
        var v = clampD(jsRound(v0 * 10.0) / 10.0, Rules.SPEED_MIN, Rules.SPEED_MAX)
        var msg: String? = null
        val cap = g.maxSpeed
        if (v > cap) {
            v = cap
            msg = "Durasi potongan minimal " + Fmt.jsNum(Rules.MIN) +
                " detik, kecepatan dibatasi ke " + Fmt.jsNum(jsRound(cap * 100.0) / 100.0) + "x"
        }
        val L = locate(t)
        val frac: Double? = if (L.i == sel) min(0.999, (t - lay.off[sel]) / g.len) else null
        val n = ArrayList(segs)
        n[sel] = g.copy(sp = v)
        segs = n
        calc()
        if (frac != null) t = lay.off[sel] + frac * segs[sel].len
        layoutSel()
        sync()
        if (commit) push()
        return msg
    }

    /** Mendorong riwayat setelah slider kecepatan selesai digeser (hanya bila ada perubahan). */
    fun commitIfChanged() {
        if (hi < 0 || hist[hi].segs != segs || hist[hi].rot != rot || hist[hi].flip != flip) push()
    }

    /** Teks dialog konfirmasi "Terapkan ke semua potongan". */
    fun applyAllText(): String {
        val g = segs[sel]
        return "Kecepatan " + Fmt.one(g.sp) + "x akan diterapkan ke semua " + segs.size +
            " potongan (termasuk potongan beku). Durasi tiap potongan akan berubah (minimal " +
            Fmt.jsNum(Rules.MIN) + " detik)."
    }

    fun applySpeedAll(): String {
        val v = segs[sel].sp
        val gi = locate(t).i
        val frac = min(0.999, (t - lay.off[gi]) / segs[gi].len)
        var capped = 0
        val n = ArrayList<Seg>(segs.size)
        for (s in segs) {
            val c = s.maxSpeed
            if (v > c) {
                n.add(s.copy(sp = c))
                capped++
            } else {
                n.add(s.copy(sp = v))
            }
        }
        segs = n
        calc()
        t = lay.off[gi] + frac * segs[gi].len
        push()
        build()
        return if (capped > 0) {
            capped.toString() + " potongan dibatasi ke durasi minimal " + Fmt.jsNum(Rules.MIN) + " detik"
        } else {
            "Kecepatan " + Fmt.one(v) + "x diterapkan ke semua potongan"
        }
    }

    // ------------------------------------------------------------------ trim (handle kiri/kanan)

    /** Mulai menggeser handle. side: 1 = kiri, 2 = kanan. */
    fun trimBegin(side: Int) {
        onPause()
        val g = segs[sel]
        trimIdx = sel
        trimOrig = g
        trimSeg = g
        trimSide = side
    }

    /** dx = jarak geser dalam detik timeline sejak awal sentuhan. */
    fun trimMove(dx: Double) {
        val g0 = trimOrig ?: return
        val i = trimIdx
        val maxDur = durOf(g0.src)
        if (trimSide == 1) {
            trimSeg = if (g0.fz) {
                g0.copy(d = clampD(g0.d - dx * g0.sp, Rules.MIN * g0.sp, Rules.FZ_MAX))
            } else {
                g0.copy(a = clampD(g0.a + dx * g0.sp, 0.0, g0.b - Rules.MIN * g0.sp))
            }
        } else if (trimSide == 2) {
            val ng = if (g0.fz) {
                g0.copy(d = clampD(g0.d + dx * g0.sp, Rules.MIN * g0.sp, Rules.FZ_MAX))
            } else {
                g0.copy(b = clampD(g0.b + dx * g0.sp, g0.a + Rules.MIN * g0.sp, maxDur))
            }
            val n = ArrayList(segs)
            n[i] = ng
            segs = n
            trimSeg = ng
            calc()
        }
    }

    /** Membatalkan geser handle (mis. saat jari kedua menyentuh layar untuk pinch-zoom). */
    fun trimCancel() {
        val orig = trimOrig ?: return
        val i = trimIdx
        if (i in segs.indices && segs[i] != orig) {
            val n = ArrayList(segs)
            n[i] = orig
            segs = n
        }
        trimSide = 0
        trimIdx = -1
        trimOrig = null
        trimSeg = null
        calc()
        sync()
    }

    /** Selesai menggeser handle. Mengembalikan true bila potongan berubah. */
    fun trimEnd(): Boolean {
        val orig = trimOrig ?: return false
        val i = trimIdx
        if (trimSide == 1) {
            val ng = trimSeg
            if (ng != null) {
                val n = ArrayList(segs)
                n[i] = ng
                segs = n
            }
        }
        val changed = segs[i] != orig
        trimSide = 0
        trimIdx = -1
        trimOrig = null
        trimSeg = null
        calc()
        sel = i
        layoutSel()
        t = clampD(t, lay.off[i], lay.off[i] + segs[i].len - 0.01)
        sync()
        if (changed) push()
        return changed
    }

    // ------------------------------------------------------------------ playhead

    /** Mengatur posisi playhead (scrub/ketuk) lalu menyinkronkan potongan aktif. */
    fun setTime(newT: Double) {
        t = newT
        sync()
    }

    /** Bantuan: durasi total, minimal 0 — dipakai untuk batas scrub. */
    fun maxT(): Double = max(0.0, lay.total)
}
