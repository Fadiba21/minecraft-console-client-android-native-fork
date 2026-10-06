package app.mccdroid.logic

/**
 * JSON mini tanpa dependensi (tidak memakai org.json) supaya logika bisa diuji di JVM biasa.
 * parse() menghasilkan: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        require(p.pos == text.length) { "Karakter berlebih pada posisi ${p.pos}" }
        return v
    }

    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (e: Exception) {
        null
    }

    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, value, pretty, 0)
        return sb.toString()
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWs()
            require(pos < s.length) { "JSON berakhir terlalu cepat" }
            val c = s[pos]
            return when {
                c == '{' -> readObject()
                c == '[' -> readArray()
                c == '"' -> readString()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c.isDigit() -> readNumber()
                else -> throw IllegalArgumentException("Karakter tak terduga '$c' pada posisi $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "Literal tidak valid pada posisi $pos" }
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                require(pos < s.length && s[pos] == '"') { "Kunci objek harus string (posisi $pos)" }
                val key = readString()
                skipWs()
                require(pos < s.length && s[pos] == ':') { "Diharapkan ':' pada posisi $pos" }
                pos++
                map[key] = readValue()
                skipWs()
                require(pos < s.length) { "Objek tidak ditutup" }
                val c = s[pos++]
                if (c == '}') break
                require(c == ',') { "Diharapkan ',' atau '}' pada posisi ${pos - 1}" }
            }
            return map
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(readValue())
                skipWs()
                require(pos < s.length) { "Array tidak ditutup" }
                val c = s[pos++]
                if (c == ']') break
                require(c == ',') { "Diharapkan ',' atau ']' pada posisi ${pos - 1}" }
            }
            return list
        }

        private fun readString(): String {
            val sb = StringBuilder()
            pos++ // "
            while (true) {
                require(pos < s.length) { "String tidak ditutup" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "Escape tidak lengkap" }
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= s.length) { "Escape \\u tidak lengkap" }
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Escape tidak dikenal: \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            val t = s.substring(start, pos)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) {
                t.toDouble()
            } else {
                t.toLongOrNull() ?: t.toDouble()
            }
        }
    }

    private fun write(sb: StringBuilder, v: Any?, pretty: Boolean, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> sb.append(if (v.isFinite()) v.toString() else "null")
            is Double -> sb.append(if (v.isFinite()) v.toString() else "null")
            is Number -> sb.append(v.toString())
            is String -> quote(sb, v)
            is Enum<*> -> quote(sb, v.name)
            is Map<*, *> -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, pretty, depth + 1)
                    quote(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    write(sb, value, pretty, depth + 1)
                }
                newline(sb, pretty, depth)
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                var first = true
                for (item in items) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, pretty, depth + 1)
                    write(sb, item, pretty, depth + 1)
                }
                newline(sb, pretty, depth)
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun newline(sb: StringBuilder, pretty: Boolean, depth: Int) {
        if (!pretty) return
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }
}

// ---- Akses ringkas -------------------------------------------------------------------------

fun Any?.asObj(): Map<String, Any?> {
    val m = this as? Map<*, *> ?: return emptyMap()
    val out = LinkedHashMap<String, Any?>()
    for ((k, v) in m) out[k.toString()] = v
    return out
}

fun Any?.asArr(): List<Any?> = (this as? List<*>) ?: emptyList<Any?>()

fun Map<String, Any?>.str(k: String, d: String = ""): String = (this[k] as? String) ?: d
fun Map<String, Any?>.bool(k: String, d: Boolean = false): Boolean = (this[k] as? Boolean) ?: d
fun Map<String, Any?>.int(k: String, d: Int = 0): Int = (this[k] as? Number)?.toInt() ?: d
fun Map<String, Any?>.long(k: String, d: Long = 0L): Long = (this[k] as? Number)?.toLong() ?: d
fun Map<String, Any?>.dbl(k: String, d: Double = 0.0): Double = (this[k] as? Number)?.toDouble() ?: d
