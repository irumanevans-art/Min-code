package dev.min.agent.mcp

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 这次 `tools/call` 重发也无妨：工具自称只读（`readOnlyHint`）或幂等（`idempotentHint`）。
 * Streamable HTTP 的 POST 已被服务器接受（开了 SSE 流）、之后续传 GET 才 404（会话过期）时，服务器可能已经执行了这次调用；
 * 重新握手后再 POST 一遍等于让它再执行一次（CC 2.1.307 修的正是这个重复发送）。所以重发只给带了这个标记的调用，
 * 其余调用恢复会话后以「结果未知」结束。和 [McpTraceContext] 一样放在协程上下文里：工具注解在内核的工具表里，连接按项目共享，
 * 调用方 `withContext(McpResendSafe())` 包住这一次调用即可。stdio 与旧版 SSE 传输没有这种重发，忽略它
 */
class McpResendSafe : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<McpResendSafe>
}
