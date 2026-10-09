package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.host.UrlConnectionTransport
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ToolSpec
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.llm.openai.OpenAIChatClient
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import dev.min.agent.spi.Secret
import dev.min.agent.spi.Secrets
import dev.min.agent.tools.BashTool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture

/** 手写请求快照钉住实际发送的工具指导，避免付费模型跑过后却把有效规矩从前缀删掉。 */
class BashRequestSnapshotTest {
    @Test fun `Chat sends the Bash guidance and schema without rewriting the command contract`() {
        val received = CompletableFuture<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            received.complete(exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) })
            val stream = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, stream.size.toLong())
            exchange.responseBody.use { it.write(stream) }
        }
        server.start()
        try {
            val catalog = Catalog.bundled()
            val profile = requireNotNull(catalog.model(ModelRef("deepseek-openai", "deepseek-flash")))
            val provider = requireNotNull(catalog.provider(profile.ref.providerId)).copy(baseUrl = "http://127.0.0.1:${server.address.port}")
            val harness = HarnessProfile(profile.family, profile.editStyle)
            val request = ModelRequest(profile = profile, system = listOf("stable"), tools = listOf(ToolSpec(BashTool.name, BashTool.description(harness), BashTool.inputSchema)), messages = listOf(ModelMessage(Role.User, listOf(TextPart("run tests")))))
            runBlocking { OpenAIChatClient(provider, UrlConnectionTransport(), Secrets { Secret("test-local") }).stream(request).toList() }
            val actual = (Json.parseToJsonElement(received.get()) as JsonObject)["tools"] as JsonArray
            val expected = Json.parseToJsonElement("""
                [{"type":"function","function":{"name":"Bash",
                  "description":"在 shell 里执行一条命令，返回输出（stdout 与 stderr 合并）与退出码；退出码非 0 视为失败。构建与测试请原样执行用户给出的命令，保留它的最终退出码；不要追加 echo 或用 head/tail 管道掩盖失败。工具会裁剪长输出，无需为截断输出改写命令。每条命令都从会话目录开始执行，cd 与 export 不会保留到下一次调用——需要换目录就在同一条命令里写 cd x && ...。默认超时 120 秒，最大 600 秒。命令前台运行超过 10 秒还没结束会自动转入后台，此时会返回句柄与日志路径，用 Read 看日志。读写文件、搜索请优先用 Read / Write / Edit / Glob / Grep，不要用 cat / sed / grep / find 代替。",
                  "parameters":{"type":"object","required":["command"],"properties":{
                    "command":{"type":"string","description":"要执行的 shell 命令"},
                    "timeout":{"type":"integer","description":"超时毫秒数，默认 120000，最大 600000（更大的值按最大算）"},
                    "description":{"type":"string","description":"用一句话说明这条命令做什么（给用户看）"},
                    "run_in_background":{"type":"boolean","description":"true 则立刻转入后台运行，返回句柄与日志路径；适合开发服务器等长时间进程"},
                    "allowed_domains":{"type":"array","items":{"type":"string","description":"本命令需要的域名，可带 *. 通配与 :端口"},"description":"沙箱开启的 auto 模式下，由分类器随命令批准；仅本命令有效，拒绝规则与托管锁继续生效"}}}}}]
            """.trimIndent())
            assertEquals(expected, actual)
        } finally { server.stop(0) }
    }
}
