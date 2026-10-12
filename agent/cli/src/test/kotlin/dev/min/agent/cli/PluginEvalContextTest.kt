package dev.min.agent.cli

import dev.min.agent.protocol.*
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginEvalContextTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun fixture(): EvalSuite {
        val root = tmp.newFolder().toPath()
        Files.createDirectories(root.resolve(".claude-plugin"))
        Files.writeString(root.resolve(".claude-plugin/plugin.json"), """{"name":"demo"}""")
        val c = Files.createDirectories(root.resolve("evals/check/graders"))
        Files.writeString(c.parent.resolve("prompt.md"), "---\nallowed_tools: [Read]\n---\ninspect")
        Files.writeString(c.resolve("ok.md"), "---\ntype: regex\n---\nok")
        return PluginEval.load(root)
    }
    @Test fun `context fixture 可读取但不能写入且不发现其中配置`(): Unit = runBlocking {
        val suite = fixture(); val case = suite.cases.single()
        Files.createDirectories(case.dir.resolve("resources/.claude"))
        Files.writeString(case.dir.resolve("resources/value.txt"), "fixture-marker")
        Files.writeString(case.dir.resolve("resources/CLAUDE.md"), "FORBIDDEN_CONFIG")
        val updated = case.copy(fields = JsonObject(case.fields + ("context" to buildJsonObject { put("add_dirs", JsonArray(listOf(JsonPrimitive("resources")))) })))
        val model = ScriptedModel(reply(ToolCallPart(CallId("read"), "Read", args("file_path" to "/fixtures/0/value.txt")), stop = StopReason.ToolUse),
            reply(ToolCallPart(CallId("write"), "Write", args("file_path" to "/fixtures/0/value.txt", "content" to "changed")), stop = StopReason.ToolUse), reply(TextPart("ok")))
        val root = Files.createTempDirectory("min-plugin-eval-context-")
        try {
            val e = LocalEvalKernel(suite, parseArgs(arrayOf("--model", "test", "-p", "eval"), suite.plugin), listOf("Write"), true) { ResolvedModel(TEST_PROFILE, model) }.run(updated, true, root)
            assertNull(e.error)
            assertTrue(model.requests[1].messages.toString().contains("fixture-marker"))
            assertTrue(model.requests[2].messages.toString().contains("isError=true"))
            assertTrue("写请求必须真正抵达只读挂载边界", model.requests[2].messages.toString().contains("只读"))
            assertFalse(model.requests.flatMap { it.system }.toString().contains("FORBIDDEN_CONFIG"))
            assertEquals("fixture-marker", Files.readString(case.dir.resolve("resources/value.txt")))
        } finally { deleteEvalTemp(root) }
    }
    @Test fun `history 是下一用户回合之前的对话`(): Unit = runBlocking {
        val suite = fixture(); val case = suite.cases.single()
        Files.writeString(case.dir.resolve("history.jsonl"), """{"type":"user","message":{"role":"user","content":"old-user-marker"}}
{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"old-assistant-marker"}]}}
""")
        val updated = case.copy(fields = JsonObject(case.fields + ("context" to buildJsonObject { put("history_file", "history.jsonl") })))
        val model = ScriptedModel(reply(TextPart("ok")))
        val root = Files.createTempDirectory("min-plugin-eval-history-")
        try {
            LocalEvalKernel(suite, parseArgs(arrayOf("--model", "test", "-p", "eval"), suite.plugin), emptyList(), true) { ResolvedModel(TEST_PROFILE, model) }.run(updated, true, root)
            val messages = model.requests.single().messages.toString()
            assertTrue(messages.contains("old-user-marker")); assertTrue(messages.contains("old-assistant-marker")); assertTrue(messages.contains("inspect"))
        } finally { deleteEvalTemp(root) }
    }
    @Test fun `MCP 默认仅广告 mock 工具并用 case 文件覆盖 suite`(): Unit = runBlocking {
        val suite = fixture(); val case = suite.cases.single()
        Files.writeString(suite.plugin.resolve(".mcp.json"), """{"mcpServers":{"tracker":{"type":"http","url":"http://127.0.0.1:1/never-connect"}}}""")
        for ((folder, body) in listOf(suite.directory.resolve("mocks/tracker") to "suite", case.dir.resolve("mocks/tracker") to "case {{input.title}}")) {
            Files.createDirectories(folder); Files.writeString(folder.resolve("create.md"), "---\nexpect:\n  title: string\n---\n$body")
        }
        val model = ScriptedModel(reply(ToolCallPart(CallId("mock"), "mcp__plugin_demo_tracker__create", args("title" to "marker")), stop = StopReason.ToolUse), reply(TextPart("ok")))
        val root = Files.createTempDirectory("min-plugin-eval-mock-")
        try {
            val e = LocalEvalKernel(suite, parseArgs(arrayOf("--model", "test", "-p", "eval"), suite.plugin), emptyList(), true) { ResolvedModel(TEST_PROFILE, model) }.run(case, true, root)
            assertNull(e.error)
            assertTrue(model.requests.first().tools.any { it.name == "mcp__plugin_demo_tracker__create" })
            assertTrue(model.requests[1].messages.toString().contains("case marker"))
        } finally { deleteEvalTemp(root) }
    }
}
