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
 * Compose 没有内置这个，所以自己写一份。每一条规则背后都有一个具体的岔子：
 *
 * - **入口只能是长按。** 供应商列表里整行点击已经是「切到这一条」——最高频的动作。
 *   拿短按去开拖拽，等于让每一次切换都要先赢一场手势竞争。
 * - **拖过半个邻居就换位，不是拖过整个。** 按整个高度判的话，手指要越过一整行才
 *   「咬合」一次，拖到第三位要走三行的距离，手感是黏的。
 * - **边缘自动滚。** 表有八十多条，不滚就只能在一屏之内排序。
 * - **落位才写盘。** 拖动途中只动内存里的顺序；每挪一格写一次 DataStore，一次拖动
 *   能写十几次，而且中途松手/取消还要回滚。
 * - **换位按 key，不按 LazyColumn 的绝对下标。** 这张表前面常年挂着搜索框、提示、
 *   统一供应商区之类的固定项，它们也占下标。拿绝对下标去动业务列表，要么越界直接
 *   没反应（被拖的那行只靠偏移在闪），要么把 `draggingIndex` 指到一个固定项上，
 *   旁边的行看起来在乱跳。
 */
@Stable
class ReorderState internal constructor(
    internal val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (fromKey: Any, toKey: Any) -> Unit,
    private val onSettle: () -> Unit,
    private val isReorderable: (key: Any) -> Boolean,
) {
    /** 正在被拖的那一项的 key；null = 没人在拖 */
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    /** 被拖那一项相对它原位的偏移（px），用来把它浮起来 */
    var offsetY by mutableFloatStateOf(0f)
        private set

    private var autoScroll: Job? = null

    internal fun start(key: Any) {
        if (!isReorderable(key)) return
        if (itemInfo(key) == null) return
        draggingKey = key
        offsetY = 0f
    }

    internal fun drag(deltaY: Float) {
        val key = draggingKey ?: return
        offsetY += deltaY
        // 始终按 key 找回自己：换位之后下标会变，按旧下标取到的可能是搜索框
        val current = itemInfo(key) ?: return
        val top = current.offset + offsetY
        val bottom = top + current.size

        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { other ->
            if (other.key == key) return@firstOrNull false
            // 固定项（搜索框、分区标题、按钮行）不参与换位
            if (!isReorderable(other.key)) return@firstOrNull false
            val middle = other.offset + other.size / 2f
            // 往下拖：下沿越过邻居的中线；往上拖：上沿越过中线
            if (other.index > current.index) bottom > middle else top < middle
        }
        if (target != null) {
            onMove(key, target.key)
            // 换位之后这一项换了槽，偏移要减去那一格的高度，否则它会跟着"跳"一下
            offsetY -= (target.offset - current.offset)
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
        offsetY = 0f
        onSettle()
    }

    private fun itemInfo(key: Any): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /** 拖到上下边缘时自动滚。滚速按"越过边界多少"给，不是常数 */
    private fun ensureAutoScroll() {
        if (autoScroll?.isActive == true) return
        autoScroll = scope.launch {
            while (isActive && draggingKey != null) {
                val key = draggingKey ?: break
                val info = itemInfo(key)
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
    onMove: (fromKey: Any, toKey: Any) -> Unit,
    onSettle: () -> Unit,
    isReorderable: (key: Any) -> Boolean,
): ReorderState {
    val move by rememberUpdatedState(onMove)
    val settle by rememberUpdatedState(onSettle)
    val reorderable by rememberUpdatedState(isReorderable)
    return remember(listState, scope) {
        ReorderState(
            listState,
            scope,
            { f, t -> move(f, t) },
            { settle() },
            { reorderable(it) },
        )
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
