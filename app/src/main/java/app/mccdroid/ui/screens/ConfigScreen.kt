@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.mccdroid.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mccdroid.core.Diagnostics
import app.mccdroid.core.Paths
import app.mccdroid.core.ProfileStore
import app.mccdroid.core.SessionManager
import app.mccdroid.logic.CfgEntry
import app.mccdroid.logic.CfgSection
import app.mccdroid.logic.ConfigDoc
import app.mccdroid.logic.ConfigHints
import app.mccdroid.logic.Toml
import app.mccdroid.logic.VKind
import app.mccdroid.ui.Nav
import app.mccdroid.ui.components.ConfirmDialog
import app.mccdroid.ui.components.DropdownRow
import app.mccdroid.ui.components.EmptyState
import app.mccdroid.ui.components.GlassCard
import app.mccdroid.ui.components.PrimaryButton
import app.mccdroid.ui.components.ScreenHeader
import app.mccdroid.ui.components.SectionTitle
import app.mccdroid.ui.components.SliderRow
import app.mccdroid.ui.components.StringListEditor
import app.mccdroid.ui.components.SwitchRow
import app.mccdroid.ui.components.TextFieldRow
import app.mccdroid.ui.components.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class ConfigState {
    var doc by mutableStateOf<ConfigDoc?>(null)
    var raw by mutableStateOf("")
    var rev by mutableIntStateOf(0)
    var dirty by mutableStateOf(false)
    var missing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun ConfigScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by ProfileStore.profiles.collectAsState()
    val profile = nav.pick(profiles)

    if (profile == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Konfigurasi")
            EmptyState(Icons.Rounded.Tune, "Belum ada profil", "Buat profil di tab Beranda untuk mengatur konfigurasinya.")
        }
        return
    }

    val st = remember(profile.id) { ConfigState() }
    var reloadKey by remember(profile.id) { mutableIntStateOf(0) }
    var rawMode by remember(profile.id) { mutableStateOf(false) }
    var query by remember(profile.id) { mutableStateOf("") }
    var category by remember(profile.id) { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    val file = remember(profile.id) { Paths.configFile(ctx, profile.id) }

    LaunchedEffect(profile.id, reloadKey) {
        val text = withContext(Dispatchers.IO) { if (file.exists()) file.readText() else null }
        if (text == null) {
            st.missing = true; st.doc = null; st.raw = ""
        } else {
            st.missing = false
            st.raw = text
            try {
                st.doc = ConfigDoc.parse(text); st.error = null
            } catch (e: Exception) {
                st.doc = null; st.error = e.message
            }
        }
        st.dirty = false
    }

    fun save(reload: Boolean) {
        scope.launch {
            val out = if (rawMode) st.raw else st.doc?.render() ?: return@launch
            withContext(Dispatchers.IO) {
                try {
                    if (file.exists()) file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true)
                    Paths.writeAtomic(file, out)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { toast(ctx, "Gagal menyimpan: ${e.message}") }
                    return@withContext
                }
            }
            st.dirty = false
            st.raw = out
            if (!rawMode) st.doc = ConfigDoc.parse(out)
            val s = SessionManager.session(profile.id)
            if (reload && s.isAlive) {
                s.send("/reload")
                toast(ctx, "Tersimpan dan /reload dikirim")
            } else toast(ctx, "Tersimpan (cadangan: .bak)")
        }
    }

    val sessionAlive = SessionManager.session(profile.id).isAlive
    // Jangan tampilkan banner aksi di atas keyboard: banner lama menutupi field
    // tepat saat pengguna mulai mengetik dan terasa seperti popup yang muncul terus.
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                "Konfigurasi",
                profile.name,
                actions = {
                    IconButton(onClick = {
                        if (rawMode) {
                            // kembali ke mode visual: parse ulang teks mentah
                            try {
                                st.doc = ConfigDoc.parse(st.raw)
                            } catch (_: Exception) {
                            }
                        } else {
                            st.doc?.let { st.raw = it.render() }
                        }
                        rawMode = !rawMode
                    }) { Icon(if (rawMode) Icons.Rounded.Tune else Icons.Rounded.Code, "Ganti mode") }
                },
            )

            when {
                st.missing -> {
                    EmptyState(
                        Icons.Rounded.Dns, "Config belum ada",
                        "MCC membuat MinecraftClient.ini saat pertama dijalankan. Anda bisa membuatnya sekarang.",
                    ) {
                        PrimaryButton(if (generating) "Membuat…" else "Buat config default", enabled = !generating) {
                            generating = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { Diagnostics.generateDefaultConfig(ctx, profile.id) }
                                generating = false
                                if (ok) reloadKey++ else toast(ctx, "Gagal membuat config. Cek Pengaturan → Diagnostik.")
                            }
                        }
                    }
                }
                st.error != null -> {
                    Text("Config tidak bisa dibaca: ${st.error}. Pakai mode mentah untuk memperbaikinya.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    if (!rawMode) rawMode = true
                    RawEditor(st)
                }
                rawMode -> RawEditor(st)
                else -> {
                    val doc = st.doc
                    if (doc != null) VisualEditor(doc, st, query, { query = it }, category, { category = it })
                }
            }
        }

        AnimatedVisibility(
            visible = st.dirty && !keyboardVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            GlassCard(Modifier.padding(12.dp), accent = MaterialTheme.colorScheme.primary) {
                Text("Ada perubahan belum disimpan", fontWeight = FontWeight.SemiBold)
                Text(
                    "MCC menulis ulang file ini saat dijalankan. Ubah saat MCC berhenti, atau simpan lalu /reload.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    OutlinedButton(onClick = { confirmDiscard = true }) { Text("Buang") }
                    PrimaryButton("Simpan", Icons.Rounded.Save) { save(false) }
                    if (sessionAlive) PrimaryButton("Simpan & /reload") { save(true) }
                }
            }
        }
    }

    if (confirmDiscard) ConfirmDialog("Buang perubahan?", "Perubahan yang belum disimpan akan hilang.", "Buang", { confirmDiscard = false }) { reloadKey++ }
}

