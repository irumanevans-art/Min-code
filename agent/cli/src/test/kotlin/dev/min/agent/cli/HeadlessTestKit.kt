package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/*
 * 无头测试共用的假模型（HeadlessCompatTest 与 StreamJsonTest 同用一份）：本地 OpenAI Chat SSE 服务 + 剧本响应构造。
 */

/**
 * 本地假模型（照 DesktopSubagentTest 的做法）：OpenAI Chat SSE；route 返回 null = 回 400（不可重试）。
 * [statusFor]（可设）：按「第几个请求（从 0 数）」给一个 HTTP 状态码就回这个错误（带 `retry-after: 0`，重试不用等）；null = 照 [route] 走。
 * 单线程分发：route 里可以阻塞住一个请求（测中断时用），后面的请求排队到它放开为止
 */
internal class ModelServer(val route: (String) -> String?) : AutoCloseable {
    /** 第几个请求（从 0 数）给 HTTP 错误状态；默认都走 [route] */
    @Volatile var statusFor: (Int) -> Int? = { null }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<JsonObject>()
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.use { it.readBytes().decodeToString() }
            val request = Json.parseToJsonElement(body).jsonObject
            val index = requests.size
            requests += request
            statusFor(index)?.let { status ->
                val payload = """{"error":{"message":"CC_STATUS_$status"}}""".encodeToByteArray()
                exchange.responseHeaders.add("content-type", "application/json")
                exchange.responseHeaders.add("retry-after", "0")
                exchange.sendResponseHeaders(status, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
                return@createContext
            }
            val messages = request.getValue("messages").jsonArray
            // 结构化输出的重试提醒是相邻的 user 消息，取最后一段 user 文本当路由键
            val tail = if (messages.last().jsonObject["role"]?.jsonPrimitive?.content == "user") {
                messages.reversed().takeWhile { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }.reversed()
            } else listOf(messages.last())
            val last = tail.joinToString("\n") {
                val content = it.jsonObject["content"]
                (content as? JsonPrimitive)?.content ?: content.toString()
            }
            val answer = route(last)
            if (answer == null) {
                val payload = """{"error":{"message":"CC_FAIL_BAD_REQUEST"}}""".encodeToByteArray()
                exchange.responseHeaders.add("content-type", "application/json")
                exchange.sendResponseHeaders(400, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } else {
                val payload = ("data: " + answer + "\n\ndata: [DONE]\n\n").encodeToByteArray()
                exchange.responseHeaders.add("content-type", "text/event-stream")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        }
        server.start()
    }

    override fun close() { server.stop(0) }
}

internal fun textResponse(text: String) = response("stop", buildJsonObject { put("role", "assistant"); put("content", text) })

internal fun toolResponse(id: String, name: String, input: JsonObject) = response("tool_calls", buildJsonObject {
    put("role", "assistant")
    put("tool_calls", buildJsonArray {
        add(buildJsonObject {
            put("index", 0); put("id", id); put("type", "function")
            put("function", buildJsonObject { put("name", name); put("arguments", input.toString()) })
        })
    })
})

internal fun response(stop: String, delta: JsonObject) = buildJsonObject {
    put("id", "response")
    put("choices", buildJsonArray { add(buildJsonObject {
        put("index", 0); put("delta", delta); put("finish_reason", stop)
    }) })
}.toString()

/** 指向假模型的基线参数（bypassPermissions，不读真实凭证）；各测试在后面接自己的 -p / 输出格式 */
internal fun baselineArgv(server: ModelServer, work: java.nio.file.Path): Array<String> = arrayOf(
    "--provider", "desktop-test", "--model", "scripted", "--base-url", server.baseUrl,
    "--wire", "openai_chat", "--mode", "bypassPermissions",
    // 本地模型只需要存在的普通环境变量，不读真实凭证
    "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER",
    "--cwd", work.toString(),
)

/** 一条助手消息里并发的几次工具调用（id, 工具名, 入参）：分块的 tool_calls，下标各自独立 */
internal fun toolsResponse(vararg calls: Triple<String, String, JsonObject>, text: String? = null) = response("tool_calls", buildJsonObject {
    put("role", "assistant")
    text?.let { put("content", it) }
    put("tool_calls", buildJsonArray {
        calls.forEachIndexed { i, (id, name, input) ->
            add(buildJsonObject {
                put("index", i); put("id", id); put("type", "function")
                put("function", buildJsonObject { put("name", name); put("arguments", input.toString()) })
            })
        }
    })
})

/** stream-json 一次运行的产物：退出码、stdout / stderr、会话 id、假模型收到的请求 */
internal class StreamOut(val exit: Int, val stdout: String, val stderr: String, val sessionId: String, val requests: List<JsonObject>) {
    val lines: List<JsonObject> by lazy { parseLines(stdout) }
    fun ofType(type: String) = lines.filter { it["type"]?.jsonPrimitive?.contentOrNull == type }
    fun system(subtype: String) = lines.filter { it["type"]?.jsonPrimitive?.contentOrNull == "system" && it["subtype"]?.jsonPrimitive?.contentOrNull == subtype }
    val results get() = ofType("result")
    val assistants get() = ofType("assistant")
    val controlResponses get() = ofType("control_response")
}

internal fun parseLines(text: String): List<JsonObject> =
    text.lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList()

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** 可以由测试一行一行喂的 stdin：读端阻塞到有数据或被关闭 */
internal class QueueReader : java.io.Reader() {
    private val queue = java.util.concurrent.LinkedBlockingQueue<String>()
    private var current: String? = null
    private var pos = 0
    private val EOF = "\u0000EOF"

