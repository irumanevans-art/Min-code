package dev.min.code.ui.session

import dev.min.code.core.engine.AgentSession
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.CloseReason
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.LiveStream
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SessionSignal
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 会话页显示哪一边：Claude 的旧注册表与 Min 的统一注册表各有一个活跃项，后写者赢（[shownSession] / [focusChanges]）。 */
class ActiveSessionTest {
    private class Fake(override val ref: SessionRef) : AgentSession {
        override val capabilities = EngineCapabilities.MinAgent
        override val state: StateFlow<AgentSessionState> = MutableStateFlow(AgentSessionState.initial(ref))
        override val stream: StateFlow<LiveStream> = MutableStateFlow(LiveStream.Empty)
        override val signals: SharedFlow<SessionSignal> = MutableSharedFlow()
        override suspend fun submit(command: SessionCommand): CommandResult = CommandResult.Accepted
        override suspend fun searchFiles(query: String, limit: Int): List<String> = emptyList()
        override suspend fun close(reason: CloseReason) = Unit
    }

    private val claude = Fake(SessionRef(EngineKind.ClaudeCode, "c1"))
    private val min = Fake(SessionRef(EngineKind.MinAgent, "s_1"))

    @Test fun `the focused side is shown when it has a session`() {
        assertSame(claude, shownSession(Shown.ClaudeCode, claude, min))
        assertSame(min, shownSession(Shown.Min, claude, min))
    }

    @Test fun `a side that was just closed falls back to the other side`() {
        assertSame(claude, shownSession(Shown.Min, claude, null))
        assertSame(min, shownSession(Shown.ClaudeCode, null, min))
    }

    @Test fun `no session on either side means the start panel`() {
        assertNull(shownSession(Shown.Min, null, null))
        assertNull(shownSession(Shown.ClaudeCode, null, null))
    }

    @Test fun `the last side to change wins and a side closing does not steal the focus`() = runBlocking {
        val claudeKeys = flowOf("c1", null)
        val minRefs = flowOf(SessionRef(EngineKind.MinAgent, "s_1"), null)
        // merge 的先后依赖调度；这里只断言「只发非空」：两边的 null（关掉）一个都不该变成焦点
        val seen = focusChanges(claudeKeys, minRefs).toList()
        assertEquals(setOf(Shown.ClaudeCode, Shown.Min), seen.toSet())
        assertEquals(2, seen.size)
    }

    @Test fun `only Min refs of the Min engine move the focus to Min`() = runBlocking {
        // 统一注册表的活跃项也可能是 Claude 的会话（通知直达）：那不该把焦点拨去 Min
        val seen = focusChanges(emptyFlow(), flowOf(SessionRef(EngineKind.ClaudeCode, "c9"))).toList()
        assertEquals(emptyList<Shown>(), seen)
    }
}
