package app.mccdroid.logic

enum class TriggerKind(val label: String) { LINE("Baris log cocok"), EVENT("Kejadian") }
enum class MatchType(val label: String) {
    CONTAINS("Mengandung"), REGEX("Regex"), STARTS_WITH("Diawali"), EXACT("Persis sama")
}
enum class ActionType(val label: String) {
    SEND("Kirim ke MCC"), NOTIFY("Notifikasi"), DELAY("Tunggu (ms)"), RESTART("Mulai ulang MCC"), STOP("Hentikan MCC")
}

data class RuleAction(val type: ActionType, val arg: String = "")

data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    /** Kosong = semua profil. */
    val profileId: String = "",
    val trigger: TriggerKind = TriggerKind.LINE,
    val match: MatchType = MatchType.CONTAINS,
    val pattern: String = "",
    val ignoreCase: Boolean = true,
    val event: McEvent = McEvent.JOINED,
    val cooldownSec: Int = 2,
    val actions: List<RuleAction> = emptyList(),
)

/** Hasil pencocokan: aksi yang sudah diperluas (placeholder diganti). */
data class Fired(val rule: Rule, val actions: List<RuleAction>)

object Automation {

    fun toJson(rules: List<Rule>): String = Json.stringify(rules.map { r ->
        linkedMapOf<String, Any?>(
            "id" to r.id, "name" to r.name, "enabled" to r.enabled, "profileId" to r.profileId,
            "trigger" to r.trigger.name, "match" to r.match.name, "pattern" to r.pattern,
            "ignoreCase" to r.ignoreCase, "event" to r.event.name, "cooldownSec" to r.cooldownSec,
            "actions" to r.actions.map { a -> linkedMapOf<String, Any?>("type" to a.type.name, "arg" to a.arg) },
        )
    }, pretty = true)

