package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.FallbackModelSetting
import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.llm.Wire
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelFallback
import dev.min.agent.spi.TurnOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class FallbackModelsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private fun args(vararg argv: String) = parseArgs(argv.toList().toTypedArray(), Path.of("."))
    private val catalog = Catalog.bundled()

    @Test fun `flag parses and is absent by default`() {
        assertEquals("a,b", args("--model", "m", "--fallback-model", "a,b").fallbackModel)
        assertNull(args("--model", "m").fallbackModel)
    }

    @Test fun `same provider entries inherit the address but not the window and cross provider entries use their own catalog defaults`() {
        val a = args(
            "--provider", "local", "--model", "m", "--base-url", "http://localhost/v1", "--wire", "openai_chat",
            "--context", "32000", "--api-key-env", "LOCAL_TOKEN", "--fallback-model", "backup,deepseek:v-x",
        )
        val own = Catalog(listOf(ProviderDescriptor("local", "http://localhost/v1", Wire.OpenAIChat, AuthScheme.Bearer)) + catalog.providers, catalog.models)
        val primary = resolveModel(a, own)
        val fb = resolveFallbackModels(a, own, null, primary)
        assertEquals(listOf(ModelRef("local", "backup"), ModelRef("deepseek", "v-x")), fb.chain)
        val same = fb.selections.getValue(ModelRef("local", "backup"))
        assertEquals("http://localhost/v1", same.provider.baseUrl)
        assertEquals("LOCAL_TOKEN", same.keyEnv)
        assertEquals("窗口是给主模型指定的，不沿用", 200_000, same.profile.contextWindow)
        val cross = fb.selections.getValue(ModelRef("deepseek", "v-x"))
        assertEquals(catalog.provider("deepseek")?.baseUrl, cross.provider.baseUrl)
        assertEquals("DEEPSEEK_API_KEY", cross.keyEnv)
    }

    @Test fun `a custom provider that has no address of its own cannot back a cross provider entry`() {
        // mine 是命令行现场指定的自定义供应商；链上写 other:x，other 不是已知供应商，整串当 mine 上的模型 id，照常选得出
        val a = args("--provider", "mine", "--model", "m", "--base-url", "http://h/v1", "--wire", "openai_chat", "--fallback-model", "other:x")
        val empty = Catalog(emptyList(), emptyList())
        val fb = resolveFallbackModels(a, empty, null, resolveModel(a, empty))
        assertEquals(listOf(ModelRef("mine", "other:x")), fb.chain)
        assertTrue(fb.notes.isEmpty())
    }

    @Test fun `project settings cannot send the conversation to another provider but the flag can`() {
        val a = args("--provider", "anthropic", "--model", "claude-sonnet-5")
        val primary = resolveModel(a, catalog)
        val fromProject = resolveFallbackModels(a, catalog, FallbackModelSetting(listOf("claude-haiku-4-5", "deepseek:v-x"), ConfigSource.Project), primary)
        assertEquals(listOf(ModelRef("anthropic", "claude-haiku-4-5")), fromProject.chain)
        assertTrue(fromProject.notes.single().contains("deepseek:v-x"))
        val flag = args("--provider", "anthropic", "--model", "claude-sonnet-5", "--fallback-model", "deepseek:v-x")
        assertEquals(1, resolveFallbackModels(flag, catalog, FallbackModelSetting(listOf("zzz"), ConfigSource.Project), primary).chain.size)
    }

    /** 本地假模型：按请求里的 model 字段路由；「gone」这个模型回 404 model_not_found，其余正常答 */
    private class ModelServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val models = CopyOnWriteArrayList<String>()
        val baseUrl get() = "http://127.0.0.1:${server.address.port}"
        init {
            server.createContext("/chat/completions") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().decodeToString() }
                val model = Json.parseToJsonElement(body).jsonObject.getValue("model").jsonPrimitive.content
                models += model
                if (model == "gone") {
                    val payload = """{"error":{"code":"model_not_found","message":"The model `gone` does not exist"}}""".encodeToByteArray()
                    exchange.sendResponseHeaders(404, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                } else {
                    val delta = """{"id":"r","choices":[{"index":0,"delta":{"role":"assistant","content":"from $model"},"finish_reason":"stop"}]}"""
                    val payload = ("data: $delta\n\ndata: [DONE]\n\n").encodeToByteArray()
                    exchange.responseHeaders.add("content-type", "text/event-stream")
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                }
            }
            server.start()
        }
        override fun close() { server.stop(0) }
    }

    private fun argv(server: ModelServer, cwd: Path, vararg extra: String) = arrayOf(
        "--provider", "desktop-test", "--model", "gone", "--base-url", server.baseUrl, "--wire", "openai_chat",
        "--mode", "bypassPermissions", "--cwd", cwd.toString(),
        "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER", *extra,
    )

    @Test fun `end to end the flag carries a turn past an unavailable primary and is not restored on resume`() = runBlocking {
        withTimeout(30_000) {
            ModelServer().use { server ->
                val home = tmp.newFolder("home").toPath()
                val cwd = tmp.newFolder("work").toPath()
                val app = DesktopApp(parseArgs(argv(server, cwd, "--fallback-model", "backup"), cwd), scope(), home, claudeHome = home.resolve("claude-home"))
                val rt = app.openSession()
                rt.send(ClientMsgId("u1"), listOf(TextPart("hi")))
                val done = rt.state.first { it.turn == null && it.lastTurn != null }
                assertEquals(TurnOutcome.Completed, done.lastTurn?.outcome)
                assertEquals(listOf("gone", "backup"), server.models.toList())
                val fallback = app.records(rt.sessionId, 0).map { it.event }.filterIsInstance<ModelFallback>().single()
                assertEquals(ModelFailureKind.ModelUnavailable, fallback.reason)
                assertEquals("backup", fallback.to.modelId)

                // 重开会话不带 --fallback-model：回退链没有恢复，主模型不可用就是失败
                server.models.clear()
                val reopened = DesktopApp(
                    parseArgs(argv(server, cwd, "--resume", rt.sessionId.value), cwd), scope(), home, claudeHome = home.resolve("claude-home"),
                ).openSession()
                reopened.send(ClientMsgId("u2"), listOf(TextPart("again")))
                val failed = reopened.state.first { it.turn == null && it.lastTurn?.turnId != done.lastTurn?.turnId }
                assertTrue(failed.lastTurn?.outcome is TurnOutcome.Failed)
                assertEquals(listOf("gone"), server.models.toList())
            }
        }
    }
}
