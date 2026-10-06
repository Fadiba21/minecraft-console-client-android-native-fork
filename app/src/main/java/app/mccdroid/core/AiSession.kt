package app.mccdroid.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.mccdroid.logic.Json
import app.mccdroid.logic.asArr
import app.mccdroid.logic.asObj
import app.mccdroid.logic.str
import kotlinx.coroutines.CompletableDeferred
import java.io.File

enum class AiKind { USER, AI, TOOL, ERROR }

/** Pesan yang tampil di layar. [kind] TOOL = jejak tool yang dijalankan agen. */
data class AiMessage(val kind: AiKind, val text: String) {
    val fromAi: Boolean get() = kind != AiKind.USER
}

/** Permintaan persetujuan untuk satu tool; [decision] diselesaikan oleh tombol di UI. */
class PendingApproval(
    val toolName: String,
    val preview: String,
    val danger: Boolean,
    val decision: CompletableDeferred<Boolean> = CompletableDeferred(),
)

/**
 * State chat AI. Bertahan saat layar ditutup dan disimpan ke disk, jadi AI tetap paham
 * percakapan sebelumnya setelah aplikasi dibuka ulang.
 */
object AiSession {
    private const val FILE = "ai_chat.json"
    private const val PREFS = "ai_prefs"
    private const val MAX_UI = 200

    val messages = mutableStateListOf<AiMessage>()

    /** Riwayat format API Gemini (giliran user/model termasuk functionCall & functionResponse). */
    val contents = ArrayList<Map<String, Any?>>()

    var prompt by mutableStateOf("")
    var busy by mutableStateOf(false)
    var activity by mutableStateOf<String?>(null)
    var pending by mutableStateOf<PendingApproval?>(null)
    var status by mutableStateOf<String?>(null)

    /** Jawaban AI yang sedang mengalir (streaming); kosong bila tidak ada. */
    var streamText by mutableStateOf("")

    /** 0 = tanya semua penulisan, 1 = tanya hanya hapus/shell, 2 = otomatis penuh. */
    var approvalMode by mutableStateOf(0)
        private set

    /** Profil yang sedang dipilih pengguna; dipakai sebagai default oleh tool. */
    @Volatile var contextProfileId: String? = null

    /** Nama layar yang sedang dibuka pengguna (mis. "Konfig"), diisi UI. Dipakai sebagai konteks. */
    @Volatile var screen: String? = null

    private var appCtx: Context? = null

    fun init(c: Context) {
        appCtx = c.applicationContext
        AiMemory.init(c)
        AiJournal.init(c)
        approvalMode = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("approval", 0).coerceIn(0, 2)
        val f = File(c.filesDir, FILE)
        if (!f.exists()) return
        val root = Json.parseOrNull(f.readText()).asObj()
        messages.clear()
        for (m in root["messages"].asArr()) {
            val o = m.asObj()
            val kind = AiKind.values().firstOrNull { it.name == o.str("kind") } ?: continue
            messages += AiMessage(kind, o.str("text"))
        }
        contents.clear()
        for (m in root["contents"].asArr()) {
            val o = m.asObj()
            if (o.isNotEmpty()) contents += o
        }
        sanitize()
    }

    fun chooseApprovalMode(v: Int) {
        approvalMode = v.coerceIn(0, 2)
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putInt("approval", approvalMode)?.apply()
    }

    fun persist() {
        val c = appCtx ?: return
        try {
            val data = linkedMapOf<String, Any?>(
                "messages" to messages.toList().takeLast(MAX_UI).map { mapOf("kind" to it.kind.name, "text" to it.text) },
                "contents" to ArrayList(contents),
            )
            Paths.writeAtomic(File(c.filesDir, FILE), Json.stringify(data))
        } catch (_: Exception) {
        }
    }

    fun clear() {
        pending?.decision?.complete(false)
        messages.clear()
        contents.clear()
        prompt = ""
        busy = false
        activity = null
        pending = null
        status = null
        streamText = ""
        persist()
    }

    fun resolve(ok: Boolean) {
        pending?.decision?.complete(ok)
    }

    /** Tunggu keputusan pengguna. Mengembalikan false bila ditolak atau dibatalkan. */
    suspend fun awaitApproval(tool: String, preview: String, danger: Boolean): Boolean {
        val p = PendingApproval(tool, preview, danger)
        pending = p
        try {
            return p.decision.await()
        } finally {
            if (pending === p) pending = null
        }
    }

    private fun isUserText(m: Map<String, Any?>): Boolean =
        m["role"] == "user" && m["parts"].asArr().any { it.asObj().containsKey("text") }

    /** Pastikan riwayat dimulai dari giliran teks user dan tidak berakhir pada functionCall menggantung. */
    private fun sanitize() {
        while (contents.isNotEmpty() && !isUserText(contents[0])) contents.removeAt(0)
        while (contents.isNotEmpty()) {
            val last = contents.last()
            val dangling = last["role"] == "model" && last["parts"].asArr().any { it.asObj().containsKey("functionCall") }
            if (dangling) contents.removeAt(contents.lastIndex) else break
        }
    }

    /** Buang giliran paling lama bila riwayat terlalu besar, selalu berhenti di batas giliran user. */
    fun trimHistory(maxChars: Int = 300_000) {
        var total = contents.sumOf { Json.stringify(it).length }
        while (total > maxChars) {
            val next = (1 until contents.size).firstOrNull { isUserText(contents[it]) } ?: break
            repeat(next) { total -= Json.stringify(contents.removeAt(0)).length }
        }
    }
}
