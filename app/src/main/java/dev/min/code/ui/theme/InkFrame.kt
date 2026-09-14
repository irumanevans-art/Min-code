package dev.min.code.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/** A local material, independent of the page theme and without system-bar side effects. */
@Composable
fun InkFrame(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme.copy(
        primary = DarkSea.sea,
        onPrimary = DarkSea.paper,
        surface = DarkSea.paper2,
        onSurface = DarkSea.ink,
        onSurfaceVariant = DarkSea.graphite,
        surfaceContainerLow = DarkSea.paper2,
        surfaceContainer = DarkSea.paper3,
        surfaceContainerHigh = DarkSea.paper4,
        surfaceContainerHighest = DarkSea.paperBright,
        outline = DarkSea.graphiteLight,
        outlineVariant = DarkSea.rule,
    )
    val indication = remember { InkIndication(true) }
    CompositionLocalProvider(
        LocalSea provides DarkSea,
        LocalDarkMode provides true,
        LocalContentColor provides DarkSea.ink,
        LocalIndication provides indication,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
