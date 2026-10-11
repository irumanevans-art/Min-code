package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/*
 * 无头测试共用的假模型（HeadlessCompatTest 与 StreamJsonTest 同用一份）：本地 OpenAI Chat SSE 服务 + 剧本响应构造。
 */

/** 本地假模型（照 DesktopSubagentTest 的做法）：OpenAI Chat SSE；route 返回 null = 回 400（不可重试） */
internal class ModelServer(val route: (String) -> String?) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<JsonObject>()
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.use { it.readBytes().decodeToString() }
            val request = Json.parseToJsonElement(body).jsonObject
            requests += request
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
