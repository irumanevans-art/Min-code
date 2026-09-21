package dev.min.code.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.min.code.core.settings.SkinStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** 一道细金边引出新纸色，遮盖时应用主题，再露出内容。 */
@Stable
class FormSwitchController {
    internal var request by mutableStateOf<FormSwitchRequest?>(null)

    /**
     * [origin] 是触发控件的窗口坐标；最新的选择可以替换尚未完成的过渡。
     *
     * 换昼夜和换风格走**同一个动作** —— 对用户来说它们是同一件事（界面整个变了个样），
     * 分成两种过渡只会让其中一种显得像 bug。所以这里收两个目标值，
     * 遮盖层照着它们去取新纸色、也照着它们判断「主题真的换过来了没有」。
     */
    fun switch(origin: Offset?, toDark: Boolean, toStyle: SkinStyle, apply: () -> Unit) {
        request?.applyOnce()
        request = FormSwitchRequest(origin, toDark, toStyle, apply)
    }
}

class FormSwitchRequest internal constructor(
    val origin: Offset?,
    val toDark: Boolean,
    val toStyle: SkinStyle,
    val apply: () -> Unit,
) {
    private var applied = false

    internal fun applyOnce() {
        if (applied) return
        applied = true
        apply()
    }
}

val LocalFormSwitch = staticCompositionLocalOf { FormSwitchController() }

@Composable
fun FormSwitchHost(controller: FormSwitchController, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        controller.request?.let { req ->
            key(req) {
                FormSwitchOverlay(req, onDone = {
                    if (controller.request === req) controller.request = null
                })
            }
        }
    }
}

@Composable
private fun FormSwitchOverlay(req: FormSwitchRequest, onDone: () -> Unit) {
    val dark by rememberUpdatedState(LocalDarkMode.current)
    val style by rememberUpdatedState(LocalSkin.current.style)
    val finish by rememberUpdatedState(onDone)
    val animations = rememberAnimationsEnabled()
    // 遮盖层推的是**目标形态**的纸色与强调色。以前这里写死成海，
    // 于是在云或陶的页面上切昼夜会闪出一道蓝边 —— 那道边本该是新形态的颜色
    val target = Skins.of(req.toStyle).palette(req.toDark)
    val reveal = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }

    // 离开页面或替换请求也必须写入选择，不能因为取消动画丢失主题设置。
    DisposableEffect(req) {
        onDispose {
            req.applyOnce()
            finish()
        }
    }
    LaunchedEffect(req, animations) {
        if (!animations) {
            req.applyOnce()
            finish()
            return@LaunchedEffect
        }
        reveal.animateTo(1f, tween(240, easing = InkMotion.Ease))
        req.applyOnce()
        // 等主题真的换过来再淡出。**两个维度都要等** —— 只等 dark 的话，
        // 单换风格（昼夜没变）时这一句立刻就满足了，遮盖层会在设置落盘之前就散开，
        // 用户看见旧颜色闪一下才变；而只等 style 同理。超时是兜底，正常走不到
        withTimeoutOrNull(900) {
            snapshotFlow { dark to style }.first { it == (req.toDark to req.toStyle) }
        }
        fade.animateTo(0f, tween(180, easing = InkMotion.Ease))
        finish()
    }

    if (!animations) return
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        val fromRight = (req.origin?.x ?: size.width) >= size.width / 2f
        val width = size.width * reveal.value
        val edge = if (fromRight) size.width - width else width
        drawRect(
            target.paper.copy(alpha = fade.value),
            topLeft = Offset(if (fromRight) edge else 0f, 0f),
            size = Size(width, size.height),
        )
        if (reveal.value in 0.001f..0.999f) {
            drawLine(
                target.sea.copy(alpha = 0.8f * fade.value),
                Offset(edge, 0f),
                Offset(edge, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}
