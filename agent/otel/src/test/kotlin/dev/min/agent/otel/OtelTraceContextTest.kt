package dev.min.agent.otel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.protocol.PermissionMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 子进程 TRACEPARENT 的计算：与导出用同一套 id，子 agent 沿父链，开关与传播口径照原文 */
class OtelTraceContextTest {
    private val model = ModelRef("anthropic", "claude-test")

    private fun config(vararg extra: Pair<String, String>): OtelConfig {
        val base = mapOf(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1", "MIN_TELEMETRY_FILE" to "/tmp/x.jsonl",
        )
        val merged = base + extra.toMap()
        return OtelConfig.fromEnv { merged[it] }
    }

    private fun provider(cfg: OtelConfig?, vararg logs: SessionLog) = OtelTraceContext({ cfg }) { id -> logs.firstOrNull { it.header.sessionId.value == id } }

    private val tp = Regex("00-([0-9a-f]{32})-([0-9a-f]{16})-01")

    @Test
    fun `根会话：trace id 与导出的 interaction 同源，span id 是这次执行的 span`() = runBlocking {
        val log = FakeSessionLog("root")
        log.add(1, SessionCreated(GuestPath("/w"), model, PermissionMode.Default))
        val header = provider(config(), log).toolExecution(SessionId("root"), TurnId("t1"), CallId("c1"))!!
        val m = tp.matchEntire(header)!!
        assertEquals(SpanIds.trace("root", "t1"), m.groupValues[1])
        assertEquals(SpanIds.execution("root", "c1"), m.groupValues[2])
    }

    @Test
    fun `子 agent：trace id 是父会话那次委派所在的 trace，不是自己的`() = runBlocking {
        val parent = FakeSessionLog("p")
        parent.add(1, SessionCreated(GuestPath("/w"), model, PermissionMode.Default))
        parent.add(2, ToolPlanned(TurnId("tp1"), CallId("agent1"), "Agent", JsonObject(emptyMap()), ResumePolicy.Unknown))
        val child = FakeSessionLog("kid", parent = "p")
        child.add(1, SessionCreated(GuestPath("/w"), model, PermissionMode.Default))
        child.add(2, SubagentOrigin(SessionId("p"), SessionId("p"), CallId("agent1"), "Explore", fork = false, depth = 1, cacheKey = "k"))
        val p = provider(config(), parent, child)
        val header = p.toolExecution(SessionId("kid"), TurnId("kt1"), CallId("c7"))!!
        val m = tp.matchEntire(header)!!
        assertEquals(SpanIds.trace("p", "tp1"), m.groupValues[1])
        assertNotEquals(SpanIds.trace("kid", "kt1"), m.groupValues[1])
        assertEquals(SpanIds.execution("kid", "c7"), m.groupValues[2])
        // 子 agent 的 hook（没有 call）：父是委派它的那次工具调用的 span
        val hook = p.hook(SessionId("kid"), TurnId("kt1"), null, null)!!
        assertEquals(SpanIds.tool("p", "agent1"), tp.matchEntire(hook)!!.groupValues[2])
    }

    @Test
    fun `hook：详细追踪开且有 runId 指向 hook 自己的 span，否则指向父`() = runBlocking {
        val log = FakeSessionLog("root")
        val plain = provider(config(), log)
        val detailed = provider(config("ENABLE_BETA_TRACING_DETAILED" to "1", "BETA_TRACING_ENDPOINT" to "x"), log)
        val s = SessionId("root"); val t = TurnId("t1")
        // 没开详细：有调用 → tool span；没有调用 → interaction
        assertEquals(SpanIds.tool("root", "c1"), tp.matchEntire(plain.hook(s, t, CallId("c1"), "t1:PreToolUse:1")!!)!!.groupValues[2])
        assertEquals(SpanIds.interaction("root", "t1"), tp.matchEntire(plain.hook(s, t, null, "t1:Stop:1")!!)!!.groupValues[2])
        // 开了详细：有 runId → hook span；后台（runId 为空）仍指向父
        assertEquals(SpanIds.hookRun("root", "t1:PreToolUse:1"), tp.matchEntire(detailed.hook(s, t, CallId("c1"), "t1:PreToolUse:1")!!)!!.groupValues[2])
        assertEquals(SpanIds.tool("root", "c1"), tp.matchEntire(detailed.hook(s, t, CallId("c1"), null)!!)!!.groupValues[2])
    }

    @Test
    fun `不传播的情形：没配置、追踪没在产出、自定义代理且没有 PROPAGATE 开关`() = runBlocking {
        val s = SessionId("root"); val t = TurnId("t1"); val c = CallId("c1")
        assertNull(provider(null).toolExecution(s, t, c))
        assertNull(provider(config("CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "")).toolExecution(s, t, c))
        assertNull(provider(config("CLAUDE_CODE_ENABLE_TELEMETRY" to "")).toolExecution(s, t, c))
        val proxy = "ANTHROPIC_BASE_URL" to "https://proxy.internal.example"
        assertNull(provider(config(proxy)).toolExecution(s, t, c))
        assertNotNull(provider(config(proxy, "CLAUDE_CODE_PROPAGATE_TRACEPARENT" to "1")).toolExecution(s, t, c))
        // Anthropic 自己的地址、没设：照常传
        assertNotNull(provider(config("ANTHROPIC_BASE_URL" to "https://api.anthropic.com")).toolExecution(s, t, c))
        assertNotNull(provider(config()).toolExecution(s, t, c))
        assertTrue(config("ANTHROPIC_BASE_URL" to "https://api.anthropic.com").propagateTraceparent)
        // 子域伪装不算（evilanthropic.com）
        assertNull(provider(config("ANTHROPIC_BASE_URL" to "https://evilanthropic.com")).toolExecution(s, t, c))
    }

    @Test
    fun `入站 TRACEPARENT 的 trace id 用于子进程`() = runBlocking {
        val inbound = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        val env = mapOf("CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1", "MIN_TELEMETRY_FILE" to "/tmp/x", "TRACEPARENT" to inbound)
        val cfg = OtelConfig.fromEnv({ env[it] }, acceptInbound = true)
        val log = FakeSessionLog("root")
        val m = tp.matchEntire(provider(cfg, log).toolExecution(SessionId("root"), TurnId("t1"), CallId("c1"))!!)!!
        assertEquals("0af7651916cd43dd8448eb211c80319c", m.groupValues[1])
    }
}
