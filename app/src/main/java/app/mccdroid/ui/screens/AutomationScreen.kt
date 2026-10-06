@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.mccdroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mccdroid.core.ProfileStore
import app.mccdroid.core.RuleStore
import app.mccdroid.logic.ActionType
import app.mccdroid.logic.Automation
import app.mccdroid.logic.McEvent
import app.mccdroid.logic.MatchType
import app.mccdroid.logic.Rule
import app.mccdroid.logic.RuleAction
import app.mccdroid.logic.TriggerKind
import app.mccdroid.ui.Nav
import app.mccdroid.ui.components.ChoiceChips
import app.mccdroid.ui.components.ConfirmDialog
import app.mccdroid.ui.components.DropdownRow
import app.mccdroid.ui.components.EmptyState
import app.mccdroid.ui.components.GlassCard
import app.mccdroid.ui.components.PrimaryButton
import app.mccdroid.ui.components.ScreenHeader
import app.mccdroid.ui.components.SectionTitle
import app.mccdroid.ui.components.SliderRow
import app.mccdroid.ui.components.SwitchRow

@Composable
fun AutomationScreen(nav: Nav) {
    val rules by RuleStore.rules.collectAsState()
    val profiles by ProfileStore.profiles.collectAsState()
    var editing by remember { mutableStateOf<Rule?>(null) }
    var deleting by remember { mutableStateOf<Rule?>(null) }
    var presets by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Otomasi", "Balas chat, login otomatis, atau kirim notifikasi saat teks/kejadian tertentu muncul",
            actions = {
                IconButton(onClick = { presets = true }) { Icon(Icons.Rounded.AutoAwesome, "Contoh") }
                IconButton(onClick = { editing = Rule(id = "", name = "Aturan baru") }) { Icon(Icons.Rounded.Add, "Tambah") }
            },
        )
        if (rules.isEmpty()) {
            EmptyState(Icons.Rounded.Bolt, "Belum ada aturan", "Tambah aturan sendiri atau mulai dari contoh siap pakai.") {
                PrimaryButton("Lihat contoh", Icons.Rounded.AutoAwesome) { presets = true }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(rules, key = { it.id }) { r ->
                GlassCard(onClick = { editing = r }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontWeight = FontWeight.Bold)
                            val trig = if (r.trigger == TriggerKind.LINE) "${r.match.label}: “${r.pattern}”" else "Saat: ${r.event.label}"
                            Text(trig, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val scope = if (r.profileId.isEmpty()) "Semua profil" else profiles.firstOrNull { it.id == r.profileId }?.name ?: "Profil dihapus"
                            Text(
                                "$scope · ${r.actions.size} aksi",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Switch(checked = r.enabled, onCheckedChange = { RuleStore.setEnabled(r.id, it) })
                        IconButton(onClick = { deleting = r }) { Icon(Icons.Rounded.Delete, "Hapus") }
                    }
                }
            }
        }
    }

    editing?.let { r -> RuleEditor(r, onDismiss = { editing = null }) { RuleStore.upsert(it); editing = null } }
    deleting?.let { r -> ConfirmDialog("Hapus aturan?", r.name, "Hapus", { deleting = null }) { RuleStore.delete(r.id) } }
    if (presets) AlertDialog(
        onDismissRequest = { presets = false },
        title = { Text("Contoh aturan") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Ketuk untuk menambahkan. Periksa dan sesuaikan isinya (mis. kata sandi) sebelum mengaktifkan.",
                    style = MaterialTheme.typography.bodySmall,
                )
                for (p in Automation.presets()) {
                    TextButton(onClick = { RuleStore.upsert(p.copy(enabled = false)); presets = false }) { Text(p.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { presets = false }) { Text("Tutup") } },
    )
}

