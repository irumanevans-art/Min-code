package dev.min.agent.spi

/**
 * WebSocket 端口（Monitor 的 `ws` 源）。宿主侧直连，不经 proot（与 WebFetch、远程 MCP 同理）。
 * 连接前宿主要判目标地址（私网、链路本地、云元数据一概拒绝，主机名解析到这些地址的也拒），
 * 并且**把校验过的那个地址钉住来连**：校验一次、连接时再解析一次的话，攻击者的 DNS 可以在两次之间换成内网地址（DNS 重绑定）。
 */
interface WebSocketPort {
    /** 打开连接（握手完成才返回）。拒绝（地址不允许、握手失败）抛 [WebSocketRefused]，消息写给模型 */
    suspend fun open(url: String, protocols: List<String>): WebSocketConnection

    object Unavailable : WebSocketPort {
        override suspend fun open(url: String, protocols: List<String>): WebSocketConnection =
            throw WebSocketRefused("这个环境不能打开 WebSocket。")
    }
}

/** 没连上的原因，写给模型看（不含凭据、不含对端响应正文） */
class WebSocketRefused(message: String) : Exception(message)

interface WebSocketConnection : AutoCloseable {
    /**
     * 下一个事件，挂起到有为止。收到 [WebSocketEvent.Closed] / [WebSocketEvent.TooLarge] / [WebSocketEvent.Failed] 之后连接已结束，
     * 再调返回同一个结束事件。协程被取消时连接随之关闭（[close] 幂等）
     */
    suspend fun next(): WebSocketEvent
}

sealed interface WebSocketEvent {
    /** 一条文本消息（分片已拼好）。多行的也只算一个事件 */
    data class Text(val text: String) : WebSocketEvent

    /** 二进制消息不传内容，只告诉有多大（CC：给模型一行 `[binary frame, 512 bytes]`） */
    data class Binary(val bytes: Long) : WebSocketEvent

    /** 对端关闭了连接；[code] 为 1005 表示对端没带关闭码 */
    data class Closed(val code: Int, val reason: String) : WebSocketEvent

    /** 一条消息超过 [WEBSOCKET_MAX_MESSAGE_BYTES]：不再读下去，连接已关（CC：超过 1 MiB 结束 watch） */
    data class TooLarge(val bytes: Long) : WebSocketEvent

    /** 连接出了问题（读失败、协议错误、文本不是合法 UTF-8）：[detail] 写给模型 */
    data class Failed(val detail: String) : WebSocketEvent
}

/** 单条消息的上限：CC 写的是 1 MiB，超过就结束 watch */
const val WEBSOCKET_MAX_MESSAGE_BYTES = 1L * 1024 * 1024
