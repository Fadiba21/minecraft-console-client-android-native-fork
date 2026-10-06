package app.mccdroid.core

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.mccdroid.logic.GeminiClient
import app.mccdroid.logic.Json
import app.mccdroid.logic.asObj
import app.mccdroid.logic.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.math.pow
import kotlin.random.Random

private const val CHATGAME_DEFAULT_PROMPT = "Kamu pemain yang menjawab kuis Chat Game di server Minecraft. Jawab HANYA jawabannya, 1 sampai 3 kata, tanpa penjelasan, tanpa tanda kutip, tanpa titik. Gunakan bahasa yang sama dengan soal. Soal hitungan jawab angkanya saja. Jika ragu, tebak jawaban paling mungkin."

/** Port logika ChatGameAI Fabric untuk MCC BasicIO. Tidak memuat JAR Fabric. */
data class ChatGameAiConfig(
    val enabled: Boolean = false,
    val triggerKeyword: String = "soal",
    val maxLinesAfterTrigger: Int = 3,
    val captureTimeoutMs: Int = 5000,
    val ignorePlayerChat: Boolean = true,
    val detectMathWithoutTag: Boolean = true,
    val answerGeneral: Boolean = true,
    val answerMath: Boolean = true,
    val answerType: Boolean = true,
    val answerScramble: Boolean = true,
    val localMathSolver: Boolean = true,
    val useCache: Boolean = true,
    val saveUnverified: Boolean = false,
    val ignoredCategories: String = "",
    val winVerbs: String = "answered,typed,solved,unscrambled,guessed,decoded,benar,menang,winner,won,berhasil,selamat",
    val winKeywords: String = "",
    val revealRegex: String = "",
    val confirmWindowMs: Int = 8000,
    val cooldownMs: Int = 3000,
    val delayGeneralMs: Int = 0,
    val delayMathMs: Int = 0,
    val delayTypeMs: Int = 0,
    val delayScrambleMs: Int = 0,
    val randomDelay: Boolean = false,
    val randomDelayMaxMs: Int = 0,
    val maxWords: Int = 3,
    val answerCase: String = "keep",
    val stripTrailingPunct: Boolean = true,
    val systemPrompt: String = CHATGAME_DEFAULT_PROMPT,
    val notifyEnabled: Boolean = true,
    val notifyMs: Int = 2500,
    val showInfoInConsole: Boolean = true,
)

data class ChatGameAiStats(
    val total: Int = 0,
    val won: Int = 0,
    val late: Int = 0,
    val local: Int = 0,
    val cache: Int = 0,
    val ai: Int = 0,
    val aiMsSum: Long = 0,
    val aiCount: Int = 0,
) {
    val winRate: Int get() = if (total == 0) 0 else (won * 100 / total)
    val avgAiMs: Long get() = if (aiCount == 0) 0 else aiMsSum / aiCount
}

object ChatGameAi {
    private const val PREFS = "chatgameai_port"
    private const val CFG = "config"
    private const val STATS = "stats"
    private const val CACHE = "cache"
    private lateinit var ctx: Context
    private val _config = mutableStateOf(ChatGameAiConfig())
    private val _stats = mutableStateOf(ChatGameAiStats())
    private val cache = ConcurrentHashMap<String, String>()
    private val waiting = ConcurrentHashMap<String, Pending>()
    private val lastAnswered = ConcurrentHashMap<String, Long>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val scope get() = SessionManager.scope
    val config: ChatGameAiConfig get() = _config.value
    val stats: ChatGameAiStats get() = _stats.value
    val revision get() = _config

    private data class Pending(val key: String, val answer: String, val at: Long, val category: String, val source: String)

    fun init(c: Context) {
        ctx = c.applicationContext
        val p = prefs()
        _config.value = decodeConfig(p.getString(CFG, "") ?: "")
        _stats.value = decodeStats(p.getString(STATS, "") ?: "")
        cache.clear()
        runCatching {
            val o = JSONObject(p.getString(CACHE, "{}") ?: "{}")
            o.keys().forEach { k -> cache[k] = o.optString(k) }
        }
    }

