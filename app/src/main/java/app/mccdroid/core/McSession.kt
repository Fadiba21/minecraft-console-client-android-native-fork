package app.mccdroid.core

import android.content.Context
import app.mccdroid.logic.ConfigDoc
import app.mccdroid.logic.LineKind
import app.mccdroid.logic.LogBuffer
import app.mccdroid.logic.Toml
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlin.concurrent.thread

enum class SState(val label: String) {
    STOPPED("Berhenti"),
    STARTING("Memulai"),
    WAITING_LOGIN("Menunggu login"),
    ONLINE("Online"),
    DISCONNECTED("Terputus"),
    RECONNECTING("Menyambung ulang"),
    CRASHED("Crash"),
}

data class DeviceCode(val url: String, val code: String)

/** Satu proses MCC untuk satu profil. I/O lewat pipe dengan argumen "BasicIO". */
class McSession(private val ctx: Context, val profileId: String) {
    val state = MutableStateFlow(SState.STOPPED)
    val log = LogBuffer { AppPrefs.maxLogLines }
    val deviceCode = MutableStateFlow<DeviceCode?>(null)
    val startedAt = MutableStateFlow(0L)
    val lastEvent = MutableStateFlow("")

    @Volatile private var process: Process? = null
    @Volatile private var writer: Writer? = null
    @Volatile var stopRequested = false
        internal set
    @Volatile var accountName: String? = null
        private set

    private val writeExec = Executors.newSingleThreadExecutor { r -> Thread(r, "mcc-write-$profileId").apply { isDaemon = true } }
    private var logWriter: FileWriter? = null
    private var logCount = 0

    val isAlive: Boolean get() = process?.isAlive == true

    internal fun setState(s: SState) {
        if (state.value != s) {
            state.value = s
            SessionManager.refreshSummary()
        }
    }

    /** @return pesan error, atau null bila proses berhasil dimulai. */
    fun start(): String? {
        if (isAlive) return null
        if (!RuntimeInstaller.isReady) return "Runtime MCC belum siap. Lihat tab Pengaturan > Diagnostik."
        val exe = RuntimeInstaller.exe(ctx)
        if (!exe.exists()) return "File MinecraftClient tidak ada."
        if (!exe.canExecute()) exe.setExecutable(true, false)
        val prof = ProfileStore.get(profileId) ?: return "Profil tidak ditemukan."
        val dir = Paths.profileDir(ctx, profileId)

        val cmd = ArrayList<String>()
        cmd += exe.absolutePath
        cmd += splitArgs(prof.extraArgs)
        cmd += "BasicIO" // harus argumen terakhir
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        pb.environment().putAll(RuntimeEnv.environment(ctx))

        stopRequested = false
        deviceCode.value = null
        accountName = loadAccountName()
        setState(SState.STARTING)
        startedAt.value = System.currentTimeMillis()
        log.append("— Memulai ${prof.name} —", LineKind.SYS)
        val p = try {
            pb.start()
        } catch (e: IOException) {
            setState(SState.CRASHED)
            val msg = "Gagal menjalankan MCC: ${e.message}"
            log.append(msg, LineKind.ERR)
            return msg
        }
        process = p
        writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
        SessionManager.onProcessStarted(this)

        thread(name = "mcc-out-$profileId", isDaemon = true) { readLoop(p) }
        thread(name = "mcc-wait-$profileId", isDaemon = true) {
            val code = try {
                p.waitFor()
            } catch (_: InterruptedException) {
                -1
            }
            onExit(code, p)
        }
        return null
    }