    fun write(text: String) { queue.put(text) }
    fun close_() { queue.put(EOF) }

    override fun read(buf: CharArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (current == null || pos >= current!!.length) {
            val next = queue.take()
            if (next == EOF) { queue.put(EOF); return -1 }
            current = next; pos = 0
        }
        val n = minOf(len, current!!.length - pos)
        current!!.toCharArray(pos, pos + n).copyInto(buf, off)
        pos += n
        return n
    }

    override fun close() { close_() }
}

/**
 * 进行中的 stream-json 运行：测试在另一边一行行读它的 stdout、往 stdin 写行、最后关 stdin 收结果。
 * 用来测要「看到 control_request 再回话」的流程（权限询问、中断）
 */
internal class LiveRun(
    private val stdin: QueueReader,
    private val stdout: java.io.ByteArrayOutputStream,
    private val stderr: java.io.ByteArrayOutputStream,
    private val job: kotlinx.coroutines.Deferred<Int>,
    private val sessionId: () -> String,
    private val server: ModelServer,
) {
    fun send(line: String) = stdin.write(line.trimEnd('\n') + "\n")
    fun closeStdin() = stdin.close_()

    fun linesNow(): List<JsonObject> = parseLines(stdout.toString("UTF-8").substringBeforeLast('\n', ""))

    /** 等到 stdout 里出现满足 [pred] 的行（超时失败，附上当时的输出便于诊断） */
    suspend fun awaitLine(timeoutMs: Long = 30_000, pred: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            linesNow().firstOrNull(pred)?.let { return it }
            kotlinx.coroutines.delay(20)
        }
        throw AssertionError("等不到期望的输出行。stdout：\n${stdout.toString("UTF-8")}\nstderr：\n${stderr.toString("UTF-8")}")
    }

    /** 等假模型收到至少 [n] 个请求 */
    suspend fun awaitRequests(n: Int, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (server.requests.size < n && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(20)
        if (server.requests.size < n) throw AssertionError("假模型只收到 ${server.requests.size} 个请求，期望 $n 个。stdout：\n${stdout.toString("UTF-8")}")
    }

    /** 关 stdin、等运行结束 */
    suspend fun finish(timeoutMs: Long = 60_000): StreamOut {
        closeStdin()
        val exit = kotlinx.coroutines.withTimeout(timeoutMs) { job.await() }
        return StreamOut(exit, stdout.toString("UTF-8"), stderr.toString("UTF-8"), sessionId(), server.requests.toList())
    }
}

/** 起一次 stream-json 运行（与 Main 同一条 runHeadless），立即返回，由 [LiveRun] 驱动 */
internal suspend fun startLive(
    root: java.nio.file.Path, scope: kotlinx.coroutines.CoroutineScope, name: String, server: ModelServer, extra: List<String>,
): LiveRun {
    val home = java.nio.file.Files.createDirectories(root.resolve("$name-home"))
    val work = java.nio.file.Files.createDirectories(root.resolve("$name-work"))
    val args = parseArgs(baselineArgv(server, work) + extra.toTypedArray(), work)
    val stdin = QueueReader()
    val out = java.io.ByteArrayOutputStream()
    val err = java.io.ByteArrayOutputStream()
    val app = DesktopApp(args, scope, home, claudeHome = java.nio.file.Files.createDirectories(home.resolve("claude-home")))
    app.checkKey()
    val runtime = app.openSession()
    val job = scope.async(kotlinx.coroutines.Dispatchers.Default) {
        try {
            runHeadless(
                app, runtime, args, scope,
                java.io.BufferedReader(stdin), java.io.PrintStream(out, true, "UTF-8"), java.io.PrintStream(err, true, "UTF-8"),
            )
        } finally {
            app.exitSession(runtime).join()
        }
    }
    return LiveRun(stdin, out, err, job, { runtime.sessionId.value }, server)
}
