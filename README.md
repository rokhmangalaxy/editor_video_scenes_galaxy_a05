# Editor Video — Pemecah Video Menjadi Beberapa Bagian (Samsung Galaxy A05)

Aplikasi editor video Android berbasis **Jetpack Compose** & **Media3 (ExoPlayer + Transformer)** dengan fungsi utama memecah/memotong satu atau beberapa video menjadi beberapa bagian (*scenes/potongan*) secara manual maupun deteksi scene otomatis, mengedit tiap potongan, dan mengekspornya secara utuh maupun per potongan.

## Fitur Utama

1. **Impor Media dari Galeri atau File Manager (`+` & Placeholder Pratinjau):**
   - Saat pratinjau kosong, mengetuk placeholder pratinjau (maupun tombol **`+`**) menampilkan pilihan **Upload dari Galeri** (*Photo Picker*) atau **Pilih dari File Manager** (*Storage Access Framework*, multiseleksi).
   - Ketuk tombol **`+`** kapan saja untuk menambahkan video baru ke akhir timeline yang sedang diedit.
2. **Pemecah Video Manual, Seek Auto Seleksi & Deteksi Scene Otomatis + Grid Preview:**
   - **Seek Auto Seleksi Timeline:** Saat terdapat beberapa klip di timeline, mengetuk klip **sesudah** klip aktif otomatis memindahkan posisi *seek* ke **frame awal** klip tersebut; sedangkan mengetuk klip **sebelum** klip aktif memindahkan posisi *seek* ke **akhir frame** klip tersebut (disertai auto-seleksi klip).
   - **Potong (*Split*):** Memecah klip pada posisi *playhead* menjadi beberapa bagian.
   - **Deteksi Scene Otomatis (*Scene*):** Memindai pergantian adegan dalam klip secara otomatis, menampilkan **Grid Preview Deteksi** berisi thumbnail tiap bagian scene, durasi, sensitivitas, dan pilihan centang per scene sebelum di-split ke timeline.
   - **Trim Handle Kiri/Kanan:** Mengatur batas awal dan akhir setiap potongan langsung di timeline.
3. **Membalikkan: Opsi Mundur & Loop (*Normal + Mundur*) serta Alat Klip:**
   - **Mundur (*Reverse*):** Merender potongan video dengan cara membalik urutan frame-nya (dari akhir ke awal) menjadi klip MP4 terbalik.
   - **Loop (*Normal + Mundur*):** Menggabungkan video normal diikuti video mundur menjadi satu klip MP4 utuh (bolak-balik).
   - **Kecepatan (`0.1x` – `9.0x`):** Mengatur kecepatan per potongan atau menerapkannya ke semua potongan.
   - **Duplikat, Hapus, Putar (`90°`), Cermin (*Flip*), Beku (*Freeze Frame*), Undo & Redo.**
4. **Kustomisasi Overlay Teks yang Kaya (Default Kosong):**
   - Mendukung 2 layer teks multi-baris (default kosong).
   - Pilihan gaya huruf (*Font*), perataan (Kiri/Tengah/Kanan), ukuran huruf (`40%`–`220%`), palet warna isi teks, warna & ketebalan *outline*, kotak latar belakang (*background pill*), bayangan (*shadow*), serta geser (*drag*) posisi langsung di layar pratinjau.
5. **Mode Grid Lengkap (Floating 3–9 Kolom, 12 Warna Custom Tag Transparan, Sort, Filter & Multiplayer Collage):**
   - **Floating Range Vertikal 3–9 Kolom:** Grid menggunakan lebar penuh 100%, sedangkan penggeser range vertikal **3 sampai 9 kolom** tampil mengambang (*floating*) saat simbol kostum kolom di-toggle.
   - **Preview Frame Pertama Utuh:** Setiap item Grid menampilkan **frame pertama klip secara utuh** (tanpa terpotong/*crop*), selaras penuh dengan tampilan *autoplay* saat diklik.
   - **Tagging (12 Opsi Warna), Eksport/Import Tagging, Label Transparan, Sort & Filter:** Buat *Custom Tag* dengan **12 pilihan warna** (label pada item Grid diberi efek transparansi), **Eksport & Import Tagging** via berkas `.json` File Manager maupun Clipboard (mode *Gabung* atau *Timpa*), urutkan (*Sort*) berdasarkan urutan timeline, durasi, atau abjad tag, serta saring (*Filter*) tampilan per tag.
   - **Hold Multiseleksi & Multiplayer Collage:** Tekan tahan (*hold*) sel Grid untuk memilih banyak klip sekaligus, memberi tag massal, atau memutar seluruh klip terpilih secara serentak dalam kanvas **Multiplayer Collage**.
6. **Fitur AI (Frame 100% Identik Preview + Step Per-Frame):**
   - Menu **AI** di toolbar bawah menangkap langsung buffer tampilan *Preview* (`TextureView`) yang tersinkronisasi penuh ke posisi *seek* (disertai *fallback* decode PTS presisi mikrodetik via `MediaCodec` dan tombol geser `±1 Frame` / `±5 Frame`), kolom **textarea prompt**, serta tombol **Generate** untuk persiapan penyisipan hasil AI secara presisi di posisi *seek*.
7. **10 Pilihan Tema dengan Grid 2 Kolom Mini Preview:**
   - Tersedia **10 tema warna** (*Medium Maroon*, *Gelap Standar*, *Midnight Ocean*, *Emerald Forest*, *Royal Purple*, *Sunset Amber*, *Rose Gold*, *Cyber Teal*, *Crimson Gold*, dan *Slate Mocha*) yang ditampilkan dalam **Grid 2 Kolom lengkap dengan Mini Preview UI**.
8. **Pinch untuk Zoom In/Out:**
   - **Timeline:** Cubit 2 jari pada area timeline untuk memperbesar/memperkecil rentang skala waktu (`4 dp/detik` hingga `400 dp/detik`).
   - **Pratinjau Video:** Cubit 2 jari pada area pratinjau untuk memperbesar/memperkecil tampilan video (`1.0x` – `4.0x`).
9. **Catatan Bug, Pengembangan & Tab Updated (Readme):**
   - Fitur **Catatan** terintegrasi di bar atas memiliki dua bagian:
     - **Catatan:** Mencatat temuan **Bug** maupun **Ide Pengembangan** (tersimpan di perangkat + fitur salin ke *clipboard*).
     - **Updated (Readme):** Menyimpan riwayat pembaruan (*changelog*) serta panduan lengkap aplikasi (*Readme*), sehingga area pratinjau awal tetap bersih.
10. **Update Timpa APK Tanpa Uninstall:**
   - Workflow build menjaga sertifikat penandatanganan (*signing keystore*) tetap konsisten secara permanen dan menaikkan `versionCode` otomatis di setiap build, sehingga instalasi APK versi terbaru langsung menimpa versi lama tanpa perlu menghapus aplikasi yang sudah terpasang.
11. **Mode Ekspor Fleksibel (Foreground Service):**
   - **Ekspor Video:** Menggabungkan seluruh potongan di timeline menjadi 1 berkas MP4.
   - **Ekspor Semua Potongan:** Mengekspor setiap potongan menjadi berkas MP4 terpisah secara berurutan.
   - **Ekspor Potongan Saat Ini:** Mengekspor hanya potongan yang sedang dipilih.
