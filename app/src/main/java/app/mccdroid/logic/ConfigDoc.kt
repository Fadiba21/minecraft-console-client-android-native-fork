package app.mccdroid.logic

enum class VKind { BOOL, INT, FLOAT, STRING, ARRAY, TABLE, OTHER }

class CfgSection(
    val name: String,
    val isArray: Boolean,
    val index: Int,
    var comment: String = "",
) {
    val entries = ArrayList<CfgEntry>()

    /** Judul tampilan. Tabel-array yang berulang diberi nomor. */
    val title: String
        get() = when {
            name.isEmpty() -> "(Umum)"
            isArray && index > 0 -> "$name #${index + 1}"
            else -> name
        }

    val topLevel: String
        get() = name.substringBefore('.').ifEmpty { "(Umum)" }
}

class CfgEntry(
    val key: String,
    var raw: String,
    var inline: String,
    val comment: String,
    val indent: String,
) {
    lateinit var section: CfgSection

    /** Baris asli; di-null-kan setelah nilai diubah supaya baris dirender ulang. */
    internal var orig: List<String>? = null

    val kind: VKind get() = Toml.kindOf(raw)

    /** Deskripsi dari komentar di atas baris dan komentar inline (MCC memakai keduanya). */
    val description: String
        get() = listOf(comment, inline).filter { it.isNotBlank() }.joinToString("\n")

    val path: String get() = if (section.name.isEmpty()) key else section.name + "." + key
}

/**
 * Penyunting berkas konfigurasi MCC (TOML) yang mempertahankan komentar, urutan, dan format baris
 * yang tidak diubah. Hanya baris yang nilainya diubah yang ditulis ulang.
 */
