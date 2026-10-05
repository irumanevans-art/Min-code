package dev.min.code.core.claudecode

import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClaudeCodeSessionRegistry 的特征测试（M2-B 的 B0）：**把现在的行为钉住**，B5 把它拆进
 * `ClaudeCodeEngine` 时对拍用。标着「当前行为」的断言不代表那样是对的，只是迁移时不许顺手改掉。
 *
 * manager 是真的，进程那头是 [FakeCliProcess] 回放真 CLI 录下的夹具（见 [RegistryHarness]）。
 */
class ClaudeCodeSessionRegistryTest {

    private fun FakeCliProcess.controlResponses() = written
        .filter { it.str("type") == "control_response" }
        .map { it["response"]!!.jsonObject }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    // ---------------------------------------------------------------- 新建与上限

    @Test
    fun `newSession registers the session under a fresh key, makes it active and launches it with that key`() = runBlocking<Unit> {
        RegistryHarness("plain_reply").use { rh ->
            val (key, h) = rh.newSession()!!
            assertEquals(key, rh.registry.activeKey.value)
            assertSame(h.manager, rh.registry.get(key))
            assertSame(h.manager, rh.registry.active())
            // key 就是传给 CLI 的新会话 id（进程起来之前就得登记，所以由注册表先生成）
            val launch = h.launches.single()
            assertEquals(key, launch.options.newSessionId)
            assertNull(launch.options.resumeSessionId)
            assertEquals(key, launch.sessionId)
        }
    }

    @Test
    fun `newSession strips a resume id from the options it is given`() = runBlocking<Unit> {
        RegistryHarness("plain_reply").use { rh ->
            val (key, h) = rh.newSession(ClaudeCodeManager.SessionOptions(resumeSessionId = "old-session"))!!
            assertNull(h.launches.single().options.resumeSessionId)
            assertEquals(key, h.launches.single().options.newSessionId)
        }
    }

    @Test
    fun `the fourth live session is refused - null, no manager built, active key untouched`() = runBlocking<Unit> {
        RegistryHarness("plain_reply").use { rh ->
            assertEquals(3, ClaudeCodeSessionRegistry.MAX_CONCURRENT)
            val keys = (1..3).map { rh.newSession()!!.first }
            assertEquals(3, rh.registry.liveCount())
            assertFalse(rh.registry.canStartMore())

            assertNull(rh.registry.newSession(ClaudeCodeManager.SessionOptions()))
            assertEquals(3, rh.factoryCalls)
            assertEquals(keys.last(), rh.registry.activeKey.value)
            assertEquals(3, rh.registry.liveCount())
        }
    }

    @Test
    fun `the cap counts only live processes - a dead one frees its slot and is pruned by the next newSession`() = runBlocking<Unit> {
        RegistryHarness("plain_reply").use { rh ->
            val sessions = (1..3).map { rh.newSession()!! }
            val (firstKey, first) = sessions.first()
            first.process.crash(1)
            first.awaitState("关闭") { it.status == SessionStatus.Closed }
            assertEquals(2, rh.registry.liveCount())
            assertTrue(rh.registry.canStartMore())
            // 死壳还登记着，直到有人回收
            assertNotNull(rh.registry.get(firstKey))

            val (newKey, _) = rh.newSession()!!
            // newSession 一进门先 pruneDead：死壳被摘掉，新的占位
            assertNull(rh.registry.get(firstKey))
            assertEquals(newKey, rh.registry.activeKey.value)
            assertEquals(3, rh.registry.liveCount())
        }
    }

    @Test
    fun `openSession on a registered live session only switches to it without relaunching`() = runBlocking<Unit> {
        RegistryHarness("plain_reply").use { rh ->
            val (firstKey, first) = rh.newSession()!!
            rh.newSession()!!
            assertTrue(rh.registry.activeKey.value != firstKey)

            val opened = rh.registry.openSession(firstKey)
            assertSame(first.manager, opened)
            assertEquals(firstKey, rh.registry.activeKey.value)
            assertEquals("没有重启进程", 1, first.launches.size)
            assertEquals(2, rh.factoryCalls)
        }
    }

    @Test
    fun `openSession is refused at the cap unless the session is already live`() = runBlocking<Unit> {
        RegistryHarness("plain_reply").use { rh ->
            val keys = (1..3).map { rh.newSession()!!.first }
            assertNull(rh.registry.openSession("some-session-on-disk"))
            assertEquals(3, rh.factoryCalls)
            // 已经在跑的不受上限影响
            assertNotNull(rh.registry.openSession(keys.first()))
        }
    }

    // ---------------------------------------------------------------- 回收与关闭

