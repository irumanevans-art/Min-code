package dev.min.agent.cli

import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.protocol.TextPart
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import java.nio.file.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginEvalInitTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun `交互 init 提问生成用例并通过一次隔离试跑`(): Unit = runBlocking {
        val root = tmp.root.toPath(); Files.createDirectories(root.resolve(".claude-plugin"))
        Files.writeString(root.resolve(".claude-plugin/plugin.json"), """{"name":"demo"}""")
        val answers = listOf("""{"question":"What is a good result?"}""", """{"cases":[{"name":"positive","prompt":"inspect","graders":[{"name":"ok","type":"regex","pattern":"ok"}]}]}""", "ok")
        val calls = AtomicInteger(); val output = java.io.ByteArrayOutputStream()
        val exit = pluginEvalCommand(listOf("init", "--model", "test"), root, java.io.PrintStream(output), java.io.PrintStream(output),
            models = { _, _ -> ResolvedModel(TEST_PROFILE, ScriptedModel(reply(TextPart(answers[calls.getAndIncrement()])))) }, input = "y\nA good answer says ok\n/exit\n".reader().buffered(), terminal = true)
        assertEquals(output.toString(), 0, exit)
        assertEquals(3, calls.get()); assertTrue(output.toString().contains("What is a good result?"))
        assertTrue(Files.exists(root.resolve("evals/positive/prompt.md")))
        assertTrue(Files.exists(root.resolve("evals/init-trial-result.json")))
    }
}
