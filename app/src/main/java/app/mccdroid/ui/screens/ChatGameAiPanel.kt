package app.mccdroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mccdroid.core.ChatGameAi
import app.mccdroid.core.ChatGameAiConfig
import app.mccdroid.ui.components.GlassCard
import app.mccdroid.ui.components.PrimaryButton
import app.mccdroid.ui.components.SectionTitle
import app.mccdroid.ui.components.toast
import androidx.compose.ui.platform.LocalContext

@Composable
fun ChatGameAiPanel() {
    val ctx = LocalContext.current
    val revision by ChatGameAi.revision
    val c = ChatGameAi.config
    var prompt by remember(revision) { mutableStateOf(c.systemPrompt) }
    GlassCard(accent = androidx.compose.material3.MaterialTheme.colorScheme.primary) {
        SectionTitle("ChatGameAI — port MCC")
        Text("Menjawab kuis Chat Game dari console MCC menggunakan solver lokal, cache, atau Gemini. Aktifkan hanya pada profil/server yang memang memakai kuis.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        ToggleRow("Aktif", c.enabled) { ChatGameAi.update { it.copy(enabled = !it.enabled) } }
        OutlinedTextField(c.triggerKeyword, { value -> ChatGameAi.update { cfg -> cfg.copy(triggerKeyword = value) } }, label = { Text("Kata pemicu soal") }, supportingText = { Text("Contoh: soal, question, quiz. Kosong = hanya deteksi matematika.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Jenis jawaban", fontWeight = FontWeight.Bold)
        ToggleRow("Umum / Gemini", c.answerGeneral) { ChatGameAi.update { it.copy(answerGeneral = !it.answerGeneral) } }
        ToggleRow("Matematika + solver lokal", c.answerMath) { ChatGameAi.update { it.copy(answerMath = !it.answerMath) } }
        ToggleRow("Ketik kata", c.answerType) { ChatGameAi.update { it.copy(answerType = !it.answerType) } }
        ToggleRow("Acak huruf / sandi", c.answerScramble) { ChatGameAi.update { it.copy(answerScramble = !it.answerScramble) } }
        ToggleRow("Deteksi matematika tanpa kata pemicu", c.detectMathWithoutTag) { ChatGameAi.update { it.copy(detectMathWithoutTag = !it.detectMathWithoutTag) } }
        ToggleRow("Abaikan chat pemain biasa", c.ignorePlayerChat) { ChatGameAi.update { it.copy(ignorePlayerChat = !it.ignorePlayerChat) } }
        ToggleRow("Gunakan cache jawaban", c.useCache) { ChatGameAi.update { it.copy(useCache = !it.useCache) } }
        ToggleRow("Simpan jawaban AI sebelum konfirmasi server", c.saveUnverified) { ChatGameAi.update { it.copy(saveUnverified = !it.saveUnverified) } }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Delay per kategori (milidetik)", fontWeight = FontWeight.Bold)
        NumberField("Umum", c.delayGeneralMs) { value -> ChatGameAi.update { cfg -> cfg.copy(delayGeneralMs = value.coerceIn(0, 60000)) } }
        NumberField("Matematika", c.delayMathMs) { value -> ChatGameAi.update { cfg -> cfg.copy(delayMathMs = value.coerceIn(0, 60000)) } }
        NumberField("Ketik", c.delayTypeMs) { value -> ChatGameAi.update { cfg -> cfg.copy(delayTypeMs = value.coerceIn(0, 60000)) } }
        NumberField("Sandi", c.delayScrambleMs) { value -> ChatGameAi.update { cfg -> cfg.copy(delayScrambleMs = value.coerceIn(0, 60000)) } }
        ToggleRow("Delay acak sampai batas maksimum", c.randomDelay) { ChatGameAi.update { it.copy(randomDelay = !it.randomDelay) } }
        if (c.randomDelay) NumberField("Batas maksimum delay", c.randomDelayMaxMs) { value -> ChatGameAi.update { cfg -> cfg.copy(randomDelayMaxMs = value.coerceIn(0, 60000)) } }
        NumberField("Cooldown antar soal", c.cooldownMs) { value -> ChatGameAi.update { cfg -> cfg.copy(cooldownMs = value.coerceIn(0, 120000)) } }
        NumberField("Batas kata jawaban", c.maxWords) { value -> ChatGameAi.update { cfg -> cfg.copy(maxWords = value.coerceIn(0, 20)) } }
        OutlinedTextField(c.ignoredCategories, { value -> ChatGameAi.update { cfg -> cfg.copy(ignoredCategories = value) } }, label = { Text("Kategori yang diabaikan") }, supportingText = { Text("Pisahkan dengan koma") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Konfirmasi pemenang dan output", fontWeight = FontWeight.Bold)
        OutlinedTextField(c.winVerbs, { value -> ChatGameAi.update { cfg -> cfg.copy(winVerbs = value) } }, label = { Text("Kata penanda pemenang") }, supportingText = { Text("Contoh: answered, solved, benar, menang") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(c.revealRegex, { value -> ChatGameAi.update { cfg -> cfg.copy(revealRegex = value) } }, label = { Text("Regex reveal custom (opsional)") }, supportingText = { Text("Grup tangkap pertama menjadi jawaban server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ToggleRow("Notifikasi hasil", c.notifyEnabled) { ChatGameAi.update { it.copy(notifyEnabled = !it.notifyEnabled) } }
        ToggleRow("Tulis status ke console MCC", c.showInfoInConsole) { ChatGameAi.update { it.copy(showInfoInConsole = !it.showInfoInConsole) } }
        OutlinedTextField(prompt, { prompt = it }, label = { Text("System prompt Gemini") }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 180.dp), maxLines = 6)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            PrimaryButton("Simpan prompt") { ChatGameAi.update { it.copy(systemPrompt = prompt) } }
            OutlinedButton(onClick = { ChatGameAi.clearCache(); toast(ctx, "Cache jawaban dihapus") }) { Text("Hapus cache") }
            OutlinedButton(onClick = { ChatGameAi.reset(); toast(ctx, "Pengaturan ChatGameAI direset") }) { Text("Reset") }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Statistik: ${ChatGameAi.stats.total} soal · ${ChatGameAi.stats.won} menang (${ChatGameAi.stats.winRate}%) · lokal ${ChatGameAi.stats.local} · cache ${ChatGameAi.stats.cache} · AI ${ChatGameAi.stats.ai} · rata-rata AI ${ChatGameAi.stats.avgAiMs} ms", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        Text("API key dan model memakai pengaturan AI aplikasi yang sudah ada.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, { onToggle() })
    }
}

@Composable
private fun NumberField(label: String, value: Int, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { text = it.filter(Char::isDigit); onValue(text.toIntOrNull() ?: 0) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
