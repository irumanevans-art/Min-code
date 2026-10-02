package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.RegistryHarness
import dev.min.code.core.claudecode.awaitHandshake
import dev.min.code.core.claudecode.eventually
import dev.min.code.core.engine.CloseReason
import dev.min.code.core.engine.EngineAvailability
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.StartOptions
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ClaudeCodeEngine` 套在真注册表上的行为：新建 / 打开 / 在线摘要 / 上限（manager 与进程都是 B0 的夹具） */
class ClaudeCodeEngineTest {

    private fun engineOf(rh: RegistryHarness, scope: CoroutineScope) =
        ClaudeCodeEngine(rh.registry, scope, MutableStateFlow<EngineAvailability>(EngineAvailability.Ready))

    @Test
    fun `create wraps the registry's new manager, live lists it and the cap is the registry's`() = runBlocking<Unit> {
        // configProbe 要占一个 manager，所以多备几个
        RegistryHarness("plain_reply", size = 6).use { rh ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val engine = engineOf(rh, scope)
                assertEquals(EngineKind.ClaudeCode, engine.kind)
                val first = engine.create(StartOptions(cwd = "/workspace")).getOrThrow()
                // 会话 ref 的 id 就是注册表 key = 传给 CLI 的新会话 id
                val key = rh.registry.activeKey.value!!
                assertEquals(key, first.ref.id)
                assertEquals(EngineKind.ClaudeCode, first.ref.engine)
                rh.harnessOf(rh.registry.get(key)!!).awaitHandshake()
                eventually("在线摘要里有它") { engine.live.value.any { it.ref == first.ref } }
                assertEquals(SessionStatus.Running, engine.live.value.single().status)
                assertTrue("CC 在线 = 进程活着 = 保活", engine.live.value.single().keepAlive)

                // 打开已经在线的会话：拿回同一个，不重开
                assertSame(first, engine.open(key).getOrThrow())

                engine.create(StartOptions(cwd = "/workspace")).getOrThrow()
                engine.create(StartOptions(cwd = "/workspace")).getOrThrow()
                val refused = engine.create(StartOptions(cwd = "/workspace"))
                assertTrue(refused.exceptionOrNull() is AtCapacityException)

                engine.closeAll(CloseReason.StopAll)
                eventually("关完了") { engine.live.value.isEmpty() }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `start options reach the launch - mode, model and the ultracode effort`() = runBlocking<Unit> {
        RegistryHarness("plain_reply", size = 3).use { rh ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val engine = engineOf(rh, scope)
                engine.create(StartOptions(cwd = "/workspace/app", modelId = "sonnet", modeId = "plan", effortId = "ultracode")).getOrThrow()
                val key = rh.registry.activeKey.value!!
                val h = rh.harnessOf(rh.registry.get(key)!!)
                h.awaitHandshake()
                val options = h.launches.single().options
                assertEquals("sonnet", options.model)
                assertEquals("plan", options.permissionMode.wire)
                assertTrue(options.ultracode)
                assertEquals("/workspace/app", options.cwd)
            } finally {
                scope.cancel()
            }
        }
    }
}
