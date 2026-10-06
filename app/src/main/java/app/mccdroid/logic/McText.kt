package app.mccdroid.logic

/** Potongan teks berwarna hasil parsing kode warna Minecraft (§) / ANSI. */
data class Span(
    val text: String,
    val rgb: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
)

/**
 * MCC (mode BasicIO) menulis kode warna Minecraft mentah, mis. "§c" untuk merah, ke stdout.
 * Objek ini mengubahnya menjadi span berwarna, atau membuangnya (strip) untuk analisis.
 */
object McText {
    private const val SECTION = '§'
    private const val ESC = '\u001B'

    private val MC_COLORS = intArrayOf(
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    )
    private val ANSI_STD = intArrayOf(0x000000, 0xAA0000, 0x00AA00, 0xAA5500, 0x0000AA, 0xAA00AA, 0x00AAAA, 0xAAAAAA)
    private val ANSI_BRIGHT = intArrayOf(0x555555, 0xFF5555, 0x55FF55, 0xFFFF55, 0x5555FF, 0xFF55FF, 0x55FFFF, 0xFFFFFF)

    private val URL_REGEX = Regex("https?://[^\\s<>\"')\\]]+")

    /**
     * Format hex yang dikenali (semuanya eksplisit, tanpa menebak):
     *  - §#RRGGBB  (Paper/Adventure/plugin gradasi, mis. "§#69e7c0O§#7aebc6r...")
     *  - &#RRGGBB
     *  - §x§R§R§G§G§B§B (format Spigot/BungeeCord)
     * @return pasangan (warna RGB, panjang karakter yang dipakai) atau null.
     */
    private fun hexColorAt(s: String, i: Int): Pair<Int, Int>? {
        val c = s[i]
        if ((c == SECTION || c == '&') && i + 7 < s.length && s[i + 1] == '#') {
            val h = s.substring(i + 2, i + 8)
            if (h.isHexColor()) return h.toInt(16) to 8
        }
        if (c == SECTION && i + 13 < s.length && s[i + 1].lowercaseChar() == 'x') {
            val sb = StringBuilder(6)
            var p = i + 2
            while (sb.length < 6 && p + 1 < s.length && s[p] == SECTION) {
                sb.append(s[p + 1]); p += 2
            }
            if (sb.length == 6 && sb.toString().isHexColor()) return sb.toString().toInt(16) to 14
        }
        return null
    }

    /** Glyph private-use (ikon resource pack server) tidak ada di font Android -> tampil kotak. */
    private fun isPrivateUse(c: Char) = c in '\uE000'..'\uF8FF'

    /** Normalisasi escape Unicode, mojibake UTF-8, dan kontrol terminal sebelum dirender. */
    fun normalize(s: String): String {
        val unescaped = decodeUnicodeEscapes(s)
        val repaired = repairMojibake(unescaped)
        return repaired.filter {
            it == '\t' || it == '\n' || it == '\r' || it == ESC || (!it.isISOControl() && !isPrivateUse(it))
        }
    }

