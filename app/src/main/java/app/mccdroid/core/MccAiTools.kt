package app.mccdroid.core

import android.content.Context
import app.mccdroid.logic.ActionType
import app.mccdroid.logic.Automation
import app.mccdroid.logic.CommandCatalog
import app.mccdroid.logic.ConfigDoc
import app.mccdroid.logic.ConfigHints
import app.mccdroid.logic.EventPref
import app.mccdroid.logic.Level
import app.mccdroid.logic.LineKind
import app.mccdroid.logic.LogLine
import app.mccdroid.logic.MatchType
import app.mccdroid.logic.McEvent
import app.mccdroid.logic.NotifyLogic
import app.mccdroid.logic.Rule
import app.mccdroid.logic.RuleAction
import app.mccdroid.logic.TriggerKind
import app.mccdroid.logic.VKind
import app.mccdroid.logic.asArr
import app.mccdroid.logic.asObj
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Semua tool yang boleh dipanggil Gemini. Seluruh akses file dibatasi ke folder data MCC
 * (sandbox aplikasi). Penghapusan tidak permanen: berkas dipindah ke `.ai_trash` di folder data.
 */
object MccAiTools {
    enum class Risk { READ, WRITE, DANGER }

    class Tool(
        val name: String,
        val description: String,
        val risk: Risk,
        val props: Map<String, Any?> = emptyMap(),
        val required: List<String> = emptyList(),
        val preview: (Map<String, Any?>) -> String,
        val run: suspend (Context, Map<String, Any?>) -> String,
    )

    private const val MAX_READ = 48 * 1024
    private const val MAX_WRITE = 256 * 1024
    private const val TRASH = ".ai_trash"

    private val TEXT_EXT = setOf(
        "ini", "json", "txt", "log", "cs", "toml", "yaml", "yml", "conf", "cfg", "properties",
        "md", "xml", "csv", "tsv", "sh", "lang", "bak", "jsonc", "jsonl", "mcmeta", "html", "js", "py", "lua", "kts", "gradle",
    )

    // ---- Registry ----------------------------------------------------------------------------