@Composable
private fun RuleEditor(initial: Rule, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    val profiles by ProfileStore.profiles.collectAsState()
    var name by remember { mutableStateOf(initial.name) }
    var profileId by remember { mutableStateOf(initial.profileId) }
    var trigger by remember { mutableStateOf(initial.trigger) }
    var match by remember { mutableStateOf(initial.match) }
    var pattern by remember { mutableStateOf(initial.pattern) }
    var ignoreCase by remember { mutableStateOf(initial.ignoreCase) }
    var event by remember { mutableStateOf(initial.event) }
    var cooldown by remember { mutableStateOf(initial.cooldownSec.toFloat()) }
    val actions = remember { mutableStateListOf<RuleAction>().apply { addAll(initial.actions) } }
    var testText by remember { mutableStateOf("") }

    val regexBad = trigger == TriggerKind.LINE && match == MatchType.REGEX && pattern.isNotEmpty() && !Automation.isValidRegex(pattern)
    val canSave = name.isNotBlank() && !regexBad && actions.isNotEmpty() && (trigger == TriggerKind.EVENT || pattern.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isEmpty()) "Aturan baru" else "Ubah aturan") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nama") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DropdownRow(
                    "Berlaku untuk", null, profileId, listOf("") + profiles.map { it.id },
                    display = { id -> if (id.isEmpty()) "Semua profil" else profiles.firstOrNull { it.id == id }?.name ?: id },
                ) { profileId = it }

                SectionTitle("Pemicu")
                ChoiceChips(TriggerKind.values().toList(), trigger, { it.label }) { trigger = it }
                if (trigger == TriggerKind.LINE) {
                    ChoiceChips(MatchType.values().toList(), match, { it.label }) { match = it }
                    OutlinedTextField(
                        pattern, { pattern = it }, label = { Text("Pola teks") }, singleLine = true,
                        isError = regexBad, modifier = Modifier.fillMaxWidth(),
                        supportingText = { if (regexBad) Text("Regex tidak valid") else Text("Grup regex bisa dipakai sebagai {1}, {2}; seluruh baris: {line}") },
                    )
                    SwitchRow("Abaikan huruf besar/kecil", null, ignoreCase) { ignoreCase = it }
                    OutlinedTextField(
                        testText, { testText = it }, label = { Text("Uji dengan contoh baris") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (testText.isNotEmpty() && !regexBad && pattern.isNotEmpty()) {
                        val probe = Rule("", name, trigger = trigger, match = match, pattern = pattern, ignoreCase = ignoreCase)
                        val hit = Automation.matchLine(probe, testText)
                        Text(
                            if (hit != null) "✓ Cocok" else "✗ Tidak cocok",
                            color = if (hit != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    DropdownRow("Kejadian", null, event.name, McEvent.values().map { it.name }, display = { McEvent.valueOf(it).label }) {
                        event = McEvent.valueOf(it)
                    }
                    Text(
                        "Variabel: {detail}, {name}, {event}, dan data tambahan kejadian.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SliderRow("Jeda antar pemicu", "Minimal 1 detik agar balasan tidak membuat putaran tak berujung.", cooldown, 1f..300f, "dtk") { cooldown = it }

                SectionTitle("Aksi (berurutan)")
                actions.forEachIndexed { i, a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            DropdownRow("Aksi ${i + 1}", null, a.type.name, ActionType.values().map { it.name }, display = { ActionType.valueOf(it).label }) {
                                actions[i] = a.copy(type = ActionType.valueOf(it))
                            }
                            if (a.type != ActionType.RESTART && a.type != ActionType.STOP) {
                                OutlinedTextField(
                                    a.arg, { actions[i] = a.copy(arg = it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    label = {
                                        Text(
                                            when (a.type) {
                                                ActionType.SEND -> "Teks / perintah (mis. /send halo)"
                                                ActionType.NOTIFY -> "Judul|Isi (judul opsional)"
                                                else -> "Milidetik"
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        IconButton(onClick = { actions.removeAt(i) }) { Icon(Icons.Rounded.Close, "Hapus aksi") }
                    }
                }
                AssistChip(onClick = { actions.add(RuleAction(ActionType.SEND, "")) }, label = { Text("+ Tambah aksi") })
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(), profileId = profileId, trigger = trigger, match = match,
                            pattern = pattern, ignoreCase = ignoreCase, event = event,
                            cooldownSec = cooldown.toInt(), actions = actions.toList(),
                        ),
                    )
                },
            ) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