class ConfigDoc private constructor(
    private val items: MutableList<Item>,
    val sections: List<CfgSection>,
    private val crlf: Boolean,
) {
    sealed class Item {
        class Text(val line: String) : Item()
        class Head(val line: String) : Item()
        class Ent(val entry: CfgEntry) : Item()
    }

    fun render(): String {
        val eol = if (crlf) "\r\n" else "\n"
        val sb = StringBuilder()
        for ((idx, item) in items.withIndex()) {
            if (idx > 0) sb.append(eol)
            when (item) {
                is Item.Text -> sb.append(item.line)
                is Item.Head -> sb.append(item.line)
                is Item.Ent -> sb.append(renderEntry(item.entry, eol))
            }
        }
        return sb.toString()
    }

    private fun renderEntry(e: CfgEntry, eol: String): String {
        val o = e.orig
        if (o != null) return o.joinToString(eol)
        val c = if (e.inline.isNotEmpty()) "  # " + e.inline else ""
        return e.indent + e.key + " = " + e.raw + c
    }

    val allEntries: List<CfgEntry> get() = sections.flatMap { it.entries }

    fun find(section: String, key: String): CfgEntry? =
        sections.firstOrNull { it.name == section }?.entries?.firstOrNull { it.key == key }

    fun section(name: String): CfgSection? = sections.firstOrNull { it.name == name }

    /** Kelompokkan bagian berdasarkan kategori tingkat atas (sebelum titik pertama). */
    fun categories(): LinkedHashMap<String, List<CfgSection>> {
        val m = LinkedHashMap<String, MutableList<CfgSection>>()
        for (s in sections) m.getOrPut(s.topLevel) { ArrayList() }.add(s)
        return LinkedHashMap<String, List<CfgSection>>(m)
    }

    /** Bagian bot ("ChatBot.X") yang punya saklar Enabled. */
    fun botSections(): List<CfgSection> =
        sections.filter { it.name.startsWith("ChatBot.") && it.entries.any { e -> e.key == "Enabled" && e.kind == VKind.BOOL } }

    fun search(query: String): List<CfgEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return allEntries.filter { e ->
            val hay = (e.section.name + " " + e.key + " " + ConfigHints.label(e) + " " + e.description + " " + (ConfigHints.hint(e)?.desc ?: "")).lowercase()
            words.all { hay.contains(it) }
        }
    }

    // ---- Penyuntingan --------------------------------------------------------------------

    fun setRaw(e: CfgEntry, raw: String) {
        e.raw = raw.trim()
        e.orig = null
    }

    fun setBool(e: CfgEntry, v: Boolean) = setRaw(e, if (v) "true" else "false")
    fun setInt(e: CfgEntry, v: Long) = setRaw(e, v.toString())
    fun setDouble(e: CfgEntry, v: Double) = setRaw(e, Toml.formatDouble(v))
    fun setString(e: CfgEntry, v: String) = setRaw(e, Toml.quote(v))
    fun setStringArray(e: CfgEntry, values: List<String>) = setRaw(e, Toml.arrayRender(values.map { Toml.quote(it) }))

    fun tableGet(e: CfgEntry, key: String): String? =
        Toml.tablePairs(e.raw).firstOrNull { it.first == key }?.second

    /** Ubah/tambah pasangan di tabel inline. [rawValue] null = hapus pasangan. */
    fun tableSet(e: CfgEntry, key: String, rawValue: String?) {
        val pairs = Toml.tablePairs(e.raw).toMutableList()
        val idx = pairs.indexOfFirst { it.first == key }
        if (rawValue == null) {
            if (idx >= 0) pairs.removeAt(idx)
        } else if (idx >= 0) {
            pairs[idx] = key to rawValue
        } else {
            pairs.add(key to rawValue)
        }
        setRaw(e, Toml.tableRender(pairs))
    }

    companion object {
        private val HEADER = Regex("^\\s*(\\[\\[?)\\s*(.+?)\\s*(\\]\\]?)\\s*(#.*)?$")

        fun parse(text: String): ConfigDoc {
            val crlf = text.contains("\r\n")
            val lines = text.replace("\r\n", "\n").split("\n")
            val items = ArrayList<Item>(lines.size)
            val sections = ArrayList<CfgSection>()
            var current = CfgSection("", false, 0)
            var rootUsed = false
            val pending = ArrayList<String>()
            val arrayCounts = HashMap<String, Int>()

            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val t = line.trim()
                if (t.isEmpty()) {
                    items.add(Item.Text(line)); pending.clear(); i++; continue
                }
                if (t.startsWith("#")) {
                    items.add(Item.Text(line)); pending.add(t.removePrefix("#").trim()); i++; continue
                }
                if (t.startsWith("[")) {
                    val m = HEADER.find(line)
                    if (m != null) {
                        val isArr = m.groupValues[1] == "[["
                        val name = m.groupValues[2]
                        val idx = if (isArr) (arrayCounts[name] ?: 0) else 0
                        if (isArr) arrayCounts[name] = idx + 1
                        val s = CfgSection(name, isArr, idx, pending.joinToString("\n"))
                        pending.clear()
                        sections.add(s)
                        current = s
                        items.add(Item.Head(line))
                        i++
                        continue
                    }
                }
                // key = value
                val eq = findEquals(line)
                if (eq < 0) {
                    items.add(Item.Text(line)); pending.clear(); i++; continue
                }
                val keyText = line.substring(0, eq).trim()
                val indent = line.substring(0, line.length - line.trimStart().length)
                val scan = scanValue(lines, i, eq + 1)
                val entry = CfgEntry(
                    key = Toml.unquote(keyText),
                    raw = scan.raw,
                    inline = scan.comment,
                    comment = pending.joinToString("\n"),
                    indent = indent,
                )
                entry.orig = lines.subList(i, scan.lastLine + 1).toList()
                pending.clear()
                if (sections.isEmpty() || current.name.isEmpty() && !rootUsed) {
                    if (sections.isEmpty() && !rootUsed) {
                        sections.add(0, current)
                        rootUsed = true
                    }
                }
                entry.section = current
                current.entries.add(entry)
                items.add(Item.Ent(entry))
                i = scan.lastLine + 1
            }
            return ConfigDoc(items, sections, crlf)
        }

        /** Posisi '=' pertama di luar tanda kutip (pemisah kunci/nilai). */
        private fun findEquals(line: String): Int {
            var inBasic = false
            var inLit = false
            var c = 0
            while (c < line.length) {
                val ch = line[c]
                if (inBasic) {
                    if (ch == '\\') c++ else if (ch == '"') inBasic = false
                } else if (inLit) {
                    if (ch == '\'') inLit = false
                } else {
                    when (ch) {
                        '"' -> inBasic = true
                        '\'' -> inLit = true
                        '#' -> return -1
                        '=' -> return c
                    }
                }
                c++
            }
            return -1
        }

        private class Scan(val raw: String, val comment: String, val lastLine: Int)

        /** Baca ekspresi nilai (bisa multi-baris untuk array/tabel/string tiga-kutip) dan komentar inline. */
        private fun scanValue(lines: List<String>, startLine: Int, startCol: Int): Scan {
            val sb = StringBuilder()
            var li = startLine
            var col = startCol
            var depth = 0
            var mlDelim: String? = null
            var comment = ""
            while (true) {
                val line = lines[li]
                var c = col
                var inBasic = false
                var inLit = false
                while (c < line.length) {
                    val ch = line[c]
                    val md = mlDelim
                    if (md != null) {
                        if (line.startsWith(md, c)) {
                            sb.append(md); c += 3; mlDelim = null
                        } else if (md == "\"\"\"" && ch == '\\' && c + 1 < line.length) {
                            sb.append(ch).append(line[c + 1]); c += 2
                        } else {
                            sb.append(ch); c++
                        }
                        continue
                    }
                    if (inBasic) {
                        if (ch == '\\' && c + 1 < line.length) {
                            sb.append(ch).append(line[c + 1]); c += 2
                            continue
                        }
                        if (ch == '"') inBasic = false
                        sb.append(ch); c++
                        continue
                    }
                    if (inLit) {
                        if (ch == '\'') inLit = false
                        sb.append(ch); c++
                        continue
                    }
                    when {
                        line.startsWith("\"\"\"", c) -> { mlDelim = "\"\"\""; sb.append("\"\"\""); c += 3 }
                        line.startsWith("'''", c) -> { mlDelim = "'''"; sb.append("'''"); c += 3 }
                        ch == '"' -> { inBasic = true; sb.append(ch); c++ }
                        ch == '\'' -> { inLit = true; sb.append(ch); c++ }
                        ch == '#' -> { comment = line.substring(c + 1).trim(); c = line.length }
                        ch == '[' || ch == '{' -> { depth++; sb.append(ch); c++ }
                        ch == ']' || ch == '}' -> { depth--; sb.append(ch); c++ }
                        else -> { sb.append(ch); c++ }
                    }
                }
                if ((mlDelim != null || depth > 0) && li + 1 < lines.size) {
                    sb.append(if (mlDelim != null) '\n' else ' ')
                    li++
                    col = 0
                } else {
                    break
                }
            }
            return Scan(sb.toString().trim(), comment, li)
        }
    }
}

