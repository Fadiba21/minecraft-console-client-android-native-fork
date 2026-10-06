package app.mccdroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mccdroid.ui.Nav
import app.mccdroid.ui.Sub
import app.mccdroid.ui.components.GlassCard
import app.mccdroid.ui.components.ScreenHeader

@Composable
fun MoreScreen(nav: Nav) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Lainnya")
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Item(Icons.Rounded.Folder, "File & folder", "Lihat, sunting, impor/ekspor file MCC, buka di aplikasi File") { nav.sub = Sub.FILES }
            Item(Icons.Rounded.Notifications, "Notifikasi", "Kick, putus, login, pesan pribadi, jam tenang") { nav.sub = Sub.NOTIFY }
            Item(Icons.Rounded.Extension, "Mod & skrip", "Skrip C# MCC, file mod Fabric, peluncur Minecraft") { nav.sub = Sub.MODS }
            Item(Icons.Rounded.Bolt, "Otomasi", "Balas chat, kirim command, dan jalankan aksi otomatis") { nav.sub = Sub.AUTOMATION }
            Item(Icons.Rounded.Settings, "Pengaturan", "Tema, baterai, runtime, diagnostik") { nav.sub = Sub.SETTINGS }
        }
    }
}

@Composable
private fun Item(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    GlassCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