@Composable
private fun RawEditor(st: ConfigState) {
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    OutlinedTextField(
        value = st.raw,
        onValueChange = { st.raw = it; st.dirty = true },
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp, bottom = if (st.dirty && !keyboardVisible) 150.dp else 12.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
    )
}

@Composable
private fun VisualEditor(
    doc: ConfigDoc,
    st: ConfigState,
    query: String,
    onQuery: (String) -> Unit,
    category: String?,
    onCategory: (String?) -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val rev = st.rev // membaca agar recompose saat nilai berubah
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val cats = remember(doc) { doc.categories() }
    val bots = remember(doc) { doc.botSections() }

    fun changed() {
        st.rev++
        st.dirty = true
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (st.dirty && !keyboardVisible) 180.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Cari pengaturan…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
        }

        if (query.isNotBlank()) {
            val found = doc.search(query)
            item { SectionTitle("${found.size} hasil") }
            items(found, key = { it.path + "#" + it.section.index }) { e ->
                // Item LazyColumn perlu membaca revision agar nilai kontrol
                // ikut diperbarui setelah model ConfigDoc dimutasi.
                st.rev
                GlassCard {
                    Text(e.section.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    EntryEditor(doc, e, ::changed)
                }
            }
            return@LazyColumn
        }

        // Akun & server: bagian yang paling sering dipakai.
        item {
            st.rev
            QuickCard(doc, ::changed)
        }

        if (bots.isNotEmpty()) {
            item {
                st.rev
                GlassCard {
                    SectionTitle("Bot (ChatBot)")
                    for (b in bots) {
                        val en = b.entries.first { it.key == "Enabled" }
                        SwitchRow(
                            title = b.name.removePrefix("ChatBot."),
                            desc = b.comment.lineSequence().firstOrNull { it.isNotBlank() },
                            checked = en.raw.trim() == "true",
                        ) { doc.setBool(en, it); changed() }
                    }
                }
            }
        }

        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = category == null, onClick = { onCategory(null) }, label = { Text("Semua") })
                for (c in cats.keys) {
                    FilterChip(selected = category == c, onClick = { onCategory(c) }, label = { Text(ConfigHints.categoryTitle(c)) })
                }
            }
        }

        for ((cat, secs) in cats) {
            if (category != null && category != cat) continue
            for (sec in secs) {
                if (sec.entries.isEmpty()) continue
                item(key = "sec:" + sec.name + "#" + sec.index) {
                    st.rev
                    SectionCard(doc, sec, ::changed)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(doc: ConfigDoc, sec: CfgSection, changed: () -> Unit) {
    var open by remember { mutableStateOf(sec.topLevel == "Main" && sec.name == "Main.General") }
    GlassCard(onClick = null) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickableNoRipple { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(sec.title, fontWeight = FontWeight.Bold)
                val d = sec.comment.lineSequence().firstOrNull { it.isNotBlank() }
                if (d != null) Text(d.trim('#', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Text(if (open) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
        }
        AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                for ((i, e) in sec.entries.withIndex()) {
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    EntryEditor(doc, e, changed)
                }
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

@Composable
private fun QuickCard(doc: ConfigDoc, changed: () -> Unit) {
    val account = doc.find("Main.General", "Account")
    val server = doc.find("Main.General", "Server")
    if (account == null && server == null) return
    GlassCard(accent = MaterialTheme.colorScheme.primary) {
        SectionTitle("Akun & server")
        if (account != null) {
            val login = doc.tableGet(account, "Login")?.let { Toml.unquote(it) } ?: ""
            val pass = doc.tableGet(account, "Password")?.let { Toml.unquote(it) } ?: ""
            TextFieldRow("Email / nama pengguna", "Untuk akun Microsoft isi email; kata sandi boleh '-' (login lewat kode perangkat).", login) {
                doc.tableSet(account, "Login", Toml.quote(it)); changed()
            }
            TextFieldRow("Kata sandi", "Isi '-' agar MCC memakai login Microsoft interaktif.", pass, secret = true) {
                doc.tableSet(account, "Password", Toml.quote(it)); changed()
            }
        }
        if (server != null) {
            val host = doc.tableGet(server, "Host")?.let { Toml.unquote(it) } ?: ""
            val port = doc.tableGet(server, "Port")?.let { Toml.unquote(it) } ?: ""
            TextFieldRow("Alamat server", "Contoh: play.contoh.net", host) {
                doc.tableSet(server, "Host", Toml.quote(it)); changed()
            }
            TextFieldRow("Port", "Kosongkan untuk 25565 / SRV otomatis.", port, numeric = true) {
                val v = it.filter(Char::isDigit)
                doc.tableSet(server, "Port", if (v.isEmpty()) "25565" else v); changed()
            }
        }
    }
}

/** Satu baris pengaturan dengan widget sesuai tipe nilainya. */
@Composable
private fun EntryEditor(doc: ConfigDoc, e: CfgEntry, changed: () -> Unit) {
    val hint = ConfigHints.hint(e)
    val title = ConfigHints.label(e)
    val desc = (hint?.desc ?: e.description).lineSequence().map { it.trim('#', ' ') }.filter { it.isNotEmpty() }.joinToString(" ").take(300)
    val secret = hint?.secret == true || e.key.contains("password", true) || e.key.contains("token", true)

    when (e.kind) {
        VKind.BOOL -> SwitchRow(title, desc, e.raw.trim() == "true") { doc.setBool(e, it); changed() }
        VKind.INT, VKind.FLOAT -> {
            val range = ConfigHints.sliderRange(e)
            val num = ConfigHints.numberOf(e) ?: 0.0
            val isInt = e.kind == VKind.INT
            if (range != null) {
                val step = ConfigHints.step(e)
                val steps = ((range.endInclusive - range.start) / step).toInt() - 1
                SliderRow(
                    title, desc, num.toFloat(), range.start.toFloat()..range.endInclusive.toFloat(),
                    unit = hint?.unit ?: "",
                    steps = steps.coerceIn(0, 200),
                    format = { if (isInt) it.toLong().toString() else "%.2f".format(it) },
                ) { v ->
                    val snapped = Math.round(v / step) * step
                    if (isInt) doc.setInt(e, snapped.toLong()) else doc.setDouble(e, snapped)
                    changed()
                }
            } else {
                TextFieldRow(title, desc, e.raw.trim(), numeric = true) { s ->
                    val t = s.trim()
                    if (isInt) t.toLongOrNull()?.let { doc.setInt(e, it); changed() }
                    else t.toDoubleOrNull()?.let { doc.setDouble(e, it); changed() }
                }
            }
        }
        VKind.STRING -> {
            val cur = Toml.unquote(e.raw)
            val opts = ConfigHints.options(e)
            if (opts != null) DropdownRow(title, desc, cur, opts) { doc.setString(e, it); changed() }
            else TextFieldRow(title, desc, cur, secret = secret) { doc.setString(e, it); changed() }
        }
        VKind.ARRAY -> {
            val items = Toml.arrayItems(e.raw)
            val allStrings = items.all { it.trim().startsWith("\"") || it.trim().startsWith("'") }
            if (allStrings) {
                StringListEditor(title, desc, items.map { Toml.unquote(it.trim()) }) { doc.setStringArray(e, it); changed() }
            } else {
                TextFieldRow(title, desc, e.raw.trim(), mono = true) { doc.setRaw(e, it.trim()); changed() }
            }
        }
        VKind.TABLE -> {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(title, fontWeight = FontWeight.Medium)
                if (desc.isNotEmpty()) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for ((k, v) in Toml.tablePairs(e.raw)) {
                    val quoted = v.trim().startsWith("\"") || v.trim().startsWith("'")
                    val shown = if (quoted) Toml.unquote(v.trim()) else v.trim()
                    val isBool = v.trim() == "true" || v.trim() == "false"
                    if (isBool) {
                        SwitchRow(k, null, v.trim() == "true") { doc.tableSet(e, k, if (it) "true" else "false"); changed() }
                    } else {
                        TextFieldRow(
                            k, null, shown,
                            secret = k.contains("pass", true) || k.contains("token", true),
                        ) { s ->
                            val raw = if (quoted) Toml.quote(s) else if (s.toDoubleOrNull() != null) s else Toml.quote(s)
                            doc.tableSet(e, k, raw); changed()
                        }
                    }
                }
            }
        }
        VKind.OTHER -> TextFieldRow(title, desc, e.raw.trim(), mono = true) { doc.setRaw(e, it.trim()); changed() }
    }
}
