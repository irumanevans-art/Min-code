package dev.min.agent.mcp

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 这次 MCP 调用要带的 W3C `traceparent`（OpenTelemetry traces，原文 monitoring-usage：「Outbound HTTP MCP requests carry
 * `traceparent` the same way」）。放在协程上下文里而不是改 [McpClient] 的签名：连接按项目共享，同一连接上并发的调用各带各的，
 * 调用方 `withContext(McpTraceContext(…))` 包住调用，HTTP 传输在发 POST 时从当前上下文取。stdio 传输没有请求头，忽略它。
 * 值由宿主按原文的开关算好（追踪没开、自定义 `ANTHROPIC_BASE_URL` 代理且没设 `CLAUDE_CODE_PROPAGATE_TRACEPARENT` 时根本不放进来）
 */
class McpTraceContext(val traceparent: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<McpTraceContext>
}
