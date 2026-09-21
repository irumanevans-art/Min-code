package dev.min.code.ui.providers

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 长按拖动重排一个 [androidx.compose.foundation.lazy.LazyColumn]。
 *
 * Compose 没有内置这个，所以自己写一份。四十来行，但每一条规则背后都有一个具体的岔子：
 *
 * - **入口只能是长按。** 供应商列表里整行点击已经是「切到这一条」——最高频的动作。
 *   拿短按去开拖拽，等于让每一次切换都要先赢一场手势竞争。
 * - **拖过半个邻居就换位，不是拖过整个。** 按整个高度判的话，手指要越过一整行才
 *   「咬合」一次，拖到第三位要走三行的距离，手感是黏的。
 * - **边缘自动滚。** 表有八十多条，不滚就只能在一屏之内排序。
 * - **落位才写盘。** 拖动途中只动内存里的顺序；每挪一格写一次 DataStore，一次拖动
 *   能写十几次，而且中途松手/取消还要回滚。
 */
@Stable
class ReorderState internal constructor(
    internal val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onSettle: () -> Unit,
) {
    /** 正在被拖的那一项的 key；null = 没人在拖 */
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    /** 被拖那一项相对它原位的偏移（px），用来把它浮起来 */
    var offsetY by mutableFloatStateOf(0f)
        private set

    private var draggingIndex = -1
    private var autoScroll: Job? = null

    internal fun start(key: Any) {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        draggingKey = key
        draggingIndex = info.index
        offsetY = 0f
    }

    internal fun drag(deltaY: Float) {
        if (draggingKey == null) return
        offsetY += deltaY
        val current = itemInfo(draggingIndex) ?: return
        // 被拖那一项此刻的上下沿（原位 + 偏移）
        val top = current.offset + offsetY
        val bottom = top + current.size

        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { other ->
            if (other.index == draggingIndex) return@firstOrNull false
            val middle = other.offset + other.size / 2f
            // 往下拖：下沿越过邻居的中线；往上拖：上沿越过中线
            if (other.index > draggingIndex) bottom > middle else top < middle
        }
        if (target != null) {
            onMove(draggingIndex, target.index)
            // 换位之后这一项换了槽，偏移要减去那一格的高度，否则它会跟着"跳"一下
            offsetY -= (target.offset - current.offset)
            draggingIndex = target.index
        }
        ensureAutoScroll()
    }

    /**
     * 松手、或者手势被取消（被父级抢走、手指滑出屏幕）。
     *
     * 两种情况都要落盘：内存里的顺序在拖动途中已经变了，不写的话界面和存的东西对不上，
     * 下次进页面顺序会「弹回去」。所以这里不分正常结束和取消。
     */
    internal fun stop() {
        autoScroll?.cancel()
        autoScroll = null
        draggingKey = null
        draggingIndex = -1
        offsetY = 0f
        onSettle()
    }

    private fun itemInfo(index: Int): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

    /** 拖到上下边缘时自动滚。滚速按"越过边界多少"给，不是常数 */
    private fun ensureAutoScroll() {
        if (autoScroll?.isActive == true) return
        autoScroll = scope.launch {
            while (isActive && draggingKey != null) {
                val info = itemInfo(draggingIndex)
                val amount = if (info == null) 0f else {
                    val top = info.offset + offsetY
                    val bottom = top + info.size
                    val viewportTop = listState.layoutInfo.viewportStartOffset + EDGE_PX
                    val viewportBottom = listState.layoutInfo.viewportEndOffset - EDGE_PX
                    when {
                        top < viewportTop -> (top - viewportTop).coerceAtLeast(-MAX_SCROLL_PX)
                        bottom > viewportBottom -> (bottom - viewportBottom).coerceAtMost(MAX_SCROLL_PX)
                        else -> 0f
                    }
                }
                if (amount != 0f) {
                    val consumed = listState.scrollByPx(amount * SCROLL_FACTOR)
                    // 滚动把内容挪走了，被拖那一项要跟着手指留在原地
                    offsetY += consumed
                    drag(0f)
                }
                delay(16)
            }
        }
    }

    /** 名字刻意不叫 scrollBy：那会和 ScrollScope 自己的同名成员在下面那行里撞上 */
    private suspend fun LazyListState.scrollByPx(value: Float): Float {
        var consumed = 0f
        scroll { consumed = scrollBy(value) }
        return consumed
    }

    private companion object {
        /** 离边界多近开始滚 */
        const val EDGE_PX = 72f
        const val MAX_SCROLL_PX = 40f
        const val SCROLL_FACTOR = 0.35f
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    scope: CoroutineScope,
    onMove: (from: Int, to: Int) -> Unit,
    onSettle: () -> Unit,
): ReorderState {
    val move by rememberUpdatedState(onMove)
    val settle by rememberUpdatedState(onSettle)
    return remember(listState, scope) {
        ReorderState(listState, scope, { f, t -> move(f, t) }, { settle() })
    }
}

/**
 * 挂在每一行上：长按起拖。
 *
 * [key] 必须和 `LazyColumn` 的 `items(key = ...)` 是同一个值 —— 拖动时要靠它在
 * `visibleItemsInfo` 里找回自己。
 */
fun Modifier.reorderable(state: ReorderState, key: Any): Modifier = this.pointerInput(key) {
    detectDragGesturesAfterLongPress(
        onDragStart = { state.start(key) },
        onDrag = { change, amount ->
            change.consume()
            state.drag(amount.y)
        },
        onDragEnd = { state.stop() },
        onDragCancel = { state.stop() },
    )
}
