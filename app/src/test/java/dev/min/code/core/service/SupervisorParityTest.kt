package dev.min.code.core.service

import dev.min.code.core.claudecode.RegistryHarness
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.InteractionBrief
import dev.min.code.core.engine.InteractionKind
import dev.min.code.core.engine.InterruptCause
import dev.min.code.core.engine.LiveSessionSummary
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SupervisorEvent
import dev.min.code.core.engine.TurnEnd
import dev.min.code.core.engine.summary
import dev.min.code.core.engine.supervisorDiff
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台服务 / 通知边沿判定的**对拍基准**（M2-B 的 B0）。
 *
 * B2 的新版 `supervisorDiff` 要对同一组输入给出和这里一致的结果（再加 Question / PlanReview /
 * Resume 的新边沿），B5 把 Claude / Codex 两路并成一路时靠它兜底。
 *
 * 和 [ClaudeSupervisorDiffTest] 的分工：那份是写边沿时顺手钉的几条代表性用例，
 * 这份把旧 Claude 函数的**输入空间逐格走完**（previous 有无 × 前后台 × 每个字段翻转；
 * Codex 那半 B9 起改写成统一 supervisorDiff 的等价用例），并把「一次观察里事件的先后与取舍」也钉上。标着「当前行为」的断言不代表那样是对的，
 * 只是迁移时不许顺手改掉；看起来像缺陷的在注释里点了名，汇报里也列了。
 */
class SupervisorParityTest {

    // ------------------------------------------------------------------ Claude

    private fun claude(
        busy: Boolean = false,
        pendingId: String? = null,
        pendingTool: String? = null,
        status: SessionStatus = SessionStatus.Running,
        isLive: Boolean = true,
        errorMessage: String? = null,
    ) = SessionSupervisor.ClaudeSeen(busy, pendingId, pendingTool, status, isLive, errorMessage)

    private fun claudeDiff(
        previous: SessionSupervisor.ClaudeSeen?,
        current: SessionSupervisor.ClaudeSeen,
        foreground: Boolean = false,
    ) = claudeSupervisorDiff(previous, current, foreground)

    private val none = emptyList<ClaudeSupervisorEvent>()

    // 待批请求（Permission / PermissionCleared）

    @Test
    fun `claude - the very first frame already asking notifies in the background, not in the foreground`() {
        val asking = claude(busy = true, pendingId = "r1", pendingTool = "Bash")
        // 注意：previous == null 时「完成 / 死亡」被压住，但「待批」不被压 —— 冷启动就挂着请求要提醒
        assertEquals(listOf(ClaudeSupervisorEvent.Permission("Bash", "r1")), claudeDiff(null, asking))
        assertEquals(none, claudeDiff(null, asking, foreground = true))
    }

    @Test
    fun `claude - a quiet first frame says nothing whatever it looks like`() {
        assertEquals(none, claudeDiff(null, claude()))
        assertEquals(none, claudeDiff(null, claude(busy = true)))
        assertEquals(none, claudeDiff(null, claude(status = SessionStatus.Failed, isLive = false, errorMessage = "x")))
        assertEquals(none, claudeDiff(null, claude(status = SessionStatus.Closed, isLive = false)))
    }

    @Test
    fun `claude - a missing tool name goes out as an empty string`() {
        assertEquals(
            listOf(ClaudeSupervisorEvent.Permission("", "r1")),
            claudeDiff(claude(busy = true), claude(busy = true, pendingId = "r1", pendingTool = null)),
        )
    }

    @Test
    fun `claude - a request swapped for another without a gap notifies again in the background and stays silent in the foreground`() {
        val a = claude(busy = true, pendingId = "r1", pendingTool = "Read")
        val b = claude(busy = true, pendingId = "r2", pendingTool = "Bash")
        assertEquals(listOf(ClaudeSupervisorEvent.Permission("Bash", "r2")), claudeDiff(a, b))
        // 前台：既不提醒，也不「撤」—— 状态栏上那条旧的要等回到前台时由监督器统一撤
        assertEquals(none, claudeDiff(a, b, foreground = true))
    }

