package app.mccdroid.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mccdroid.core.AiAgent
import app.mccdroid.core.AiMemory
import app.mccdroid.core.AiKind
import app.mccdroid.core.AiMessage
import app.mccdroid.core.AiSession
import app.mccdroid.core.GeminiStore
import app.mccdroid.core.MemoryNote
import app.mccdroid.core.ProfileStore
import app.mccdroid.core.UiSound
import app.mccdroid.ui.Nav
import app.mccdroid.ui.Sub
import app.mccdroid.ui.components.GlassCard
import app.mccdroid.ui.components.ScreenHeader
import app.mccdroid.ui.components.copyToClipboard

private val MODE_LABELS = listOf("Tanya semua", "Otomatis", "Otomatis penuh")
private val MODE_HINTS = listOf(
    "Setiap perubahan menunggu persetujuan Anda",
    "Perubahan langsung jalan; hapus & shell tetap ditanya",
    "Semua langsung jalan, termasuk hapus & shell",
)
private val QUICK_PROMPTS = listOf(
    "Cek kenapa bot saya terputus, lalu perbaiki bila bisa",
    "Ringkas apa yang terjadi di console beberapa menit terakhir",
    "Audit config: cari setting yang berisiko atau bisa dioptimalkan",
    "Buat otomasi: respawn otomatis saat mati",
    "Tampilkan status semua profil dan pemakaian resource",
)

private val TAB_NAMES = listOf("Beranda", "Terminal", "Konfig", "Lainnya")

