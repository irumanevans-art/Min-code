package dev.min.agent.otel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.TraceContextProvider

/**
 * 子进程的 TRACEPARENT（原文 monitoring-usage：tracing 开着时，Bash 子进程继承当前工具执行 span 的 TRACEPARENT）。
 *
 * span 是事后从会话日志折出来的，但它们的 id 只由会话 id 与日志里的 turn / call id 哈希得来（[SpanIds]），
 * 所以这里在子进程启动之前就能算出「这次执行的 span 是谁」，而不用等日志。trace id 与导出用同一个函数（[traceIdFor]、[OtelSpanLinks]）：
 * 子 agent 里的 Bash，trace id 是父会话那次委派所在的 trace。
 *
 * 不传播（返回 null）的情形：追踪没在产出（[OtelConfig.tracesEnabled]）；或照原文口径——`ANTHROPIC_BASE_URL` 指向自定义代理
 * 且没有 `CLAUDE_CODE_PROPAGATE_TRACEPARENT=1`（[OtelConfig.propagateTraceparent]）。
 *
 * [config] 是函数：App 的遥测设置可以随时改，每次都按当前设置判断。[open] 按会话 id 只读地打开日志（找子会话的父链用）。
 */
class OtelTraceContext(
    private val config: () -> OtelConfig?,
    open: suspend (String) -> SessionLog?,
) : TraceContextProvider {
    private val links = OtelSpanLinks({ config()?.inbound }, open)

    private fun active(): OtelConfig? = config()?.takeIf { it.tracesEnabled && it.propagateTraceparent }

    override suspend fun toolExecution(session: SessionId, turn: TurnId, call: CallId): String? {
        val c = active() ?: return null
        val trace = links.linkOf(session.value)?.traceId ?: traceIdFor(session.value, turn.value, c.inbound)
        return TraceParent.of(trace, SpanIds.execution(session.value, call.value)).header()
    }

    override suspend fun hook(session: SessionId, turn: TurnId, call: CallId?, runId: String?): String? {
        val c = active() ?: return null
        val link = links.linkOf(session.value)
        val trace = link?.traceId ?: traceIdFor(session.value, turn.value, c.inbound)
        val span = when {
            // 这批 hook 自己的 span 只在详细 beta 追踪下才会导出；否则指向它的父，免得子进程的 span 挂在一个不存在的父上
            runId != null && c.detailedTracing -> SpanIds.hookRun(session.value, runId)
            call != null -> SpanIds.tool(session.value, call.value)
            else -> link?.parentSpanId ?: SpanIds.interaction(session.value, turn.value)
        }
        return TraceParent.of(trace, span).header()
    }
}
