package dev.min.agent.kernel

import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.HookOutputCommon
import dev.min.agent.ext.hooks.MessageDisplayInput
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * MessageDisplay（官方 hooks 页「MessageDisplay」）：助手消息的文字**上屏**那一刻的 hook。内核自己不渲染屏幕——屏显在宿主（CLI 的终端、App 的会话页），
 * 所以触发点是宿主在渲染前调用的这个端口：把要显示的一批文字（[delta]）交给 hooks，拿回**实际要显示的文字**。
 *
 * 原文口径：
 * - 每次有一批新完成的行准备渲染就跑一次（流式：[MessageDisplayStream] 按行攒批）；非交互（`-p` / SDK）每条消息只跑一次，`index` 0、`final` true、全文在 `delta`；
 * - 输入 `turn_id`、`message_id`、`index`（该消息内的批序号，从 0 数）、`final`（最后一批）、`delta`（新文字，含换行）；没有 matcher，每条流式文字消息都触发；
 * - 输出 `hookSpecificOutput.displayContent` 替换这一批的屏显；**纯显示替换**：转录（会话日志）与模型看到的永远是原文——这个函数不写任何会话事件，
 *   除了那一批 hook 自己的 `hooks_ran` 记录；没有决策控制，不能拦消息；
 * - hook 失败 / 超时 / exit 2 / 没给 displayContent = 显示原文；默认超时 10 秒（[HookDriver.defaultTimeout]）；宿主会等 hook 返回才上屏。
 */
suspend fun SessionRuntime.displayMessage(turnId: TurnId?, messageId: String, index: Long, final: Boolean, delta: String): String {
    if (delta.isEmpty() || !hasMessageDisplayHooks()) return delta
    val specific = HookJson.encodeToJsonElement(
        MessageDisplayInput.serializer(), MessageDisplayInput(turnId?.value, messageId, index, final, delta),
    ).jsonObject
    val outcome = HookDriver(this).run(HookEvent.MessageDisplay, null, specific, turnId, notifyUser = false) ?: return delta
    // 只认退出码 0 的处理器给的 displayContent（原文：exit 2 = 显示原文）；多个 hook 都给时取最后一个
    return outcome.runs.asReversed().firstNotNullOfOrNull { run ->
        if (run.exitCode != 0 || run.timedOut || run.failedToStart) null else displayContentOf(run.stdout)
    } ?: delta
}

/** 这个会话有没有要跑的 MessageDisplay hook：宿主据此决定走不走「攒批、等 hook」的上屏路径（没有就原样即时打印，零开销） */
fun SessionRuntime.hasMessageDisplayHooks(): Boolean = HookDriver(this).has(HookEvent.MessageDisplay, null)

private fun displayContentOf(stdout: String): String? = catchingNonCancellation {
    val specific = HookJson.decodeFromString(HookOutputCommon.serializer(), stdout.trim()).hookSpecificOutput as? JsonObject
    if ((specific?.get("hookEventName") as? JsonPrimitive)?.content == HookEvent.MessageDisplay.name) {
        (specific["displayContent"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    } else null
}.getOrNull()

/**
 * 流式上屏的攒批器（CLI 的终端用；App 的会话页同一套）：宿主把每个 [LiveUpdate.Text] 喂进来，它按**完成的行**攒批，
 * 每批过 [displayMessage]，再把要显示的文字交给 [sink]；hook 没返回之前下一批不上屏（原文：「holds each batch until your hook returns」）。
 * 消息结束（新的请求开始、别的更新到来、回合收尾）时 [finish] 把没满一行的尾巴作为 `final` 那一批发出去。
 * 与原文的小差别：一条消息恰好以换行结尾时，最后一批在它出现的时候就发了（那时还不知道它是最后一批），`final` 没法回头标——尾巴为空就不再发。
 */
class MessageDisplayStream(private val runtime: SessionRuntime, private val sink: suspend (String) -> Unit) {
    private val lock = Mutex()
    private var request: RequestId? = null
    private var turn: TurnId? = null
    private var index = 0L
    private val buffer = StringBuilder()

    suspend fun onText(update: LiveUpdate.Text) = lock.withLock {
        if (request != null && request != update.requestId) flushLocked()
        request = update.requestId
        turn = update.turnId
        buffer.append(update.text)
        val cut = buffer.lastIndexOf("\n")
        if (cut >= 0) {
            val batch = buffer.substring(0, cut + 1)
            buffer.delete(0, cut + 1)
            emit(batch, final = false)
        }
    }

    /** 当前这条消息结束了：发出没满一行的尾巴（`final`），重置 */
    suspend fun finish() = lock.withLock { flushLocked() }

    private suspend fun flushLocked() {
        if (buffer.isNotEmpty()) {
            val tail = buffer.toString()
            buffer.setLength(0)
            emit(tail, final = true)
        }
        request = null
        index = 0
    }

    private suspend fun emit(batch: String, final: Boolean) {
        val shown = runtime.displayMessage(turn, request?.value ?: "", index++, final, batch)
        sink(shown)
    }
}
