@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.mccdroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mccdroid.core.AppPrefs
import app.mccdroid.core.Paths
import app.mccdroid.core.ProfileStore
import app.mccdroid.core.SState
import app.mccdroid.core.SessionManager
import app.mccdroid.core.UiSound
import app.mccdroid.logic.CommandCatalog
import app.mccdroid.logic.LineKind
import app.mccdroid.logic.LogBuffer
import app.mccdroid.logic.LogLine
import app.mccdroid.logic.McText
import app.mccdroid.ui.Nav
import app.mccdroid.ui.components.DeviceCodeCard
import app.mccdroid.ui.components.EmptyState
import app.mccdroid.ui.components.ScreenHeader
import app.mccdroid.ui.components.StateChip
import app.mccdroid.ui.components.copyToClipboard
import app.mccdroid.ui.components.openUrl
import app.mccdroid.ui.components.toast
import app.mccdroid.ui.isDarkTheme
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun readable(rgb: Int, dark: Boolean): Color {
    val r = (rgb shr 16) and 0xFF
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    val lum = 0.299 * r + 0.587 * g + 0.114 * b
    return if (dark && lum < 70) Color(minOf(255, r + 90), minOf(255, g + 90), minOf(255, b + 90))
    else if (!dark && lum > 190) Color(r * 6 / 10, g * 6 / 10, b * 6 / 10)
    else Color(r, g, b)
}

private fun render(line: LogLine, dark: Boolean, timestamps: Boolean): AnnotatedString {
    val base = if (dark) Color(0xFFD7E2DB) else Color(0xFF1B2620)
    return buildAnnotatedString {
        if (timestamps) withStyle(SpanStyle(color = base.copy(alpha = .45f))) { append("[" + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(line.ts)) + "] ") }
        when (line.kind) {
            LineKind.IN -> withStyle(SpanStyle(color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)) { append("› " + line.clean) }
            LineKind.SYS -> withStyle(SpanStyle(color = Color(0xFFFBBF24), fontStyle = FontStyle.Italic)) { append(line.clean) }
            LineKind.ERR -> withStyle(SpanStyle(color = Color(0xFFF87171))) { append(line.clean) }
            LineKind.OUT -> line.spans.forEach { s ->
                val deco = when {
                    s.underline && s.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    s.underline -> TextDecoration.Underline
                    s.strike -> TextDecoration.LineThrough
                    else -> null
                }
                withStyle(SpanStyle(color = s.rgb?.let { readable(it, dark) } ?: base, fontWeight = if (s.bold) FontWeight.Bold else null, fontStyle = if (s.italic) FontStyle.Italic else null, textDecoration = deco)) { append(s.text) }
            }
        }
    }
}

@Composable
fun ConsoleScreen(nav: Nav) {
    val ctx = LocalContext.current
    val profiles by ProfileStore.profiles.collectAsState()
    val profile = nav.pick(profiles)
    var menu by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Console MCC", profile?.name ?: "Belum ada profil", actions = {
            if (profile != null) {
                val s = SessionManager.session(profile.id)
                val state by s.state.collectAsState()
                StateChip(state)
                val alive = state != SState.STOPPED && state != SState.CRASHED
                IconButton(onClick = { if (alive) SessionManager.stop(profile.id) else SessionManager.start(profile.id)?.let { toast(ctx, it) } }) {
                    Icon(if (alive) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (alive) "Hentikan" else "Mulai")
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.Menu, "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Katalog perintah") }, onClick = { menu = false; catalog = true })
                    DropdownMenuItem(text = { Text("Ganti profil") }, onClick = { menu = false; pickerOpen = true })
                }
            }
        })
        if (profile == null) {
            EmptyState(Icons.Rounded.Terminal, "Belum ada profil", "Buat profil di tab Beranda terlebih dahulu.")
        } else {
            val s = SessionManager.session(profile.id)
            val dc by s.deviceCode.collectAsState()
            dc?.let { DeviceCodeCard(it) }
            TerminalPane(s.log, "Perintah MCC, mis. /health atau teks chat", s.isAlive, profile.name, Paths.profileDir(ctx, profile.id), { s.log.clear() }) { s.send(it) }
        }
    }
    if (catalog) CatalogDialog({ catalog = false }) { cmd -> catalog = false; profile?.let { SessionManager.session(it.id).send(cmd) } }
    if (pickerOpen) AlertDialog(onDismissRequest = { pickerOpen = false }, title = { Text("Pilih profil") }, text = {
        Column { profiles.forEach { p -> TextButton(onClick = { nav.profileId = p.id; pickerOpen = false }) { Text(p.name) } } }
    }, confirmButton = { TextButton(onClick = { pickerOpen = false }) { Text("Tutup") } })
}

