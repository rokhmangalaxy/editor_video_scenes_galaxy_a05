package com.pribadi.editorvideo.export

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keadaan ekspor yang ditampilkan di UI dan notifikasi. */
sealed interface ExportState {
    object Idle : ExportState

    /** fileIndex mulai dari 0; fraction = kemajuan file yang sedang dikerjakan (0..1). */
    data class Running(
        val fileIndex: Int,
        val fileCount: Int,
        val fraction: Float,
        val text: String
    ) : ExportState

    data class Done(val files: List<String>, val notes: List<String>) : ExportState

    data class Failed(val message: String) : ExportState

    object Cancelled : ExportState
}

/**
 * Penghubung antara UI (ViewModel) dan ExportService. Keduanya berada di proses yang sama,
 * jadi cukup berbagi objek ini.
 */
object ExportCenter {

    private val mutableState = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = mutableState.asStateFlow()

    @Volatile
    var pendingRequest: ExportRequest? = null

    @Volatile
    var pendingPlan: ExportPlan? = null

    /** Diisi oleh service saat berjalan; dipanggil dari UI untuk membatalkan. */
    @Volatile
    var cancelHook: (() -> Unit)? = null

    fun isRunning(): Boolean = mutableState.value is ExportState.Running

    /** Memulai ekspor lewat foreground service. Mengembalikan false bila sudah ada ekspor berjalan. */
    fun start(context: Context, request: ExportRequest, plan: ExportPlan): Boolean {
        if (isRunning()) return false
        val count = ExportMath.buildJobs(request.mode, request.segs, request.currentIndex).size
        pendingRequest = request
        pendingPlan = plan
        mutableState.value = ExportState.Running(0, count, 0f, "Menyiapkan")
        try {
            val app = context.applicationContext
            ContextCompat.startForegroundService(app, Intent(app, ExportService::class.java))
        } catch (e: Exception) {
            pendingRequest = null
            pendingPlan = null
            mutableState.value =
                ExportState.Failed("Layanan ekspor tidak bisa dimulai. Buka aplikasi lalu coba lagi. (" + (e.message ?: "") + ")")
        }
        return true
    }

    /** Dipanggil UI untuk membatalkan ekspor yang sedang berjalan. */
    fun cancel() {
        val hook = cancelHook
        if (hook != null) {
            hook()
        } else if (isRunning()) {
            // Service belum sempat berjalan: langsung batalkan.
            pendingRequest = null
            pendingPlan = null
            mutableState.value = ExportState.Cancelled
        }
    }

    internal fun publish(s: ExportState) {
        mutableState.value = s
    }

    internal fun clearPending() {
        pendingRequest = null
        pendingPlan = null
    }

    /** Dipanggil UI setelah hasil (selesai/gagal/batal) ditutup. */
    fun reset() {
        if (!isRunning()) mutableState.value = ExportState.Idle
    }
}
