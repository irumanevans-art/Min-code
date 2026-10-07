package dev.min.code.core.engine.min

import dev.min.agent.kernel.CallPhase
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.tools.AgentToolOf
import dev.min.code.core.engine.AgentThread
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.ImageInput
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.RefreshTarget
import dev.min.code.core.engine.SendTarget
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionSignal
import dev.min.code.core.engine.UserInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 命令语义：对着真内核（SessionHub + 日志落临时目录）验证 Steer / Queue 的选择、Stale 的返回、
 * 「拒绝并停止」、停止时排队输入的去向。模型、文件系统、进程是假的。
 */
class MinCommandTest {
    private var kernel: TestKernel? = null
    private val ids = AtomicInteger()
    private fun cmd() = "cmd-${ids.incrementAndGet()}"

    @After fun tearDown() { kernel?.close() }

    private data class Rig(val k: TestKernel, val host: FakeHost, val session: MinAgentSession) {
        val root get() = session.runtime
        val signals = mutableListOf<SessionSignal>()
        fun events() = runBlocking { k.store.open(root.sessionId)!!.read().toList().map { it.event } }
        fun send(text: String, id: String = "send-$text", target: SendTarget = SendTarget.Main) =
            runBlocking { session.submit(SessionCommand.Send(id, UserInput(text), target)) }
        fun interrupt(expected: String? = null, runQueued: Boolean = false, id: String = "int-${System.nanoTime()}") =
            runBlocking { session.submit(SessionCommand.Interrupt(id, expected, runQueued)) }
    }

    private fun rig(model: dev.min.agent.llm.ModelClient, tools: List<dev.min.agent.spi.Tool<*>> = listOf(AskingTool())): Rig {
        val k = TestKernel(model, tools).also { kernel = it }
        val host = FakeHost(k)
        val session = MinAgentSession(runBlocking { k.newRoot() }, host, k.scope)
        return Rig(k, host, session).also { r ->
            // 先订信号：没人订的话撤回会落成草稿（另一个测试专门验）
            k.scope.launch(Dispatchers.Unconfined) { session.signals.collect { r.signals += it } }
        }
    }

    private fun Rig.awaitBusy() = awaitValue { root.state.value.turn }
    private fun Rig.awaitIdle() = runBlocking { root.awaitIdle() }

    // ---- 发送 ----

    @Test fun sendWhileIdleQueuesAndWhileBusySteers() {
        val gate = CompletableDeferred<Unit>()
        val r = rig(ScriptedModel(gated(gate), answer("second")))
        assertEquals(CommandResult.Accepted, r.send("one"))
        r.awaitBusy()
        assertEquals(CommandResult.Accepted, r.send("two"))
        gate.complete(Unit)
        r.awaitIdle()
        val received = r.events().filterIsInstance<InputReceived>()
        assertEquals(listOf(Delivery.Queue, Delivery.Steer), received.map { it.delivery })
        assertEquals(listOf("send-one", "send-two"), received.map { it.clientMsgId.value })
        assertEquals(2, r.root.state.value.history.count { h -> h is dev.min.agent.kernel.HistoryEntry.User })
    }

    @Test fun sameCommandIdIsDuplicateAndWritesOnce() {
        val r = rig(ScriptedModel(answer("a")))
        assertEquals(CommandResult.Accepted, r.send("hi", id = "same"))
        assertEquals(CommandResult.Duplicate, r.send("hi", id = "same"))
        r.awaitIdle()
        assertEquals(1, r.events().filterIsInstance<InputReceived>().size)
    }

