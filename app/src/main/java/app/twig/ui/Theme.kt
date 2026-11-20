package app.twig.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.twig.data.Store

/** Bullet text (bodyLarge) follows the text size (in dp, so it ignores the system font scale) and font settings. */
@Composable
fun TwigTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = remember(dark) {
        when {
            Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            else -> if (dark) darkColorScheme() else lightColorScheme()
        }
    }
    val size = Store.textSize
    val mono = Store.monospace
    val density = LocalDensity.current
    val type = remember(size, mono, density) {
        val t = Typography()
        with(density) {
            t.copy(
                bodyLarge = t.bodyLarge.copy(
                    fontSize = size.dp.toSp(),
                    lineHeight = (size * 1.5f).dp.toSp(),
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
                ),
            )
        }
    }
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
