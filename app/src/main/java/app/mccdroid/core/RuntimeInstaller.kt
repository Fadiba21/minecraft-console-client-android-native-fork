package app.mccdroid.core

import android.content.Context
import android.os.Build
import android.system.Os
import app.mccdroid.BuildConfig
import app.mccdroid.logic.Json
import app.mccdroid.logic.asObj
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

sealed class RtState {
    object Checking : RtState()
    data class Missing(val reason: String) : RtState()
    data class Installing(val progress: Float, val message: String) : RtState()
    data class Ready(val info: Map<String, Any?>) : RtState()
    data class Failed(val message: String) : RtState()
}

/** Mengekstrak runtime MCC (.NET self-contained + OpenSSL) dari assets APK ke penyimpanan aplikasi. */
object RuntimeInstaller {
    val state = MutableStateFlow<RtState>(RtState.Checking)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val ASSET_ZIP = "mcc-bundle.zip"
    private const val ASSET_INFO = "bundle-info.json"

    val isReady: Boolean get() = state.value is RtState.Ready

    fun exe(ctx: Context): File = File(Paths.runtimeDir(ctx), "MinecraftClient")

    fun start(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        scope.launch { ensure(app, force) }
    }

    private fun readInfo(ctx: Context): Map<String, Any?> {
        val f = File(Paths.runtimeDir(ctx), "bundle-info.json")
        return if (f.exists()) Json.parseOrNull(f.readText()).asObj() else emptyMap()
    }

    private fun ensure(ctx: Context, force: Boolean) {
        state.value = RtState.Checking
        try {
            val assets = ctx.assets.list("")?.toSet() ?: emptySet()
            val runtime = Paths.runtimeDir(ctx)
            val exe = exe(ctx)

            if (ASSET_ZIP !in assets) {
                if (exe.exists()) {
                    state.value = RtState.Ready(readInfo(ctx))
                } else {
                    state.value = RtState.Missing(
                        "APK ini dibangun tanpa runtime MCC. Periksa log langkah OpenSSL/Publish/Bundle " +
                            "di GitHub Actions, lalu bangun ulang.",
                    )
                }
                return
            }

            val infoText = try {
                ctx.assets.open(ASSET_INFO).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                ""
            }
            val stamp = "${BuildConfig.VERSION_CODE}:${infoText.hashCode()}"
            val stampFile = File(runtime, ".stamp")
            if (!force && exe.exists() && stampFile.exists() && stampFile.readText() == stamp) {
                state.value = RtState.Ready(readInfo(ctx))
                return
            }

            extract(ctx, runtime)
            stampFile.writeText(stamp)
            state.value = RtState.Ready(readInfo(ctx))
        } catch (e: Throwable) {
            state.value = RtState.Failed("Gagal memasang runtime: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private class CountingStream(inp: InputStream) : FilterInputStream(inp) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    private fun extract(ctx: Context, dir: File) {
        dir.deleteRecursively()
        dir.mkdirs()
        var total = -1L
        try {
            ctx.assets.openFd(ASSET_ZIP).use { total = it.length }
        } catch (_: Exception) {
        }
        val counting = CountingStream(ctx.assets.open(ASSET_ZIP))
        val base = dir.canonicalPath + File.separator
        var lastUpdate = 0L
        ZipInputStream(BufferedInputStream(counting, 1 shl 16)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(dir, entry.name).canonicalFile
                if (out.path.startsWith(base)) {
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zin.copyTo(it, 1 shl 16) }
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 150) {
                    lastUpdate = now
                    val frac = if (total > 0) (counting.count.toFloat() / total).coerceIn(0f, 0.98f) else -1f
                    state.value = RtState.Installing(frac, entry.name)
                }
                entry = zin.nextEntry
            }
        }
        state.value = RtState.Installing(0.99f, "Mengatur izin eksekusi…")
        chmodAll(dir)
        if (!exe(ctx).exists()) throw IllegalStateException("MinecraftClient tidak ditemukan di bundle")
    }

    private fun chmodAll(dir: File) {
        dir.walkTopDown().forEach { f ->
            try {
                Os.chmod(f.path, 493) // 0755
            } catch (_: Exception) {
            }
        }
    }

    fun reinstall(ctx: Context) = start(ctx, force = true)
}

