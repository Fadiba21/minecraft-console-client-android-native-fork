package app.mccdroid.core

import android.content.Context
import app.mccdroid.logic.Automation
import app.mccdroid.logic.Json
import app.mccdroid.logic.NotifyConfig
import app.mccdroid.logic.NotifyLogic
import app.mccdroid.logic.Rule
import app.mccdroid.logic.asArr
import app.mccdroid.logic.asObj
import app.mccdroid.logic.bool
import app.mccdroid.logic.int
import app.mccdroid.logic.long
import app.mccdroid.logic.str
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.UUID

/** Satu profil = satu akun/server dengan folder dan config MCC sendiri. */
data class Profile(
    val id: String,
    val name: String,
    val notes: String = "",
    val autoStart: Boolean = false,
    val autoRestart: Boolean = true,
    val extraArgs: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val colorIdx: Int = 0,
)

object ProfileStore {
    private lateinit var ctx: Context
    val profiles = MutableStateFlow<List<Profile>>(emptyList())

    private fun file() = File(ctx.filesDir, "profiles.json")

    fun init(c: Context) {
        ctx = c.applicationContext
        val f = file()
        val list = if (f.exists()) {
            Json.parseOrNull(f.readText()).asArr().mapNotNull { it.asObj().takeIf { m -> m.isNotEmpty() } }.map { m ->
                Profile(
                    id = m.str("id"), name = m.str("name", "Profil"), notes = m.str("notes"),
                    autoStart = m.bool("autoStart"), autoRestart = m.bool("autoRestart", true),
                    extraArgs = m.str("extraArgs"), createdAt = m.long("createdAt"), colorIdx = m.int("colorIdx"),
                )
            }.filter { it.id.isNotEmpty() }
        } else emptyList()
        profiles.value = list
    }

    fun get(id: String): Profile? = profiles.value.firstOrNull { it.id == id }

    private fun save() {
        val json = Json.stringify(profiles.value.map {
            linkedMapOf<String, Any?>(
                "id" to it.id, "name" to it.name, "notes" to it.notes, "autoStart" to it.autoStart,
                "autoRestart" to it.autoRestart, "extraArgs" to it.extraArgs, "createdAt" to it.createdAt,
                "colorIdx" to it.colorIdx,
            )
        }, pretty = true)
        Paths.writeAtomic(file(), json)
    }

    fun create(name: String): Profile {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val p = Profile(id = id, name = name.ifBlank { "Profil ${profiles.value.size + 1}" }, colorIdx = profiles.value.size % 6)
        Paths.profileDir(ctx, id)
        profiles.value = profiles.value + p
        save()
        return p
    }

    fun update(p: Profile) {
        profiles.value = profiles.value.map { if (it.id == p.id) p else it }
        save()
    }

    fun delete(id: String) {
        SessionManager.forget(id)
        profiles.value = profiles.value.filter { it.id != id }
        File(Paths.profilesDir(ctx), id).deleteRecursively()
        save()
    }

    fun duplicate(id: String): Profile? {
        val src = get(id) ?: return null
        val copy = create(src.name + " (salinan)")
        val from = Paths.profileDir(ctx, id)
        val to = Paths.profileDir(ctx, copy.id)
        try {
            from.copyRecursively(to, overwrite = true)
        } catch (_: Exception) {
        }
        update(copy.copy(notes = src.notes, extraArgs = src.extraArgs, autoRestart = src.autoRestart))
        return get(copy.id)
    }
}

object RuleStore {
    private lateinit var ctx: Context
    val rules = MutableStateFlow<List<Rule>>(emptyList())

    private fun file() = File(ctx.filesDir, "rules.json")

    fun init(c: Context) {
        ctx = c.applicationContext
        val f = file()
        rules.value = if (f.exists()) Automation.fromJson(f.readText()) else emptyList()
    }

    private fun save() = Paths.writeAtomic(file(), Automation.toJson(rules.value))

    fun upsert(r: Rule) {
        val withId = if (r.id.isEmpty()) r.copy(id = UUID.randomUUID().toString()) else r
        val cur = rules.value
        rules.value = if (cur.any { it.id == withId.id }) cur.map { if (it.id == withId.id) withId else it } else cur + withId
        save()
    }

    fun delete(id: String) {
        rules.value = rules.value.filter { it.id != id }
        save()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        rules.value = rules.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        save()
    }
}

object NotifyStore {
    private lateinit var ctx: Context
    val config = MutableStateFlow(NotifyConfig())

    private fun file() = File(ctx.filesDir, "notify.json")

    fun init(c: Context) {
        ctx = c.applicationContext
        val f = file()
        config.value = if (f.exists()) NotifyLogic.fromJson(f.readText()) else NotifyConfig()
    }

    fun update(c: NotifyConfig) {
        config.value = c
        Paths.writeAtomic(file(), NotifyLogic.toJson(c))
    }
}
