package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.AgentListingKind
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.TeamMemberSpec
import dev.min.agent.spi.TeamOp
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ListAgents 的内核一侧：端口列出的是本会话委派出去的子 agent 与团队成员，不含自己。
 * 子会话与成员都停在一个不回话的模型上，所以它们的状态只看名字与归属，不看运行到哪一步。
 */
class ListAgentsRosterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    /** 永远不回话的模型：子会话与团队成员一直处于回合中 */
    private val silent = object : ModelClient {
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow { awaitCancellation() }
    }

    @Test
    fun `the roster lists the delegated subagent and the team members, and never the caller itself`() = run {
        val hub = SessionHub(store, testDeps(silent, emptyList()), newScope())
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        val port = hub.portFor(rt, TurnId("t1"), CallId("c1"))

        val started = port.delegate(DelegateRequest(description = "查竞品", prompt = "去查竞品的定价", background = true))
        val child = (started as DelegateResult.Started).child
        port.team(TeamOp.Create(listOf(TeamMemberSpec("写手", null, "写初稿"))))

        val rows = port.roster()
        val sub = rows.single { it.kind == AgentListingKind.Subagent }
        assertEquals(child.value, sub.id)
        assertEquals("查竞品", sub.description)
        val mate = rows.single { it.kind == AgentListingKind.Teammate }
        assertEquals("写手", mate.name)
        // 团队成员同时也是本会话的子会话：只列一次，按团队成员算
        assertEquals(2, rows.size)
        assertFalse(rows.any { it.id == rt.sessionId.value })
    }

    @Test
    fun `a session that delegated nothing has an empty roster`() = run {
        val hub = SessionHub(store, testDeps(silent, emptyList()), newScope())
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        assertEquals(emptyList<Any>(), hub.portFor(rt, TurnId("t1"), CallId("c1")).roster())
    }
}
