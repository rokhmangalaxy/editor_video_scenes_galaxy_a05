package com.pribadi.editorvideo.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Konstanta aturan editor. */
object Rules {
    /** Durasi minimal potongan (detik). */
    const val MIN = 0.1

    /** Durasi dasar potongan beku saat dibuat (detik). */
    const val FZ_DUR = 3.0

    /** Durasi dasar maksimum potongan beku (detik). */
    const val FZ_MAX = 30.0

    /** Zoom awal timeline (dp per detik). */
    const val PPS0 = 56.0
    const val PPS_MIN = 4.0
    const val PPS_MAX = 400.0

    /** Lebar tile thumbnail (dp). */
    const val TILE = 64.0

    const val SPEED_MIN = 0.1
    const val SPEED_MAX = 9.0

    /** Durasi fallback bila diperlukan untuk metadata kosong. */
    const val DEMO_DUR = 12.2
}

fun clampD(v: Double, a: Double, b: Double): Double = min(b, max(a, v))

/** Pembulatan ala JavaScript Math.round (setengah dibulatkan ke atas). */
fun jsRound(v: Double): Double = floor(v + 0.5)

/**
 * Konfigurasi kustomisasi kaya untuk satu layer teks overlay.
 * Default text = "" (kosong).
 */
data class TextOverlayConfig(
    val text: String = "",
    val sizeScale: Float = 1.0f,
    val posX: Float = 0.5f,
    val posY: Float = 0.12f,
    val fontIndex: Int = 0,
    val alignIndex: Int = 1,
    val fillColor: Int = 0xFFFFE45C.toInt(),
    val strokeColor: Int = 0xFF4A2FB0.toInt(),
    val strokeScale: Float = 1.0f,
    val bgColor: Int = 0x00000000,
    val shadow: Boolean = true
) {
    val isNotEmpty: Boolean get() = text.isNotEmpty()

    companion object {
        fun defaultTop(): TextOverlayConfig = TextOverlayConfig(
            text = "",
            sizeScale = 1.0f,
            posX = 0.5f,
            posY = 0.12f,
            fontIndex = 0,
            alignIndex = 1,
            fillColor = 0xFFFFE45C.toInt(),
            strokeColor = 0xFF4A2FB0.toInt(),
            strokeScale = 1.0f,
            bgColor = 0x00000000,
            shadow = true
        )

        fun defaultBottom(): TextOverlayConfig = TextOverlayConfig(
            text = "",
            sizeScale = 1.0f,
            posX = 0.5f,
            posY = 0.88f,
            fontIndex = 0,
            alignIndex = 1,
            fillColor = 0xFFFFFFFF.toInt(),
            strokeColor = 0xFFF2C22A.toInt(),
            strokeScale = 0.85f,
            bgColor = 0x00000000,
            shadow = true
        )
    }
}

/**
 * Satu potongan di timeline.
 *  - klip   : {a, b, sp, rev, revMode, src, origSrc, origA, origB}
 *             revMode: 0 = Normal, 1 = Mundur, 2 = Loop (Normal + Mundur)
 *  - beku   : {fz=true, a, d, sp, src}
 * Durasi di timeline = (b - a) / sp  atau  d / sp.
 */
data class Seg(
    val a: Double,
    val b: Double,
    val sp: Double = 1.0,
    val fz: Boolean = false,
    val d: Double = 0.0,
    val rev: Boolean = false,
    val revMode: Int = 0,
    val src: Int = 0,
    val origSrc: Int = -1,
    val origA: Double = 0.0,
    val origB: Double = 0.0,
    val tags: List<String> = emptyList(),
    val rot: Int = 0
) {
    /** Durasi dasar (sebelum dibagi kecepatan). */
    val baseLen: Double get() = if (fz) d else b - a

    /** Durasi di timeline. */
    val len: Double get() = baseLen / sp

    /** Kecepatan maksimum agar durasi potongan tidak kurang dari Rules.MIN detik. */
    val maxSpeed: Double get() = min(Rules.SPEED_MAX, baseLen / Rules.MIN)

    companion object {
        const val REV_NONE = 0
        const val REV_MUNDUR = 1
        const val REV_LOOP = 2

        fun freezeAt(time: Double, src: Int = 0, rot: Int = 0): Seg =
            Seg(
                a = time,
                b = time,
                sp = 1.0,
                fz = true,
                d = Rules.FZ_DUR,
                rev = false,
                revMode = REV_NONE,
                src = src,
                rot = rot
            )
    }
}

data class Located(val i: Int, val u: Double, val s: Double)

