package dev.min.code.ui.session

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.exp

/**
 * 会话流「贴底跟随」。Claude Code 和 Codex 两个会话页共用这一份。
 *
 * 规则只有一条：**用户此刻在底部，内容长了就跟着贴底；不在底部就一动不动。**
 * 发送消息也不例外（用户明确要求过「发送也别跳」）：发送时本来在底，新消息按这条规则自然跟上；
 * 在上面翻旧消息时发送，位置保持原样。打开会话时列表是空的，空列表算「在底」，所以回放的历史会落在底部。
 *
 * 状态机（[TranscriptFollow.following]）：
 * - 手指一拖（[DragInteraction.Start]）立刻脱离跟随。**不能等到松手再判**：以前就是松手后才重算，
 *   而惯性滑动（fling）的优先级和程序滚动一样是 MutatePriority.Default，拖动期间正文每长一截发起的
 *   animateScrollToItem 会把惯性直接打断、拽回底部，停下时又恰好在底，跟随就永远关不掉 ——
 *   这就是「生成的时候一往上翻就被拽回去」。
 * - 滚动完全停下（含惯性）且停在底部 → 恢复跟随。
 * - 程序自己的滚动（跟随、键盘补偿）不改这个状态：它们只在跟随时才发生，
 *   停下时本来就在底；判「在不在底」时也只看手势停下那一刻，不看内容增长之后的布局 ——
 *   增长当帧的 layoutInfo 里最后一项已经伸出视口，拿它判断会永远判成「不在底」。
 */
@Stable
internal class TranscriptFollow {
    var following by mutableStateOf(true)
        private set

    /** 换了一个会话：新会话照旧从底部开始读 */
    fun reset() {
        following = true
    }

    internal fun detach() {
        following = false
    }

    internal fun attach() {
        following = true
    }
}

/**
 * 判「在底部」的容差。
 *
 * 48dp 约两行正文。下限：惯性停下到我们确认之间隔一帧，这一帧里正好到一截正文
 * （流式 token 常常一次来一两行）不该让人掉出跟随；
 * 上限：用户往上翻哪怕两三行也是明确的「我要看上面」，不能被当成在底。
 */
internal val FOLLOW_BOTTOM_SLOP = 48.dp

/**
 * 跟随时把最后一项的底部顶到视口下沿。animateScrollToItem 的语义是把目标项对齐到
 * 视口**顶部**，直接用会导致"滚到最后一条却停在屏幕上方"，看着像弹回去了；
 * 给一个远超任何条目高度的偏移，滚动会在内容尽头被夹住，落点就是真正的底。
 */
internal const val TAIL_SCROLL_OFFSET = 100_000

/**
 * 列表是否贴在底部。纯函数，参数都取自 [LazyListLayoutInfo]：
 *
 * @param lastVisibleIndex 最后一个可见项的下标，没有可见项时传 -1
 * @param lastVisibleEnd 最后一个可见项的下沿（offset + size），与 layoutInfo 里的 offset 同一坐标系
 * @param viewportEndOffset layoutInfo.viewportEndOffset（视口高度减去 beforeContentPadding）
 * @param afterContentPadding 底部内容留白：滚到底时最后一项下沿停在留白上方，而不是视口下沿
 *
 * 只处理正向布局：两个会话流都没用 reverseLayout。哪天用了，要按首项判，别直接套这个函数。
 */
internal fun isAtListBottom(
    totalItemsCount: Int,
    lastVisibleIndex: Int,
    lastVisibleEnd: Int,
    viewportEndOffset: Int,
    afterContentPadding: Int,
    slopPx: Int,
): Boolean {
    // 空列表没得往下翻，算在底：第一条内容（打开会话回放的历史、新会话的第一句）就会被跟上
    if (totalItemsCount == 0) return true
    if (lastVisibleIndex != totalItemsCount - 1) return false
    val contentEnd = viewportEndOffset - afterContentPadding
    return lastVisibleEnd - contentEnd <= slopPx
}

internal fun LazyListLayoutInfo.isAtBottom(slopPx: Int): Boolean {
    val last = visibleItemsInfo.lastOrNull()
    return isAtListBottom(
        totalItemsCount = totalItemsCount,
        lastVisibleIndex = last?.index ?: -1,
        lastVisibleEnd = last?.let { it.offset + it.size } ?: 0,
        viewportEndOffset = viewportEndOffset,
        afterContentPadding = afterContentPadding,
        slopPx = slopPx,
    )
}

/**
 * 最后一项的下沿比「滚到底时该在的位置」多出来多少 px：正数 = 还有这么多压在下面。
 * 参数与 [isAtListBottom] 同源。返回 null = 最后一项不在视口里（差得太远，量不出来）。
 */
internal fun tailGapPx(
    totalItemsCount: Int,
    lastVisibleIndex: Int,
    lastVisibleEnd: Int,
    viewportEndOffset: Int,
    afterContentPadding: Int,
): Int? {
    if (totalItemsCount == 0) return 0
    if (lastVisibleIndex != totalItemsCount - 1) return null
    return lastVisibleEnd - (viewportEndOffset - afterContentPadding)
}

internal fun LazyListLayoutInfo.tailGap(): Int? {
    val last = visibleItemsInfo.lastOrNull()
    return tailGapPx(
        totalItemsCount = totalItemsCount,
        lastVisibleIndex = last?.index ?: -1,
        lastVisibleEnd = last?.let { it.offset + it.size } ?: 0,
        viewportEndOffset = viewportEndOffset,
        afterContentPadding = afterContentPadding,
    )
}

