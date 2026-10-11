package dev.min.agent.spi

import dev.min.agent.protocol.AgentJson
import org.junit.Assert.*
import org.junit.Test

class WorktreeEventsWireTest {
    @Test fun `cwd event default shape old fields and binding round trip`() {
        val json = """{"type":"cwd_changed","oldCwd":"/old","cwd":"/new"}"""
        val event = CwdChanged(GuestPath("/old"), GuestPath("/new"))
        assertEquals(event, AgentJson.decodeFromString(SessionEvent.serializer(), json))
        assertEquals(AgentJson.parseToJsonElement(json), AgentJson.parseToJsonElement(AgentJson.encodeToString(SessionEvent.serializer(), event)))
        val binding = WorktreeBinding("w", GuestPath("/new"), GuestPath("/old"), GuestPath("/old"), "worktree-w", "base", pinned = true)
        for (e in listOf(event.copy(worktree = binding, rules = emptyList(), additionalDirectories = emptyList()), WorktreeFinished(binding, false), CwdWatchPathsChanged(listOf(GuestPath("/new/a"))))) {
            val text = AgentJson.encodeToString(SessionEvent.serializer(), e)
            assertEquals(e, AgentJson.decodeFromString(SessionEvent.serializer(), text))
        }
    }

    @Test fun `shell cwd event round trips and a missing cwd means back to the session directory`() {
        for ((json, event) in listOf(
            """{"type":"shell_cwd_changed","cwd":"/old/sub"}""" to ShellCwdChanged(GuestPath("/old/sub")),
            """{"type":"shell_cwd_changed"}""" to ShellCwdChanged(null),
        )) {
            assertEquals(event, AgentJson.decodeFromString(SessionEvent.serializer(), json))
            assertEquals(AgentJson.parseToJsonElement(json), AgentJson.parseToJsonElement(AgentJson.encodeToString(SessionEvent.serializer(), event)))
        }
    }
}
