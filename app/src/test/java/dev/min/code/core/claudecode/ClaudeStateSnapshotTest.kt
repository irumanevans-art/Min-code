package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 夹具回放 -> 会话页读到的字段 -> 稳定文本快照，与提交进仓库的快照文件比对（M2-B 的 B0）。
 *
 * 这些快照是 B4（`ClaudeStateMapper` 黄金测试）和 B6（换掉 ClaudeCodeVM）对拍的基准。
 * 渲染与规范化规则见 [ClaudeStateSnapshot]；快照在 `src/test/resources/claudecode/snapshots/<夹具名>.txt`，
 * 每个夹具一份，里面按「阶段」分节（例如 asking / turn-done）。
 *
 * 重新生成：`-Dmin.snapshot.update=true` 或环境变量 `MIN_SNAPSHOT_UPDATE=true`，细节见 [SnapshotStore]。
 *
 * 为了让快照不抖，每个阶段取样前先等**静默**：渲染出来的文本连续若干次采样完全一致
 * （收尾之后还有读用量、拟标题、stderr 入流这些异步尾巴，不等它们落地快照会随调度时序变）。
 * 中途阶段只取夹具停住的点（等权限应答、`# pause`），不取流式输出进行到一半的点 ——
 * 那种点每次切到哪一块都不一样，interrupt_midturn 因此只有收尾阶段。
 */
class ClaudeStateSnapshotTest {

    /** 夹具里没录到 get_settings / get_session_cost 的应答时给最小应答（同 ClaudeCodeForkSubagentTest） */
    private fun minimalSettingsOverrides(cost: String): Map<String, (JsonObject) -> JsonObject> = mapOf(
        "get_settings" to { _ ->
            buildJsonObject {
                put("subtype", "success")
                put("response", buildJsonObject {
                    put("applied", buildJsonObject {
                        put("model", "claude-haiku-4-5-20251001")
                        put("effort", "low")
                        put("ultracode", false)
                    })
                })
            }
        },
        "get_session_cost" to { _ ->
            buildJsonObject {
                put("subtype", "success")
                put("response", buildJsonObject { put("text", cost) })
            }
        },
    )

    /** 一份快照文件 = 若干阶段 */
    private class Recorder(private val h: ManagerHarness) {
        val text = StringBuilder()

        fun render(): String = ClaudeStateSnapshot.render(h.manager.state.value, h.dir, h.launches.map { it.sessionId })

        /** 等渲染结果连续 4 次（间隔 100ms）一致，再记一个阶段 */
        suspend fun stage(name: String) {
            var last = render()
            var same = 0
            val deadline = System.currentTimeMillis() + 15_000
            while (same < 4) {
                if (System.currentTimeMillis() > deadline) fail("阶段「$name」一直没静默，最后的状态：\n$last")
                delay(100)
                val now = render()
                if (now == last) same++ else { same = 0; last = now }
            }
            text.append("== stage: ").append(name).append(" ==\n").append(last).append('\n')
        }
    }

    private fun scenario(
        fixture: String,
        overrides: Map<String, (JsonObject) -> JsonObject>? = null,
        body: suspend ManagerHarness.(Recorder) -> Unit,
    ) = runBlocking<Unit> {
        ManagerHarness(fixture).use { h ->
            if (overrides != null) h.nextProcess = { FakeCliProcess(h.fixture, overrides = overrides) }
            val rec = Recorder(h)
            rec.text.append("# fixture: ").append(fixture).append('\n')
            h.body(rec)
            SnapshotStore.check(fixture, rec.text.toString())
        }
    }

    private suspend fun ManagerHarness.handshakeWithUsage() {
        startAndHandshake()
        awaitState("用量读回") { it.contextTokens != null }
    }

    // ---------------------------------------------------------------- 夹具

    @Test
    fun `plain_reply`() = scenario("plain_reply") { rec ->
        handshakeWithUsage()
        rec.stage("handshaken")
        manager.send("Reply with exactly the two letters OK and nothing else.")
        awaitTurnEnd()
        awaitState("拟名") { it.liveTitle != null }
        rec.stage("turn-done")
    }

    @Test
    fun `bash_with_permission`() = scenario("bash_with_permission") { rec ->
        handshakeWithUsage()
        manager.send("run it")
        awaitState("权限请求") { it.pendingPermission != null }
        rec.stage("asking")
        assertTrue(manager.answerPermission(allow = true))
        awaitTurnEnd()
        rec.stage("turn-done")
    }

    @Test
    fun `bash_denied_interrupt`() = scenario("bash_denied_interrupt") { rec ->
        handshakeWithUsage()
        manager.send("run it")
        awaitState("权限请求") { it.pendingPermission != null }
        rec.stage("asking")
        assertTrue(manager.answerPermission(allow = false))
        awaitTurnEnd()
        rec.stage("denied")
    }

