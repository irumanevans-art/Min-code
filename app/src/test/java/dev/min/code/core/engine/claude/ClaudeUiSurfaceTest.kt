package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.ClaudeCodeManager.ModelOption
import dev.min.code.core.claudecode.ClaudeCodeManager.RelayModel
import dev.min.code.core.claudecode.ClaudeCodeManager.SessionOptions
import dev.min.code.core.claudecode.ClaudeCodeManager.SessionState
import dev.min.code.core.claudecode.ClaudeCodeManager.TaskInfo
import dev.min.code.core.claudecode.RegistryHarness
import dev.min.code.core.claudecode.awaitHandshake
import dev.min.code.core.engine.EngineAvailability
import dev.min.code.core.engine.StartOptions
import dev.min.code.core.session.ChatItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B6(b) 给通用类型补的字段（设计 §3.4 / §3.6 / §3.7 没有、会话页要读的那些）在 Claude 适配器里的填法，
 * 以及引擎把旧注册表的活跃项套成通用会话的几条规矩。夹具级对拍仍在 [ClaudeStateMapperGoldenTest]。
 */
class ClaudeUiSurfaceTest {
    private val ref = claudeRef("s1")

    @Test
    fun `settings carry the raw material the model panel draws from`() {
        val state = SessionState(
            model = "claude-sonnet-5",
            toolCount = 7,
            options = SessionOptions(model = "sonnet", effort = "high"),
            availableModels = listOf(
                ModelOption("sonnet", "Sonnet", "d", supportedEffortLevels = listOf("low", "high"), resolvedModel = "claude-sonnet-5"),
            ),
            relayModels = listOf(RelayModel("claude-fable-5", "Fable 5"), RelayModel("bare-id")),
            relayModelsChecked = true,
            relayModelsError = null,
            appliedModel = "claude-sonnet-5",
            appliedEffort = "high",
            applyingEffort = true,
        )
        val mapped = ClaudeStateMapper.map(ref, state)
        assertEquals("claude-sonnet-5", mapped.reportedModel)
        assertEquals(7, mapped.toolCount)
        val s = mapped.settings
        assertEquals("claude-sonnet-5", s.catalogModels.single().resolvedId)
        assertEquals(listOf("low", "high"), s.catalogModels.single().supportedEfforts)
        // 有显示名：description 是 id；没有显示名：label 就是 id、description 为空
        assertEquals("Fable 5" to "claude-fable-5", s.relayModels[0].label to s.relayModels[0].description)
        assertEquals("bare-id" to null, s.relayModels[1].label to s.relayModels[1].description)
        assertTrue(s.aliasModels.any { it.recommended })
        assertEquals("claude-sonnet-5", s.applied.model)
        assertEquals("high", s.applied.effort)
        // 合并的 applying 与分开的两个：换强度是「重启」那一种
        assertTrue(s.applying)
        assertTrue(s.relaunching)
        assertTrue(!s.hotSwapping)
    }

    @Test
    fun `haiku has no effort even when the catalog says nothing about it`() {
        val s = ClaudeStateMapper.map(ref, SessionState(options = SessionOptions(model = "haiku"))).settings
        assertTrue(!s.effortSupported)
        assertTrue(ClaudeStateMapper.map(ref, SessionState()).settings.effortSupported)
    }

    @Test
    fun `a running subagent task shows up as thread progress and shell tasks keep their command`() {
        val card = ChatItem.ToolCall(
            id = "i1", toolUseId = "tu1", name = "Agent",
            input = buildJsonObject { put("description", "查入口"); put("subagent_type", "Explore") },
            status = ChatItem.ToolCall.Status.Running,
        )
        val state = SessionState(
            items = listOf(card),
            tasks = listOf(
                TaskInfo(
                    id = "a1", description = "查入口", progress = "读 Foo.kt", subagentType = "Explore", taskType = "local_agent",
                    toolUseId = "tu1", toolUses = 3, totalTokens = 1500, lastToolName = "Grep", summary = "找到了",
                ),
            ),
        )
        val mapped = ClaudeStateMapper.map(ref, state)
        val thread = mapped.runningThreads.single()
        val p = thread.progress!!
        assertEquals("找到了", p.summary)
        assertEquals("读 Foo.kt", p.detail)
        assertEquals("Grep", p.lastTool)
        assertEquals(3, p.toolUses)
        assertEquals(1500, p.tokens)
        assertEquals("查入口", p.description)
    }

