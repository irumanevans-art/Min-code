package dev.min.agent.spi

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId

/**
 * 当前 span 的 W3C trace context，宿主给内核的出口（OpenTelemetry traces）。
 *
 * 为什么是宿主给、内核只管塞进环境：span 是事后从会话日志折出来的，span 的 id 由会话 id 与日志里的 turn / call id 哈希得来，
 * 宿主（`:agent:otel`）能在子进程启动之前就算出「这次执行的 span 是谁」；内核不依赖 `:agent:otel`，也不知道 trace id 怎么来。
 * 原文（monitoring-usage）：tracing 开着时，Bash 子进程继承当前工具执行 span 的 `TRACEPARENT`。
 *
 * 返回值是 `TRACEPARENT` 环境变量的值（`00-<trace>-<span>-01`）；追踪没开、或按原文口径不该传播（自定义 `ANTHROPIC_BASE_URL`
 * 代理且没设 `CLAUDE_CODE_PROPAGATE_TRACEPARENT`）时返回 null，内核什么都不加。出任何错也返回 null——遥测不许挡住命令。
 */
interface TraceContextProvider {
    /** Bash（及 Monitor 一类跑 shell 的工具）这次执行的 span */
    suspend fun toolExecution(session: SessionId, turn: TurnId, call: CallId): String?

    /**
     * hook 子进程：[runId] 非空时是这批 hook 自己的 span（只在详细 beta 追踪下才存在，否则落到它的父）；
     * 为空（后台 hook：它完成时才落记录，启动时没有自己的 span）取父——有 [call] 是那次工具调用的 span，否则是这个回合的 interaction。
     */
    suspend fun hook(session: SessionId, turn: TurnId, call: CallId?, runId: String?): String?

    /** 默认：不传播。没接遥测的宿主与测试替身用它 */
    object None : TraceContextProvider {
        override suspend fun toolExecution(session: SessionId, turn: TurnId, call: CallId): String? = null
        override suspend fun hook(session: SessionId, turn: TurnId, call: CallId?, runId: String?): String? = null
    }
}
