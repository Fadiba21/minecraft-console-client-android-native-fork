package app.mccdroid.core

import android.content.Context
import java.io.File

/** Lokasi folder aplikasi. Semua di dalam penyimpanan privat aplikasi (filesDir). */
object Paths {
    /** Akar data yang ditampilkan di aplikasi File sistem (lihat McDocumentsProvider). */
    fun dataRoot(c: Context): File = File(c.filesDir, "mcc").apply { mkdirs() }

    fun profilesDir(c: Context): File = File(dataRoot(c), "profiles").apply { mkdirs() }
    fun profileDir(c: Context, id: String): File = File(profilesDir(c), id).apply { mkdirs() }
    fun configFile(c: Context, id: String): File = File(profileDir(c, id), "MinecraftClient.ini")

    fun sharedDir(c: Context): File = File(dataRoot(c), "shared").apply { mkdirs() }
    fun modsDir(c: Context): File = File(sharedDir(c), "mods").apply { mkdirs() }
    fun scriptsDir(c: Context): File = File(sharedDir(c), "scripts").apply { mkdirs() }

    /** Runtime .NET + MCC hasil ekstrak dari APK. */
    fun runtimeDir(c: Context): File = File(c.filesDir, "runtime")

    fun homeDir(c: Context): File = File(c.filesDir, "home").apply { mkdirs() }
    fun tmpDir(c: Context): File = File(c.cacheDir, "tmp").apply { mkdirs() }

    /** Tulis berkas secara atomik (tulis ke .tmp lalu rename). */
    fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
