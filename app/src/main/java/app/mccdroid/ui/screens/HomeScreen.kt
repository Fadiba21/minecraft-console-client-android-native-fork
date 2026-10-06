package app.mccdroid.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mccdroid.core.Profile
import app.mccdroid.core.ProfileStore
import app.mccdroid.core.RtState
import app.mccdroid.core.RuntimeInstaller
import app.mccdroid.core.SState
import app.mccdroid.core.SessionManager
import app.mccdroid.core.Summary
import app.mccdroid.core.UiSound
import app.mccdroid.ui.AccentPalette
import app.mccdroid.ui.Nav
import app.mccdroid.ui.StateBad
import app.mccdroid.ui.StateBusy
import app.mccdroid.ui.Sub
import app.mccdroid.ui.components.ConfirmDialog
import app.mccdroid.ui.components.DeviceCodeCard
import app.mccdroid.ui.components.EmptyState
import app.mccdroid.ui.components.GlassCard
import app.mccdroid.ui.components.StateChip
import app.mccdroid.ui.components.StatusDot
import app.mccdroid.ui.components.SwitchRow
import app.mccdroid.ui.components.formatDuration
import app.mccdroid.ui.components.toast
import app.mccdroid.ui.stateColor
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(nav: Nav) {
    val profiles by ProfileStore.profiles.collectAsState()
    val rt by RuntimeInstaller.state.collectAsState()
    val summary by SessionManager.summary.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    var aiOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Hero(summary, profiles.size, nav) }
            item { RuntimeCard(rt, nav) }
            if (profiles.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.Dns, "Belum ada profil",
                        "Satu profil = satu akun/server dengan folder dan config MCC sendiri. Anda bisa menjalankan beberapa sekaligus.",
                    ) {
                        FilledTonalButton(onClick = { creating = true }) { Text("Buat profil pertama") }
                    }
                }
            }
            items(profiles, key = { it.id }) { p ->
                ProfileCard(
                    p, nav,
                    onEdit = { editing = p },
                    onDelete = { deleting = p },
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("Profil baru") },
        )
        SmallFloatingActionButton(
            onClick = {
                aiOpen = !aiOpen
                if (aiOpen) UiSound.open() else UiSound.close()
            },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) { Icon(Icons.Rounded.AutoAwesome, "Buka Gemini Assistant") }
        AnimatedVisibility(
            visible = aiOpen,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(220)) + scaleIn(tween(300), initialScale = 0.92f),
            exit = fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 0.96f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.48f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(0.84f)
                        .clip(RoundedCornerShape(26.dp)),
                ) {
                    AiScreen(nav) { aiOpen = false }
                }
            }
        }
    }

    if (creating) ProfileDialog(null, onDismiss = { creating = false }) { name, notes, args, auto, restart ->
        val p = ProfileStore.create(name)
        ProfileStore.update(p.copy(notes = notes, extraArgs = args, autoStart = auto, autoRestart = restart))
        nav.profileId = p.id
    }
    editing?.let { e ->
        ProfileDialog(e, onDismiss = { editing = null }) { name, notes, args, auto, restart ->
            ProfileStore.update(e.copy(name = name.ifBlank { e.name }, notes = notes, extraArgs = args, autoStart = auto, autoRestart = restart))
        }
    }
    deleting?.let { d ->
        ConfirmDialog(
            "Hapus profil?",
            "Profil \"${d.name}\" beserta seluruh filenya (config, cache sesi, skrip, log) akan dihapus permanen.",
            confirm = "Hapus", onDismiss = { deleting = null },
        ) { ProfileStore.delete(d.id) }
    }
}