/** Susunan potongan: offset awal tiap potongan dan total durasi. */
class SegLayout(val segs: List<Seg>) {
    val off = DoubleArray(segs.size)
    val total: Double

    init {
        var o = 0.0
        for (i in segs.indices) {
            off[i] = o
            o += segs[i].len
        }
        total = o
    }

    /** Mencari potongan pada waktu timeline tt dan waktu sumber yang sesuai. */
    fun locate(tt: Double): Located {
        if (segs.isEmpty()) return Located(0, 0.0, 0.0)
        for (i in segs.indices) {
            val g = segs[i]
            val l = g.len
            if (tt < off[i] + l || i == segs.size - 1) {
                val u = clampD(tt - off[i], 0.0, l)
                val s = if (g.fz) g.a else clampD(g.a + u * g.sp, g.a, g.b)
                return Located(i, u, s)
            }
        }
        return Located(0, 0.0, 0.0)
    }
}

/**
 * Tanda (Marker) yang dikaitkan ke sumber video & detik sumber [srcSec]
 * sehingga tetap akurat walau potongan di bagian lain dipotong/digeser, serta mendukung Undo/Redo.
 */
data class ClipMarker(
    val id: Long,
    val src: Int,
    val srcSec: Double,
    val label: String = ""
)

/** Proyeksi Tanda ke posisi waktu timeline saat ini untuk digambar di Timeline & navigasi. */
data class ProjectedMarker(
    val markerId: Long,
    val timelineSec: Double,
    val segIndex: Int,
    val src: Int,
    val srcSec: Double,
    val label: String
)

/**
 * Rencana penggantian 1 scene dengan hasil AI (dipakai untuk Replace Multi-Scene Berurutan).
 * [newSegSpecs] berisi Triple(srcIdx, durasiPenuhSumber, durasiDipakai).
 */
data class SceneAiReplace(
    val segIdx: Int,
    val newSegSpecs: List<Triple<Int, Double, Double>>,
    val keepOverflowFromSec: Double? = null
)

/**
 * Mode tampilan sebuah OverlayClip di atas track utama (multilayer).
 * Fase 1 hanya mendukung tampilan penuh & PiP sudut; split-half menyusul.
 */
enum class OverlayMode(val label: String, val shortLabel: String) {
    REPLACE_VISUAL("Replace Visual (Penuh)", "Penuh"),
    PIP_CORNER("Picture-in-Picture (Sudut)", "PiP"),
    SPLIT_HALF("Belah Layar (Kiri/Kanan)", "Split")
}

/** Posisi & ukuran overlay dalam koordinat ternormalisasi (0..1) terhadap kanvas pratinjau. */
data class OverlayRect(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val w: Double = 1.0,
    val h: Double = 1.0,
    val opacity: Double = 1.0,
    val corner: Int = 0
) {
    fun clamped(): OverlayRect = OverlayRect(
        x = clamp01(x),
        y = clamp01(y),
        w = w.coerceIn(0.12, 1.0),
        h = h.coerceIn(0.12, 1.0),
        opacity = opacity.coerceIn(0.0, 1.0),
        corner = corner.coerceIn(0, 3)
    )

    private fun clamp01(v: Double): Double = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v

    companion object {
        /** PiP kecil di sudut kanan bawah (default). */
        fun pipCorner(corner: Int = 3): OverlayRect = fromCorner(corner)

        /** Mengatur PiP ke salah satu dari 4 sudut: 0=Kiri Atas, 1=Kanan Atas, 2=Kiri Bawah, 3=Kanan Bawah. */
        fun fromCorner(corner: Int): OverlayRect {
            val c = corner.coerceIn(0, 3)
            val w = 0.38
            val h = 0.38
            val margin = 0.03
            val x = if (c == 0 || c == 2) margin else 1.0 - w - margin
            val y = if (c == 0 || c == 1) margin else 1.0 - h - margin
            return OverlayRect(x = x, y = y, w = w, h = h, opacity = 1.0, corner = c)
        }

        fun fullFrame(): OverlayRect = OverlayRect(0.0, 0.0, 1.0, 1.0, 1.0, 0)
    }
}

/**
 * Satu klip pada TRACK OVERLAY (multilayer, non-destruktif).
 * Berbeda dari [Seg] yang menempati track utama: overlay punya posisi waktu sendiri
 * ([startT]) sehingga scene utama tetap utuh dan overlay bisa ditumpuk/dimatikan kapan saja.
 */
