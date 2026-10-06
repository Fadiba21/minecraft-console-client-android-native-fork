@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.mccdroid.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mccdroid.core.NotifyStore
import app.mccdroid.core.Notifier
import app.mccdroid.logic.EventPref
import app.mccdroid.logic.Level
import app.mccdroid.logic.McEvent
import app.mccdroid.logic.NotifyLogic
import app.mccdroid.ui.Nav
import app.mccdroid.ui.components.ChoiceChips
import app.mccdroid.ui.components.GlassCard
import app.mccdroid.ui.components.ScreenHeader
import app.mccdroid.ui.components.SectionTitle
import app.mccdroid.ui.components.SliderRow
import app.mccdroid.ui.components.StringListEditor
import app.mccdroid.ui.components.SwitchRow

private fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)

@Composable
fun NotificationsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val cfg by NotifyStore.config.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Notifikasi", "Atur peringatan untuk tiap kejadian", actions = {
            IconButton(onClick = { nav.sub = null }) { Icon(Icons.Rounded.ArrowBack, "Kembali") }
        })
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassCard {
                SectionTitle("Per kejadian")
                for ((i, ev) in McEvent.values().withIndex()) {
                    val p = cfg.prefs[ev] ?: NotifyLogic.defaults()[ev] ?: EventPref(Level.NORMAL)
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Text(ev.label, fontWeight = FontWeight.SemiBold)
                    ChoiceChips(Level.values().toList(), p.level, { it.label }) {
                        NotifyStore.update(cfg.copy(prefs = cfg.prefs + (ev to p.copy(level = it))))
                    }
                    if (p.level != Level.OFF) {
                        SwitchRow("Getar", null, p.vibrate) {
                            NotifyStore.update(cfg.copy(prefs = cfg.prefs + (ev to p.copy(vibrate = it))))
                        }
                        SwitchRow("Izinkan balas dari notifikasi", "Mengirim teks langsung ke console MCC profil ini.", p.reply) {
                            NotifyStore.update(cfg.copy(prefs = cfg.prefs + (ev to p.copy(reply = it))))
                        }
                        SliderRow("Jeda minimal antar notifikasi", null, p.cooldownSec.toFloat(), 0f..300f, "dtk") {
                            NotifyStore.update(cfg.copy(prefs = cfg.prefs + (ev to p.copy(cooldownSec = it.toInt()))))
                        }
                    }
                }
            }

            GlassCard {
                SectionTitle("Jam tenang")
                SwitchRow("Aktifkan jam tenang", "Notifikasi non-kritis ditahan pada rentang waktu ini.", cfg.quietEnabled) {
                    NotifyStore.update(cfg.copy(quietEnabled = it))
                }
                if (cfg.quietEnabled) {
                    SliderRow("Mulai", null, cfg.quietStartMin.toFloat(), 0f..1425f, steps = 94, format = { fmt(it.toInt()) }) {
                        NotifyStore.update(cfg.copy(quietStartMin = (it / 15).toInt() * 15))
                    }
                    SliderRow("Selesai", null, cfg.quietEndMin.toFloat(), 0f..1425f, steps = 94, format = { fmt(it.toInt()) }) {
                        NotifyStore.update(cfg.copy(quietEndMin = (it / 15).toInt() * 15))
                    }
                    SwitchRow("Tetap tampilkan yang kritis", "Kick, putus koneksi, login gagal, crash.", cfg.quietAllowCritical) {
                        NotifyStore.update(cfg.copy(quietAllowCritical = it))
                    }
                }
            }

            GlassCard {
                SectionTitle("Kata kunci")
                StringListEditor(
                    "Kata yang memicu notifikasi", "Jika muncul di chat, kejadian 'Kata kunci terdeteksi' dipicu.",
                    cfg.keywords,
                ) { NotifyStore.update(cfg.copy(keywords = it)) }
            }

            GlassCard {
                SectionTitle("Uji & sistem")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Notifier.test(ctx, Level.NORMAL, false) }) { Text("Tes normal") }
                    OutlinedButton(onClick = { Notifier.test(ctx, Level.HIGH, true) }) { Text("Tes penting") }
                }
                OutlinedButton(
                    modifier = Modifier.padding(top = 8.dp),
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                ) { Text("Pengaturan notifikasi sistem") }
            }
        }
    }
}
