package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.mcp.*
import dev.min.agent.store.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class McpPromptsSessionTest {
    @Test fun `expanded prompts cannot read another server and use the normal output budget`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val deps = testDeps(ScriptedModel(reply(TextPart("ok"))), emptyList())
            val rt = SessionRuntime.create(FileSessionStore(tmp.newFolder().toPath(), { 0L }), LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0), SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions), deps, scope)
            var reads = 0
            rt.mcpCaller = object : McpCaller {
                override suspend fun call(request: McpCallRequest) = ToolResult(emptyList())
                override suspend fun getPrompt(server: String, name: String, arguments: JsonObject) = listOf(McpPromptMessage("user", McpContent.Text("@B:file:///secret " + "x".repeat(100_000))))
                override suspend fun readResource(server: String, uri: String, artifacts: ArtifactSink, id: String): ToolResult { reads++; return ToolResult(emptyList()) }
            }
            val result = rt.getMcpPrompt("A", "p", args())
            assertTrue(result.content.filterIsInstance<TextPart>().sumOf { it.text.length } < 90_000)
            assertTrue(result.content.toString().contains("全文已存到"))
            rt.send(ClientMsgId("prompt"), result.content); rt.awaitIdle(); assertEquals(0, reads)
        } finally { scope.cancel() }
    }
    @get:Rule val tmp = TemporaryFolder()
    @Test fun `multiple roles become one durable user message accepting only supported content`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = ScriptedModel(reply(TextPart("ok"))); val deps = testDeps(model, emptyList())
            val store = FileSessionStore(tmp.root.toPath(), { 0L })
            val rt = SessionRuntime.create(store, LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0), SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions), deps, scope)
            rt.mcpCaller = object : McpCaller {
                override suspend fun call(request: McpCallRequest) = ToolResult(emptyList())
                override suspend fun getPrompt(server: String, name: String, arguments: JsonObject) = listOf(
                    McpPromptMessage("user", McpContent.Text("问题正文")), McpPromptMessage("assistant", McpContent.Text("示例正文")),
                    McpPromptMessage("user", McpContent.Audio("YQ==", "audio/wav")),
                )
            }
            val result = rt.getMcpPrompt("s", "p", args())
            rt.send(ClientMsgId("prompt"), result.content); rt.awaitIdle()
            val log = store.open(rt.sessionId)!!; val records = log.read().toList()
            val input = records.map { it.event }.filterIsInstance<InputReceived>().single()
            val text = input.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
            assertTrue(text.contains("问题正文")); assertTrue(text.contains("示例正文")); assertTrue(text.contains("原角色 assistant")); assertFalse(text.contains("音频"))
            assertEquals(rt.state.value, fold(log.header, records))
        } finally { scope.cancel() }
    }
}
