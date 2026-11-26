# Editor Video — Pemecah Video Menjadi Beberapa Bagian (Samsung Galaxy A05)

Aplikasi editor video Android berbasis **Jetpack Compose** & **Media3 (ExoPlayer + Transformer)** dengan fungsi utama memecah/memotong satu atau beberapa video menjadi beberapa bagian (*scenes/potongan*) dan mengekspornya secara utuh maupun per potongan.

## Fitur Utama

1. **Impor Media Multiseleksi (`+`):**
   - Mendukung pemilihan satu atau beberapa video sekaligus dari galeri (*Photo Picker*).
   - Ketuk tombol **`+`** kapan saja untuk menambahkan video baru ke akhir timeline yang sedang diedit.
2. **Pemecah & Pengelola Potongan Video:**
   - **Potong (*Split*):** Memecah klip pada posisi *playhead* menjadi beberapa bagian.
   - **Trim Handle Kiri/Kanan:** Mengatur batas awal dan akhir setiap potongan langsung di timeline.
   - **Kecepatan (`0.1x` – `9.0x`):** Mengatur kecepatan per potongan atau menerapkannya ke semua potongan.
   - **Membalikkan (*Reverse*):** Memutar dan mengekspor potongan video secara mundur (terbalik).
   - **Duplikat, Hapus, Putar (`90°`), Cermin (*Flip*), dan Beku (*Freeze Frame*).**
   - **Undo & Redo** penuh untuk riwayat pengeditan.
3. **Overlay Teks (Default Kosong):**
   - Secara bawaan kosong (tanpa teks).
   - Buka alat **Teks** di bar bawah (atau ketuk teks pada pratinjau) untuk menambahkan, mengubah, atau mengosongkan **Teks Atas** dan **Teks Bawah**.
4. **Pinch untuk Zoom In/Out:**
   - **Timeline:** Cubit 2 jari pada area timeline untuk memperbesar/memperkecil rentang skala waktu (`4 dp/detik` hingga `400 dp/detik`).
   - **Pratinjau Video:** Cubit 2 jari pada area pratinjau untuk memperbesar/memperkecil tampilan video (`1.0x` – `4.0x`).
5. **Readme di Awal Pratinjau Kosong:**
   - Saat belum ada video yang diimpor, area pratinjau menampilkan panduan singkat (*Readme*) penggunaan aplikasi, dan dapat dibuka kembali kapan saja melalui tombol **`?`** di bar atas.
6. **Catatan Bug & Pengembangan Masa Depan (*Note*):**
   - Fitur catatan terintegrasi (tersimpan otomatis di perangkat) untuk mencatat temuan **Bug** maupun **Ide Pengembangan**, lengkap dengan penanda selesai (*checklist*) dan tombol **Salin Semua** ke *clipboard*.
7. **Mode Ekspor Fleksibel (Foreground Service):**
   - **Ekspor Video:** Menggabungkan seluruh potongan di timeline menjadi 1 file MP4.
   - **Ekspor Semua Potongan:** Mengekspor setiap potongan menjadi file MP4 terpisah secara berurutan.
   - **Ekspor Potongan Saat Ini:** Mengekspor hanya potongan yang sedang dipilih.

## Cara Build APK (GitHub Actions)

Workflow `.github/workflows/build.yml` otomatis membangun APK rilis (`EditorVideo-A05.apk`) setiap kali ada perubahan pada kode proyek atau dijalankan manual lewat **Actions -> Build APK -> Run workflow**, lalu menerbitkannya ke halaman **Releases** dan **Artifacts**.