    fun update(transform: (ChatGameAiConfig) -> ChatGameAiConfig) {
        _config.value = transform(_config.value)
        prefs().edit().putString(CFG, encodeConfig(_config.value)).apply()
    }

    fun reset() { _config.value = ChatGameAiConfig(); prefs().edit().putString(CFG, encodeConfig(_config.value)).apply() }
    fun clearCache() { cache.clear(); prefs().edit().remove(CACHE).apply() }

    fun onLine(session: McSession, raw: String) {
        val c = config
        if (!c.enabled) return
        val line = raw.replace(Regex("§."), "").trim()
        if (line.isBlank() || line.startsWith("[ChatGameAI]")) return
        val now = System.currentTimeMillis()
        if (lastSeen[session.profileId] == line.hashCode().toLong() && now - (lastSeen["t:${session.profileId}"] ?: 0) < 100) return
        lastSeen[session.profileId] = line.hashCode().toLong(); lastSeen["t:${session.profileId}"] = now
        checkWinner(session, line, now)
        val p = waiting[session.profileId]
        if (p != null && now - p.at <= c.confirmWindowMs) {
            findReveal(line, c)?.let { answer ->
                cache[p.key] = answer
                persistCache()
                waiting.remove(session.profileId)
                info(session, "Jawaban server tersimpan: $answer")
                return
            }
        }
        if (c.ignorePlayerChat && isPlayerChat(line, session.accountName)) return
        val trigger = c.triggerKeyword.trim()
        val idx = if (trigger.isNotEmpty()) line.indexOf(trigger, ignoreCase = true) else -1
        if (idx >= 0) {
            val candidate = line.substring(idx + trigger.length).trim().trimStart(':', '-', '>', '»', '|', ']').trim()
            startQuestion(session, candidate, now)
            return
        }
        if (p != null && now - p.at <= c.captureTimeoutMs && p.answer.isEmpty()) startQuestion(session, line, now)
        if (c.detectMathWithoutTag && c.answerMath && looksLikeMath(line)) startQuestion(session, line, now)
    }

