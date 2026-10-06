package app.mccdroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mccdroid.core.Paths
import app.mccdroid.core.ShellSession
import app.mccdroid.ui.Nav
import app.mccdroid.ui.components.ScreenHeader

@Composable
fun ShellScreen(nav: Nav) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cwd = remember { Paths.dataRoot(ctx) }
    val lines by ShellSession.log.flow.collectAsState()
    val list = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { ShellSession.ensure(ctx, cwd) }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    fun send() { val t = input.trim(); if (t.isNotEmpty()) { ShellSession.run(ctx, cwd, t); input = "" } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Shell Android", "sh · advanced", actions = { IconButton(onClick = { nav.sub = null }) { Icon(Icons.Rounded.ArrowBack, "Kembali") } })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(lines, key = { it.id }) { line -> Text(line.clean, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface) }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Perintah shell") }, singleLine = true, shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            IconButton(onClick = { send() }, enabled = input.isNotBlank()) { Icon(Icons.Rounded.Send, "Kirim") }
        }
    }
}
