package dev.min.agent.mcp

import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

internal const val DEFAULT_MAX_MESSAGE_BYTES = 16 * 1024 * 1024

/** 观察者异常不拥有连接生命周期；取消仍交还调用它的协程。 */
internal fun safeDebug(debug: (String) -> Unit): (String) -> Unit = { message ->
    catchingNonCancellation { debug(message) }
}

/** 先按字节限流再解 UTF-8，避免分块切断多字节字符，也避免 readLine 无界分配。 */
internal fun Flow<ByteArray>.lines(limit: Int, emitResidual: Boolean = true): Flow<String> = flow {
    val line = ByteArrayOutputStream()
    var skipLf = false
    collect { chunk ->
        for (b in chunk) {
            if (skipLf && b == 10.toByte()) { skipLf = false; continue }
            skipLf = false
            if (b == 10.toByte() || b == 13.toByte()) {
                emit(line.toString("UTF-8")); line.reset(); skipLf = b == 13.toByte()
            } else {
                if (line.size() >= limit) throw McpTransportClosed("MCP 消息超过 $limit 字节上限，已断开连接。")
                line.write(b.toInt())
            }
        }
    }
    if (emitResidual && line.size() > 0) emit(line.toString("UTF-8"))
}

internal suspend fun decodeMessages(raw: String, debug: (String) -> Unit, tolerateNoise: Boolean = false, emit: suspend (JsonObject) -> Unit) {
    val parsed = catchingNonCancellation { Json.parseToJsonElement(raw) }.getOrNull()
    val messages = when (parsed) {
        is JsonObject -> listOf(parsed)
        is JsonArray -> parsed.toList()
        else -> {
            if (tolerateNoise) { debug("MCP 跳过 stdout 上的非 JSON 消息。"); return }
            throw McpTransportClosed("MCP 服务器返回了无法解析的 JSON 消息。")
        }
    }
    for (message in messages) {
        if (message is JsonObject) emit(message)
        else debug("MCP 跳过批量消息里的非对象元素。")
    }
}

internal data class McpSseEvent(val event: String?, val data: String?, val id: String?, val retryMs: Long?)
internal fun Flow<ByteArray>.sse(limit: Int): Flow<McpSseEvent> = flow {
    var event: String? = null
    var id: String? = null
    var retry: Long? = null
    var touched = false
    var size = 0
    val data = mutableListOf<String>()
    suspend fun dispatch() {
        if (touched) emit(McpSseEvent(event, data.takeIf { it.isNotEmpty() }?.joinToString("\n"), id, retry))
        event = null; data.clear(); size = 0; touched = false
    }
    this@sse.lines(limit, emitResidual = false).collect { line ->
        if (line.isEmpty()) dispatch()
        else if (!line.startsWith(":")) {
            val key = line.substringBefore(':')
            val value = if (':' in line) line.substringAfter(':').removePrefix(" ") else ""
            when (key) {
                "event" -> { event = value; touched = true }
                "id" -> if ('\u0000' !in value) { id = value; touched = true }
                "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
                    value.toLongOrNull()?.let { retry = it.coerceAtMost(30_000); touched = true }
                }
                "data" -> {
                    size += value.toByteArray(Charsets.UTF_8).size + 1
                    if (size > limit) throw McpTransportClosed("MCP SSE 消息超过 $limit 字节上限，已断开连接。")
                    data += value; touched = true
                }
            }
        }
    }
    // SSE 只在空行处提交事件；EOF 的半帧要留给 Last-Event-ID 续传，不能当作完整响应。
}

internal suspend fun Flow<ByteArray>.boundedBytes(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    collect {
        if (it.size > limit - out.size()) throw McpTransportClosed("MCP HTTP 消息超过 $limit 字节上限，已断开连接。")
        out.write(it)
    }
    return out.toByteArray()
}

internal fun safeUrl(url: String): String = url.substringBefore('?').substringBefore('#').replace(Regex("//[^/]*@"), "//")
internal fun safeLog(line: String): String = line
    .replace(Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s]+")) { safeUrl(it.value) }
    .replace(Regex("""(?i)("(?:proxy-)?authorization"\s*:\s*)"(?:\\.|[^"\\])*""""), "$1\"***\"")
    .replace(Regex("""(?im)((?:proxy-)?authorization\s*[:=]\s*)[^\r\n]+"""), "$1***")
    .replace(Regex("""(?i)\bBearer\s+[^\s,;"'}]+"""), "Bearer ***")
    .replace(Regex("""(?i)(\b[\w-]*(?:token|secret|password|key)[\w-]*\s*=\s*)(?:"[^"]*"|'[^']*'|[^\s&;,]+)"""), "$1***")
