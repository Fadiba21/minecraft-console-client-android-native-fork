@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.mccdroid.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mccdroid.core.AppPrefs
import app.mccdroid.core.Diagnostics
import app.mccdroid.core.GeminiStore
import app.mccdroid.core.RtState
import app.mccdroid.core.RuntimeInstaller
import app.mccdroid.core.UiSound
import app.mccdroid.ui.AccentPalette
import app.mccdroid.ui.Nav
import app.mccdroid.ui.components.ChoiceChips
import app.mccdroid.ui.components.DropdownRow
import app.mccdroid.ui.components.GlassCard
import app.mccdroid.ui.components.ScreenHeader
import app.mccdroid.ui.components.SectionTitle
import app.mccdroid.ui.components.SliderRow
import app.mccdroid.ui.components.SwitchRow
import app.mccdroid.ui.components.copyToClipboard
import app.mccdroid.ui.components.toast
import app.mccdroid.logic.GeminiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rt by RuntimeInstaller.state.collectAsState()
    var diag by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Pengaturan", actions = {
            IconButton(onClick = { nav.sub = null }) { Icon(Icons.Rounded.ArrowBack, "Kembali") }
        })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard {
                SectionTitle("Tampilan")
                ChoiceChips(listOf("system", "dark", "light", "amoled"), AppPrefs.themeMode, {
                    when (it) { "system" -> "Sistem"; "dark" -> "Gelap"; "light" -> "Terang"; else -> "AMOLED" }
                }) { AppPrefs.themeMode = it }
                if (Build.VERSION.SDK_INT >= 31) {
                    SwitchRow("Warna dinamis", "Ikuti warna wallpaper (Android 12+).", AppPrefs.dynamicColor) { AppPrefs.dynamicColor = it }
                }
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccentPalette.forEachIndexed { i, c ->
                        Box(
                            Modifier
                                .size(if (AppPrefs.accentIdx == i) 38.dp else 30.dp)
                                .clip(CircleShape)
                                .background(c)
                                .clickable { AppPrefs.accentIdx = i },
                        )
                    }
                }
                SwitchRow("Layar tetap menyala", "Saat aplikasi terbuka.", AppPrefs.keepScreenOn) { AppPrefs.keepScreenOn = it }
            }

            GlassCard {
                SectionTitle("Konsol")
                SliderRow("Ukuran font", null, AppPrefs.consoleFontSp, 9f..20f, "sp", steps = 10) { AppPrefs.consoleFontSp = Math.round(it).toFloat() }
                SliderRow("Maks. baris log", "Memengaruhi pemakaian memori.", AppPrefs.maxLogLines.toFloat(), 500f..10000f, steps = 18) {
                    AppPrefs.maxLogLines = (it / 500).toInt() * 500
                }
                SwitchRow("Bungkus baris panjang", null, AppPrefs.wrapLines) { AppPrefs.wrapLines = it }
                SwitchRow("Tampilkan jam", null, AppPrefs.showTimestamps) { AppPrefs.showTimestamps = it }
                SwitchRow("Simpan log ke file", "Ditulis ke logs/console.log di folder profil.", AppPrefs.saveConsoleLog) { AppPrefs.saveConsoleLog = it }
                OutlinedButton(onClick = { nav.sub = app.mccdroid.ui.Sub.SHELL }) {
                    Text("Buka Shell Android (advanced)")
                }
            }

            GlassCard {
                SectionTitle("Sound effect UI")
                Text(
                    "15 template sound aesthetic, crispy, dan interaktif untuk klik, buka, tutup, kirim, toggle, sukses, error, notifikasi, dan seluruh aksi UI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow("Aktifkan sound effect", null, AppPrefs.soundEnabled) {
                    AppPrefs.soundEnabled = it
                    UiSound.setEnabled(it)
                }
                DropdownRow(
                    "Template aktif", "Pilih karakter suara yang paling cocok untuk UI aplikasi.", AppPrefs.soundTheme,
                    listOf("neo", "soft", "glass", "tech", "minimal", "pulse", "airy", "carbon", "pixel", "chime", "velvet", "spark", "orbit", "mono", "bloom"),
                    { soundLabel(it) },
                ) {
                    AppPrefs.soundTheme = it
                    UiSound.reload(ctx)
                    UiSound.click()
                }
                Text("Karakter: ${soundDescription(AppPrefs.soundTheme)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SliderRow("Volume sound effect", null, AppPrefs.soundVolume * 100f, 0f..100f, "%", steps = 9) {
                    AppPrefs.soundVolume = it / 100f
                }
                OutlinedButton(onClick = { UiSound.notification() }) { Text("Tes suara notifikasi") }
                Text("Sumber: Kenney Interface Sounds · CC0 1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            GeminiSettingsCard(scope)

            GlassCard {
                SectionTitle("Berjalan di latar belakang")
                SwitchRow("Wake lock CPU", "Menjaga MCC tetap berjalan saat layar mati.", AppPrefs.wakeLock) { AppPrefs.wakeLock = it }
                SwitchRow("Wi-Fi lock", "Menjaga Wi-Fi tetap aktif.", AppPrefs.wifiLock) { AppPrefs.wifiLock = it }
                SwitchRow("Mulai saat perangkat menyala", "Profil bertanda 'mulai otomatis' dijalankan.", AppPrefs.bootStart) { AppPrefs.bootStart = it }
                SliderRow(
                    "Batas heap .NET", "0 = otomatis. Batasi bila perangkat RAM kecil.", AppPrefs.heapLimitMb.toFloat(), 0f..2048f,
                    "MB", steps = 15,
                ) { AppPrefs.heapLimitMb = (it / 128).toInt() * 128 }
                OutlinedButton(onClick = {
                    val pm = ctx.getSystemService(PowerManager::class.java)
                    val i = if (pm.isIgnoringBatteryOptimizations(ctx.packageName))
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
                    try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { toast(ctx, "Tidak bisa membuka pengaturan baterai") }
                }) { Text("Nonaktifkan optimasi baterai") }
                if (Build.VERSION.SDK_INT >= 31) {
                    Text(
                        "Android 12+ dapat mematikan proses anak (phantom process killer) saat memori menipis. Bila MCC sering mati sendiri, " +
                            "jalankan sekali lewat adb: adb shell \"settings put global settings_enable_monitor_phantom_procs false\" " +
                            "(Android 14+: Opsi pengembang → Nonaktifkan batasan proses turunan).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            GlassCard {
                SectionTitle("Runtime MCC")
                when (val s = rt) {
                    is RtState.Checking -> Text("Memeriksa…")
                    is RtState.Missing -> Text("Tidak ada: ${s.reason}", color = MaterialTheme.colorScheme.error)
                    is RtState.Installing -> {
                        Text(s.message)
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.padding(top = 6.dp))
                    }
                    is RtState.Ready -> Text("Siap · ${"MCC " + (s.info["mccRef"] ?: "?") + " (" + (s.info["mccCommit"]?.toString()?.take(7) ?: "?") + ")"}")
                    is RtState.Failed -> Text("Gagal: ${s.message}", color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { RuntimeInstaller.reinstall(ctx) }) { Text("Pasang ulang") }
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            diag = withContext(Dispatchers.IO) { Diagnostics.run(ctx) }
                            busy = false
                        }
                    }) { Text(if (busy) "Memeriksa…" else "Diagnostik") }
                }
            }

            GlassCard {
                SectionTitle("Tentang")
                Text("MCC Droid · MCC dijalankan native di Android (linux-bionic arm64).", style = MaterialTheme.typography.bodySmall)
                Text("Minecraft Console Client © MCCTeam, lisensi CDDL-1.0.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    diag?.let { text ->
        AlertDialog(
            onDismissRequest = { diag = null },
            title = { Text("Diagnostik") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { copyToClipboard(ctx, "Diagnostik", text) }) { Text("Salin") } },
            dismissButton = { TextButton(onClick = { diag = null }) { Text("Tutup") } },
        )
    }
}

