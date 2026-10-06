package app.mccdroid.logic

/** Petunjuk tampilan untuk satu setting MCC (label Indonesia, rentang slider, pilihan dropdown). */
data class Hint(
    val label: String? = null,
    val desc: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val unit: String? = null,
    val options: List<String>? = null,
    val secret: Boolean = false,
)

object ConfigHints {
    private val QUOTED = Regex("\"([A-Za-z0-9_\\-]+)\"")

    private val ENABLE_MODES = listOf("no", "auto", "force")

    /** Kunci diberi bentuk "Bagian.Kunci"; "*.Kunci" berlaku di bagian mana pun. */
    private val HINTS: Map<String, Hint> = mapOf(
        "Main.General.AccountType" to Hint("Jenis akun", "microsoft = akun premium Microsoft; mojang = lama; yggdrasil = authlib server", options = listOf("microsoft", "mojang", "yggdrasil")),
        "Main.General.Method" to Hint("Metode login Microsoft", "mcc = kode perangkat (device code); browser = tempel kode dari browser", options = listOf("mcc", "browser")),
        "Main.General.AuthServerUrl" to Hint("URL authlib-injector"),
        "Main.Advanced.Language" to Hint("Bahasa MCC", "Mis. en_us. Memengaruhi terjemahan pesan server."),
        "Main.Advanced.InternalCmdChar" to Hint("Awalan perintah internal", "Karakter pembuka perintah MCC", options = listOf("none", "slash", "backslash")),
        "Main.Advanced.MessageCooldown" to Hint("Jeda antar pesan", "Detik minimum antar pesan chat agar tidak terkena spam-kick", 0.0, 5.0, 0.1, "dtk"),
        "Main.Advanced.MaxChatMessageLength" to Hint("Panjang maks. pesan", "0 = otomatis sesuai versi server", 0.0, 256.0, 1.0),
        "Main.Advanced.MinecraftVersion" to Hint("Versi Minecraft", "auto = deteksi otomatis, atau mis. 1.21.4"),
        "Main.Advanced.EnableForge" to Hint("Dukungan Forge", options = ENABLE_MODES),
        "Main.Advanced.BrandInfo" to Hint("Brand klien", "Nama klien yang dikirim ke server", options = listOf("mcc", "vanilla", "empty")),
        "Main.Advanced.TerrainAndMovements" to Hint("Terrain & gerakan", "Wajib untuk bot yang berjalan/menggali"),
        "Main.Advanced.InventoryHandling" to Hint("Penanganan inventaris", "Wajib untuk AutoEat, AutoDrop, AutoCraft"),
        "Main.Advanced.EntityHandling" to Hint("Penanganan entitas", "Wajib untuk AutoAttack"),
        "Main.Advanced.MovementSpeed" to Hint("Kecepatan gerak", "Langkah per tick", 1.0, 10.0, 1.0),
        "Main.Advanced.SessionCache" to Hint("Cache sesi", options = listOf("none", "memory", "disk")),
        "Main.Advanced.ProfileKeyCache" to Hint("Cache kunci profil", options = listOf("none", "memory", "disk")),
        "Main.Advanced.ResolveSrvRecords" to Hint("Resolusi SRV", options = listOf("no", "fast", "yes")),
        "Main.Advanced.TcpTimeout" to Hint("Timeout TCP", "Detik menunggu server", 5.0, 120.0, 1.0, "dtk"),
        "Main.Advanced.AutoRespawn" to Hint("Respawn otomatis", "Hidup kembali otomatis setelah mati"),
        "Main.Advanced.ExitOnFailure" to Hint("Keluar jika gagal", "Hentikan MCC saat koneksi gagal (tanpa tanya)"),
        "Main.Advanced.Timestamps" to Hint("Stempel waktu di log"),
        "Main.Advanced.ShowSystemMessages" to Hint("Tampilkan pesan sistem"),
        "Main.Advanced.EnableSentry" to Hint("Laporan crash (Sentry)"),
        "Main.Advanced.BotOwners" to Hint("Pemilik bot", "Pemain yang boleh memberi perintah lewat pesan pribadi"),
        "Logging.FilterMode" to Hint("Mode filter log", options = listOf("disable", "blacklist", "whitelist")),
        "Logging.LogToFile" to Hint("Simpan log ke berkas"),
        "Logging.DebugMessages" to Hint("Pesan debug"),
        "MCSettings.RenderDistance" to Hint("Jarak render", "Chunk", 2.0, 32.0, 1.0),
        "MCSettings.Difficulty" to Hint("Kesulitan", options = listOf("peaceful", "easy", "normal", "difficult")),
        "MCSettings.ChatMode" to Hint("Mode chat", options = listOf("enabled", "commands", "disabled")),
        "MCSettings.MainHand" to Hint("Tangan utama", options = listOf("left", "right")),
        "Console.General.ConsoleMode" to Hint("Mode konsol", "Diabaikan oleh aplikasi ini: selalu memakai mode baris (BasicIO)", options = listOf("classic", "tui")),
        "Console.General.ConsoleColorMode" to Hint("Mode warna", options = listOf("disable", "legacy_4bit", "vt100_4bit", "vt100_8bit", "vt100_24bit")),
        "Console.General.History_Input_Records" to Hint("Riwayat input", null, 0.0, 200.0, 1.0),
        "Console.Minimap.Position" to Hint("Posisi minimap", options = listOf("top_left", "top_right", "center", "bottom_left", "bottom_right")),
        "Console.Minimap.CaveMode" to Hint("Mode gua", options = listOf("auto", "on", "off")),
        "*.Enabled" to Hint("Aktif"),
        "*.Password" to Hint("Kata sandi", secret = true),
        "*.Delay" to Hint(null, null, 0.0, 600.0, 1.0, "dtk"),
    )

