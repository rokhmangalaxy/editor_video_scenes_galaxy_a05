package com.pribadi.editorvideo.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Menyimpan hasil ekspor ke Movies/EditorVideo lewat MediaStore (tanpa izin penyimpanan luas). */
object MediaStoreSaver {

    const val FOLDER: String = "EditorVideo"

    /** Menyimpan berkas dan mengembalikan teks lokasi untuk ditampilkan ke pengguna. */
    fun save(context: Context, src: File, displayName: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw IOException("Gagal membuat berkas di folder Movies")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Gagal membuka berkas tujuan")
                out.use { o ->
                    FileInputStream(src).use { i -> i.copyTo(o, 256 * 1024) }
                }
                val done = ContentValues()
                done.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (e2: Exception) {
                    // diabaikan
                }
                throw e
            }
            return Environment.DIRECTORY_MOVIES + "/" + FOLDER + "/" + displayName
        }

        // Android 9 (API 28): simpan di folder khusus aplikasi supaya tidak perlu izin penyimpanan.
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val dir = File(base, FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Gagal membuat folder tujuan")
        val dest = File(dir, displayName)
        FileInputStream(src).use { i ->
            FileOutputStream(dest).use { o -> i.copyTo(o, 256 * 1024) }
        }
        return dest.absolutePath
    }
}