    @Test
    fun `claude - the same request on later frames does not repeat, whatever else moves`() {
        val a = claude(busy = true, pendingId = "r1", pendingTool = "Read")
        assertEquals(none, claudeDiff(a, a))
        assertEquals(none, claudeDiff(a, a.copy(errorMessage = "retrying")))
        // 同一个 id 工具名变了也不重发：判的是 id
        assertEquals(none, claudeDiff(a, a.copy(pendingTool = "Write")))
    }

    @Test
    fun `claude - an answered request is cleared in both foreground and background`() {
        val a = claude(busy = true, pendingId = "r1", pendingTool = "Read")
        val cleared = claude(busy = true)
        assertEquals(listOf(ClaudeSupervisorEvent.PermissionCleared), claudeDiff(a, cleared))
        assertEquals(listOf(ClaudeSupervisorEvent.PermissionCleared), claudeDiff(a, cleared, foreground = true))
    }

    @Test
    fun `claude - nothing pending before or after clears nothing`() {
        assertEquals(none, claudeDiff(claude(busy = true), claude(busy = true)))
        assertEquals(none, claudeDiff(claude(busy = true), claude(busy = true), foreground = true))
    }

    // 一轮完成（TurnDone）

    @Test
    fun `claude - turn done is busy falling on a live session, background only`() {
        assertEquals(listOf(ClaudeSupervisorEvent.TurnDone), claudeDiff(claude(busy = true), claude(busy = false)))
        assertEquals(none, claudeDiff(claude(busy = true), claude(busy = false), foreground = true))
    }

    @Test
    fun `claude - the other busy transitions are silent`() {
        assertEquals(none, claudeDiff(claude(busy = false), claude(busy = true)))   // 开始一轮
        assertEquals(none, claudeDiff(claude(busy = true), claude(busy = true)))
        assertEquals(none, claudeDiff(claude(busy = false), claude(busy = false)))
    }

    @Test
    fun `claude - busy falling because the session died reports the death only, not a finished turn`() {
        val dead = claude(busy = false, status = SessionStatus.Closed, isLive = false)
        assertEquals(listOf(ClaudeSupervisorEvent.Died(null)), claudeDiff(claude(busy = true), dead))
    }

    // 会话结束（Died）

    @Test
    fun `claude - died fires when the status flips into Failed or Closed, carrying the error message`() {
        val failed = claude(status = SessionStatus.Failed, isLive = false, errorMessage = "boom")
        val closed = claude(status = SessionStatus.Closed, isLive = false, errorMessage = "claude 进程退出 (exit 1)")
        assertEquals(listOf(ClaudeSupervisorEvent.Died("boom")), claudeDiff(claude(), failed))
        assertEquals(listOf(ClaudeSupervisorEvent.Died("claude 进程退出 (exit 1)")), claudeDiff(claude(), closed))
        // Starting 里就挂了也算（启动失败）
        assertEquals(
            listOf(ClaudeSupervisorEvent.Died("boom")),
            claudeDiff(claude(status = SessionStatus.Starting), failed),
        )
    }

    @Test
    fun `claude - died does not repeat while the session stays dead, and Failed to Closed counts as a new flip`() {
        val failed = claude(status = SessionStatus.Failed, isLive = false, errorMessage = "boom")
        assertEquals(none, claudeDiff(failed, failed))
        // 当前行为：Failed → Closed 也是「状态变了且落在 Failed/Closed」，会再报一次
        assertEquals(
            listOf(ClaudeSupervisorEvent.Died("boom")),
            claudeDiff(failed, failed.copy(status = SessionStatus.Closed)),
        )
    }

