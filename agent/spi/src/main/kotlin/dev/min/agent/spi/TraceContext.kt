package dev.min.agent.spi

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.RequestId
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

    /**
     * 模型请求（`claude_code.llm_request` span）的出站 `traceparent`。[startSeq] 是这个请求第一次尝试的 `model_request_started` 的 seq——
     * span id 由它（与会话 id、请求 id）哈希得出，所以内核要把落盘返回的 seq 交过来，宿主才算得出「这次请求的 span 是谁」。
     * 追踪没开返回 null。默认实现返回 null，旧的宿主与测试替身不用改
     */
    suspend fun modelRequest(session: SessionId, turn: TurnId, request: RequestId, startSeq: Long): OutboundTrace? = null

    /** 默认：不传播。没接遥测的宿主与测试替身用它 */
    object None : TraceContextProvider {
        override suspend fun toolExecution(session: SessionId, turn: TurnId, call: CallId): String? = null
        override suspend fun hook(session: SessionId, turn: TurnId, call: CallId?, runId: String?): String? = null
    }
}

/**
 * 模型请求要带的 trace 上下文。[traceparent] 是 `traceparent` 头的值（`00-<trace>-<span>-01`，span 为这次 llm_request）。
 * [allowCustomAnthropicEndpoint]：用户显式设了 `CLAUDE_CODE_PROPAGATE_TRACEPARENT=1`，允许发给自定义的 Anthropic 兼容端点
 * （原文：默认只发给 Anthropic API，自定义代理要显式开）。是否真的发由各家编解码器按自己的端点决定（见 `ModelClient.propagatesTrace`）。
 */
data class OutboundTrace(val traceparent: String, val allowCustomAnthropicEndpoint: Boolean = false)
