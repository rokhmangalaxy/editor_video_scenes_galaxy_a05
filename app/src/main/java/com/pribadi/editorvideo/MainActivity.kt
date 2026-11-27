package com.pribadi.editorvideo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
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
        handleIncomingVideoIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingVideoIntent(intent)
    }

    /**
     * Menangkap video yang dibagikan (Share) atau dibuka dari aplikasi lain
     * (Galeri, WhatsApp, Telegram, TikTok, File Manager, Browser, dsb.) untuk dijadikan input.
     */
    @Suppress("DEPRECATION")
    private fun handleIncomingVideoIntent(incoming: Intent?) {
        if (incoming == null) return
        if (incoming.getBooleanExtra(EXTRA_SHARE_CONSUMED, false)) return
        val action = incoming.action ?: return
        if (action != Intent.ACTION_SEND &&
            action != Intent.ACTION_SEND_MULTIPLE &&
            action != Intent.ACTION_VIEW
        ) {
            return
        }

        val collected = LinkedHashSet<Uri>()

        when (action) {
            Intent.ACTION_SEND -> {
                val streamUri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                if (streamUri != null) collected.add(streamUri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streamList: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
                    incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (!streamList.isNullOrEmpty()) collected.addAll(streamList)
            }
            Intent.ACTION_VIEW -> {
                incoming.data?.let { collected.add(it) }
            }
        }

        // Ambil juga dari ClipData bila aplikasi pengirim menaruh URI di ClipData
        val clip = incoming.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { collected.add(it) }
            }
        }
        incoming.data?.let { collected.add(it) }

        if (collected.isEmpty()) return
        incoming.putExtra(EXTRA_SHARE_CONSUMED, true)

        val persistFlags = LinkedHashMap<Uri, Boolean>()
        for (u in collected) {
            val persisted = try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (_: Exception) {
                false
            }
            persistFlags[u] = persisted
        }
        vm.importSharedVideos(collected.toList(), persistFlags)
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

    companion object {
        private const val EXTRA_SHARE_CONSUMED = "com.pribadi.editorvideo.SHARE_CONSUMED"
    }
}