    @Test
    fun `claude - non-fatal status flips are silent`() {
        assertEquals(none, claudeDiff(claude(status = SessionStatus.Starting), claude(status = SessionStatus.Running)))
        assertEquals(none, claudeDiff(claude(status = SessionStatus.Idle), claude(status = SessionStatus.Starting)))
        // 重启（换档 / 换供应商）：Running → Starting 不是死
        assertEquals(none, claudeDiff(claude(), claude(status = SessionStatus.Starting, busy = false)))
    }

    @Test
    fun `claude - foreground suppresses turn done and died`() {
        assertEquals(none, claudeDiff(claude(busy = true), claude(busy = false), foreground = true))
        assertEquals(
            none,
            claudeDiff(claude(), claude(status = SessionStatus.Failed, isLive = false, errorMessage = "x"), foreground = true),
        )
    }

    // 同一次观察里的多件事：先后与取舍

    @Test
    fun `claude - a death while asking clears first and then reports, in that order`() {
        val asking = claude(busy = true, pendingId = "r1", pendingTool = "Bash")
        val failed = claude(status = SessionStatus.Failed, isLive = false, errorMessage = "boom")
        assertEquals(
            listOf(ClaudeSupervisorEvent.PermissionCleared, ClaudeSupervisorEvent.Died("boom")),
            claudeDiff(asking, failed),
        )
        // 前台只剩「撤」
        assertEquals(listOf(ClaudeSupervisorEvent.PermissionCleared), claudeDiff(asking, failed, foreground = true))
    }

    @Test
    fun `claude - a turn ending right after the answer clears then completes`() {
        val asking = claude(busy = true, pendingId = "r1", pendingTool = "Bash")
        assertEquals(
            listOf(ClaudeSupervisorEvent.PermissionCleared, ClaudeSupervisorEvent.TurnDone),
            claudeDiff(asking, claude(busy = false)),
        )
    }

    @Test
    fun `claude - a request arriving in the same frame as the turn ending notifies for both, request first`() {
        // 当前行为（极少出现，但这就是现在的取舍）
        assertEquals(
            listOf(ClaudeSupervisorEvent.Permission("Bash", "r1"), ClaudeSupervisorEvent.TurnDone),
            claudeDiff(claude(busy = true), claude(busy = false, pendingId = "r1", pendingTool = "Bash")),
        )
    }

    @Test
    fun `claude - sequence replay A, B, cleared, done gives exactly the events a user would see`() {
        val frames = listOf(
            claude(busy = true),
            claude(busy = true, pendingId = "r1", pendingTool = "Read"),
            claude(busy = true, pendingId = "r2", pendingTool = "Bash"),
            claude(busy = true),
            claude(busy = false),
        )
        val events = frames.zipWithNext { prev, cur -> claudeDiff(prev, cur) }
        assertEquals(
            listOf(
                listOf(ClaudeSupervisorEvent.Permission("Read", "r1")),
                listOf(ClaudeSupervisorEvent.Permission("Bash", "r2")),
                listOf(ClaudeSupervisorEvent.PermissionCleared),
                listOf(ClaudeSupervisorEvent.TurnDone),
            ),
            events,
        )
    }

    // ------------------------------------------------------------------ Codex（B9 起走统一的 supervisorDiff）
    //
    // 旧 Codex 专用路径（codexSupervisorDiff / CodexSeen）已删，这里把它那套边沿用
    // `LiveSessionSummary(ref = Codex)` 重写成等价用例。与旧版有三处有意差异，各有一条测试钉住：
    // 失败一轮由 lastTurnEnd 判（不再看 errorMessage），审批撤掉与退出 / 完成落在同一帧时两件事都报，
    // 审批到达与一轮结束同帧时两件事都报。

    private val codexRef = SessionRef(EngineKind.Codex, "th-1")

