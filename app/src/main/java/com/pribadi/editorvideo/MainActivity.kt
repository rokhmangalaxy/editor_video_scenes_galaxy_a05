package com.pribadi.editorvideo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.pribadi.editorvideo.ui.EditorScreen
import com.pribadi.editorvideo.ui.EditorTheme
import com.pribadi.editorvideo.vm.EditorViewModel

/**
 * Satu-satunya activity. Tugasnya hanya:
 *  - memasang layar Compose,
 *  - membuka pemilih video sistem dengan dukungan multiseleksi (Photo Picker / SAF),
 *  - meminta izin notifikasi (Android 13+) sebelum ekspor dimulai,
 *  - menjeda pemutaran saat aplikasi tidak terlihat.
 */
class MainActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels()

    private val pickVideos = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // Tidak semua URI mendukung izin permanen (mis. Photo Picker). Tidak masalah.
                }
            }
            vm.importVideos(uris)
        }
    }

    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            vm.toast("Notifikasi dimatikan, progres ekspor hanya tampil di aplikasi")
        }
        vm.startExport()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            EditorTheme {
                EditorScreen(
                    vm = vm,
                    onImport = { openPicker() },
                    onStartExport = { startExport() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Permukaan video dibuat ulang saat aplikasi kembali tampil; gambar ulang frame yang sedang dijeda.
        window.decorView.postDelayed({ vm.refreshFrame() }, 400L)
    }

    override fun onStop() {
        vm.pause()
        super.onStop()
    }

    private fun openPicker() {
        pickVideos.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
    }

    private fun startExport() {
        if (!vm.hasVideo) {
            // ViewModel akan menampilkan pesan "Impor video dulu sebelum ekspor".
            vm.startExport()
            return
        }
        val needAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needAsk) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startExport()
        }
    }
}
