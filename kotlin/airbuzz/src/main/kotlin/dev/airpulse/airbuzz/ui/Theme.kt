package dev.airpulse.airbuzz.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF00696E), onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF0F6), onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF4A6365), tertiary = Color(0xFF8B5000),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF80D4DA), onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF004F53), onPrimaryContainer = Color(0xFF9CF0F6),
    secondary = Color(0xFFB1CBCE), tertiary = Color(0xFFFFB86E),
)

@Composable
fun AirBuzzTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Status colours that read the same with dynamic colour. */
object StatusColors {
    val below = Color(0xFF1E88E5)
    val inZone = Color(0xFF2E7D32)
    val above = Color(0xFFD84315)
}
