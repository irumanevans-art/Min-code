package dev.min.code.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.min.code.ui.theme.sea

/**
 * 会话页铬件后面那一层。
 *
 * ChatGPT 顶底不是高斯糊：从圆钮下沿 / 胶囊上沿起，越靠近屏沿，
 * 会话和花纹越被纸色盖住，接到底色。糊只留给圆钮自己（[frostPane]）。
 *
 * 没有后台模糊时这条纸色渐隐本身就是效果，不会变成一块实纸。
 */
val LocalFrost = compositionLocalOf<HazeState?> { null }

/**
 * 纸色在铬件沿（圆钮下沿 / 胶囊上沿）之外只留这一截接到 0。
 * 切线以下正文是原色；再长就是把字洗成色块。
 */
val FrostFade = 8.dp

@Composable
fun rememberFrostState(): HazeState = rememberHazeState()

fun Modifier.frostSource(state: HazeState): Modifier = hazeSource(state = state)

@Composable
internal fun paperFrostStyle(
    blurRadius: Dp,
    tintAlpha: Float,
    fill: Color,
    fallbackAlpha: Float,
): HazeStyle {
    return HazeStyle(
        backgroundColor = fill.copy(alpha = 0f),
        tints = listOf(HazeTint(fill.copy(alpha = tintAlpha))),
        blurRadius = blurRadius,
        noiseFactor = 0.03f,
        fallbackTint = HazeTint(fill.copy(alpha = fallbackAlpha)),
    )
}

/**
 * 顶 / 底那条纸色。节点要比铬件更高：多出来的 [FrostFade] 伸进会话，
 * 把纸色收到 0，才没有硬边。
 *
 * [hold] 是铬件在整条带里占的比例（0..1）。切线处纸色是 0，
 * 越靠近屏沿越接近底色——字、轨道、花纹一起被洗过去。
 */
@Composable
fun Modifier.frostVeil(
    fromTop: Boolean,
    hold: Float = 0.55f,
    fill: Color = MaterialTheme.colorScheme.surface,
): Modifier {
    val dark = MaterialTheme.sea.dark
    // 屏沿几乎就是纸；铬件里字还是残影（GPT 那行 mid-2026?）；
    // 切线处收到 0，正文立刻是原色。
    val edge = if (dark) 0.96f else 0.97f
    val upper = if (dark) 0.86f else 0.88f
    val h = hold.coerceIn(0.15f, 0.95f)
    // 收到 0 的位置不能放在切线（圆钮下沿 / 胶囊上沿）上：正文从状态栏下方就开始了，
    // 和切线之间隔着整行按钮。按切线收，滚到顶时第一条的头一两行就被洗成浅影。
    // 收到按钮行中线附近，屏沿那一端仍托住坐在圆钮之间的字。
    val scrim = fadeScrim(fromTop, h, fill, edge, upper)
    return drawBehind { drawRect(scrim) }
    // 没有 pointerInput：Compose 命中测试会穿过这层，点到下面的会话。
    // 菜单 / 输入胶囊是叠在前面的兄弟，命中先到它们。
}

/**
 * 均匀的一扇雾：顶栏圆钮、输入胶囊。ChatGPT 那两粒白圆是这个。
 * 只盖按钮自己，不往正文铺。
 */
@Composable
fun Modifier.frostPane(
    state: HazeState,
    blurRadius: Dp = 12.dp,
    tintAlpha: Float = if (MaterialTheme.sea.dark) 0.28f else 0.22f,
    fill: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
): Modifier {
    val fallback = if (MaterialTheme.sea.dark) 0.42f else 0.55f
    val style = paperFrostStyle(blurRadius, tintAlpha, fill, fallback)
    return hazeEffect(state = state, style = style)
}

private fun fadeScrim(
    fromTop: Boolean,
    hold: Float,
    fill: Color,
    edge: Float,
    upper: Float,
): Brush {
    val stops = arrayOf(
        0f to fill.copy(alpha = edge),
        (hold * 0.30f) to fill.copy(alpha = upper),
        (hold * 0.55f) to Color.Transparent,
        1f to Color.Transparent,
    )
    return if (fromTop) Brush.verticalGradient(colorStops = stops)
    else Brush.verticalGradient(colorStops = stops.reversedArray())
}

private fun Array<Pair<Float, Color>>.reversedArray(): Array<Pair<Float, Color>> =
    Array(size) { i ->
        val (stop, color) = this[lastIndex - i]
        1f - stop to color
    }
