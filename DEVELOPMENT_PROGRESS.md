# Development Progress

## Milestone 7 — Console-only dan AI context update

Terminal dikembalikan menjadi halaman console MCC murni. Modern Chat dihapus, Shell dipindahkan ke Pengaturan, dan kontrol ukuran teks `−/+` ditambahkan agar pengguna dapat melihat lebih banyak baris. Jalur stdout MCC sekarang membaca byte per baris dengan decoder UTF-8 strict dan fallback byte-safe. Heuristik penghapusan token hex yang dapat menghilangkan bagian nama atau pesan dihapus; format warna eksplisit tetap diparsing tanpa merusak teks biasa.

Akses Gemini dari header Dashboard dan menu Lainnya dihapus. Gemini hanya dibuka melalui floating popup di sisi kiri Dashboard. Request Gemini kini menyertakan riwayat percakapan terbaru, instruksi penggunaan tool berbasis konteks, dan cakupan setting aplikasi yang lebih luas melalui confirmation flow.

## Milestone 0 — Baseline

- Source MCC Droid Android Native diambil dari source kerja dan baseline patched APK.
- Runtime MCC disimpan sebagai bundle ARM64 terpisah dan dikemas ke APK saat release packaging.
- Struktur aplikasi memakai Jetpack Compose, session manager, profile store, dan log buffer.

## Milestone 1 — Config interaction fixes

- Memperbaiki banner perubahan agar tidak menutupi area input.
- Memperbaiki input field agar teks langsung tampil saat mengetik.
- Memastikan slider, dropdown, switch, dan editor config membaca state terbaru.
- Menjaga perubahan config tetap konsisten antara visual editor dan raw editor.

## Milestone 2 — Console reliability

- Menambahkan normalisasi Unicode dan perbaikan mojibake.
- Memproses warna Minecraft `§`, ANSI SGR, dan extended hex colors.
- Menambahkan heuristik format warna hex yang kehilangan separator.
- Memperbaiki rendering span agar output server berwarna tetap terbaca.
- Console mendukung copy, save, clear, history, shortcut, dan auto-follow.

## Milestone 3 — Dashboard dan launcher UI

- Menambahkan Dashboard hero header dengan gradient, glow, pulse, dan status live.
- Mengecilkan logo cube agar lebih simetris di adaptive icon.
- Menambahkan intro startup dengan blur dan animasi.
- Menambahkan shortcut command adaptif maksimal lima command yang sering digunakan.

## Milestone 4 — Gemini Assistant

- Menambahkan Gemini API key storage menggunakan Android Keystore.
- Menambahkan daftar dan pemilihan model Google AI Studio.
- Menambahkan Gemini Assistant.
- Menambahkan tool sandbox untuk membaca dan mengubah data MCC.
- Menambahkan konfirmasi sebelum write config, write file, create script, create automation, setting change, dan send command.
- Menambahkan popup AI besar dari tombol kecil di Dashboard.
- Chat dan progres tetap ada ketika popup ditutup, tetapi tidak dipersistenkan setelah proses aplikasi berhenti.

## Milestone 5 — Modern Chat UI

- Menambahkan setting `consoleViewMode` dengan nilai `console` atau `modern`.
- Console mode mempertahankan tampilan monospace/debug.
- Modern Chat menampilkan pesan MCC, command pengguna, sistem, dan error sebagai bubble berbeda.
- Menambahkan timestamp, copy baris, open URL, auto-follow, input command, history, dan shortcut.
- Menambahkan fade/slide transition saat pesan masuk.
- Kompilasi Kotlin berhasil setelah validasi source.

## Milestone 6 — Soft UI sound pack

- Mengganti sound sintetis dan pack glass dengan UI SFX soft pack.
- Sumber: `romainsimon/uisfx`.
- Audio: CC0 1.0.
- Cue: select, success, error, open, close, copy, send, toggle, delete, processing, notification, dan lainnya.
- Menyimpan notice lisensi di `app/src/main/res/raw/uisfx_cc0_license.txt`.

## Release validation — 1.0.4

- `assembleRelease`: sukses.
- `versionCode`: 4.
- `versionName`: 1.0.4.
- Package: `app.mccdroid`.
- Runtime MCC tersedia di `assets/mcc-bundle.zip`.
- `bundle-info.json` tersedia di assets.
- APK alignment: sukses.
- APK Signature Scheme v2/v3: valid.
- Resource Modern Chat, Gemini, dan sound terdaftar di `resources.arsc`.
- SHA-256 APK: `f675627dd9d89ab087b8d7b9732f60aa9ba1738bea48964add8da250a2dd36ce`.
