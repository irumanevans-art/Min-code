package dev.min.code.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.sea
import kotlinx.coroutines.delay

enum class ToastType { Normal, Success, Error }

/** 简单的 toast 出口，接口对齐搬来的页面里 `toaster.show(text, type)` 的用法 */
class Toaster(private val sink: (String, ToastType) -> Unit) {
    fun show(message: String, type: ToastType = ToastType.Normal) = sink(message, type)
}

val LocalToaster = staticCompositionLocalOf<Toaster> { error("No Toaster provided") }

@Stable
class InkToastHostState {
    internal var current by mutableStateOf<ToastItem?>(null)
    private var seq = 0

    fun show(message: String, type: ToastType) {
        seq += 1
        current = ToastItem(seq, message, type)
    }
}

class ToastItem internal constructor(val id: Int, val message: String, val type: ToastType)

@Composable
fun rememberInkToaster(): Pair<Toaster, InkToastHostState> {
    val state = remember { InkToastHostState() }
    val toaster = remember(state) { Toaster { message, type -> state.show(message, type) } }
    return toaster to state
}

/**
 * 应用内 toast：一张小纸条从底部浮起，左边一道色边说明性质（成功 = 金，出错 = 朱），
 * 两秒多后沉下去。替代系统 Toast——系统那个是另一套皮肤。
 */
@Composable
fun InkToastHost(state: InkToastHostState, modifier: Modifier = Modifier) {
    val item = state.current
    var last by remember { mutableStateOf<ToastItem?>(null) }
    if (item != null) last = item
    LaunchedEffect(item?.id) {
        if (item == null) return@LaunchedEffect
        delay(if (item.type == ToastType.Error) 3400 else 2300)
        if (state.current?.id == item.id) state.current = null
    }
    Box(
        modifier
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(visible = item != null, enter = InkMotion.rise, exit = InkMotion.sink) {
            val shown = last ?: return@AnimatedVisibility
            val palette = MaterialTheme.sea
            val scheme = MaterialTheme.colorScheme
            val edge = when (shown.type) {
                ToastType.Normal -> scheme.outline
                ToastType.Success -> palette.sea
                ToastType.Error -> palette.vermilion
            }
            Row(
                Modifier
                    .widthIn(max = 420.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(scheme.surfaceContainerHigh)
                    .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.medium)
                    .drawBehind {
                        drawRect(edge, size = Size(3.dp.toPx(), size.height))
                    }
                    .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(shown.message, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
            }
        }
    }
}