    private fun codex(
        busy: Boolean = false,
        approvalId: String? = null,
        status: SessionStatus = SessionStatus.Running,
        errorMessage: String? = null,
        end: TurnEnd? = null,
    ) = summary(
        ref = codexRef, status = status, busy = busy, error = errorMessage, end = end,
        head = approvalId?.let { InteractionBrief(it, InteractionKind.Approval, "Bash", null) },
    )

    private fun codexDiff(previous: LiveSessionSummary?, current: LiveSessionSummary, foreground: Boolean = false) =
        supervisorDiff(previous, current, foreground)

    private fun approvalEvent(id: String) = SupervisorEvent.Interaction(InteractionBrief(id, InteractionKind.Approval, "Bash", null))

    private val noEvents = emptyList<SupervisorEvent>()

    @Test
    fun `codex - the very first frame already asking notifies in the background, not in the foreground`() {
        assertEquals(listOf(approvalEvent("1")), codexDiff(null, codex(approvalId = "1")))
        assertEquals(noEvents, codexDiff(null, codex(approvalId = "1"), foreground = true))
    }

    @Test
    fun `codex - a quiet first frame says nothing whatever it looks like`() {
        assertEquals(noEvents, codexDiff(null, codex()))
        assertEquals(noEvents, codexDiff(null, codex(busy = true)))
        assertEquals(noEvents, codexDiff(null, codex(status = SessionStatus.Failed, errorMessage = "x")))
        assertEquals(noEvents, codexDiff(null, codex(status = SessionStatus.Closed)))
    }

    @Test
    fun `codex - approval pending in the background raises, in the foreground stays silent`() {
        assertEquals(listOf(approvalEvent("42")), codexDiff(codex(), codex(approvalId = "42")))
        assertEquals(noEvents, codexDiff(codex(), codex(approvalId = "42"), foreground = true))
    }

    /** A 还挂着就直接换成 B：不重发的话状态栏上留着 A 的按钮，点下去应答的是没人等的请求 */
    @Test
    fun `codex - an approval swapped straight for another re-raises in the background and is silent in the foreground`() {
        assertEquals(listOf(approvalEvent("43")), codexDiff(codex(approvalId = "42"), codex(approvalId = "43")))
        assertEquals(noEvents, codexDiff(codex(approvalId = "42"), codex(approvalId = "43"), foreground = true))
    }

    @Test
    fun `codex - the same approval on later frames does not repeat, whatever else moves`() {
        val a = codex(busy = true, approvalId = "42")
        assertEquals(noEvents, codexDiff(a, a))
        assertEquals(noEvents, codexDiff(a, a.copy(errorMessage = "x")))
    }

    /** 审批被清掉要撤通知：前台也得执行，状态栏还挂着那条 ongoing */
    @Test
    fun `codex - an approval cleared elsewhere cancels the notification in both foreground and background`() {
        val a = codex(busy = true, approvalId = "42")
        assertEquals(listOf(SupervisorEvent.InteractionCleared), codexDiff(a, codex(busy = true)))
        assertEquals(listOf(SupervisorEvent.InteractionCleared), codexDiff(a, codex(busy = true), foreground = true))
    }

    @Test
    fun `codex - busy falling cleanly is a finished turn, background only`() {
        assertEquals(listOf(SupervisorEvent.TurnDone), codexDiff(codex(busy = true), codex()))
        assertEquals(listOf(SupervisorEvent.TurnDone), codexDiff(codex(busy = true), codex(end = TurnEnd.Completed)))
        assertEquals(noEvents, codexDiff(codex(busy = true), codex(), foreground = true))
    }

    /** 失败一轮进程还活着（status 仍是 Running）：靠 lastTurnEnd = Failed 区别于完成，通知带原因 */
    @Test
    fun `codex - busy falling with a failed turn end is incomplete, not done and not dead`() {
        val end = TurnEnd.Failed(null, "401 Incorrect API key")
        assertEquals(
            listOf(SupervisorEvent.TurnIncomplete(end, null)),
            codexDiff(codex(busy = true), codex(errorMessage = "401 Incorrect API key", end = end)),
        )
        // 前台不报
        assertEquals(noEvents, codexDiff(codex(busy = true), codex(errorMessage = "401", end = end), foreground = true))
    }

