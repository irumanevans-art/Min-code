package dev.min.agent.kernel

import dev.min.agent.ext.config.CliSettings
import dev.min.agent.ext.config.SettingsInputs
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset

/**
 * `--settings`（CC :128）进内核：cli 层的 deny 规则在会话里真的挡住工具。对照组是同一个会话不给 --settings，
 * 同一个 Read 放行——这样能看出挡住它的确实是 --settings 那一层。
 */
class CliSettingsSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val fs = FakeFs()

    private val read = object : Tool<JsonObject> {
        override val name = "Read"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true)
        override fun description(profile: HarnessProfile) = "读文件"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext): PermissionAsk {
            val path = (input["file_path"] as? JsonPrimitive)?.content ?: "/dev/null"
            return PermissionAsk(name, "读文件", paths = listOf(PathAccess(GuestPath(path), AccessKind.Read, PathScope.Workspace)))
        }
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("read ok")))))
        }
    }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private fun session(cli: CliSettings?, model: ScriptedModel): SessionRuntime = runBlocking {
        fs.put("/workspace/secret.txt", "s3cret")
        val base = testDeps(model, listOf(read))
        val deps = KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = listOf(read),
            host = HostPorts(fs, base.host.search, FakeExec(), base.host.artifacts),
            platform = "test", zone = ZoneOffset.UTC,
            memoryHome = GuestPath("/root"),
            trustProjectHooks = { true },
            mcpFirstSnapshotWaitMs = 500,
            settingsInputs = SettingsInputs(cli = cli),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        SessionHub(store, deps, scope).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
    }

    private suspend fun dr(rt: SessionRuntime) {
        rt.send(ClientMsgId("u1"), listOf(TextPart("read it")))
        rt.state.first { it.turn == null && it.lastTurn != null }
    }

    private fun readCall() = ToolCallPart(CallId("c1"), "Read", args("file_path" to "/workspace/secret.txt"))

    @Test
    fun `对照组：不给 --settings，同一个 Read 放行`() = run {
        val model = ScriptedModel(reply(readCall(), stop = StopReason.ToolUse), reply(TextPart("done")))
        val rt = session(null, model)
        dr(rt)
        assertFalse(rt.state.value.toolResults.getValue(CallId("c1")).isError)
    }

    @Test
    fun `--settings 的 deny 规则挡住同一个 Read`() = run {
        val model = ScriptedModel(reply(readCall(), stop = StopReason.ToolUse), reply(TextPart("done")))
        val rt = session(CliSettings("--settings", """{"permissions":{"deny":["Read(secret.txt)"]}}"""), model)
        dr(rt)
        assertTrue(rt.state.value.toolResults.getValue(CallId("c1")).isError)
    }
}
