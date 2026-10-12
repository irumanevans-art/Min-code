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
    /**
     * 收到响应头（状态行与头）后回调一次，早于第一个 SSE 事件；只有 [HttpTransport.stream] 调它。
     * 为什么是回调：`stream` 的返回是事件流，没有放响应头的地方，而遥测要 `request-id` 与 `traceresponse`。不关心的传输忽略它
     */
    val onResponseHeaders: ((List<Pair<String, String>>) -> Unit)? = null,
) {
    /** 头里可能有凭证，toString 只写方法与地址 */
    override fun toString(): String = "HttpRequest($method ${url.substringBefore('?').substringBefore('#').replace(Regex("//[^/]*@"), "//")})"
}

class HttpResponse(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray)

/** 正文只能收集一次；未收集或提前放弃时也要 close，避免只读响应头就遗留连接。 */
class HttpStreamingResponse(
    val status: Int,
    val headers: List<Pair<String, String>>,
    val body: Flow<ByteArray>,
    private val release: () -> Unit = {},
) : java.io.Closeable {
    override fun close() = release()
}

data class SseEvent(val event: String?, val data: String, val id: String? = null)

/** 非 2xx 或流式请求收到非 SSE 响应。body 带响应正文预览，供编解码器分类（200 非 SSE → Other） */
class HttpStatusException(val status: Int, val headers: List<Pair<String, String>>, val body: String) :
    RuntimeException("HTTP $status")

interface HttpTransport {
    /** 原样响应，不分类状态或解析正文；旧端口不必为了新协议实现它。 */
    suspend fun open(request: HttpRequest): HttpStreamingResponse =
        throw UnsupportedOperationException("这个 HTTP 端口不支持原样流式响应。")

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

    /**
     * 只读地打开（不修撕裂尾巴、不动文件），返回的视图**不可追加**（真存储里 [SessionLog.append] 会拒绝）。
     * 轮询一个**还在被追加**的日志必须走它：[open] 是给写者附着用的，会做崩溃修复——对活日志做修复
     * 会把并发刚追加上去的记录截掉，之后 seq 断档、整个日志报废。
     * 默认退回 [open]：只读的测试存储没有修复这回事
     */
    suspend fun peek(id: SessionId): SessionLog? = open(id)

    /**
     * 删掉一个会话的全部落盘内容（日志与同目录下的附件）。不存在返回 false。
     * 默认不支持（返回 false）：只读的、测试用的存储不用实现；真正的存储（FileSessionStore）覆盖它
     */
    suspend fun delete(id: SessionId): Boolean = false
}
