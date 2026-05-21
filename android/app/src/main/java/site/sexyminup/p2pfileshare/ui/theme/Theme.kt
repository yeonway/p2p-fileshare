package site.sexyminup.p2pfileshare.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF146C75),
    secondary = Color(0xFF6A5A00),
    tertiary = Color(0xFF7A3E3E),
    background = Color(0xFFF6F8F9),
    surface = Color(0xFFFFFFFF),
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