    @Test
    fun `interrupt_before_output`() = scenario("interrupt_before_output") { rec ->
        handshakeWithUsage()
        manager.send("Write a haiku about the sea.")
        awaitState("busy") { it.busy }
        rec.stage("busy-before-output")
        manager.interrupt()
        awaitTurnEnd()
        rec.stage("withdrawn")
    }

    @Test
    fun `interrupt_midturn`() = scenario("interrupt_midturn") { rec ->
        handshakeWithUsage()
        manager.send("count")
        awaitState("开始流") { it.streamingText.isNotEmpty() && it.turnProduced }
        manager.interrupt()
        awaitTurnEnd()
        rec.stage("interrupted")
    }

    @Test
    fun `interrupt_during_permission`() = scenario("interrupt_during_permission") { rec ->
        handshakeWithUsage()
        manager.send("Run this exact Bash command and nothing else: ls / . Then reply with one word: done.")
        awaitState("权限请求") { it.pendingPermission != null }
        rec.stage("asking")
        manager.interrupt()
        awaitTurnEnd()
        rec.stage("interrupted")
    }

    @Test
    fun `model_not_found`() = scenario("model_not_found") { rec ->
        handshakeWithUsage()
        manager.send("Say hi.")
        awaitTurnEnd()
        awaitState("stderr 入流") { st -> st.items.any { it is ChatItem.ProcessOutput } }
        rec.stage("failed")
    }

    @Test
    fun `subagent`() = scenario("subagent") { rec ->
        handshakeWithUsage()
        manager.send("go")
        awaitTurnEnd()
        rec.stage("turn-done")
    }

    @Test
    fun `background_agents_followup`() = scenario(
        "background_agents_followup",
        minimalSettingsOverrides("Total cost: \$0.06"),
    ) { rec ->
        startAndHandshake()
        manager.send("go")
        awaitTurnEnd()
        // 夹具停在 # pause t2：主回合 result 已落、CLI 自开的两轮还没开始
        rec.stage("main-turn-ended")
        listOf("t2", "t2init", "mid2", "t3", "t3init", "mid3").forEach(process::releasePause)
        awaitState("第三轮收尾") { st ->
            !st.busy && st.items.any { it is ChatItem.AssistantText && "Both agents completed" in it.text }
        }
        rec.stage("followups-done")
    }

    @Test
    fun `fork_background_permission`() = scenario(
        "fork_background_permission",
        minimalSettingsOverrides("Total cost: \$0.02"),
    ) { rec ->
        startAndHandshake()
        manager.send("launch a fork")
        awaitState("fork 的权限卡") { it.pendingPermission?.agentId != null }
        rec.stage("fork-asking")
        assertTrue(manager.answerPermission(allow = true))
        awaitState("fork 跑完且主线程交代完") { st ->
            !st.busy && st.items.any { it is ChatItem.AssistantText && "Fork completed" in it.text }
        }
        rec.stage("fork-done")
    }

    // ---------------------------------------------------------------- 比对工具本身

    @Test
    fun `lineDiff shows changed lines with their snapshot line numbers and context`() {
        val expected = "a: 1\nb: 2\nc: 3\nd: 4\ne: 5\nf: 6\ng: 7\nh: 8"
        val actual = "a: 1\nb: 2\nc: 3\nd: X\ne: 5\nf: 6\ng: 7\nh: 8"
        val diff = SnapshotStore.lineDiff(expected, actual)
        assertTrue(diff, "-    4 | d: 4" in diff)
        assertTrue(diff, "+    4 | d: X" in diff)
        assertTrue("上下文各 2 行", "     2 | b: 2" in diff && "     6 | f: 6" in diff)
        assertTrue("再远的行不出现", "a: 1" !in diff && "h: 8" !in diff)
    }

    @Test
    fun `lineDiff of identical text is empty`() {
        assertEquals("", SnapshotStore.lineDiff("a\nb", "a\nb"))
    }

    @Test
    fun `render never prints chat item ids, wall-clock times or the scratch directory`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.handshakeWithUsage()
            h.manager.send("hi")
            h.awaitTurnEnd()
            val state = h.manager.state.value
            val text = ClaudeStateSnapshot.render(state, h.dir)
            state.items.forEach { assertTrue("条目 id 不该进快照：${it.id}", it.id !in text) }
            assertTrue("finishedAt 应被规范化", "finishedAt=<ts>" in text)
            assertTrue("lastTurnFinishedAt 应被规范化", "lastTurnFinishedAt: <ts>" in text)
            state.lastTurnFinishedAt!!.toString().let { assertTrue("墙钟毫秒不该进快照", it !in text) }
            assertTrue("临时目录不该进快照", h.dir.path !in text)
        }
    }
}