    @Test
    fun `permission cards keep the description and the preview of a question option`() {
        val request = dev.min.code.core.claudecode.ClaudeCodeEvent.PermissionRequest(
            requestId = "r1", toolName = "Bash", input = buildJsonObject { put("command", "ls") },
            description = "列目录", toolUseId = "tu",
        )
        val cards = claudeInteractions(listOf(request), emptyList(), emptyList())
        val approval = cards.single() as dev.min.code.core.engine.PendingInteraction.Approval
        assertEquals("列目录", approval.description)
        val noDescription = claudeInteractions(listOf(request.copy(description = null)), emptyList(), emptyList()).single()
            as dev.min.code.core.engine.PendingInteraction.Approval
        assertNull(noDescription.description)
        assertEquals("Bash", noDescription.summary)
    }

    @Test
    fun `the engine hands out one wrapper per manager and follows the registry's active session`() = runBlocking<Unit> {
        RegistryHarness("plain_reply", size = 4).use { rh ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val engine = ClaudeCodeEngine(rh.registry, scope, MutableStateFlow<EngineAvailability>(EngineAvailability.Ready))
                assertNull(engine.activeSession.value)
                val created = engine.create(StartOptions(cwd = "/workspace")).getOrThrow()
                val key = rh.registry.activeKey.value!!
                rh.harnessOf(rh.registry.get(key)!!).awaitHandshake()
                // 界面经 activeSession 取，create 返回的是同一个包装：后到的不能把先到的撤掉
                dev.min.code.core.claudecode.eventually("活跃会话跟上") { engine.activeSession.value != null }
                assertSame(created, engine.activeSession.value)
                assertSame(created, engine.sessionFor(key))
                assertNotNull(engine.sessionFor(key)!!.state.value)
                assertNull(engine.sessionFor("no-such-key"))
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `an empty model or effort id means follow the default, not reuse the last one`() = runBlocking<Unit> {
        RegistryHarness("plain_reply", size = 3).use { rh ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val engine = ClaudeCodeEngine(rh.registry, scope, MutableStateFlow<EngineAvailability>(EngineAvailability.Ready))
                engine.create(StartOptions(cwd = "/workspace", modelId = "", effortId = "")).getOrThrow()
                val h = rh.harnessOf(rh.registry.get(rh.registry.activeKey.value!!)!!)
                h.awaitHandshake()
                val options = h.launches.single().options
                assertNull(options.model)
                assertNull(options.effort)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `reverting a call that has no snapshot is Rejected so the page does not mark it undone`() = runBlocking<Unit> {
        RegistryHarness("plain_reply", size = 3).use { rh ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val engine = ClaudeCodeEngine(rh.registry, scope, MutableStateFlow<EngineAvailability>(EngineAvailability.Ready))
                val session = engine.create(StartOptions(cwd = "/workspace")).getOrThrow()
                rh.harnessOf(rh.registry.get(rh.registry.activeKey.value!!)!!).awaitHandshake()
                val result = session.submit(dev.min.code.core.engine.SessionCommand.Revert("c1", "no-such-tool-use"))
                assertTrue(result.toString(), result is dev.min.code.core.engine.CommandResult.Rejected)
                assertTrue(session.checkpointed(listOf("no-such-tool-use")).isEmpty())
                assertTrue(session.revertFile("f", listOf("no-such-tool-use")).isEmpty())
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `a plan card keeps the CLI's suggestion as an extra option the page can show and answer`() {
        val suggestion = dev.min.code.core.claudecode.PermissionSuggestion("切换到「Accept edits」模式", buildJsonObject { put("type", "setMode") })
        val request = dev.min.code.core.claudecode.ClaudeCodeEvent.PermissionRequest(
            requestId = "p1", toolName = "ExitPlanMode", input = buildJsonObject { put("plan", "# 计划") },
            suggestions = listOf(suggestion),
        )
        val card = claudeInteractions(listOf(request), emptyList(), emptyList()).single() as dev.min.code.core.engine.PendingInteraction.PlanReview
        // 标准选项里没有「始终允许」（通知与命令层只认这些），建议另放 extraOptions
        assertTrue(card.options.none { it.kind == dev.min.code.core.engine.ApprovalOption.Kind.AllowAlways })
        assertEquals("切换到「Accept edits」模式", card.extraOptions.single().label)
        val call = claudeAnswerCall(card, dev.min.code.core.engine.InteractionAnswer.Choose(card.extraOptions.single().id), request, isHead = true)
        call as ClaudeAnswerCall.Permission
        assertTrue(call.allow)
        assertEquals(suggestion, call.suggestion)
    }
}