@Composable
private fun GeminiSettingsCard(scope: kotlinx.coroutines.CoroutineScope) {
    var key by remember { mutableStateOf(GeminiStore.apiKey) }
    var menu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    GlassCard(accent = MaterialTheme.colorScheme.primary) {
        SectionTitle("Gemini AI")
        Text(
            "API key disimpan terenkripsi Android Keystore. Daftar model diambil dari Google AI Studio.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("Gemini API key") },
            placeholder = { Text("AIza…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = key.isNotBlank(), onClick = {
                GeminiStore.setApiKey(key)
                message = "API key tersimpan aman."
            }) { Text("Simpan key") }
            OutlinedButton(enabled = key.isNotBlank() && !loading, onClick = {
                GeminiStore.setApiKey(key)
                loading = true
                message = null
                scope.launch {
                    try {
                        val models = withContext(Dispatchers.IO) { GeminiClient.listModels(key) }
                        GeminiStore.setModels(models)
                        message = "${models.size} model tersedia."
                    } catch (e: Exception) {
                        message = e.message ?: "Gagal mengambil model."
                    } finally {
                        loading = false
                    }
                }
            }) { Text(if (loading) "Memuat…" else "Muat model") }
        }
        if (GeminiStore.models.isNotEmpty()) {
            Box(Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { menu = true }) { Text("Model: ${GeminiStore.selectedModel}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (model in GeminiStore.models) {
                        DropdownMenuItem(
                            text = { Text(model.displayName.ifBlank { model.id }) },
                            onClick = { GeminiStore.selectedModel = model.id; menu = false },
                        )
                    }
                }
            }
        }
        message?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        TextButton(onClick = { /* API key dihapus tanpa menampilkan kembali nilainya. */ key = ""; GeminiStore.setApiKey("") }) { Text("Hapus API key") }
    }
}

private fun soundLabel(id: String): String = mapOf(
    "neo" to "Neo · clean modern", "soft" to "Soft · warm tactile", "glass" to "Glass · crystalline",
    "tech" to "Tech · digital crisp", "minimal" to "Minimal · quiet precise", "pulse" to "Pulse · rhythmic UI",
    "airy" to "Airy · light spacious", "carbon" to "Carbon · deep sharp", "pixel" to "Pixel · arcade crisp",
    "chime" to "Chime · bright melodic", "velvet" to "Velvet · smooth muted", "spark" to "Spark · energetic bright",
    "orbit" to "Orbit · swirling interactive", "mono" to "Mono · focused neutral", "bloom" to "Bloom · soft expanding",
)[id] ?: id

private fun soundDescription(id: String): String = mapOf(
    "neo" to "clean modern", "soft" to "warm dan tactile", "glass" to "crystalline", "tech" to "digital crispy",
    "minimal" to "quiet dan precise", "pulse" to "rhythmic dengan tail pendek", "airy" to "ringan dan spacious",
    "carbon" to "deep dengan attack tajam", "pixel" to "arcade high-pitch", "chime" to "bright melodic",
    "velvet" to "smooth dan muted", "spark" to "energetic bright", "orbit" to "swirling interaktif",
    "mono" to "focused neutral", "bloom" to "soft expanding",
)[id] ?: "custom"
