package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.ClaudeCodeEvent
import dev.min.code.core.claudecode.ClaudeCodeManager.ModelOption
import dev.min.code.core.claudecode.ClaudeCodeManager.RelayModel
import dev.min.code.core.claudecode.ClaudeCodeManager.SessionState
import dev.min.code.core.claudecode.ClaudeCodeManager.TaskInfo
import dev.min.code.core.claudecode.ClaudeCodeManager.SessionOptions
import dev.min.code.core.claudecode.PermissionSuggestion
import dev.min.code.core.claudecode.SessionMeta
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.HistoryTail
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.InterruptCause
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.QuestionAnswerInput
import dev.min.code.core.engine.ResumeMode
import dev.min.code.core.engine.ResumeReason
import dev.min.code.core.engine.TurnEnd
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 不起进程的纯函数测试：映射的几条规则、交互翻译、续跑判定。夹具级对拍在 [ClaudeStateMapperGoldenTest]。 */
class ClaudeAdapterPureTest {

    private val ref = claudeRef("s1")

    private fun request(
        id: String, tool: String, input: JsonObject = JsonObject(emptyMap()),
        suggestions: List<PermissionSuggestion> = emptyList(), agentId: String? = null,
    ) = ClaudeCodeEvent.PermissionRequest(
        requestId = id, toolName = tool, input = input, description = "do $tool", toolUseId = "tu-$id",
        suggestions = suggestions, agentId = agentId,
    )

    private fun suggestion(label: String) = PermissionSuggestion(label, buildJsonObject { put("type", "addRules") })

    // ---------------------------------------------------------------- 交互

    @Test
    fun `tool name decides the card kind, queue depth counts the cards behind each one`() {
        val questions = buildJsonObject {
            put("questions", buildJsonArray {
                add(buildJsonObject {
                    put("question", "用哪个？"); put("header", "库"); put("multiSelect", true)
                    put("options", buildJsonArray {
                        add(buildJsonObject { put("label", "A"); put("description", "第一个") })
                        add(buildJsonObject { put("label", "B") })
                    })
                })
            })
        }
        val state = SessionState(
            permissionQueue = listOf(
                request("r1", "AskUserQuestion", questions),
                request("r2", "ExitPlanMode", buildJsonObject { put("plan", "# 计划") }, listOf(suggestion("切换模式"))),
                request("r3", "Bash", buildJsonObject { put("command", "ls") }, listOf(suggestion("始终允许 ls"))),
            ),
        )
        val cards = ClaudeStateMapper.map(ref, state).interactions
        val q = cards[0] as PendingInteraction.Question
        assertEquals("用哪个？", q.questions.single().question)
        assertTrue(q.questions.single().multiSelect)
        assertEquals(listOf("A", "B"), q.questions.single().options.map { it.label })
        val plan = cards[1] as PendingInteraction.PlanReview
        assertEquals("# 计划", plan.plan)
        assertTrue("计划审批不带「始终允许」", plan.options.none { it.kind == ApprovalOption.Kind.AllowAlways })
        val approval = cards[2] as PendingInteraction.Approval
        assertEquals(
            listOf(ApprovalOption.ALLOW, "always:0", ApprovalOption.DENY_AND_STOP, ApprovalOption.DENY),
            approval.options.map { it.id },
        )
        assertEquals(listOf("ls"), approval.commands)
        assertEquals(listOf(2, 1, 0), cards.map { it.origin.queuedAfter })
        // 通知上只放允许 / 拒绝（带话的那种）；始终允许、拒绝并停止必须回 App 里
        assertEquals(listOf(ApprovalOption.ALLOW, ApprovalOption.DENY), approval.notificationOptions.map { it.id })
    }

    @Test
    fun `a request from a background sub-agent carries its name card`() {
        val task = TaskInfo(
            id = "agent-1", description = "查找入口", subagentType = "Explore", toolUseId = "tu-agent", backgrounded = true,
        )
        val state = SessionState(
            tasks = listOf(task),
            permissionQueue = listOf(request("r1", "Bash", agentId = "agent-1")),
        )
        val card = ClaudeStateMapper.map(ref, state).interactions.single()
        val agent = card.origin.agent!!
        assertEquals("tu-agent", agent.threadId)
        assertEquals("Explore", agent.agentType)
        assertEquals("查找入口", agent.description)
        assertTrue(agent.background)
    }