@Composable
fun AiScreen(nav: Nav, onClose: (() -> Unit)? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()
    val profile = nav.pick(ProfileStore.profiles.value)
    val pending = AiSession.pending
    var modeMenu by remember { mutableStateOf(false) }
    var memoryOpen by remember { mutableStateOf(false) }
    val notes by AiMemory.notes.collectAsState()
    val streaming = AiSession.streamText

    // Beri tahu AI layar asal pengguna (konteks: mis. di Konfig berarti "ini" kemungkinan config).
    val screenLabel = when (nav.sub) {
        null, Sub.AI -> TAB_NAMES.getOrNull(nav.tab) ?: "Beranda"
        Sub.FILES -> "File"
        Sub.NOTIFY -> "Notifikasi"
        Sub.MODS -> "Mod & Skrip"
        Sub.SETTINGS -> "Pengaturan"
        Sub.SHELL -> "Shell"
        Sub.AUTOMATION -> "Otomasi"
    }
    SideEffect { AiSession.screen = screenLabel }

    LaunchedEffect(AiSession.messages.size, AiSession.busy, pending, streaming.length / 24) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            if (streaming.isNotEmpty()) listState.scrollToItem(total - 1) else listState.animateScrollToItem(total - 1)
        }
    }

    fun ask() {
        AiAgent.ask(ctx, AiSession.prompt, profile?.id)
    }

    val close = onClose ?: { nav.sub = null }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                "Asisten AI",
                "${GeminiStore.selectedModel} · ${profile?.name ?: "tanpa profil"}",
                actions = {
                    IconButton(
                        onClick = { UiSound.click(); AiAgent.cancel(); AiSession.clear() },
                        enabled = AiSession.messages.isNotEmpty() || AiSession.busy,
                    ) { Icon(Icons.Rounded.DeleteSweep, "Mulai percakapan baru") }
                    IconButton(onClick = { UiSound.close(); close() }) {
                        Icon(if (onClose == null) Icons.Rounded.ArrowBack else Icons.Rounded.Close, "Tutup")
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    AssistChip(onClick = { modeMenu = true }, label = { Text("Izin: ${MODE_LABELS[AiSession.approvalMode]}") })
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        MODE_LABELS.forEachIndexed { idx, label ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(label, fontWeight = if (idx == AiSession.approvalMode) FontWeight.Bold else FontWeight.Normal)
                                        Text(MODE_HINTS[idx], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { AiSession.chooseApprovalMode(idx); modeMenu = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                AssistChip(onClick = { memoryOpen = true }, label = { Text("Memori (${notes.size})") })
            }
            if (memoryOpen) MemoryDialog(notes) { memoryOpen = false }
            if (AiSession.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (AiSession.messages.isEmpty()) {
                    item {
                        GlassCard(accent = MaterialTheme.colorScheme.primary) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                                Text("Saya bisa membaca console (dan menunggu balasan server), config, file, serta semua pengaturan, lalu mengedit, membuat, menyalin, atau menghapusnya (hapus masuk folder .ai_trash dan bisa dipulihkan). Saya juga mengelola profil, sesi MCC, otomasi, dan notifikasi, serta mengingat preferensi Anda lintas percakapan.")
                            }
                        }
                    }
                    items(QUICK_PROMPTS) { q ->
                        AssistChip(
                            onClick = { AiSession.prompt = q; ask() },
                            label = { Text(q) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                items(AiSession.messages) { message ->
                    when (message.kind) {
                        AiKind.TOOL -> ToolLine(message)
                        AiKind.ERROR -> ErrorLine(message)
                        else -> ChatBubble(message)
                    }
                }
                if (streaming.isNotBlank()) item { ChatBubble(AiMessage(AiKind.AI, streaming), copyable = false) }
                if (AiSession.busy && pending == null && streaming.isBlank()) item { ThinkingRow() }
                if (pending != null) {
                    item { ApprovalCard(pending.preview, pending.danger) }
                }
            }
            AiSession.status?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = AiSession.prompt,
                    onValueChange = { AiSession.prompt = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (AiSession.busy) "AI sedang bekerja…" else "Tanya atau minta ubah apa saja…") },
                    enabled = !AiSession.busy,
                    shape = RoundedCornerShape(18.dp),
                    singleLine = false,
                    maxLines = 4,
                )
                if (AiSession.busy) {
                    IconButton(onClick = { UiSound.click(); AiAgent.cancel() }) { Icon(Icons.Rounded.Stop, "Hentikan") }
                } else {
                    IconButton(onClick = ::ask, enabled = AiSession.prompt.isNotBlank()) { Icon(Icons.Rounded.Send, "Kirim") }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(preview: String, danger: Boolean) {
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    GlassCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (danger) Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tint = accent)
            Text(
                if (danger) " Tindakan berisiko — perlu persetujuan" else " AI meminta izin",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        SelectionContainer {
            Text(preview, Modifier.padding(top = 6.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Text("Belum dijalankan. Periksa lalu setujui atau tolak.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { UiSound.click(); AiSession.resolve(true) },
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) { Icon(Icons.Rounded.Check, null); Text(" Setujui") }
            OutlinedButton(onClick = { UiSound.click(); AiSession.resolve(false) }) { Text("Tolak") }
        }
    }
}

@Composable
private fun ChatBubble(message: AiMessage, copyable: Boolean = true) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isAi = message.fromAi
    val brush = if (isAi) Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .22f), MaterialTheme.colorScheme.surface))
    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface))
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.background(brush).padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isAi) Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(6.dp))
                Text(if (isAi) "AI" else "Anda", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (isAi && copyable) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { copyToClipboard(ctx, "Jawaban AI", message.text) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.ContentCopy, "Salin jawaban", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SelectionContainer {
                Column(Modifier.padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (block in remember(message.text) { parseBlocks(message.text, codeBg) }) {
                        when (block) {
                            is Block.Prose -> Text(block.text)
                            is Block.Code -> Surface(shape = RoundedCornerShape(10.dp), color = codeBg, modifier = Modifier.fillMaxWidth()) {
                                Text(block.code, Modifier.padding(10.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolLine(message: AiMessage) {
    SelectionContainer {
        Text(
            message.text,
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorLine(message: AiMessage) {
    Text(
        message.text,
        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ThinkingRow() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "thinking-alpha")
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha))
        Text(
            "  " + (AiSession.activity ?: "AI sedang bekerja…"),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            maxLines = 2,
        )
    }
}

@Composable
private fun MemoryDialog(notes: List<MemoryNote>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Memori permanen AI") },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Fakta tahan lama yang disimpan AI dan dipakai di semua percakapan. Password, token, dan API key tidak pernah disimpan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (notes.isEmpty()) Text("Belum ada catatan. AI menyimpannya saat Anda menyebut preferensi atau aturan yang perlu diingat.")
                for (n in notes) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { UiSound.click(); AiMemory.remove(n.id) }) { Icon(Icons.Rounded.Close, "Hapus catatan") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Tutup") } },
        dismissButton = { if (notes.isNotEmpty()) TextButton(onClick = { AiMemory.clear() }) { Text("Hapus semua") } },
    )
}

// ---- Markdown ringan: judul, **tebal**, *miring*, `kode`, daftar (bullet/bernomor), kutipan, tabel, blok kode ``` ----

private sealed interface Block {
    data class Prose(val text: AnnotatedString) : Block
    data class Code(val code: String) : Block
}

private val NUMBERED = Regex("^(\\d{1,3})[.)]\\s+(.*)$")
private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")

private fun parseBlocks(src: String, codeBg: Color): List<Block> {
    val out = ArrayList<Block>()
    val parts = src.split("```")
    for ((idx, part) in parts.withIndex()) {
        if (idx % 2 == 1) {
            out += Block.Code(part.substringAfter('\n', part).trimEnd())
        } else if (part.isNotBlank()) {
            for ((isTable, chunk) in splitTables(part.trim('\n'))) {
                if (chunk.isBlank()) continue
                out += if (isTable) Block.Code(renderTable(chunk)) else Block.Prose(formatProse(chunk, codeBg))
            }
        }
    }
    return out
}

/** Pisahkan teks menjadi potongan tabel (baris berawalan "|") dan non-tabel. */
private fun splitTables(text: String): List<Pair<Boolean, String>> {
    val out = ArrayList<Pair<Boolean, String>>()
    val buf = StringBuilder()
    var inTable = false
    for (line in text.split('\n')) {
        val isRow = line.trimStart().startsWith("|")
        if (isRow != inTable && buf.isNotEmpty()) {
            out += inTable to buf.toString().trimEnd('\n')
            buf.setLength(0)
        }
        inTable = isRow
        buf.append(line).append('\n')
    }
    if (buf.isNotEmpty()) out += inTable to buf.toString().trimEnd('\n')
    return out
}

/** Tabel markdown -> teks monospace rata kolom (baris pemisah ---- dibuang). */
private fun renderTable(raw: String): String {
    val rows = raw.split('\n').map { it.trim() }.filter { it.startsWith("|") }
        .map { r -> r.trim('|').split('|').map { it.trim().replace("**", "").replace("`", "") } }
        .filterNot { cells -> cells.all { c -> c.isNotEmpty() && c.all { ch -> ch == '-' || ch == ':' } } }
    if (rows.isEmpty()) return raw
    val cols = rows.maxOf { it.size }
    val widths = IntArray(cols) { c -> minOf(40, rows.maxOf { it.getOrNull(c)?.length ?: 0 }) }
    return rows.joinToString("\n") { r ->
        (0 until cols).joinToString(" │ ") { c -> (r.getOrNull(c) ?: "").take(40).padEnd(widths[c]) }.trimEnd()
    }
}

private fun formatProse(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    text.split('\n').forEachIndexed { n, raw ->
        if (n > 0) append('\n')
        val t = raw.trimStart()
        val indent = "  ".repeat(((raw.length - t.length) / 2).coerceAtMost(4))
        val numbered = NUMBERED.matchEntire(t)
        when {
            t.startsWith("#") -> {
                val level = t.takeWhile { it == '#' }.length
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = if (level <= 2) 17.sp else 15.sp)) {
                    appendInline(t.trimStart('#').trim(), codeBg)
                }
            }
            RULE.matches(t) -> append("────────────")
            t.startsWith("- ") || t.startsWith("* ") -> {
                append("$indent  • ")
                appendInline(t.substring(2), codeBg)
            }
            numbered != null -> {
                append("$indent  ${numbered.groupValues[1]}. ")
                appendInline(numbered.groupValues[2], codeBg)
            }
            t.startsWith(">") -> {
                append("▎ ")
                withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    appendInline(t.removePrefix(">").trim(), codeBg)
                }
            }
            else -> appendInline(raw, codeBg)
        }
    }
}

private fun AnnotatedString.Builder.appendInline(s: String, codeBg: Color) {
    var i = 0
    while (i < s.length) {
        if (s.startsWith("**", i)) {
            val end = s.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (s[i] == '*' && i + 1 < s.length && s[i + 1] != '*' && s[i + 1] != ' ') {
            // *miring*: penutup harus menempel pada huruf (bukan spasi) dan bukan bagian dari **.
            var end = s.indexOf('*', i + 1)
            while (end > 0 && (s[end - 1] == ' ' || (end + 1 < s.length && s[end + 1] == '*'))) end = s.indexOf('*', end + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        if (s[i] == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        if (s[i] == '[') {
            val close = s.indexOf("](", i + 1)
            val urlEnd = if (close > i) s.indexOf(')', close + 2) else -1
            if (close > i + 1 && urlEnd > close + 2) {
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(s.substring(i + 1, close)) }
                append(" (" + s.substring(close + 2, urlEnd) + ")")
                i = urlEnd + 1
                continue
            }
        }
        append(s[i])
        i++
    }
}
