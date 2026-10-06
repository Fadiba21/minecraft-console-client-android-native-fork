package app.mccdroid.logic

enum class Level(val label: String) { OFF("Mati"), SILENT("Senyap"), NORMAL("Normal"), HIGH("Penting (pop-up)") }

data class EventPref(val level: Level, val vibrate: Boolean = false, val cooldownSec: Int = 0, val reply: Boolean = false)

data class NotifyConfig(
    val prefs: Map<McEvent, EventPref> = NotifyLogic.defaults(),
    val quietEnabled: Boolean = false,
    /** Menit sejak 00:00. */
    val quietStartMin: Int = 23 * 60,
    val quietEndMin: Int = 7 * 60,
    /** Jam tenang tetap memunculkan peristiwa kritis (kick, putus, crash, login). */
    val quietAllowCritical: Boolean = true,
    val keywords: List<String> = emptyList(),
)

object NotifyLogic {
    val CRITICAL = setOf(McEvent.KICKED, McEvent.LOST, McEvent.LOGIN_FAILED, McEvent.DEVICE_CODE, McEvent.PROCESS_CRASHED)

    fun defaults(): Map<McEvent, EventPref> = linkedMapOf(
        McEvent.JOINED to EventPref(Level.NORMAL),
        McEvent.KICKED to EventPref(Level.HIGH, vibrate = true),
        McEvent.LOST to EventPref(Level.HIGH, vibrate = true),
        McEvent.LOGIN_FAILED to EventPref(Level.HIGH, vibrate = true),
        McEvent.DEVICE_CODE to EventPref(Level.HIGH, vibrate = true),
        McEvent.RECONNECTING to EventPref(Level.SILENT, cooldownSec = 60),
        McEvent.RESTARTING to EventPref(Level.SILENT, cooldownSec = 60),
        McEvent.DEAD to EventPref(Level.NORMAL, cooldownSec = 30),
        McEvent.WHISPER to EventPref(Level.HIGH, vibrate = true, cooldownSec = 5, reply = true),
        McEvent.MENTION to EventPref(Level.NORMAL, cooldownSec = 10, reply = true),
        McEvent.KEYWORD to EventPref(Level.HIGH, vibrate = true, cooldownSec = 10, reply = true),
        McEvent.PLAYER_JOIN to EventPref(Level.OFF),
        McEvent.PLAYER_LEAVE to EventPref(Level.OFF),
        McEvent.PROCESS_STARTED to EventPref(Level.OFF),
        McEvent.PROCESS_STOPPED to EventPref(Level.SILENT),
        McEvent.PROCESS_CRASHED to EventPref(Level.HIGH, vibrate = true),
    )

    fun inQuiet(cfg: NotifyConfig, nowMin: Int): Boolean {
        if (!cfg.quietEnabled) return false
        val s = cfg.quietStartMin
        val e = cfg.quietEndMin
        return if (s == e) false else if (s < e) nowMin in s until e else nowMin >= s || nowMin < e
    }

    /** Preferensi efektif untuk [event] saat ini; null bila tidak boleh ditampilkan. */
    fun effective(cfg: NotifyConfig, event: McEvent, nowMin: Int): EventPref? {
        val p = cfg.prefs[event] ?: defaults()[event] ?: return null
        if (p.level == Level.OFF) return null
        if (inQuiet(cfg, nowMin)) {
            if (event in CRITICAL && cfg.quietAllowCritical) return p
            return p.copy(level = Level.SILENT, vibrate = false)
        }
        return p
    }

    fun toJson(c: NotifyConfig): String = Json.stringify(
        linkedMapOf<String, Any?>(
            "quietEnabled" to c.quietEnabled, "quietStartMin" to c.quietStartMin, "quietEndMin" to c.quietEndMin,
            "quietAllowCritical" to c.quietAllowCritical, "keywords" to c.keywords,
            "prefs" to c.prefs.entries.associate { (k, v) ->
                k.name to linkedMapOf<String, Any?>("level" to v.level.name, "vibrate" to v.vibrate, "cooldownSec" to v.cooldownSec, "reply" to v.reply)
            },
        ),
    )

    fun fromJson(text: String): NotifyConfig {
        val m = Json.parseOrNull(text).asObj()
        if (m.isEmpty()) return NotifyConfig()
        val prefs = LinkedHashMap(defaults())
        val pm = m["prefs"].asObj()
        for (ev in McEvent.values()) {
            val o = pm[ev.name].asObj()
            if (o.isEmpty()) continue
            val lvl = Level.values().firstOrNull { it.name == o.str("level") } ?: prefs[ev]?.level ?: Level.NORMAL
            prefs[ev] = EventPref(lvl, o.bool("vibrate"), o.int("cooldownSec"), o.bool("reply"))
        }
        return NotifyConfig(
            prefs = prefs,
            quietEnabled = m.bool("quietEnabled"),
            quietStartMin = m.int("quietStartMin", 23 * 60),
            quietEndMin = m.int("quietEndMin", 7 * 60),
            quietAllowCritical = m.bool("quietAllowCritical", true),
            keywords = m["keywords"].asArr().mapNotNull { it as? String },
        )
    }
}
