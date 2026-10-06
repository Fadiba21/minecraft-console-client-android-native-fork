package app.mccdroid.logic

/** Perintah internal MCC (diambil dari kelas di folder Commands dan dokumentasi). */
data class CmdInfo(val name: String, val usage: String, val desc: String, val group: String)

object CommandCatalog {
    val all: List<CmdInfo> = listOf(
        CmdInfo("health", "health", "Tampilkan darah & lapar", "Status"),
        CmdInfo("list", "list", "Daftar pemain online", "Status"),
        CmdInfo("tps", "tps", "Tampilkan TPS server", "Status"),
        CmdInfo("effects", "effects", "Efek yang aktif", "Status"),
        CmdInfo("inventory", "inventory", "Kelola inventaris (butuh Inventory Handling)", "Status"),
        CmdInfo("tab", "tab", "Daftar tab pemain", "Status"),
        CmdInfo("teams", "teams", "Daftar tim", "Status"),
        CmdInfo("send", "send <teks>", "Kirim pesan/perintah ke server", "Chat"),
        CmdInfo("log", "log <teks>", "Tulis teks ke log konsol", "Chat"),
        CmdInfo("respawn", "respawn", "Hidup kembali setelah mati", "Gerak"),
        CmdInfo("move", "move <on|off|get|up|down|east|west|north|south|center|x y z>", "Berjalan (butuh Terrain)", "Gerak"),
        CmdInfo("look", "look <x y z|yaw pitch|up|down|east|west|north|south>", "Arahkan pandangan", "Gerak"),
        CmdInfo("sneak", "sneak", "Ganti mode jongkok", "Gerak"),
        CmdInfo("bed", "bed leave|sleep <x> <y> <z>|sleep <radius>", "Tidur di kasur", "Gerak"),
        CmdInfo("dig", "dig <x> <y> <z>", "Gali blok", "Aksi"),
        CmdInfo("useitem", "useitem [mainhand|offhand]", "Gunakan item di tangan", "Aksi"),
        CmdInfo("useblock", "useblock <x> <y> <z> [mainhand|offhand]", "Gunakan blok", "Aksi"),
        CmdInfo("dropitem", "dropitem <itemtype>", "Buang item", "Aksi"),
        CmdInfo("changeslot", "changeslot <1-9>", "Ganti slot hotbar", "Aksi"),
        CmdInfo("entity", "entity [near] <id|entitytype> <attack|use>", "Interaksi entitas", "Aksi"),
        CmdInfo("animation", "animation <mainhand|offhand>", "Ayunkan tangan", "Aksi"),
        CmdInfo("blockinfo", "blockinfo <x> <y> <z>", "Info blok", "Info"),
        CmdInfo("chunk", "chunk status", "Status chunk", "Info"),
        CmdInfo("bots", "bots [list|unload <nama|all>]", "Kelola bot yang aktif", "MCC"),
        CmdInfo("script", "script <nama>", "Jalankan skrip", "MCC"),
        CmdInfo("set", "set nama=nilai", "Setel variabel", "MCC"),
        CmdInfo("reload", "reload", "Muat ulang config & bot", "MCC"),
        CmdInfo("reco", "reco [akun]", "Sambung ulang", "MCC"),
        CmdInfo("connect", "connect <server> [akun]", "Sambung ke server lain", "MCC"),
        CmdInfo("debug", "debug [on|off]", "Mode debug", "MCC"),
        CmdInfo("help", "help [perintah]", "Bantuan MCC", "MCC"),
        CmdInfo("exit", "exit", "Keluar dari MCC", "MCC"),
        CmdInfo("quit", "quit", "Keluar dari MCC", "MCC"),
    )

    /** Saran untuk teks yang diketik, mis. "/he" -> health, help. */
    fun suggest(input: String, limit: Int = 8): List<CmdInfo> {
        val t = input.trimStart()
        if (!t.startsWith("/") || t.contains(' ')) return emptyList()
        val q = t.removePrefix("/").lowercase()
        return all.filter { it.name.startsWith(q) }.take(limit)
    }

    val defaultFavorites: List<String> = listOf("/health", "/list", "/tps", "/respawn", "/reco", "/reload")
}
