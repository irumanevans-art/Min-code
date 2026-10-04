package dev.min.code.core.service

import dev.min.code.core.claudecode.RegistryHarness
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台服务 / 通知边沿判定的**对拍基准**（M2-B 的 B0）。
 *
 * B2 的新版 `supervisorDiff` 要对同一组输入给出和这里一致的结果（再加 Question / PlanReview /
 * Resume 的新边沿），B5 把 Claude / Codex 两路并成一路时靠它兜底。
 *
 * 和 [ClaudeSupervisorDiffTest] / [CodexSupervisorDiffTest] 的分工：那两份是写边沿时顺手钉的几条代表性用例，
 * 这份把两个函数的**输入空间逐格走完**（previous 有无 × 前后台 × 每个字段翻转），
 * 并把「一次观察里事件的先后与取舍」也钉上。标着「当前行为」的断言不代表那样是对的，
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

    // ------------------------------------------------------------------ Codex

    private fun codex(
        busy: Boolean = false,
        approvalKey: String? = null,
        status: SessionStatus = SessionStatus.Running,
        errorMessage: String? = null,
    ) = SessionSupervisor.CodexSeen(busy, approvalKey, status, errorMessage)

    private fun codexDiff(
        previous: SessionSupervisor.CodexSeen?,
        current: SessionSupervisor.CodexSeen,
        foreground: Boolean = false,
    ) = codexSupervisorDiff(previous, current, foreground)

    @Test
    fun `codex - the very first frame already asking notifies in the background, not in the foreground`() {
        // 和 Claude 一样：previous == null 压住完成 / 死亡，不压审批
        assertEquals(CodexSupervisorEvent.Approval, codexDiff(null, codex(approvalKey = "1")))
        assertNull(codexDiff(null, codex(approvalKey = "1"), foreground = true))
    }

    @Test
    fun `codex - a quiet first frame says nothing whatever it looks like`() {
        assertNull(codexDiff(null, codex()))
        assertNull(codexDiff(null, codex(busy = true)))
        assertNull(codexDiff(null, codex(status = SessionStatus.Failed, errorMessage = "x")))
        assertNull(codexDiff(null, codex(status = SessionStatus.Closed)))
    }

    @Test
    fun `codex - approval swap re-raises in the background and is silent in the foreground`() {
        assertEquals(CodexSupervisorEvent.Approval, codexDiff(codex(approvalKey = "1"), codex(approvalKey = "2")))
        assertNull(codexDiff(codex(approvalKey = "1"), codex(approvalKey = "2"), foreground = true))
    }

    @Test
    fun `codex - the same approval on later frames does not repeat`() {
        val a = codex(busy = true, approvalKey = "1")
        assertNull(codexDiff(a, a))
        assertNull(codexDiff(a, a.copy(errorMessage = "x")))
    }

    @Test
    fun `codex - approval cleared in both foreground and background`() {
        val a = codex(busy = true, approvalKey = "1")
        assertEquals(CodexSupervisorEvent.ApprovalCleared, codexDiff(a, codex(busy = true)))
        assertEquals(CodexSupervisorEvent.ApprovalCleared, codexDiff(a, codex(busy = true), foreground = true))
    }

    @Test
    fun `codex - turn done and turn failed are told apart by the error message, blank counts as none`() {
        assertEquals(CodexSupervisorEvent.TurnDone, codexDiff(codex(busy = true), codex()))
        assertEquals(CodexSupervisorEvent.TurnDone, codexDiff(codex(busy = true), codex(errorMessage = "")))
        assertEquals(CodexSupervisorEvent.TurnDone, codexDiff(codex(busy = true), codex(errorMessage = "   ")))
        assertEquals(CodexSupervisorEvent.TurnFailed("401"), codexDiff(codex(busy = true), codex(errorMessage = "401")))
    }

    @Test
    fun `codex - busy falling on a session that is not Running reports nothing`() {
        // 当前行为：完成 / 失败只在 status == Running 时判；Idle 下 busy 落下是静默的
        assertNull(codexDiff(codex(busy = true), codex(busy = false, status = SessionStatus.Idle)))
        assertNull(codexDiff(codex(busy = true), codex(busy = false, status = SessionStatus.Starting)))
    }

    @Test
    fun `codex - other busy transitions are silent`() {
        assertNull(codexDiff(codex(), codex(busy = true)))
        assertNull(codexDiff(codex(busy = true), codex(busy = true)))
        assertNull(codexDiff(codex(), codex()))
    }

    @Test
    fun `codex - died fires on a flip into Failed or Closed, only once`() {
        assertEquals(CodexSupervisorEvent.Died("x"), codexDiff(codex(), codex(status = SessionStatus.Failed, errorMessage = "x")))
        assertEquals(CodexSupervisorEvent.Died(null), codexDiff(codex(), codex(status = SessionStatus.Closed)))
        val failed = codex(status = SessionStatus.Failed, errorMessage = "x")
        assertNull(codexDiff(failed, failed))
        // 当前行为：Failed → Closed 再报一次（同 Claude）
        assertEquals(CodexSupervisorEvent.Died("x"), codexDiff(failed, failed.copy(status = SessionStatus.Closed)))
    }

    @Test
    fun `codex - busy falling together with death reports death, not completion`() {
        assertEquals(
            CodexSupervisorEvent.Died("退出码 1"),
            codexDiff(codex(busy = true), codex(busy = false, status = SessionStatus.Failed, errorMessage = "退出码 1")),
        )
    }

    @Test
    fun `codex - foreground suppresses turn done, turn failed and died`() {
        assertNull(codexDiff(codex(busy = true), codex(), foreground = true))
        assertNull(codexDiff(codex(busy = true), codex(errorMessage = "x"), foreground = true))
        assertNull(codexDiff(codex(), codex(status = SessionStatus.Closed), foreground = true))
    }

    /**
     * 当前行为（看起来像缺陷）：Codex 一次观察最多一个事件，审批相关的先返回。
     * 所以「待批的审批和会话死亡落在同一帧」时只会撤通知、**不报死亡** ——
     * 而 [dev.min.code.core.codex.CodexAppServerManager] 退出时恰恰是在同一次状态更新里
     * 同时清 pendingApproval 并置 Failed / Closed（见其 448-453 行）。Claude 一路同帧会两个都发。
     */
    @Test
    fun `codex - an approval cleared in the same frame as death or turn end swallows that second event`() {
        val asking = codex(busy = true, approvalKey = "1")
        assertEquals(
            CodexSupervisorEvent.ApprovalCleared,
            codexDiff(asking, codex(busy = false, status = SessionStatus.Failed, errorMessage = "boom")),
        )
        assertEquals(CodexSupervisorEvent.ApprovalCleared, codexDiff(asking, codex(busy = false)))
    }

    @Test
    fun `codex - an approval arriving in the same frame as turn end wins over the turn event`() {
        assertEquals(
            CodexSupervisorEvent.Approval,
            codexDiff(codex(busy = true), codex(busy = false, approvalKey = "1")),
        )
        assertNull(codexDiff(codex(busy = true), codex(busy = false, approvalKey = "1"), foreground = true))
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