    @Test fun blankAndImageOnTextOnlyModelAreRejectedAndNotRemembered() {
        val noImages = FakeLookup(profile = { T_PROFILE.copy(ref = it, supportsImages = false) })
        val k = TestKernel(ScriptedModel(answer("a"))).also { kernel = it }
        val session = MinAgentSession(runBlocking { k.newRoot() }, FakeHost(k, noImages), k.scope)
        val blank = runBlocking { session.submit(SessionCommand.Send("b1", UserInput("  "))) }
        assertTrue(blank is CommandResult.Rejected)
        val image = runBlocking { session.submit(SessionCommand.Send("i1", UserInput("look", listOf(ImageInput("image/png", "AAAA"))))) }
        assertTrue(image is CommandResult.Rejected)
        // 失败不记账：同一个 id 改好再发不是「重复」
        assertEquals(CommandResult.Accepted, runBlocking { session.submit(SessionCommand.Send("i1", UserInput("look"))) })
    }

    @Test fun sendIsRejectedWhenTheProviderWasRemoved() {
        val k = TestKernel(ScriptedModel(answer("a"))).also { kernel = it }
        val session = MinAgentSession(runBlocking { k.newRoot(ModelRef("gone", "m")) }, FakeHost(k), k.scope)
        val r = runBlocking { session.submit(SessionCommand.Send("x", UserInput("hi"))) }
        assertEquals(CommandResult.Rejected("provider-removed"), r)
    }

    // ---- 停止 ----

