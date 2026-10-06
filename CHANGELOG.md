# Changelog

## Unreleased — Console-only MCC dan AI context

- Menghapus Modern Chat UI dari halaman Terminal.
- Menjadikan halaman Terminal khusus console MCC monospace.
- Memindahkan Shell Android ke halaman terpisah dari Pengaturan.
- Menambahkan tombol `−/+` kecil di kanan atas console untuk mengatur ukuran teks.
- Memperbaiki pembacaan output MCC dengan decoder UTF-8 strict dan fallback byte-safe.
- Menghapus heuristik yang sebelumnya dapat menghilangkan token huruf/angka valid dari nama pemain dan chat.
- Menambahkan dukungan marker warna eksplisit `#RRGGBB` dan `&RRGGBB` tanpa mengorbankan teks biasa.
- Menghapus tombol AI dari header Dashboard dan menu langsung; AI hanya dibuka melalui popup floating di kiri.
- Menambahkan riwayat percakapan ke request Gemini agar AI memahami konteks.
- Memperluas setting aplikasi yang dapat diubah AI melalui confirmation flow.

## 1.0.4 — Modern Chat, Gemini AI, dan soft UI sounds

- Menambahkan Modern Chat sebagai eksperimen UI. Fitur ini kemudian dihapus pada milestone Unreleased agar Terminal kembali fokus sebagai console MCC murni.
- Menambahkan pilihan tampilan console dan Modern Chat pada milestone sebelumnya.
- Menambahkan Gemini Assistant dari Dashboard dengan sandbox tools dan confirmation flow.
- Menambahkan config editor, automation, notification, file manager, dan adaptive command shortcuts.
- Menambahkan soft UI sound pack 17 cue dengan lisensi CC0.
- Memperbaiki Unicode, mojibake, ANSI, dan warna Minecraft pada console.
- Menambahkan runtime MCC ke APK final pada `assets/mcc-bundle.zip`.
- Validasi final: build sukses, APK aligned, signature v2/v3 valid, runtime tersedia.

## 1.0.3 — Gemini dan UI dasar

- Integrasi Google Gemini API melalui REST.
- Penyimpanan API key menggunakan Android Keystore.
- Pemilihan model Gemini dari Google AI Studio.
- Tool AI untuk config, file, script, automation, dan command.
- Perbaikan input config, slider, dropdown, switch, dan perubahan state.
- Dashboard hero dengan glow dan status interaktif.

## 1.0.2 — Baseline patched build

- Baseline APK patched dengan runtime MCC.
- Perbaikan awal konfigurasi dan packaging runtime.