    private fun startQuestion(s: McSession, question: String, now: Long) {
        val c = config
        val q = question.trim()
        if (q.isBlank() || q.length > 500) return
        val key = norm(q)
        val last = lastAnswered[s.profileId] ?: 0
        if (now - last < c.cooldownMs) return
        if (ignoredCategory(q, c) || key.length < 2) return
        val category = category(q)
        if (!enabledCategory(category, c)) return
        lastAnswered[s.profileId] = now
        inc { copy(total = total + 1) }
        scope.launch {
            var answer: String? = null
            var source = ""
            if (c.localMathSolver && category == "hitung") mathSolve(q)?.let { answer = it; source = "local" }
            if (answer == null && c.answerType) typeSolve(q)?.let { answer = it; source = "local" }
            if (answer == null && c.answerScramble) cipherSolve(q)?.let { answer = it; source = "local" }
            if (answer == null && c.useCache) cache[key]?.let { answer = it; source = "cache" }
            if (answer == null && c.answerGeneral) {
                val started = System.currentTimeMillis()
                val api = GeminiStore.apiKey
                if (api.isNotBlank()) {
                    runCatching {
                        val reply = GeminiClient.generate(api, GeminiStore.selectedModel, listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to q)))), c.systemPrompt, emptyList(), 128, 0.2)
                        reply.text.trim().ifBlank { null }
                    }.getOrNull()?.let { answer = it; source = "ai"; inc { copy(ai = ai + 1, aiMsSum = aiMsSum + (System.currentTimeMillis() - started), aiCount = aiCount + 1) } }
                }
            }
            if (answer.isNullOrBlank()) { info(s, "Tidak ada jawaban untuk: $q"); return@launch }
            val clean = cleanAnswer(answer!!, c)
            if (clean.isBlank()) return@launch
            if (source == "local") inc { copy(local = local + 1) }
            if (source == "cache") inc { copy(cache = cache + 1) }
            if (c.saveUnverified || source != "ai") { cache[key] = clean; persistCache() }
            waiting[s.profileId] = Pending(key, clean, System.currentTimeMillis(), category, source)
            val delayMs = resolveDelay(category, c)
            if (delayMs > 0) delay(delayMs.toLong())
            s.send(clean)
            info(s, "Jawab [$category/$source]: $clean")
        }
    }

    private fun checkWinner(s: McSession, line: String, now: Long) {
        val p = waiting[s.profileId] ?: return
        if (now - p.at > config.confirmWindowMs) { waiting.remove(s.profileId); return }
        val verbs = config.winVerbs.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val name = s.accountName?.trim().orEmpty()
        val mentionsMe = name.isNotEmpty() && line.contains(name, true) || Regex("\\b(you|kamu|anda)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line)
        val win = mentionsMe && (verbs.isEmpty() || verbs.any { line.contains(it, true) })
        if (!win) return
        waiting.remove(s.profileId)
        if (config.saveUnverified.not()) { cache[p.key] = p.answer; persistCache() }
        inc { copy(won = won + 1) }
        if (config.notifyEnabled) Notifier.custom(ctx, ProfileStore.get(s.profileId), "Chat Game", "Terjawab: ${p.answer}")
        info(s, "Terjawab: ${p.answer}")
    }

    private fun findReveal(line: String, c: ChatGameAiConfig): String? {
        val built = Regex("(?i)(?:jawaban(?:\\s+yang)?\\s+benar|jawabannya|correct\\s+answer|the\\s+answer\\s+was)\\s*(?:adalah|is|:|=)?\\s*:?\\s*(.+)$").find(line)?.groupValues?.getOrNull(1)
        val custom = c.revealRegex.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it).find(line)?.groupValues?.getOrNull(1) }.getOrNull() }
        return (built ?: custom)?.trim()?.trimEnd('.', '!', '?', ':')?.takeIf { it.isNotBlank() }
    }

    private fun typeSolve(q: String): String? {
        val m = Regex("(?i)(?:first\\s+to\\s+(?:type|write)|type|ketik(?:kan)?)\\s*:?\\s*(?:the\\s+)?(?:word|text|phrase|kata|teks)?\\s*[\\\"'“‘`]([^\\\"'”’`]{1,60})[\\\"'”’`]").find(q)
        return m?.groupValues?.getOrNull(1)?.trim()
    }

    private fun cipherSolve(q: String): String? {
        val m = Regex("(?i)\\(?\\s*(?:key|kunci|shift|geser)\\s*[:=]\\s*(\\d{1,2})\\s*(down|up|turun|naik|bawah|atas|mundur|maju)\\s*\\)?").find(q) ?: return null
        val key = m.groupValues[1].toIntOrNull() ?: return null
        val down = m.groupValues[2].lowercase() in setOf("down", "turun", "bawah", "mundur")
        val letters = q.substringAfter(m.value).replace(Regex("[^A-Za-z]"), "")
        if (letters.isBlank()) return null
        return letters.map { ch ->
            val base = if (ch.isUpperCase()) 'A'.code else 'a'.code
            ((ch.code - base + if (down) -key else key).mod(26) + base).toChar()
        }.joinToString("")
    }

    private fun mathSolve(q: String): String? {
        var x = q.lowercase(Locale.ROOT)
            .replace(Regex("(?<!\\p{L})(ditambahkan|ditambah|tambahkan|tambah|plus)(?!\\p{L})"), "+")
            .replace(Regex("(?<!\\p{L})(dikurangi|dikurang|kurangi|kurang|minus)(?!\\p{L})"), "-")
            .replace(Regex("(?<!\\p{L})(dikalikan|dikali|kalikan|kali|times)(?!\\p{L})"), "*")
            .replace(Regex("(?<!\\p{L})(dibagi|bagi|divided\\s+by)(?!\\p{L})"), "/")
        val candidate = Regex("[0-9(][0-9\\s+\\-*/x×÷^%().,]*[0-9)]").findAll(x.replace(',', '.')).maxByOrNull { it.value.length }?.value ?: return null
        val expr = candidate.replace('x', '*').replace('×', '*').replace('÷', '/').replace("^", "**").replace(Regex("\\s+"), "")
        if (!Regex("^[0-9+\\-*/().%]+$").matches(expr) || !expr.any { it in "+-*/%" }) return null
        return runCatching { NumParser(expr).parse().let { BigDecimal.valueOf(it).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() } }.getOrNull()
    }

    private class NumParser(private val s: String) { var i = 0; fun parse(): Double { val v = add(); if (i != s.length) error("tail"); return v }; private fun add(): Double { var v = mul(); while (i < s.length && (s[i] == '+' || s[i] == '-')) { val o=s[i++]; val n=mul(); v=if(o=='+')v+n else v-n }; return v }; private fun mul(): Double { var v=pow(); while(i<s.length&&(s[i]=='*'||s[i]=='/'||s[i]=='%')){val o=s[i++];val n=pow();v=when(o){ '*'->v*n; '/'->v/n; else->v%n}};return v }; private fun pow(): Double { var v=un(); if(i<s.length&&s[i]=='^'){i++;v=v.pow(un())};return v }; private fun un(): Double { if(i<s.length&&s[i]=='-'){i++;return -un()}; if(i<s.length&&s[i]=='+')i++; if(i<s.length&&s[i]=='('){i++;val v=add();if(i>=s.length||s[i++]!=')')error("paren");return v}; val st=i;while(i<s.length&&(s[i].isDigit()||s[i]=='.'))i++;return s.substring(st,i).toDouble() } }

    private fun looksLikeMath(q: String) = Regex("(?i)(mat|math|hitung|calc|angka|number|aritm|tambah|add|sum|jumlah)").containsMatchIn(q) && Regex("[0-9].*[+\\-*/%].*[0-9]").containsMatchIn(q)
    private fun category(q: String): String = when { typeSolve(q) != null -> "ketik"; cipherSolve(q) != null -> "sandi"; looksLikeMath(q) -> "hitung"; else -> "umum" }
    private fun enabledCategory(cat: String, c: ChatGameAiConfig) = when(cat){"ketik"->c.answerType;"sandi"->c.answerScramble;"hitung"->c.answerMath;else->c.answerGeneral}
    private fun ignoredCategory(q: String, c: ChatGameAiConfig) = c.ignoredCategories.split(',').any { it.isNotBlank() && q.contains(it.trim(), true) }
    private fun resolveDelay(cat: String, c: ChatGameAiConfig): Int { val base = when(cat){"ketik"->c.delayTypeMs;"sandi"->c.delayScrambleMs;"hitung"->c.delayMathMs;else->c.delayGeneralMs}; return if(c.randomDelay) Random.nextInt(base.coerceAtLeast(0), c.randomDelayMaxMs.coerceAtLeast(base)+1) else base }
    private fun isPlayerChat(line: String, name: String?) = line.startsWith("<") || Regex("^\\s*[▌|│]?\\s*\\[\\s*[^]]+\\s*(?:->|→|»)\\s*(?:me|you)\\s*]", RegexOption.IGNORE_CASE).containsMatchIn(line) || (!name.isNullOrBlank() && line.startsWith("${name.trim()}:", true))
    private fun norm(s: String) = s.replace(Regex("§."), "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    private fun cleanAnswer(s: String, c: ChatGameAiConfig): String { var x=s.trim().replace(Regex("^[\\\"'`]+|[\\\"'`]+$"), "").replace(Regex("(?i)^(jawaban|answer)\\s*[:=-]\\s*"), ""); if(c.stripTrailingPunct)x=x.trimEnd('.', '!', '?', ',', ';', ':'); if(c.answerCase=="lower")x=x.lowercase(); if(c.answerCase=="upper")x=x.uppercase(); if(c.maxWords>0)x=x.split(Regex("\\s+")).take(c.maxWords).joinToString(" "); return x }
    private fun info(s: McSession, text: String) { if(config.showInfoInConsole) s.log.append("[ChatGameAI] $text", app.mccdroid.logic.LineKind.SYS) }
    private fun inc(f: ChatGameAiStats.() -> ChatGameAiStats) { _stats.value=f(_stats.value); prefs().edit().putString(STATS, encodeStats(_stats.value)).apply() }
    private fun prefs()=ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun persistCache(){ val o=JSONObject(); cache.entries.take(2000).forEach{(k,v)->o.put(k,v)};prefs().edit().putString(CACHE,o.toString()).apply() }
    private fun encodeConfig(c: ChatGameAiConfig)=JSONObject().apply{ c::class.java.declaredFields.filter{it.name!="INSTANCE"}.forEach{ f-> f.isAccessible=true; put(f.name,f.get(c)) } }.toString()
    private fun decodeConfig(s: String): ChatGameAiConfig {
        if (s.isBlank()) return ChatGameAiConfig()
        val o = runCatching { JSONObject(s) }.getOrNull() ?: return ChatGameAiConfig()
        fun b(n: String, d: Boolean) = o.optBoolean(n, d)
        fun i(n: String, d: Int) = o.optInt(n, d)
        fun z(n: String, d: String) = o.optString(n, d)
        return ChatGameAiConfig(
            enabled = b("enabled", false), triggerKeyword = z("triggerKeyword", "soal"),
            maxLinesAfterTrigger = i("maxLinesAfterTrigger", 3), captureTimeoutMs = i("captureTimeoutMs", 5000),
            ignorePlayerChat = b("ignorePlayerChat", true), detectMathWithoutTag = b("detectMathWithoutTag", true),
            answerGeneral = b("answerGeneral", true), answerMath = b("answerMath", true), answerType = b("answerType", true),
            answerScramble = b("answerScramble", true), localMathSolver = b("localMathSolver", true), useCache = b("useCache", true),
            saveUnverified = b("saveUnverified", false), ignoredCategories = z("ignoredCategories", ""),
            winVerbs = z("winVerbs", ChatGameAiConfig().winVerbs), winKeywords = z("winKeywords", ""), revealRegex = z("revealRegex", ""),
            confirmWindowMs = i("confirmWindowMs", 8000), cooldownMs = i("cooldownMs", 3000),
            delayGeneralMs = i("delayGeneralMs", 0), delayMathMs = i("delayMathMs", 0), delayTypeMs = i("delayTypeMs", 0),
            delayScrambleMs = i("delayScrambleMs", 0), randomDelay = b("randomDelay", false), randomDelayMaxMs = i("randomDelayMaxMs", 0),
            maxWords = i("maxWords", 3), answerCase = z("answerCase", "keep"), stripTrailingPunct = b("stripTrailingPunct", true),
            systemPrompt = z("systemPrompt", DEFAULT_PROMPT), notifyEnabled = b("notifyEnabled", true), notifyMs = i("notifyMs", 2500),
            showInfoInConsole = b("showInfoInConsole", true),
        )
    }
    private fun encodeStats(s:ChatGameAiStats)=JSONObject().apply{put("total",s.total);put("won",s.won);put("late",s.late);put("local",s.local);put("cache",s.cache);put("ai",s.ai);put("aiMsSum",s.aiMsSum);put("aiCount",s.aiCount)}.toString()
    private fun decodeStats(s:String):ChatGameAiStats { val o=runCatching{JSONObject(s)}.getOrNull()?:return ChatGameAiStats();return ChatGameAiStats(o.optInt("total"),o.optInt("won"),o.optInt("late"),o.optInt("local"),o.optInt("cache"),o.optInt("ai"),o.optLong("aiMsSum"),o.optInt("aiCount")) }
    const val DEFAULT_PROMPT = CHATGAME_DEFAULT_PROMPT
}
