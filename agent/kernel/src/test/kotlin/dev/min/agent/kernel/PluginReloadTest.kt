package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

class PluginReloadTest {
    private suspend fun SessionRuntime.reload(force: Boolean = false, reconnect: Boolean = false): String = suspendCoroutine { continuation ->
        val method = javaClass.methods.firstOrNull { it.name == "reloadPlugins" }
        if (method == null) continuation.resume("未实现") else try {
            val result = method.invoke(this, force, reconnect, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(result as String)
        } catch(e: java.lang.reflect.InvocationTargetException) { continuation.resumeWithException(e.targetException) }
    }
    @Test fun `热重载只在回合边界切换快照且服务器前缀变化要 force`(): Unit = runBlocking { withTimeout(30_000) {
        val root = Files.createTempDirectory("plugin-reload-log")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val model = ScriptedModel({ request -> flow { entered.complete(Unit); release.await(); emitAll(reply(TextPart("ok"))(request)) } }, reply(TextPart("next")))
        try {
            val fs = FakeFs().apply {
                put("/plugin/.claude-plugin/plugin.json", """{"name":"demo"}""")
                put("/plugin/skills/old/SKILL.md", "---\ndescription: old\n---\nold")
            }
            val ids = AtomicLong()
            val store = FileSessionStore(root, { ids.incrementAndGet() })
            val deps = KernelDeps(clock = { ids.incrementAndGet() }, ids = { "$it-${ids.incrementAndGet()}" }, models = { ResolvedModel(TEST_PROFILE, model) },
                tools = listOf(dev.min.agent.tools.SkillTool), host = HostPorts(fs, FakeSearch(), FakeExec()) { FakeArtifacts() }, platform = "test", zone = ZoneOffset.UTC,
                memoryHome = GuestPath("/home"), pluginDirs = listOf(GuestPath("/plugin")))
            val hub = SessionHub(store, deps, scope); val runtime = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
            assertTrue(runtime.state.value.skills!!.entries.any { it.name == "demo:old" })
            runtime.send(ClientMsgId("first"), listOf(TextPart("first"))); entered.await()
            val frozen = model.requests.first().system
            fs.put("/plugin/skills/new/SKILL.md", "---\ndescription: new\n---\nnew")
            assertTrue(runtime.reload().contains("回合")); assertFalse(runtime.state.value.skills!!.entries.any { it.name == "demo:new" })
            assertEquals(frozen, model.requests.first().system)
            release.complete(Unit); runtime.awaitIdle()
            runtime.registerComponentHooks("skill", "demo:old", "plugin:demo", kotlinx.serialization.json.buildJsonObject { put("Stop", kotlinx.serialization.json.JsonArray(emptyList())) })
            runtime.reload(); assertTrue(runtime.state.value.componentHooks.isEmpty()); assertTrue("显式重载后新技能必须生效", runtime.state.value.skills!!.entries.any { it.name == "demo:new" })
            fs.put("/plugin/skills/server/SKILL.md", "---\ndescription: server\n---\nserver")
            fs.put("/plugin/.lsp.json", """{"test":{"command":"test","extensionToLanguage":{".x":"x"}}}""")
            assertTrue(runtime.reload(reconnect = true).contains("--force"))
            assertFalse(runtime.state.value.skills!!.entries.any { it.name == "demo:server" })
            runtime.reload(force = true); assertTrue(runtime.state.value.skills!!.entries.any { it.name == "demo:server" })
            runtime.send(ClientMsgId("second"), listOf(TextPart("second"))); runtime.awaitIdle()
            assertTrue(model.requests.last().system.toString().contains("demo:new"))
            assertEquals(runtime.state.value, fold(store.open(runtime.sessionId)!!.header, store.open(runtime.sessionId)!!.read().toList()))
            hub.close(runtime.sessionId)
        } finally { release.complete(Unit); scope.cancel(); root.toFile().deleteRecursively() }
    } }
}