    @Test
    fun `pruneDead removes dead sessions but never the active one even if it is dead`() = runBlocking<Unit> {
        RegistryHarness("plain_reply").use { rh ->
            val (aKey, a) = rh.newSession()!!
            val (bKey, b) = rh.newSession()!!
            val (cKey, c) = rh.newSession()!!   // 活跃的是 c
            a.process.crash(1)
            c.process.crash(1)
            a.awaitState("a 关闭") { it.status == SessionStatus.Closed }
            c.awaitState("c 关闭") { it.status == SessionStatus.Closed }

            rh.registry.pruneDead()
            assertNull("死的非活跃项被摘掉", rh.registry.get(aKey))
            assertNotNull("活着的留下", rh.registry.get(bKey))
            // 当前活跃的那个不动：用户正看着它的报错
            assertNotNull(rh.registry.get(cKey))
            assertEquals(cKey, rh.registry.activeKey.value)
            assertSame(b.manager, rh.registry.get(bKey))
        }
    }

    @Test
    fun `closing the active session falls back to the most recently registered remaining one`() = runBlocking<Unit> {
        RegistryHarness("plain_reply").use { rh ->
            val (aKey, _) = rh.newSession()!!
            val (bKey, _) = rh.newSession()!!
            val (cKey, c) = rh.newSession()!!
            assertEquals(cKey, rh.registry.activeKey.value)

            rh.registry.closeSession(cKey)
            assertEquals(bKey, rh.registry.activeKey.value)
            assertNull(rh.registry.get(cKey))
            // dispose 是异步的，进程最终被关掉
            eventually("c 的进程退出") { c.process.hasExited }

            // 关的不是活跃项：活跃项不变
            rh.registry.closeSession(aKey)
            assertEquals(bKey, rh.registry.activeKey.value)

            // 关最后一个：没有活跃项，registry.state 退回 Idle 默认值（UI 不用判空）
            rh.registry.closeSession(bKey)
            assertNull(rh.registry.activeKey.value)
            assertNull(rh.registry.active())
            eventually("state 退回默认") { rh.registry.state.value == ClaudeCodeManager.SessionState() }
        }
    }

    @Test
    fun `closing an unknown key is a no-op`() = runBlocking<Unit> {
        RegistryHarness("plain_reply").use { rh ->
            val (key, _) = rh.newSession()!!
            rh.registry.closeSession("nope")
            assertEquals(key, rh.registry.activeKey.value)
            assertEquals(1, rh.registry.liveCount())
        }
    }

    @Test
    fun `switchTo changes the active key only for registered keys`() = runBlocking<Unit> {
        RegistryHarness("plain_reply").use { rh ->
            val (aKey, _) = rh.newSession()!!
            val (bKey, _) = rh.newSession()!!
            rh.registry.switchTo(aKey)
            assertEquals(aKey, rh.registry.activeKey.value)
            rh.registry.switchTo("nope")
            assertEquals(aKey, rh.registry.activeKey.value)
            assertEquals("没有杀进程", 2, rh.registry.liveCount())
            assertTrue(bKey != aKey)
        }
    }

    // ---------------------------------------------------------------- 换供应商后重启

    @Test
    fun `reloadConnection restarts idle sessions and hands back the keys of busy ones it skipped`() = runBlocking<Unit> {
        RegistryHarness("plain_reply").use { rh ->
            val (idleKey, idle) = rh.newSession()!!
            val (busyKey, busy) = rh.newSession()!!
            val (deadKey, dead) = rh.newSession()!!
            busy.process.holdTurns = true
            busy.manager.send("work")
            busy.awaitState("busy") { it.busy }
            dead.process.crash(1)
            dead.awaitState("关闭") { it.status == SessionStatus.Closed }

            val skipped = rh.registry.reloadConnection()
            assertEquals("只有在忙的被跳过；闲的重启、死的不碰", listOf(busyKey), skipped)
            eventually("闲的那个重启了一次") { idle.launches.size == 2 }
            assertEquals("在忙的没动", 1, busy.launches.size)
            assertTrue("在忙的那一轮没被掐", busy.manager.state.value.busy)
            assertEquals("死的没被拉起", 1, dead.launches.size)
            // 重启走的是 --resume 同一个会话
            assertEquals(idleKey, idle.launches.last().sessionId)
            assertTrue(deadKey !in skipped && idleKey !in skipped)
        }
    }

    @Test
    fun `reloadConnection with includeBusy restarts the busy ones too and reports nothing skipped`() = runBlocking<Unit> {
        RegistryHarness("plain_reply").use { rh ->
            val (_, idle) = rh.newSession()!!
            val (_, busy) = rh.newSession()!!
            busy.process.holdTurns = true
            busy.manager.send("work")
            busy.awaitState("busy") { it.busy }

            assertEquals(emptyList<String>(), rh.registry.reloadConnection(includeBusy = true))
            eventually("两个都重启了") { idle.launches.size == 2 && busy.launches.size == 2 }
        }
    }

