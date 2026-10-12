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

class PluginEvalAdditionalTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun suite(): EvalSuite {
        val plugin = tmp.newFolder().toPath(); Files.createDirectories(plugin.resolve(".claude-plugin"))
        Files.writeString(plugin.resolve(".claude-plugin/plugin.json"), """{"name":"demo"}""")
        val c = Files.createDirectories(plugin.resolve("evals/case/graders"))
        Files.writeString(c.parent.resolve("prompt.md"), "---\nallowed_tools: [Read, Skill]\n---\ninspect")
        Files.writeString(c.resolve("ok.md"), "---\ntype: regex\n---\nok")
        return PluginEval.load(plugin)
    }
    @Test fun `scaffold 显式启用且已有文件不算模型创建`(): Unit = runBlocking {
        val suite = suite(); val case = suite.cases.single()
        Files.writeString(case.dir.resolve("setup.sh"), "printf seeded > seeded.txt\n")
        val configured = case.copy(fields = JsonObject(case.fields + ("context" to buildJsonObject { put("scaffold_script", "setup.sh") })))
        val root = Files.createTempDirectory("min-plugin-eval-scaffold-")
        val inactive = Files.createTempDirectory("min-plugin-eval-scaffold-off-")
        try {
            val model = ScriptedModel(reply(ToolCallPart(CallId("read"), "Read", args("file_path" to "/workspace/seeded.txt")), stop = StopReason.ToolUse),
                reply(ToolCallPart(CallId("write"), "Write", args("file_path" to "/workspace/created.txt", "content" to "new")), stop = StopReason.ToolUse), reply(TextPart("ok")))
            val args = parseArgs(arrayOf("--model", "test", "-p", "eval"), suite.plugin)
            val evidence = LocalEvalKernel(suite, args, listOf("Write"), true, scaffoldEnabled = true) { ResolvedModel(TEST_PROFILE, model) }.run(configured, true, root)
            assertNull(evidence.error); assertEquals("seeded", evidence.files["seeded.txt"])
            assertFalse(evidence.createdFiles.contains("seeded.txt")); assertTrue(evidence.createdFiles.contains("created.txt"))
            assertFalse(LocalEvalKernel(suite, args, emptyList(), true) { ResolvedModel(TEST_PROFILE, ScriptedModel(reply(TextPart("ok")))) }.run(configured, false, inactive).files.containsKey("seeded.txt"))
        } finally { deleteEvalTemp(root); deleteEvalTemp(inactive) }
    }
    @Test fun `plugins 覆写换掉默认插件且空列表不加载插件`(): Unit = runBlocking {
        val suite = suite(); val case = suite.cases.single()
        val other = tmp.newFolder().toPath(); Files.createDirectories(other.resolve(".claude-plugin"))
        Files.writeString(other.resolve(".claude-plugin/plugin.json"), """{"name":"override"}""")
        Files.createDirectories(other.resolve("skills/marker")); Files.writeString(other.resolve("skills/marker/SKILL.md"), "---\ndescription: override marker\n---\nmarker")
        val configured = case.copy(fields = JsonObject(case.fields + ("plugins" to JsonArray(listOf(JsonPrimitive(other.toString()))))))
        for ((c, expected) in listOf(configured to true, configured.copy(fields = JsonObject(configured.fields + ("plugins" to JsonArray(emptyList())))) to false)) {
            val model = ScriptedModel(reply(TextPart("ok"))); val root = Files.createTempDirectory("min-plugin-eval-override-")
            try {
                LocalEvalKernel(suite, parseArgs(arrayOf("--model", "test", "-p", "eval"), suite.plugin), emptyList(), true) { ResolvedModel(TEST_PROFILE, model) }.run(c, true, root)
                assertEquals(expected, model.requests.single().system.toString().contains("override:marker"))
            } finally { deleteEvalTemp(root) }
        }
    }
    @Test fun `agent mock 使用指定 judge 并发布 ADOPT 录制`(): Unit = runBlocking {
        val suite = suite(); val case = suite.cases.single()
        Files.writeString(suite.plugin.resolve(".mcp.json"), """{"mcpServers":{"tracker":{"type":"http","url":"http://127.0.0.1:1/unused"}}}""")
        val mocks = Files.createDirectories(case.dir.resolve("mocks/tracker"))
        Files.writeString(mocks.resolve("create.md"), "---\ntype: agent\n---\nanswer")
        val main = ScriptedModel(reply(ToolCallPart(CallId("mock"), "mcp__plugin_demo_tracker__create", args("title" to "marker")), stop = StopReason.ToolUse), reply(TextPart("ok")))
        val judge = ScriptedModel(reply(TextPart("""{"output":"judge-recording","error":false}""")))
        val selected = mutableListOf<String>(); val output = java.io.ByteArrayOutputStream(); val results = tmp.newFolder().toPath()
        val code = pluginEvalCommand(listOf(suite.plugin.toString(), "--trust-plugin", "--model", "test", "--judge-model", "judge", "--runs", "1", "--ablation", "none", "--output-dir", results.toString(), "--json"), suite.plugin,
            java.io.PrintStream(output), java.io.PrintStream(output)) { _, ref -> selected += ref.modelId; ResolvedModel(TEST_PROFILE, if (ref.modelId == "judge") judge else main) }
        assertEquals(output.toString(), 0, code); assertTrue(selected.contains("judge"))
        assertTrue(Files.exists(results.resolve("mock-recordings/ADOPT.txt")))
        assertTrue(Files.readString(results.resolve("mock-recordings/ADOPT.txt")).contains("mocks/.replay/tracker"))
        assertTrue(main.requests[1].messages.toString().contains("judge-recording"))
    }
}
