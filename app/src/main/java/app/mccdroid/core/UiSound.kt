package app.mccdroid.core

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import app.mccdroid.R
import kotlin.random.Random

/** Sound UI modern berbasis Kenney Interface Sounds (CC0). */
object UiSound {
    private var pool: SoundPool? = null
    private var theme = "neo"
    private var loadedTheme = ""
    private val groups = HashMap<String, IntArray>()

    fun init(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build()
        reload(context)
    }

    fun reload(context: Context) {
        val p = pool ?: return
        theme = AppPrefs.soundTheme
        if (loadedTheme == theme && groups.isNotEmpty()) return
        groups.clear()
        val names = listOf("click", "success", "error", "open", "close", "copy", "send", "toggle_on", "toggle_off", "delete", "processing", "notification", "info")
        for (name in names) {
            val id = context.resources.getIdentifier("sfx_pack_${theme}_$name", "raw", context.packageName)
            if (id != 0) groups[name] = intArrayOf(p.load(context, id, 1))
        }
        loadedTheme = theme
    }

    fun setEnabled(value: Boolean) { AppPrefs.soundEnabled = value }
    fun click() = play("click", 0.82f)
    fun success() = play("success", 0.92f)
    fun alert() = play("error", 0.9f)
    fun open() = play("open", 0.82f)
    fun close() = play("close", 0.78f)
    fun copy() = play("copy", 0.8f)
    fun send() = play("send", 0.84f)
    fun toggle(on: Boolean) = play(if (on) "toggle_on" else "toggle_off", 0.82f)
    fun delete() = play("delete", 0.86f)
    fun processing() = play("processing", 0.68f)
    fun notification() = play("notification", 0.9f)
    fun info() = play("info", 0.78f)

    private fun play(name: String, factor: Float) {
        if (!AppPrefs.soundEnabled) return
        val ids = groups[name] ?: return
        val id = ids[Random.nextInt(ids.size)]
        if (id != 0) {
            val volume = (AppPrefs.soundVolume * factor).coerceIn(0f, 1f)
            pool?.play(id, volume, volume, 1, 0, 1f)
        }
    }
}
