package site.sexyminup.p2pfileshare.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFFA85B0D),
    onPrimary = Color(0xFF241306),
    secondary = Color(0xFF0F766E),
    tertiary = Color(0xFFF4B63C),
    background = Color(0xFFFBF6EB),
    surface = Color(0xFFFFFAF0),
    surfaceVariant = Color(0xFFF1E2C8),
    onSurface = Color(0xFF18140D),
    onSurfaceVariant = Color(0xFF6F665A),
    error = Color(0xFFB3261E),
)

@Composable
fun P2PFileShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
