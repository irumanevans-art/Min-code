package dev.min.code.core.engine.min

import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.LiveUpdate
import dev.min.agent.kernel.MessageDisplayStream
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.SessionState
import dev.min.agent.kernel.hasMessageDisplayHooks
import dev.min.agent.protocol.RequestId
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话页的 MessageDisplay（官方 hooks 页「MessageDisplay」）：助手文字上屏前过 hook，显示它替换后的文字。
 * 与桌面 CLI 同一个内核端口（[MessageDisplayStream]：按完成的行攒批、hook 没返回之前下一批不上屏）。
 *
 * ## 为什么不在 [MinLiveStream] 里直接挂起等 hook
 *
 * 内核瞬时流的收集是同步的，而 hook 调用是挂起的（最长 10 秒）。直接在收集里等，工具卡、思考的更新都被堵住。
 * 所以收集这一侧只把正文增量**入队**（[offer]，不挂起），由本类自己的一个协程顺序出队、过 [MessageDisplayStream]、
 * 把屏显文字送进 [MinLiveStream.onDisplayed]；别的更新（思考、工具草稿）照旧直接进 [MinLiveStream]。
 *
 * ## 完成后的消息
 *
 * 每个请求（消息）的屏显文字全文记在内存的 [shown] 里，按请求 id 索引：[SessionState] 里的
 * [HistoryEntry.Assistant.requestId] 把它对回消息，`projectMinItems` 据此画替换后的文字。这样消息落盘进历史之后，
 * 屏上不会闪回原文；hook 没返回完时先画已返回的那部分（一个字都没返回就先不画，免得原文闪一下）。
 *
 * ## 纯显示替换
 *
 * 只在内存里：日志、模型看到的、导出（取 [dev.min.code.core.session.ChatItem.AssistantText.rawText]）都是原文；
 * **重开会话后旧消息显示原文**（内存里的表没了）。没配 MessageDisplay hook 的会话 [offer] 返回 false，一切照旧、零开销。
 */
class MinMessageDisplay(
    private val runtime: SessionRuntime,
    scope: CoroutineScope,
    private val live: MinLiveStream,
) {
    private sealed interface Job {
        data class Text(val update: LiveUpdate.Text) : Job

        /** 一个工具调用开始了：这条消息的文字到头了，没满一行的尾巴作为 `final` 一批上屏 */
        data object Finish : Job

        /** 状态里看到这个请求完成了：它的尾巴作为 `final` 一批上屏（流若已转去下一个请求就不动它） */
        data class FinishRequest(val request: RequestId) : Job
    }

    private val queue = Channel<Job>(Channel.UNLIMITED)

    /** 请求 id -> 已经决定要显示的全文（hook 一批批返回，累加）。空串 = 开始了但还没有一批返回 */
    private val shown = ConcurrentHashMap<RequestId, String>()
    /** 请求 id -> 已入队的正文增量数；状态帧据此判断收尾信号发出之后有没有又来迟到的增量（瞬时流与状态流先后没有保证） */
    private val offers = ConcurrentHashMap<RequestId, Int>()
    private val finishSent = HashMap<RequestId, Int>()
    private val _changes = MutableStateFlow(0L)

    /** 屏显文字每变一次加一：会话把它并进投影的输入，消息才会跟着换 */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private val stream = MessageDisplayStream(runtime) { request, turn, text ->
        if (request != null && turn != null) {
            shown.merge(request, text) { old, new -> old + new }
            live.onDisplayed(turn, request, text)
            _changes.update { it + 1 }
        }
    }

    init {
        scope.launch {
            for (job in queue) {
                // 一批失败不该让后面的都不显示：MessageDisplayStream 已经把 hook 的失败兜成「显示原文」，这里只防意外
                catchingNonCancellation {
                    when (job) {
                        is Job.Text -> stream.onText(job.update)
                        Job.Finish -> stream.finish()
                        is Job.FinishRequest -> stream.finishRequest(job.request)
                    }
                }
            }
        }
    }

    /**
     * 内核瞬时流的收集调这里（同步）。返回 true = 这条增量由本类接管（不要再喂 [MinLiveStream.onUpdate]）。
     * 只有正文增量、且会话有 MessageDisplay hook 才接管；工具调用开始时顺带让没满一行的尾巴收尾
     */
    fun offer(update: LiveUpdate): Boolean {
        if (update is LiveUpdate.ToolCallStarted && shown.isNotEmpty()) queue.trySend(Job.Finish)
        if (update !is LiveUpdate.Text || !runtime.hasMessageDisplayHooks()) return false
        shown.putIfAbsent(update.requestId, "")
        offers.merge(update.requestId, 1, Int::plus)
        live.onTextHeld(update)
        queue.trySend(Job.Text(update))
        return true
    }

    /** 结构化状态每来一帧调一次：哪个请求完成了，就让它没满一行的尾巴收尾 */
    fun onState(state: SessionState) {
        if (shown.isEmpty()) return
        for (entry in state.history.asReversed().take(RECENT)) {
            val request = (entry as? HistoryEntry.Assistant)?.requestId ?: continue
            val n = offers[request] ?: continue
            if (finishSent[request] != n) {
                finishSent[request] = n
                queue.trySend(Job.FinishRequest(request))
            }
        }
    }

    /** 此刻全部有屏显文字的请求（给投影用的快照）；没用过 hook 为空表 */
    fun snapshot(): Map<RequestId, String> = if (shown.isEmpty()) emptyMap() else HashMap(shown)

    private companion object {
        /** 状态是合并的，一帧里可能同时多出几条消息：看最后几条历史就够 */
        const val RECENT = 8
    }
}
