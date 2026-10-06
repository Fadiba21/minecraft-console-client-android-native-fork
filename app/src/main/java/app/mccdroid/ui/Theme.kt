package app.mccdroid.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.mccdroid.core.AppPrefs

val AccentPalette = listOf(
    Color(0xFF4ADE80), // hijau
    Color(0xFF38BDF8), // biru langit
    Color(0xFFA78BFA), // ungu
    Color(0xFFF472B6), // merah muda
    Color(0xFFFBBF24), // kuning
    Color(0xFFFB7185), // koral
)

val StateOnline = Color(0xFF4ADE80)
val StateBusy = Color(0xFFFBBF24)
val StateWarn = Color(0xFFFB923C)
val StateBad = Color(0xFFF87171)
val StateOff = Color(0xFF94A3B8)

@Composable
@ReadOnlyComposable
fun isDarkTheme(): Boolean = when (AppPrefs.themeMode) {
    "light" -> false
    "system" -> isSystemInDarkTheme()
    else -> true
}

@Composable
fun McTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isDarkTheme()
    val amoled = AppPrefs.themeMode == "amoled"
    val accent = AccentPalette[AppPrefs.accentIdx.coerceIn(0, AccentPalette.lastIndex)]

    var scheme = when {
        AppPrefs.dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF05210F),
            primaryContainer = accent.copy(alpha = 0.22f),
            onPrimaryContainer = accent,
            secondary = Color(0xFF7DD3FC),
            background = Color(0xFF0E1512),
            onBackground = Color(0xFFE6EFEA),
            surface = Color(0xFF131C18),
            onSurface = Color(0xFFE6EFEA),
            surfaceVariant = Color(0xFF1C2822),
            onSurfaceVariant = Color(0xFFA9B8B0),
            outline = Color(0xFF3B4A42),
            error = StateBad,
        )
        else -> lightColorScheme(
            primary = accent.copy(red = accent.red * 0.55f, green = accent.green * 0.7f, blue = accent.blue * 0.7f),
            secondary = Color(0xFF0369A1),
            background = Color(0xFFF4F8F5),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE5EEE8),
            error = Color(0xFFB91C1C),
        )
    }
    if (dark && amoled) {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color(0xFF070B09),
            surfaceVariant = Color(0xFF111A15),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun stateColor(s: app.mccdroid.core.SState): Color = when (s) {
    app.mccdroid.core.SState.ONLINE -> StateOnline
    app.mccdroid.core.SState.STARTING, app.mccdroid.core.SState.WAITING_LOGIN -> StateBusy
    app.mccdroid.core.SState.DISCONNECTED, app.mccdroid.core.SState.RECONNECTING -> StateWarn
    app.mccdroid.core.SState.CRASHED -> StateBad
    app.mccdroid.core.SState.STOPPED -> StateOff
}
