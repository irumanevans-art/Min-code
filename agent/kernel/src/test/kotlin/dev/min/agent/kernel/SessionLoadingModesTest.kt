package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLoadingModesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `bare 收窄工具并关闭后台，restricted 移除执行与联网工具，缺档位不改变默认`() {
        val tools = listOf(dev.min.agent.tools.ReadTool, dev.min.agent.tools.WriteTool,
            dev.min.agent.tools.BashTool(), dev.min.agent.tools.MonitorTool, dev.min.agent.tools.WebFetchTool, dev.min.agent.tools.SkillTool)
        val deps = testDeps(ScriptedModel(reply(TextPart("done"))), tools)
        assertSame(deps, deps.forSession(null))
        val bare = deps.forSession(SessionLoadingModes(bare = true))
        assertEquals(listOf("Read", "Write", "Bash"), bare.tools.map { it.name })
        assertFalse(bare.tools.last().inputSchema.toString().contains("run_in_background"))
        val restricted = deps.forSession(SessionLoadingModes(restricted = true))
        assertEquals(listOf("Read", "Write", "Skill"), restricted.tools.map { it.name })
        val override = deps.copy(customizations = Customizations.Safe,
            settingsInputs = dev.min.agent.ext.config.SettingsInputs(restricted = true)).forSession(SessionLoadingModes())
        assertEquals(Customizations.Full, override.customizations)
        assertFalse(override.settingsInputs.restricted)
    }

    @Test fun `受限会话禁用 bypass 且不会影响同进程普通会话`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val deps = testDeps(ScriptedModel(reply(TextPart("done"))), emptyList()).copy(memoryHome = GuestPath("/root"))
            val h = SessionHub(FileSessionStore(tmp.root.toPath(), { 0L }), deps, scope)
            val restricted = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
                loadingModes = SessionLoadingModes(restricted = true))
            val full = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
                loadingModes = SessionLoadingModes())
            assertEquals(PermissionMode.Default, restricted.state.value.settings.mode)
            assertEquals(PermissionMode.BypassPermissions, full.state.value.settings.mode)
            assertTrue(restricted.state.value.autoReviewSettings.disableBypassPermissionsMode)
        } finally { scope.cancel() }
    }

    @Test fun `日志中的会话档位覆盖进程默认且重开不丢失`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val created = AgentJson.decodeFromString(SessionCreated.serializer(),
                """{"cwd":"/workspace","model":{"providerId":"test","modelId":"test-model"},"mode":"default","loadingModes":{"safe":true,"restricted":true}}""")
            val store = FileSessionStore(tmp.root.toPath(), { 0L })
            val deps = testDeps(ScriptedModel(reply(TextPart("done"))), emptyList())
            val id = SessionId("modes")
            val rt = SessionRuntime.create(store, LogHeader(LOG_FORMAT_VERSION, id, 0), created, deps, scope)
            assertEquals(Customizations.Safe, rt.deps.customizations)
            assertTrue(rt.deps.settingsInputs.restricted)
            val reopened = SessionRuntime.open(store, id, deps, scope)!!
            assertEquals(Customizations.Safe, reopened.deps.customizations)
            assertTrue(reopened.deps.settingsInputs.restricted)
            assertEquals(Customizations.Full, deps.customizations)
            assertFalse(deps.settingsInputs.restricted)
        } finally { scope.cancel() }
    }
}