    private fun readLoop(p: Process) {
        try {
            val input = p.inputStream
            val bytes = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                for (i in 0 until n) {
                    val b = chunk[i].toInt() and 0xFF
                    if (b == '\n'.code) {
                        publishDecoded(bytes.toByteArray())
                        bytes.reset()
                    } else if (b != '\r'.code) bytes.write(b)
                }
            }
            if (bytes.size() > 0) publishDecoded(bytes.toByteArray())
            input.close()
        } catch (_: IOException) {
        }
    }

    private fun publishDecoded(raw: ByteArray) {
        // MCC BasicIO umumnya UTF-8. Jangan pernah menebak UTF-16 dari byte chat:
        // pasangan byte ASCII jika dibaca UTF-16 menghasilkan huruf/angka acak.
        val text = if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) {
            raw.toString(Charsets.UTF_16LE).removePrefix("\uFEFF")
        } else if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
            raw.toString(Charsets.UTF_16BE).removePrefix("\uFEFF")
        } else {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(raw)).toString()
        }
        val l = log.append(text)
        if (AppPrefs.saveConsoleLog) appendToFile(l.clean)
        SessionManager.onLine(this, l.clean)
    }

    private fun onExit(code: Int, expected: Process) {
        // Proses lama tidak boleh menimpa state proses baru setelah restart cepat.
        if (process !== expected) return
        process = null
        try {
            writer?.close()
        } catch (_: IOException) {
        }
        writer = null
        synchronized(this) {
            try {
                logWriter?.close()
            } catch (_: IOException) {
            }
            logWriter = null
        }
        log.append("— Proses berhenti (kode $code) —", LineKind.SYS)
        val crashed = !stopRequested && code != 0
        setState(if (crashed) SState.CRASHED else SState.STOPPED)
        SessionManager.onExit(this, code)
    }

    /** Kirim satu atau beberapa baris ke stdin MCC. */
    fun send(text: String, echo: Boolean = true) {
        val lines = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (Regex("^/?(quit|exit)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line.trim())) stopRequested = true
            if (echo) log.append("> $line", LineKind.IN)
            writeExec.execute {
                try {
                    val w = writer
                    if (w == null || !isAlive) {
                        log.append("MCC tidak berjalan.", LineKind.ERR)
                    } else {
                        w.write(line)
                        w.write("\n")
                        w.flush()
                    }
                } catch (e: IOException) {
                    log.append("Gagal mengirim: ${e.message}", LineKind.ERR)
                }
            }
        }
    }

    /** Hentikan dengan sopan (/quit), paksa bila tidak berhenti. */
    fun stop() {
        val p = process ?: return
        stopRequested = true
        send("/quit", echo = false)
        thread(name = "mcc-kill-$profileId", isDaemon = true) {
            try {
                if (!p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroy()
                    if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
                }
            } catch (_: InterruptedException) {
            }
        }
    }

    fun kill() {
        stopRequested = true
        process?.destroyForcibly()
    }

    private fun loadAccountName(): String? = try {
        val f = Paths.configFile(ctx, profileId)
        if (!f.exists()) {
            null
        } else {
            val doc = ConfigDoc.parse(f.readText())
            val e = doc.find("Main.General", "Account")
            val login = e?.let { doc.tableGet(it, "Login") }?.let { Toml.unquote(it) }
            login?.takeIf { it.isNotBlank() && !it.contains('@') && it != "-" }
        }
    } catch (_: Exception) {
        null
    }

    private fun appendToFile(text: String) {
        synchronized(this) {
            try {
                if (logWriter == null) {
                    val dir = File(Paths.profileDir(ctx, profileId), "logs").apply { mkdirs() }
                    val f = File(dir, "console.log")
                    if (f.length() > 5L * 1024 * 1024) f.renameTo(File(dir, "console.1.log"))
                    logWriter = FileWriter(f, true)
                }
                logWriter?.apply {
                    val t = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                    write("[$t] $text\n")
                    if (++logCount % 20 == 0) flush()
                }
            } catch (_: IOException) {
            }
        }
    }

    companion object {
        /** Pecah argumen tambahan dengan dukungan tanda kutip sederhana. */
        fun splitArgs(s: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var quote = 0.toChar()
            var has = false
            for (c in s) {
                when {
                    quote != 0.toChar() -> if (c == quote) quote = 0.toChar() else sb.append(c)
                    c == '"' || c == '\'' -> { quote = c; has = true }
                    c.isWhitespace() -> if (has || sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0); has = false }
                    else -> sb.append(c)
                }
            }
            if (has || sb.isNotEmpty()) out.add(sb.toString())
            return out
        }
    }
}
