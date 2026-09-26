package dev.min.code.core.relay

import android.util.Log
import dev.min.code.core.settings.joinClaudeApi
import dev.min.code.core.settings.joinOpenAiApi
import dev.min.code.core.settings.normalizeBaseUrl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LocalRelay"

/**
 * 机内的协议路由。
 *
 * 只绑 `127.0.0.1`，路径里带一枚进程内随机 token —— 同机别的 App 就算扫到端口
 * 也拼不出完整路径。proot 不隔离网络命名空间，CLI 子进程访问 `127.0.0.1:port`
 * 就是打到这里，这是整条路能成立的前提。
 *
 * ## 路径
 *
 * ```
 * POST /r/{token}/claude/{profileId}/v1/messages
 * POST /r/{token}/codex/{profileId}/v1/chat/completions   （Responses → Chat 时）
 * POST /r/{token}/codex/{profileId}/v1/responses          （透传 / 反向）
 * ```
 *
 * [Resolver] 按 profileId 给出上游地址、key、方言。服务器本身不持有任何密钥。
 */
class LocalRelayServer(
    private val resolver: Resolver,
) {
    fun interface Resolver {
        fun resolve(kind: Kind, profileId: String): Upstream?
    }

    enum class Kind { Claude, Codex }

    data class Upstream(
        val baseUrl: String,
        val token: String,
        /** Claude 侧：要不要把 Anthropic 请求转成 Chat Completions */
        val toChatCompletions: Boolean,
        /**
         * 把 [baseUrl] 当完整端点原样请求，不再拼 `/v1/messages` 或 `/v1/chat/completions`。
         * 见 [dev.min.code.core.settings.ApiProfile.fullUrlEndpoint]。
         */
        val fullUrlEndpoint: Boolean = false,
        /** 上游要哪种头 */
        val authHeader: AuthStyle = AuthStyle.Bearer,
        val modelOverride: String? = null,
    )

    enum class AuthStyle { Bearer, ApiKey }

    private val token: String = UUID.randomUUID().toString().replace("-", "")
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "min-relay").apply { isDaemon = true }
    }

    @Volatile private var boundPort: Int = -1

    val port: Int get() = boundPort
    val pathToken: String get() = token
    val isRunning: Boolean get() = running.get()

    /** Claude 侧给 CLI 的 base URL：`http://127.0.0.1:{port}/r/{token}/claude/{profileId}` */
    fun claudeBaseUrl(profileId: String): String =
        "http://127.0.0.1:$boundPort/r/$token/claude/$profileId"

    fun codexBaseUrl(profileId: String): String =
        "http://127.0.0.1:$boundPort/r/$token/codex/$profileId"

    @Synchronized
    fun start() {
        if (running.get()) return
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = ss
        boundPort = ss.localPort
        running.set(true)
        pool.execute {
            while (running.get()) {
                try {
                    val socket = ss.accept()
                    pool.execute { handle(socket) }
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed", e)
                }
            }
        }
        Log.i(TAG, "listening on 127.0.0.1:$boundPort")
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { server?.close() }
        server = null
        boundPort = -1
        Log.i(TAG, "stopped")
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 120_000
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val req = readRequest(input) ?: run {
                writeResponse(output, 400, "text/plain", "bad request".toByteArray())
                return
            }
            if (req.method == "OPTIONS") {
                writeResponse(output, 204, "text/plain", ByteArray(0), cors = true)
                return
            }
            route(req, output)
        } catch (e: Exception) {
            Log.w(TAG, "handle failed", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun route(req: HttpRequest, output: OutputStream) {
        val parts = req.path.trim('/').split('/')
        // r / {token} / {claude|codex} / {profileId} / …
        if (parts.size < 4 || parts[0] != "r" || parts[1] != token) {
            writeResponse(output, 404, "text/plain", "not found".toByteArray())
            return
        }
        val kind = when (parts[2]) {
            "claude" -> Kind.Claude
            "codex" -> Kind.Codex
            else -> {
                writeResponse(output, 404, "text/plain", "not found".toByteArray())
                return
            }
        }
        val profileId = parts[3]
        val rest = parts.drop(4).joinToString("/", prefix = "/")
        val upstream = resolver.resolve(kind, profileId)
        if (upstream == null) {
            writeResponse(output, 404, "application/json",
                """{"type":"error","error":{"type":"not_found","message":"provider gone"}}""".toByteArray())
            return
        }
        when (kind) {
            Kind.Claude -> proxyClaude(req, rest, upstream, output)
            Kind.Codex -> proxyCodex(req, rest, upstream, output)
        }
    }

    private fun proxyClaude(req: HttpRequest, rest: String, upstream: Upstream, output: OutputStream) {
        val bodyText = req.body.toString(Charsets.UTF_8)
        val anthropic = runCatching {
            relayJson.parseToJsonElement(bodyText).jsonObject
        }.getOrNull()
        if (anthropic == null) {
            writeResponse(output, 400, "application/json",
                OpenAIToAnthropic.convertError(400, "invalid json").toString().toByteArray())
            return
        }
        val stream = anthropic.bool("stream") == true
        val model = anthropic.str("model") ?: upstream.modelOverride.orEmpty()

        if (!upstream.toChatCompletions) {
            // 方言是原生的却走了路由？不该发生，原样转发一次兜底
            forwardRaw(req, upstreamUrl(upstream, rest.ifBlank { "/v1/messages" }, claudeStyle = true), upstream, output)
            return
        }

        val openaiReq = AnthropicToOpenAI.convertRequest(anthropic, upstream.modelOverride)
        // Claude 的 base 约定不带 /v1；用户抄 OpenAI 地址时常带着，joinClaudeApi 会剥掉
        val target = if (upstream.fullUrlEndpoint) {
            normalizeBaseUrl(upstream.baseUrl)
        } else {
            joinClaudeApi(upstream.baseUrl, "/v1/chat/completions")
        }
        val upstreamResp = postJson(target, openaiReq.toString().toByteArray(), upstream, stream)
        if (upstreamResp == null) {
            writeResponse(output, 502, "application/json",
                OpenAIToAnthropic.convertError(502, "upstream unreachable").toString().toByteArray())
            return
        }
        if (upstreamResp.code !in 200..299) {
            val err = OpenAIToAnthropic.convertError(upstreamResp.code, upstreamResp.body.toString(Charsets.UTF_8))
            writeResponse(output, upstreamResp.code, "application/json", err.toString().toByteArray())
            return
        }
        if (!stream) {
            // 中转网关可能回 HTML 插页/空体：解析不了就回 502，别让异常冒成连接重置
            val chat = runCatching {
                relayJson.parseToJsonElement(upstreamResp.body.toString(Charsets.UTF_8)).jsonObject
            }.getOrElse {
                writeResponse(output, 502, "application/json",
                    OpenAIToAnthropic.convertError(502, "upstream returned non-JSON").toString().toByteArray())
                return
            }
            val converted = OpenAIToAnthropic.convertResponse(chat, model)
            writeResponse(output, 200, "application/json", converted.toString().toByteArray())
            return
        }
        // 流式：把上游 SSE 逐条转成 Anthropic 事件
        writeSseHeaders(output)
        val converter = OpenAIToAnthropic.StreamConverter(model)
        try {
            readSse(upstreamResp.stream ?: upstreamResp.body.inputStream()) { data ->
                val chunk = runCatching {
                    relayJson.parseToJsonElement(data).jsonObject
                }.getOrNull() ?: return@readSse
                converter.onChunk(chunk).forEach { frame ->
                    output.write(frame.toByteArray())
                    output.flush()
                }
            }
            converter.finish().forEach { frame ->
                output.write(frame.toByteArray())
                output.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stream failed", e)
        } finally {
            upstreamResp.close()
        }
    }

    private fun proxyCodex(req: HttpRequest, rest: String, upstream: Upstream, output: OutputStream) {
        // Codex 侧：如果 upstream.toChatCompletions，把 /v1/responses 转成 /v1/chat/completions
        val bodyText = req.body.toString(Charsets.UTF_8)
        if (upstream.toChatCompletions && rest.endsWith("/responses")) {
            val responses = runCatching {
                relayJson.parseToJsonElement(bodyText).jsonObject
            }.getOrNull()
            if (responses == null) {
                writeResponse(output, 400, "application/json", """{"error":"invalid json"}""".toByteArray())
                return
            }
            val chatReq = ResponsesBridge.toChatCompletions(responses)
            // Codex base 常常已经以 /v1 结尾；joinOpenAiApi 不会再叠一层
            val target = if (upstream.fullUrlEndpoint) {
                upstream.baseUrl.trimEnd('/')
            } else {
                joinOpenAiApi(upstream.baseUrl, "/chat/completions")
            }
            val stream = responses.bool("stream") == true
            val upstreamResp = postJson(target, chatReq.toString().toByteArray(), upstream, stream)
            if (upstreamResp == null) {
                writeResponse(output, 502, "application/json", """{"error":"upstream unreachable"}""".toByteArray())
                return
            }
            if (upstreamResp.code !in 200..299) {
                writeResponse(output, upstreamResp.code, "application/json", upstreamResp.body)
                upstreamResp.close()
                return
            }
            if (!stream) {
                // 同 proxyClaude：上游回非 JSON 时给 502，别让异常冒成连接重置
                val chat = runCatching {
                    relayJson.parseToJsonElement(upstreamResp.body.toString(Charsets.UTF_8)).jsonObject
                }.getOrElse {
                    writeResponse(output, 502, "application/json",
                        """{"error":"upstream returned non-JSON"}""".toByteArray())
                    upstreamResp.close()
                    return
                }
                val converted = ResponsesBridge.fromChatCompletions(chat)
                writeResponse(output, 200, "application/json", converted.toString().toByteArray())
                upstreamResp.close()
                return
            }
            // 流式暂原样透传 chat 的 SSE —— Codex 的 app-server 对 chat wire_api
            // 本来就能直接吃；走到这里多半是 responses→chat 的兼容路径
            writeSseHeaders(output)
            try {
                (upstreamResp.stream ?: upstreamResp.body.inputStream()).copyTo(output)
                output.flush()
            } finally {
                upstreamResp.close()
            }
            return
        }
        forwardRaw(req, upstreamUrl(upstream, rest, claudeStyle = false), upstream, output)
    }

    private fun upstreamUrl(upstream: Upstream, rest: String, claudeStyle: Boolean): String {
        if (upstream.fullUrlEndpoint) {
            return if (claudeStyle) normalizeBaseUrl(upstream.baseUrl) else upstream.baseUrl.trimEnd('/')
        }
        // Claude：base 不带 /v1，rest 通常是 /v1/messages —— 用 joinClaudeApi 防双写
        // Codex：base 常带 /v1，rest 是 /responses 或 /v1/responses；后者要先剥掉多余的 /v1
        if (claudeStyle) {
            return joinClaudeApi(upstream.baseUrl, rest.ifBlank { "/v1/messages" })
        }
        val normalizedRest = rest.trimEnd('/').ifBlank { "/responses" }
        val resource = when {
            normalizedRest.equals("/v1", ignoreCase = true) -> "/responses"
            normalizedRest.length >= 3 && normalizedRest.regionMatches(0, "/v1", 0, 3, ignoreCase = true) &&
                (normalizedRest.length == 3 || normalizedRest[3] == '/') ->
                normalizedRest.substring(3).ifBlank { "/responses" }
            else -> normalizedRest
        }
        return joinOpenAiApi(upstream.baseUrl, resource)
    }

    private fun forwardRaw(req: HttpRequest, url: String, upstream: Upstream, output: OutputStream) {
        val resp = postJson(url, req.body, upstream, acceptStream = true) ?: run {
            writeResponse(output, 502, "text/plain", "upstream unreachable".toByteArray())
            return
        }
        try {
            writeResponse(output, resp.code, resp.contentType, resp.body, stream = resp.stream)
        } finally {
            resp.close()
        }
    }

    // -----------------------------------------------------------------------
    // HTTP 原语
    // -----------------------------------------------------------------------

    private class UpstreamResponse(
        val code: Int,
        val contentType: String,
        val body: ByteArray,
        val stream: InputStream?,
        private val connection: HttpURLConnection,
    ) {
        fun close() {
            runCatching { stream?.close() }
            runCatching { connection.disconnect() }
        }
    }

    private fun postJson(
        url: String,
        body: ByteArray,
        upstream: Upstream,
        acceptStream: Boolean,
    ): UpstreamResponse? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 600_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", if (acceptStream) "text/event-stream, application/json" else "application/json")
                when (upstream.authHeader) {
                    AuthStyle.Bearer -> setRequestProperty("Authorization", "Bearer ${upstream.token}")
                    AuthStyle.ApiKey -> setRequestProperty("x-api-key", upstream.token)
                }
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val contentType = conn.contentType ?: "application/json"
            val stream = try {
                if (code in 200..299) conn.inputStream else conn.errorStream
            } catch (_: Exception) {
                null
            }
            if (acceptStream && contentType.contains("text/event-stream")) {
                UpstreamResponse(code, contentType, ByteArray(0), stream, conn)
            } else {
                val bytes = stream?.readBytes() ?: ByteArray(0)
                stream?.close()
                UpstreamResponse(code, contentType, bytes, null, conn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "upstream POST $url failed", e)
            null
        }
    }

    private fun writeSseHeaders(output: OutputStream) {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** 读上游 SSE，对每一条 `data:` 回调一次（多行 data 拼成一条） */
    private fun readSse(input: InputStream, onData: (String) -> Unit) {
        val reader = input.bufferedReader(Charsets.UTF_8)
        val data = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trim())
                }
                line.isEmpty() -> {
                    if (data.isNotEmpty() && data.toString() != "[DONE]") {
                        onData(data.toString())
                    }
                    data.clear()
                }
            }
        }
        if (data.isNotEmpty() && data.toString() != "[DONE]") onData(data.toString())
    }
}
