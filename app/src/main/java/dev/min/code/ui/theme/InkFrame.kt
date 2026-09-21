package dev.min.code.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/**
 * 一块本地的暗材质：终端、代码那类**自带暗底**的东西，不跟页面的昼夜走，
 * 也不碰系统栏。
 *
 * 但它**跟着风格走** —— 用的是当前风格自己的夜色。写死成海的夜蓝的话，
 * 一个用陶土风格的人会在暖纸上看到一块冷蓝的终端，那不是「独立材质」，
 * 那是漏进来的另一套配色。
 */
@Composable
fun InkFrame(content: @Composable () -> Unit) {
    val night = LocalSkin.current.dark
    val scheme = MaterialTheme.colorScheme.copy(
        primary = night.sea,
        onPrimary = night.paper,
        surface = night.paper2,
        onSurface = night.ink,
        onSurfaceVariant = night.graphite,
        surfaceContainerLow = night.paper2,
        surfaceContainer = night.paper3,
        surfaceContainerHigh = night.paper4,
        surfaceContainerHighest = night.paperBright,
        outline = night.graphiteLight,
        outlineVariant = night.rule,
    )
    val indication = remember { InkIndication(true) }
    CompositionLocalProvider(
        LocalSea provides night,
        LocalDarkMode provides true,
        LocalContentColor provides night.ink,
        LocalIndication provides indication,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
