package app.mccdroid.logic

/**
 * Menggabungkan potongan SSE `streamGenerateContent` menjadi satu respons utuh dalam bentuk yang sama
 * dengan respons `generateContent` biasa, sehingga parser yang sama bisa dipakai.
 *
 * Aturan penting:
 *  - Potongan teks bersebelahan dengan jenis yang sama (biasa / thought) digabung menjadi satu part.
 *  - `thoughtSignature` dipertahankan (yang terakhir menang) karena harus dikirim balik apa adanya.
 *  - Part functionCall disalin utuh; part teks kosong tanpa signature dibuang.
 *
 * Tidak bergantung pada Android atau coroutine supaya mudah diuji.
 */
class GeminiStreamAccumulator {
    private val parts = ArrayList<Map<String, Any?>>()
    private var finishReason = ""
    private var blockReason = ""

    /** True bila sudah ada part yang diterima (retry otomatis tidak aman lagi). */
    val hasContent: Boolean get() = parts.isNotEmpty()

    /** @return teks jawaban (bukan thought) baru dari potongan ini, "" bila tidak ada. */
    fun feed(chunk: Map<String, Any?>): String {
        val blocked = chunk["promptFeedback"].asObj().str("blockReason")
        if (blocked.isNotEmpty()) blockReason = blocked
        val candidate = chunk["candidates"].asArr().firstOrNull().asObj()
        val finish = candidate.str("finishReason")
        if (finish.isNotEmpty()) finishReason = finish

        val delta = StringBuilder()
        for (raw in candidate["content"].asObj()["parts"].asArr()) {
            val part = raw.asObj()
            if (part.isEmpty()) continue
            if (!isTextPart(part)) {
                parts.add(LinkedHashMap(part))
                continue
            }
            val text = part.str("text")
            val thought = part["thought"] == true
            if (!thought) delta.append(text)
            val last = parts.lastOrNull()
            if (last != null && isTextPart(last) && (last["thought"] == true) == thought) {
                val merged = LinkedHashMap(last)
                merged["text"] = last.str("text") + text
                val signature = part["thoughtSignature"]
                if (signature != null) merged["thoughtSignature"] = signature
                parts[parts.lastIndex] = merged
            } else {
                parts.add(LinkedHashMap(part))
            }
        }
        return delta.toString()
    }

    /** Respons gabungan, berbentuk sama dengan respons `generateContent`. */
    fun toRoot(): Map<String, Any?> {
        val kept = parts.filterNot { isTextPart(it) && it.str("text").isEmpty() && it["thoughtSignature"] == null }
        return mapOf(
            "candidates" to listOf(
                mapOf(
                    "content" to mapOf("role" to "model", "parts" to kept),
                    "finishReason" to finishReason,
                ),
            ),
            "promptFeedback" to mapOf("blockReason" to blockReason),
        )
    }

    private fun isTextPart(p: Map<String, Any?>): Boolean = p["text"] is String && !p.containsKey("functionCall")
}
