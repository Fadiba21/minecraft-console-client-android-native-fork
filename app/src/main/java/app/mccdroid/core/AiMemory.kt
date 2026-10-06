package app.mccdroid.core

import android.content.Context
import app.mccdroid.logic.Json
import app.mccdroid.logic.asArr
import app.mccdroid.logic.asObj
import app.mccdroid.logic.long
import app.mccdroid.logic.str
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class MemoryNote(val id: String, val text: String, val ts: Long)

/**
 * Catatan permanen AI: fakta tahan lama tentang pengguna dan setelan mereka (preferensi, tujuan server,
 * konvensi). Bertahan walau percakapan dihapus dan selalu masuk ke system prompt, jadi AI tidak perlu
 * ditanya ulang. Rahasia (password, token, API key) ditolak.
 */
object AiMemory {
    const val MAX_NOTES = 60
    const val MAX_NOTE_CHARS = 400
    private const val FILE = "ai_memory.json"

    private val lock = Any()
    private var file: File? = null

    val notes = MutableStateFlow<List<MemoryNote>>(emptyList())

    private val SECRET = Regex(
        "(?i)(password|passwd|kata\\s*sandi|token|api[\\s_-]?key|secret)\\s*[:=]|AIza[0-9A-Za-z_-]{20,}|sk-[0-9A-Za-z]{20,}",
    )

    fun init(c: Context) {
        val f = File(c.filesDir, FILE)
        synchronized(lock) {
            file = f
            notes.value = if (f.exists()) {
                Json.parseOrNull(f.readText()).asArr().mapNotNull { raw ->
                    val o = raw.asObj()
                    val text = o.str("text")
                    if (text.isBlank()) null else MemoryNote(o.str("id").ifBlank { newId(emptySet()) }, text, o.long("ts"))
                }
            } else emptyList()
        }
    }

    private fun newId(taken: Set<String>): String {
        while (true) {
            val id = UUID.randomUUID().toString().take(4)
            if (id !in taken) return id
        }
    }

    private fun save() {
        val f = file ?: return
        try {
            Paths.writeAtomic(f, Json.stringify(notes.value.map { mapOf("id" to it.id, "text" to it.text, "ts" to it.ts) }))
        } catch (_: Exception) {
        }
    }

    /** @return catatan yang tersimpan (atau yang sudah ada bila duplikat). Melempar error bila ditolak. */
    fun add(raw: String): MemoryNote {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        require(text.isNotEmpty()) { "Catatan kosong." }
        require(text.length <= MAX_NOTE_CHARS) { "Catatan terlalu panjang (maks $MAX_NOTE_CHARS karakter). Ringkas dulu." }
        require(!SECRET.containsMatchIn(text)) { "Catatan tampak berisi rahasia (password/token/API key). Rahasia tidak boleh disimpan." }
        synchronized(lock) {
            notes.value.firstOrNull { it.text.equals(text, true) }?.let { return it }
            val note = MemoryNote(newId(notes.value.map { it.id }.toSet()), text, System.currentTimeMillis())
            notes.value = (notes.value + note).takeLast(MAX_NOTES)
            save()
            return note
        }
    }

    /** Hapus berdasarkan id, atau potongan teks bila hanya satu catatan yang cocok. */
    fun remove(key: String): MemoryNote? {
        val k = key.trim()
        if (k.isEmpty()) return null
        synchronized(lock) {
            val hit = notes.value.firstOrNull { it.id.equals(k, true) }
                ?: notes.value.filter { it.text.contains(k, true) }.singleOrNull()
                ?: return null
            notes.value = notes.value.filter { it.id != hit.id }
            save()
            return hit
        }
    }

    fun clear() {
        synchronized(lock) {
            notes.value = emptyList()
            save()
        }
    }

    /** Blok untuk system prompt; kosong bila belum ada catatan. */
    fun promptBlock(): String {
        val list = notes.value
        if (list.isEmpty()) return "(belum ada catatan)"
        return list.joinToString("\n") { "- [${it.id}] ${it.text}" }
    }
}

data class JournalEntry(val ts: Long, val tool: String, val summary: String, val result: String)

/**
 * Jurnal perubahan yang dilakukan AI (tulis/hapus). Disimpan ke disk supaya AI tahu apa yang sudah
 * dikerjakannya walau percakapan sudah dihapus, dan pengguna bisa menelusuri perubahan.
 */
object AiJournal {
    private const val FILE = "ai_journal.json"
    private const val MAX = 100

    private val lock = Any()
    private var file: File? = null
    private val entries = ArrayList<JournalEntry>()

    fun init(c: Context) {
        val f = File(c.filesDir, FILE)
        synchronized(lock) {
            file = f
            entries.clear()
            if (f.exists()) {
                for (raw in Json.parseOrNull(f.readText()).asArr()) {
                    val o = raw.asObj()
                    if (o.str("tool").isNotEmpty()) entries += JournalEntry(o.long("ts"), o.str("tool"), o.str("summary"), o.str("result"))
                }
            }
        }
    }

    fun add(tool: String, summary: String, result: String) {
        synchronized(lock) {
            entries += JournalEntry(System.currentTimeMillis(), tool, summary.take(200), result.take(200))
            while (entries.size > MAX) entries.removeAt(0)
            val f = file ?: return
            try {
                Paths.writeAtomic(f, Json.stringify(entries.map {
                    mapOf("ts" to it.ts, "tool" to it.tool, "summary" to it.summary, "result" to it.result)
                }))
            } catch (_: Exception) {
            }
        }
    }

    fun recent(n: Int): List<JournalEntry> = synchronized(lock) { entries.takeLast(n) }

    fun format(list: List<JournalEntry>): String {
        if (list.isEmpty()) return "(belum ada)"
        val fmt = SimpleDateFormat("d MMM HH:mm", Locale("id", "ID"))
        return list.joinToString("\n") { "- ${fmt.format(Date(it.ts))} ${it.tool}: ${it.summary} → ${it.result}" }
    }
}
