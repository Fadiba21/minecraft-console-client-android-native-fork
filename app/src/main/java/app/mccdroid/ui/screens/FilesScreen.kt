@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.mccdroid.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mccdroid.core.Paths
import app.mccdroid.ui.Nav
import app.mccdroid.ui.components.ConfirmDialog
import app.mccdroid.ui.components.EmptyState
import app.mccdroid.ui.components.ScreenHeader
import app.mccdroid.ui.components.TextInputDialog
import app.mccdroid.ui.components.formatSize
import app.mccdroid.ui.components.toast
import java.io.File

private val TEXT_EXT = setOf("ini", "cs", "txt", "log", "toml", "json", "md", "sh", "yml", "yaml", "cfg", "conf", "properties", "bak")

@Composable
fun FilesScreen(nav: Nav) {
    val ctx = LocalContext.current
    val root = remember { Paths.dataRoot(ctx) }
    var dir by remember { mutableStateOf(root) }
    var rev by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    var editing by remember { mutableStateOf<File?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var newFile by remember { mutableStateOf(false) }
    var newDir by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<File?>(null) }

    val entries = remember(dir, rev) {
        (dir.listFiles()?.toList() ?: emptyList()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        var n = 0
        for (u in uris) {
            try {
                val name = ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: u.lastPathSegment?.substringAfterLast('/') ?: "impor"
                val out = File(dir, name.replace('/', '_'))
                ctx.contentResolver.openInputStream(u)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                n++
            } catch (e: Exception) {
                toast(ctx, "Gagal impor: ${e.message}")
            }
        }
        if (n > 0) toast(ctx, "$n file diimpor")
        rev++
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = exporting
        exporting = null
        if (uri != null && f != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                toast(ctx, "Diekspor")
            } catch (e: Exception) {
                toast(ctx, "Gagal ekspor: ${e.message}")
            }
        }
    }

    fun up() {
        if (dir.path != root.path) dir = dir.parentFile ?: root else nav.sub = null
    }
    androidx.activity.compose.BackHandler(enabled = dir.path != root.path) { up() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("File & folder", "/" + dir.relativeTo(root).path, actions = {
            IconButton(onClick = { up() }) { Icon(Icons.Rounded.ArrowBack, "Kembali") }
            IconButton(onClick = { newFile = true }) { Icon(Icons.Rounded.NoteAdd, "File baru") }
            IconButton(onClick = { newDir = true }) { Icon(Icons.Rounded.CreateNewFolder, "Folder baru") }
            IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.UploadFile, "Impor") }
            IconButton(onClick = {
                val auth = ctx.packageName + ".documents"
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(DocumentsContract.buildRootUri(auth, "mcc"), "vnd.android.document/root")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (e: Exception) {
                    try {
                        ctx.startActivity(
                            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(auth, "root"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: Exception) {
                        toast(ctx, "Aplikasi File tidak tersedia. Buka Files → menu samping → MCC Droid.")
                    }
                }
            }) { Icon(Icons.Rounded.FolderOpen, "Buka di aplikasi File") }
        })
        if (entries.isEmpty()) EmptyState(Icons.Rounded.Folder, "Folder kosong", "Impor file atau buat file baru.")
        LazyColumn(contentPadding = PaddingValues(bottom = 100.dp)) {
            items(entries, key = { it.path }) { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (f.isDirectory) dir = f
                            else if (f.extension.lowercase() in TEXT_EXT && f.length() < 1_000_000) editing = f
                            else toast(ctx, "Bukan file teks. Gunakan ⋮ → Ekspor.")
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (f.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description, null,
                        Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(f.name, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(
                            if (f.isDirectory) "${f.listFiles()?.size ?: 0} item" else formatSize(f.length()),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuFor = f }) { Icon(Icons.Rounded.MoreVert, "Menu") }
                        DropdownMenu(expanded = menuFor == f, onDismissRequest = { menuFor = null }) {
                            if (!f.isDirectory) DropdownMenuItem(text = { Text("Ekspor") }, onClick = { menuFor = null; exporting = f; exporter.launch(f.name) })
                            DropdownMenuItem(text = { Text("Ganti nama") }, onClick = { menuFor = null; renaming = f })
                            DropdownMenuItem(text = { Text("Hapus") }, onClick = { menuFor = null; deleting = f })
                        }
                    }
                }
            }
        }
    }

    editing?.let { f -> TextEditorDialog(f, onDismiss = { editing = null; rev++ }) }
    renaming?.let { f ->
        TextInputDialog("Ganti nama", "Nama baru", f.name, onDismiss = { renaming = null }) { n ->
            val t = File(f.parentFile, n.trim().replace('/', '_'))
            if (n.isNotBlank() && !t.exists() && f.renameTo(t)) rev++ else toast(ctx, "Gagal mengganti nama")
        }
    }
    deleting?.let { f ->
        ConfirmDialog("Hapus ${f.name}?", if (f.isDirectory) "Seluruh isi folder ikut terhapus." else "File tidak bisa dikembalikan.", "Hapus", { deleting = null }) {
            if (f.deleteRecursively()) rev++ else toast(ctx, "Gagal menghapus")
        }
    }
    if (newFile) TextInputDialog("File baru", "Nama file", "", "Buat", { newFile = false }) { n ->
        val t = File(dir, n.trim().replace('/', '_'))
        if (n.isNotBlank() && !t.exists()) { try { t.writeText(""); rev++; editing = t } catch (e: Exception) { toast(ctx, e.message ?: "Gagal") } }
    }
    if (newDir) TextInputDialog("Folder baru", "Nama folder", "", "Buat", { newDir = false }) { n ->
        if (n.isNotBlank() && File(dir, n.trim().replace('/', '_')).mkdirs()) rev++
    }
}

@Composable
private fun TextEditorDialog(f: File, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(try { f.readText() } catch (e: Exception) { "" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.name) },
        text = {
            OutlinedTextField(
                text, { text = it }, modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                try { Paths.writeAtomic(f, text); toast(ctx, "Tersimpan"); onDismiss() } catch (e: Exception) { toast(ctx, "Gagal: ${e.message}") }
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}
