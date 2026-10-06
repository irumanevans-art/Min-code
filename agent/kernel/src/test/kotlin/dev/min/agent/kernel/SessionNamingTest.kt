package dev.min.agent.kernel

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.SessionRenamed
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.reply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset

/**
 * 会话命名（`--name` 建会话、`/rename` 改名、按名续接）：名字经规整后落 session_renamed、折叠取最后一条、
 * 同目录重名建会话时自动加 -2 变体、`--resume <名字>` 按名字找最近的根会话。日志即真相。
 */
class SessionNamingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val cwd = GuestPath("/work")

    /** 不跑真模型：回合一开口就收尾（这里测的是事件与折叠，不是对话） */
    private val model = dev.min.agent.testkit.ScriptedModel(reply(TextPart("好")))

    private fun hub(): SessionHub {
        val base = dev.min.agent.testkit.testDeps(model, emptyList())
        val deps = KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = emptyList(),
            host = base.host, platform = base.platform, zone = ZoneOffset.UTC, retry = base.retry,
            memoryHome = null,
        )
        return SessionHub(store, deps, CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it })
    }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    private suspend fun logIsTruth(rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    @Test
    fun `建会话命名落在日志里且折叠出名字`() = run {
        val h = hub()
        val rt = h.create(cwd, TEST_MODEL, PermissionMode.Default, name = "auth-refactor")
        assertEquals("auth-refactor", rt.state.value.name)
        val renames = store.open(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<SessionRenamed>()
        assertEquals(listOf("auth-refactor"), renames.map { it.name })
        logIsTruth(rt)
    }

    @Test
    fun `名字先规整再落盘：控制与格式字符换空格、去首尾、截到 200、空名拒绝`() = run {
        assertEquals("auth refactor", normalizeSessionName("  auth\u0000refactor\n"))
        assertEquals("x".repeat(200), normalizeSessionName("x".repeat(300)))
        assertNull(normalizeSessionName("  \u0000\u200B \n"))
        val h = hub()
        val rt = h.create(cwd, TEST_MODEL, PermissionMode.Default)
        assertEquals(null, rt.rename("\u0001\u0002"))   // 空名：拒绝、不写
        assertEquals(null, rt.state.value.name)
        assertEquals("太空计划", rt.rename("  太空计划 \n"))
        assertEquals("太空计划", rt.state.value.name)
        // /rename 再改一次：最后一条赢
        assertEquals("orbit", rt.rename("orbit"))
        assertEquals("orbit", rt.state.value.name)
        assertEquals(listOf("太空计划", "orbit"), store.open(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<SessionRenamed>().map { it.name })
        logIsTruth(rt)
    }

    @Test
    fun `同目录重名时建会话自动加 -2 变体，别的目录不受影响`() = run {
        val h = hub()
        h.create(cwd, TEST_MODEL, PermissionMode.Default, name = "auth")
        h.create(cwd, TEST_MODEL, PermissionMode.Default, name = "auth")
        val third = h.create(cwd, TEST_MODEL, PermissionMode.Default, name = "auth")
        assertEquals("auth-3", third.state.value.name)
        // 另一个目录的同名不冲突
        val elsewhere = h.create(GuestPath("/elsewhere"), TEST_MODEL, PermissionMode.Default, name = "auth")
        assertEquals("auth", elsewhere.state.value.name)
    }

    @Test
    fun `按名字找到当前目录最近的根会话，别的目录不算`() = run {
        val h = hub()
        val first = h.create(cwd, TEST_MODEL, PermissionMode.Default, name = "auth")
        val other = h.create(GuestPath("/elsewhere"), TEST_MODEL, PermissionMode.Default, name = "auth")
        first.rename("renamed-away")
        val second = h.create(cwd, TEST_MODEL, PermissionMode.Default, name = "auth")
        // first 已改名：现在叫 auth 的是 second
        assertEquals(second.sessionId, h.findSessionByName(cwd, "auth"))
        assertEquals(other.sessionId, h.findSessionByName(GuestPath("/elsewhere"), "auth"))
        assertEquals(first.sessionId, h.findSessionByName(cwd, "renamed-away"))
        assertNull(h.findSessionByName(cwd, "不存在"))
        assertNull(h.findSessionByName(GuestPath("/nope"), "auth"))
        // 列表摘要带上名字与目录（界面 B7 用）
        val summaries = h.summaries()
        assertEquals(
            mapOf(first.sessionId to "renamed-away", second.sessionId to "auth"),
            summaries.filter { it.cwd == cwd }.associate { it.header.sessionId to it.name },
        )
        assertTrue(summaries.any { it.cwd == GuestPath("/elsewhere") && it.name == "auth" })
    }

    @Test
    fun `重开会话后名字从日志折叠回来`() = run {
        val h = hub()
        val rt = h.create(cwd, TEST_MODEL, PermissionMode.Default, name = "seed")
        rt.rename("harvest")
        val reopened = hub().open(rt.sessionId)!!
        assertEquals("harvest", reopened.state.value.name)
        assertEquals(rt.state.value, reopened.state.value)
    }
}
