package dev.min.code.core.engine.min

import dev.min.agent.kernel.LiveUpdate
import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.code.core.engine.LiveStream
import dev.min.code.core.engine.ToolDraft
import dev.min.code.core.session.ClippedOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 内核瞬时流（[LiveUpdate]）-> 通用 [LiveStream]（设计 5.3）。逐字增量只改这一条流，不碰结构化状态。
 *
 * ## 缓冲归零（K4）
 *
 * 一个回合里有好几次模型请求。「完成」落盘（让结构化状态里多出那条助手消息）与最后几个增量，走的是两条流，
 * 先后没有保证：旧请求的尾巴可能在新请求开始之后才到。所以每个增量带 [RequestId]，规则是：
 *
 * - 见到新的 requestId：清掉正文 / 思考 / 工具草稿，从这个增量重新攒（工具输出按调用 id，跨请求不清，回合换了才清）；
 * - 状态里这次请求已经完成（[onState] 看到 `openRequest` 没了）：缓冲清空，并记住这个 id 为「已收尾」，
 *   之后迟到的同 id 增量直接丢弃——否则刚落盘的那条回答会在屏幕上出现第二遍；
 * - 回合换了（turnId 变）：全部清空。
 *
 * ## 为什么 [onState] 要喂状态
 *
 * 瞬时流没有 replay，丢了也无妨；但「请求已完成」只有持久状态知道。另外界面接上时若缓冲是空的、而状态里
 * `openRequest.segments` 已有落盘片段，就先显示片段，免得中途接入时一片空白（片段比实时增量攒得慢，
 * 所以展示取两者里较长的那份，不会出现文字回缩）。
 *
 * 线程安全：增量来自内核的回合协程，状态来自投影协程，所以所有改动在同一把锁里。
 */
class MinLiveStream {
    private val lock = Any()
    private val _stream = MutableStateFlow(LiveStream.Empty)
    val stream: StateFlow<LiveStream> = _stream.asStateFlow()

    /** 最近一次工具状态行（「正在搜索 …」），进回合状态行的 detail。随调用结束被状态清掉 */
    private val _toolStatus = MutableStateFlow<Pair<CallId, String>?>(null)
    val toolStatus: StateFlow<Pair<CallId, String>?> = _toolStatus.asStateFlow()

    private var turnId: String? = null
    private var requestId: RequestId? = null

    /** 状态里已收尾的请求。只记最近一个：迟到的只可能是紧挨着的上一次请求 */
    private var finished: RequestId? = null
    private var openSeen: RequestId? = null

    private var text = StringBuilder()
    private var thinking = StringBuilder()
    private var drafts = linkedMapOf<Int, ToolDraft>()
    private val outputs = linkedMapOf<CallId, ClippedOutput>()
    private var persistedText = ""
    private var persistedThinking = ""

    fun onUpdate(update: LiveUpdate) = synchronized(this) {
        when (update) {
            is LiveUpdate.Text -> if (enter(update.turnId.value, update.requestId)) text.append(update.text)
            is LiveUpdate.Thinking -> if (enter(update.turnId.value, update.requestId)) thinking.append(update.text)
            is LiveUpdate.ToolCallStarted -> if (enter(update.turnId.value, update.requestId)) {
                drafts[update.index] = ToolDraft(update.index, null, update.name, "")
            }
            is LiveUpdate.ToolInput -> if (enter(update.turnId.value, update.requestId)) {
                val old = drafts[update.index]
                drafts[update.index] = (old ?: ToolDraft(update.index, null, "", "")).let { it.copy(partialJson = it.partialJson + update.partialJson) }
            }
            is LiveUpdate.ToolOutput -> outputs.getOrPut(update.callId) { ClippedOutput() }.append(update.text)
            is LiveUpdate.ToolStatus -> _toolStatus.value = update.callId to update.text
        }
        publish()
    }

    /** 每次结构化状态更新时调用：收尾已完成的请求、换回合清空、接入时用落盘片段垫底 */
    fun onState(state: SessionState) = synchronized(this) {
        val turn = state.turn
        if (turn == null) {
            // 回合已结束：流里不该再有东西（中断时增量可能还在路上，这里一并清掉，之后同回合的迟到增量也进不来）
            if (turnId != null || !isBlank()) {
                finished = requestId ?: finished
                resetAll()
            }
        } else {
            if (turnId != null && turnId != turn.turnId.value) resetAll()
            // 接入时（还没收到任何增量）也要知道这是哪个回合，界面据此在回合切换时丢旧缓冲
            if (turnId == null) turnId = turn.turnId.value
            val open = turn.openRequest
            if (open != null) {
                // 状态流是合并的，「上一个请求已完成」那一帧可能被跳过：直接从旧 id 跳到新 id 也算旧的收尾了
                openSeen?.takeIf { it != open.requestId }?.let { finished = it }
                openSeen = open.requestId
                persistedText = open.segments.filterIsInstance<TextPart>().joinToString("") { it.text }
                persistedThinking = open.segments.filterIsInstance<ThinkingPart>().joinToString("") { it.text }
            } else {
                // 请求已完成落盘（回答进了 items），流里这一份该让位
                openSeen?.let { finished = it }
                openSeen = null
                persistedText = ""
                persistedThinking = ""
                clearRequestBuffers()
            }
            // 已经结束的调用，输出尾巴不再需要（卡片上有完整结果）
            val running = turn.calls.filterValues { it.phase !is dev.min.agent.kernel.CallPhase.Done }.keys
            outputs.keys.retainAll(running)
            _toolStatus.value = _toolStatus.value?.takeIf { it.first in running }
        }
        publish()
    }

    /** 接受一个正文类增量吗：先处理回合 / 请求切换，再判是不是迟到的旧请求 */
    private fun enter(turn: String, request: RequestId): Boolean {
        if (turnId != null && turnId != turn) resetAll()
        if (request == finished) return false
        turnId = turn
        if (request != requestId) {
            clearRequestBuffers()
            requestId = request
        }
        return true
    }

    private fun clearRequestBuffers() {
        text = StringBuilder()
        thinking = StringBuilder()
        drafts = linkedMapOf()
        requestId = null
    }

    private fun resetAll() {
        clearRequestBuffers()
        outputs.clear()
        turnId = null
        persistedText = ""
        persistedThinking = ""
        openSeen = null
        _toolStatus.value = null
    }

    private fun isBlank() = text.isEmpty() && thinking.isEmpty() && drafts.isEmpty() && outputs.isEmpty()

    private fun publish() {
        _stream.value = LiveStream(
            turnId = turnId,
            text = if (text.length >= persistedText.length) text.toString() else persistedText,
            thinking = if (thinking.length >= persistedThinking.length) thinking.toString() else persistedThinking,
            toolDrafts = drafts.values.toList(),
            toolOutput = outputs.mapKeys { it.key.value }.mapValues { (_, out) -> out.applyTo(EMPTY_CARD).result.orEmpty() },
        )
    }

    private companion object {
        /** [ClippedOutput] 只会往卡片上落，这里借一张空卡取它攒好的文本 */
        val EMPTY_CARD = dev.min.code.core.session.ChatItem.ToolCall(
            "", "", "", kotlinx.serialization.json.JsonObject(emptyMap()), dev.min.code.core.session.ChatItem.ToolCall.Status.Running,
        )
    }
}
