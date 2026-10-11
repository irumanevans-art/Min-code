package dev.min.code.core.engine.codex

import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.codex.CodexDecision
import dev.min.code.core.codex.CodexPermissionPreset
import dev.min.code.core.codex.codexPermissionWire
import dev.min.code.core.codex.permissionPreset
import dev.min.code.core.codex.withPermission
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.ImageInput
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.InterruptCause
import dev.min.code.core.engine.LiveStream
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.ResumeMode
import dev.min.code.core.engine.SendTarget
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionSignal
import dev.min.code.core.engine.TurnEnd
import dev.min.code.core.engine.UserInput
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Codex 适配器：映射黄金对拍 + 命令转发 + 纯函数（M2-B B4）。
 *
 * 剧本进程 [ScriptedProcess] 与 `CodexAppServerManagerTest` 里那个同构（那个是 private 的，只能照抄）：
 * 看见匹配的写入就吐出对应的几行。
 *
 * ## 黄金对拍
 * Codex 没有 B0 那样提交进仓库的快照文件，所以这里的基准是 [pageView]：**照会话页（CodexPage / CodexComposer）
 * 读 `State` 的方式**写出来的键值表，与 [mappedView]（只读通用状态）逐键比。两边各自独立推导，
 * 映射写错了（例如把 cancel 当 decline、把排队消息丢了）两张表就对不上。
 */
class CodexAdapterTest {

    private val ids = AtomicInteger()
    private fun id() = "cmd-${ids.incrementAndGet()}"

    private val ref = CodexStateMapper.codexRef("t")

    // ---------------------------------------------------------------- 剧本进程与脚手架

    private class ScriptedProcess(private vararg val triggers: Pair<String, List<String>>) : Process() {
        private val output = ByteArrayOutputStream()
        private val inputWriter = PipedOutputStream()
        private val input = PipedInputStream(inputWriter, 1 shl 16)
        private val fired = mutableSetOf<Int>()

        @Volatile
        var destroyed = false
            private set

        init {
            Thread {
                while (!destroyed) {
                    val written = writtenLines()
                    triggers.forEachIndexed { index, (pattern, lines) ->
                        if (index !in fired && written.any { it.contains(pattern) }) {
                            fired += index
                            lines.forEach { emit(it) }
                        }
                    }
                    Thread.sleep(2)
                }
            }.apply { isDaemon = true }.start()
        }

        fun queue(vararg lines: String) = lines.forEach { emit(it) }

        private fun emit(line: String) {
            runCatching { synchronized(inputWriter) { inputWriter.write((line + "\n").toByteArray()); inputWriter.flush() } }
        }

        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { while (!destroyed) Thread.sleep(5); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int = if (destroyed) 0 else throw IllegalThreadStateException()
        override fun destroy() { destroyed = true; runCatching { inputWriter.close() }; runCatching { input.close() } }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive(): Boolean = !destroyed

        @Synchronized
        fun writtenLines(): List<String> = output.toString(Charsets.UTF_8.name()).lineSequence().filter { it.isNotBlank() }.toList()
    }

    private val handshake = arrayOf(
        "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
        "thread/start" to listOf("""{"id":"2","result":{"threadId":"t"}}"""),
    )

    private class Rig(
        val manager: CodexAppServerManager,
        val process: ScriptedProcess,
        val session: CodexSession,
        private val scope: CoroutineScope,
        val metaFile: File,
        val meta: ClaudeCodeSessionMetaStore,
    ) : AutoCloseable {
        override fun close() { scope.cancel(); manager.close(); metaFile.delete() }
    }

    private suspend fun rig(vararg extra: Pair<String, List<String>>): Rig {
        val process = ScriptedProcess(*handshake, *extra)
        val manager = CodexAppServerManager { process }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val metaFile = Files.createTempFile("codex-meta", ".json").toFile().also { it.delete() }
        val meta = ClaudeCodeSessionMetaStore(metaFile)
        val session = CodexSession(manager, "t", scope, metaStore = meta)
        assertTrue(manager.start())
        await { manager.state.value.status == SessionStatus.Running }
        return Rig(manager, process, session, scope, metaFile, meta)
    }

