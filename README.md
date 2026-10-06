# Minecraft Console Client Android Native

> MCC Droid adalah port Android native berbasis Jetpack Compose untuk menjalankan dan mengelola **Minecraft Console Client (MCC)** dari perangkat Android.

[![Build APK](https://github.com/Fadiba21/minecraft-console-client-android-native/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Fadiba21/minecraft-console-client-android-native/actions/workflows/build-apk.yml)

## Tentang proyek

Proyek ini adalah **fork dan adaptasi Android native** dari [MCCTeam/Minecraft-Console-Client](https://github.com/MCCTeam/Minecraft-Console-Client). MCC Droid mempertahankan runtime MCC sebagai komponen inti, kemudian menambahkan antarmuka Android, pengelolaan profil, editor konfigurasi, otomasi, Gemini Assistant, console MCC monospace, dan sound effect UI.

Proyek ini sedang dikembangkan secara aktif. Source code, workflow build, dokumentasi, catatan progres, dan APK release tersedia di repository ini.

## Fitur utama

### Pengelolaan MCC

- Menjalankan MCC native pada Android ARM64.
- Banyak profil untuk akun/server yang berbeda.
- Start, stop, restart, dan status sesi.
- Login Microsoft melalui device code.
- Runtime MCC diekstrak otomatis ke penyimpanan aplikasi.
- Dukungan command MCC dan command Minecraft berbasis `/`.

### Console MCC

Halaman Terminal sekarang murni untuk MCC dan menggunakan console monospace penuh. Shell Android dipindahkan ke **Pengaturan → Konsol → Buka Shell Android (advanced)**.

Console MCC memiliki fungsi:

- Kirim command.
- Command history.
- Shortcut command adaptif maksimal lima command teratas.
- Saran command dan katalog perintah.
- Auto-follow output.
- Tombol `−` dan `+` kecil di kanan atas untuk mengatur ukuran teks dan menampilkan lebih banyak baris.
- Copy baris atau seluruh log.
- Simpan log ke folder profil.
- Buka URL yang muncul di chat.
- Bersihkan output.

### Editor konfigurasi

- Editor visual untuk field konfigurasi MCC.
- Input teks, password, dropdown, switch, slider, dan daftar string.
- Mode raw editor untuk pengguna tingkat lanjut.
- Perubahan konfigurasi terlihat langsung di UI.
- Simpan, buang perubahan, dan reload konfigurasi.

### Otomasi dan notifikasi

- Trigger berdasarkan teks atau event log MCC.
- Auto-command, auto-chat, notifikasi, delay, stop, dan restart.
- Regex untuk pola event.
- Notifikasi login, kick, disconnect, whisper, dan event lain.
- Script C# MCC di folder data bersama.

### Gemini Assistant

Gemini Assistant dapat dibuka dari Dashboard melalui tombol AI kecil di kanan bawah atau dari menu Lainnya.

Kemampuan AI di dalam sandbox aplikasi:

- Membaca daftar profil.
- Membaca config MCC.
- Membaca log dan file teks profil.
- Membaca status aplikasi, otomasi, notifikasi, dan setting.
- Membaca metadata runtime MCC.
- Membuat atau mengubah config.
- Membuat dan mengedit file teks.
- Membuat script C# MCC.
- Membuat otomasi.
- Mengubah setting aplikasi yang diizinkan.
- Mengirim command MCC setelah konfirmasi pengguna.

Perubahan yang bersifat menulis atau mengirim command membutuhkan konfirmasi. AI tidak memiliki akses root, tidak menjalankan shell bebas, tidak dapat menulis binary runtime, dan tidak dapat mengakses path di luar sandbox data MCC Droid.

### Sound effect UI

Aplikasi memakai 17 sound effect UI dari [UI SFX](https://github.com/romainsimon/uisfx), menggunakan **cinematic pack** dengan lisensi audio **CC0 1.0**. Cue dipetakan berdasarkan makna aksi, seperti select, open, close, copy, send, success, error, warning, processing, toggle, dan delete.

Sound effect dibuat singkat, lembut, dan tidak mengambil audio proprietary dari Windows atau Apple.

## Cara menggunakan aplikasi

### 1. Buat profil

1. Buka tab **Beranda**.
2. Tekan **Profil baru**.
3. Masukkan nama profil.
4. Buka tab **Konfig**.
5. Isi login, password, host, port, dan setting lain.
6. Simpan perubahan.

Satu profil memiliki folder data dan konfigurasi sendiri.

### 2. Jalankan MCC

1. Kembali ke **Beranda**.
2. Tekan tombol ▶ pada profil.
3. Jika login Microsoft diperlukan, ikuti device code yang ditampilkan.
4. Buka tab **Terminal**.
5. Gunakan console MCC untuk memantau output dan mengirim command.

### 3. Mengirim command

Contoh:

```text
/help
/health
/list
/reload
```

Command yang diawali `/` akan dicatat sebagai statistik shortcut. Lima command yang paling sering digunakan akan tampil sebagai shortcut otomatis.

### 4. Mengonfigurasi Gemini API

1. Buka **Lainnya → Pengaturan**.
2. Cari bagian **Gemini / Google AI Studio**.
3. Masukkan API key dari Google AI Studio.
4. Simpan API key.
5. Tekan tombol untuk mengambil daftar model.
6. Pilih model Gemini yang tersedia.
7. Buka tombol AI dari Dashboard.

API key disimpan terenkripsi menggunakan Android Keystore. API key tidak ditulis ke source code atau repository.

Contoh pengujian aman:

```text
Baca config profil saya dan jelaskan setting server yang aktif.
```

Kemudian uji pembuatan script:

```text
Buat script C# MCC yang mengirim pesan otomatis ketika pemain mengetik "hello".
Jelaskan file yang akan dibuat dan tunggu konfirmasi sebelum menyimpannya.
```

AI akan menampilkan preview tindakan. Tekan **Terapkan** hanya setelah isi dan target file sudah diperiksa.

### 5. Menggunakan otomasi

1. Buka **Otomasi**.
2. Buat aturan baru.
3. Isi pola teks atau event.
4. Tentukan command atau aksi.
5. Simpan dan aktifkan aturan.

Contoh konsep:

```text
Jika log berisi "joined the game", kirim /say Selamat datang
```

## Arsitektur sistem

```text
Android Activity / Jetpack Compose UI
        │
        ├── Dashboard, Config, Terminal, Automation, Files, Gemini
        │
        ├── SessionManager
        │       └── McSession → MCC BasicIO process
        │
        ├── LogBuffer → McText → Console renderer
        │
        ├── ProfileStore / RuleStore / NotifyStore / AppPrefs
        │
        ├── GeminiClient → Google Gemini REST API
        │       └── MccAiTools → sandbox data MCC
        │
        └── RuntimeInstaller → assets/mcc-bundle.zip
```

### Jalur proses MCC

1. `RuntimeInstaller` mengekstrak runtime MCC dari `assets/mcc-bundle.zip`.
2. `SessionManager` membuat sesi per profil.
3. `McSession` menjalankan MCC dengan mode `BasicIO`.
4. Output stdout dibaca sebagai UTF-8.
5. `McText` menormalisasi Unicode, warna Minecraft, ANSI, mojibake, dan format warna hex.
6. `LogBuffer` mengirim batch output ke UI.
7. Output dirender sebagai console monospace dengan warna dan style server.

### Jalur Gemini

1. Gemini API key disimpan terenkripsi di Android Keystore.
2. `GeminiClient` mengirim prompt dan deklarasi function tools ke Gemini.
3. `MccAiTools` memvalidasi dan menjalankan tool yang diminta.
4. Operasi baca dijalankan langsung dalam sandbox.
5. Operasi tulis dan command ditampilkan sebagai preview untuk konfirmasi pengguna.

## Struktur repository

```text
app/                         Source aplikasi Android
app/src/main/java/           UI, core, parser, session, AI, automation
app/src/main/res/            Resource UI, icon, sound effect
scripts/                     Script runtime MCC dan build bundle
.github/workflows/            Workflow build APK
CHANGELOG.md                 Ringkasan perubahan
DEVELOPMENT_PROGRESS.md      Riwayat development dan validasi
NOTICE.md                    Attribution dan sumber upstream/assets
```

## Build dari source

### Prasyarat

- JDK 21
- Android SDK dengan platform/build-tools yang sesuai
- Gradle 8.11.1 atau Gradle Wrapper
- Linux environment untuk membuat runtime MCC ARM64
- .NET SDK jika ingin membangun ulang runtime MCC

### Build debug/release

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_HOME="$ANDROID_SDK_ROOT"
./gradlew assembleRelease
```

APK hasil build Gradle berada di:

```text
app/build/outputs/apk/release/app-release.apk
```

### GitHub Actions

Workflow tersedia di `.github/workflows/build-apk.yml`.

1. Buka tab **Actions**.
2. Pilih workflow **Build APK**.
3. Tekan **Run workflow**.
4. Isi `mcc_ref` jika ingin memakai branch, tag, atau commit MCC tertentu.
5. Unduh artifact atau APK dari GitHub Release.

Untuk signing tetap, gunakan GitHub Actions Secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## Download APK

APK release tersedia di halaman [Releases](../../releases). APK yang dibangun dan divalidasi pada milestone ini adalah **MCCDroid 1.0.4**.

## Status development

Fitur yang sudah tersedia:

- Runtime MCC ARM64 di Android.
- Profil multi-server.
- Config editor.
- Terminal Console MCC murni dengan kontrol ukuran teks.
- Parsing warna dan perbaikan karakter rusak.
- Shortcut command adaptif.
- Dashboard dengan animasi.
- Gemini Assistant dan tool sandbox.
- Automation dan notification.
- File manager dan script support.
- Soft UI sound pack CC0.
- Release APK 1.0.4 tervalidasi.

Lihat [DEVELOPMENT_PROGRESS.md](DEVELOPMENT_PROGRESS.md) untuk catatan perubahan lebih rinci.

## Batasan dan catatan

- MCC Droid bukan Minecraft penuh dan tidak merender dunia Minecraft.
- Mod Fabric/Forge tidak berjalan di dalam MCC; integrasi mod launcher terpisah.
- Terminal MCC memakai BasicIO/pipa, bukan PTY/TUI penuh.
- Runtime yang disertakan adalah target Android ARM64.
- Target SDK saat ini dipertahankan untuk kompatibilitas eksekusi runtime lokal dan bukan target Google Play.
- Gunakan API key Gemini pribadi dan jangan memasukkannya ke issue, commit, atau log.

## Sumber dan attribution

- Upstream utama: [MCCTeam/Minecraft-Console-Client](https://github.com/MCCTeam/Minecraft-Console-Client)
- Android UI dan integrasi: MCC Droid Android Native
- UI audio: [romainsimon/uisfx](https://github.com/romainsimon/uisfx), soft pack, CC0 audio
- Dokumentasi Gemini: [Google AI for Developers](https://ai.google.dev/)

## Kontribusi

Issue dan pull request dipersilakan. Saat melaporkan bug, sertakan:

- Versi APK.
- Versi Android dan arsitektur perangkat.
- Ukuran teks console dan jenis encoding/format server yang digunakan.
- Langkah reproduksi.
- Potongan log yang sudah disensor dari API key, password, token, dan data pribadi.

## Lisensi

MCC Droid adalah fork/adaptasi dan harus dibaca bersama lisensi upstream Minecraft Console Client. Komponen Android tambahan, asset, dan dependency pihak ketiga memiliki lisensi masing-masing. Lihat [NOTICE.md](NOTICE.md) dan dokumentasi upstream sebelum redistribusi.