/**
 * 跟随缓动的时间常数：每过这么久，离底的距离收掉约 63%。
 * 90 ms 让一整行（约 20 dp）的落差在一百多毫秒里滑完 —— 看得出是滑的，又不拖。
 */
internal const val FOLLOW_GLIDE_TAU_MS = 90f

/**
 * 这一帧往下滚多少 px。指数趋近：离得远走得快、快到了放慢，没有匀速动画的起停感；
 * 每帧至少 1 px，免得最后那一两 px 半天收不完；[gapPx] ≤ 0 时不动。
 * [frameMs] 是和上一帧的间隔，按它换算，60 Hz 和 120 Hz 的屏上滑得一样快。
 */
internal fun followGlideStep(gapPx: Float, frameMs: Float): Float {
    if (gapPx <= 0f) return 0f
    val step = gapPx * (1f - exp(-frameMs.coerceIn(1f, 64f) / FOLLOW_GLIDE_TAU_MS))
    return step.coerceIn(minOf(1f, gapPx), gapPx)
}

/**
 * 跟随时把尾巴贴到底。**逐帧缓动，不是一次次发起滚动动画。**
 *
 * 以前是正文每长 64 个字符调一次 animateScrollToItem：流式正文一截一截地到，每截都是
 * 「内容先长出一截 → 动画再追过去」，左边的线、末段收笔和正文一起一顿一顿地挪，像卡。
 * 现在盯的是布局本身（正文区又是渐长的，见 [TranscriptEntry]）：布局每变一次，
 * 下一帧按 [followGlideStep] 收掉一部分落差。长高是连续的，跟随也是连续的；
 * 插进来一张工具卡这种整块变高，也是滑过去而不是跳过去。
 *
 * 最后一项不在视口里（打开一个长会话、或一口气插进好几屏）时量不出落差，
 * 这时退回 [animateToTail] 滚过去，尾巴一进视口就换回缓动接着贴。
 *
 * 用户一拖 [TranscriptFollow.following] 就是 false，这个协程随之取消，不会和手指抢。
 */
@Composable
internal fun FollowTailEffect(listState: LazyListState, follow: TranscriptFollow) {
    LaunchedEffect(listState, follow.following) {
        if (!follow.following) return@LaunchedEffect
        var lastFrame = 0L
        snapshotFlow { listState.layoutInfo.tailGap() }.collectLatest { gap ->
            if (gap == null) {
                listState.animateToTail(listState.layoutInfo.totalItemsCount)
                return@collectLatest
            }
            if (gap <= 0) return@collectLatest
            val now = withFrameNanos { it }
            // 停过一阵再动（上一帧是很久以前）时按一帧算，不然第一步会一下子收完
            val frameMs = if (lastFrame == 0L) 16f else ((now - lastFrame) / 1_000_000f).coerceAtMost(32f)
            lastFrame = now
            val current = listState.layoutInfo.tailGap() ?: return@collectLatest
            val step = followGlideStep(current.toFloat(), frameMs)
            if (step <= 0f) return@collectLatest
            // 滚完布局一变，snapshotFlow 再发一次，下一帧接着收 —— 落差收完就不再发了
            try {
                listState.scrollBy(step)
            } catch (e: CancellationException) {
                // 被更高优先级的滚动（手指）抢了：那一下 following 马上会变 false，这里只别让异常冒出去
                currentCoroutineContext().ensureActive()
            }
        }
    }
}

/**
 * 贴到最后一项的底。[itemCount] 是列表的总项数（含流式、进度这类尾项）。
 *
 * 被别的滚动抢占（手指拖动、键盘补偿的 scrollBy）时 animateScrollToItem 会抛
 * CancellationException，而我们自己的协程并没有被取消。不吞掉的话它会顺着 collect 往外冒，
 * 把整个跟随协程悄悄结束掉，直到下一次 key 变化才重启 —— 期间内容再长也不跟了。
 */
internal suspend fun LazyListState.animateToTail(itemCount: Int) {
    if (itemCount <= 0) return
    try {
        animateScrollToItem(itemCount - 1, scrollOffset = TAIL_SCROLL_OFFSET)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
    }
}

@Composable
internal fun rememberTranscriptFollow(listState: LazyListState): TranscriptFollow {
    val slopPx = with(LocalDensity.current) { FOLLOW_BOTTOM_SLOP.roundToPx() }
    val follow = remember(listState) { TranscriptFollow() }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) follow.detach()
        }
    }
    LaunchedEffect(listState, slopPx) {
        snapshotFlow {
            !follow.following && !listState.isScrollInProgress && listState.layoutInfo.isAtBottom(slopPx)
        }.collectLatest { settledAtBottom ->
            if (!settledAtBottom) return@collectLatest
            // 隔一帧再认。松手到惯性开始之间 isScrollInProgress 可能短暂为 false（拖动和惯性是两段
            // scroll），在底部轻轻往上一甩时，若此刻就恢复跟随，跟随的滚动会把刚起步的惯性打断。
            // 一帧后惯性已经开始，下面的复查就不成立
            withFrameNanos { }
            if (!listState.isScrollInProgress && listState.layoutInfo.isAtBottom(slopPx)) follow.attach()
        }
    }
    return follow
}
