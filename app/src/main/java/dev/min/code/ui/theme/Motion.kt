package dev.min.code.ui.theme

import android.provider.Settings
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * 动效语汇。全 App 只有这几种运动，名字来自笔墨：
 *
 * - **落墨**（[Ease]）：快起慢收。出现、展开、位移都用它——墨落纸上是瞬间的，洇开是慢的。
 * - **飞白**（[DryEase]）：起笔即走。消失用它，短而干脆，不拖泥带水。
 * - 空间属性（位置、尺寸、缩放）走弹簧（[spatial]），稍欠阻尼、几乎不过冲——纸是有分量的；
 *   效果属性（透明度、颜色）走 tween（[effect]），弹簧对颜色没有意义。
 *
 * 时长三档：快 140 / 常 260 / 慢 520 ms。比 Material 略快一点：这是工具，不是舞台。
 */
object InkMotion {
    val Ease: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val DryEase: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    fun <T> spatialFast(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.86f, stiffness = 1100f)
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.82f, stiffness = 480f)
    fun <T> spatialSlow(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 220f)

    fun <T> effectFast(): FiniteAnimationSpec<T> = tween(140, easing = Ease)
    fun <T> effect(): FiniteAnimationSpec<T> = tween(260, easing = Ease)
    fun <T> effectSlow(): FiniteAnimationSpec<T> = tween(520, easing = Ease)

    /** 出现：淡入 + 从 96% 落到 100%（落墨） */
    val enter: EnterTransition get() = fadeIn(effect()) + scaleIn(initialScale = 0.96f, animationSpec = spatial())

    /** 消失：淡出 + 微缩（飞白） */
    val exit: ExitTransition get() = fadeOut(effectFast()) + scaleOut(targetScale = 0.98f, animationSpec = effectFast())

    /** 从上往下洇开 */
    val expand: EnterTransition get() = fadeIn(effect()) + expandVertically(animationSpec = spatial(), expandFrom = Alignment.Top)
    val collapse: ExitTransition get() = fadeOut(effectFast()) + shrinkVertically(animationSpec = spatial(), shrinkTowards = Alignment.Top)

    /** 从下方浮起（toast、横幅） */
    val rise: EnterTransition get() = fadeIn(effect()) + androidx.compose.animation.slideInVertically(animationSpec = spatial()) { it / 3 }
    val sink: ExitTransition get() = fadeOut(effectFast()) + androidx.compose.animation.slideOutVertically(animationSpec = effectFast()) { it / 3 }
}

/**
 * 按下时整体缩到 [pressedScale]，松开弹回。所有按钮共用——纸被手指压了一下。
 * 只做缩放，不做阴影：这套界面没有海拔。
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.97f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = InkMotion.spatialFast(),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 系统动画是否开着。关掉动画（开发者选项 / 省电 / 无障碍）时所有循环动效退化成静态，
 * 不能无视用户的系统级选择。
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun readEnabled() =
        runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
    var enabled by remember(resolver) { mutableStateOf(readEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = readEnabled()
            }
        }
        val registered = runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }.isSuccess
        enabled = readEnabled()
        onDispose {
            if (registered) resolver.unregisterContentObserver(observer)
        }
    }
    return enabled
}
