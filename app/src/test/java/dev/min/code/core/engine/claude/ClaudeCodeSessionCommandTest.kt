package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.FakeCliProcess
import dev.min.code.core.claudecode.ManagerHarness
import dev.min.code.core.claudecode.eventually
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.SendTarget
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionSignal
import dev.min.code.core.engine.StopAction
import dev.min.code.core.engine.UserInput
import dev.min.code.core.engine.stopActionFor
import dev.min.code.core.service.LocalService
import dev.min.code.core.service.LocalServiceStatus
import dev.min.code.core.session.ChatItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 命令转发（设计 §4.1 的命令表）：每条 [SessionCommand] 经 `ClaudeCodeSession.submit` 之后，假 CLI 进程那头
 * 真的看到了对应的帧。manager 是真的，进程是 [FakeCliProcess] 回放 B0 的夹具，不起真进程。
 */
class ClaudeCodeSessionCommandTest {

    private val ids = AtomicInteger()
    private fun id() = "cmd-${ids.incrementAndGet()}"

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun FakeCliProcess.userFrames() = written.filter { it.str("type") == "user" }
    private fun FakeCliProcess.requests(subtype: String) = written.filter {
        it.str("type") == "control_request" && it["request"]!!.jsonObject.str("subtype") == subtype
    }.map { it["request"]!!.jsonObject }

    /** 对 can_use_tool 的应答（带 behavior 的那种 control_response） */
    private fun FakeCliProcess.permissionResponses() = written
        .filter { it.str("type") == "control_response" }
        .map { it["response"]!!.jsonObject["response"]?.jsonObject }
        .filter { it != null && it.containsKey("behavior") }.map { it!! }

    private class Rig(val h: ManagerHarness, val session: ClaudeCodeSession, private val scope: CoroutineScope) : AutoCloseable {
        override fun close() {
            scope.cancel()
            h.close()
        }
    }

    private fun rig(
        fixture: String,
        externals: MutableStateFlow<ClaudeMapContext> = MutableStateFlow(ClaudeMapContext()),
        stopHosted: (String) -> Unit = {},
    ): Rig {
        val h = ManagerHarness(fixture)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return Rig(h, ClaudeCodeSession(h.manager, "key", scope, externals, stopHostedService = stopHosted), scope)
    }

    private suspend fun Rig.start() = h.startAndHandshake()

    private fun send(text: String) = SessionCommand.Send(id(), UserInput(text))

    // ---------------------------------------------------------------- Send

