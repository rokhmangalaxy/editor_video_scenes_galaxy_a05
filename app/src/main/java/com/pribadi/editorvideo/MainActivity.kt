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
 * Satu-satunya activity. Tugasnya:
 *  - memasang layar Compose,
 *  - membuka pemilih video dari Galeri (Photo Picker) maupun File Manager (SAF) dengan multiseleksi,
 *  - menangani eksport & import berkas Tagging (.json) lewat SAF,
 *  - meminta izin notifikasi (Android 13+) sebelum ekspor dimulai,
 *  - menjeda pemutaran saat aplikasi tidak terlihat.
 */
class MainActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels()

    private val pickVideosFromGallery = registerForActivityResult(
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

    private val pickVideosFromFileManager = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // Diabaikan bila penyedia dokumen tidak mendukung persistable permission.
                }
            }
            vm.importVideos(uris)
        }
    }

    private val exportTaggingDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            vm.exportTaggingToUri(uri)
        }
    }

    private val importTaggingDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importTaggingFromUri(uri)
        }
    }

    private val exportPromptsDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            vm.exportPromptsToUri(uri)
        }
    }

    private val importPromptsDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importPromptsFromUri(uri)
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
            EditorTheme(theme = vm.appTheme) {
                EditorScreen(
                    vm = vm,
                    onPickGallery = { openGalleryPicker() },
                    onPickFileManager = { openFileManagerPicker() },
                    onExportTaggingFile = { openExportTagging() },
                    onImportTaggingFile = { openImportTagging() },
                    onExportPromptsFile = { openExportPrompts() },
                    onImportPromptsFile = { openImportPrompts() },
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

    private fun openGalleryPicker() {
        try {
            pickVideosFromGallery.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        } catch (e: Exception) {
            openFileManagerPicker()
        }
    }

    private fun openFileManagerPicker() {
        try {
            pickVideosFromFileManager.launch(arrayOf("video/*"))
        } catch (e: Exception) {
            vm.toast("File Manager tidak ditemukan di perangkat ini")
        }
    }

    private fun openExportTagging() {
        try {
            exportTaggingDoc.launch(vm.defaultTaggingFileName())
        } catch (e: Exception) {
            vm.toast("Gagal membuka dialog simpan berkas")
        }
    }

    private fun openImportTagging() {
        try {
            importTaggingDoc.launch(arrayOf("application/json", "text/*", "*/*"))
        } catch (e: Exception) {
            vm.toast("Gagal membuka File Manager untuk import tagging")
        }
    }

    private fun openExportPrompts() {
        try {
            exportPromptsDoc.launch(vm.defaultPromptsFileName())
        } catch (e: Exception) {
            vm.toast("Gagal membuka dialog simpan koleksi prompt")
        }
    }

    private fun openImportPrompts() {
        try {
            importPromptsDoc.launch(arrayOf("application/json", "text/*", "*/*"))
        } catch (e: Exception) {
            vm.toast("Gagal membuka File Manager untuk import koleksi prompt")
        }
    }

    private fun startExport() {
        if (!vm.hasVideo) {
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
