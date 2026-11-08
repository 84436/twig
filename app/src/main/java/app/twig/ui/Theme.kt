package app.twig.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF3A6A4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBCEFCD),
    onPrimaryContainer = Color(0xFF002112),
    secondary = Color(0xFF4F6355),
    tertiary = Color(0xFF3B6470),
    background = Color(0xFFFCF8F6),
    surface = Color(0xFFFCF8F6),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F2F0),
    surfaceContainer = Color(0xFFF0ECEA),
    surfaceContainerHigh = Color(0xFFEAE7E4),
    surfaceContainerHighest = Color(0xFFE5E1DF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA1D3B2),
    onPrimary = Color(0xFF053823),
    primaryContainer = Color(0xFF225138),
    onPrimaryContainer = Color(0xFFBCEFCD),
    secondary = Color(0xFFB6CCBA),
    tertiary = Color(0xFFA3CDDB),
    background = Color(0xFF141314),
    surface = Color(0xFF141314),
    surfaceContainerLowest = Color(0xFF0E0E0F),
    surfaceContainerLow = Color(0xFF1C1B1C),
    surfaceContainer = Color(0xFF201F20),
    surfaceContainerHigh = Color(0xFF2B2A2A),
    surfaceContainerHighest = Color(0xFF363435),
)

@Composable
fun TwigTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = remember(dark) {
        when {
            Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            dark -> Dark
            else -> Light
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
