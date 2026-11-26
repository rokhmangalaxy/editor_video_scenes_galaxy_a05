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

    /** Durasi klip demo sebelum ada video yang diimpor. */
    const val DEMO_DUR = 12.2
}

fun clampD(v: Double, a: Double, b: Double): Double = min(b, max(a, v))

/** Pembulatan ala JavaScript Math.round (setengah dibulatkan ke atas). */
fun jsRound(v: Double): Double = floor(v + 0.5)

/**
 * Satu potongan di timeline.
 *  - klip   : {a, b, sp, rev, src}       (a..b detik dari video sumber ke-src, kecepatan sp, arah mundur rev)
 *  - beku   : {fz=true, a, d, sp, src}   (gambar statis dari frame di detik a pada sumber ke-src, durasi dasar d)
 * Durasi di timeline = (b - a) / sp  atau  d / sp.
 */
data class Seg(
    val a: Double,
    val b: Double,
    val sp: Double = 1.0,
    val fz: Boolean = false,
    val d: Double = 0.0,
    val rev: Boolean = false,
    val src: Int = 0
) {
    /** Durasi dasar (sebelum dibagi kecepatan). */
    val baseLen: Double get() = if (fz) d else b - a

    /** Durasi di timeline. */
    val len: Double get() = baseLen / sp

    /** Kecepatan maksimum agar durasi potongan tidak kurang dari Rules.MIN detik. */
    val maxSpeed: Double get() = min(Rules.SPEED_MAX, baseLen / Rules.MIN)

    companion object {
        fun freezeAt(time: Double, src: Int = 0): Seg =
            Seg(a = time, b = time, sp = 1.0, fz = true, d = Rules.FZ_DUR, rev = false, src = src)
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
        for (i in segs.indices) {
            val g = segs[i]
            val l = g.len
            if (tt < off[i] + l || i == segs.size - 1) {
                val u = clampD(tt - off[i], 0.0, l)
                val s = when {
                    g.fz -> g.a
                    g.rev -> clampD(g.b - u * g.sp, g.a, g.b)
                    else -> clampD(g.a + u * g.sp, g.a, g.b)
                }
                return Located(i, u, s)
            }
        }
        return Located(0, 0.0, 0.0)
    }
}

/** Satu langkah riwayat undo/redo. */
data class Snap(val segs: List<Seg>, val rot: Int, val flip: Boolean)

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