    private val tools: List<Tool> = listOf(
        // ===== Baca / konteks =====
        Tool("get_overview", "Ringkasan keadaan terkini: semua profil + status sesi, akun, event terakhir, ekor console profil terpilih, otomasi, notifikasi, setting. Panggil ini dulu bila konteks kurang.", Risk.READ,
            preview = { "Baca ringkasan aplikasi" },
            run = { ctx, _ -> AiContext.snapshot(ctx, AiSession.contextProfileId, consoleLines = 30) }),

        Tool("list_profiles", "Daftar profil MCC beserta status sesi.", Risk.READ,
            preview = { "Daftar profil" },
            run = { _, _ -> profileList().ifBlank { "Belum ada profil." } }),

        Tool("read_console", "Baca baris terakhir console sebuah profil (teks bersih tanpa kode warna). Gunakan untuk diagnosa: kenapa terputus, error, chat, dsb.", Risk.READ,
            props = mapOf(
                "profileId" to p("string", "ID atau nama profil. Kosong = profil yang sedang dipilih pengguna."),
                "lines" to p("integer", "Jumlah baris terakhir (1-300, default 80)."),
                "contains" to p("string", "Opsional: hanya baris yang mengandung teks ini (tanpa beda huruf besar/kecil)."),
            ),
            preview = { "Baca console ${it.s("profileId").ifBlank { "(profil terpilih)" }}" },
            run = { _, a -> readConsole(a) }),

        Tool("list_files", "Daftar file/folder di folder data MCC (path relatif, mis. 'profiles/ab12cd34' atau 'shared/scripts'). Kosong = akar.", Risk.READ,
            props = mapOf(
                "path" to p("string", "Folder relatif. Kosong = akar data."),
                "depth" to p("integer", "Kedalaman (1-6, default 3)."),
            ),
            preview = { "Daftar file ${it.s("path").ifBlank { "/" }}" },
            run = { ctx, a -> listFiles(ctx, a.s("path"), a.i("depth", 3).coerceIn(1, 6)) }),

        Tool("read_file", "Baca file teks (ini, json, txt, log, cs, md, dll) relatif ke folder data MCC. Untuk file besar pakai offset/limit (karakter).", Risk.READ,
            props = mapOf(
                "path" to p("string", "Path relatif, mis. 'profiles/ab12cd34/MinecraftClient.ini'."),
                "offset" to p("integer", "Mulai dari karakter ke-N (default 0)."),
                "limit" to p("integer", "Maks karakter dibaca (default 40000)."),
            ),
            required = listOf("path"),
            preview = { "Baca ${it.s("path")}" },
            run = { ctx, a -> readFile(ctx, a.s("path"), a.i("offset", 0), a.i("limit", 40_000)) }),

        Tool("search_files", "Cari teks di semua file teks dalam folder data MCC (seperti grep). Hasil: path:baris: isi.", Risk.READ,
            props = mapOf(
                "query" to p("string", "Teks atau regex yang dicari."),
                "path" to p("string", "Batasi ke folder tertentu. Kosong = semua."),
                "regex" to p("boolean", "True bila query adalah regex."),
            ),
            required = listOf("query"),
            preview = { "Cari '${it.s("query")}'" },
            run = { ctx, a -> searchFiles(ctx, a.s("query"), a.s("path"), a.b("regex") ?: false) }),

        Tool("read_config", "Baca MinecraftClient.ini sebuah profil secara utuh.", Risk.READ,
            props = mapOf("profileId" to p("string", "ID atau nama profil. Kosong = profil terpilih.")),
            preview = { "Baca config profil ${it.s("profileId").ifBlank { "(terpilih)" }}" },
            run = { ctx, a ->
                val f = Paths.configFile(ctx, profileId(a))
                if (f.exists()) f.readText(Charsets.UTF_8).take(MAX_READ) else "Config belum ada. Gunakan generate_default_config."
            }),

        Tool("search_config", "Cari setting di config MCC berdasarkan kata (nama, deskripsi). Mengembalikan section, key, nilai saat ini, dan keterangan.", Risk.READ,
            props = mapOf(
                "profileId" to p("string", "ID atau nama profil. Kosong = profil terpilih."),
                "query" to p("string", "Kata kunci, mis. 'autorespawn' atau 'timeout'."),
            ),
            required = listOf("query"),
            preview = { "Cari setting '${it.s("query")}'" },
            run = { ctx, a -> searchConfig(ctx, a) }),

        Tool("get_config_value", "Baca satu setting config (nilai, tipe, keterangan, pilihan).", Risk.READ,
            props = mapOf(
                "profileId" to p("string", "ID atau nama profil. Kosong = profil terpilih."),
                "section" to p("string", "Nama section, mis. 'Main.General' atau 'ChatBot.AutoRelog'."),
                "key" to p("string", "Nama key, mis. 'Account'."),
            ),
            required = listOf("section", "key"),
            preview = { "Baca setting [${it.s("section")}] ${it.s("key")}" },
            run = { ctx, a -> getConfigValue(ctx, a) }),

        Tool("list_automations", "Daftar semua otomasi (aturan pemicu → aksi) dalam JSON.", Risk.READ,
            preview = { "Daftar otomasi" },
            run = { _, _ -> Automation.toJson(RuleStore.rules.value).take(MAX_READ).ifBlank { "[]" } }),

        Tool("get_notification_config", "Baca pengaturan notifikasi (level per event, jam tenang, kata kunci).", Risk.READ,
            preview = { "Baca pengaturan notifikasi" },
            run = { _, _ -> NotifyLogic.toJson(NotifyStore.config.value) }),

        Tool("get_app_settings", "Baca semua setting aplikasi MCC Droid.", Risk.READ,
            preview = { "Baca setting aplikasi" },
            run = { _, _ -> appSettingsText() }),

        Tool("list_mcc_commands", "Daftar perintah internal MCC (nama, sintaks, fungsi).", Risk.READ,
            preview = { "Daftar perintah MCC" },
            run = { _, _ -> CommandCatalog.all.joinToString("\n") { "/${it.usage} — ${it.desc} [${it.group}]" } }),

        Tool("read_runtime_info", "Daftar file runtime MCC (read-only) untuk diagnosa.", Risk.READ,
            preview = { "Baca info runtime" },
            run = { ctx, _ -> listTree(Paths.runtimeDir(ctx), 2, 200) }),

        Tool("search_console", "Cari teks/regex di console (buffer memori) satu profil atau semua profil. Berguna untuk mencari kapan sebuah error, kick, atau chat terjadi. Hasil: [jam] profil: baris.", Risk.READ,
            props = mapOf(
                "pattern" to p("string", "Teks atau regex yang dicari."),
                "profileId" to p("string", "ID/nama profil, atau '*' untuk semua profil. Kosong = profil terpilih."),
                "regex" to p("boolean", "True bila pattern adalah regex."),
                "context" to p("integer", "Baris konteks sebelum/sesudah tiap hasil (0-5, default 0)."),
                "maxResults" to p("integer", "Maks hasil (1-100, default 40)."),
            ),
            required = listOf("pattern"),
            preview = { "Cari di console: '${it.s("pattern")}'" },
            run = { _, a -> searchConsole(a) }),

        Tool("wait_for_console", "Tunggu sampai baris console BARU yang cocok muncul (atau timeout), lalu kembalikan semua baris baru. Pakai setelah send_mcc_command / send_chat untuk membaca balasan server, atau setelah start/restart untuk menunggu 'online'. Pattern kosong = tunggu keluaran baru apa pun.", Risk.READ,
            props = mapOf(
                "profileId" to p("string", "ID/nama profil. Kosong = profil terpilih."),
                "pattern" to p("string", "Teks atau regex yang ditunggu (hanya baris keluaran, bukan echo perintah)."),
                "regex" to p("boolean", "True bila pattern adalah regex."),
                "timeoutSec" to p("integer", "Batas tunggu detik (1-60, default 15)."),
            ),
            preview = { "Tunggu console${if (it.s("pattern").isNotEmpty()) " '${it.s("pattern")}'" else ""} (${it.i("timeoutSec", 15)} dtk)" },
            run = { _, a -> waitForConsole(a) }),

        Tool("get_session_status", "Status rinci sesi MCC: state, uptime, akun, event terakhir, kode login perangkat Microsoft bila ada, jumlah baris log. profileId kosong atau '*' = semua profil.", Risk.READ,
            props = mapOf("profileId" to p("string", "ID/nama profil; kosong atau '*' = semua.")),
            preview = { "Status sesi ${it.s("profileId").ifBlank { "(semua)" }}" },
            run = { ctx, a -> sessionStatus(ctx, a) }),

        Tool("get_resource_usage", "Pemakaian CPU dan RAM perangkat serta aplikasi (sampel ~1 detik).", Risk.READ,
            preview = { "Ukur pemakaian resource" },
            run = { ctx, _ -> resourceUsage(ctx) }),

        Tool("read_shell_output", "Baca keluaran terakhir layar Shell di aplikasi (sesi shell interaktif milik pengguna).", Risk.READ,
            props = mapOf("lines" to p("integer", "Jumlah baris terakhir (1-300, default 60).")),
            preview = { "Baca keluaran Shell" },
            run = { _, a ->
                ShellSession.log.flush()
                val lines = ShellSession.log.flow.value.takeLast(a.i("lines", 60).coerceIn(1, 300))
                if (lines.isEmpty()) "(Shell belum dipakai atau kosong)" else lines.joinToString("\n") { it.clean.take(300) }
            }),

        Tool("list_changes", "Jurnal perubahan yang pernah dilakukan AI (tulis/hapus/ubah), terbaru di bawah. Berguna untuk tahu apa yang sudah dikerjakan di percakapan sebelumnya.", Risk.READ,
            props = mapOf("count" to p("integer", "Jumlah entri (1-100, default 30).")),
            preview = { "Baca jurnal perubahan" },
            run = { _, a -> AiJournal.format(AiJournal.recent(a.i("count", 30).coerceIn(1, 100))) }),

        // Memori permanen: tanpa persetujuan karena hanya menyimpan catatan internal AI (bisa dilihat/dihapus pengguna di layar AI).
        Tool("remember", "Simpan SATU fakta tahan lama ke memori permanen AI (preferensi pengguna, tujuan server, konvensi, keputusan penting), maks 400 karakter. Tersedia di semua percakapan berikutnya. JANGAN simpan password/token/API key atau hal sementara.", Risk.READ,
            props = mapOf("note" to p("string", "Fakta ringkas satu kalimat.")),
            required = listOf("note"),
            preview = { "Ingat: ${snippet(it.s("note"), 120)}" },
            run = { _, a -> "Tersimpan di memori (id ${AiMemory.add(a.s("note")).id})." }),

        Tool("forget", "Hapus satu catatan memori permanen lewat id (atau potongan teks yang cocok dengan tepat satu catatan).", Risk.READ,
            props = mapOf("note" to p("string", "ID catatan atau potongan teksnya.")),
            required = listOf("note"),
            preview = { "Lupakan: ${snippet(it.s("note"), 80)}" },
            run = { _, a ->
                val gone = AiMemory.remove(a.s("note")) ?: error("Tidak ada catatan unik yang cocok dengan '${a.s("note")}'.")
                "Catatan [${gone.id}] dihapus."
            }),

        // ===== Tulis: file =====
        Tool("write_file", "Buat atau timpa file teks. Bila sudah ada, cadangan .bak dibuat. Untuk perubahan kecil gunakan edit_file.", Risk.WRITE,
            props = mapOf("path" to p("string", "Path relatif."), "content" to p("string", "Isi file lengkap.")),
            required = listOf("path", "content"),
            preview = { "Tulis file ${it.s("path")} (${it.s("content").length} karakter)\n" + snippet(it.s("content")) },
            run = { ctx, a -> writeFile(ctx, a.s("path"), a.s("content")) }),

        Tool("edit_file", "Ganti teks persis di file (find → replace). Lebih aman daripada menulis ulang. Gagal bila teks tidak ditemukan atau ambigu (kecuali replaceAll).", Risk.WRITE,
            props = mapOf(
                "path" to p("string", "Path relatif."),
                "find" to p("string", "Teks persis yang dicari (sertakan konteks agar unik)."),
                "replace" to p("string", "Teks pengganti."),
                "replaceAll" to p("boolean", "True untuk mengganti semua kemunculan."),
            ),
            required = listOf("path", "find", "replace"),
            preview = { "Edit ${it.s("path")}\n− ${snippet(it.s("find"), 160)}\n+ ${snippet(it.s("replace"), 160)}" },
            run = { ctx, a -> editFile(ctx, a.s("path"), a.s("find"), a.s("replace"), a.b("replaceAll") ?: false) }),

        Tool("move_file", "Pindah/ganti nama file atau folder (juga untuk memulihkan dari .ai_trash).", Risk.WRITE,
            props = mapOf("from" to p("string", "Path asal."), "to" to p("string", "Path tujuan (belum ada).")),
            required = listOf("from", "to"),
            preview = { "Pindah ${it.s("from")} → ${it.s("to")}" },
            run = { ctx, a -> moveFile(ctx, a.s("from"), a.s("to")) }),

        Tool("make_dir", "Buat folder.", Risk.WRITE,
            props = mapOf("path" to p("string", "Path folder relatif.")),
            required = listOf("path"),
            preview = { "Buat folder ${it.s("path")}" },
            run = { ctx, a -> safeFile(ctx, a.s("path")).also { it.mkdirs() }.let { "Folder ${rel(ctx, it)} siap." } }),

        Tool("create_script", "Buat script C# MCC di shared/scripts (format //MCCScript 1.0). Dijalankan dengan /script <nama>.", Risk.WRITE,
            props = mapOf("name" to p("string", "Nama file, mis. 'afk.cs'."), "content" to p("string", "Isi script C# lengkap.")),
            required = listOf("name", "content"),
            preview = { "Buat script ${it.s("name")}\n" + snippet(it.s("content")) },
            run = { ctx, a ->
                require(a.s("content").isNotBlank()) { "Isi script kosong." }
                val n = a.s("name").substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val file = File(Paths.scriptsDir(ctx), if (n.endsWith(".cs")) n else "$n.cs")
                val bak = backup(file)
                Paths.writeAtomic(file, a.s("content"))
                "Script ${file.name} disimpan di shared/scripts${bak?.let { " (cadangan ${it.name})" } ?: ""}."
            }),

        // ===== Tulis: config =====
        Tool("set_config_value", "Ubah satu setting di MinecraftClient.ini tanpa menyentuh bagian lain (komentar dipertahankan). Cadangan .bak dibuat. Berlaku setelah /reload atau restart sesi.", Risk.WRITE,
            props = mapOf(
                "profileId" to p("string", "ID atau nama profil. Kosong = profil terpilih."),
                "section" to p("string", "Section, mis. 'Main.Advanced'."),
                "key" to p("string", "Key, mis. 'AutoRespawn'."),
                "value" to p("string", "Nilai baru. bool: true/false; angka; teks tanpa tanda kutip; array: JSON ['a','b'] atau dipisah koma."),
            ),
            required = listOf("section", "key", "value"),
            preview = { "Config [${it.s("section")}] ${it.s("key")} = ${snippet(it.s("value"), 120)}" },
            run = { ctx, a -> setConfigValue(ctx, a) }),

        Tool("write_config", "Tulis ulang seluruh MinecraftClient.ini (cadangan .bak dibuat). Pakai hanya bila set_config_value tidak cukup.", Risk.WRITE,
            props = mapOf("profileId" to p("string", "ID atau nama profil."), "content" to p("string", "Isi INI/TOML lengkap.")),
            required = listOf("content"),
            preview = { "Tulis ulang config profil ${it.s("profileId").ifBlank { "(terpilih)" }} (${it.s("content").length} karakter)" },
            run = { ctx, a ->
                require(a.s("content").isNotBlank()) { "Isi config kosong." }
                require(a.s("content").length <= MAX_WRITE) { "Config terlalu besar." }
                ConfigDoc.parse(a.s("content"))
                val f = Paths.configFile(ctx, profileId(a))
                val bak = backup(f)
                Paths.writeAtomic(f, a.s("content"))
                "Config tersimpan${bak?.let { " (cadangan ${it.name})" } ?: ""}. Jalankan /reload atau restart sesi agar berlaku."
            }),

        Tool("generate_default_config", "Jalankan MCC sebentar agar membuat MinecraftClient.ini default untuk profil (butuh runtime siap, bisa sampai 60 detik).", Risk.WRITE,
            props = mapOf("profileId" to p("string", "ID atau nama profil.")),
            preview = { "Buat config default profil ${it.s("profileId").ifBlank { "(terpilih)" }}" },
            run = { ctx, a ->
                val id = profileId(a)
                if (Diagnostics.generateDefaultConfig(ctx, id)) "Config default tersedia untuk profil $id." else error("Gagal membuat config default (runtime belum siap?).")
            }),

        // ===== Tulis: profil & sesi =====
        Tool("create_profile", "Buat profil MCC baru (folder + config sendiri).", Risk.WRITE,
            props = mapOf(
                "name" to p("string", "Nama profil."),
                "notes" to p("string", "Catatan."),
                "extraArgs" to p("string", "Argumen tambahan MCC."),
                "autoStart" to p("boolean", "Mulai otomatis saat aplikasi dibuka."),
                "autoRestart" to p("boolean", "Mulai ulang otomatis bila crash."),
            ),
            required = listOf("name"),
            preview = { "Buat profil '${it.s("name")}'" },
            run = { _, a ->
                val created = ProfileStore.create(a.s("name"))
                ProfileStore.update(created.copy(
                    notes = a.s("notes"), extraArgs = a.s("extraArgs"),
                    autoStart = a.b("autoStart") ?: false, autoRestart = a.b("autoRestart") ?: true,
                ))
                "Profil '${created.name}' dibuat dengan id ${created.id}."
            }),

        Tool("update_profile", "Ubah properti profil (nama, catatan, argumen, mulai/restart otomatis). Hanya field yang diberikan yang berubah.", Risk.WRITE,
            props = mapOf(
                "profileId" to p("string", "ID atau nama profil."),
                "name" to p("string", "Nama baru."),
                "notes" to p("string", "Catatan baru."),
                "extraArgs" to p("string", "Argumen tambahan baru."),
                "autoStart" to p("boolean", "Mulai otomatis."),
                "autoRestart" to p("boolean", "Mulai ulang otomatis."),
            ),
            preview = { "Ubah profil ${it.s("profileId").ifBlank { "(terpilih)" }}: " + it.keys.filter { k -> k != "profileId" }.joinToString() },
            run = { _, a ->
                val old = ProfileStore.get(profileId(a))!!
                ProfileStore.update(old.copy(
                    name = if (a.has("name") && a.s("name").isNotBlank()) a.s("name") else old.name,
                    notes = if (a.has("notes")) a.s("notes") else old.notes,
                    extraArgs = if (a.has("extraArgs")) a.s("extraArgs") else old.extraArgs,
                    autoStart = a.b("autoStart") ?: old.autoStart,
                    autoRestart = a.b("autoRestart") ?: old.autoRestart,
                ))
                "Profil ${old.id} diperbarui."
            }),

        Tool("duplicate_profile", "Gandakan profil beserta config-nya.", Risk.WRITE,
            props = mapOf("profileId" to p("string", "ID atau nama profil.")),
            preview = { "Gandakan profil ${it.s("profileId").ifBlank { "(terpilih)" }}" },
            run = { _, a ->
                val copy = ProfileStore.duplicate(profileId(a)) ?: error("Gagal menggandakan.")
                "Profil salinan '${copy.name}' dibuat dengan id ${copy.id}."
            }),

        Tool("start_session", "Jalankan MCC untuk sebuah profil.", Risk.WRITE,
            props = mapOf("profileId" to p("string", "ID atau nama profil.")),
            preview = { "Mulai sesi ${it.s("profileId").ifBlank { "(terpilih)" }}" },
            run = { _, a ->
                val id = profileId(a)
                SessionManager.start(id)?.let { error(it) }
                "Sesi $id dimulai."
            }),

        Tool("stop_session", "Hentikan MCC sebuah profil dengan sopan (/quit).", Risk.WRITE,
            props = mapOf("profileId" to p("string", "ID atau nama profil.")),
            preview = { "Hentikan sesi ${it.s("profileId").ifBlank { "(terpilih)" }}" },
            run = { _, a -> val id = profileId(a); SessionManager.stop(id); "Perintah berhenti dikirim ke sesi $id." }),

        Tool("restart_session", "Mulai ulang MCC sebuah profil.", Risk.WRITE,
            props = mapOf("profileId" to p("string", "ID atau nama profil.")),
            preview = { "Mulai ulang sesi ${it.s("profileId").ifBlank { "(terpilih)" }}" },
            run = { _, a -> val id = profileId(a); SessionManager.restart(id); "Sesi $id dimulai ulang." }),

        Tool("send_mcc_command", "Kirim perintah internal MCC (harus diawali '/', mis. /health, /list, /reload, /send ...) ke sesi yang berjalan.", Risk.WRITE,
            props = mapOf("profileId" to p("string", "ID atau nama profil."), "command" to p("string", "Perintah diawali '/'.")),
            required = listOf("command"),
            preview = { "Kirim ${it.s("command")} ke ${it.s("profileId").ifBlank { "(profil terpilih)" }}" },
            run = { _, a ->
                val cmd = a.s("command").trim()
                require(cmd.startsWith("/")) { "Perintah MCC harus diawali '/'. Untuk chat gunakan send_chat." }
                val id = profileId(a)
                require(SessionManager.session(id).isAlive) { "Sesi $id tidak berjalan. Gunakan start_session." }
                SessionManager.session(id).send(cmd)
                "Perintah dikirim ke $id. Lihat hasilnya dengan wait_for_console (menunggu balasan) atau read_console."
            }),

        Tool("send_chat", "Kirim pesan chat ke server Minecraft atas nama akun (terlihat pemain lain!). Teks berawalan '/' dikirim sebagai command server lewat /send.", Risk.WRITE,
            props = mapOf("profileId" to p("string", "ID atau nama profil."), "text" to p("string", "Isi chat atau command server.")),
            required = listOf("text"),
            preview = { "Chat ke server: ${snippet(it.s("text"), 200)}" },
            run = { _, a ->
                val t = a.s("text").trim()
                require(t.isNotEmpty()) { "Teks kosong." }
                val id = profileId(a)
                require(SessionManager.session(id).isAlive) { "Sesi $id tidak berjalan." }
                SessionManager.session(id).send(if (t.startsWith("/")) "/send $t" else t)
                "Chat dikirim lewat sesi $id. Verifikasi dengan wait_for_console bila perlu."
            }),

        // ===== Tulis: otomasi =====
        Tool("create_automation", "Buat otomasi: pemicu (baris log cocok / event) → satu atau lebih aksi. Placeholder: {line}, {1}.. (grup regex), {name}, {detail}, {event}.", Risk.WRITE,
            props = automationProps(forUpdate = false),
            required = listOf("name", "actions"),
            preview = { "Buat otomasi '${it.s("name")}' — ${describeTrigger(it)} → ${describeActions(it)}" },
            run = { _, a -> saveAutomation(a, null) }),

        Tool("update_automation", "Ubah otomasi yang ada (cari lewat id atau nama). Hanya field yang diberikan yang berubah; gunakan enabled=false untuk menonaktifkan.", Risk.WRITE,
            props = automationProps(forUpdate = true),
            required = listOf("ruleId"),
            preview = { "Ubah otomasi ${it.s("ruleId")}: " + it.keys.filter { k -> k != "ruleId" }.joinToString() },
            run = { _, a ->
                val key = a.s("ruleId")
                val old = RuleStore.rules.value.firstOrNull { it.id == key } ?: RuleStore.rules.value.firstOrNull { it.name.equals(key, true) }
                    ?: error("Otomasi '$key' tidak ditemukan. Gunakan list_automations.")
                saveAutomation(a, old)
            }),

        Tool("update_notifications", "Ubah pengaturan notifikasi: jam tenang, kata kunci (menggantikan daftar lama), dan level per event.", Risk.WRITE,
            props = mapOf(
                "quietEnabled" to p("boolean", "Aktifkan jam tenang."),
                "quietStart" to p("string", "Mulai jam tenang 'HH:mm'."),
                "quietEnd" to p("string", "Akhir jam tenang 'HH:mm'."),
                "quietAllowCritical" to p("boolean", "Tetap tampilkan event kritis saat jam tenang."),
                "keywords" to arr(p("string", "Kata kunci"), "Daftar kata kunci pemicu notifikasi (menggantikan yang lama)."),
                "events" to arr(
                    obj(mapOf(
                        "event" to p("string", "Nama event.", McEvent.values().map { it.name }),
                        "level" to p("string", "Level notifikasi.", Level.values().map { it.name }),
                        "vibrate" to p("boolean", "Getar."),
                        "cooldownSec" to p("integer", "Jeda minimum antar notifikasi (detik)."),
                    ), listOf("event")),
                    "Preferensi per event.",
                ),
            ),
            preview = { "Ubah notifikasi: " + it.keys.joinToString() },
            run = { _, a -> updateNotifications(a) }),

        Tool("set_app_setting", "Ubah setting aplikasi: themeMode(system|dark|light|amoled), dynamicColor, accentIdx(0-5), consoleFontSp(9-20), maxLogLines(500-10000), wrapLines, showTimestamps, saveConsoleLog, keepScreenOn, wakeLock, wifiLock, bootStart, heapLimitMb(0-2048), consoleViewMode(console|modern), soundTheme(neo|soft|glass|tech|minimal|pulse|airy|carbon|pixel|chime|velvet|spark|orbit|mono|bloom), soundVolume(0-1), soundEnabled.", Risk.WRITE,
            props = mapOf("key" to p("string", "Nama setting."), "value" to p("string", "Nilai baru.")),
            required = listOf("key", "value"),
            preview = { "Setting ${it.s("key")} = ${it.s("value")}" },
            run = { _, a -> setAppSetting(a.s("key"), a.s("value")) }),

        Tool("append_file", "Tambahkan teks ke akhir file teks (dibuat bila belum ada; cadangan .bak dibuat). Cocok untuk menambah baris tanpa menulis ulang seluruh file.", Risk.WRITE,
            props = mapOf("path" to p("string", "Path relatif."), "content" to p("string", "Teks yang ditambahkan.")),
            required = listOf("path", "content"),
            preview = { "Tambah ke ${it.s("path")} (${it.s("content").length} karakter)\n" + snippet(it.s("content")) },
            run = { ctx, a -> appendFile(ctx, a.s("path"), a.s("content")) }),

        Tool("copy_file", "Salin file atau folder (tujuan belum boleh ada). Cocok untuk membuat cadangan atau template.", Risk.WRITE,
            props = mapOf("from" to p("string", "Path asal."), "to" to p("string", "Path tujuan (belum ada).")),
            required = listOf("from", "to"),
            preview = { "Salin ${it.s("from")} → ${it.s("to")}" },
            run = { ctx, a -> copyFile(ctx, a.s("from"), a.s("to")) }),

        Tool("restore_backup", "Pulihkan file teks dari cadangan .bak di sampingnya (dibuat otomatis tiap kali AI menulis/mengedit). Versi sebelum dipulihkan ditukar ke .bak, jadi menjalankannya dua kali berarti membatalkan.", Risk.WRITE,
            props = mapOf("path" to p("string", "Path file asli (tanpa .bak).")),
            required = listOf("path"),
            preview = { "Pulihkan ${it.s("path")} dari cadangan .bak" },
            run = { ctx, a -> restoreBackup(ctx, a.s("path")) }),

        Tool("send_notification", "Tampilkan notifikasi Android dari aplikasi (mis. pengingat atau hasil tugas panjang).", Risk.WRITE,
            props = mapOf(
                "title" to p("string", "Judul."),
                "text" to p("string", "Isi."),
                "profileId" to p("string", "Opsional: profil yang dibuka saat notifikasi diketuk."),
            ),
            required = listOf("title", "text"),
            preview = { "Notifikasi: ${snippet(it.s("title"), 60)} — ${snippet(it.s("text"), 100)}" },
            run = { ctx, a ->
                require(a.s("title").isNotBlank() && a.s("text").isNotBlank()) { "Judul dan isi wajib diisi." }
                val pid = optionalProfile(a)?.takeIf { it.isNotEmpty() }
                Notifier.custom(ctx, pid?.let { ProfileStore.get(it) }, a.s("title").take(80), a.s("text").take(400))
                "Notifikasi ditampilkan."
            }),

        // ===== Berisiko: hapus & shell =====
        Tool("delete_file", "Hapus file/folder. Tidak permanen: dipindah ke folder .ai_trash di data MCC (bisa dipulihkan dengan move_file).", Risk.DANGER,
            props = mapOf("path" to p("string", "Path relatif.")),
            required = listOf("path"),
            preview = { "HAPUS ${it.s("path")} (dipindah ke .ai_trash)" },
            run = { ctx, a ->
                val f = safeFile(ctx, a.s("path"))
                require(f.exists()) { "Tidak ditemukan: ${a.s("path")}" }
                require(!isProtected(ctx, f)) { "Folder inti tidak boleh dihapus." }
                "Dipindah ke ${rel(ctx, trashMove(ctx, f))}."
            }),

        Tool("delete_profile", "Hapus profil beserta foldernya. Sesi dihentikan; salinan folder disimpan di .ai_trash.", Risk.DANGER,
            props = mapOf("profileId" to p("string", "ID atau nama profil.")),
            required = listOf("profileId"),
            preview = { "HAPUS profil ${it.s("profileId")} beserta foldernya (salinan ke .ai_trash)" },
            run = { ctx, a ->
                val id = profileId(a)
                val name = ProfileStore.get(id)?.name.orEmpty()
                val dir = File(Paths.profilesDir(ctx), id)
                val saved = if (dir.exists()) rel(ctx, trashCopy(ctx, dir)) else "-"
                ProfileStore.delete(id)
                "Profil '$name' ($id) dihapus. Salinan: $saved."
            }),

        Tool("delete_automation", "Hapus otomasi (cari lewat id atau nama).", Risk.DANGER,
            props = mapOf("ruleId" to p("string", "ID atau nama otomasi.")),
            required = listOf("ruleId"),
            preview = { "HAPUS otomasi ${it.s("ruleId")}" },
            run = { _, a ->
                val key = a.s("ruleId")
                val r = RuleStore.rules.value.firstOrNull { it.id == key } ?: RuleStore.rules.value.firstOrNull { it.name.equals(key, true) }
                    ?: error("Otomasi '$key' tidak ditemukan.")
                RuleStore.delete(r.id)
                "Otomasi '${r.name}' dihapus."
            }),

        Tool("empty_trash", "Kosongkan folder .ai_trash secara PERMANEN (tidak bisa dipulihkan lagi).", Risk.DANGER,
            preview = { "KOSONGKAN .ai_trash (hapus permanen semua isinya)" },
            run = { ctx, _ ->
                val t = File(Paths.dataRoot(ctx), TRASH)
                val n = t.listFiles()?.size ?: 0
                if (n == 0) "Tempat sampah sudah kosong." else { t.deleteRecursively(); "$n item di .ai_trash dihapus permanen." }
            }),

        Tool("run_shell", "Jalankan perintah shell Android (/system/bin/sh -c) di dalam sandbox aplikasi, timeout 30 detik, keluaran dibatasi. Tanpa input interaktif.", Risk.DANGER,
            props = mapOf("command" to p("string", "Perintah shell."), "workdir" to p("string", "Folder kerja relatif data MCC (default akar).")),
            required = listOf("command"),
            preview = { "SHELL: ${snippet(it.s("command"), 240)}" },
            run = { ctx, a -> runShell(ctx, a.s("command"), a.s("workdir")) }),
    )

    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    /** Deklarasi untuk Gemini. `parameters` dihilangkan untuk tool tanpa argumen (properties kosong ditolak API). */
    val declarations: List<Map<String, Any?>> = tools.map { t ->
        val d = linkedMapOf<String, Any?>("name" to t.name, "description" to t.description)
        if (t.props.isNotEmpty()) {
            val params = linkedMapOf<String, Any?>("type" to "object", "properties" to t.props)
            if (t.required.isNotEmpty()) params["required"] = t.required
            d["parameters"] = params
        }
        d
    }

