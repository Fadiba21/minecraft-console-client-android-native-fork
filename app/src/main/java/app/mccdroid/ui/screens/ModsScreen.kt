@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.mccdroid.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mccdroid.core.Paths
import app.mccdroid.core.ProfileStore
import app.mccdroid.ui.Nav
import app.mccdroid.ui.components.GlassCard
import app.mccdroid.ui.components.PrimaryButton
import app.mccdroid.ui.components.ScreenHeader
import app.mccdroid.ui.components.SectionTitle
import app.mccdroid.ui.components.copyToClipboard
import app.mccdroid.ui.components.formatSize
import app.mccdroid.ui.components.toast
import java.io.File

private const val SCRIPT_TEMPLATE = """//MCCScript 1.0

MCC.LoadBot(new MyBot());

//MCCScript Extensions

class MyBot : ChatBot
{
    public override void Initialize()
    {
        LogToConsole("MyBot dimuat!");
    }

    public override void GetText(string text)
    {
        string message = "", username = "";
        text = GetVerbatim(text);
        if (IsPrivateMessage(text, ref message, ref username) && message == "ping")
            SendPrivateMessage(username, "pong");
    }
}
"""

private val LAUNCHERS = listOf(
    "PojavLauncher" to "net.kdt.pojavlaunch",
    "Zalith Launcher" to "com.movtery.zalithlauncher",
    "FCL" to "com.tungsten.fcl",
)

@Composable
fun ModsScreen(nav: Nav) {
    val ctx = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    val mods = remember(rev) { Paths.modsDir(ctx).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList() }
    val scripts = remember(rev) { Paths.scriptsDir(ctx).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList() }
    val profiles by ProfileStore.profiles.collectAsState()

    val importJar = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (u in uris) {
            try {
                val name = ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "mod.jar"
                ctx.contentResolver.openInputStream(u)?.use { i -> File(Paths.modsDir(ctx), name.replace('/', '_')).outputStream().use { o -> i.copyTo(o) } }
            } catch (e: Exception) {
                toast(ctx, "Gagal impor: ${e.message}")
            }
        }
        rev++
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Mod & skrip", actions = {
            IconButton(onClick = { nav.sub = null }) { Icon(Icons.Rounded.ArrowBack, "Kembali") }
        })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard(accent = MaterialTheme.colorScheme.secondary) {
                Text("Soal mod Fabric", fontWeight = FontWeight.Bold)
                Text(
                    "MCC adalah klien konsol, bukan Minecraft Java penuh, sehingga JAR Fabric tidak dimuat langsung. " +
                        "Untuk ChatGameAI, logikanya sudah dipindahkan ke engine MCC Droid dan dapat diatur pada panel di bawah. " +
                        "Mod Fabric lain tetap disimpan sebagai file .jar dan dapat dibuka melalui launcher Minecraft Java.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ChatGameAiPanel()

            GlassCard {
                SectionTitle("File mod (.jar)")
                if (mods.isEmpty()) Text("Belum ada.", style = MaterialTheme.typography.bodySmall)
                for (m in mods) Row {
                    Text(m.name, Modifier.weight(1f))
                    Text(formatSize(m.length()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PrimaryButton("Impor mod", modifier = Modifier.padding(top = 8.dp)) { importJar.launch(arrayOf("*/*")) }
            }

            GlassCard {
                SectionTitle("Peluncur Minecraft")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, pkg) in LAUNCHERS) {
                        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                        OutlinedButton(enabled = intent != null, onClick = { ctx.startActivity(intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                            Text(if (intent != null) label else "$label (belum terpasang)")
                        }
                    }
                }
            }

            GlassCard {
                SectionTitle("Skrip MCC (.cs)")
                if (scripts.isEmpty()) Text("Belum ada skrip.", style = MaterialTheme.typography.bodySmall)
                for (s in scripts) Column(Modifier.padding(vertical = 4.dp)) {
                    Text(s.name, fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (p in profiles) OutlinedButton(onClick = {
                            try {
                                s.copyTo(File(Paths.profileDir(ctx, p.id), s.name), overwrite = true)
                                toast(ctx, "Disalin ke ${p.name}. Jalankan: /script ${s.name}")
                            } catch (e: Exception) {
                                toast(ctx, "Gagal: ${e.message}")
                            }
                        }) { Text("Salin ke ${p.name}") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    PrimaryButton("Buat contoh skrip") {
                        val f = File(Paths.scriptsDir(ctx), "mybot.cs")
                        if (!f.exists()) f.writeText(SCRIPT_TEMPLATE)
                        rev++
                    }
                    OutlinedButton(onClick = { copyToClipboard(ctx, "Template", SCRIPT_TEMPLATE) }) { Text("Salin template") }
                }
            }
        }
    }
}
