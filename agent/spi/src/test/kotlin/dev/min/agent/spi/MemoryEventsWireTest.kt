package dev.min.agent.spi

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.MessageId
import org.junit.Assert.assertEquals
import org.junit.Test

/** memory_loaded 的线形状。期望 JSON 按源码里的 @SerialName 与属性名手写，谁改了字段名或判别值立刻红 */
class MemoryEventsWireTest {

    private fun assertEventWire(event: SessionEvent, expectedJson: String) {
        val text = AgentJson.encodeToString(SessionEvent.serializer(), event)
        assertEquals("往返不相等：$text", event, AgentJson.decodeFromString(SessionEvent.serializer(), text))
        assertEquals(AgentJson.parseToJsonElement(expectedJson), AgentJson.parseToJsonElement(text))
    }

    @Test
    fun `memory loaded keeps its wire shape`() {
        assertEventWire(
            MemoryLoaded(
                files = listOf(
                    LoadedMemoryFile(GuestPath("/root/.claude/CLAUDE.md"), MemorySource.User, "用中文"),
                    LoadedMemoryFile(GuestPath("/workspace/CLAUDE.md"), MemorySource.Project, "p"),
                    LoadedMemoryFile(GuestPath("/workspace/CLAUDE.local.md"), MemorySource.Local, "l"),
                    LoadedMemoryFile(GuestPath("/workspace/.claude/rules/a.md"), MemorySource.Rule, "r"),
                    LoadedMemoryFile(GuestPath("/workspace/AGENTS.md"), MemorySource.Import, "i"),
                ),
                notes = listOf("跳过"),
            ),
            """{"type":"memory_loaded","files":[
                {"path":"/root/.claude/CLAUDE.md","source":"user","content":"用中文"},
                {"path":"/workspace/CLAUDE.md","source":"project","content":"p"},
                {"path":"/workspace/CLAUDE.local.md","source":"local","content":"l"},
                {"path":"/workspace/.claude/rules/a.md","source":"rule","content":"r"},
                {"path":"/workspace/AGENTS.md","source":"import","content":"i"}],
              "notes":["跳过"]}""",
        )
    }

    @Test
    fun `on-demand and auto sources keep their wire names`() {
        assertEventWire(
            MemoryLoaded(
                files = listOf(
                    LoadedMemoryFile(GuestPath("/workspace/src/CLAUDE.md"), MemorySource.Nested, "n"),
                    LoadedMemoryFile(GuestPath("/workspace/.claude/rules/ts.md"), MemorySource.PathRule, "t"),
                    LoadedMemoryFile(GuestPath("/root/.claude/projects/-workspace/memory/MEMORY.md"), MemorySource.Auto, "- a"),
                ),
            ),
            """{"type":"memory_loaded","files":[
                {"path":"/workspace/src/CLAUDE.md","source":"nested","content":"n"},
                {"path":"/workspace/.claude/rules/ts.md","source":"path_rule","content":"t"},
                {"path":"/root/.claude/projects/-workspace/memory/MEMORY.md","source":"auto","content":"- a"}]}""",
        )
    }

    @Test
    fun `memory loaded carries the auto memory state and omits it when off`() {
        assertEventWire(
            MemoryLoaded(
                files = emptyList(),
                autoMemory = AutoMemoryInfo(GuestPath("/root/.claude/projects/-workspace/memory"), totalLines = 412, loadedLines = 200),
            ),
            """{"type":"memory_loaded","files":[],
              "autoMemory":{"directory":"/root/.claude/projects/-workspace/memory","totalLines":412,"loadedLines":200}}""",
        )
        // 索引还不存在：两个行数都是默认值，不落盘
        assertEventWire(
            MemoryLoaded(files = emptyList(), autoMemory = AutoMemoryInfo(GuestPath("/m"))),
            """{"type":"memory_loaded","files":[],"autoMemory":{"directory":"/m"}}""",
        )
    }

    @Test
    fun `memory attached keeps its wire shape`() {
        assertEventWire(
            MemoryAttached(
                assistantMessageId = MessageId("msg_7"),
                files = listOf(
                    LoadedMemoryFile(GuestPath("/workspace/src/api/CLAUDE.md"), MemorySource.Nested, "api rules"),
                    LoadedMemoryFile(GuestPath("/workspace/.claude/rules/ts.md"), MemorySource.PathRule, "ts rules"),
                ),
                notes = listOf("太长"),
            ),
            """{"type":"memory_attached","assistantMessageId":"msg_7","files":[
                {"path":"/workspace/src/api/CLAUDE.md","source":"nested","content":"api rules"},
                {"path":"/workspace/.claude/rules/ts.md","source":"path_rule","content":"ts rules"}],
              "notes":["太长"]}""",
        )
        assertEventWire(
            MemoryAttached(MessageId("msg_8"), emptyList()),
            """{"type":"memory_attached","assistantMessageId":"msg_8","files":[]}""",
        )
    }

    @Test
    fun `memory loaded omits empty notes`() {
        // 没有说明时不落盘 notes；只有说明、没有文件也是合法的一条（全被跳过时让人知道为什么）
        assertEventWire(MemoryLoaded(files = emptyList()), """{"type":"memory_loaded","files":[]}""")
        assertEventWire(MemoryLoaded(files = emptyList(), notes = listOf("n")), """{"type":"memory_loaded","files":[],"notes":["n"]}""")
    }
}