    @Test fun interruptWithoutATurnOrWithAnotherTurnIdIsStale() {
        val gate = CompletableDeferred<Unit>()
        val r = rig(ScriptedModel(gated(gate)))
        assertTrue(r.interrupt() is CommandResult.Stale)
        r.send("go"); r.awaitBusy()
        assertTrue(r.interrupt(expected = "not-this-turn") is CommandResult.Stale)
        assertEquals(CommandResult.Accepted, r.interrupt(expected = r.root.state.value.turn!!.turnId.value))
        r.awaitIdle()
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.User), r.root.state.value.lastTurn!!.outcome)
    }

    @Test fun stopReturnsQueuedInputsToTheInputBoxByDefault() {
        val gate = CompletableDeferred<Unit>()
        val r = rig(ScriptedModel(gated(gate)))
        r.send("one"); r.awaitBusy()
        r.send("queued text")
        assertEquals(CommandResult.Accepted, r.interrupt())
        r.awaitIdle()
        assertTrue("排队的退回输入框，不进历史", r.root.state.value.inbox.isEmpty())
        assertTrue(r.root.state.value.history.none { it is dev.min.agent.kernel.HistoryEntry.User && it.parts.any { p -> (p as? TextPart)?.text == "queued text" } })
        val withdrawn = awaitValue { r.signals.filterIsInstance<SessionSignal.Withdrawn>().firstOrNull() }
        assertEquals("queued text", withdrawn.input.text)
        assertEquals(1, r.events().count { it is dev.min.agent.spi.TurnEnded })
    }

    @Test fun withdrawnTextBecomesADraftWhenNobodyListens() {
        val gate = CompletableDeferred<Unit>()
        val k = TestKernel(ScriptedModel(gated(gate))).also { kernel = it }
        val host = FakeHost(k)
        val session = MinAgentSession(runBlocking { k.newRoot() }, host, k.scope)
        runBlocking { session.submit(SessionCommand.Send("a", UserInput("one"))) }
        awaitValue { session.runtime.state.value.turn }
        runBlocking {
            session.submit(SessionCommand.Send("b", UserInput("keep me")))
            session.submit(SessionCommand.Interrupt("i", null))
            session.runtime.awaitIdle()
        }
        assertEquals("keep me", host.drafts.single().second.text)
    }

    @Test fun explicitRunQueuedAfterStopStartsTheQueuedInputRightAfter() {
        val gate = CompletableDeferred<Unit>()
        val model = ScriptedModel(gated(gate), answer("queued answered"))
        val r = rig(model)
        r.send("one"); r.awaitBusy()
        r.send("queued")
        assertEquals(CommandResult.Accepted, r.interrupt(runQueued = true))
        awaitValue { model.requests.size.takeIf { it >= 2 } }
        r.awaitIdle()
        val s = awaitValue { r.root.state.value.takeIf { it.turn == null && it.lastTurn?.outcome == TurnOutcome.Completed } }
        assertTrue(s.history.any { it is dev.min.agent.kernel.HistoryEntry.User && it.parts.any { p -> (p as? TextPart)?.text == "queued" } })
        assertTrue("没有被退回输入框", r.signals.none { it is SessionSignal.Withdrawn })
    }

    @Test fun withdrawQueuedPullsBackOneAndSecondTryIsStale() {
        val gate = CompletableDeferred<Unit>()
        val r = rig(ScriptedModel(gated(gate), answer("a")))
        r.send("one"); r.awaitBusy()
        r.send("oops", id = "q-1")
        assertEquals(CommandResult.Accepted, runBlocking { r.session.submit(SessionCommand.WithdrawQueued(cmd(), "q-1")) })
        assertTrue(runBlocking { r.session.submit(SessionCommand.WithdrawQueued(cmd(), "q-1")) } is CommandResult.Stale)
        assertEquals("oops", awaitValue { r.signals.filterIsInstance<SessionSignal.Withdrawn>().firstOrNull() }.input.text)
        gate.complete(Unit); r.awaitIdle()
    }

    // ---- 审批 ----

    private fun toolCall() = replyParts(listOf(ToolCallPart(CallId("c1"), "Touch", JsonObject(emptyMap()))), StopReason.ToolUse)

    private fun Rig.awaitApproval(): PendingInteraction.Approval =
        awaitValue { session.state.value.interactions.firstOrNull() as? PendingInteraction.Approval }

    @Test fun allowRunsTheToolAndASecondAnswerIsStale() {
        val r = rig(ScriptedModel(toolCall(), answer("done")))
        r.send("go")
        val card = r.awaitApproval()
        assertTrue(card.id.startsWith("${r.root.sessionId.value}/"))
        assertEquals(CommandResult.Accepted, runBlocking { r.session.submit(SessionCommand.Answer(cmd(), card.id, InteractionAnswer.Choose("allow"))) })
        assertTrue(runBlocking { r.session.submit(SessionCommand.Answer(cmd(), card.id, InteractionAnswer.Choose("deny"))) } is CommandResult.Stale)
        r.awaitIdle()
        assertEquals("done", (r.root.state.value.toolResults.getValue(CallId("c1")).content.single() as TextPart).text)
    }

    @Test fun denyKeepsTheTurnGoingAndTellsTheModelWhy() {
        val model = ScriptedModel(toolCall(), answer("ok then"))
        val r = rig(model)
        r.send("go")
        val card = r.awaitApproval()
        runBlocking { r.session.submit(SessionCommand.Answer(cmd(), card.id, InteractionAnswer.Choose("deny", "use another way"))) }
        r.awaitIdle()
        assertEquals(TurnOutcome.Completed, r.root.state.value.lastTurn!!.outcome)
        assertTrue(model.requests.last().allText().contains("use another way"))
    }

    @Test fun denyAndStopDeniesThenInterruptsTheWholeTurn() {
        val gate = CompletableDeferred<Unit>()
        val r = rig(ScriptedModel(toolCall(), gated(gate)))
        r.send("go")
        val card = r.awaitApproval()
        assertEquals(CommandResult.Accepted, runBlocking { r.session.submit(SessionCommand.Answer(cmd(), card.id, InteractionAnswer.Choose("deny-stop"))) })
        r.awaitIdle()
        val end = r.root.state.value.lastTurn!!
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.User), end.outcome)
        assertTrue("拒绝落盘了", r.events().any { it is dev.min.agent.spi.ApprovalResolved })
    }

    @Test fun answerWithAnUnknownOptionOrBadIdIsRejected() {
        val r = rig(ScriptedModel(toolCall(), answer("x")))
        r.send("go")
        val card = r.awaitApproval()
        assertTrue(runBlocking { r.session.submit(SessionCommand.Answer(cmd(), card.id, InteractionAnswer.Choose("nope"))) } is CommandResult.Rejected)
        assertTrue(runBlocking { r.session.submit(SessionCommand.Answer(cmd(), "no-slash", InteractionAnswer.Choose("allow"))) } is CommandResult.Rejected)
        assertTrue(runBlocking { r.session.submit(SessionCommand.Answer(cmd(), "s_gone/ap_1", InteractionAnswer.Choose("allow"))) } is CommandResult.Stale)
        runBlocking { r.session.submit(SessionCommand.Answer(cmd(), card.id, InteractionAnswer.Choose("allow"))) }
        r.awaitIdle()
    }

    // ---- 设置 ----

    @Test fun setModelAllowsCrossProviderAndRefusesAMissingProvider() {
        val r = rig(ScriptedModel())
        assertEquals(CommandResult.Accepted, runBlocking { r.session.submit(SessionCommand.SetModel(cmd(), "m2", profileId = "p2", asDefault = true)) })
        assertEquals(ModelRef("p2", "m2"), r.root.state.value.settings.model)
        assertEquals(listOf("p2" to "m2"), r.host.defaults)
        assertEquals(CommandResult.Accepted, runBlocking { r.session.submit(SessionCommand.SetModel(cmd(), "m3")) })
        assertEquals(ModelRef("p2", "m3"), r.root.state.value.settings.model)
        val again = SessionCommand.SetModel("bad", "m", profileId = "gone")
        assertEquals(CommandResult.Rejected("provider-removed"), runBlocking { r.session.submit(again) })
        assertEquals(CommandResult.Rejected("provider-removed"), runBlocking { r.session.submit(again) })
    }

    @Test fun effortAndModeApplyImmediatelyNullEffortClearsAndBadValuesAreRejected() {
        val r = rig(ScriptedModel())
        runBlocking {
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetEffort(cmd(), "high")))
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetMode(cmd(), "plan")))
            assertEquals(Effort.High, r.root.state.value.settings.effort)
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetEffort(cmd(), null)))
            assertTrue(r.session.submit(SessionCommand.SetEffort(cmd(), "turbo")) is CommandResult.Rejected)
            assertTrue(r.session.submit(SessionCommand.SetMode(cmd(), "auto")) is CommandResult.Rejected)
            assertTrue(r.session.submit(SessionCommand.SetMode(cmd(), "yolo")) is CommandResult.Rejected)
        }
        assertEquals("null = 回到不控制", null, r.root.state.value.settings.effort)
        assertEquals(PermissionMode.Plan, r.root.state.value.settings.mode)
    }

    @Test fun commandsTheEngineCannotDoAreUnsupported() {
        val r = rig(ScriptedModel())
        runBlocking {
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.SetCwd(cmd(), "/x")))
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.RunShell(cmd(), "ls")))
            // 撤销现在走内核的编辑记录：没有这次调用的记录就是被拒，不是「不支持」
            assertTrue(r.session.submit(SessionCommand.Revert(cmd(), "c")) is CommandResult.Rejected)
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.SetEngineOption(cmd(), "k", "v")))
        }
    }

    @Test fun renameStopTaskAndRefreshGoToTheirHandlers() {
        val r = rig(ScriptedModel())
        runBlocking {
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.Rename(cmd(), "  New name ")))
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.StopTask(cmd(), "task-1")))
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.Refresh(cmd(), setOf(RefreshTarget.Models))))
        }
        assertEquals(listOf("New name"), r.host.renames); assertEquals(listOf("task-1"), r.host.killed)
    }

    // ---- 续跑 ----

    @Test fun resumeNeedsTheCurrentOfferIdAndRunsAResumeTurn() {
        val gate = CompletableDeferred<Unit>()
        val r = rig(ScriptedModel(gated(gate), answer("resumed")))
        r.send("go"); r.awaitBusy(); r.interrupt(); r.awaitIdle()
        val offer = awaitValue { r.session.state.value.resume }
        assertTrue(runBlocking { r.session.submit(SessionCommand.Resume(cmd(), "old-turn")) } is CommandResult.Stale)
        assertEquals(CommandResult.Accepted, runBlocking { r.session.submit(SessionCommand.Resume(cmd(), offer.offerId)) })
        r.awaitIdle()
        val last = r.root.state.value.lastTurn!!
        assertEquals(TurnTrigger.Resume, last.trigger); assertEquals(TurnOutcome.Completed, last.outcome)
        assertTrue("续跑后 ▶ 消失", awaitValue { r.session.state.value.takeIf { it.resume == null } } != null)
    }

    // ---- 子 agent：真 Hub 委派一遍 ----

    private fun delegatingModel() = RoutedModel { req ->
        val text = req.allText()
        when {
            "more work" in text && "find the entry" in text -> flowOf(done("child again"))
            "find the entry" in text && "<subagent-report" !in text && "已在后台启动" !in text -> flowOf(done("child done"))
            "<subagent-report" in text -> flowOf(done("parent saw the report"))
            "已在后台启动" in text -> flowOf(done("parent waiting"))
            else -> flowOf(
                dev.min.agent.llm.ModelStreamEvent.Completed(
                    dev.min.agent.spi.ModelMessage(
                        dev.min.agent.spi.Role.Assistant,
                        listOf(
                            ToolCallPart(
                                CallId("agentcall"), "Agent",
                                JsonObject(mapOf("description" to JsonPrimitive("find entry"), "prompt" to JsonPrimitive("find the entry"), "subagent_type" to JsonPrimitive("Explore"), "name" to JsonPrimitive("finder"))),
                            ),
                        ),
                    ),
                    dev.min.agent.protocol.Usage(1, 1), StopReason.ToolUse, false,
                ),
            )
        }
    }

    private fun done(text: String) = dev.min.agent.llm.ModelStreamEvent.Completed(
        dev.min.agent.spi.ModelMessage(dev.min.agent.spi.Role.Assistant, listOf(TextPart(text))), dev.min.agent.protocol.Usage(1, 1), StopReason.EndTurn, false,
    )

    @Test fun backgroundDelegationShowsAThreadTheReportWakesTheParentAndYouCanTalkToTheChild() {
        val r = rig(delegatingModel(), listOf(AgentToolOf(emptyList())))
        // 新委派那一帧不能闪出「已停止」的占位线程：根状态带着新子会话、子节点还没订上的那一帧要被滤掉
        val phases = java.util.Collections.synchronizedList(mutableListOf<AgentThread.Phase>())
        r.k.scope.launch(Dispatchers.Unconfined) { r.session.state.collect { st -> st.threads.forEach { phases += it.phase } } }
        r.send("go")
        // 子 agent 完成 -> 报告进父会话 -> 父会话自动开回合（Notification）看到报告
        val s = awaitValue(20_000) {
            r.session.state.value.takeIf { st ->
                st.turn == null && st.items.any { (it as? dev.min.code.core.session.ChatItem.AssistantText)?.text == "parent saw the report" }
            }
        }
        assertFalse("占位线程闪过：$phases", AgentThread.Phase.Stopped in phases)
        val thread = s.threads.single()
        assertEquals("Explore", thread.agentType); assertEquals("finder", thread.name); assertEquals(AgentThread.Phase.Done, thread.phase)
        assertTrue(thread.items.any { (it as? dev.min.code.core.session.ChatItem.AssistantText)?.text == "child done" })
        assertEquals(TurnTrigger.Notification, r.root.state.value.lastTurn!!.trigger)
        // 直达子 agent：它再跑一个回合，完成后又有报告回到父会话
        assertEquals(CommandResult.Accepted, r.send("more work", id = "to-child", target = SendTarget.Subagent(thread.threadId)))
        awaitValue(20_000) { r.session.state.value.threads.single().items.firstOrNull { (it as? dev.min.code.core.session.ChatItem.AssistantText)?.text == "child again" } }
        assertEquals(CommandResult.Stale("子 agent 已不在线"), r.send("x", id = "ghost", target = SendTarget.Subagent("nope")))
    }
}
