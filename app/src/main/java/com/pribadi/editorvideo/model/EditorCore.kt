package com.pribadi.editorvideo.model

import kotlin.math.max
import kotlin.math.min

/**
 * Logika inti editor (tanpa Android/Compose):
 * Di awal kosong (segs = emptyList(), hasVideo = false).
 * Mendukung multi-video sumber, playhead, pilihan potongan, riwayat undo/redo,
 * potong, deteksi scene otomatis, beku, kecepatan, duplikat, hapus, putar, cermin,
 * membalikkan (opsi Mundur & Loop normal+mundur), dan geser handle trim.
 */
class EditorCore {

    /** Daftar durasi tiap video sumber (detik). Di awal kosong. */
    var srcDurs: List<Double> = emptyList()
        private set

    /** Durasi video sumber pertama. */
    val fullDur: Double get() = srcDurs.firstOrNull() ?: 0.0

    /** Durasi video sumber ke-[src]. */
    fun durOf(src: Int): Double = srcDurs.getOrElse(src) { fullDur }

    /** Daftar potongan di timeline. Di awal kosong (sebelum ada video). */
    var segs: List<Seg> = emptyList()
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

    val canUndo: Boolean get() = hasVideo && hi > 0
    val canRedo: Boolean get() = hasVideo && hi < hist.size - 1

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

    val total: Double get() = lay.total

    // ------------------------------------------------------------------ model

    fun calc() {
        lay = SegLayout(segs)
    }

    fun locate(tt: Double): Located = lay.locate(tt)

    private fun layoutSel() {
        sel = if (segs.isEmpty()) 0 else sel.coerceIn(0, segs.size - 1)
    }

    private fun updateSel() {
        if (segs.isEmpty()) {
            sel = 0
            return
        }
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
        if (hasVideo && hi > 0) {
            onPause()
            hi--
            apply(hist[hi])
            return true
        }
        return false
    }

    fun redo(): Boolean {
        if (hasVideo && hi < hist.size - 1) {
            onPause()
            hi++
            apply(hist[hi])
            return true
        }
        return false
    }

    /** Mengosongkan timeline sepenuhnya ke kondisi awal (kosong). */
    fun clear() {
        onPause()
        trimSide = 0
        trimIdx = -1
        trimOrig = null
        trimSeg = null
        srcDurs = emptyList()
        segs = emptyList()
        hasVideo = false
        t = 0.0
        sel = 0
        rot = 0
        flip = false
        hist.clear()
        hi = -1
        calc()
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
        if (!hasVideo || segs.isEmpty()) {
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
        if (!hasVideo || segs.isEmpty()) return "Impor video dulu"
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
        if (!hasVideo || segIdx !in segs.indices || ranges.isEmpty()) return "Tidak ada scene untuk diterapkan"
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
        if (!hasVideo || segs.isEmpty()) return "Impor video dulu"
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
        if (!hasVideo || segs.isEmpty()) return "Impor video dulu"
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
        if (!hasVideo || segs.isEmpty()) return "Impor video dulu"
        onPause()
        val n = ArrayList(segs)
        n.add(sel + 1, segs[sel])
        segs = n
        push()
        build()
        return "Potongan diduplikat"
    }

    fun rotate(): String? {
        if (!hasVideo || segs.isEmpty()) return "Impor video dulu"
        rot = (rot + 90) % 360
        push()
        return null
    }

    fun mirror(): String {
        if (!hasVideo || segs.isEmpty()) return "Impor video dulu"
        flip = !flip
        push()
        return if (flip) "Cermin kiri-kanan aktif" else "Cermin dimatikan"
    }

    /** Status apakah potongan terpilih saat ini adalah hasil Mundur atau Loop. */
    val selReversed: Boolean get() = segs.getOrNull(sel)?.rev ?: false

    /** Mode membalikkan pada potongan terpilih: 0 = Normal, 1 = Mundur, 2 = Loop. */
    val selRevMode: Int get() = segs.getOrNull(sel)?.revMode ?: Seg.REV_NONE

    /**
     * Mengganti potongan ke-[segIdx] dengan sumber video baru hasil render Mundur atau Loop (Normal + Mundur).
     */
    fun applyRenderedReverse(segIdx: Int, newSrcIdx: Int, revDurSec: Double, mode: Int): String {
        if (!hasVideo || segIdx !in segs.indices) return "Potongan tidak ditemukan"
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
            rev = mode != Seg.REV_NONE,
            revMode = mode,
            src = newSrcIdx,
            origSrc = if (g.origSrc >= 0) g.origSrc else g.src,
            origA = if (g.origSrc >= 0) g.origA else g.a,
            origB = if (g.origSrc >= 0) g.origB else g.b,
            tags = g.tags
        )
        val n = ArrayList(segs)
        n[segIdx] = revSeg
        segs = n
        calc()
        sel = segIdx
        t = lay.off[segIdx]
        push()
        build()
        return if (mode == Seg.REV_LOOP) {
            "Video Loop (Normal + Mundur) berhasil dibuat"
        } else {
            "Video Mundur berhasil dibuat"
        }
    }

