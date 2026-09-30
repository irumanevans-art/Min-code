package dev.min.agent.spi

import dev.min.agent.protocol.SessionId
import kotlinx.coroutines.flow.Flow

/*
 * 内核碰外部世界的其余端口。时间、id、随机性一律从这里拿——恢复测试要在任意点杀进程再重放，
 * 这些东西不可控就复现不了（docs/agent/07-工程规范.md 第 2 条）。
 */

fun interface Clock {
    fun nowMs(): Long
}

fun interface IdGenerator {
    /** 例：next("turn") → "turn_01J…"。前缀只为日志好读，唯一性由实现保证 */
    fun next(prefix: String): String
}

/**
 * 凭证。只在 Keystore 与 HTTP 头之间流动：不进 rootfs、环境变量、argv、日志、事件（docs/agent/01-目标与边界.md 硬约束）。
 * [toString] 永远打码，防止被顺手拼进日志或异常信息。
 */
class Secret(private val raw: String) {
    fun reveal(): String = raw
    override fun toString(): String = "Secret(***)"
}

fun interface Secrets {
    suspend fun apiKey(providerId: String): Secret?
}

// ---- HTTP：模型请求、WebFetch 共用 ----

class HttpRequest(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray? = null,
    val timeoutMs: Long,
) {
    /** 头里可能有凭证，toString 只写方法与地址 */
    override fun toString(): String = "HttpRequest($method $url)"
}

class HttpResponse(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray)

data class SseEvent(val event: String?, val data: String, val id: String? = null)

/** 非 2xx 或流式请求收到非 SSE 响应。body 带响应正文预览，供编解码器分类（200 非 SSE → Other） */
class HttpStatusException(val status: Int, val headers: List<Pair<String, String>>, val body: String) :
    RuntimeException("HTTP $status")

interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse

    /** 流式（SSE）。非 2xx 或非 SSE 响应抛带正文的 [HttpStatusException]；网络错误原样抛给编解码器 */
    fun stream(request: HttpRequest): Flow<SseEvent>
}

// ---- 会话存储 ----

enum class Durability {
    /** 返回前已 fsync：用户输入、副作用前、审批结果 */
    Sync,

    /** 可攒批：流式片段、说明类事件 */
    Batched,
}

interface SessionLog {
    val header: LogHeader

    /** 追加并返回带 seq 的记录。seq 与时间由存储分配 */
    suspend fun append(events: List<SessionEvent>, durability: Durability): List<EventRecord>

    fun read(fromSeq: Long = 0): Flow<EventRecord>
}

interface SessionStore {
    suspend fun create(header: LogHeader): SessionLog
    suspend fun open(id: SessionId): SessionLog?
    suspend fun list(): List<LogHeader>
}
