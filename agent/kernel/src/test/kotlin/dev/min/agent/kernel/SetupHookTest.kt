package dev.min.agent.kernel

import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.Note
import dev.min.agent.testkit.FakeFs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Setup（官方 hooks 页「Setup」、cli-reference 的 `--init` / `--init-only` / `--maintenance`）：只在这三个旗标启动时触发，
 * matcher 是 init / maintenance，在 SessionStart 之前跑；无决策控制，每个退出码都丢弃它的 JSON 输出。
 */
class SetupHookTest {
    @get:Rule val tmp = TemporaryFolder()
    private val rigs = mutableListOf<HookTestRig>()
    @After fun tearDown() = rigs.forEach { it.close() }

    private fun rig(http: FakeHttp = FakeHttp(), block: suspend HookTestRig.() -> Unit) = runBlocking {
        val fs = FakeFs().apply { mkdir("/workspace") }
        val rig = HookTestRig(tmp.newFolder().toPath(), fs = fs, http = http).also { rigs += it }
        withTimeout(30_000) { rig.block() }
    }

    private suspend fun HookTestRig.create(hooks: kotlinx.serialization.json.JsonObject, setup: List<String>) = hub.create(
        dev.min.agent.spi.GuestPath("/workspace"), dev.min.agent.testkit.TEST_MODEL, dev.min.agent.protocol.PermissionMode.BypassPermissions,
        initial = listOf(dev.min.agent.spi.HooksLoaded(listOf(dev.min.agent.spi.HookSourceData("user", hooks)))),
        setupTriggers = setup,
    )

    @Test fun `a normal start fires no Setup hook`() = rig {
        create(httpHooks("Setup" to null, "SessionStart" to null), emptyList())
        assertTrue(http.bodies("Setup").isEmpty())
        assertEquals(1, http.bodies("SessionStart").size)
    }

    @Test fun `init fires Setup with trigger init before SessionStart`() = rig {
        val rt = create(httpHooks("Setup" to "init", "SessionStart" to null), listOf("init"))
        val order = http.sent.map { if ("\"hook_event_name\":\"Setup\"" in it.body) "Setup" else "SessionStart" }
        assertEquals(listOf("Setup", "SessionStart"), order)
        val body = http.bodies("Setup").single()
        assertTrue(body, body.contains("\"trigger\":\"init\""))
        assertTrue(body, body.contains("\"session_id\":\"${rt.sessionId.value}\""))
        assertEquals(1, hooksRan(rt, "Setup").size)
    }

    @Test fun `the matcher separates init from maintenance`() = rig {
        create(httpHooks("Setup" to "maintenance"), listOf("init"))
        assertTrue("matcher 是 maintenance，init 不触发", http.bodies("Setup").isEmpty())
        create(httpHooks("Setup" to "maintenance"), listOf("maintenance"))
        val body = http.bodies("Setup").single()
        assertTrue(body, body.contains("\"trigger\":\"maintenance\""))
    }

    @Test fun `init and maintenance given together both fire, in that order`() = rig {
        create(httpHooks("Setup" to null), listOf("init", "maintenance"))
        assertEquals(listOf("init", "maintenance"), http.bodies("Setup").map { if ("\"trigger\":\"init\"" in it) "init" else "maintenance" })
    }

    @Test fun `every output field of a Setup hook is discarded`() = rig(
        http = FakeHttp { HttpHookResult.Responded(200, """{"continue":false,"systemMessage":"hello","decision":"block","reason":"r","hookSpecificOutput":{"hookEventName":"Setup","additionalContext":"ctx"}}""") },
    ) {
        val rt = create(httpHooks("Setup" to null), listOf("init"))
        val events = events(rt)
        assertTrue("additionalContext 不进对话", events.none { it is HookContextAdded })
        assertTrue("systemMessage 不显示", events.filterIsInstance<Note>().none { it.text == "hello" })
        assertTrue("continue:false 也停不了会话：会话建好了", rt.state.value.turn == null)
        assertEquals(1, hooksRan(rt, "Setup").size)
    }

    @Test fun `Setup is also fired when an existing session is reopened with the flag`() = rig {
        val rt = create(httpHooks("Setup" to null, "SessionStart" to null), emptyList())
        assertTrue(hub.close(rt.sessionId))
        hub.open(rt.sessionId, resumedByUser = true, setupTriggers = listOf("maintenance"))
        val body = http.bodies("Setup").single()
        assertTrue(body, body.contains("\"trigger\":\"maintenance\""))
        assertEquals("resume 的 SessionStart 随后跑", 2, http.bodies("SessionStart").size)
    }
}