    @Test
    fun `reloadConnection with nothing live returns an empty list`() = runBlocking<Unit> {
        RegistryHarness("plain_reply").use { rh ->
            assertEquals(emptyList<String>(), rh.registry.reloadConnection())
            assertEquals(emptyList<String>(), rh.registry.reloadConnection(includeBusy = true))
        }
    }

    // ---------------------------------------------------------------- 通知应答

    @Test
    fun `answerPermission checks the request id - a stale or foreign id answers nothing`() = runBlocking<Unit> {
        RegistryHarness("bash_with_permission").use { rh ->
            val (key, h) = rh.newSession()!!
            h.manager.send("run it")
            val pending = h.awaitState("权限请求") { it.pendingPermission != null }.pendingPermission!!

            // 未知会话、对不上的 id：都是 false，且挂着的请求原封不动、没有写任何应答
            assertFalse(rh.registry.answerPermission("nope", true, pending.requestId))
            assertFalse(rh.registry.answerPermission(key, true, "someone-else"))
            assertEquals(pending.requestId, h.manager.state.value.pendingPermission?.requestId)
            assertTrue(h.process.controlResponses().isEmpty())

            // 对得上才应答；写出去的是「只批准这一次」
            assertTrue(rh.registry.answerPermission(key, true, pending.requestId))
            h.awaitCondition("应答写出") { h.process.controlResponses().isNotEmpty() }
            val answer = h.process.controlResponses().single()
            assertEquals(pending.requestId, answer.str("request_id"))
            assertEquals(buildJsonObject { put("behavior", "allow") }, answer["response"])
            // 同一条不能答两次
            assertFalse(rh.registry.answerPermission(key, true, pending.requestId))
            h.awaitTurnEnd()
        }
    }

    @Test
    fun `answerPermission without a request id answers whatever is at the head (pre-upgrade notifications)`() = runBlocking<Unit> {
        RegistryHarness("bash_with_permission").use { rh ->
            val (key, h) = rh.newSession()!!
            h.manager.send("run it")
            val pending = h.awaitState("权限请求") { it.pendingPermission != null }.pendingPermission!!
            assertTrue(rh.registry.answerPermission(key, true, null))
            h.awaitCondition("应答写出") { h.process.controlResponses().isNotEmpty() }
            assertEquals(pending.requestId, h.process.controlResponses().single().str("request_id"))
            h.awaitTurnEnd()
        }
    }

    // ---------------------------------------------------------------- 给监督器的聚合流

    @Test
    fun `liveSessions carries the pending request id and tool the supervisor diffs on, and anyLive tracks the processes`() = runBlocking<Unit> {
        RegistryHarness("bash_with_permission").use { rh ->
            assertFalse(rh.registry.anyLive.value)
            val (key, h) = rh.newSession()!!
            eventually("anyLive") { rh.registry.anyLive.value }

            h.manager.send("run it")
            val pending = h.awaitState("权限请求") { it.pendingPermission != null }.pendingPermission!!
            eventually("liveSessions 带上待批请求") {
                rh.registry.liveSessions.value.singleOrNull()?.pendingPermissionId == pending.requestId
            }
            val live = rh.registry.liveSessions.value.single()
            assertEquals(key, live.key)
            assertEquals("Bash", live.pendingPermissionTool)
            assertTrue(live.busy)
            assertTrue(live.isActive)
            assertTrue(live.isLive)
            eventually("anyBusy") { rh.registry.anyBusy.value }

            rh.registry.answerPermission(key, true, pending.requestId)
            h.awaitTurnEnd()
            // 聚合流里「待批请求清空」与「助手文本到达」是先后两次推送：两件事一起等，不在中间那一帧上断言
            eventually("待批请求从聚合流里消失、回复文本到达") {
                rh.registry.liveSessions.value.single().let { it.pendingPermissionId == null && it.lastAssistantText == "done" }
            }
            val done = rh.registry.liveSessions.value.single()
            assertEquals("done", done.lastAssistantText)
            eventually("anyBusy 落下") { !rh.registry.anyBusy.value }

            rh.registry.closeAll()
            eventually("anyLive 落下") { !rh.registry.anyLive.value }
            assertEquals(emptyList<ClaudeCodeSessionRegistry.LiveSession>(), rh.registry.liveSessions.value)
        }
    }

    @Test
    fun `sessions created back to back count against the cap before their start coroutines run`() = runBlocking<Unit> {
        RegistryHarness("bash_with_permission").use { rh ->
            // 不等握手连开三个：启动在各自的协程里排队，名额必须从叫它启动那一刻就算上，也不能被当成死会话清掉
            val started = List(3) { rh.registry.newSession(ClaudeCodeManager.SessionOptions()) }
            assertTrue(started.all { it != null })
            assertNull("第四个被并发上限挡住", rh.registry.newSession(ClaudeCodeManager.SessionOptions()))
            assertEquals(3, rh.factoryCalls)
            started.forEach { m -> assertTrue("刚启动的会话占着名额", m!!.holdsSlot) }
        }
    }
}
