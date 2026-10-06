package app.mccdroid.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mccdroid.core.DeviceCode
import app.mccdroid.core.UiSound
import app.mccdroid.ui.StateBusy

fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    UiSound.copy()
    Toast.makeText(ctx, "Disalin", Toast.LENGTH_SHORT).show()
}

fun openUrl(ctx: Context, url: String) {
    try {
        UiSound.click()
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        UiSound.alert()
        Toast.makeText(ctx, "Tidak bisa membuka tautan", Toast.LENGTH_SHORT).show()
    }
}

fun toast(ctx: Context, msg: String) {
    UiSound.success()
    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%dj %02dm %02dd".format(h, m, sec) else if (m > 0) "%dm %02dd".format(m, sec) else "%dd".format(sec)
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%.2f GB".format(bytes / 1073741824.0)
}

/** Kartu bantuan login Microsoft (device code) yang muncul saat MCC meminta masuk. */
@Composable
fun DeviceCodeCard(dc: DeviceCode) {
    val ctx = LocalContext.current
    GlassCard(accent = StateBusy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Key, null, Modifier.size(20.dp), tint = StateBusy)
            Text("  Login Microsoft diperlukan", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            dc.code,
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Buka tautan, masukkan kode di atas, lalu setujui login. MCC akan melanjutkan otomatis.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copyToClipboard(ctx, "Kode login", dc.code) }) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Text("  Salin kode")
            }
            OutlinedButton(onClick = { openUrl(ctx, dc.url) }) {
                Icon(Icons.Rounded.Link, null, Modifier.size(16.dp)); Text("  Buka tautan")
            }
        }
    }
}