@Composable
private fun CatalogDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Katalog perintah MCC") }, text = {
        LazyColumn(Modifier.fillMaxWidth()) { items(CommandCatalog.all) { c ->
            Column(Modifier.fillMaxWidth().clickable { onPick("/" + c.name) }.padding(vertical = 6.dp)) {
                Text("/" + c.usage, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("${c.group} · ${c.desc}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } })
}

@Composable
private fun TerminalPane(log: LogBuffer, placeholder: String, enabled: Boolean, logName: String, logDir: File, onClear: () -> Unit, onSend: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val lines by log.flow.collectAsState()
    val dark = isDarkTheme()
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }
    var fontSp by remember { mutableStateOf(AppPrefs.consoleFontSp) }
    val history = remember { mutableStateListOf<String>() }
    var histIdx by remember { mutableStateOf(-1) }
    val atBottom by remember { derivedStateOf { val info = listState.layoutInfo; (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 2 } }
    LaunchedEffect(atBottom) { if (atBottom) follow = true else if (listState.isScrollInProgress) follow = false }
    LaunchedEffect(lines.size, follow) { if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }

    fun submit(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        onSend(value)
        if (value.startsWith("/")) AppPrefs.recordCommand(value)
        if (history.lastOrNull() != value) history.add(value)
        if (history.size > 100) history.removeAt(0)
        histIdx = -1; input = ""; follow = true
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Console output", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { fontSp = (fontSp - 1f).coerceIn(9f, 20f); AppPrefs.consoleFontSp = fontSp }) { Icon(Icons.Rounded.Remove, "Kecilkan teks", Modifier.size(18.dp)) }
            Text("${fontSp.toInt()}sp", style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = { fontSp = (fontSp + 1f).coerceIn(9f, 20f); AppPrefs.consoleFontSp = fontSp }) { Icon(Icons.Rounded.Add, "Besarkan teks", Modifier.size(18.dp)) }
            IconButton(onClick = { UiSound.copy(); copyToClipboard(ctx, "Log", lines.joinToString("\n") { it.clean }) }) { Icon(Icons.Rounded.ContentCopy, "Salin semua", Modifier.size(18.dp)) }
            IconButton(onClick = {
                try { val f = File(logDir, "console-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"); f.writeText(lines.joinToString("\n") { it.clean }); UiSound.success(); toast(ctx, "Disimpan: ${f.name}") }
                catch (e: Exception) { UiSound.alert(); toast(ctx, "Gagal menyimpan: ${e.message}") }
            }) { Icon(Icons.Rounded.Save, "Simpan", Modifier.size(18.dp)) }
            IconButton(onClick = { UiSound.delete(); onClear() }) { Icon(Icons.Rounded.DeleteSweep, "Bersihkan", Modifier.size(18.dp)) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (dark) Color(0xFF080D0B) else Color(0xFFEFF4F1))) {
            if (lines.isEmpty()) Text("Belum ada keluaran. Mulai profil dari Beranda atau tombol ▶.", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(6.dp)) {
                items(lines, key = { it.id }) { line ->
                    val urls = remember(line.id) { McText.findUrls(line.clean) }
                    Text(render(line, dark, AppPrefs.showTimestamps), fontFamily = FontFamily.Monospace, fontSize = fontSp.sp, lineHeight = (fontSp * 1.3f).sp, softWrap = AppPrefs.wrapLines, modifier = Modifier.fillMaxWidth().clickable {
                        if (urls.isNotEmpty()) openUrl(ctx, line.clean.substring(urls[0].first, urls[0].last + 1)) else copyToClipboard(ctx, "Baris", line.clean)
                    })
                }
            }
            if (!follow) FloatingActionButton(onClick = { follow = true; scope.launch { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(42.dp)) { Icon(Icons.Rounded.ArrowDownward, "Ke bawah") }
        }
        val suggestions = CommandCatalog.suggest(input)
        if (suggestions.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) { suggestions.forEach { c -> SuggestionChip(onClick = { input = "/${c.name} " }, label = { Text("/${c.name}") }) } }
        else if (input.isEmpty()) {
            val shortcuts = AppPrefs.commandShortcuts
            if (shortcuts.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) { shortcuts.forEach { cmd -> AssistChip(onClick = { submit(cmd) }, label = { Text(cmd, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace) }, enabled = enabled) } }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (history.isNotEmpty()) { histIdx = if (histIdx < 0) history.lastIndex else maxOf(0, histIdx - 1); input = history[histIdx] } }) { Icon(Icons.Rounded.ArrowUpward, "Riwayat") }
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text(placeholder, maxLines = 1) }, singleLine = true, enabled = enabled, shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit(input) }))
            Spacer(Modifier.width(4.dp)); IconButton(onClick = { UiSound.send(); submit(input) }, enabled = enabled && input.isNotBlank()) { Icon(Icons.Rounded.Send, "Kirim") }
        }
    }
}
