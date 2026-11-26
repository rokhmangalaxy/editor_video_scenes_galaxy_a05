package com.pribadi.editorvideo.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.pribadi.editorvideo.MainActivity
import com.pribadi.editorvideo.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service yang menjalankan ekspor supaya tetap berjalan saat aplikasi di latar belakang. */
class ExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_export),
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
        ExportCenter.cancelHook = { job?.cancel() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_CANCEL == intent.action) {
            val running = job
            if (running != null && running.isActive) {
                running.cancel()
            } else {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        // Aturan Android: setelah startForegroundService(), startForeground() harus segera dipanggil.
        startInForeground(buildProgressNotification("Menyiapkan ekspor", 0, true))

        val req = ExportCenter.pendingRequest
        val plan = ExportCenter.pendingPlan
        if (req == null || plan == null) {
            finishService()
            return START_NOT_STICKY
        }
        val running = job
        if (running != null && running.isActive) return START_NOT_STICKY

        acquireWakeLock()
        job = scope.launch { runExport(req, plan) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ExportCenter.cancelHook = null
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private suspend fun runExport(req: ExportRequest, plan: ExportPlan) {
        try {
            val outcome = ExportEngine.run(applicationContext, req, plan) { index, count, fraction ->
                onProgress(index, count, fraction)
            }
            ExportCenter.clearPending()
            ExportCenter.publish(ExportState.Done(outcome.files, outcome.notes))
            notifyResult("Ekspor selesai", resultText(outcome))
        } catch (e: CancellationException) {
            ExportCenter.clearPending()
            ExportCenter.publish(ExportState.Cancelled)
            notifyResult("Ekspor dibatalkan", "Tidak ada file yang disimpan untuk bagian yang dibatalkan.")
        } catch (e: ExportFailure) {
            ExportCenter.clearPending()
            val msg = e.message ?: "Ekspor gagal."
            ExportCenter.publish(ExportState.Failed(msg))
            notifyResult("Ekspor gagal", msg)
        } catch (e: Throwable) {
            ExportCenter.clearPending()
            val msg = "Ekspor gagal: " + (e.message ?: e.javaClass.simpleName)
            ExportCenter.publish(ExportState.Failed(msg))
            notifyResult("Ekspor gagal", msg)
        } finally {
            releaseWakeLock()
            finishService()
        }
    }

    private fun resultText(outcome: ExportOutcome): String {
        val n = outcome.files.size
        return if (n == 1) "Tersimpan di " + outcome.files[0] else n.toString() + " file tersimpan di Movies/" + MediaStoreSaver.FOLDER
    }

    private fun onProgress(index: Int, count: Int, fraction: Float) {
        val label = if (count > 1) "Mengekspor " + (index + 1) + "/" + count else "Mengekspor video"
        ExportCenter.publish(ExportState.Running(index, count, fraction, label))
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < 700L && fraction < 1f) return
        lastNotifyAt = now
        val overall = (index + fraction) / count.toFloat()
        var pct = Math.round(overall * 100f)
        if (pct < 0) pct = 0
        if (pct > 100) pct = 100
        val text = label + " · " + pct + "%"
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildProgressNotification(text, pct, false))
        } catch (e: SecurityException) {
            // Izin notifikasi ditolak: ekspor tetap berjalan tanpa notifikasi.
        }
    }

    private fun startInForeground(n: Notification) {
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Bila sistem menolak, ekspor tetap dicoba berjalan sebagai service biasa.
        }
    }

    private fun finishService() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // diabaikan
        }
        stopSelf()
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildProgressNotification(text: String, percent: Int, indeterminate: Boolean): Notification {
        val cancelIntent = Intent(this, ExportService::class.java)
        cancelIntent.action = ACTION_CANCEL
        val cancelPi = PendingIntent.getService(
            this, 1, cancelIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Batal", cancelPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyResult(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_RESULT, n)
        } catch (e: SecurityException) {
            // Izin notifikasi ditolak: abaikan.
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EditorVideo:export")
            wl.setReferenceCounted(false)
            wl.acquire(3L * 60L * 60L * 1000L)
            wakeLock = wl
        } catch (e: Exception) {
            // diabaikan
        }
    }

    private fun releaseWakeLock() {
        try {
            val wl = wakeLock
            if (wl != null && wl.isHeld) wl.release()
        } catch (e: Exception) {
            // diabaikan
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_CANCEL = "com.pribadi.editorvideo.action.CANCEL_EXPORT"
        private const val CHANNEL_ID = "export"
        private const val NOTIF_ID = 1001
        private const val NOTIF_ID_RESULT = 1002
    }
}