    fun exists(name: String): Boolean = name in byName
    fun riskOf(name: String): Risk = byName[name]?.risk ?: Risk.DANGER

    /** @param mode 0 = tanya semua penulisan, 1 = tanya hanya yang berisiko, 2 = jangan tanya. */
    fun needsApproval(name: String, mode: Int): Boolean = when (riskOf(name)) {
        Risk.READ -> false
        Risk.WRITE -> mode < 1
        Risk.DANGER -> mode < 2
    }

    fun preview(name: String, args: Map<String, Any?>): String =
        byName[name]?.let { runCatching { it.preview(args) }.getOrDefault(name) } ?: name

    suspend fun execute(ctx: Context, name: String, args: Map<String, Any?>): String {
        val tool = byName[name] ?: error("Tool AI tidak dikenal: $name")
        return tool.run(ctx, args)
    }

    // ---- Skema -------------------------------------------------------------------------------

    private fun p(type: String, desc: String, enum: List<String>? = null): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("type" to type, "description" to desc)
        if (enum != null) m["enum"] = enum
        return m
    }

    private fun arr(items: Map<String, Any?>, desc: String): Map<String, Any?> =
        linkedMapOf("type" to "array", "description" to desc, "items" to items)

    private fun obj(props: Map<String, Any?>, required: List<String> = emptyList()): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("type" to "object", "properties" to props)
        if (required.isNotEmpty()) m["required"] = required
        return m
    }

    private fun automationProps(forUpdate: Boolean): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        if (forUpdate) m["ruleId"] = p("string", "ID atau nama otomasi yang diubah.")
        m["name"] = p("string", "Nama otomasi.")
        m["enabled"] = p("boolean", "Aktif atau tidak.")
        m["profileId"] = p("string", "ID atau nama profil; kosong = semua profil.")
        m["trigger"] = p("string", "LINE = baris log cocok, EVENT = kejadian terdeteksi.", TriggerKind.values().map { it.name })
        m["match"] = p("string", "Cara cocok untuk LINE.", MatchType.values().map { it.name })
        m["pattern"] = p("string", "Pola teks/regex untuk trigger LINE.")
        m["ignoreCase"] = p("boolean", "Abaikan huruf besar/kecil.")
        m["event"] = p("string", "Event untuk trigger EVENT.", McEvent.values().map { it.name })
        m["cooldownSec"] = p("integer", "Jeda minimum antar eksekusi (detik, min 1).")
        m["actions"] = arr(
            obj(mapOf(
                "type" to p("string", "SEND: kirim command MCC (arg harus diawali '/', mis. '/send halo' atau '/respawn'); NOTIFY: arg 'Judul|Isi'; DELAY: arg milidetik; RESTART/STOP: tanpa arg.", ActionType.values().map { it.name }),
                "arg" to p("string", "Argumen aksi."),
            ), listOf("type")),
            "Urutan aksi yang dijalankan.",
        )
        return m
    }

    // ---- Helper argumen ----------------------------------------------------------------------

    private fun Map<String, Any?>.s(k: String): String = when (val v = this[k]) {
        null -> ""
        is String -> v
        else -> v.toString()
    }

    private fun Map<String, Any?>.has(k: String): Boolean = this[k] != null

    private fun Map<String, Any?>.i(k: String, d: Int): Int =
        (this[k] as? Number)?.toInt() ?: (this[k] as? String)?.trim()?.toIntOrNull() ?: d

    private fun Map<String, Any?>.b(k: String): Boolean? = when (val v = this[k]) {
        is Boolean -> v
        is String -> v.trim().lowercase().toBooleanStrictOrNull()
        else -> null
    }

    private fun Map<String, Any?>.list(k: String): List<Any?> = this[k].asArr()

    private fun snippet(s: String, n: Int = 300): String {
        val t = s.trim().replace("\r", "")
        return if (t.length <= n) t else t.take(n) + "…"
    }

    private inline fun <reified T : Enum<T>> enumArg(a: Map<String, Any?>, key: String, default: T?): T? {
        val v = a.s(key).trim()
        if (v.isEmpty()) return default
        return enumValues<T>().firstOrNull { it.name.equals(v, true) }
            ?: error("Nilai $key '$v' tidak valid. Pilihan: " + enumValues<T>().joinToString { it.name })
    }

    // ---- Profil ------------------------------------------------------------------------------

    private fun profileList(): String = ProfileStore.profiles.value.joinToString("\n") { prof ->
        val s = SessionManager.session(prof.id)
        val tag = if (prof.id == AiSession.contextProfileId) " (dipilih pengguna)" else ""
        "${prof.id}: ${prof.name} — ${s.state.value.label}$tag"
    }

    private fun profileId(a: Map<String, Any?>): String {
        val given = a.s("profileId").trim()
        val id = given.ifBlank { AiSession.contextProfileId.orEmpty() }
            .ifBlank { ProfileStore.profiles.value.singleOrNull()?.id.orEmpty() }
        require(id.isNotBlank()) { "profileId wajib diisi. Profil tersedia:\n" + profileList() }
        val prof = ProfileStore.get(id) ?: ProfileStore.profiles.value.firstOrNull { it.name.equals(id, true) }
        require(prof != null) { "Profil '$id' tidak ditemukan. Profil tersedia:\n" + profileList() }
        return prof.id
    }

    private fun readConsole(a: Map<String, Any?>): String {
        val id = profileId(a)
        val s = SessionManager.session(id)
        s.log.flush()
        val filter = a.s("contains").trim()
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val lines = s.log.flow.value
            .filter { filter.isEmpty() || it.clean.contains(filter, true) }
            .takeLast(a.i("lines", 80).coerceIn(1, 300))
        if (lines.isEmpty()) return "Console profil $id kosong${if (filter.isNotEmpty()) " untuk filter '$filter'" else ""}. Status: ${s.state.value.label}."
        return lines.joinToString("\n") { l ->
            val mark = when (l.kind) { LineKind.IN -> "> "; LineKind.ERR -> "! "; LineKind.SYS -> "# "; else -> "" }
            "[${fmt.format(Date(l.ts))}] $mark${l.clean.take(300)}"
        }
    }

    // ---- File --------------------------------------------------------------------------------

    private fun isTextFile(file: File): Boolean = file.extension.lowercase() in TEXT_EXT

    private fun safeFile(ctx: Context, relative: String): File {
        val root = Paths.dataRoot(ctx).canonicalFile
        val f = File(root, relative.trim().trimStart('/', '\\')).canonicalFile
        require(f.path == root.path || f.path.startsWith(root.path + File.separator)) { "Path di luar folder data MCC ditolak." }
        return f
    }

    private fun rel(ctx: Context, f: File): String {
        val root = Paths.dataRoot(ctx).canonicalFile
        return f.canonicalFile.relativeTo(root).path.ifEmpty { "/" }
    }

    private fun isProtected(ctx: Context, f: File): Boolean {
        val c = f.canonicalFile
        val protectedDirs = listOf(
            Paths.dataRoot(ctx), Paths.profilesDir(ctx), Paths.sharedDir(ctx),
            Paths.modsDir(ctx), Paths.scriptsDir(ctx), File(Paths.dataRoot(ctx), TRASH),
        ).map { it.canonicalFile.path }
        return c.path in protectedDirs
    }

    private fun backup(f: File): File? {
        if (!f.isFile) return null
        val bak = File(f.parentFile, f.name + ".bak")
        f.copyTo(bak, overwrite = true)
        return bak
    }

    private fun trashSlot(ctx: Context, f: File): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dest = File(File(Paths.dataRoot(ctx), "$TRASH/$stamp"), rel(ctx, f))
        dest.parentFile?.mkdirs()
        return dest
    }

    private fun trashMove(ctx: Context, f: File): File {
        val dest = trashSlot(ctx, f)
        if (!f.renameTo(dest)) {
            f.copyRecursively(dest, overwrite = true)
            f.deleteRecursively()
        }
        return dest
    }

    private fun trashCopy(ctx: Context, f: File): File {
        val dest = trashSlot(ctx, f)
        f.copyRecursively(dest, overwrite = true)
        return dest
    }

    private fun human(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${n / 1024} KB"
        else -> "${n / (1024 * 1024)} MB"
    }

    private fun listTree(root: File, depth: Int, limit: Int): String {
        if (!root.exists()) return "Folder tidak ditemukan."
        val base = root.canonicalFile
        val rows = base.walkTopDown().maxDepth(depth).filter { it != base }
            .filter { !it.relativeTo(base).path.startsWith(TRASH) || base.name == TRASH }
            .sortedBy { it.relativeTo(base).path }.take(limit).toList()
        if (rows.isEmpty()) return "Kosong."
        return rows.joinToString("\n") { f ->
            val r = f.relativeTo(base).path
            if (f.isDirectory) "$r/" else "$r  (${human(f.length())})"
        }
    }

    private fun listFiles(ctx: Context, path: String, depth: Int): String {
        val dir = safeFile(ctx, path)
        require(dir.isDirectory) { "Bukan folder: $path" }
        return listTree(dir, depth, 300)
    }

    private fun readFile(ctx: Context, path: String, offset: Int, limit: Int): String {
        val f = safeFile(ctx, path)
        require(f.isFile) { "File tidak ditemukan: $path" }
        require(isTextFile(f)) { "Hanya file teks yang boleh dibaca AI (${TEXT_EXT.joinToString()})." }
        val text = f.readText(Charsets.UTF_8)
        val from = offset.coerceIn(0, text.length)
        val to = minOf(text.length, from + limit.coerceIn(1, MAX_READ))
        val head = "(${rel(ctx, f)} · total ${text.length} karakter · menampilkan $from–$to)\n"
        return head + text.substring(from, to) + if (to < text.length) "\n…[terpotong, lanjutkan dengan offset=$to]" else ""
    }

    private fun searchFiles(ctx: Context, query: String, path: String, regex: Boolean): String {
        require(query.isNotEmpty()) { "query kosong." }
        val root = safeFile(ctx, path)
        require(root.exists()) { "Path tidak ditemukan: $path" }
        val re = if (regex) Regex(query, RegexOption.IGNORE_CASE) else null
        val base = Paths.dataRoot(ctx).canonicalFile
        val out = ArrayList<String>()
        for (f in root.walkTopDown()) {
            if (out.size >= 100) break
            if (!f.isFile || !isTextFile(f) || f.length() > 2L * 1024 * 1024) continue
            if (f.relativeTo(base).path.startsWith(TRASH)) continue
            val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrNull() ?: continue
            for ((n, line) in lines.withIndex()) {
                val hit = if (re != null) re.containsMatchIn(line) else line.contains(query, true)
                if (hit) {
                    out += "${f.relativeTo(base).path}:${n + 1}: ${line.trim().take(200)}"
                    if (out.size >= 100) break
                }
            }
        }
        return if (out.isEmpty()) "Tidak ada hasil untuk '$query'." else out.joinToString("\n")
    }

    private fun writeFile(ctx: Context, path: String, content: String): String {
        val f = safeFile(ctx, path)
        require(!f.isDirectory) { "$path adalah folder." }
        require(isTextFile(f)) { "AI hanya boleh menulis file teks (${TEXT_EXT.joinToString()}), bukan binary." }
        require(content.length <= MAX_WRITE) { "File terlalu besar (maks ${MAX_WRITE / 1024} KB)." }
        val bak = backup(f)
        Paths.writeAtomic(f, content)
        return "Tersimpan ${rel(ctx, f)} (${content.length} karakter)${bak?.let { "; cadangan ${it.name}" } ?: ""}."
    }

    private fun editFile(ctx: Context, path: String, find: String, replace: String, all: Boolean): String {
        require(find.isNotEmpty()) { "find kosong." }
        val f = safeFile(ctx, path)
        require(f.isFile) { "File tidak ditemukan: $path" }
        require(isTextFile(f)) { "Hanya file teks yang boleh diedit." }
        val text = f.readText(Charsets.UTF_8)
        var count = 0
        var idx = text.indexOf(find)
        while (idx >= 0) { count++; idx = text.indexOf(find, idx + find.length) }
        require(count > 0) { "Teks yang dicari tidak ditemukan di $path. Baca file dulu dan salin teks persis." }
        require(count == 1 || all) { "Teks ditemukan $count kali. Tambahkan konteks agar unik atau set replaceAll=true." }
        val updated = if (all) text.replace(find, replace) else text.replaceFirst(find, replace)
        require(updated.length <= MAX_WRITE) { "Hasil terlalu besar." }
        val bak = backup(f)
        Paths.writeAtomic(f, updated)
        return "Edit ${rel(ctx, f)}: $count penggantian${bak?.let { "; cadangan ${it.name}" } ?: ""}."
    }

    private fun moveFile(ctx: Context, from: String, to: String): String {
        val src = safeFile(ctx, from)
        val dst = safeFile(ctx, to)
        require(src.exists()) { "Asal tidak ditemukan: $from" }
        require(!isProtected(ctx, src)) { "Folder inti tidak boleh dipindah." }
        require(!dst.exists()) { "Tujuan sudah ada: $to" }
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = false)
            src.deleteRecursively()
        }
        return "Dipindah ${rel(ctx, src)} → ${rel(ctx, dst)}."
    }

    // ---- Config ------------------------------------------------------------------------------

    private fun loadDoc(ctx: Context, a: Map<String, Any?>): Pair<File, ConfigDoc> {
        val f = Paths.configFile(ctx, profileId(a))
        require(f.exists()) { "Config belum ada. Gunakan generate_default_config." }
        return f to ConfigDoc.parse(f.readText(Charsets.UTF_8))
    }

    private fun describeEntry(e: app.mccdroid.logic.CfgEntry): String {
        val hint = ConfigHints.hint(e)
        val opts = hint?.options?.let { " · pilihan: ${it.joinToString("|")}" } ?: ""
        val desc = e.description.replace("\n", " ").take(200)
        return "[${e.section.name}] ${e.key} = ${e.raw}  (${e.kind.name.lowercase()}$opts)" + if (desc.isNotBlank()) "\n    $desc" else ""
    }

    private fun searchConfig(ctx: Context, a: Map<String, Any?>): String {
        val (_, doc) = loadDoc(ctx, a)
        val hits = doc.search(a.s("query")).take(25)
        return if (hits.isEmpty()) "Tidak ada setting yang cocok dengan '${a.s("query")}'." else hits.joinToString("\n") { describeEntry(it) }
    }

    private fun getConfigValue(ctx: Context, a: Map<String, Any?>): String {
        val (_, doc) = loadDoc(ctx, a)
        val e = doc.find(a.s("section"), a.s("key"))
            ?: error("Setting [${a.s("section")}] ${a.s("key")} tidak ditemukan. Gunakan search_config.")
        return describeEntry(e)
    }

    private fun setConfigValue(ctx: Context, a: Map<String, Any?>): String {
        val (file, doc) = loadDoc(ctx, a)
        val e = doc.find(a.s("section"), a.s("key"))
            ?: error("Setting [${a.s("section")}] ${a.s("key")} tidak ditemukan. Gunakan search_config.")
        val old = e.raw
        val v = a.s("value")
        when (e.kind) {
            VKind.BOOL -> doc.setBool(e, v.trim().lowercase().toBooleanStrictOrNull() ?: error("Nilai harus true atau false."))
            VKind.INT -> doc.setInt(e, v.trim().toLongOrNull() ?: error("Nilai harus bilangan bulat."))
            VKind.FLOAT -> doc.setDouble(e, v.trim().toDoubleOrNull() ?: error("Nilai harus angka."))
            VKind.STRING -> doc.setString(e, v)
            VKind.ARRAY ->
                if (v.trim().startsWith("[")) doc.setRaw(e, v)
                else doc.setStringArray(e, v.split(',').map { it.trim() }.filter { it.isNotEmpty() })
            VKind.TABLE, VKind.OTHER -> doc.setRaw(e, v)
        }
        val bak = backup(file)
        Paths.writeAtomic(file, doc.render())
        return "[${e.section.name}] ${e.key}: $old → ${e.raw}${bak?.let { " (cadangan ${it.name})" } ?: ""}. Jalankan /reload atau restart sesi agar berlaku."
    }

    // ---- Otomasi & notifikasi ----------------------------------------------------------------

    private fun describeTrigger(a: Map<String, Any?>): String =
        if (a.s("trigger").equals("EVENT", true)) "event ${a.s("event")}" else "log ${a.s("match").ifBlank { "CONTAINS" }} '${a.s("pattern")}'"

    private fun describeActions(a: Map<String, Any?>): String {
        val acts = a.list("actions").map { it.asObj() }
        if (acts.isEmpty()) return a.s("command")
        return acts.joinToString(" → ") { "${it.s("type")}${if (it.s("arg").isNotEmpty()) "(${snippet(it.s("arg"), 60)})" else ""}" }
    }

    private fun parseActions(a: Map<String, Any?>): List<RuleAction>? {
        val raw = a.list("actions")
        val parsed: List<RuleAction> = when {
            raw.isNotEmpty() -> raw.map { x ->
                val m = x.asObj()
                RuleAction(enumArg<ActionType>(m, "type", null) ?: error("type aksi wajib diisi."), m.s("arg"))
            }
            a.s("command").isNotBlank() -> listOf(RuleAction(ActionType.SEND, a.s("command")))
            else -> return null
        }
        for (act in parsed) when (act.type) {
            ActionType.SEND -> require(act.arg.trim().startsWith("/")) { "Aksi SEND harus diawali '/' (mis. '/send halo'); tanpa '/' bisa memicu loop chat." }
            ActionType.DELAY -> require(act.arg.trim().toLongOrNull() != null) { "Aksi DELAY butuh angka milidetik." }
            ActionType.NOTIFY -> require(act.arg.isNotBlank()) { "Aksi NOTIFY butuh teks ('Judul|Isi')." }
            else -> {}
        }
        return parsed
    }

    private fun optionalProfile(a: Map<String, Any?>): String? {
        if (!a.has("profileId")) return null
        val v = a.s("profileId").trim()
        if (v.isEmpty()) return ""
        return (ProfileStore.get(v) ?: ProfileStore.profiles.value.firstOrNull { it.name.equals(v, true) }
            ?: error("Profil '$v' tidak ditemukan.")).id
    }

    private fun saveAutomation(a: Map<String, Any?>, old: Rule?): String {
        val base = old ?: Rule(id = UUID.randomUUID().toString(), name = "Otomasi AI")
        val actions = parseActions(a) ?: base.actions
        val rule = base.copy(
            name = if (a.s("name").isNotBlank()) a.s("name") else base.name,
            enabled = a.b("enabled") ?: base.enabled,
            profileId = optionalProfile(a) ?: base.profileId,
            trigger = enumArg(a, "trigger", base.trigger)!!,
            match = enumArg(a, "match", base.match)!!,
            pattern = if (a.has("pattern")) a.s("pattern") else base.pattern,
            ignoreCase = a.b("ignoreCase") ?: base.ignoreCase,
            event = enumArg(a, "event", base.event)!!,
            cooldownSec = a.i("cooldownSec", base.cooldownSec).coerceIn(1, 3600),
            actions = actions,
        )
        require(rule.actions.isNotEmpty()) { "Otomasi butuh minimal satu aksi (actions)." }
        if (rule.trigger == TriggerKind.LINE) {
            require(rule.pattern.isNotEmpty()) { "Trigger LINE butuh pattern." }
            if (rule.match == MatchType.REGEX) require(Automation.isValidRegex(rule.pattern)) { "Regex tidak valid: ${rule.pattern}" }
        }
        RuleStore.upsert(rule)
        return "Otomasi '${rule.name}' (${rule.id}) ${if (old == null) "dibuat" else "diperbarui"} dan ${if (rule.enabled) "aktif" else "nonaktif"}."
    }

    private fun hm(s: String): Int {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(s.trim()) ?: error("Format jam harus HH:mm, bukan '$s'.")
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        require(h in 0..23 && min in 0..59) { "Jam tidak valid: $s" }
        return h * 60 + min
    }

    private fun updateNotifications(a: Map<String, Any?>): String {
        var c = NotifyStore.config.value
        a.b("quietEnabled")?.let { c = c.copy(quietEnabled = it) }
        if (a.s("quietStart").isNotBlank()) c = c.copy(quietStartMin = hm(a.s("quietStart")))
        if (a.s("quietEnd").isNotBlank()) c = c.copy(quietEndMin = hm(a.s("quietEnd")))
        a.b("quietAllowCritical")?.let { c = c.copy(quietAllowCritical = it) }
        if (a.has("keywords")) {
            c = c.copy(keywords = a.list("keywords").mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } })
        }
        if (a.has("events")) {
            val prefs = LinkedHashMap(c.prefs)
            for (x in a.list("events")) {
                val m = x.asObj()
                val ev = enumArg<McEvent>(m, "event", null) ?: error("event wajib diisi.")
                val old = prefs[ev] ?: EventPref(Level.NORMAL)
                val lvl = enumArg<Level>(m, "level", old.level) ?: old.level
                prefs[ev] = EventPref(lvl, m.b("vibrate") ?: old.vibrate, m.i("cooldownSec", old.cooldownSec).coerceIn(0, 3600))
            }
            c = c.copy(prefs = prefs)
        }
        NotifyStore.update(c)
        return "Pengaturan notifikasi diperbarui."
    }

    // ---- Setting aplikasi --------------------------------------------------------------------

    private fun appSettingsText(): String = listOf(
        "themeMode" to AppPrefs.themeMode, "dynamicColor" to AppPrefs.dynamicColor, "accentIdx" to AppPrefs.accentIdx,
        "consoleFontSp" to AppPrefs.consoleFontSp, "maxLogLines" to AppPrefs.maxLogLines, "wrapLines" to AppPrefs.wrapLines,
        "showTimestamps" to AppPrefs.showTimestamps, "saveConsoleLog" to AppPrefs.saveConsoleLog,
        "keepScreenOn" to AppPrefs.keepScreenOn, "wakeLock" to AppPrefs.wakeLock, "wifiLock" to AppPrefs.wifiLock,
        "bootStart" to AppPrefs.bootStart, "heapLimitMb" to AppPrefs.heapLimitMb, "consoleViewMode" to AppPrefs.consoleViewMode,
        "soundTheme" to AppPrefs.soundTheme, "soundVolume" to AppPrefs.soundVolume, "soundEnabled" to AppPrefs.soundEnabled,
    ).joinToString("\n") { "${it.first} = ${it.second}" }

    private fun setAppSetting(key: String, value: String): String {
        fun bool() = value.trim().lowercase().toBooleanStrictOrNull() ?: error("Nilai boolean tidak valid.")
        when (key) {
            "themeMode" -> {
                require(value in setOf("system", "dark", "light", "amoled")) { "Tema tidak valid." }
                AppPrefs.themeMode = value
            }
            "dynamicColor" -> AppPrefs.dynamicColor = bool()
            "accentIdx" -> AppPrefs.accentIdx = value.trim().toIntOrNull()?.coerceIn(0, 5) ?: error("Aksen tidak valid.")
            "consoleFontSp" -> AppPrefs.consoleFontSp = value.trim().toFloatOrNull()?.coerceIn(9f, 20f) ?: error("Ukuran font tidak valid.")
            "maxLogLines" -> AppPrefs.maxLogLines = value.trim().toIntOrNull()?.coerceIn(500, 10000) ?: error("Batas log tidak valid.")
            "wrapLines" -> AppPrefs.wrapLines = bool()
            "showTimestamps" -> AppPrefs.showTimestamps = bool()
            "saveConsoleLog" -> AppPrefs.saveConsoleLog = bool()
            "keepScreenOn" -> AppPrefs.keepScreenOn = bool()
            "wakeLock" -> AppPrefs.wakeLock = bool()
            "wifiLock" -> AppPrefs.wifiLock = bool()
            "bootStart" -> AppPrefs.bootStart = bool()
            "heapLimitMb" -> AppPrefs.heapLimitMb = value.trim().toIntOrNull()?.coerceIn(0, 2048) ?: error("Batas heap tidak valid.")
            "consoleViewMode" -> {
                require(value in setOf("console", "modern")) { "Mode harus console atau modern." }
                AppPrefs.consoleViewMode = value
            }
            "soundTheme" -> {
                require(value in setOf("neo", "soft", "glass", "tech", "minimal", "pulse", "airy", "carbon", "pixel", "chime", "velvet", "spark", "orbit", "mono", "bloom")) { "Preset sound tidak valid." }
                AppPrefs.soundTheme = value
            }
            "soundVolume" -> AppPrefs.soundVolume = value.trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: error("Volume sound tidak valid.")
            "soundEnabled" -> AppPrefs.soundEnabled = bool()
            else -> error("Setting aplikasi tidak dikenal: $key")
        }
        return "Setting $key diubah menjadi $value."
    }

    // ---- Console lanjutan ---------------------------------------------------------------------

    private fun lineMatches(l: LogLine, pattern: String, re: Regex?): Boolean =
        if (re != null) re.containsMatchIn(l.clean) else l.clean.contains(pattern, true)

    private fun searchConsole(a: Map<String, Any?>): String {
        val pattern = a.s("pattern")
        require(pattern.isNotEmpty()) { "pattern kosong." }
        val re = if (a.b("regex") == true) Regex(pattern, RegexOption.IGNORE_CASE) else null
        val ids = if (a.s("profileId").trim() == "*") ProfileStore.profiles.value.map { it.id } else listOf(profileId(a))
        val ctxLines = a.i("context", 0).coerceIn(0, 5)
        val max = a.i("maxResults", 40).coerceIn(1, 100)
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val out = ArrayList<String>()
        var hits = 0
        var truncated = false
        for (id in ids) {
            val s = SessionManager.session(id)
            s.log.flush()
            val lines = s.log.flow.value
            val name = ProfileStore.get(id)?.name ?: id
            var next = 0
            for ((i, l) in lines.withIndex()) {
                if (!lineMatches(l, pattern, re)) continue
                if (hits >= max) { truncated = true; break }
                hits++
                val from = maxOf(i - ctxLines, next)
                val to = minOf(i + ctxLines, lines.lastIndex)
                if (ctxLines > 0 && from > next && out.isNotEmpty()) out += "  ⋯"
                for (j in from..to) {
                    val x = lines[j]
                    out += (if (j == i) "" else "  ") + "[${fmt.format(Date(x.ts))}] $name: ${x.clean.take(300)}"
                }
                next = to + 1
            }
            if (truncated) break
        }
        if (hits == 0) return "Tidak ada baris console yang cocok dengan '$pattern'."
        return out.joinToString("\n") + if (truncated) "\n…[dibatasi $max hasil; persempit pencarian]" else ""
    }

    private suspend fun waitForConsole(a: Map<String, Any?>): String {
        val id = profileId(a)
        val s = SessionManager.session(id)
        val pattern = a.s("pattern")
        val re = if (pattern.isNotEmpty() && a.b("regex") == true) Regex(pattern, RegexOption.IGNORE_CASE) else null
        val timeout = a.i("timeoutSec", 15).coerceIn(1, 60)
        s.log.flush()
        val startId = s.log.flow.value.lastOrNull()?.id ?: 0L
        val deadline = System.currentTimeMillis() + timeout * 1000L

        fun fresh(): List<LogLine> = s.log.flow.value.filter { it.id > startId }
        fun matched(list: List<LogLine>): Boolean =
            list.any { it.kind != LineKind.IN && (pattern.isEmpty() || lineMatches(it, pattern, re)) }

        var hit = false
        while (true) {
            delay(300)
            s.log.flush()
            hit = matched(fresh())
            if (hit || System.currentTimeMillis() >= deadline) break
        }
        if (hit) {
            delay(600) // beri waktu baris susulan (mis. balasan /list yang beberapa baris)
            s.log.flush()
        }
        val lines = fresh()
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val head = "(profil $id · ${s.state.value.label} · ${if (hit) "cocok" else "timeout $timeout dtk tanpa kecocokan"}; ${lines.size} baris baru)"
        if (lines.isEmpty()) return head
        val shown = lines.takeLast(120).joinToString("\n") { l ->
            val mark = when (l.kind) { LineKind.IN -> "> "; LineKind.ERR -> "! "; LineKind.SYS -> "# "; else -> "" }
            "[${fmt.format(Date(l.ts))}] $mark${l.clean.take(300)}"
        }
        return head + "\n" + shown
    }

    private fun sessionStatus(ctx: Context, a: Map<String, Any?>): String {
        val given = a.s("profileId").trim()
        val ids = if (given.isEmpty() || given == "*") ProfileStore.profiles.value.map { it.id } else listOf(profileId(a))
        if (ids.isEmpty()) return "Belum ada profil."
        val now = System.currentTimeMillis()
        return ids.joinToString("\n\n") { id ->
            val p = ProfileStore.get(id)
            val s = SessionManager.session(id)
            val cfg = Paths.configFile(ctx, id)
            buildString {
                append("$id \"${p?.name ?: "?"}\": ${s.state.value.label} (proses ${if (s.isAlive) "hidup" else "mati"})")
                if (s.isAlive && s.startedAt.value > 0) append(", uptime ${(now - s.startedAt.value) / 60000} menit")
                s.accountName?.let { append("\n  akun: $it") }
                if (s.lastEvent.value.isNotBlank()) append("\n  event terakhir: ${s.lastEvent.value}")
                s.deviceCode.value?.let { append("\n  LOGIN PERANGKAT DIPERLUKAN: buka ${it.url} dan masukkan kode ${it.code}") }
                s.log.flush()
                append("\n  baris log di memori: ${s.log.flow.value.size}")
                append("\n  autoStart=${p?.autoStart}, autoRestart=${p?.autoRestart}, args=${p?.extraArgs?.ifBlank { "-" }}")
                append("\n  config: " + if (cfg.exists()) "ada, diubah ${SimpleDateFormat("d MMM HH:mm", Locale("id", "ID")).format(Date(cfg.lastModified()))}" else "belum ada")
            }
        }
    }

    private suspend fun resourceUsage(ctx: Context): String {
        val sampler = ResourceUsageSampler()
        sampler.sampleOrNull(ctx)
        delay(700)
        val u = sampler.sampleOrNull(ctx) ?: error("Statistik resource tidak tersedia di perangkat ini.")
        return "CPU sistem %.0f%%, CPU aplikasi %.0f%%; RAM sistem %.0f%% dari %d MB; RAM aplikasi %d MB (%.1f%%); %d core."
            .format(u.systemCpuPercent, u.appCpuPercent, u.systemRamPercent, u.totalRamMb, u.appRamMb, u.appRamPercent, u.cores)
    }

    // ---- File tambahan -----------------------------------------------------------------------

    private fun appendFile(ctx: Context, path: String, content: String): String {
        require(content.isNotEmpty()) { "content kosong." }
        val f = safeFile(ctx, path)
        require(!f.isDirectory) { "$path adalah folder." }
        require(isTextFile(f)) { "AI hanya boleh menulis file teks (${TEXT_EXT.joinToString()})." }
        val old = if (f.isFile) f.readText(Charsets.UTF_8) else ""
        val sep = if (old.isNotEmpty() && !old.endsWith("\n")) "\n" else ""
        val updated = old + sep + content
        require(updated.length <= MAX_WRITE) { "Hasil terlalu besar (maks ${MAX_WRITE / 1024} KB)." }
        val bak = backup(f)
        Paths.writeAtomic(f, updated)
        return "Ditambahkan ${content.length} karakter ke ${rel(ctx, f)}${bak?.let { "; cadangan ${it.name}" } ?: ""}."
    }

    private fun copyFile(ctx: Context, from: String, to: String): String {
        val src = safeFile(ctx, from)
        val dst = safeFile(ctx, to)
        require(src.exists()) { "Asal tidak ditemukan: $from" }
        require(!dst.exists()) { "Tujuan sudah ada: $to" }
        require(!dst.path.startsWith(src.path + File.separator)) { "Tujuan tidak boleh di dalam asal." }
        dst.parentFile?.mkdirs()
        require(src.copyRecursively(dst, overwrite = false)) { "Penyalinan gagal sebagian." }
        return "Disalin ${rel(ctx, src)} → ${rel(ctx, dst)}."
    }

    private fun restoreBackup(ctx: Context, path: String): String {
        val f = safeFile(ctx, path)
        require(isTextFile(f) || f.name.endsWith(".ini")) { "Hanya file teks yang bisa dipulihkan." }
        val bak = File(f.parentFile, f.name + ".bak")
        require(bak.isFile) { "Tidak ada cadangan ${rel(ctx, bak)}." }
        val current = if (f.isFile) f.readText(Charsets.UTF_8) else null
        Paths.writeAtomic(f, bak.readText(Charsets.UTF_8))
        if (current != null) Paths.writeAtomic(bak, current)
        return "${rel(ctx, f)} dipulihkan dari cadangan; versi sebelumnya kini ada di ${bak.name} (jalankan lagi untuk membatalkan)."
    }

    // ---- Shell -------------------------------------------------------------------------------

    private fun runShell(ctx: Context, command: String, workdir: String): String {
        require(command.isNotBlank()) { "Perintah kosong." }
        val cwd = if (workdir.isBlank()) Paths.dataRoot(ctx) else safeFile(ctx, workdir)
        require(cwd.isDirectory) { "Folder kerja tidak ada: $workdir" }
        val pb = ProcessBuilder("/system/bin/sh", "-c", command).directory(cwd).redirectErrorStream(true)
        pb.environment().putAll(RuntimeEnv.environment(ctx))
        val p = pb.start()
        p.outputStream.close()
        val out = StringBuilder()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(2048)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) { if (out.length < 32_000) out.append(buf, 0, n) }
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = p.waitFor(30, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(1500)
        val text = synchronized(out) { out.toString() }.trimEnd()
        val status = if (finished) "(kode keluar ${p.exitValue()})" else "(dihentikan: melebihi 30 detik)"
        return (if (text.isEmpty()) "(tanpa keluaran)" else text.take(MAX_READ)) + "\n" + status
    }
}