    fun hint(e: CfgEntry): Hint? = hint(e.section.name, e.key)

    fun hint(section: String, key: String): Hint? = HINTS["$section.$key"] ?: HINTS["*.$key"]

    fun prettyKey(key: String): String =
        key.replace('_', ' ').replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").trim()

    fun label(e: CfgEntry): String = hint(e)?.label ?: prettyKey(e.key)

    /** Pilihan dropdown: dari tabel kurasi, atau dari komentar bila berformat `"a" or "b"`. */
    fun options(e: CfgEntry): List<String>? {
        hint(e)?.options?.let { return withCurrent(it, e) }
        if (e.kind != VKind.STRING) return null
        val current = Toml.unquote(e.raw)
        val found = QUOTED.findAll(e.description).map { it.groupValues[1] }.distinct().toList()
        if (found.size < 2 || found.size > 12) return null
        if (found.none { it.equals(current, ignoreCase = true) }) return null
        return found
    }

    private fun withCurrent(opts: List<String>, e: CfgEntry): List<String> {
        if (e.kind != VKind.STRING) return opts
        val cur = Toml.unquote(e.raw)
        return if (opts.any { it == cur } || cur.isEmpty()) opts else opts + cur
    }

    /** Rentang slider untuk angka. Dipakai hanya bila nilai saat ini berada dalam rentang. */
    fun sliderRange(e: CfgEntry): ClosedFloatingPointRange<Double>? {
        val kind = e.kind
        if (kind != VKind.INT && kind != VKind.FLOAT) return null
        val v = numberOf(e) ?: return null
        val h = hint(e)
        if (h?.min != null && h.max != null) {
            val lo = minOf(h.min, v)
            val hi = maxOf(h.max, v)
            return lo..hi
        }
        if (v < 0) return minOf(v * 2, -10.0)..maxOf(v * -2, 10.0)
        val hi = niceMax(v)
        return 0.0..hi
    }

    fun step(e: CfgEntry): Double {
        hint(e)?.step?.let { return it }
        return if (e.kind == VKind.INT) 1.0 else 0.1
    }

    fun numberOf(e: CfgEntry): Double? {
        val t = e.raw.trim().replace("_", "")
        return t.toDoubleOrNull() ?: t.toLongOrNull()?.toDouble()
    }

    private fun niceMax(v: Double): Double {
        val target = maxOf(10.0, v * 3)
        var p = 10.0
        while (p < target) p *= if (p * 2.5 >= target) 2.5 else 10.0
        return p
    }

    fun categoryTitle(top: String): String = when (top) {
        "Head" -> "Dasar"
        "Main" -> "Akun, server & lanjutan"
        "Console" -> "Konsol"
        "Logging" -> "Pencatatan log"
        "AppVars" -> "Variabel"
        "Proxy" -> "Proxy"
        "MCSettings" -> "Pengaturan klien Minecraft"
        "ChatFormat" -> "Format chat"
        "Signature" -> "Tanda tangan chat"
        "ChatBot" -> "Bot obrolan"
        else -> top
    }
}
