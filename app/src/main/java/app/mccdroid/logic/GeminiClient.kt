package app.mccdroid.logic

import app.mccdroid.core.GeminiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Client REST Gemini API tanpa menyimpan API key di log atau URL. */
object GeminiClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private val RETRYABLE = setOf(429, 500, 502, 503, 504)

    class ApiException(val code: Int, message: String) : Exception(message)

    data class FunctionCall(val name: String, val args: Map<String, Any?>)

    /**
     * @property parts bagian mentah dari model. Harus dikirim balik apa adanya pada giliran berikutnya
     * (model Gemini baru memakai thoughtSignature yang melekat pada part).
     */
    data class Reply(
        val text: String,
        val calls: List<FunctionCall>,
        val parts: List<Any?> = emptyList(),
        val finishReason: String = "",
        val blocked: String = "",
    )

    suspend fun listModels(apiKey: String): List<GeminiModel> {
        val root = call("$BASE/models?pageSize=1000", apiKey, "GET", null).asObj()
        return root["models"].asArr().mapNotNull { raw ->
            val o = raw.asObj()
            val methods = o["supportedGenerationMethods"].asArr().mapNotNull { it as? String }
            val id = o.str("baseModelId").ifBlank { o.str("name").removePrefix("models/") }
            if (id.isBlank() || !methods.contains("generateContent")) null
            else GeminiModel(id, o.str("displayName", id), o.str("description"))
        }
    }

    private fun buildBody(
        contents: List<Map<String, Any?>>,
        system: String,
        tools: List<Map<String, Any?>>,
        maxOutputTokens: Int,
        temperature: Double,
    ): String {
        val body = linkedMapOf<String, Any?>(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to system))),
            "contents" to contents,
            "generationConfig" to mapOf("temperature" to temperature, "maxOutputTokens" to maxOutputTokens),
        )
        if (tools.isNotEmpty()) {
            body["tools"] = listOf(mapOf("functionDeclarations" to tools))
            body["toolConfig"] = mapOf("functionCallingConfig" to mapOf("mode" to "AUTO"))
        }
        return Json.stringify(body)
    }

    private fun modelPath(model: String) = "$BASE/models/${model.removePrefix("models/")}"

    /**
     * @param contents riwayat lengkap: giliran "user" (teks atau functionResponse) dan "model"
     * (teks atau functionCall) dalam format REST Gemini.
     */
    suspend fun generate(
        apiKey: String,
        model: String,
        contents: List<Map<String, Any?>>,
        system: String,
        tools: List<Map<String, Any?>>,
        maxOutputTokens: Int = 8192,
        temperature: Double = 0.4,
    ): Reply {
        val body = buildBody(contents, system, tools, maxOutputTokens, temperature)
        return parseReply(call("${modelPath(model)}:generateContent", apiKey, "POST", body).asObj())
    }

    /**
     * Sama seperti [generate] tetapi memakai SSE: [onText] dipanggil setiap ada potongan teks jawaban
     * baru (bukan thought), sehingga UI bisa menampilkan jawaban yang mengalir.
     * Retry otomatis hanya dilakukan selama belum ada isi yang diterima, agar teks tidak dobel.
     */
    suspend fun generateStream(
        apiKey: String,
        model: String,
        contents: List<Map<String, Any?>>,
        system: String,
        tools: List<Map<String, Any?>>,
        maxOutputTokens: Int = 8192,
        temperature: Double = 0.4,
        onText: (String) -> Unit,
    ): Reply {
        val body = buildBody(contents, system, tools, maxOutputTokens, temperature)
        val url = "${modelPath(model)}:streamGenerateContent?alt=sse"
        var attempt = 0
        while (true) {
            val acc = GeminiStreamAccumulator()
            try {
                withContext(Dispatchers.IO) { streamRequest(url, apiKey, body, acc, onText) }
                return parseReply(acc.toRoot())
            } catch (e: ApiException) {
                if (e.code in RETRYABLE && attempt < 2 && !acc.hasContent) {
                    attempt++
                    delay(1500L * attempt * attempt)
                    continue
                }
                throw e
            } catch (e: IOException) {
                if (acc.hasContent) throw Exception("Koneksi terputus saat menerima jawaban Gemini: ${e.message ?: e.javaClass.simpleName}")
                if (attempt < 1) {
                    attempt++
                    delay(1500L)
                    continue
                }
                throw Exception("Koneksi ke Gemini gagal: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    internal fun parseReply(root: Map<String, Any?>): Reply {
        val candidate = root["candidates"].asArr().firstOrNull().asObj()
        val parts = candidate["content"].asObj()["parts"].asArr()
        val text = parts.mapNotNull { p ->
            val o = p.asObj()
            if (o["thought"] == true) null else o["text"] as? String
        }.joinToString("")
        val calls = parts.mapNotNull { part ->
            val o = part.asObj()["functionCall"].asObj()
            val name = o.str("name")
            if (name.isBlank()) null else FunctionCall(name, o["args"].asObj())
        }
        return Reply(
            text = text,
            calls = calls,
            parts = parts,
            finishReason = candidate.str("finishReason"),
            blocked = root["promptFeedback"].asObj().str("blockReason"),
        )
    }

    private suspend fun call(url: String, apiKey: String, method: String, body: String?): Any? {
        var attempt = 0
        while (true) {
            try {
                return withContext(Dispatchers.IO) { request(url, apiKey, method, body) }
            } catch (e: ApiException) {
                if (e.code in RETRYABLE && attempt < 2) {
                    attempt++
                    delay(1500L * attempt * attempt)
                    continue
                }
                throw e
            } catch (e: IOException) {
                if (attempt < 1) {
                    attempt++
                    delay(1500L)
                    continue
                }
                throw Exception("Koneksi ke Gemini gagal: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun errorMessage(text: String): String =
        runCatching { Json.parse(text).asObj()["error"].asObj().str("message") }
            .getOrDefault("").ifBlank { text.take(300) }

    private fun request(url: String, apiKey: String, method: String, body: String?): Any? {
        require(apiKey.isNotBlank()) { "API key Gemini belum diatur." }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json")
            doInput = true
            if (method == "POST") doOutput = true
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw ApiException(code, "Gemini HTTP $code: ${errorMessage(text)}")
            return Json.parse(text)
        } finally {
            c.disconnect()
        }
    }

    /** Dipanggil di dalam withContext(IO): membaca SSE baris demi baris dan memeriksa pembatalan tiap baris. */
    private fun CoroutineScope.streamRequest(
        url: String,
        apiKey: String,
        body: String,
        acc: GeminiStreamAccumulator,
        onText: (String) -> Unit,
    ) {
        require(apiKey.isNotBlank()) { "API key Gemini belum diatur." }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            doInput = true
            doOutput = true
        }
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val text = c.errorStream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } }.orEmpty()
                throw ApiException(code, "Gemini HTTP $code: ${errorMessage(text)}")
            }
            var sawEvent = false
            val loose = StringBuilder()
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    ensureActive()
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data:")) {
                        val payload = line.substring(5).trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue
                        sawEvent = true
                        handleChunk(Json.parseOrNull(payload), acc, onText)
                    } else if (line.isNotBlank() && !line.startsWith(":") && !line.startsWith("event:") && !line.startsWith("id:")) {
                        loose.append(line).append('\n')
                    }
                }
            }
            // Cadangan: bila server membalas JSON biasa (bukan SSE), tetap proses.
            if (!sawEvent && loose.isNotBlank()) {
                val v = Json.parseOrNull(loose.toString())
                if (v is List<*>) v.forEach { handleChunk(it, acc, onText) } else handleChunk(v, acc, onText)
            }
        } finally {
            c.disconnect()
        }
    }

    private fun handleChunk(raw: Any?, acc: GeminiStreamAccumulator, onText: (String) -> Unit) {
        val o = raw.asObj()
        if (o.isEmpty()) return
        val err = o["error"].asObj()
        if (err.isNotEmpty()) throw ApiException(err.int("code", 500), "Gemini: ${err.str("message", "error tidak dikenal")}")
        val delta = acc.feed(o)
        if (delta.isNotEmpty()) onText(delta)
    }
}