data class OverlayClip(
    val id: Long,
    val src: Int,
    val a: Double,
    val b: Double,
    val startT: Double,
    val sp: Double = 1.0,
    val mode: OverlayMode = OverlayMode.REPLACE_VISUAL,
    val rect: OverlayRect = OverlayRect.fullFrame(),
    val fadeInSec: Double = 0.0,
    val fadeOutSec: Double = 0.0,
    val muted: Boolean = true,
    val label: String = ""
) {
    /** Durasi tampil di timeline (detik). */
    val len: Double get() = max(Rules.MIN, (b - a) / sp.coerceAtLeast(0.1))

    val endT: Double get() = startT + len

    fun covers(t: Double): Boolean = t >= startT - 1e-6 && t < endT - 1e-6

    /** Waktu sumber (detik) pada posisi timeline [t]. */
    fun sourceSecAt(t: Double): Double {
        val u = (t - startT).coerceAtLeast(0.0) * sp
        return clampD(a + u, a, max(a, b - 0.001))
    }

    /** Opasitas efektif setelah fade in/out. */
    fun opacityAt(t: Double): Double {
        val base = rect.opacity
        if (fadeInSec <= 0.0 && fadeOutSec <= 0.0) return base
        var k = 1.0
        if (fadeInSec > 0.0 && t < startT + fadeInSec) {
            k = ((t - startT) / fadeInSec).coerceIn(0.0, 1.0)
        }
        if (fadeOutSec > 0.0 && t > endT - fadeOutSec) {
            k = minOf(k, ((endT - t) / fadeOutSec).coerceIn(0.0, 1.0))
        }
        return base * k
    }
}

/** Satu langkah riwayat undo/redo. */
data class Snap(
    val segs: List<Seg>,
    val rot: Int,
    val flip: Boolean,
    val markers: List<ClipMarker> = emptyList(),
    val overlays: List<OverlayClip> = emptyList()
)

object Fmt {
    private fun parts(s: Double): LongArray {
        val tenths = max(0L, floor(s * 10.0 + 0.5).toLong())
        val m = tenths / 600
        val rem = tenths % 600
        return longArrayOf(m, rem / 10, rem % 10)
    }

    /** Format waktu seperti di HTML: m:ss.d */
    fun time(s: Double): String {
        val p = parts(s)
        return "${p[0]}:${if (p[1] < 10) "0" else ""}${p[1]}.${p[2]}"
    }

    /** Format waktu presisi milidetik untuk posisi seek AI: m:ss.mmm */
    fun preciseTime(s: Double): String {
        val msTotal = max(0L, Math.round(s * 1000.0))
        val m = msTotal / 60000L
        val sec = (msTotal % 60000L) / 1000L
        val ms = msTotal % 1000L
        return String.format(Locale.US, "%d:%02d.%03d", m, sec, ms)
    }

    /** Format waktu ringkas dengan 2 desimal untuk label seek: m:ss.cc */
    fun time2(s: Double): String {
        val centis = max(0L, Math.round(s * 100.0))
        val m = centis / 6000L
        val sec = (centis % 6000L) / 100L
        val cc = centis % 100L
        return String.format(Locale.US, "%d:%02d.%02d", m, sec, cc)
    }

    /** Label durasi ringkas pada tiap potongan di timeline: "4.2s" atau "1:05.2" bila >= 60s. */
    fun durLabel(s: Double): String =
        if (s >= 60.0) time(s) else one(max(0.0, s)) + "s"

    /** Label ruler: mm:ss.d bila langkah < 1 detik, selain itu mm:ss. */
    fun ruler(s: Double, decimals: Boolean): String {
        if (decimals) {
            val p = parts(s)
            val mm = if (p[0] < 10) "0${p[0]}" else "${p[0]}"
            return "$mm:${if (p[1] < 10) "0" else ""}${p[1]}.${p[2]}"
        }
        val tot = max(0L, floor(s + 0.5).toLong())
        val mm = tot / 60
        val ss = tot % 60
        return (if (mm < 10) "0$mm" else "$mm") + ":" + (if (ss < 10) "0$ss" else "$ss")
    }

    /** Seperti toFixed(1) di JavaScript. */
    fun one(v: Double): String = String.format(Locale.US, "%.1f", v)

    /** Angka tanpa ekor ".0" bila bulat (seperti konversi angka JavaScript ke teks). */
    fun jsNum(x: Double): String =
        if (x == floor(x) && abs(x) < 1e15) x.toLong().toString() else x.toString()

    /** Frame rate: bulat bila sangat dekat bilangan bulat, selain itu satu desimal. */
    fun fps(v: Double): String =
        if (abs(v - floor(v + 0.5)) < 0.05) floor(v + 0.5).toLong().toString() else one(v)
}