/** Variabel lingkungan untuk menjalankan MCC dan shell di Android. */
object RuntimeEnv {
    fun environment(ctx: Context): Map<String, String> {
        val rt = Paths.runtimeDir(ctx).absolutePath
        val tmp = Paths.tmpDir(ctx).absolutePath
        val m = LinkedHashMap<String, String>()
        m["HOME"] = Paths.homeDir(ctx).absolutePath
        m["TMPDIR"] = tmp
        m["PATH"] = "/system/bin:/system/xbin:$rt"
        m["LD_LIBRARY_PATH"] = rt
        m["DOTNET_ROOT"] = rt
        m["DOTNET_BUNDLE_EXTRACT_BASE_DIR"] = "$tmp/bundle"
        m["DOTNET_SYSTEM_GLOBALIZATION_INVARIANT"] = "1"
        m["DOTNET_EnableDiagnostics"] = "0"
        m["DOTNET_TieredPGO"] = "0"
        m["DOTNET_gcServer"] = "0"
        m["DOTNET_gcConcurrent"] = "0"
        m["DOTNET_CLI_TELEMETRY_OPTOUT"] = "1"
        m["DOTNET_NOLOGO"] = "1"
        m["SSL_CERT_FILE"] = "$rt/cacert.pem"
        m["TERM"] = "dumb"
        m["LANG"] = "en_US.UTF-8"
        val heap = AppPrefs.heapLimitMb
        if (heap > 0) m["DOTNET_GCHeapHardLimit"] = (heap.toLong() * 1024 * 1024).toString(16)
        return m
    }
}

object Diagnostics {
    /** Ringkasan sistem + uji `MinecraftClient --help`. Blokir; panggil dari thread latar. */
    fun run(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("Perangkat : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android   : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val abis = Build.SUPPORTED_ABIS.joinToString()
        sb.appendLine("ABI       : $abis")
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            sb.appendLine("PERINGATAN: runtime MCC hanya dibangun untuk arm64-v8a.")
        }
        sb.appendLine("Runtime   : ${RuntimeInstaller.state.value}")
        val rt = Paths.runtimeDir(ctx)
        for (n in listOf("MinecraftClient", "MinecraftClient.dll", "libcoreclr.so", "libhostfxr.so", "libhostpolicy.so", "libSystem.Native.so", "libssl.so.3", "libcrypto.so.3", "cacert.pem")) {
            val f = File(rt, n)
            sb.appendLine("  %-26s %s".format(n, if (f.exists()) "ada (${f.length() / 1024} KB${if (f.canExecute()) ", x" else ""})" else "TIDAK ADA"))
        }
        val exe = RuntimeInstaller.exe(ctx)
        if (!exe.exists()) return sb.toString()
        sb.appendLine()
        sb.appendLine("$ MinecraftClient --help")
        try {
            val pb = ProcessBuilder(exe.absolutePath, "--help").directory(Paths.homeDir(ctx)).redirectErrorStream(true)
            pb.environment().putAll(RuntimeEnv.environment(ctx))
            val p = pb.start()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().useLines { seq ->
                        var n = 0
                        for (line in seq) {
                            if (n++ < 40) sb.appendLine(app.mccdroid.logic.McText.strip(line))
                        }
                    }
                } catch (_: Exception) {
                }
            }
            reader.isDaemon = true
            reader.start()
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                sb.appendLine("(timeout 60 dtk)")
            } else {
                reader.join(2000)
                sb.appendLine("(kode keluar ${p.exitValue()})")
            }
        } catch (e: Exception) {
            sb.appendLine("Gagal menjalankan: ${e.javaClass.simpleName}: ${e.message}")
        }
        return sb.toString()
    }

    /** Jalankan MCC sebentar agar menulis MinecraftClient.ini default ke folder profil. */
    fun generateDefaultConfig(ctx: Context, profileId: String): Boolean {
        val cfg = Paths.configFile(ctx, profileId)
        if (cfg.exists()) return true
        val exe = RuntimeInstaller.exe(ctx)
        if (!exe.exists()) return false
        return try {
            val pb = ProcessBuilder(exe.absolutePath, "BasicIO").directory(Paths.profileDir(ctx, profileId)).redirectErrorStream(true)
            pb.environment().putAll(RuntimeEnv.environment(ctx))
            val p = pb.start()
            val drain = Thread {
                try {
                    val buf = ByteArray(4096)
                    while (p.inputStream.read(buf) >= 0) { /* buang keluaran */ }
                } catch (_: Exception) {
                }
            }
            drain.isDaemon = true
            drain.start()
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline && !cfg.exists() && p.isAlive) Thread.sleep(300)
            Thread.sleep(500)
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
            cfg.exists()
        } catch (_: Exception) {
            false
        }
    }
}
