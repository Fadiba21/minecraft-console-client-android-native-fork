package app.mccdroid.core

import android.content.Context
import app.mccdroid.logic.LineKind
import app.mccdroid.logic.LogBuffer
import java.io.File
import java.io.IOException
import java.io.Writer
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Shell sederhana (/system/bin/sh) lewat pipe, tanpa PTY: cocok untuk ls, cat, nano-less editing,
 * menjalankan skrip, dan memeriksa folder MCC. Program layar-penuh interaktif (vim, top) tidak didukung.
 */
object ShellSession {
    val log = LogBuffer { AppPrefs.maxLogLines }

    @Volatile private var process: Process? = null
    @Volatile private var writer: Writer? = null
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "shell-write").apply { isDaemon = true } }

    val isAlive: Boolean get() = process?.isAlive == true

    fun ensure(ctx: Context, cwd: File) {
        if (isAlive) return
        try {
            val pb = ProcessBuilder("/system/bin/sh").directory(cwd).redirectErrorStream(true)
            pb.environment().putAll(RuntimeEnv.environment(ctx))
            pb.environment()["PS1"] = "\$ "
            val p = pb.start()
            process = p
            writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
            log.append("Shell dimulai di ${cwd.absolutePath}", LineKind.SYS)
            thread(name = "shell-out", isDaemon = true) {
                try {
                    p.inputStream.bufferedReader(Charsets.UTF_8).useLines { seq ->
                        for (line in seq) log.append(line.trimEnd('\r'))
                    }
                } catch (_: IOException) {
                }
                log.append("— shell berhenti —", LineKind.SYS)
            }
        } catch (e: IOException) {
            log.append("Gagal memulai shell: ${e.message}", LineKind.ERR)
        }
    }

    fun run(ctx: Context, cwd: File, command: String) {
        ensure(ctx, cwd)
        log.append("$ $command", LineKind.IN)
        exec.execute {
            try {
                writer?.apply {
                    write(command)
                    write("\n")
                    flush()
                }
            } catch (e: IOException) {
                log.append("Gagal mengirim: ${e.message}", LineKind.ERR)
            }
        }
    }

    fun changeDir(ctx: Context, dir: File) {
        ensure(ctx, dir)
        exec.execute {
            try {
                writer?.apply {
                    write("cd '${dir.absolutePath.replace("'", "'\\''")}'\n")
                    flush()
                }
            } catch (_: IOException) {
            }
        }
    }

    fun stop() {
        try {
            writer?.close()
        } catch (_: IOException) {
        }
        process?.destroy()
        process = null
        writer = null
    }
}