    @Test
    fun `answers translate to the manager call the UI makes today`() {
        val req = request("r1", "Bash", suggestions = listOf(suggestion("始终允许 ls")))
        val card = ClaudeStateMapper.map(ref, SessionState(permissionQueue = listOf(req))).interactions.single()
        fun call(answer: InteractionAnswer) = claudeAnswerCall(card, answer, req, isHead = true)

        assertEquals(ClaudeAnswerCall.Permission(true, null, null, "r1"), call(InteractionAnswer.Choose(ApprovalOption.ALLOW)))
        assertEquals(
            ClaudeAnswerCall.Permission(true, null, req.suggestions[0], "r1"),
            call(InteractionAnswer.Choose("always:0")),
        )
        assertEquals(ClaudeAnswerCall.Permission(false, null, null, "r1"), call(InteractionAnswer.Choose(ApprovalOption.DENY_AND_STOP)))
        assertEquals(
            ClaudeAnswerCall.Permission(false, DEFAULT_DENY_NOTE, null, "r1"),
            call(InteractionAnswer.Choose(ApprovalOption.DENY)),
        )
        assertEquals(
            ClaudeAnswerCall.Permission(false, "不行", null, "r1"),
            call(InteractionAnswer.Choose(ApprovalOption.DENY, "不行")),
        )
        assertTrue("不存在的选项", call(InteractionAnswer.Choose("always:9")) is ClaudeAnswerCall.Rejected)
        assertTrue(call(InteractionAnswer.Decline) is ClaudeAnswerCall.Rejected)
    }

    @Test
    fun `question answers become the question-to-joined-labels map, Decline is the empty map`() {
        val req = request("q1", "AskUserQuestion")
        val card = ClaudeStateMapper.map(ref, SessionState(permissionQueue = listOf(req))).interactions.single()
        val answers = InteractionAnswer.Answer(
            listOf(QuestionAnswerInput("用哪个？", listOf("A", "B"), other = "C"), QuestionAnswerInput("再问", listOf("X"))),
        )
        assertEquals(
            ClaudeAnswerCall.Questions(mapOf("用哪个？" to "A, B, C", "再问" to "X")),
            claudeAnswerCall(card, answers, req, isHead = true),
        )
        assertEquals(ClaudeAnswerCall.Questions(emptyMap()), claudeAnswerCall(card, InteractionAnswer.Decline, req, isHead = true))
        assertTrue(claudeAnswerCall(card, answers, req, isHead = false) is ClaudeAnswerCall.Rejected)
        assertTrue(claudeAnswerCall(card, InteractionAnswer.Choose("allow"), req, isHead = true) is ClaudeAnswerCall.Rejected)
    }

    // ---------------------------------------------------------------- 设置与杂项

    @Test
    fun `model and effort chips show the effective value, the id keeps what the user asked for`() {
        val models = listOf(
            ModelOption("default", "Default"),
            ModelOption("sonnet", "Sonnet", supportedEffortLevels = listOf("low", "high")),
        )
        // 没选过模型：id 空串（跟随默认），标签是 CLI 回读的实际模型
        var s = ClaudeStateMapper.map(
            ref, SessionState(availableModels = models, appliedModel = "claude-opus-5-5", appliedEffort = "high"),
        ).settings
        assertEquals("", s.model!!.id)
        assertEquals("claude-opus-5-5", s.model!!.label)
        assertEquals("", s.effort!!.id)
        assertEquals("high", s.effort!!.label)
        // 选了 sonnet + ultracode：标签用目录里的名字，强度芯片是 ultracode；强度档按目录裁
        s = ClaudeStateMapper.map(
            ref,
            SessionState(availableModels = models, options = SessionOptions(model = "sonnet", ultracode = true), appliedUltracode = true),
        ).settings
        assertEquals("sonnet", s.model!!.id)
        assertEquals("Sonnet", s.model!!.label)
        assertEquals("ultracode", s.effort!!.label)
        assertEquals(listOf("", "low", "high"), s.efforts.map { it.id })
        assertEquals("true", s.extras.first { it.key == ClaudeStateMapper.OPTION_ULTRACODE }.value)
        assertTrue(s.effortNeedsRestart)
    }

    @Test
    fun `haiku has no effort levels and relay models join the catalog without duplicates`() {
        val state = SessionState(
            availableModels = listOf(ModelOption("haiku", "Haiku")),
            relayModels = listOf(RelayModel("haiku"), RelayModel("claude-x", "X")),
            appliedModel = "claude-haiku-4-5-20251001",
            relayModelsChecked = true,
        )
        val s = ClaudeStateMapper.map(ref, state).settings
        assertEquals(listOf(""), s.efforts.map { it.id })
        assertEquals(listOf("haiku", "claude-x"), s.models.map { it.id })
        assertEquals("X", s.models.last().label)
        assertFalse(s.modelsLoading)
    }

    @Test
    fun `the human-set title beats the live title, a changed provider marks the connection stale`() {
        val state = SessionState(liveTitle = "CLI 拟的名", launchedProfileId = "p1")
        val plain = ClaudeStateMapper.map(ref, state)
        assertEquals("CLI 拟的名", plain.title)
        val ctx = ClaudeMapContext(meta = SessionMeta(title = "我改的"), currentProfileId = "p2", profileLabels = mapOf("p1" to "旧中转"))
        val mapped = ClaudeStateMapper.map(ref, state, ctx)
        assertEquals("我改的", mapped.title)
        assertEquals("p1", mapped.connection!!.profileId)
        assertEquals("旧中转", mapped.connection!!.profileLabel)
        assertTrue(mapped.connection!!.stale)
        assertFalse(ClaudeStateMapper.map(ref, state, ctx.copy(currentProfileId = "p1")).connection!!.stale)
        assertNull("还没起过进程：没有连接信息", ClaudeStateMapper.map(ref, SessionState()).connection)
    }

