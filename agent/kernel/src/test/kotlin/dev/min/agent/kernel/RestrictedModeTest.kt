package dev.min.agent.kernel

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
import dev.min.agent.spi.WorkspaceFs
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset

/**
 * `--restricted`（CC cli-reference :123）进内核：
 * - 拒绝 bypassPermissions：创建时给了会降为 Default，运行中切换会被拒；
 * - 内置文件工具限制在工作目录内：工作目录外的读被拒，工作目录内的照常。
 * 每一条都配对照组（不开 restricted），拒绝才能归因到限制本身。
 */
class RestrictedModeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val fs = FakeFs()

    /** 宿主的分级：/workspace 是工作目录（Workspace），别的都是 Rootfs；与真宿主 RootfsFileSystem.scopeOf 的形状一致 */
    private val scopedFs: WorkspaceFs = object : WorkspaceFs by fs {
        override fun scopeOf(path: GuestPath): PathScope =
            if (path.value == "/workspace" || path.value.startsWith("/workspace/")) PathScope.Workspace else PathScope.Rootfs
    }

    private val read = object : Tool<JsonObject> {
        override val name = "Read"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true)
        override fun description(profile: HarnessProfile) = "读文件"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext): PermissionAsk {
            val path = (input["file_path"] as? JsonPrimitive)?.content ?: "/dev/null"
            return PermissionAsk(name, "读文件", paths = listOf(PathAccess(GuestPath(path), AccessKind.Read, PathScope.Rootfs)))
        }
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("read ok")))))
        }
    }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private fun hub(restricted: Boolean, model: ScriptedModel): SessionHub {
        val base = testDeps(model, listOf(read))
        val deps = KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = listOf(read),
            host = HostPorts(scopedFs, base.host.search, FakeExec(), base.host.artifacts),
            platform = "test", zone = ZoneOffset.UTC,
            memoryHome = GuestPath("/root"),
            trustProjectHooks = { true },
            mcpFirstSnapshotWaitMs = 500,
            settingsInputs = SettingsInputs(restricted = restricted),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return SessionHub(FileSessionStore(tmp.root.toPath(), { 0L }), deps, scope)
    }

    private fun readCall(id: String, path: String) = ToolCallPart(CallId(id), "Read", args("file_path" to path))

    private suspend fun SessionRuntime.say(text: String) {
        send(ClientMsgId("u1"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null }
    }

    // ---- 拒绝 bypassPermissions ----

    @Test
    fun `restricted：创建时给 bypassPermissions 降为 Default`() = run {
        val rt = hub(restricted = true, ScriptedModel(reply(TextPart("done")))).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        assertEquals(PermissionMode.Default, rt.state.value.settings.mode)
    }

    @Test
    fun `对照组：不开 restricted，创建时的 bypassPermissions 保留`() = run {
        val rt = hub(restricted = false, ScriptedModel(reply(TextPart("done")))).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        assertEquals(PermissionMode.BypassPermissions, rt.state.value.settings.mode)
    }

    @Test
    fun `restricted：运行中切到 bypassPermissions 被拒`() = run {
        val rt = hub(restricted = true, ScriptedModel(reply(TextPart("done")))).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        try {
            rt.changeSettings(mode = PermissionMode.BypassPermissions)
            fail("restricted 下切到 bypassPermissions 要被拒")
        } catch (_: IllegalArgumentException) { }
        assertEquals(PermissionMode.Default, rt.state.value.settings.mode)
    }

    @Test
    fun `对照组：不开 restricted，运行中可以切到 bypassPermissions`() = run {
        val rt = hub(restricted = false, ScriptedModel(reply(TextPart("done")))).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.changeSettings(mode = PermissionMode.BypassPermissions)
        assertEquals(PermissionMode.BypassPermissions, rt.state.value.settings.mode)
    }

    // ---- 工作目录限制 ----

    @Test
    fun `restricted：工作目录外的读被拒，工作目录内的照常`() = run {
        fs.put("/etc/hosts", "127.0.0.1 localhost")
        fs.put("/workspace/a.txt", "hello")
        val model = ScriptedModel(
            reply(readCall("out", "/etc/hosts"), stop = StopReason.ToolUse),
            reply(readCall("in", "/workspace/a.txt"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
        )
        val rt = hub(restricted = true, model).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("read both")
        assertTrue("工作目录外的读该被拒", rt.state.value.toolResults.getValue(CallId("out")).isError)
        assertFalse("工作目录内的读该放行", rt.state.value.toolResults.getValue(CallId("in")).isError)
    }

    @Test
    fun `对照组：不开 restricted，工作目录外的读放行`() = run {
        fs.put("/etc/hosts", "127.0.0.1 localhost")
        val model = ScriptedModel(reply(readCall("out", "/etc/hosts"), stop = StopReason.ToolUse), reply(TextPart("done")))
        val rt = hub(restricted = false, model).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("read it")
        assertFalse(rt.state.value.toolResults.getValue(CallId("out")).isError)
    }
}
