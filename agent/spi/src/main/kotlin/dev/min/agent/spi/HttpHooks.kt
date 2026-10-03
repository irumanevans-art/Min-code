package dev.min.agent.spi

/*
 * hooks 的 type:http（docs/agent/detail/hooks设计.md §8 H3）：事件 JSON 由宿主代发成 POST，
 * 响应怎么归档由 ext 引擎按 Claude Code 的 HTTP response handling 决定，端口只报告事实：
 * 收到了什么状态码和响应体、没连上、还是超时。凭证头在这里不进日志（同 HttpTransport 的约定）。
 */

data class HttpHookRequest(
    val url: String,
    val headers: Map<String, String>,
    /** 事件输入的 JSON 原文；Content-Type 由实现补 application/json，用户头可覆盖 */
    val body: String,
    /** 整个请求（连接加读取）的预算；到点未完成按 [HttpHookResult.TimedOut] 报告 */
    val timeoutMs: Long,
)

sealed interface HttpHookResult {
    /** 收到响应：任何状态码都算，2xx 与否由引擎判断 */
    data class Responded(val status: Int, val body: String) : HttpHookResult

    /** 连不上、DNS、TLS 等失败。error 是给人看的一句说明，不含请求头 */
    data class Failed(val error: String) : HttpHookResult

    data object TimedOut : HttpHookResult
}

fun interface HttpHookClient {
    suspend fun send(request: HttpHookRequest): HttpHookResult
}