    @Test
    fun `nested Agent cards become threads with parents and depth`() {
        fun agentCard(id: String, sub: List<ChatItem> = emptyList()) = ChatItem.ToolCall(
            id = "i-$id", toolUseId = id, name = "Agent",
            input = buildJsonObject { put("subagent_type", "Explore"); put("description", "d-$id") },
            status = ChatItem.ToolCall.Status.Done, subItems = sub,
        )
        val state = SessionState(items = listOf(agentCard("outer", listOf(agentCard("inner"))), agentCard("sibling")))
        val threads = ClaudeStateMapper.map(ref, state).threads
        assertEquals(listOf("outer", "inner", "sibling"), threads.map { it.threadId })
        assertEquals(listOf(null, "outer", null), threads.map { it.parentThreadId })
        assertEquals(listOf(0, 1, 0), threads.map { it.depth })
    }

    // ---------------------------------------------------------------- 续跑

    @Test
    fun `endedMidTurn looks at the last conversation event and ignores titles and notes`() {
        val user = ClaudeCodeEvent.UserMessage("hi")
        val noise = listOf(ClaudeCodeEvent.CustomTitle("t"), ClaudeCodeEvent.SystemNote("n"))
        assertTrue(ClaudeResume.endedMidTurn(listOf(user) + noise))
        assertTrue(ClaudeResume.endedMidTurn(listOf(user, ClaudeCodeEvent.ToolUse("t1", "Bash", JsonObject(emptyMap())))))
        assertTrue(ClaudeResume.endedMidTurn(listOf(user, ClaudeCodeEvent.ToolResult("t1", "ok", false))))
        assertFalse(ClaudeResume.endedMidTurn(listOf(user, ClaudeCodeEvent.AssistantText("done"))))
        assertFalse(ClaudeResume.endedMidTurn(emptyList()))
    }

    @Test
    fun `history tail from items - notes and stderr do not count`() {
        fun tool(status: ChatItem.ToolCall.Status) =
            ChatItem.ToolCall("i", "t", "Bash", JsonObject(emptyMap()), status)
        assertEquals(HistoryTail.Empty, ClaudeResume.tailOf(listOf(ChatItem.Note("n", "x"))))
        assertEquals(HistoryTail.UserMessage, ClaudeResume.tailOf(listOf(ChatItem.UserText("u", "hi"), ChatItem.Note("n", "x"))))
        assertEquals(HistoryTail.AssistantFinal, ClaudeResume.tailOf(listOf(ChatItem.AssistantText("a", "ok"))))
        assertEquals(HistoryTail.AssistantWithToolCalls, ClaudeResume.tailOf(listOf(tool(ChatItem.ToolCall.Status.Running))))
        assertEquals(HistoryTail.ToolResults, ClaudeResume.tailOf(listOf(tool(ChatItem.ToolCall.Status.Done))))
    }

    @Test
    fun `resume offer needs a known stop reason - without lastTurnEnd there is never a play key`() {
        val running = SessionState(
            status = SessionStatus.Running, sessionId = "s1", items = listOf(ChatItem.UserText("u", "hi")),
            lastTurnFinishedAt = 1_000L,
        )
        assertNull("现在的 manager 没有 lastTurnEnd", ClaudeStateMapper.map(ref, running).resume)
        val stopped = ClaudeStateMapper.map(ref, running, ClaudeMapContext(lastTurnEnd = TurnEnd.Interrupted(InterruptCause.User)))
        assertEquals(ResumeReason.UserStopped, stopped.resume!!.reason)
        assertEquals(ResumeMode.Prompted, stopped.resume!!.mode)
        assertNull(ClaudeStateMapper.map(ref, running, ClaudeMapContext(lastTurnEnd = TurnEnd.Completed)).resume)
        // 进程没了：先接回会话再续
        val exited = running.copy(status = SessionStatus.Closed)
        val offer = ClaudeStateMapper.map(ref, exited, ClaudeMapContext(lastTurnEnd = TurnEnd.Interrupted(InterruptCause.EngineExited))).resume!!
        assertEquals(ResumeMode.RelaunchThenPrompted, offer.mode)
        // 回答已经完整落盘：不续（会变成 assistant prefill）
        val answered = running.copy(items = listOf(ChatItem.UserText("u", "hi"), ChatItem.AssistantText("a", "ok")))
        assertNull(ClaudeStateMapper.map(ref, answered, ClaudeMapContext(lastTurnEnd = TurnEnd.Interrupted(InterruptCause.User))).resume)
    }
}