    /** 失败的错误信息一直挂着，后面的帧不能再报一次 */
    @Test
    fun `codex - a failed turn does not re-notify while its error lingers`() {
        val end = TurnEnd.Failed(null, "401")
        val after = codex(errorMessage = "401", end = end)
        assertEquals(noEvents, codexDiff(after, after))
    }

    @Test
    fun `codex - a turn the user interrupted is silent`() {
        assertEquals(
            noEvents,
            codexDiff(codex(busy = true), codex(end = TurnEnd.Interrupted(InterruptCause.User))),
        )
    }

    @Test
    fun `codex - busy falling on a session that is not Running reports nothing`() {
        assertEquals(noEvents, codexDiff(codex(busy = true), codex(status = SessionStatus.Idle)))
        assertEquals(noEvents, codexDiff(codex(busy = true), codex(status = SessionStatus.Starting)))
    }

    @Test
    fun `codex - other busy transitions are silent`() {
        assertEquals(noEvents, codexDiff(codex(), codex(busy = true)))
        assertEquals(noEvents, codexDiff(codex(busy = true), codex(busy = true)))
        assertEquals(noEvents, codexDiff(codex(), codex()))
    }

    @Test
    fun `codex - died fires on a flip into Failed or Closed, only once per flip`() {
        assertEquals(
            listOf(SupervisorEvent.Died("x", null)),
            codexDiff(codex(), codex(status = SessionStatus.Failed, errorMessage = "x")),
        )
        assertEquals(listOf(SupervisorEvent.Died(null, null)), codexDiff(codex(), codex(status = SessionStatus.Closed)))
        val failed = codex(status = SessionStatus.Failed, errorMessage = "x")
        assertEquals(noEvents, codexDiff(failed, failed))
        // 与 Claude 同：Failed -> Closed 也是一次翻转
        assertEquals(listOf(SupervisorEvent.Died("x", null)), codexDiff(failed, failed.copy(status = SessionStatus.Closed)))
    }

    @Test
    fun `codex - busy falling together with death reports death, not completion`() {
        assertEquals(
            listOf(SupervisorEvent.Died("退出码 1", null)),
            codexDiff(codex(busy = true), codex(busy = false, status = SessionStatus.Failed, errorMessage = "退出码 1")),
        )
    }

    @Test
    fun `codex - foreground suppresses turn done, incomplete and died`() {
        assertEquals(noEvents, codexDiff(codex(busy = true), codex(), foreground = true))
        assertEquals(noEvents, codexDiff(codex(), codex(status = SessionStatus.Closed), foreground = true))
        assertEquals(noEvents, codexDiff(codex(), codex(status = SessionStatus.Failed), foreground = true))
    }

    /**
     * 有意差异：旧 Codex 一次观察最多一个事件，审批撤掉的同一帧里会吞掉退出 / 完成
     * （app-server 退出时恰恰是同一次状态更新里清 pendingApproval 并置 Failed / Closed）。
     * 统一路径同 Claude：先撤、再报，两件事都在。
     */
    @Test
    fun `codex - an approval cleared in the same frame as death or turn end no longer swallows the second event`() {
        val asking = codex(busy = true, approvalId = "1")
        assertEquals(
            listOf(SupervisorEvent.InteractionCleared, SupervisorEvent.Died("boom", null)),
            codexDiff(asking, codex(busy = false, status = SessionStatus.Failed, errorMessage = "boom")),
        )
        assertEquals(
            listOf(SupervisorEvent.InteractionCleared, SupervisorEvent.TurnDone),
            codexDiff(asking, codex(busy = false)),
        )
        // 前台只剩「撤」
        assertEquals(
            listOf(SupervisorEvent.InteractionCleared),
            codexDiff(asking, codex(busy = false, status = SessionStatus.Failed), foreground = true),
        )
    }