    private suspend fun await(what: String = "条件", condition: () -> Boolean) {
        repeat(600) { if (condition()) return; delay(5) }
        error("等不到：$what")
    }

    private val busyTurn = listOf("""{"id":"3","result":{"turn":{"id":"turn-1"}}}""")
    private fun completed(status: String) = """{"method":"turn/completed","params":{"turn":{"id":"turn-1","status":"$status"}}}"""
    private fun ScriptedProcess.turnStarts() = writtenLines().count { it.contains(""""method":"turn/start"""") }
    private fun ScriptedProcess.lastTurnStart(): JsonObject =
        Json.parseToJsonElement(writtenLines().last { it.contains(""""method":"turn/start"""") }).jsonObject["params"]!!.jsonObject
    private fun send(text: String) = SessionCommand.Send(id(), UserInput(text))

    // ---------------------------------------------------------------- 黄金对拍

    private fun itemLines(items: List<ChatItem>): List<String> = items.map {
        when (it) {
            is ChatItem.UserText -> "user queued=${it.queued} ${it.text}"
            is ChatItem.AssistantText -> "assistant ${it.text}"
            is ChatItem.Thinking -> "thinking ${it.text}"
            is ChatItem.Note -> "note error=${it.isError} ${it.text}"
            is ChatItem.ProcessOutput -> "stderr ${it.lines}"
            is ChatItem.Folded -> "folded ${it.title}"
            is ChatItem.SentFiles -> "files ${it.names}"
            is ChatItem.AgentReport -> "report ${it.agentType} ${it.status}"
            is ChatItem.ToolCall -> "tool ${it.name} ${it.status} error=${it.isError} ${it.input} ${it.result}"
        }
    }

    /** 会话页读 State 的方式：CodexPage / CodexComposer 用到的每个字段 */
    private fun pageView(s: CodexAppServerManager.State): Map<String, String> = buildMap {
        put("status", s.status.name)
        put("busy", s.busy.toString())
        put("threadId", s.threadId.orEmpty())
        put("errorMessage", s.errorMessage.orEmpty())
        put("cwd", s.options.cwd)
        put("model", s.options.model.orEmpty())
        put("effort", s.options.effort.orEmpty())
        put("summary", s.options.summary.orEmpty())
        put("permission", s.options.permissionPreset?.id ?: codexPermissionWire(s.options.approvalPolicy, s.options.sandbox))
        // 页面的排队 chip + 会话流；通用状态把排队接在流末尾
        put("items", (itemLines(s.items) + s.queued.map { "user queued=true $it" }).joinToString("\n"))
        put("queuedCount", s.queued.size.toString())
        put("streamingText", s.streamingText)
        put("streamingThinking", s.streamingThinking)
        put("turnId", if (s.busy) s.turnId.orEmpty() else "")
        put("turnStarted", (s.busy && s.turnStartedAt != null).toString())
        put("contextWindow", s.contextWindow?.toString().orEmpty())
        put("lastTurnDurationMs", s.lastTurnDurationMs?.toString().orEmpty())
        val a = s.pendingApproval
        put("approval", if (a == null) "none" else buildString {
            append(a.kind).append('|').append(a.requestId).append('|').append(a.command.orEmpty()).append('|')
            append(a.reason.orEmpty()).append('|').append(a.cwd.orEmpty()).append('|').append(a.grantRoot.orEmpty())
            // 页面的按钮：拒绝、允许永远画；本会话允许在「没限制」或服务端列了它时画（CodexApprovalSheet.allowsSession）
            val session = a.availableDecisions.isEmpty() || CodexDecision.ACCEPT_FOR_SESSION.wire in a.availableDecisions
            append("|buttons=decline,accept").append(if (session) ",acceptForSession" else "")
            append("|cancel=").append(CodexDecision.CANCEL.wire in a.availableDecisions)
        })
    }

    /** 通用状态读出的同一批键 */
    private fun mappedView(a: AgentSessionState, live: LiveStream): Map<String, String> = buildMap {
        put("status", a.status.name)
        put("busy", a.busy.toString())
        put("threadId", a.ref.id)
        put("errorMessage", a.errorMessage.orEmpty())
        put("cwd", a.cwd)
        put("model", a.settings.model?.id.orEmpty())
        put("effort", a.settings.effort?.id.orEmpty())
        put("summary", a.settings.extras.single { it.key == CodexStateMapper.OPTION_SUMMARY }.value)
        put("permission", if (a.settings.mode.id == CodexStateMapper.MODE_CUSTOM) a.settings.mode.label else a.settings.mode.id)
        put("items", itemLines(a.items).joinToString("\n"))
        put("queuedCount", a.queuedCount.toString())
        put("streamingText", live.text)
        put("streamingThinking", live.thinking)
        put("turnId", a.turn?.turnId.orEmpty())
        put("turnStarted", (a.turn?.startedAt != null).toString())
        put("contextWindow", a.usage.contextLimit?.toString().orEmpty())
        put("lastTurnDurationMs", a.lastTurn?.durationMs?.toString().orEmpty())
        val card = a.headInteraction as? PendingInteraction.Approval
        put("approval", if (card == null) "none" else buildString {
            val input = card.input
            append(kindOf(card.toolName)).append('|').append(card.id).append('|').append(card.commands.firstOrNull().orEmpty()).append('|')
            append(card.reason.orEmpty()).append('|').append(input["cwd"]?.jsonPrimitive?.content.orEmpty()).append('|')
            append(card.blockedPath.orEmpty())
            val ids = card.options.map { it.id }
            val buttons = buildList {
                if (ApprovalOption.DENY in ids) add("decline")
                if (ApprovalOption.ALLOW in ids) add("accept")
                if (CodexStateMapper.OPTION_ACCEPT_FOR_SESSION in ids) add("acceptForSession")
            }
            append("|buttons=").append(buttons.joinToString(","))
            append("|cancel=").append(ApprovalOption.DENY_AND_STOP in ids)
        })
    }

    private fun kindOf(toolName: String) = when (toolName) {
        "Bash" -> "Command"
        "Edit" -> "FileChange"
        else -> "Permissions"
    }

    private fun assertGolden(stage: String, state: CodexAppServerManager.State) {
        val agent = CodexStateMapper.map(CodexStateMapper.codexRef(state.threadId.orEmpty()), state)
        val live = CodexStateMapper.mapLive(state)
        assertEquals("阶段「$stage」", pageView(state).toSortedMap().toString().replace(", ", "\n"), mappedView(agent, live).toSortedMap().toString().replace(", ", "\n"))
    }

    @Test
    fun `golden - handshake, streamed turn with a command card, then settled`() = runBlocking<Unit> {
        val r = rig(
            "turn/start" to busyTurn + listOf(
                """{"method":"item/started","params":{"item":{"type":"commandExecution","id":"c1","command":"ls"}}}""",
                """{"method":"item/commandExecution/outputDelta","params":{"itemId":"c1","delta":"a.kt\n"}}""",
                """{"method":"item/agentMessage/delta","params":{"itemId":"m1","delta":"hel"}}""",
                """{"method":"item/reasoning/summaryTextDelta","params":{"itemId":"r1","delta":"想"}}""",
                """{"method":"thread/tokenUsage/updated","params":{"usage":{"inputTokens":10,"outputTokens":5,"totalTokens":15,"contextWindow":200000}}}""",
            ),
        )
        r.use {
            assertGolden("handshaken", r.manager.state.value)
            r.manager.sendTurn("hello")
            await("流式正文") { r.manager.state.value.streamingText == "hel" }
            await("token 用量") { r.manager.state.value.contextWindow != null }
            assertGolden("streaming", r.manager.state.value)
            assertEquals(15, CodexStateMapper.map(ref, r.manager.state.value).usage.contextTokens)
            r.process.queue(
                """{"method":"item/completed","params":{"item":{"type":"agentMessage","id":"m1","text":"hello"}}}""",
                """{"method":"item/completed","params":{"item":{"type":"commandExecution","id":"c1","command":"ls","status":"completed","exitCode":0,"aggregatedOutput":"a.kt\n"}}}""",
                completed("completed"),
            )
            await("回合收尾") { !r.manager.state.value.busy && r.manager.state.value.lastTurnDurationMs != null }
            assertGolden("settled", r.manager.state.value)
        }
    }

    @Test
    fun `golden - approvals offer exactly the buttons the page draws`() = runBlocking<Unit> {
        val cases = listOf(
            // availableDecisions: 给了 accept / decline：不画「本会话允许」
            """["accept","decline"]""",
            // 空 = 没限制：三个都画
            """[]""",
            // 服务端明说可以 cancel：多一个「拒绝并停止」
            """["accept","acceptForSession","decline","cancel"]""",
        )
        for ((n, decisions) in cases.withIndex()) {
            val r = rig(
                "turn/start" to busyTurn + listOf(
                    """{"id":"ap-$n","method":"item/commandExecution/requestApproval","params":{"itemId":"c1","command":"git status","cwd":"/workspace","reason":"要读仓库","availableDecisions":$decisions}}""",
                ),
            )
            r.use {
                r.manager.sendTurn("go")
                await("审批卡") { r.manager.state.value.pendingApproval != null }
                assertGolden("approval $decisions", r.manager.state.value)
                val card = r.session.state.value.headInteraction as PendingInteraction.Approval
                assertEquals("Bash", card.toolName)
                assertEquals("git status", card.summary)
                assertEquals(listOf("git status"), card.commands)
                assertEquals(n == 2, card.options.any { it.kind == ApprovalOption.Kind.DenyAndStop })
            }
        }
    }

    @Test
    fun `golden - queued messages while busy, failed turn keeps them`() = runBlocking<Unit> {
        val r = rig("turn/start" to busyTurn)
        r.use {
            r.manager.sendTurn("第一问")
            await("busy") { r.manager.state.value.busy }
            r.manager.sendTurn("排队一")
            r.manager.sendTurn("排队二")
            assertGolden("queued", r.manager.state.value)
            assertEquals(2, r.manager.state.value.queued.size)
            r.process.queue(completed("failed"))
            await("失败收尾") { !r.manager.state.value.busy }
            assertGolden("failed-keeps-queue", r.manager.state.value)
            assertEquals(2, CodexStateMapper.map(ref, r.manager.state.value).queuedCount)
        }
    }

    @Test
    fun `golden - a failed handshake and a read-only replay`() = runBlocking<Unit> {
        val failing = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/start" to listOf("""{"id":"2","error":{"message":"auth expired"}}"""),
        )
        val manager = CodexAppServerManager { failing }
        try {
            manager.start()
            await("失败") { manager.state.value.status == SessionStatus.Failed }
            assertGolden("failed", manager.state.value)
            assertEquals("auth expired", CodexStateMapper.map(ref, manager.state.value).errorMessage)
        } finally {
            manager.close()
        }
        val idle = CodexAppServerManager { error("不该起进程") }
        try {
            assertTrue(idle.restore(listOf(ChatItem.UserText("u", "旧问题"), ChatItem.AssistantText("a", "旧答案")), "t"))
            assertGolden("replay", idle.state.value)
            val mapped = CodexStateMapper.map(ref, idle.state.value)
            assertEquals(SessionStatus.Idle, mapped.status)
            assertNull(mapped.turn)
        } finally {
            idle.close()
        }
    }

    // ---------------------------------------------------------------- 映射细节

    @Test
    fun `permission presets map to modes, a custom combination keeps the wire values`() {
        val auto = CodexStateMapper.map(ref, CodexAppServerManager.State()).settings
        assertEquals("auto", auto.mode.id)
        assertEquals(listOf("read-only", "auto", "full-access"), auto.modes.map { it.id })
        val readOnly = CodexAppServerManager.State(options = CodexAppServerManager.Options().withPermission(CodexPermissionPreset.READ_ONLY))
        assertEquals("read-only", CodexStateMapper.map(ref, readOnly).settings.mode.id)
        val custom = CodexAppServerManager.State(
            options = CodexAppServerManager.Options(
                approvalPolicy = dev.min.code.core.codex.CodexApprovalPolicy.NEVER,
                sandbox = dev.min.code.core.codex.CodexSandbox.READ_ONLY,
            ),
        )
        val mode = CodexStateMapper.map(ref, custom).settings.mode
        assertEquals(CodexStateMapper.MODE_CUSTOM, mode.id)
        assertEquals("never · readOnly", mode.label)
        assertFalse(CodexStateMapper.map(ref, custom).settings.effortNeedsRestart)
    }

    @Test
    fun `queued item ids carry index and text hash`() {
        val id = CodexStateMapper.queuedItemId(2, "排队三")
        assertEquals(2 to "排队三".hashCode(), CodexStateMapper.parseQueuedItemId(id))
        assertNull(CodexStateMapper.parseQueuedItemId("user-7"))
    }

    @Test
    fun `no play key today - capabilities say no resume, and with turn interrupt it needs a known end`() {
        val state = CodexAppServerManager.State(
            status = SessionStatus.Running, threadId = "t", items = listOf(ChatItem.UserText("u", "hi")), lastTurnDurationMs = 5,
        )
        val stopped = TurnEnd.Interrupted(InterruptCause.User)
        assertNull(CodexStateMapper.map(ref, state, CodexMapContext(lastTurnEnd = stopped)).resume)
        val ctx = CodexMapContext(lastTurnEnd = stopped, capabilities = EngineCapabilities.CodexWithTurnInterrupt)
        assertEquals(ResumeMode.Prompted, CodexStateMapper.map(ref, state, ctx).resume!!.mode)
        assertNull(CodexStateMapper.map(ref, state, ctx.copy(lastTurnEnd = null)).resume)
        val exited = state.copy(status = SessionStatus.Failed)
        assertEquals(ResumeMode.RelaunchThenPrompted, CodexStateMapper.map(ref, exited, ctx).resume!!.mode)
    }

    @Test
    fun `rollout mid-turn detection follows the last start and end events`() {
        fun line(type: String) = """{"type":"event_msg","payload":{"type":"$type"}}"""
        assertTrue(CodexResume.rolloutEndedMidTurn(sequenceOf(line("task_started"), line("agent_message"))))
        assertFalse(CodexResume.rolloutEndedMidTurn(sequenceOf(line("task_started"), line("task_complete"))))
        assertFalse(CodexResume.rolloutEndedMidTurn(sequenceOf(line("task_started"), line("turn_aborted"))))
        assertTrue(CodexResume.rolloutEndedMidTurn(sequenceOf(line("task_started"), line("task_complete"), line("task_started"))))
        assertFalse(CodexResume.rolloutEndedMidTurn(emptySequence()))
    }

    // ---------------------------------------------------------------- 命令转发

    @Test
    fun `Send forwards one turn start, a repeated commandId is a Duplicate, extras are Unsupported`() = runBlocking<Unit> {
        rig("turn/start" to busyTurn).use { r ->
            val cmd = send("hello")
            assertEquals(CommandResult.Accepted, r.session.submit(cmd))
            await("turn/start") { r.process.turnStarts() == 1 }
            assertEquals(CommandResult.Duplicate, r.session.submit(cmd))
            assertEquals(1, r.process.turnStarts())
            // Codex 收不了图，也没有子 agent
            val image = SessionCommand.Send(id(), UserInput("x", listOf(ImageInput("image/png", "AAAA"))))
            assertEquals(CommandResult.Unsupported, r.session.submit(image))
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.Send(id(), UserInput("x"), SendTarget.Subagent("t1"))))
            assertTrue(r.session.submit(send("  ")) is CommandResult.Rejected)
        }
    }

    @Test
    fun `Send to a replay with no process is Rejected and the id is reusable`() = runBlocking<Unit> {
        val manager = CodexAppServerManager { error("不该起进程") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            manager.restore(listOf(ChatItem.UserText("u", "旧")), "t")
            val session = CodexSession(manager, "t", scope)
            val cmd = send("接着聊")
            assertTrue(session.submit(cmd) is CommandResult.Rejected)
            assertTrue("失败的命令 id 要还回去", session.submit(cmd) is CommandResult.Rejected)
        } finally {
            scope.cancel(); manager.close()
        }
    }

    @Test
    fun `Send while busy queues, WithdrawQueued takes exactly that one back through signals`() = runBlocking<Unit> {
        rig("turn/start" to busyTurn).use { r ->
            r.session.submit(send("第一问"))
            await("busy") { r.manager.state.value.busy }
            r.session.submit(send("排队一"))
            r.session.submit(send("排队二"))
            await("映射里两条排队") { r.session.state.value.queuedCount == 2 }
            val queued = r.session.state.value.items.filterIsInstance<ChatItem.UserText>().filter { it.queued }
            assertEquals(listOf("排队一", "排队二"), queued.map { it.text })

            val withdrawn = async { withTimeout(5_000) { r.session.signals.first { it is SessionSignal.Withdrawn } } }
            delay(100)
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.WithdrawQueued(id(), queued[1].id)))
            assertEquals("排队二", (withdrawn.await() as SessionSignal.Withdrawn).input.text)
            assertEquals(listOf("排队一"), r.manager.state.value.queued)
            // 同一个 id 再撤：那一格已经不是它了
            assertTrue(r.session.submit(SessionCommand.WithdrawQueued(id(), queued[1].id)) is CommandResult.Stale)
            assertTrue(r.session.submit(SessionCommand.WithdrawQueued(id(), "garbage")) is CommandResult.Stale)
            // 队头被放出去之后，旧下标的 id 不能撤到别的消息：「排队一」占着 0 号，拿一个哈希对不上的 0 号 id
            assertTrue(
                r.session.submit(SessionCommand.WithdrawQueued(id(), CodexStateMapper.queuedItemId(0, "别的话"))) is CommandResult.Stale,
            )
            assertEquals(listOf("排队一"), r.manager.state.value.queued)
        }
    }

    @Test
    fun `Interrupt stops the whole app-server today, idle is Stale, run-queued-after is Unsupported`() = runBlocking<Unit> {
        rig("turn/start" to busyTurn).use { r ->
            assertTrue(r.session.submit(SessionCommand.Interrupt(id())) is CommandResult.Stale)
            r.session.submit(send("第一问"))
            await("busy") { r.manager.state.value.busy }
            r.session.submit(send("排队一"))
            await("排队进映射") { r.session.state.value.queuedCount == 1 }
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.Interrupt(id(), runQueuedAfter = true)))
            assertTrue(r.session.submit(SessionCommand.Interrupt(id(), expectedTurnId = "turn-other")) is CommandResult.Stale)
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.Interrupt(id())))
            await("进程被停") { r.process.destroyed }
            await("状态 Closed") { r.manager.state.value.status == SessionStatus.Closed }
            // 排队的话还在 queued 里，没丢（重启后由界面决定）
            assertEquals(listOf("排队一"), r.manager.state.value.queued)
        }
    }

    private suspend fun Rig.approvalOf(@Suppress("UNUSED_PARAMETER") what: String): PendingInteraction.Approval {
        manager.sendTurn("go")
        await("审批卡") { manager.state.value.pendingApproval != null }
        await("映射里有审批卡") { session.state.value.headInteraction != null }
        return session.state.value.headInteraction as PendingInteraction.Approval
    }

    private fun approvalFrame(n: Int, decisions: String) =
        """{"id":"ap-$n","method":"item/commandExecution/requestApproval","params":{"itemId":"c1","command":"rm x","availableDecisions":$decisions}}"""

    @Test
    fun `Answer maps option ids to Codex decisions on the wire`() = runBlocking<Unit> {
        val expectations = listOf(
            ApprovalOption.ALLOW to "accept",
            CodexStateMapper.OPTION_ACCEPT_FOR_SESSION to "acceptForSession",
            ApprovalOption.DENY to "decline",
            ApprovalOption.DENY_AND_STOP to "cancel",
        )
        for ((n, pair) in expectations.withIndex()) {
            val (optionId, wire) = pair
            rig("turn/start" to busyTurn + approvalFrame(n, """["accept","acceptForSession","decline","cancel"]""")).use { r ->
                val card = r.approvalOf("all")
                assertEquals(
                    CommandResult.Accepted,
                    r.session.submit(SessionCommand.Answer(id(), card.id, InteractionAnswer.Choose(optionId))),
                )
                await("应答写出 $wire") { r.process.writtenLines().any { it.contains("ap-$n") && it.contains("\"$wire\"") } }
                // 答过了：再答是 Stale
                assertTrue(r.session.submit(SessionCommand.Answer(id(), card.id, InteractionAnswer.Choose(optionId))) is CommandResult.Stale)
            }
        }
    }

    @Test
    fun `Answer with an option the server did not offer is Rejected and nothing is written`() = runBlocking<Unit> {
        rig("turn/start" to busyTurn + approvalFrame(7, """["accept","decline"]""")).use { r ->
            val card = r.approvalOf("limited")
            assertEquals(listOf(ApprovalOption.ALLOW, ApprovalOption.DENY), card.options.map { it.id })
            val result = r.session.submit(
                SessionCommand.Answer(id(), card.id, InteractionAnswer.Choose(CodexStateMapper.OPTION_ACCEPT_FOR_SESSION)),
            )
            assertTrue(result.toString(), result is CommandResult.Rejected)
            // 提问 / 跳过属于有提问能力的引擎：Codex 的能力位为假，supports 直接挡掉
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.Answer(id(), card.id, InteractionAnswer.Decline)))
            assertTrue(r.session.submit(SessionCommand.Answer(id(), "nope", InteractionAnswer.Choose(ApprovalOption.ALLOW))) is CommandResult.Stale)
            delay(100)
            assertTrue(r.process.writtenLines().none { it.contains("ap-7") })
            assertNotNull(r.manager.state.value.pendingApproval)
        }
    }

    private fun assertNotNull(any: Any?) = org.junit.Assert.assertNotNull(any)

    @Test
    fun `settings commands change the options the next turn carries`() = runBlocking<Unit> {
        rig("turn/start" to busyTurn).use { r ->
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetModel(id(), "gpt-5.1")))
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetEffort(id(), "high")))
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetMode(id(), "read-only")))
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetCwd(id(), "/workspace/app")))
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetEngineOption(id(), "summary", "detailed")))
            // 不合法的值不受理；没有的引擎项 Unsupported；跨供应商没有意义
            assertTrue(r.session.submit(SessionCommand.SetEffort(id(), "turbo")) is CommandResult.Rejected)
            assertTrue(r.session.submit(SessionCommand.SetMode(id(), "yolo")) is CommandResult.Rejected)
            assertTrue(r.session.submit(SessionCommand.SetEngineOption(id(), "summary", "loud")) is CommandResult.Rejected)
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.SetEngineOption(id(), "cache_ttl", "5m")))
            assertTrue(r.session.submit(SessionCommand.SetModel(id(), "x", profileId = "p")) is CommandResult.Rejected)

            await("映射跟上") { r.session.state.value.settings.effort?.id == "high" }
            val s = r.session.state.value
            assertEquals("gpt-5.1", s.settings.model?.id)
            assertEquals("read-only", s.settings.mode.id)
            assertEquals("/workspace/app", s.cwd)

            r.session.submit(send("问"))
            await("turn/start") { r.process.turnStarts() == 1 }
            val turn = r.process.lastTurnStart()
            assertEquals("gpt-5.1", turn["model"]?.jsonPrimitive?.content)
            assertEquals("high", turn["effort"]?.jsonPrimitive?.content)
            assertEquals("readOnly", turn["sandboxPolicy"]!!.jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("detailed", turn["summary"]?.jsonPrimitive?.content)
            assertEquals("/workspace/app", turn["cwd"]?.jsonPrimitive?.content)
            // 清空 = 回到不传
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetModel(id(), "")))
            assertNull(r.manager.state.value.options.model)
        }
    }

    @Test
    fun `commands Codex cannot do are Unsupported, Rename touches only the app-side meta`() = runBlocking<Unit> {
        rig().use { r ->
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.Resume(id(), "o")))
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.RunShell(id(), "ls")))
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.StopSubagent(id(), "t")))
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.StopTask(id(), "t")))
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.Revert(id(), "t")))
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.Rename(id(), "我的 Codex 会话")))
            assertEquals("我的 Codex 会话", r.meta.get("t").title)
            assertTrue(r.session.submit(SessionCommand.Rename(id(), " ")) is CommandResult.Rejected)
            assertFalse("改名不该往 app-server 写任何东西", r.process.writtenLines().any { it.contains("rename") })
        }
    }
}