    private fun decodeUnicodeEscapes(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 5 < s.length && s[i + 1] == 'u') {
                val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 6
                    continue
                }
            }
            sb.append(s[i++])
        }
        return sb.toString()
    }

    private fun repairMojibake(s: String): String {
        if (!s.any { it == 'Ã' || it == 'Â' || it == 'â' || it == 'ð' }) return s
        return try {
            val candidate = String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            if (candidate != s && candidate.count { it == '\uFFFD' } <= s.count { it == '\uFFFD' }) candidate else s
        } catch (_: Exception) {
            s
        }
    }

    /** Hapus semua kode § / &# dan urutan ANSI. */
    fun strip(s: String): String {
        if (s.indexOf(SECTION) < 0 && s.indexOf(ESC) < 0 && !s.contains("&#")) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == SECTION || c == '&') {
                val hex = hexColorAt(s, i)
                if (hex != null) {
                    i += hex.second
                } else if (c == SECTION) {
                    i += if (i + 1 < s.length) 2 else 1
                } else {
                    sb.append(c); i++
                }
            } else if (c == ESC) {
                i = skipAnsi(s, i)
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Posisi setelah urutan ANSI yang dimulai di [start] (yang berisi ESC). */
    private fun skipAnsi(s: String, start: Int): Int {
        var i = start + 1
        if (i < s.length && s[i] == '[') {
            i++
            while (i < s.length && !(s[i] in '@'..'~')) i++
            return if (i < s.length) i + 1 else s.length
        }
        return minOf(i, s.length)
    }

    fun findUrls(clean: String): List<IntRange> =
        URL_REGEX.findAll(clean).map { it.range.first..(it.range.last) }.toList()

    fun parse(s: String): List<Span> {
        val spans = ArrayList<Span>()
        val buf = StringBuilder()
        var color: Int? = null
        var bold = false
        var italic = false
        var underline = false
        var strike = false

        fun flush() {
            if (buf.isNotEmpty()) {
                spans.add(Span(buf.toString(), color, bold, italic, underline, strike))
                buf.setLength(0)
            }
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            if ((c == SECTION || c == '&') && hexColorAt(s, i) != null) {
                val (rgb, len) = hexColorAt(s, i)!!
                flush()
                color = rgb
                bold = false; italic = false; underline = false; strike = false
                i += len
                continue
            }
            if (c == SECTION && i + 1 < s.length) {
                val code = s[i + 1].lowercaseChar()
                i += 2
                val idx = "0123456789abcdef".indexOf(code)
                if (idx >= 0) {
                    flush()
                    color = MC_COLORS[idx]
                    bold = false; italic = false; underline = false; strike = false
                } else when (code) {
                    'l' -> { flush(); bold = true }
                    'o' -> { flush(); italic = true }
                    'n' -> { flush(); underline = true }
                    'm' -> { flush(); strike = true }
                    'r' -> {
                        flush()
                        color = null; bold = false; italic = false; underline = false; strike = false
                    }
                    'k' -> { flush(); bold = true }
                    else -> { buf.append(SECTION).append(s[i - 1]); }
                }
            } else if (c == ESC) {
                val end = skipAnsi(s, i)
                if (i + 1 < s.length && s[i + 1] == '[' && end > i && s[end - 1] == 'm') {
                    flush()
                    val params = s.substring(i + 2, end - 1)
                    val st = applySgr(params, color, bold, italic, underline, strike)
                    color = st.rgb; bold = st.bold; italic = st.italic; underline = st.underline; strike = st.strike
                }
                i = end
            } else {
                buf.append(c)
                i++
            }
        }
        flush()
        return spans
    }

    private fun applySgr(
        params: String, color0: Int?, bold0: Boolean, italic0: Boolean, underline0: Boolean, strike0: Boolean,
    ): Span {
        var color = color0
        var bold = bold0
        var italic = italic0
        var underline = underline0
        var strike = strike0
        val codes = if (params.isEmpty()) listOf(0) else params.split(';').map { it.toIntOrNull() ?: 0 }
        var k = 0
        while (k < codes.size) {
            when (val n = codes[k]) {
                0 -> { color = null; bold = false; italic = false; underline = false; strike = false }
                1 -> bold = true
                3 -> italic = true
                4 -> underline = true
                9 -> strike = true
                22 -> bold = false
                23 -> italic = false
                24 -> underline = false
                29 -> strike = false
                39 -> color = null
                in 30..37 -> color = ANSI_STD[n - 30]
                in 90..97 -> color = ANSI_BRIGHT[n - 90]
                38 -> {
                    if (k + 2 < codes.size && codes[k + 1] == 5) {
                        color = xterm256(codes[k + 2])
                        k += 2
                    } else if (k + 4 < codes.size && codes[k + 1] == 2) {
                        color = ((codes[k + 2] and 255) shl 16) or ((codes[k + 3] and 255) shl 8) or (codes[k + 4] and 255)
                        k += 4
                    }
                }
            }
            k++
        }
        return Span("", color, bold, italic, underline, strike)
    }

    private fun xterm256(n: Int): Int = when {
        n < 0 -> 0xFFFFFF
        n < 8 -> ANSI_STD[n]
        n < 16 -> ANSI_BRIGHT[n - 8]
        n < 232 -> {
            val v = n - 16
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            (steps[v / 36] shl 16) or (steps[(v / 6) % 6] shl 8) or steps[v % 6]
        }
        n < 256 -> {
            val g = 8 + (n - 232) * 10
            (g shl 16) or (g shl 8) or g
        }
        else -> 0xFFFFFF
    }

    private fun String.isHexColor(): Boolean = length == 6 && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}
