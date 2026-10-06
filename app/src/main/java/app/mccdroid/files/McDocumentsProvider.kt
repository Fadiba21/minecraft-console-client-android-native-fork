package app.mccdroid.files

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import app.mccdroid.R
import app.mccdroid.core.Paths
import java.io.File
import java.io.FileNotFoundException

/**
 * Menampilkan folder data MCC (profil, skrip, mod) di aplikasi File sistem dan pemilih berkas,
 * sehingga konfigurasi bisa ditaruh/diambil dengan aplikasi apa pun. Cara kerjanya sama dengan Termux.
 */
class McDocumentsProvider : DocumentsProvider() {
    private lateinit var base: File

    override fun onCreate(): Boolean {
        base = Paths.dataRoot(context!!).canonicalFile
        return true
    }

    private fun fileFor(id: String): File {
        val rel = id.removePrefix(ROOT_DOC).trimStart('/')
        val f = (if (rel.isEmpty()) base else File(base, rel)).canonicalFile
        if (f.path != base.path && !f.path.startsWith(base.path + File.separator)) {
            throw FileNotFoundException("Di luar folder data: $id")
        }
        return f
    }

    private fun idFor(f: File): String {
        val c = f.canonicalFile
        if (c.path == base.path) return ROOT_DOC
        return ROOT_DOC + "/" + c.relativeTo(base).path
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
            add(Root.COLUMN_TITLE, "MCC Droid")
            add(Root.COLUMN_SUMMARY, "Profil, skrip & mod MCC")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_AVAILABLE_BYTES, base.usableSpace)
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        include(c, fileFor(documentId))
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val parent = fileFor(parentDocumentId)
        val kids = parent.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        for (k in kids) include(c, k)
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        var target = File(parent, displayName.replace('/', '_'))
        var n = 1
        while (target.exists()) {
            val dot = displayName.lastIndexOf('.')
            val stem = if (dot > 0) displayName.substring(0, dot) else displayName
            val ext = if (dot > 0) displayName.substring(dot) else ""
            target = File(parent, "$stem ($n)$ext")
            n++
        }
        val ok = if (mimeType == Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile()
        if (!ok) throw FileNotFoundException("Gagal membuat ${target.name}")
        return idFor(target)
    }

    override fun deleteDocument(documentId: String) {
        val f = fileFor(documentId)
        if (f.path == base.path) throw FileNotFoundException("Folder akar tidak bisa dihapus")
        if (!f.deleteRecursively()) throw FileNotFoundException("Gagal menghapus ${f.name}")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val f = fileFor(documentId)
        if (f.path == base.path) throw FileNotFoundException("Folder akar tidak bisa diganti nama")
        val t = File(f.parentFile, displayName.replace('/', '_'))
        if (t.exists() || !f.renameTo(t)) throw FileNotFoundException("Gagal mengganti nama")
        return idFor(t)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val p = fileFor(parentDocumentId)
        val c = fileFor(documentId)
        c.path == p.path || c.path.startsWith(p.path + File.separator)
    } catch (_: FileNotFoundException) {
        false
    }

    override fun getDocumentType(documentId: String): String = mimeOf(fileFor(documentId))

    private fun include(c: MatrixCursor, f: File) {
        var flags = 0
        if (f.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            if (f.path != base.path) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        } else {
            flags = flags or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, idFor(f))
            add(Document.COLUMN_DISPLAY_NAME, if (f.path == base.path) "MCC Droid" else f.name)
            add(Document.COLUMN_SIZE, if (f.isDirectory) 0L else f.length())
            add(Document.COLUMN_MIME_TYPE, mimeOf(f))
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeOf(f: File): String {
        if (f.isDirectory) return Document.MIME_TYPE_DIR
        val ext = f.extension.lowercase()
        if (ext in TEXT_EXT) return "text/plain"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        private const val ROOT_ID = "mcc"
        private const val ROOT_DOC = "root"
        private val TEXT_EXT = setOf("ini", "cs", "txt", "log", "toml", "json", "md", "sh", "yml", "yaml", "cfg", "conf")

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DEFAULT_DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
        )

        fun authority(ctx: Context): String = ctx.packageName + ".documents"
    }
}
