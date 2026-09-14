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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** 一道细金边引出新纸色，遮盖时应用主题，再露出内容。 */
@Stable
class FormSwitchController {
    internal var request by mutableStateOf<FormSwitchRequest?>(null)

    /** [origin] 是触发控件的窗口坐标；最新的选择可以替换尚未完成的过渡。 */
    fun switch(origin: Offset?, toDark: Boolean, apply: () -> Unit) {
        request?.applyOnce()
        request = FormSwitchRequest(origin, toDark, apply)
    }
}

class FormSwitchRequest internal constructor(
    val origin: Offset?,
    val toDark: Boolean,
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
    val finish by rememberUpdatedState(onDone)
    val animations = rememberAnimationsEnabled()
    val target = if (req.toDark) DarkSea else LightSea
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
        withTimeoutOrNull(900) { snapshotFlow { dark }.first { it == req.toDark } }
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
