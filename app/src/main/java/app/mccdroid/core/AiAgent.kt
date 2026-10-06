package app.mccdroid.core

import android.content.Context
import app.mccdroid.logic.GeminiClient
import app.mccdroid.logic.Json
import app.mccdroid.logic.asArr
import app.mccdroid.logic.asObj
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Agen multi-langkah: model memanggil tool, hasilnya dikirim kembali ke model, dan seterusnya
 * sampai model memberi jawaban akhir. Berjalan di scope aplikasi, jadi tetap lanjut saat layar ditutup.
 * Jawaban teks dialirkan (streaming) ke [AiSession.streamText] selagi model menulis.
 */
object AiAgent {
    private const val MAX_STEPS = 40
    private const val MAX_RESULT_CHARS = 20_000
    private const val MAX_SAME_CALL = 4
    private const val MAX_EMPTY_RETRIES = 2
    private const val NUDGE = "Lanjutkan: berikan jawaban akhir untuk pengguna berdasarkan hasil tool di atas, atau panggil tool berikutnya bila tugas belum selesai."

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null

    fun ask(ctx: Context, text: String, profileId: String?) {
        val t = text.trim()
        if (t.isEmpty() || AiSession.busy) return
        if (GeminiStore.apiKey.isBlank()) {
            AiSession.status = "Atur API key Gemini di Lainnya → Pengaturan terlebih dahulu."
            UiSound.alert()
            return
        }
        AiSession.contextProfileId = profileId
        AiSession.messages += AiMessage(AiKind.USER, t)
        AiSession.prompt = ""
        AiSession.status = null
        AiSession.streamText = ""
        AiSession.busy = true
        UiSound.send()
        UiSound.processing()
        val app = ctx.applicationContext
        val startSize = AiSession.contents.size
        job = scope.launch {
            var ok = true
            try {
                run(app, t, startSize)
            } catch (e: CancellationException) {
                ok = false
                flushPartialStream()
                AiSession.messages += AiMessage(AiKind.ERROR, "Dibatalkan.")
                closeTurn(startSize, "(Dibatalkan oleh pengguna.)")
                throw e
            } catch (e: Exception) {
                ok = false
                flushPartialStream()
                val msg = e.message ?: e.javaClass.simpleName
                AiSession.status = msg
                AiSession.messages += AiMessage(AiKind.ERROR, msg)
                closeTurn(startSize, "(Terhenti karena error: ${msg.take(200)})")
            } finally {
                AiSession.busy = false
                AiSession.activity = null
                AiSession.streamText = ""
                AiSession.persist()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (ok) UiSound.success() else UiSound.alert()
                }
            }
        }
    }

    fun cancel() {
        AiSession.resolve(false)
        job?.cancel()
    }

    /** Teks yang sudah sempat tampil tetap dipertahankan sebagai pesan bila giliran terhenti di tengah. */
    private fun flushPartialStream() {
        val partial = AiSession.streamText.trim()
        AiSession.streamText = ""
        if (partial.isNotEmpty()) AiSession.messages += AiMessage(AiKind.AI, "$partial …")
    }

    private fun commitText(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) AiSession.messages += AiMessage(AiKind.AI, t)
        AiSession.streamText = ""
    }

    private suspend fun run(ctx: Context, userText: String, startSize: Int) {
        val contents = AiSession.contents
        contents += mapOf("role" to "user", "parts" to listOf(mapOf("text" to userText)))
        val seen = HashMap<String, Int>()
        var step = 0
        var emptyRetries = 0
        while (true) {
            step++
            if (step > MAX_STEPS) {
                val note = "Saya berhenti setelah $MAX_STEPS langkah. Lanjutkan dengan meminta saya meneruskan bila masih perlu."
                commitText(note)
                contents += mapOf("role" to "model", "parts" to listOf(mapOf("text" to note)))
                return
            }
            AiSession.activity = if (step == 1) "Gemini sedang berpikir…" else "Gemini menganalisa hasil…"
            AiSession.trimHistory()
            AiSession.streamText = ""
            val reply = GeminiClient.generateStream(
                apiKey = GeminiStore.apiKey,
                model = GeminiStore.selectedModel,
                contents = contents.toList(),
                system = AiContext.systemPrompt(ctx, AiSession.contextProfileId),
                tools = MccAiTools.declarations,
                onText = { AiSession.streamText += it },
            )

            if (reply.calls.isEmpty()) {
                if (reply.text.isBlank()) {
                    val retryable = reply.blocked.isEmpty() && (reply.finishReason.isEmpty() || reply.finishReason == "STOP")
                    if (retryable && emptyRetries < MAX_EMPTY_RETRIES) {
                        // Gemini kadang membalas kosong, terutama tepat setelah hasil tool. Coba lagi dengan dorongan.
                        emptyRetries++
                        if (step > 1) nudge(contents)
                        continue
                    }
                    val why = when {
                        reply.blocked.isNotEmpty() -> "Permintaan diblokir oleh Gemini (${reply.blocked})."
                        reply.finishReason.isNotEmpty() && reply.finishReason != "STOP" -> "Gemini tidak memberi jawaban (alasan: ${reply.finishReason})."
                        else -> "Gemini tidak memberi jawaban. Coba ulangi atau ganti model."
                    }
                    error(why)
                }
                val suffix = if (reply.finishReason == "MAX_TOKENS") "\n\n(jawaban terpotong karena batas panjang; minta saya melanjutkan)" else ""
                commitText(reply.text.trim() + suffix)
                contents += mapOf("role" to "model", "parts" to reply.parts.ifEmpty { listOf(mapOf("text" to reply.text)) })
                return
            }

            commitText(reply.text)
            val responses = ArrayList<Map<String, Any?>>()
            for (call in reply.calls) {
                val sig = call.name + Json.stringify(call.args)
                val n = (seen[sig] ?: 0) + 1
                seen[sig] = n
                val (ok, text) = if (n > MAX_SAME_CALL) {
                    false to "Panggilan identik sudah diulang $MAX_SAME_CALL kali. Ubah pendekatan atau jelaskan kendalanya ke pengguna."
                } else {
                    executeCall(ctx, call)
                }
                val body = if (ok) mapOf("result" to text) else mapOf("error" to text)
                responses += mapOf("functionResponse" to mapOf("name" to call.name, "response" to body))
            }
            contents += mapOf("role" to "model", "parts" to reply.parts)
            contents += mapOf("role" to "user", "parts" to responses)
            AiSession.persist()
        }
    }

    /** Tambahkan teks dorongan ke giliran user yang berisi functionResponse agar model menjawab. */
    private fun nudge(contents: MutableList<Map<String, Any?>>) {
        val last = contents.lastOrNull() ?: return
        if (last["role"] != "user") return
        val parts = last["parts"].asArr()
        if (parts.any { it.asObj().containsKey("text") }) return
        val withNudge = parts.toMutableList()
        withNudge.add(mapOf("text" to NUDGE))
        contents[contents.lastIndex] = mapOf("role" to "user", "parts" to withNudge)
    }

    private fun firstLine(s: String) = s.lineSequence().firstOrNull().orEmpty().take(180)

    private suspend fun executeCall(ctx: Context, call: GeminiClient.FunctionCall): Pair<Boolean, String> {
        val name = call.name
        if (!MccAiTools.exists(name)) return false to "Tool tidak dikenal: $name. Gunakan hanya tool yang tersedia."
        val preview = MccAiTools.preview(name, call.args)
        val risk = MccAiTools.riskOf(name)

        if (MccAiTools.needsApproval(name, AiSession.approvalMode)) {
            AiSession.activity = "Menunggu persetujuan Anda…"
            val approved = AiSession.awaitApproval(name, preview, risk == MccAiTools.Risk.DANGER)
            if (!approved) {
                AiSession.messages += AiMessage(AiKind.TOOL, "✕ Ditolak — ${firstLine(preview)}")
                return false to "Pengguna menolak tindakan ini. Jangan ulangi; tanyakan alternatif atau lanjutkan tanpa tindakan itu."
            }
        }

        AiSession.activity = firstLine(preview)
        val outcome = withContext(Dispatchers.Default) { runCatching { MccAiTools.execute(ctx, name, call.args) } }
        val icon = when (risk) {
            MccAiTools.Risk.READ -> "›"
            MccAiTools.Risk.WRITE -> "✎"
            MccAiTools.Risk.DANGER -> "⚠"
        }
        val failure = outcome.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (failure != null) {
            val msg = failure.message ?: failure.javaClass.simpleName
            AiSession.messages += AiMessage(AiKind.TOOL, "✕ ${firstLine(preview)} — $msg".take(300))
            return false to msg
        }
        val result = outcome.getOrDefault("")
        val shown = if (risk == MccAiTools.Risk.READ) firstLine(preview) else "${firstLine(preview)} — ${firstLine(result)}"
        AiSession.messages += AiMessage(AiKind.TOOL, "$icon $shown".take(300))
        if (risk != MccAiTools.Risk.READ) AiJournal.add(name, firstLine(preview), firstLine(result))
        val clipped = if (result.length > MAX_RESULT_CHARS) result.take(MAX_RESULT_CHARS) + "\n…[dipotong ${result.length - MAX_RESULT_CHARS} karakter]" else result
        return true to clipped.ifEmpty { "Berhasil." }
    }

    /** Jaga struktur riwayat tetap bergantian user/model setelah error atau pembatalan. */
    private fun closeTurn(startSize: Int, note: String) {
        val c = AiSession.contents
        if (c.size <= startSize + 1) {
            while (c.size > startSize) c.removeAt(c.lastIndex)
        } else if (c.last()["role"] == "user") {
            c += mapOf("role" to "model", "parts" to listOf(mapOf("text" to note)))
        }
    }
}