@Composable
private fun Hero(summary: Summary, count: Int, nav: Nav) {
    val t = rememberInfiniteTransition(label = "hero")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(7000), RepeatMode.Reverse), label = "phase")
    val pulse by t.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "pulse")
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val dashboardState = when {
        summary.online > 0 -> SState.ONLINE
        summary.total > 0 -> SState.STARTING
        else -> SState.STOPPED
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(primary.copy(alpha = 0.40f), secondary.copy(alpha = 0.22f), MaterialTheme.colorScheme.surface),
                    start = Offset(0f, 0f),
                    end = Offset(500f + 700f * phase, 360f + 200f * phase),
                ),
            )
            .padding(20.dp),
    ) {
        // Glow bergerak memberi kesan hidup tanpa menutupi konten header.
        Box(
            Modifier
                .size(170.dp)
                .offset(x = (phase * 150f).dp, y = (-34f + phase * 24f).dp)
                .scale(pulse)
                .alpha(0.16f)
                .clip(CircleShape)
                .background(primary),
        )
        Box(
            Modifier
                .size(110.dp)
                .align(Alignment.BottomEnd)
                .offset(x = (phase * -34f).dp, y = (phase * 24f).dp)
                .scale(1.08f - phase * 0.08f)
                .alpha(0.13f)
                .clip(CircleShape)
                .background(secondary),
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MCC Droid", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.58f))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    StatusDot(dashboardState, size = 6)
                    Text(
                        if (summary.online > 0) "LIVE" else "SIAP",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = stateColor(dashboardState),
                    )
                }
            }
            Text("Minecraft Console Client, native di Android", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Stat("Profil", count.toString())
                Stat("Berjalan", summary.total.toString())
                Stat("Online", summary.online.toString())
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RuntimeCard(rt: RtState, nav: Nav) {
    when (rt) {
        is RtState.Installing -> GlassCard(accent = StateBusy) {
            Text("Memasang runtime MCC…", fontWeight = FontWeight.Bold)
            Text("Hanya sekali setelah instal/pembaruan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (rt.progress >= 0f) LinearProgressIndicator(progress = { rt.progress }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(rt.message, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is RtState.Checking -> GlassCard { Text("Memeriksa runtime…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        is RtState.Missing -> ProblemCard("Runtime MCC tidak ada", rt.reason, nav)
        is RtState.Failed -> ProblemCard("Runtime gagal dipasang", rt.message, nav)
        is RtState.Ready -> {
            val commit = (rt.info["mccCommit"] as? String).orEmpty().take(7)
            val built = (rt.info["builtAt"] as? String).orEmpty().take(10)
            if (commit.isNotEmpty()) {
                Text(
                    "Runtime MCC siap · commit $commit · dibangun $built",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ProblemCard(title: String, text: String, nav: Nav) {
    GlassCard(accent = StateBad) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = StateBad)
            Text("  $title", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { nav.sub = Sub.SETTINGS }) { Text("Buka Diagnostik") }
    }
}

@Composable
private fun ProfileCard(p: Profile, nav: Nav, onEdit: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val s = remember(p.id) { SessionManager.session(p.id) }
    val state by s.state.collectAsState()
    val last by s.lastEvent.collectAsState()
    val dc by s.deviceCode.collectAsState()
    val started by s.startedAt.collectAsState()
    val alive = state != SState.STOPPED && state != SState.CRASHED
    var menu by remember { mutableStateOf(false) }
    val accent = AccentPalette[p.colorIdx.coerceIn(0, AccentPalette.lastIndex)]

    GlassCard(accent = if (alive) stateColor(state) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp, 40.dp).clip(RoundedCornerShape(3.dp)).background(accent))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    last.isNotEmpty() && alive -> last
                    p.notes.isNotBlank() -> p.notes
                    else -> "Folder: ${p.id}"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            StateChip(state)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Ubah profil") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Duplikat") }, onClick = { menu = false; ProfileStore.duplicate(p.id) })
                    if (alive) DropdownMenuItem(text = { Text("Paksa berhenti") }, onClick = { menu = false; s.kill() })
                    DropdownMenuItem(text = { Text("Hapus") }, onClick = { menu = false; onDelete() })
                }
            }
        }
        if (alive) {
            val now by produceState(System.currentTimeMillis(), state) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(1000)
                }
            }
            Text(
                "Aktif selama ${formatDuration(now - started)}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        AnimatedVisibility(
            visible = dc != null,
            enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.padding(top = 10.dp)) { dc?.let { DeviceCodeCard(it) } }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (alive) {
                FilledTonalButton(onClick = { SessionManager.stop(p.id) }) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Text("  Hentikan")
                }
                OutlinedButton(onClick = { SessionManager.restart(p.id) }) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Text("  Ulang")
                }
            } else {
                FilledTonalButton(onClick = {
                    val err = SessionManager.start(p.id)
                    if (err != null) toast(ctx, err)
                }) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Text("  Mulai")
                }
                OutlinedButton(onClick = { nav.profileId = p.id; nav.tab = 2 }) {
                    Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)); Text("  Config")
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { nav.profileId = p.id; nav.tab = 1 }) { Icon(Icons.Rounded.Terminal, "Terminal") }
        }
    }
}

@Composable
private fun ProfileDialog(
    existing: Profile?, onDismiss: () -> Unit,
    onOk: (name: String, notes: String, args: String, auto: Boolean, restart: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var args by remember { mutableStateOf(existing?.extraArgs ?: "") }
    var auto by remember { mutableStateOf(existing?.autoStart ?: false) }
    var restart by remember { mutableStateOf(existing?.autoRestart ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Profil baru" else "Ubah profil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nama profil") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("Catatan (opsional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    args, { args = it }, label = { Text("Argumen tambahan MCC") },
                    supportingText = { Text("Mis. --debugmessages=true") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow("Mulai otomatis", "Dijalankan saat boot bila \"Mulai saat boot\" aktif di Pengaturan", auto) { auto = it }
                SwitchRow("Mulai ulang jika berhenti", "Otomatis dengan jeda bertahap bila MCC berhenti tak terduga", restart) { restart = it }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(name, notes, args, auto, restart); onDismiss() }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
