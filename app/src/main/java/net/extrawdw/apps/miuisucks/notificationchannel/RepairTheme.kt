package net.extrawdw.apps.miuisucks.notificationchannel

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5260CC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE2FF),
    onPrimaryContainer = Color(0xFF182568),
    background = Color(0xFFF8F9FF),
    surface = Color(0xFFF8F9FF),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF202D79),
    primaryContainer = Color(0xFF39479F),
    onPrimaryContainer = Color(0xFFDFE2FF),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
)

@Composable
internal fun RepairTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
