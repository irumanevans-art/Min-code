package dev.min.agent.cli

import dev.min.agent.ext.plugins.*
import dev.min.agent.protocol.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.*
import kotlinx.serialization.json.*

class PluginMockFinishTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun suite(): EvalSuite {
        val plugin = tmp.newFolder().toPath(); val c = Files.createDirectories(plugin.resolve("evals/case"))
        val case = EvalCase("case", c, JsonObject(emptyMap()), buildJsonObject { put("max_turns", 1) }, "inspect", emptyList())
        return EvalSuite(plugin, plugin.resolve("evals"), listOf(case), emptyList())
    }
    private val plugin = LoadedPlugin(PluginRef("demo", "inline"), "demo", PluginNameSource.MANIFEST, "/plugin", true,
        mcpServers = listOf(PluginMcpServer("tracker", "plugin:demo:tracker", JsonObject(emptyMap()))))
    private fun write(path: Path, body: String) { Files.createDirectories(path.parent); Files.writeString(path, body) }
    @Test fun `input 模板输出不得重新解释为 file 指令`(): Unit = runBlocking {
        val suite = suite(); val dir = suite.cases.single().dir.resolve("mocks/tracker")
        write(dir.resolve("create.md"), "{{input.value}}")
        write(dir.resolve("secret.txt"), "PRIVATE_FIXTURE")
        val mocks = EvalMocks(suite, suite.cases.single(), { error("fixed 不应调用模型") }, {})
        val result = mocks.tools(listOf(plugin)).single().call(TestToolContext(), "value" to "{{file:secret.txt}}")
        assertEquals("{{file:secret.txt}}", result.content.filterIsInstance<TextPart>().single().text)
    }
    @Test fun `mock fixture 路径穿越被拒绝`(): Unit = runBlocking {
        val suite = suite(); val dir = suite.cases.single().dir.resolve("mocks/tracker")
        write(dir.resolve("create.md"), "{{file:{input.name}}}")
        write(dir.parent.resolve("outside.txt"), "SECRET")
        val mocks = EvalMocks(suite, suite.cases.single(), { error("fixed") }, {})
        var refused = false
        try { mocks.tools(listOf(plugin)).single().call(TestToolContext(), "name" to "../outside.txt") }
        catch(e: CancellationException) { throw e } catch(_: IllegalArgumentException) { refused = true }
        assertTrue(refused)
    }
    @Test fun `agent mock 预算 abort 与 replay 缺省模型调用可控`(): Unit = runBlocking {
        val suite = suite(); val dir = suite.cases.single().dir.resolve("mocks/tracker")
        write(dir.resolve("create.md"), "---\ntype: agent\n---\nanswer")
        var calls = 0; var aborts = 0
        val mocks = EvalMocks(suite, suite.cases.single(), { calls++; """{"output":"recorded","error":false}""" }, { aborts++ })
        val tool = mocks.tools(listOf(plugin)).single()
        repeat(4) { assertFalse(tool.call(TestToolContext(), "title" to "same").isError) }
        assertEquals(4, calls); assertEquals(1, mocks.recordings.size)
        val (filename, value) = mocks.recordings.entries.single()
        write(suite.cases.single().dir.resolve("mocks/.replay/$filename"), value.toString())
        val replay = EvalMocks(suite, suite.cases.single(), { error("已采用录制不应调用模型") }, {})
        assertEquals("recorded", replay.tools(listOf(plugin)).single().call(TestToolContext(), "title" to "same").content.filterIsInstance<TextPart>().single().text)
        assertTrue(tool.call(TestToolContext(), "title" to "same").isError)
        assertEquals(1, aborts); assertTrue(mocks.aborted!!.string("reason").contains("budget"))
    }
}