/** Fungsi bantu untuk ekspresi nilai TOML (string, array, tabel inline). */
object Toml {
    private val INT_RE = Regex("[+-]?(0|[1-9][0-9_]*)|0x[0-9A-Fa-f_]+|0o[0-7_]+|0b[01_]+")
    private val FLOAT_RE = Regex("[+-]?[0-9][0-9_]*(\\.[0-9_]+)?([eE][+-]?[0-9_]+)?|[+-]?(inf|nan)")

    fun kindOf(raw: String): VKind {
        val t = raw.trim()
        return when {
            t == "true" || t == "false" -> VKind.BOOL
            t.startsWith("\"") || t.startsWith("'") -> VKind.STRING
            t.startsWith("[") -> VKind.ARRAY
            t.startsWith("{") -> VKind.TABLE
            INT_RE.matches(t) -> VKind.INT
            FLOAT_RE.matches(t) -> VKind.FLOAT
            else -> VKind.OTHER
        }
    }

    fun formatDouble(v: Double): String {
        if (v.isNaN()) return "nan"
        if (v.isInfinite()) return if (v > 0) "inf" else "-inf"
        val s = v.toString()
        return if (s.contains('E') || s.contains('e')) s else if (s.contains('.')) s else "$s.0"
    }

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == '\u007F') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun unquote(raw: String): String {
        val t = raw.trim()
        if (t.length >= 6 && t.startsWith("\"\"\"") && t.endsWith("\"\"\"")) {
            return unescape(t.substring(3, t.length - 3).removePrefix("\n"))
        }
        if (t.length >= 6 && t.startsWith("'''") && t.endsWith("'''")) {
            return t.substring(3, t.length - 3).removePrefix("\n")
        }
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) return unescape(t.substring(1, t.length - 1))
        if (t.length >= 2 && t.startsWith("'") && t.endsWith("'")) return t.substring(1, t.length - 1)
        return t
    }

    private fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                sb.append(c); i++; continue
            }
            val n = s[i + 1]
            when (n) {
                'b' -> { sb.append('\b'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                'u' -> {
                    val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6) else ""
                    val cp = hex.toIntOrNull(16)
                    if (cp != null) { sb.append(cp.toChar()); i += 6 } else { sb.append(c); i++ }
                }
                'U' -> {
                    val hex = if (i + 10 <= s.length) s.substring(i + 2, i + 10) else ""
                    val cp = hex.toIntOrNull(16)
                    if (cp != null && Character.isValidCodePoint(cp)) { sb.appendCodePoint(cp); i += 10 } else { sb.append(c); i++ }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /** Pisahkan pada koma tingkat-atas (di luar kutip dan kurung). */
    fun splitTop(inner: String): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var inBasic = false
        var inLit = false
        var i = 0
        while (i < inner.length) {
            val ch = inner[i]
            if (inBasic) {
                sb.append(ch)
                if (ch == '\\' && i + 1 < inner.length) { sb.append(inner[i + 1]); i++ } else if (ch == '"') inBasic = false
            } else if (inLit) {
                sb.append(ch)
                if (ch == '\'') inLit = false
            } else when (ch) {
                '"' -> { inBasic = true; sb.append(ch) }
                '\'' -> { inLit = true; sb.append(ch) }
                '[', '{' -> { depth++; sb.append(ch) }
                ']', '}' -> { depth--; sb.append(ch) }
                ',' -> if (depth == 0) {
                    parts.add(sb.toString().trim()); sb.setLength(0)
                } else sb.append(ch)
                else -> sb.append(ch)
            }
            i++
        }
        val last = sb.toString().trim()
        if (last.isNotEmpty()) parts.add(last)
        return parts.filter { it.isNotEmpty() }
    }

    private fun innerOf(raw: String, open: Char, close: Char): String {
        val t = raw.trim()
        if (!t.startsWith(open)) return ""
        val end = t.lastIndexOf(close)
        return if (end > 0) t.substring(1, end) else t.substring(1)
    }

    fun arrayItems(raw: String): List<String> = splitTop(innerOf(raw, '[', ']'))

    fun arrayRender(items: List<String>): String =
        if (items.isEmpty()) "[]" else "[ " + items.joinToString(", ") + " ]"

    fun tablePairs(raw: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (part in splitTop(innerOf(raw, '{', '}'))) {
            var inBasic = false
            var inLit = false
            var eq = -1
            var i = 0
            while (i < part.length && eq < 0) {
                val ch = part[i]
                if (inBasic) {
                    if (ch == '\\') i++ else if (ch == '"') inBasic = false
                } else if (inLit) {
                    if (ch == '\'') inLit = false
                } else when (ch) {
                    '"' -> inBasic = true
                    '\'' -> inLit = true
                    '=' -> eq = i
                }
                i++
            }
            if (eq > 0) out.add(unquote(part.substring(0, eq).trim()) to part.substring(eq + 1).trim())
        }
        return out
    }

    fun tableRender(pairs: List<Pair<String, String>>): String =
        if (pairs.isEmpty()) "{}" else "{ " + pairs.joinToString(", ") { it.first + " = " + it.second } + " }"
}
