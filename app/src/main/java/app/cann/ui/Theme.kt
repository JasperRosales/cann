package app.cann.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

val Amber = Color(0xFFFFB547)
val Mono = FontFamily.Monospace

@Composable
fun CannTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Amber, onPrimary = Color(0xFF231600),
        background = Color(0xFF101114), surface = Color(0xFF101114),
        surfaceContainer = Color(0xFF1A1C21), surfaceContainerHigh = Color(0xFF22252B),
        secondaryContainer = Color(0xFF3A2D12), onSecondaryContainer = Amber,
        error = Color(0xFFFF6B6B),
    ),
    content = content,
)