    @Test
    fun `codex - an approval arriving in the same frame as turn end notifies for both, approval first`() {
        assertEquals(
            listOf(approvalEvent("1"), SupervisorEvent.TurnDone),
            codexDiff(codex(busy = true), codex(busy = false, approvalId = "1")),
        )
        assertEquals(noEvents, codexDiff(codex(busy = true), codex(busy = false, approvalId = "1"), foreground = true))
    }

    // ------------------------------------------------------------------ 通知上的「拒绝」

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * 通知上的拒绝和界面里的拒绝**不对称**（设计 §1.4 已知缺口，B5 不许顺手改）：
     *
     * - 界面里的「拒绝」不带说明，应答带 `interrupt:true`，CLI 停下来等人；
     * - 通知上的「拒绝」带说明「用户在通知里拒绝」，**不带 interrupt**，模型读到说明后会接着干、可能换条路再试一遍。
     *
     * 通知按钮走的是 `ClaudeCodePermissionReceiver` → `registry.answerPermission(key, allow, requestId)`，
     * 这里直接调那个入口（Receiver 本身要 Koin + Android 组件，JVM 单测起不来）。
     */
    @Test
    fun `notification deny carries an explanation and does not interrupt, while the in-app deny interrupts without one`() = runBlocking<Unit> {
        RegistryHarness("bash_with_permission").use { rh ->
            // 通知路径
            val (key, h) = rh.newSession()!!
            h.manager.send("run it")
            val pending = h.awaitState("权限请求") { it.pendingPermission != null }.pendingPermission!!
            assertTrue(rh.registry.answerPermission(key, allow = false, requestId = pending.requestId))
            h.awaitCondition("应答写出") { h.process.written.any { it.str("type") == "control_response" } }
            val viaNotification = h.process.written.single { it.str("type") == "control_response" }["response"]!!.jsonObject
            assertEquals(pending.requestId, viaNotification.str("request_id"))
            assertEquals(
                buildJsonObject {
                    put("behavior", "deny")
                    put("message", "用户在通知里拒绝")
                },
                viaNotification["response"],
            )
            assertFalse("通知上的拒绝不打断", viaNotification["response"]!!.jsonObject.containsKey("interrupt"))

            // 界面路径（同一个夹具，另起一个会话）
            val (_, h2) = rh.newSession()!!
            h2.manager.send("run it")
            val pending2 = h2.awaitState("权限请求") { it.pendingPermission != null }.pendingPermission!!
            assertTrue(h2.manager.answerPermission(allow = false))
            h2.awaitCondition("应答写出") { h2.process.written.any { it.str("type") == "control_response" } }
            val viaUi = h2.process.written.single { it.str("type") == "control_response" }["response"]!!.jsonObject
            assertEquals(pending2.requestId, viaUi.str("request_id"))
            assertEquals(
                buildJsonObject {
                    put("behavior", "deny")
                    put("message", "User denied")
                    put("interrupt", true)
                },
                viaUi["response"],
            )
        }
    }

    @Test
    fun `notification allow is the plain once-only allow, same as the in-app one`() = runBlocking<Unit> {
        RegistryHarness("bash_with_permission").use { rh ->
            val (key, h) = rh.newSession()!!
            h.manager.send("run it")
            val pending = h.awaitState("权限请求") { it.pendingPermission != null }.pendingPermission!!
            assertTrue(rh.registry.answerPermission(key, allow = true, requestId = pending.requestId))
            h.awaitCondition("应答写出") { h.process.written.any { it.str("type") == "control_response" } }
            val answer = h.process.written.single { it.str("type") == "control_response" }["response"]!!.jsonObject
            assertEquals(buildJsonObject { put("behavior", "allow") }, answer["response"])
            h.awaitTurnEnd()
        }
    }
}