    fun fromJson(text: String): List<Rule> {
        val root = Json.parseOrNull(text).asArr()
        return root.mapNotNull { item ->
            val m = item.asObj()
            if (m.isEmpty()) return@mapNotNull null
            Rule(
                id = m.str("id", java.util.UUID.randomUUID().toString()),
                name = m.str("name", "Aturan"),
                enabled = m.bool("enabled", true),
                profileId = m.str("profileId"),
                trigger = enumOr(m.str("trigger"), TriggerKind.LINE),
                match = enumOr(m.str("match"), MatchType.CONTAINS),
                pattern = m.str("pattern"),
                ignoreCase = m.bool("ignoreCase", true),
                event = enumOr(m.str("event"), McEvent.JOINED),
                cooldownSec = m.int("cooldownSec", 2),
                actions = m["actions"].asArr().mapNotNull { a ->
                    val am = a.asObj()
                    if (am.isEmpty()) null else RuleAction(enumOr(am.str("type"), ActionType.SEND), am.str("arg"))
                },
            )
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    /** @return daftar grup (indeks 0 = seluruh teks yang cocok) atau null bila tidak cocok. */
    fun matchLine(rule: Rule, clean: String): List<String>? {
        val p = rule.pattern
        if (p.isEmpty()) return null
        return when (rule.match) {
            MatchType.CONTAINS -> if (clean.contains(p, rule.ignoreCase)) listOf(p) else null
            MatchType.STARTS_WITH -> if (clean.trim().startsWith(p, rule.ignoreCase)) listOf(p) else null
            MatchType.EXACT -> if (clean.trim().equals(p, rule.ignoreCase)) listOf(clean.trim()) else null
            MatchType.REGEX -> {
                val re = compile(p, rule.ignoreCase) ?: return null
                val m = re.find(clean) ?: return null
                m.groupValues
            }
        }
    }

    private val regexCache = HashMap<String, Regex?>()

    private fun compile(p: String, ignoreCase: Boolean): Regex? = synchronized(regexCache) {
        val key = (if (ignoreCase) "i:" else "s:") + p
        if (regexCache.containsKey(key)) regexCache[key]
        else {
            val r = try {
                if (ignoreCase) Regex(p, RegexOption.IGNORE_CASE) else Regex(p)
            } catch (e: Exception) {
                null
            }
            regexCache[key] = r
            r
        }
    }

    fun isValidRegex(p: String): Boolean = compile(p, true) != null

    /** Ganti {line}, {1}, {name}, {player}, dst. Placeholder tak dikenal dibiarkan. */
    fun expand(template: String, vars: Map<String, String>, groups: List<String>): String {
        if (template.indexOf('{') < 0) return template
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                val end = template.indexOf('}', i + 1)
                if (end > i) {
                    val key = template.substring(i + 1, end)
                    val idx = key.toIntOrNull()
                    val rep = when {
                        idx != null -> groups.getOrNull(idx)
                        else -> vars[key]
                    }
                    if (rep != null) {
                        sb.append(rep); i = end + 1; continue
                    }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** Contoh aturan siap pakai. */
    fun presets(): List<Rule> = listOf(
        Rule(
            id = "", name = "Auto /login saat masuk", trigger = TriggerKind.LINE, match = MatchType.CONTAINS,
            pattern = "/login", cooldownSec = 10,
            actions = listOf(RuleAction(ActionType.DELAY, "1500"), RuleAction(ActionType.SEND, "/send /login KATA_SANDI")),
        ),
        Rule(
            id = "", name = "Balas pesan pribadi", trigger = TriggerKind.EVENT, event = McEvent.WHISPER, cooldownSec = 30,
            actions = listOf(RuleAction(ActionType.SEND, "/send /r Saya sedang AFK, nanti dibalas ya.")),
        ),
        Rule(
            id = "", name = "Notifikasi kata 'restart'", trigger = TriggerKind.LINE, match = MatchType.REGEX,
            pattern = "restart|maintenance", cooldownSec = 60,
            actions = listOf(RuleAction(ActionType.NOTIFY, "Server akan restart|{line}")),
        ),
        Rule(
            id = "", name = "Respawn saat mati", trigger = TriggerKind.EVENT, event = McEvent.DEAD, cooldownSec = 5,
            actions = listOf(RuleAction(ActionType.DELAY, "1500"), RuleAction(ActionType.SEND, "/respawn")),
        ),
    )
}

/** Mesin pencocok dengan pelacak cooldown per aturan+profil. Eksekusi aksi dilakukan pemanggil. */
class RuleEngine {
    private val last = HashMap<String, Long>()

    private fun ready(rule: Rule, profileId: String, now: Long): Boolean {
        val key = rule.id + "@" + profileId
        val cd = maxOf(1, rule.cooldownSec) * 1000L // minimal 1 dtk mencegah loop balasan
        val prev = last[key]
        if (prev != null && now - prev < cd) return false
        last[key] = now
        return true
    }

    private fun applies(rule: Rule, profileId: String) =
        rule.enabled && (rule.profileId.isEmpty() || rule.profileId == profileId)

    private fun expandAll(rule: Rule, vars: Map<String, String>, groups: List<String>) =
        rule.actions.map { it.copy(arg = Automation.expand(it.arg, vars, groups)) }

    @Synchronized
    fun onLine(
        rules: List<Rule>, profileId: String, profileName: String, clean: String, now: Long = System.currentTimeMillis(),
    ): List<Fired> {
        val out = ArrayList<Fired>(1)
        for (r in rules) {
            if (r.trigger != TriggerKind.LINE || !applies(r, profileId)) continue
            val groups = Automation.matchLine(r, clean) ?: continue
            if (!ready(r, profileId, now)) continue
            out += Fired(r, expandAll(r, mapOf("line" to clean, "name" to profileName), groups))
        }
        return out
    }

    @Synchronized
    fun onEvent(
        rules: List<Rule>, profileId: String, profileName: String, d: Detected, now: Long = System.currentTimeMillis(),
    ): List<Fired> {
        val out = ArrayList<Fired>(1)
        for (r in rules) {
            if (r.trigger != TriggerKind.EVENT || r.event != d.event || !applies(r, profileId)) continue
            if (!ready(r, profileId, now)) continue
            val vars = HashMap<String, String>(d.extra)
            vars["detail"] = d.detail
            vars["name"] = profileName
            vars["event"] = d.event.label
            out += Fired(r, expandAll(r, vars, listOf(d.detail)))
        }
        return out
    }
}