    /** Mengembalikan potongan yang sudah dibalik / di-loop ke video sumber aslinya (arah normal). */
    fun restoreFromReverse(segIdx: Int): String {
        if (!hasVideo || segIdx !in segs.indices) return "Potongan tidak ditemukan"
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
            revMode = Seg.REV_NONE,
            src = g.origSrc,
            tags = g.tags
        )
        val n = ArrayList(segs)
        n[segIdx] = restored
        segs = n
        calc()
        sel = segIdx
        t = lay.off[segIdx]
        push()
        build()
        return "Potongan dikembalikan ke video normal"
    }

    // ------------------------------------------------------------------ tagging & aksi multiseleksi grid

    /** Menambah atau melepas [tag] pada kumpulan potongan [indices]. */
    fun toggleTagOnSegments(indices: Set<Int>, tag: String): String {
        val clean = tag.trim()
        if (!hasVideo || segs.isEmpty() || clean.isEmpty()) return "Tag tidak valid"
        val valid = indices.filter { it in segs.indices }
        if (valid.isEmpty()) return "Pilih potongan terlebih dahulu"
        val allHave = valid.all { clean in segs[it].tags }
        val n = ArrayList(segs)
        for (idx in valid) {
            val g = n[idx]
            val updated = if (allHave) {
                g.tags.filterNot { it == clean }
            } else {
                if (clean in g.tags) g.tags else g.tags + clean
            }
            n[idx] = g.copy(tags = updated)
        }
        segs = n
        push()
        build()
        return if (allHave) {
            "Tag \"$clean\" dilepas dari ${valid.size} potongan"
        } else {
            "Tag \"$clean\" dipasang ke ${valid.size} potongan"
        }
    }

    /** Menghapus semua tag dari kumpulan potongan [indices]. */
    fun clearTagsOnSegments(indices: Set<Int>): String {
        if (!hasVideo || segs.isEmpty()) return "Impor video dulu"
        val valid = indices.filter { it in segs.indices && segs[it].tags.isNotEmpty() }
        if (valid.isEmpty()) return "Potongan terpilih belum memiliki tag"
        val n = ArrayList(segs)
        for (idx in valid) {
            n[idx] = n[idx].copy(tags = emptyList())
        }
        segs = n
        push()
        build()
        return "Tag dihapus dari ${valid.size} potongan"
    }

    /** Menerapkan hasil import tagging ke potongan-potongan timeline (mendukung Undo/Redo). */
    fun applyImportedSegmentTags(
        tagsBySegIndex: Map<Int, List<String>>,
        replaceExisting: Boolean
    ): Int {
        if (!hasVideo || segs.isEmpty()) return 0
        val n = ArrayList(segs)
        var changed = 0
        for (idx in n.indices) {
            val g = n[idx]
            val incoming = tagsBySegIndex[idx]?.map { it.trim() }?.filter { it.isNotEmpty() }
            val nextTags = if (replaceExisting) {
                (incoming ?: emptyList()).distinct()
            } else {
                if (incoming.isNullOrEmpty()) g.tags else (g.tags + incoming).distinct()
            }
            if (nextTags != g.tags) {
                n[idx] = g.copy(tags = nextTags)
                changed++
            }
        }
        if (changed > 0) {
            segs = n
            push()
            build()
        }
        return changed
    }

    /** Mendaftarkan durasi sumber-sumber video baru (mis. MP4 hasil AI) ke srcDurs. */
    fun registerAddedSourceDurs(added: List<Pair<Int, Double>>) {
        if (added.isEmpty()) return
        val updated = ArrayList(srcDurs)
        for ((srcIdx, durSec) in added) {
            val safeDur = max(Rules.MIN, durSec)
            while (updated.size <= srcIdx) {
                updated.add(safeDur)
            }
            updated[srcIdx] = safeDur
        }
        srcDurs = updated
    }

    /**
     * Menyisipkan 1..N potongan hasil AI tepat pada posisi waktu timeline [insertTimeSec]
     * (membelah klip di posisi seek secara otomatis bila berada di tengah klip, mendukung Undo/Redo).
     * Mengembalikan pesan status untuk toast.
     */
    fun insertAiSegmentsAtTime(
        insertTimeSec: Double,
        newSegSpecs: List<Triple<Int, Double, Double>>,
        tagName: String = "AI"
    ): String {
        if (newSegSpecs.isEmpty()) return "Tidak ada hasil AI yang dipilih"
        onPause()

        val aiSegs = newSegSpecs.map { (srcIdx, fullSrcDur, useDur) ->
            val endB = clampD(useDur, Rules.MIN, max(Rules.MIN, fullSrcDur))
            Seg(
                a = 0.0,
                b = endB,
                sp = 1.0,
                fz = false,
                d = 0.0,
                src = srcIdx,
                tags = listOf(tagName)
            )
        }

        if (!hasVideo || segs.isEmpty()) {
            hasVideo = true
            segs = aiSegs
            calc()
            sel = 0
            t = 0.0
            push()
            build()
            return "${aiSegs.size} video AI ditambahkan ke timeline"
        }

        val clampedT = clampD(insertTimeSec, 0.0, lay.total)
        val L = locate(clampedT)
        val g = segs[L.i]
        val leftTimelineLen = L.u
        val rightTimelineLen = g.len - L.u

        val n = ArrayList<Seg>(segs.size + aiSegs.size + 1)
        var insertedFirstIdx = L.i

        for (k in segs.indices) {
            if (k != L.i) {
                n.add(segs[k])
                continue
            }
            when {
                leftTimelineLen < Rules.MIN -> {
                    insertedFirstIdx = n.size
                    n.addAll(aiSegs)
                    n.add(g)
                }
                rightTimelineLen < Rules.MIN -> {
                    n.add(g)
                    insertedFirstIdx = n.size
                    n.addAll(aiSegs)
                }
                g.fz -> {
                    val leftPart = g.copy(d = max(Rules.MIN * g.sp, leftTimelineLen * g.sp))
                    val rightPart = g.copy(d = max(Rules.MIN * g.sp, rightTimelineLen * g.sp))
                    n.add(leftPart)
                    insertedFirstIdx = n.size
                    n.addAll(aiSegs)
                    n.add(rightPart)
                }
                else -> {
                    val splitSrc = clampD(L.s, g.a + Rules.MIN * g.sp, g.b - Rules.MIN * g.sp)
                    val leftPart = g.copy(b = splitSrc)
                    val rightPart = g.copy(a = splitSrc)
                    n.add(leftPart)
                    insertedFirstIdx = n.size
                    n.addAll(aiSegs)
                    n.add(rightPart)
                }
            }
        }

        segs = n
        calc()
        sel = insertedFirstIdx.coerceIn(0, segs.size - 1)
        t = lay.off[sel]
        push()
        build()
        return "${aiSegs.size} video AI disisipkan di ${Fmt.time(clampedT)}"
    }

    /**
     * Mengganti (Replace Scene) potongan ke-[targetSegIdx] dengan 1..N potongan hasil AI.
     * Bila potongan asli melebihi batas 10.0 detik ZeroGPU ([keepOverflowFromSec] > 0),
     * sisa durasi potongan setelah [keepOverflowFromSec] dipertahankan utuh di sebelah kanannya.
     */
    fun replaceSceneWithAiSegments(
        targetSegIdx: Int,
        newSegSpecs: List<Triple<Int, Double, Double>>,
        keepOverflowFromSec: Double?,
        tagName: String = "AI"
    ): String {
        if (!hasVideo || segs.isEmpty() || newSegSpecs.isEmpty()) return "Potongan tidak ditemukan"
        onPause()
        val idx = targetSegIdx.coerceIn(0, segs.size - 1)
        val orig = segs[idx]
        val mergedTags = (orig.tags + tagName).distinct()

        val aiSegs = newSegSpecs.map { (srcIdx, fullSrcDur, useDur) ->
            val endB = clampD(useDur, Rules.MIN, max(Rules.MIN, fullSrcDur))
            Seg(
                a = 0.0,
                b = endB,
                sp = 1.0,
                fz = false,
                d = 0.0,
                src = srcIdx,
                tags = mergedTags
            )
        }

        val n = ArrayList<Seg>(segs.size + aiSegs.size)
        for (k in segs.indices) {
            if (k != idx) {
                n.add(segs[k])
                continue
            }
            n.addAll(aiSegs)
            if (keepOverflowFromSec != null && orig.len - keepOverflowFromSec >= Rules.MIN) {
                if (orig.fz) {
                    val remD = max(Rules.MIN * orig.sp, (orig.len - keepOverflowFromSec) * orig.sp)
                    n.add(orig.copy(d = remD))
                } else {
                    val nextA = clampD(orig.a + keepOverflowFromSec * orig.sp, orig.a, orig.b - Rules.MIN * orig.sp)
                    n.add(orig.copy(a = nextA))
                }
            }
        }

        segs = n
        calc()
        sel = idx.coerceIn(0, segs.size - 1)
        t = lay.off[sel]
        push()
        build()
        val totalAiSec = aiSegs.sumOf { it.len }
        return if (keepOverflowFromSec != null && orig.len - keepOverflowFromSec >= Rules.MIN) {
            "Scene #${idx + 1}: ${Fmt.one(totalAiSec)}s diganti AI, sisa dipertahankan"
        } else {
            "Scene #${idx + 1} berhasil diganti dengan video AI (${Fmt.one(totalAiSec)} dtk)"
        }
    }

    /** Menghapus beberapa potongan sekaligus (menyisakan minimal 1 potongan di timeline). */
    fun deleteSegments(indices: Set<Int>): String {
        if (!hasVideo || segs.isEmpty()) return "Impor video dulu"
        if (segs.size < 2) return "Tidak bisa menghapus satu-satunya klip"
        val valid = indices.filter { it in segs.indices }.sorted()
        if (valid.isEmpty()) return "Tidak ada potongan dipilih"
        onPause()
        val toRemove = if (valid.size >= segs.size) {
            valid.drop(1).toSet()
        } else {
            valid.toSet()
        }
        val n = ArrayList<Seg>(segs.size - toRemove.size)
        for (i in segs.indices) {
            if (i !in toRemove) n.add(segs[i])
        }
        segs = n
        calc()
        sel = sel.coerceIn(0, segs.size - 1)
        t = lay.off[sel]
        push()
        build()
        return "${toRemove.size} potongan dihapus"
    }

    /** Memecah potongan ke-[segIdx] menjadi [parts] bagian sama rata untuk memudahkan kelola di Mode Grid. */
    fun splitIntoEqualParts(segIdx: Int, parts: Int): String {
        if (!hasVideo || segIdx !in segs.indices) return "Impor video dulu"
        val g = segs[segIdx]
        if (g.fz) return "Potongan beku tidak bisa dipecah"
        val targetParts = parts.coerceIn(2, 36)
        val span = g.b - g.a
        val step = span / targetParts.toDouble()
        if (step < Rules.MIN) return "Durasi klip terlalu pendek untuk dibagi $targetParts bagian"
        onPause()
        val created = ArrayList<Seg>(targetParts)
        for (k in 0 until targetParts) {
            val sa = g.a + k * step
            val sb = if (k == targetParts - 1) g.b else g.a + (k + 1) * step
            created.add(g.copy(a = sa, b = sb))
        }
        val n = ArrayList<Seg>(segs.size - 1 + created.size)
        for (i in segs.indices) {
            if (i == segIdx) n.addAll(created) else n.add(segs[i])
        }
        segs = n
        calc()
        sel = segIdx.coerceIn(0, segs.size - 1)
        t = lay.off[sel]
        push()
        build()
        return "Klip dibagi menjadi $targetParts potongan di Grid"
    }

    // ------------------------------------------------------------------ kecepatan

    /** Kecepatan potongan aktif. */
    val selSpeed: Double get() = segs.getOrNull(sel)?.sp ?: 1.0

    fun setSpeed(v0: Double, commit: Boolean): String? {
        if (!hasVideo || segs.isEmpty()) return "Impor video dulu"
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
        if (!hasVideo || segs.isEmpty()) return
        if (hi < 0 || hist[hi].segs != segs || hist[hi].rot != rot || hist[hi].flip != flip) push()
    }

    /** Teks dialog konfirmasi "Terapkan ke semua potongan". */
    fun applyAllText(): String {
        val g = segs.getOrNull(sel) ?: return ""
        return "Kecepatan " + Fmt.one(g.sp) + "x akan diterapkan ke semua " + segs.size +
            " potongan (termasuk potongan beku). Durasi tiap potongan akan berubah (minimal " +
            Fmt.jsNum(Rules.MIN) + " detik)."
    }

    fun applySpeedAll(): String {
        if (!hasVideo || segs.isEmpty()) return "Impor video dulu"
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
        if (!hasVideo || segs.isEmpty()) return
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
        if (i !in segs.indices) return
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
        if (i !in segs.indices) {
            trimSide = 0
            trimIdx = -1
            trimOrig = null
            trimSeg = null
            return false
        }
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
        t = clampD(t, lay.off[i], max(lay.off[i], lay.off[i] + segs[i].len - 0.01))
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