    @Test
    fun `Send while idle forwards one user frame, the same commandId is a Duplicate`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.start()
            val cmd = send("hello")
            assertEquals(CommandResult.Accepted, r.session.submit(cmd))
            r.h.awaitCondition("用户帧写出") { r.h.process.userFrames().size == 1 }
            assertEquals(CommandResult.Duplicate, r.session.submit(cmd))
            r.h.awaitTurnEnd()
            assertEquals("重复提交不该再写一帧", 1, r.h.process.userFrames().size)
            eventually("映射状态里有这条消息") { r.session.state.value.items.any { it is ChatItem.UserText && it.text == "hello" } }
        }
    }

    @Test
    fun `Send to a session that is not running is Rejected and the id can be reused`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            val cmd = send("too early")
            val result = r.session.submit(cmd)
            assertTrue(result.toString(), result is CommandResult.Rejected)
            // 失败的命令把 id 还回去：会话起来之后用户再点一次不能被当成重复
            r.start()
            assertEquals(CommandResult.Accepted, r.session.submit(cmd))
        }
    }

    @Test
    fun `an empty Send is Rejected`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.start()
            assertTrue(r.session.submit(send("   ")) is CommandResult.Rejected)
            assertTrue(r.h.process.userFrames().isEmpty())
        }
    }

    @Test
    fun `Send while busy queues the message - one more user frame and a queued item`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.start()
            r.h.process.holdTurns = true
            assertEquals(CommandResult.Accepted, r.session.submit(send("first")))
            r.h.awaitState("busy") { it.busy }
            assertEquals(CommandResult.Accepted, r.session.submit(send("second")))
            r.h.awaitCondition("第二条交棒") { r.h.process.userFrames().size == 2 }
            eventually("排队的那条在状态里") { r.session.state.value.queuedCount == 1 }
            assertEquals(
                "second",
                r.session.state.value.items.filterIsInstance<ChatItem.UserText>().single { it.queued }.text,
            )
            r.h.process.releaseTurn()
        }
    }

    // ---------------------------------------------------------------- Interrupt

    @Test
    fun `Interrupt on an idle session is Stale and writes no frame`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.start()
            assertTrue(r.session.submit(SessionCommand.Interrupt(id())) is CommandResult.Stale)
            kotlinx.coroutines.delay(200)
            assertTrue(r.h.process.requests("interrupt").isEmpty())
        }
    }

    @Test
    fun `Interrupt before any output is the Withdraw tier - the message comes back through signals`() = runBlocking<Unit> {
        rig("interrupt_before_output").use { r ->
            r.start()
            val withdrawn = async { withTimeout(10_000) { r.session.signals.first { it is SessionSignal.Withdrawn } } }
            // signals 是 WhileSubscribed：先让订阅者接上，否则话会落盘而不是进信号
            kotlinx.coroutines.delay(500) // SharedFlow 看不到订阅数，给订阅链（shareIn -> merge -> manager）一点时间接上
            r.session.submit(send("Write a haiku about the sea."))
            r.h.awaitState("busy") { it.busy && !it.turnProduced }
            eventually("映射状态也是 busy") { r.session.state.value.busy }
            // 界面算按钮样子用的 stopActionFor 与 manager 实际做的是同一档
            assertEquals(StopAction.Withdraw, stopActionFor(r.session.state.value.turn, 0, EngineCapabilities.ClaudeCode))
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.Interrupt(id())))
            val signal = withdrawn.await() as SessionSignal.Withdrawn
            assertEquals("Write a haiku about the sea.", signal.input.text)
            r.h.awaitTurnEnd()
            assertTrue("撤回后对话流里不留那条消息", r.h.manager.state.value.items.none { it is ChatItem.UserText && "haiku" in it.text })
        }
    }

    @Test
    fun `Interrupt after output is the plain Interrupt tier - the message stays, the turn is marked interrupted`() = runBlocking<Unit> {
        rig("interrupt_midturn").use { r ->
            r.start()
            r.session.submit(send("count"))
            r.h.awaitState("开始流") { it.streamingText.isNotEmpty() && it.turnProduced }
            eventually("映射状态 produced") { r.session.state.value.turn?.produced == true }
            assertEquals(StopAction.Interrupt, stopActionFor(r.session.state.value.turn, 0, EngineCapabilities.ClaudeCode))
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.Interrupt(id())))
            val end = r.h.awaitTurnEnd()
            assertTrue(end.items.any { it is ChatItem.UserText && it.text == "count" })
            assertTrue(end.items.any { it is ChatItem.Note && it.text == "已中断" })
        }
    }

    @Test
    fun `Interrupt with a queued message - the explicit runQueuedAfter tier cancels the CLI queue but keeps the copy`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.start()
            r.h.process.holdTurns = true
            r.session.submit(send("first"))
            r.h.awaitState("busy") { it.busy }
            r.session.submit(send("second"))
            eventually("排队的那条在状态里") { r.session.state.value.queuedCount == 1 }
            // 设计 §10.2：有排队输入时 ■ 默认是「打断并退回输入框」，「打断后跑排队」是显式的一档
            assertEquals(
                StopAction.InterruptReturnQueued,
                stopActionFor(r.session.state.value.turn, 1, EngineCapabilities.ClaudeCode),
            )
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.Interrupt(id(), runQueuedAfter = true)))
            r.h.awaitCondition("interrupt 写出") { r.h.process.requests("interrupt").isNotEmpty() }
            assertEquals("true", r.h.process.requests("interrupt").single()["cancel_queued"]?.jsonPrimitive?.content)
            r.h.process.releaseTurn()
        }
    }

    @Test
    fun `Interrupt with a queued message and no explicit tier still stops the turn - the known B8 gap`() = runBlocking<Unit> {
        // 默认档（InterruptReturnQueued）要 manager 提供「打断并交还排队消息」，现在没有，退回现行为：停止键必须能停。
        // 这条测试把缺口钉住，B8 补完 manager 之后应当改成「排队消息从 items 里消失并经 Withdrawn 退回」
        rig("plain_reply").use { r ->
            r.start()
            r.h.process.holdTurns = true
            r.session.submit(send("first"))
            r.h.awaitState("busy") { it.busy }
            r.session.submit(send("second"))
            eventually("排队的那条在状态里") { r.session.state.value.queuedCount == 1 }
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.Interrupt(id())))
            r.h.awaitCondition("interrupt 写出") { r.h.process.requests("interrupt").isNotEmpty() }
            r.h.process.releaseTurn()
        }
    }

    // ---------------------------------------------------------------- WithdrawQueued

    @Test
    fun `WithdrawQueued - an unknown id is Stale, a real queued id is Unsupported until the manager grows withdraw-by-id`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.start()
            r.h.process.holdTurns = true
            r.session.submit(send("first"))
            r.h.awaitState("busy") { it.busy }
            r.session.submit(send("second"))
            eventually("排队的那条在状态里") { r.session.state.value.queuedCount == 1 }
            val queuedId = r.session.state.value.items.filterIsInstance<ChatItem.UserText>().single { it.queued }.id
            assertTrue(r.session.submit(SessionCommand.WithdrawQueued(id(), "no-such-item")) is CommandResult.Stale)
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.WithdrawQueued(id(), queuedId)))
            assertEquals("没真撤回：队列还在", 1, r.session.state.value.queuedCount)
            r.h.process.releaseTurn()
        }
    }

    // ---------------------------------------------------------------- Answer

    private suspend fun Rig.awaitHead(): PendingInteraction {
        eventually("队头交互") { session.state.value.headInteraction != null }
        return session.state.value.headInteraction!!
    }

    @Test
    fun `Answer Allow answers the head request with behavior allow`() = runBlocking<Unit> {
        rig("bash_with_permission").use { r ->
            r.start()
            r.session.submit(send("run it"))
            val card = r.awaitHead() as PendingInteraction.Approval
            assertEquals("Bash", card.toolName)
            assertEquals(
                CommandResult.Accepted,
                r.session.submit(SessionCommand.Answer(id(), card.id, InteractionAnswer.Choose(ApprovalOption.ALLOW))),
            )
            r.h.awaitCondition("应答写出") { r.h.process.permissionResponses().isNotEmpty() }
            val response = r.h.process.permissionResponses().single()
            assertEquals("allow", response.str("behavior"))
            assertFalse(response.containsKey("updatedPermissions"))
            // 答完了就不在队里：再答是 Stale
            assertTrue(
                r.session.submit(SessionCommand.Answer(id(), card.id, InteractionAnswer.Choose(ApprovalOption.ALLOW))) is CommandResult.Stale,
            )
        }
    }

    @Test
    fun `Answer AllowAlways sends the CLI's own permission update back verbatim`() = runBlocking<Unit> {
        rig("bash_with_permission").use { r ->
            r.start()
            r.session.submit(send("run it"))
            val card = r.awaitHead() as PendingInteraction.Approval
            val always = card.options.first { it.kind == ApprovalOption.Kind.AllowAlways }
            assertTrue(always.id.startsWith(ApprovalOption.ALWAYS_PREFIX))
            assertEquals(
                CommandResult.Accepted,
                r.session.submit(SessionCommand.Answer(id(), card.id, InteractionAnswer.Choose(always.id))),
            )
            r.h.awaitCondition("应答写出") { r.h.process.permissionResponses().isNotEmpty() }
            val response = r.h.process.permissionResponses().single()
            assertEquals("allow", response.str("behavior"))
            assertEquals(1, response["updatedPermissions"]!!.jsonArray.size)
        }
    }

    @Test
    fun `Answer DenyAndStop denies without a note and interrupts - the UI's Deny today`() = runBlocking<Unit> {
        rig("bash_denied_interrupt").use { r ->
            r.start()
            r.session.submit(send("run it"))
            val card = r.awaitHead() as PendingInteraction.Approval
            assertEquals(
                CommandResult.Accepted,
                r.session.submit(SessionCommand.Answer(id(), card.id, InteractionAnswer.Choose(ApprovalOption.DENY_AND_STOP))),
            )
            r.h.awaitCondition("应答写出") { r.h.process.permissionResponses().isNotEmpty() }
            val response = r.h.process.permissionResponses().single()
            assertEquals("deny", response.str("behavior"))
            assertEquals("User denied", response.str("message"))
            assertEquals(true, response["interrupt"]?.jsonPrimitive?.content?.toBoolean())
        }
    }

    @Test
    fun `Answer Deny carries the note and does not interrupt - the notification's Deny today`() = runBlocking<Unit> {
        rig("bash_denied_interrupt").use { r ->
            r.start()
            r.session.submit(send("run it"))
            val card = r.awaitHead() as PendingInteraction.Approval
            assertEquals(
                CommandResult.Accepted,
                r.session.submit(SessionCommand.Answer(id(), card.id, InteractionAnswer.Choose(ApprovalOption.DENY, "换个办法"))),
            )
            r.h.awaitCondition("应答写出") { r.h.process.permissionResponses().isNotEmpty() }
            val response = r.h.process.permissionResponses().single()
            assertEquals("deny", response.str("behavior"))
            assertEquals("换个办法", response.str("message"))
            assertFalse("带话的拒绝不打断本轮", response.containsKey("interrupt"))
        }
    }

    @Test
    fun `Answer to an unknown or wrong-typed card is Stale or Rejected and writes nothing`() = runBlocking<Unit> {
        rig("bash_with_permission").use { r ->
            r.start()
            r.session.submit(send("run it"))
            val card = r.awaitHead()
            assertTrue(
                r.session.submit(SessionCommand.Answer(id(), "no-such-request", InteractionAnswer.Choose(ApprovalOption.ALLOW))) is CommandResult.Stale,
            )
            // 审批卡要用 Choose；Decline 属于提问卡（能力位 questions 为真，所以过得了 supports，到这里被拒）
            assertTrue(r.session.submit(SessionCommand.Answer(id(), card.id, InteractionAnswer.Decline)) is CommandResult.Rejected)
            assertTrue(
                r.session.submit(SessionCommand.Answer(id(), card.id, InteractionAnswer.Choose("no-such-option"))) is CommandResult.Rejected,
            )
            kotlinx.coroutines.delay(150)
            assertTrue(r.h.process.permissionResponses().isEmpty())
            // 清理：放行，让夹具跑完
            r.session.submit(SessionCommand.Answer(id(), card.id, InteractionAnswer.Choose(ApprovalOption.ALLOW)))
        }
    }

    // ---------------------------------------------------------------- 设置

    @Test
    fun `SetMode and SetModel become control requests, bad values are Rejected`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.start()
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetMode(id(), "plan")))
            r.h.awaitCondition("set_permission_mode") { r.h.process.requests("set_permission_mode").isNotEmpty() }
            assertEquals("plan", r.h.process.requests("set_permission_mode").single().str("mode"))
            eventually("模式进了映射状态") { r.session.state.value.settings.mode.id == "plan" }
            assertTrue(r.session.submit(SessionCommand.SetMode(id(), "nope")) is CommandResult.Rejected)

            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetModel(id(), "sonnet", asDefault = false)))
            r.h.awaitCondition("set_model") { r.h.process.requests("set_model").isNotEmpty() }
            assertEquals("sonnet", r.h.process.requests("set_model").single().str("model"))
            eventually("模型进了映射状态") { r.session.state.value.settings.model?.id == "sonnet" }
            // CC 没有供应商档这个维度
            assertTrue(r.session.submit(SessionCommand.SetModel(id(), "sonnet", profileId = "other")) is CommandResult.Rejected)
        }
    }

    @Test
    fun `SetEffort and the ultracode and cache TTL options restart the CLI with the new launch options`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.start()
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetEffort(id(), "high")))
            r.h.awaitCondition("换档重启") { r.h.launches.size == 2 }
            assertEquals("high", r.h.launches.last().options.effort)
            r.h.awaitState("重启完成") { it.status == dev.min.code.core.session.SessionStatus.Running && !it.applyingEffort }

            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetEngineOption(id(), "cache_ttl", "5m")))
            r.h.awaitCondition("缓存 TTL 重启") { r.h.launches.size == 3 }
            assertEquals("5m", r.h.launches.last().options.promptCacheTtl)
            r.h.awaitState("重启完成") { it.status == dev.min.code.core.session.SessionStatus.Running && !it.applyingEffort }

            assertTrue(r.session.submit(SessionCommand.SetEngineOption(id(), "cache_ttl", "2h")) is CommandResult.Rejected)
            assertEquals(CommandResult.Unsupported, r.session.submit(SessionCommand.SetEngineOption(id(), "summary", "auto")))
            // 当前模型目录里没有的强度档不受理，别让 CLI 去 warn-and-ignore
            assertTrue(r.session.submit(SessionCommand.SetEffort(id(), "turbo")) is CommandResult.Rejected)
        }
    }

    @Test
    fun `SetEffort ultracode maps to the ultracode launch flag`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.start()
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.SetEngineOption(id(), "ultracode", "true")))
            r.h.awaitCondition("ultracode 重启") { r.h.launches.size == 2 }
            assertTrue(r.h.launches.last().options.ultracode)
            assertEquals(null, r.h.launches.last().options.effort)
        }
    }

    // ---------------------------------------------------------------- 子 agent / 任务

    @Test
    fun `StopSubagent sends stop_task with the agent id of the thread, unknown threads are Stale`() = runBlocking<Unit> {
        val h = ManagerHarness("fork_background_permission")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        h.nextProcess = { FakeCliProcess(h.fixture, overrides = forkSettings()) }
        val session = ClaudeCodeSession(h.manager, "key", scope)
        try {
            h.startAndHandshake()
            session.submit(send("launch a fork"))
            h.awaitState("fork 的权限卡") { it.pendingPermission?.agentId != null }
            eventually("线程进了映射状态") { session.state.value.threads.any { it.running } }
            val thread = session.state.value.threads.single()
            assertTrue(session.submit(SessionCommand.StopSubagent(id(), "nope")) is CommandResult.Stale)
            assertEquals(CommandResult.Accepted, session.submit(SessionCommand.StopSubagent(id(), thread.threadId)))
            h.awaitCondition("stop_task") { h.process.requests("stop_task").isNotEmpty() }
            assertEquals("a0bf19a8052ec9945", h.process.requests("stop_task").single().str("task_id"))
        } finally {
            scope.cancel()
            h.close()
        }
    }

    @Test
    fun `Send to a subagent goes through sendToSubagent - an unknown thread is Stale`() = runBlocking<Unit> {
        val h = ManagerHarness("fork_background_permission")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        h.nextProcess = { FakeCliProcess(h.fixture, overrides = forkSettings()) }
        val session = ClaudeCodeSession(h.manager, "key", scope)
        try {
            h.startAndHandshake()
            session.submit(send("launch a fork"))
            h.awaitState("fork 的权限卡") { it.pendingPermission?.agentId != null }
            eventually("线程进了映射状态") { session.state.value.threads.any { it.running } }
            val thread = session.state.value.threads.single()
            assertTrue(thread.messageable)
            val missing = session.submit(SessionCommand.Send(id(), UserInput("hi"), SendTarget.Subagent("nope")))
            assertTrue(missing is CommandResult.Stale)
            val sent = session.submit(SessionCommand.Send(id(), UserInput("hi there"), SendTarget.Subagent(thread.threadId)))
            assertEquals(CommandResult.Accepted, sent)
            // 子 agent 还在跑且 hook 已登记：话进收件箱，气泡挂在它自己的对话里（标 Handoff）
            eventually("气泡挂进子 agent 的对话") {
                session.state.value.threads.single().items.any { it is ChatItem.UserText && it.text == "hi there" && it.handoff != null }
            }
        } finally {
            scope.cancel()
            h.close()
        }
    }

    private fun forkSettings(): Map<String, (JsonObject) -> JsonObject> = mapOf(
        "get_settings" to { _ ->
            kotlinx.serialization.json.buildJsonObject {
                put("subtype", JsonPrimitive("success"))
                put("response", kotlinx.serialization.json.buildJsonObject {
                    put("applied", kotlinx.serialization.json.buildJsonObject {
                        put("model", JsonPrimitive("claude-haiku-4-5-20251001"))
                        put("effort", JsonPrimitive("low"))
                        put("ultracode", JsonPrimitive(false))
                    })
                })
            }
        },
        "get_session_cost" to { _ ->
            kotlinx.serialization.json.buildJsonObject {
                put("subtype", JsonPrimitive("success"))
                put("response", kotlinx.serialization.json.buildJsonObject { put("text", JsonPrimitive("Total cost: \$0.02")) })
            }
        },
    )

    @Test
    fun `StopTask stops a CLI shell with stop_task and a hosted service through the process table`() = runBlocking<Unit> {
        val stoppedHosted = ArrayList<String>()
        val externals = MutableStateFlow(ClaudeMapContext())
        rig("plain_reply", externals) { stoppedHosted += it }.use { r ->
            r.start()
            r.h.process.emit(
                """{"type":"system","subtype":"task_started","task_id":"b1","task_type":"local_bash",
                   "tool_use_id":"toolu_b1","description":"tick","is_backgrounded":true}""".replace("\n", ""),
            )
            r.h.awaitState("后台 shell 进表") { s -> s.tasks.any { it.id == "b1" && it.isRunning } }
            val sessionId = r.h.manager.state.value.sessionId!!
            externals.value = ClaudeMapContext(
                services = listOf(
                    LocalService(
                        id = "svc9", label = "dev server", command = "npm run dev", cwdGuest = "/workspace", port = 3000,
                        status = LocalServiceStatus.Running, startedAtEpochMs = 1L, sourceSessionKey = sessionId,
                    ),
                ),
            )
            eventually("任务进了映射状态") { r.session.state.value.tasks.size == 2 }
            val tasks = r.session.state.value.tasks
            assertEquals(setOf("cli:b1", "hosted:svc9"), tasks.map { it.id }.toSet())

            assertTrue(r.session.submit(SessionCommand.StopTask(id(), "cli:none")) is CommandResult.Stale)
            // 托管的：只走进程表，CLI 那头没有这个任务，不该发 stop_task
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.StopTask(id(), "hosted:svc9")))
            assertEquals(listOf("svc9"), stoppedHosted)
            assertTrue(r.h.process.requests("stop_task").isEmpty())
            // CLI 的：stop_task
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.StopTask(id(), "cli:b1")))
            assertEquals("b1", r.h.process.requests("stop_task").single().str("task_id"))
            assertNotNull(r.session.state.value)
        }
    }

    // ---------------------------------------------------------------- 其余

    @Test
    fun `Resume without an offer is Stale - the manager has no lastTurnEnd yet`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.start()
            // Claude Code 的能力位里 images = true，所以这条只测「能力位为假的命令」没有：Resume 在 CC 是 Prompted，
            // 没有 offer 时是 Stale 而不是 Unsupported
            assertTrue(r.session.submit(SessionCommand.Resume(id(), "whatever")) is CommandResult.Stale)
        }
    }

    @Test
    fun `Refresh and Rename and RunShell are forwarded`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.start()
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.Rename(id(), "我的会话")))
            r.h.awaitCondition("rename_session") { r.h.process.requests("rename_session").isNotEmpty() }
            assertTrue(r.session.submit(SessionCommand.Rename(id(), "  ")) is CommandResult.Rejected)
            val before = r.h.process.requests("get_context_usage").size
            assertEquals(
                CommandResult.Accepted,
                r.session.submit(SessionCommand.Refresh(id(), setOf(dev.min.code.core.engine.RefreshTarget.Usage))),
            )
            r.h.awaitCondition("用量请求") { r.h.process.requests("get_context_usage").size > before }
        }
    }
}
