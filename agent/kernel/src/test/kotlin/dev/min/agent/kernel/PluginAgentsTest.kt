package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

class PluginAgentsTest {
    @Test fun `plugin agent delegates through the kernel and logs its tool restrictions`(): Unit = runBlocking { withTimeout(30_000) {
        val root = Files.createTempDirectory("plugin-agent-log")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fs = FakeFs().apply {
                put("/plugins/check/.claude-plugin/plugin.json", """{"name":"check"}""")
                put("/plugins/check/agents/helper.md", """---
name: helper
description: Inspect files
tools: Read
---
Only inspect the project.
""")
            }
            val ids = AtomicLong()
            val deps = KernelDeps(
                clock = { ids.incrementAndGet() }, ids = { prefix -> "$prefix-${ids.incrementAndGet()}" },
                models = { ResolvedModel(TEST_PROFILE, ScriptedModel(reply(TextPart("inspection complete")))) },
                tools = listOf(dev.min.agent.tools.ReadTool, dev.min.agent.tools.WriteTool),
                host = HostPorts(fs, FakeSearch(), FakeExec()) { FakeArtifacts() },
                platform = "test", zone = ZoneOffset.UTC, memoryHome = GuestPath("/home"), pluginDirs = listOf(GuestPath("/plugins/check")),
            )
            val store = FileSessionStore(root, { ids.incrementAndGet() })
            val hub = SessionHub(store, deps, scope)
            val parent = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
            val result = hub.portFor(parent, TurnId("parent-turn"), CallId("delegate")).delegate(
                DelegateRequest("Inspect", "inspect", agentType = "check:helper", background = false),
            )
            assertTrue(result.toString(), result is DelegateResult.Finished)
            val child = hub.get((result as DelegateResult.Finished).child)!!
            child.awaitIdle()
            assertEquals("check:helper", child.state.value.origin!!.agentType)
            assertEquals(listOf("Read"), child.state.value.origin!!.tools)
            assertEquals("Only inspect the project.", child.state.value.origin!!.instructions!!.trim())
            parent.awaitIdle()
            for (rt in listOf(parent, child)) {
                val log = store.open(rt.sessionId)!!
                assertEquals(rt.state.value, fold(log.header, log.read().toList()))
            }
        } finally { scope.cancel(); root.toFile().deleteRecursively() }
    } }
}
