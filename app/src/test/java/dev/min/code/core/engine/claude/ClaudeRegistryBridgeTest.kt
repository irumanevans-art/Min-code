package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.RegistryHarness
import dev.min.code.core.claudecode.eventually
import dev.min.code.core.engine.EngineAvailability
import dev.min.code.core.engine.SupervisorEvent
import dev.min.code.core.engine.supervisorDiff
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** B5 桥接旧页面：旧注册表建立的会话也必须保活，且通知要看见退出的最后一帧。 */
class ClaudeRegistryBridgeTest {
    @Test fun `legacy sessions appear without being opened through the engine and retain the exit edge`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            RegistryHarness("plain_reply").use { legacy ->
                val engine = ClaudeCodeEngine(legacy.registry, scope, MutableStateFlow(EngineAvailability.Ready))
                val (key, harness) = legacy.newSession()!!
                eventually("旧会话进入统一摘要") { engine.live.value.any { it.ref.id == key && it.keepAlive } }
                val before = engine.live.value.single()
                harness.process.crash(1)
                eventually("退出仍在统一摘要里") {
                    engine.live.value.any { it.ref.id == key && it.status == SessionStatus.Closed && !it.keepAlive }
                }
                val after = engine.live.value.single()
                assertTrue(supervisorDiff(before, after, false).any { it is SupervisorEvent.Died })
            }
        } finally { scope.cancel() }
    }

    @Test fun `opening for a notification answer wraps the existing process without changing the legacy active session`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            RegistryHarness("plain_reply").use { legacy ->
                val engine = ClaudeCodeEngine(legacy.registry, scope, MutableStateFlow(EngineAvailability.Ready))
                val (first, harness) = legacy.newSession()!!
                val (second, _) = legacy.newSession()!!
                val session = engine.open(first).getOrThrow() as ClaudeCodeSession
                assertSame(harness.manager, session.manager)
                assertEquals(1, harness.launches.size)
                assertEquals(second, legacy.registry.activeKey.value)
            }
        } finally { scope.cancel() }
    }
}
