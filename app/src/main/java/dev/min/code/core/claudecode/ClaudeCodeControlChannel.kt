package dev.min.code.core.claudecode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * control_request 的三种结局。必须把「CLI 明确报错」和「压根没应答」分开 ——
 * 之前两者都折成 null，于是 CLI 回的
 * "Cannot set permission mode to bypassPermissions because the session was not
 * launched with --dangerously-skip-permissions" 被显示成了"CLI 未应答"，
 * 用户看到的是一句完全误导的话。
 */
internal sealed interface ControlOutcome {
    data class Ok(val payload: JsonObject) : ControlOutcome
    data class Error(val message: String) : ControlOutcome
    data object Timeout : ControlOutcome
}

/**
 * 我们发给 CLI、要等应答的 control_request：按 request_id 配对、超时兜底。
 *
 * 从 [ClaudeCodeManager] 里整段搬出来的，只管「发出去 → 等回来」这一段。
 * 应答回来之后状态怎么变（模型列表、用量、标题……）仍在 Manager 的各个 refresh* 里决定；
 * 应答帧也仍由 Manager 的 dispatch 收到后转交 [complete] / [fail]，事件处理的顺序留在一处。
 *
 * interrupt 那类不关心返回值的请求不经过这里，直接写 stdin。
 */
internal class ClaudeCodeControlChannel(
    /** 会话此刻能不能发。不能发就不登记、不写，直接按超时算 —— 没有进程会来应答 */
    private val canSend: () -> Boolean,
    /** 写一行到 CLI 的 stdin（Manager 的串行写队列） */
    private val write: (String) -> Unit,
) {
    /** 已发出、等待 CLI 应答的请求，按 request_id 配对 */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ControlOutcome>>()

    fun newRequestId(): String = UUID.randomUUID().toString()

    /**
     * 发一个需要应答的 control_request 并等结果。
     * 超时返回 [ControlOutcome.Timeout] —— CLI 版本差异可能不认某个 subtype，不能让 UI 卡死。
     * [timeoutMs] 给拟名这类要打一枪小模型的请求留更长时间。
     *
     * 先登记再写：应答可能在写完的下一刻就被读循环收到，晚登记就认领不到了。
     */
    suspend fun request(
        requestId: String,
        frame: String,
        timeoutMs: Long = TIMEOUT_MS,
    ): ControlOutcome {
        if (!canSend()) return ControlOutcome.Timeout
        val deferred = CompletableDeferred<ControlOutcome>()
        pending[requestId] = deferred
        write(frame)
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: ControlOutcome.Timeout
        } finally {
            pending.remove(requestId)
        }
    }

    /** 只关心成功载荷时的便捷包装 */
    suspend fun payload(requestId: String, frame: String): JsonObject? =
        (request(requestId, frame) as? ControlOutcome.Ok)?.payload

    /** CLI 回了成功的 control_response。没人在等（已超时、或不是我们发的）就丢掉 */
    fun complete(requestId: String, payload: JsonObject) {
        pending.remove(requestId)?.complete(ControlOutcome.Ok(payload))
    }

    /**
     * CLI 回了错误的 control_response。返回有没有人认领 ——
     * 认领得到就由发起方给出更贴合场景的文案，认领不到的由调用方当孤儿错误处理。
     */
    fun fail(requestId: String, error: String): Boolean =
        pending.remove(requestId)?.complete(ControlOutcome.Error(error)) == true

    companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
