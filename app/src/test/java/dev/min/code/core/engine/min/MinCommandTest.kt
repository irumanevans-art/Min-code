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
import dev.min.agent.spi.GoalSet
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

    @Test fun advisorSelectionUsesExplicitProviderAndOffClearsIt() = runBlocking {
        val r = rig(ScriptedModel())
        assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetAdvisor(cmd(), "oracle", "other")))
        assertEquals(ModelRef("other", "oracle"), r.root.state.value.advisor.model)
        assertTrue(r.events().filterIsInstance<dev.min.agent.spi.Note>().any { "不同的供应商 other" in it.text })
        assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetAdvisor(cmd(), null)))
        assertEquals(null, r.root.state.value.advisor.model)
        assertEquals(2, r.events().filterIsInstance<dev.min.agent.spi.AdvisorConfigured>().size)
    }

    @Test fun missingAdvisorProviderIsRejectedWithoutChangingSelection() = runBlocking {
        val r = rig(ScriptedModel())
        assertTrue(r.session.submit(SessionCommand.SetAdvisor(cmd(), "oracle", "gone")) is CommandResult.Rejected)
        assertTrue(r.events().filterIsInstance<dev.min.agent.spi.AdvisorConfigured>().isEmpty())
    }

    private fun installedCommandRig(): Rig {
        val files = mutableMapOf(
            "/root/.claude/settings.json" to """{"enabledPlugins":{"market-demo@local":true}}""",
            "/root/.claude/plugins/installed_plugins.json" to """{"version":2,"plugins":{"market-demo@local":[{"scope":"user","installPath":"/root/.claude/plugins/cache/local/market-demo/1"}]}}""",
            "/root/.claude/plugins/cache/local/market-demo/1/.claude-plugin/plugin.json" to """{"name":"market-demo"}""",
            "/root/.claude/plugins/cache/local/market-demo/1/commands/hello.md" to "---\ndescription: Marketplace command\ndisable-model-invocation: true\n---\nMARKET_COMMAND_BODY ${'$'}ARGUMENTS",
            "/root/.claude/plugins/cache/local/market-demo/1/commands/private.md" to "---\ndescription: Private command\nuser-invocable: false\n---\nPRIVATE_BODY",
        )
        val fs = object : dev.min.agent.spi.WorkspaceFs {
            override suspend fun stat(path: dev.min.agent.spi.GuestPath): dev.min.agent.spi.FileStat? =
                files[path.value]?.let { dev.min.agent.spi.FileStat(dev.min.agent.spi.FileKind.File, it.toByteArray().size.toLong(), 0) }
                    ?: if (files.keys.any { it.startsWith(path.value.trimEnd('/') + "/") }) dev.min.agent.spi.FileStat(dev.min.agent.spi.FileKind.Directory, 0, 0) else null
            override suspend fun read(path: dev.min.agent.spi.GuestPath, offset: Long, limit: Int): ByteArray =
                files.getValue(path.value).toByteArray().drop(offset.toInt()).take(limit).toByteArray()
            override suspend fun writeAtomic(path: dev.min.agent.spi.GuestPath, bytes: ByteArray) { files[path.value] = bytes.decodeToString() }
            override suspend fun list(dir: dev.min.agent.spi.GuestPath): List<dev.min.agent.spi.DirEntry> = files.keys
                .filter { it.startsWith(dir.value.trimEnd('/') + "/") }
                .map { it.removePrefix(dir.value.trimEnd('/') + "/").substringBefore('/') }.distinct()
                .map { dev.min.agent.spi.DirEntry(it, stat(dev.min.agent.spi.GuestPath(dir.value.trimEnd('/') + "/" + it))!!.kind) }
            override fun scopeOf(path: dev.min.agent.spi.GuestPath) = dev.min.agent.spi.PathScope.Workspace
        }
        val k = TestKernel(ScriptedModel(answer("done")), fs = fs, memoryHome = dev.min.agent.spi.GuestPath("/root")).also { kernel = it }
        val host = FakeHost(k)
        val session = MinAgentSession(runBlocking { k.newRoot() }, host, k.scope)
        assertTrue("真实安装索引必须由现有加载器读到", session.runtime.state.value.skills!!.entries.any { it.name == "market-demo:hello" })
        return Rig(k, host, session)
    }

    @Test fun installedPluginCommandExpandsFrozenBodyInAppBeforeModelSend() {
        val r = installedCommandRig()
        assertEquals(CommandResult.Accepted, r.send("/market-demo:hello real-argument"))
        r.awaitIdle()
        assertEquals("MARKET_COMMAND_BODY real-argument", (r.events().filterIsInstance<InputReceived>().single().parts.single() as TextPart).text)
        assertTrue(awaitValue { r.session.state.value.slashCommands.takeIf { commands -> commands.any { it.name == "market-demo:hello" } } } != null)
    }

    @Test fun nonInvocableAndUnknownPluginCommandsDoNotReachTheModel() {
        val r = installedCommandRig()
        assertTrue(r.send("/market-demo:private") is CommandResult.Rejected)
        assertTrue(r.send("/market-demo:absent") is CommandResult.Rejected)
        assertTrue(r.events().filterIsInstance<InputReceived>().isEmpty())
        assertFalse(awaitValue { r.session.state.value.slashCommands.takeIf { it.any { c -> c.name == "market-demo:hello" } } }.any { it.name == "market-demo:private" })
    }

    // ---- skills 的热更新与 /reload-skills（App 入口） ----

    private fun skillsRig(files: MutableMap<String, String>): Rig {
        val fs = object : dev.min.agent.spi.WorkspaceFs {
            override suspend fun stat(path: dev.min.agent.spi.GuestPath): dev.min.agent.spi.FileStat? =
                files[path.value]?.let { dev.min.agent.spi.FileStat(dev.min.agent.spi.FileKind.File, it.toByteArray().size.toLong(), 0) }
                    ?: if (files.keys.any { it.startsWith(path.value.trimEnd('/') + "/") }) dev.min.agent.spi.FileStat(dev.min.agent.spi.FileKind.Directory, 0, 0) else null
            override suspend fun read(path: dev.min.agent.spi.GuestPath, offset: Long, limit: Int): ByteArray =
                files.getValue(path.value).toByteArray().drop(offset.toInt()).take(limit).toByteArray()
            override suspend fun writeAtomic(path: dev.min.agent.spi.GuestPath, bytes: ByteArray) { files[path.value] = bytes.decodeToString() }
            override suspend fun list(dir: dev.min.agent.spi.GuestPath): List<dev.min.agent.spi.DirEntry> = files.keys
                .filter { it.startsWith(dir.value.trimEnd('/') + "/") }
                .map { it.removePrefix(dir.value.trimEnd('/') + "/").substringBefore('/') }.distinct()
                .map { dev.min.agent.spi.DirEntry(it, stat(dev.min.agent.spi.GuestPath(dir.value.trimEnd('/') + "/" + it))!!.kind) }
            override fun scopeOf(path: dev.min.agent.spi.GuestPath) = dev.min.agent.spi.PathScope.Workspace
        }
        val k = TestKernel(ScriptedModel(answer("done"), answer("done2")), fs = fs, memoryHome = dev.min.agent.spi.GuestPath("/root")).also { kernel = it }
        val host = FakeHost(k)
        val session = MinAgentSession(runBlocking { k.newRoot() }, host, k.scope)
        return Rig(k, host, session).also { r ->
            k.scope.launch(Dispatchers.Unconfined) { session.signals.collect { r.signals += it } }
        }
    }

    private fun skillFile(description: String, body: String) = "---\ndescription: $description\n---\n$body"

    @Test fun `a forked slash skill is accepted with its outcome as a notice, so the composer does not get the text back`() {
        val files = mutableMapOf("/workspace/.claude/skills/forky/SKILL.md" to "---\ndescription: forked\ncontext: fork\n---\nDo the forked thing")
        val r = skillsRig(files)
        assertEquals(CommandResult.Accepted, r.send("/forky now"))
        assertTrue("分身的结论以说明显示", awaitValue { r.signals.filterIsInstance<SessionSignal.Notice>().firstOrNull() } != null)
        assertTrue("这次输入不进主对话", r.events().filterIsInstance<InputReceived>().none { it.clientMsgId.value == "send-/forky now" })
    }

    @Test fun `set cwd moves the session directory through the kernel and a missing directory is rejected`() {
        val r = skillsRig(mutableMapOf("/workspace/sub/README.md" to "x"))
        runBlocking {
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetCwd(cmd(), "/workspace/sub")))
            assertEquals("/workspace/sub", r.root.state.value.settings.cwd.value)
            assertTrue(r.events().any { it is dev.min.agent.spi.CwdChanged })
            val missing = r.session.submit(SessionCommand.SetCwd(cmd(), "/workspace/nope"))
            assertTrue("不存在的目录内核拒绝：$missing", missing is CommandResult.Rejected)
        }
        assertEquals("/workspace/sub", r.root.state.value.settings.cwd.value)
    }

    @Test fun `slash reload-skills in the app picks up a new skill, says so with a notice and puts it in the menu`() {
        val files = mutableMapOf("/workspace/.claude/skills/old/SKILL.md" to skillFile("old one", "OLD"))
        val r = skillsRig(files)
        files["/workspace/.claude/skills/fresh/SKILL.md"] = skillFile("brand new", "FRESH BODY")
        files.remove("/workspace/.claude/skills/old/SKILL.md")
        assertEquals(CommandResult.Accepted, r.send("/reload-skills"))
        val notice = r.signals.filterIsInstance<SessionSignal.Notice>().single()
        assertTrue(notice.text, notice.text.startsWith("skills-reloaded:true:") && notice.text.endsWith(":1:1:0"))
        assertTrue("不进对话", r.events().filterIsInstance<InputReceived>().isEmpty())
        val menu = awaitValue { r.session.state.value.slashCommands.takeIf { c -> c.any { it.name == "fresh" } } }
        assertTrue("删掉的不再列", menu.none { it.name == "old" })
    }

    @Test fun `a skill added to the project mid-session expands from the app slash entry`() {
        val files = mutableMapOf<String, String>()
        val r = skillsRig(files)
        files["/workspace/.claude/skills/fresh/SKILL.md"] = skillFile("brand new", "FRESH BODY for ${'$'}ARGUMENTS")
        assertEquals(CommandResult.Accepted, r.send("/fresh sea"))
        r.awaitIdle()
        assertTrue((r.events().filterIsInstance<InputReceived>().single().parts.single() as TextPart).text.endsWith("FRESH BODY for sea"))
    }

    @Test fun `a skill deleted mid-session is refused by the app slash entry`() {
        val files = mutableMapOf("/workspace/.claude/skills/gone/SKILL.md" to skillFile("short lived", "GONE"))
        val r = skillsRig(files)
        files.remove("/workspace/.claude/skills/gone/SKILL.md")
        assertTrue(r.send("/gone") is CommandResult.Rejected)
        assertTrue(r.events().filterIsInstance<InputReceived>().isEmpty())
    }

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
            assertTrue(r.session.submit(SessionCommand.SetMode(cmd(), "yolo")) is CommandResult.Rejected)
        }
        assertEquals("null = 回到不控制", null, r.root.state.value.settings.effort)
        assertEquals(PermissionMode.Plan, r.root.state.value.settings.mode)
    }

    @Test fun autoModeIsAcceptedNowThatTheReviewerExists() {
        val r = rig(ScriptedModel())
        assertEquals(CommandResult.Accepted, runBlocking { r.session.submit(SessionCommand.SetMode(cmd(), "auto")) })
        assertEquals(PermissionMode.Auto, r.root.state.value.settings.mode)
    }

    @Test fun theAutoReviewerIsPickedPerSessionAndNullGoesBackToTheDefault() {
        val r = rig(ScriptedModel())
        assertEquals("默认：没有单独指定", null, r.root.state.value.autoReviewSettings.reviewer)
        assertEquals(CommandResult.Accepted, runBlocking { r.session.submit(SessionCommand.SetAutoReviewer(cmd(), "small", profileId = "p2")) })
        assertEquals(ModelRef("p2", "small"), r.root.state.value.autoReviewSettings.reviewer!!.model)
        // 不带档 id = 沿用会话当前的档
        assertEquals(CommandResult.Accepted, runBlocking { r.session.submit(SessionCommand.SetAutoReviewer(cmd(), "tiny")) })
        assertEquals(ModelRef(r.root.state.value.settings.model.providerId, "tiny"), r.root.state.value.autoReviewSettings.reviewer!!.model)
        assertEquals(CommandResult.Accepted, runBlocking { r.session.submit(SessionCommand.SetAutoReviewer(cmd(), null)) })
        assertEquals(null, r.root.state.value.autoReviewSettings.reviewer)
    }

    @Test fun anAutoReviewerOnAMissingProviderIsRefusedAndNothingChanges() {
        val r = rig(ScriptedModel())
        runBlocking { r.session.submit(SessionCommand.SetAutoReviewer(cmd(), "small", profileId = "p2")) }
        val gone = runBlocking { r.session.submit(SessionCommand.SetAutoReviewer(cmd(), "small", profileId = "gone")) }
        assertEquals(CommandResult.Rejected("provider-removed"), gone)
        assertEquals("拒绝时旧的审查模型还在", ModelRef("p2", "small"), r.root.state.value.autoReviewSettings.reviewer!!.model)
    }

    @Test fun commandsTheEngineCannotDoAreUnsupported() {
        val r = rig(ScriptedModel())
        runBlocking {
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
        assertEquals("New name", r.root.state.value.name); assertEquals(listOf("task-1"), r.host.killed)
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

    // ---- /goal（会话命令：不进对话，回执走 Notice；文案在 FakeText 里是可断言的标记） ----

    @Test fun `goal with a condition while idle is set and starts a turn, with a receipt and no slash error`() {
        val r = rig(ScriptedModel(answer("working on it")))
        assertEquals(CommandResult.Accepted, r.send("/goal 测试全绿"))
        r.awaitIdle()
        val events = r.events()
        assertTrue("目标写进了日志", events.any { it is GoalSet && it.condition == "测试全绿" })
        assertTrue("条件本身作为指令开了回合", events.filterIsInstance<InputReceived>().any { (it.parts.single() as TextPart).text == "测试全绿" })
        assertTrue(r.signals.any { it is SessionSignal.Notice && it.text == "goal-set:测试全绿:true" })
        assertTrue("不当成 skill 报找不到", r.signals.none { it is SessionSignal.Notice && it.isError })
    }

    @Test fun `goal alone shows the status and clear removes the goal with a receipt`() {
        val r = rig(ScriptedModel(answer("working on it")))
        assertEquals(CommandResult.Accepted, r.send("/goal 测试全绿"))
        r.awaitIdle()
        r.signals.clear()
        assertEquals(CommandResult.Accepted, r.send("/goal"))
        assertEquals(listOf("goal-status:测试全绿:Active"), r.signals.filterIsInstance<SessionSignal.Notice>().map { it.text })
        r.signals.clear()
        assertEquals(CommandResult.Accepted, r.send("/goal clear"))
        assertEquals(listOf("goal-cleared:测试全绿"), r.signals.filterIsInstance<SessionSignal.Notice>().map { it.text })
        assertTrue("清除后日志里有 goal_cleared", r.events().any { it is dev.min.agent.spi.GoalCleared })
    }

    @Test fun `clearing with no goal says so and does not write`() {
        val r = rig(ScriptedModel(answer("unused")))
        assertEquals(CommandResult.Accepted, r.send("/goal stop"))
        assertEquals(listOf("goal-status:null:null"), r.signals.filterIsInstance<SessionSignal.Notice>().map { it.text })
        assertTrue(r.events().none { it is dev.min.agent.spi.GoalCleared })
    }
}

/** PreModelSwitch 拦下切换时 setModel 不能报成功（小尾巴 hooks-decision）：映射本身在这里钉住，hook 触发的端到端在 cli 的 HooksDecisionKernelTest */
class ModelSwitchResultTest {
    @Test fun `no refusal is accepted and a refusal is rejected with the hook reason`() {
        assertEquals(CommandResult.Accepted, modelSwitchResult(null))
        assertEquals(CommandResult.Rejected("Opus 4.6 is retired"), modelSwitchResult("Opus 4.6 is retired"))
    }
}
