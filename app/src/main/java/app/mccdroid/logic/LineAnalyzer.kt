package app.mccdroid.logic

/** Kejadian yang dikenali dari keluaran MCC. */
enum class McEvent(val label: String) {
    JOINED("Berhasil masuk server"),
    KICKED("Dikeluarkan (kick) dari server"),
    LOST("Koneksi terputus"),
    LOGIN_FAILED("Login gagal"),
    RECONNECTING("Menyambung ulang"),
    RESTARTING("MCC memulai ulang"),
    DEAD("Karakter mati"),
    DEVICE_CODE("Perlu login Microsoft"),
    WHISPER("Pesan pribadi masuk"),
    MENTION("Nama Anda disebut"),
    PLAYER_JOIN("Pemain masuk"),
    PLAYER_LEAVE("Pemain keluar"),
    KEYWORD("Kata kunci terdeteksi"),
    PROCESS_STARTED("Proses MCC dimulai"),
    PROCESS_STOPPED("Proses MCC berhenti"),
    PROCESS_CRASHED("Proses MCC crash"),
}

data class Detected(
    val event: McEvent,
    val detail: String = "",
    val extra: Map<String, String> = emptyMap(),
)

/**
 * Mengenali kejadian penting dari satu baris keluaran MCC (sudah di-strip dari kode warna).
 * Teks acuan diambil dari berkas terjemahan MCC (Translations.resx), mis.
 *   "Server was successfully joined.", "Disconnected by Server :", "Connection has been lost."
 */
object LineAnalyzer {
    private val KICK = Regex("Disconnected by Server\\s*:?\\s*(.*)", RegexOption.IGNORE_CASE)
    private val LOGIN_FAIL = Regex("(?:Login failed\\s*:?\\s*(.*)|Failed to login to this server.*)", RegexOption.IGNORE_CASE)
    private val RECONNECT_A = Regex("Waiting\\s+([0-9]+(?:[.,][0-9]+)?)\\s+seconds\\s+before\\s+reconnecting(?:\\.\\.\\.)?\\s*(?:\\((\\d+)\\s+retries left\\))?", RegexOption.IGNORE_CASE)
    private val RECONNECT_B = Regex("Waiting\\s+(\\d+)\\s+seconds\\s*\\((\\d+)\\s+attempts left\\)", RegexOption.IGNORE_CASE)
    private val DEVICE = Regex("open\\s+(https?://\\S+)\\s+in your browser and enter the code:\\s*([A-Za-z0-9-]+)", RegexOption.IGNORE_CASE)
    private val WHISPER_A = Regex("^\\s*([A-Za-z0-9_]{1,32})\\s+whispers? to you\\s*:?\\s*(.*)$", RegexOption.IGNORE_CASE)
    // Format MCC BasicIO yang umum: ▌[Player -> You] , ▌pesan
    // Prefix/suffix dekoratif sengaja opsional agar tetap cocok untuk server/plugin berbeda.
    private val WHISPER_B = Regex("^\\s*[▌|│]?\\s*\\[\\s*([A-Za-z0-9_]{1,32})\\s*(?:->|→|»)\\s*(?:me|you)\\s*]\\s*,?\\s*[▌|│]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val PLAYER_ACTION = Regex("^\\s*([A-Za-z0-9_]{3,16}) (joined|left) the game\\s*$", RegexOption.IGNORE_CASE)

    fun analyze(clean: String, myName: String?, keywords: List<String> = emptyList()): List<Detected> {
        val line = clean.trim()
        if (line.isEmpty()) return emptyList()
        val out = ArrayList<Detected>(2)
        val isMccLine = line.startsWith("[MCC]")

        DEVICE.find(line)?.let {
            out += Detected(McEvent.DEVICE_CODE, it.groupValues[2], mapOf("url" to it.groupValues[1].trimEnd('.', ',', ')'), "code" to it.groupValues[2]))
        }

        if (line.contains("Server was successfully joined", ignoreCase = true)) {
            out += Detected(McEvent.JOINED)
        }
        KICK.find(line)?.let {
            out += Detected(McEvent.KICKED, it.groupValues[1].trim())
        }
        if (line.contains("Connection has been lost", ignoreCase = true)) {
            out += Detected(McEvent.LOST)
        }
        LOGIN_FAIL.find(line)?.let {
            out += Detected(McEvent.LOGIN_FAILED, it.groupValues.getOrElse(1) { "" }.trim())
        }
        RECONNECT_A.find(line)?.let {
            val left = it.groupValues[2]
            out += Detected(McEvent.RECONNECTING, if (left.isNotEmpty()) "${it.groupValues[1]} dtk, sisa $left percobaan" else "${it.groupValues[1]} dtk")
        } ?: RECONNECT_B.find(line)?.let {
            out += Detected(McEvent.RECONNECTING, "${it.groupValues[1]} dtk, sisa ${it.groupValues[2]} percobaan")
        }
        if (line.contains("Restarting Minecraft Console Client", ignoreCase = true)) {
            out += Detected(McEvent.RESTARTING)
        }
        if (line.contains("You are dead", ignoreCase = true)) {
            out += Detected(McEvent.DEAD)
        }

        // Pesan obrolan / pemain: abaikan baris sistem MCC.
        if (!isMccLine && out.isEmpty()) {
            val w = WHISPER_A.find(line) ?: WHISPER_B.find(line)
            if (w != null) {
                val message = w.groupValues[2].trim().removePrefix("▌").trim()
                // WHISPER_B hanya menerima target me/you, sehingga pesan [You -> Player]
                // tidak pernah dianggap sebagai pesan masuk.
                if (message.isNotEmpty()) {
                    out += Detected(McEvent.WHISPER, message, mapOf("from" to w.groupValues[1], "message" to message))
                }
            }
            PLAYER_ACTION.find(line)?.let {
                val joined = it.groupValues[2].equals("joined", ignoreCase = true)
                out += Detected(
                    if (joined) McEvent.PLAYER_JOIN else McEvent.PLAYER_LEAVE,
                    it.groupValues[1], mapOf("player" to it.groupValues[1]),
                )
            }
            if (w == null && !myName.isNullOrBlank() && isMention(line, myName)) {
                out += Detected(McEvent.MENTION, line, mapOf("message" to line))
            }
        }

        if (keywords.isNotEmpty()) {
            for (k in keywords) {
                val kw = k.trim()
                if (kw.isNotEmpty() && line.contains(kw, ignoreCase = true)) {
                    out += Detected(McEvent.KEYWORD, kw, mapOf("keyword" to kw, "message" to line))
                    break
                }
            }
        }
        return out
    }

    /** Nama muncul sebagai kata utuh dan baris bukan pesan buatan sendiri ("<nama> ..."). */
    private fun isMention(line: String, name: String): Boolean {
        val n = name.trim()
        if (n.length < 3) return false
        val lower = line.lowercase()
        val ln = n.lowercase()
        if (lower.startsWith("<$ln>") || lower.startsWith("$ln:") || lower.startsWith("[$ln]")) return false
        var from = 0
        while (true) {
            val idx = lower.indexOf(ln, from)
            if (idx < 0) return false
            val before = if (idx == 0) ' ' else lower[idx - 1]
            val after = if (idx + ln.length >= lower.length) ' ' else lower[idx + ln.length]
            if (!isNameChar(before) && !isNameChar(after)) return true
            from = idx + 1
        }
    }

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_'
}
