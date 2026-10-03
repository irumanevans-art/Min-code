package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.CompactionStarted
import dev.min.agent.spi.CompactionTrigger
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.InputPromoted
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.MemoryAttached
import dev.min.agent.spi.MemoryLoaded
import dev.min.agent.spi.MemorySource
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.Role
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.args
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 按需加载的记忆（MemoryAttached）在折叠与模型视图里的样子：已加载路径集合、压缩后的移出、渲染顺序、分身继承 */
class MemoryAttachFoldTest {
    private val header = LogHeader(LOG_FORMAT_VERSION, SessionId("s1"), createdAtMs = 0)
    private val created = SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
    private val turn = TurnId("t1")

    private fun nested(path: String, content: String = "规则") = LoadedMemoryFile(GuestPath(path), MemorySource.Nested, content)

    private fun foldOf(vararg events: SessionEvent): SessionState =
        fold(header, (listOf<SessionEvent>(created) + events).mapIndexed { i, e -> EventRecord(i + 1L, 0, e) })

    /** 一个回合：用户说一句，然后 [calls] 里每个助手消息各带一个 Read 调用并有结果 */
    private fun conversation(vararg assistants: String): List<SessionEvent> = buildList {
        add(InputReceived(ClientMsgId("c1"), listOf(TextPart("读文件")), Delivery.Queue))
        add(TurnStarted(turn, TurnTrigger.User))
        add(InputPromoted(ClientMsgId("c1"), MessageId("u1")))
        assistants.forEachIndexed { i, id ->
            add(ModelRequestStarted(turn, RequestId("r$i"), 1, TEST_MODEL, "fp"))
            add(
                AssistantMessageCompleted(
                    turn, RequestId("r$i"), MessageId(id),
                    listOf(ToolCallPart(CallId("call-$id"), "Read", args("file_path" to "/workspace/src/f$i.ts"))),
                    Usage(), StopReason.ToolUse,
                ),
            )
            add(ToolPlanned(turn, CallId("call-$id"), "Read", args("file_path" to "/workspace/src/f$i.ts"), ResumePolicy.Unknown))
            add(ToolOutcome(turn, CallId("call-$id"), ToolResult(listOf(TextPart("内容 $i")))))
        }
    }

    private fun compact(through: String, id: String) = listOf(
        CompactionStarted(turn, id, CompactionTrigger.Manual),
        ContextCompacted(turn, id, CompactionTrigger.Manual, "摘要", MessageId(through)),
    )

    @Test
    fun `attached memory is tracked per assistant message and joins the paths already in context`() {
        val a = nested("/workspace/src/CLAUDE.md")
        val b = nested("/workspace/src/api/CLAUDE.md")
        val s = foldOf(
            MemoryLoaded(listOf(LoadedMemoryFile(GuestPath("/workspace/CLAUDE.md"), MemorySource.Project, "p"))),
            *conversation("a1", "a2").toTypedArray(),
            MemoryAttached(MessageId("a1"), listOf(a)),
            MemoryAttached(MessageId("a2"), listOf(b)),
        )
        assertEquals(mapOf(MessageId("a1") to listOf(a), MessageId("a2") to listOf(b)), s.memoryAttachments)
        assertEquals(setOf("/workspace/CLAUDE.md", a.path.value, b.path.value), s.memoryPathsInContext.map { it.value }.toSet())
    }

    @Test
    fun `an attachment must hang on an existing assistant message`() {
        try {
            foldOf(*conversation("a1").toTypedArray(), MemoryAttached(MessageId("nope"), listOf(nested("/workspace/src/CLAUDE.md"))))
            fail("应当拒绝挂在不存在的消息上")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("nope"))
        }
        try {
            // 用户消息也不行：按需记忆只挂在带工具调用的助手消息上
            foldOf(*conversation("a1").toTypedArray(), MemoryAttached(MessageId("u1"), listOf(nested("/workspace/src/CLAUDE.md"))))
            fail("应当拒绝挂在用户消息上")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("u1"))
        }
    }

    @Test
    fun `compaction moves paths on summarised messages out of the set and keeps the snapshot ones`() {
        val a = nested("/workspace/src/CLAUDE.md")
        val b = nested("/workspace/src/api/CLAUDE.md")
        val snapshot = LoadedMemoryFile(GuestPath("/workspace/CLAUDE.md"), MemorySource.Project, "p")
        val s = foldOf(
            MemoryLoaded(listOf(snapshot)),
            *conversation("a1", "a2").toTypedArray(),
            MemoryAttached(MessageId("a1"), listOf(a)),
            MemoryAttached(MessageId("a2"), listOf(b)),
            *compact(through = "a1", id = "k1").toTypedArray(),
        )
        // a1 被摘要取代：它上面的记忆不在视图里了；a2 还在
        assertEquals(setOf(snapshot.path, b.path), s.memoryPathsInContext)
        // 附件本身仍在日志与状态里（原文永不删除），只是不再渲染
        assertEquals(setOf(MessageId("a1"), MessageId("a2")), s.memoryAttachments.keys)
        val texts = s.modelMessages().flatMap { it.parts }.filterIsInstance<TextPart>().map { it.text }
        assertTrue(texts.none { "src/CLAUDE.md" in it })
        assertTrue(texts.any { "src/api/CLAUDE.md" in it })
    }

    @Test
    fun `a second compaction does not drop a path that was attached again after the first`() {
        val x = nested("/workspace/src/CLAUDE.md")
        val s = foldOf(
            *conversation("a1", "a2", "a3").toTypedArray(),
            MemoryAttached(MessageId("a1"), listOf(x)),
            *compact(through = "a1", id = "k1").toTypedArray(),
            // 压缩后再碰到同目录的文件：重新加载，这回挂在 a3 上
            MemoryAttached(MessageId("a3"), listOf(x)),
            *compact(through = "a2", id = "k2").toTypedArray(),
        )
        assertEquals("a3 还在视图里，它上面的记忆不能被第二次压缩连带移出", setOf(x.path), s.memoryPathsInContext)
    }

    @Test
    fun `a refreshed snapshot replaces the snapshot paths but keeps the attached ones`() {
        val old = LoadedMemoryFile(GuestPath("/workspace/CLAUDE.md"), MemorySource.Project, "旧")
        val fresh = LoadedMemoryFile(GuestPath("/workspace/AGENTS.md"), MemorySource.Project, "新")
        val x = nested("/workspace/src/CLAUDE.md")
        val s = foldOf(
            MemoryLoaded(listOf(old)),
            *conversation("a1").toTypedArray(),
            MemoryAttached(MessageId("a1"), listOf(x)),
            MemoryLoaded(listOf(fresh)),
        )
        assertEquals(setOf(fresh.path, x.path), s.memoryPathsInContext)
    }

    @Test
    fun `attached memory renders after the tool results and after hook context`() {
        val a = nested("/workspace/src/api/CLAUDE.md", "Use the typed client.")
        val s = foldOf(
            *conversation("a1").toTypedArray(),
            HookContextAdded(HookContextTarget.ToolResults(MessageId("a1")), "PostToolUse", "hook says hi"),
            MemoryAttached(MessageId("a1"), listOf(a)),
        )
        val results = s.modelMessages().last().parts
        assertTrue(results[0] is ToolResultPart)
        assertEquals("<system-reminder>\nhook says hi\n</system-reminder>", (results[1] as TextPart).text)
        val reminder = (results[2] as TextPart).text
        assertTrue(reminder, reminder.startsWith("<system-reminder>\nContents of /workspace/src/api/CLAUDE.md ("))
        assertTrue(reminder, reminder.endsWith("):\n\nUse the typed client.\n</system-reminder>"))
        assertEquals(3, results.size)
        // 先写记忆、后写 hook 上下文，顺序也不变：渲染顺序是固定的，不取决于落盘顺序
        val reversed = foldOf(
            *conversation("a1").toTypedArray(),
            MemoryAttached(MessageId("a1"), listOf(a)),
            HookContextAdded(HookContextTarget.ToolResults(MessageId("a1")), "PostToolUse", "hook says hi"),
        )
        assertEquals(results, reversed.modelMessages().last().parts)
    }

    @Test
    fun `forks inherit attached memory except the one on the delegating message`() {
        val a = nested("/workspace/src/CLAUDE.md")
        val b = nested("/workspace/src/api/CLAUDE.md")
        val parent = foldOf(
            *conversation("a1", "a2").toTypedArray(),
            MemoryAttached(MessageId("a1"), listOf(a)),
            MemoryAttached(MessageId("a2"), listOf(b)),
        )
        val inherited = forkInheritance(parent, CallId("call-a2")).filterIsInstance<MemoryAttached>()
        assertEquals(listOf(MemoryAttached(MessageId("a1"), listOf(a))), inherited)

        // 重放进分身的日志：继承来的消息上有同样的附件，已加载路径随折叠得出
        val seed = forkSeed(parent, CallId("call-a2"), "查一下")
        val childOrigin = dev.min.agent.spi.SubagentOrigin(
            parent = SessionId("s1"), root = SessionId("s1"), parentCall = CallId("call-a2"), agentType = "fork",
            fork = true, depth = 1, promptBasis = dev.min.agent.spi.PromptBasis("test", "/workspace", "1970-01-01"), cacheKey = "s1",
        )
        val child = foldOf(childOrigin, seed, *forkInheritance(parent, CallId("call-a2")).toTypedArray())
        assertEquals(mapOf(MessageId("a1") to listOf(a)), child.memoryAttachments)
        assertEquals(setOf(a.path), child.memoryPathsInContext)
        assertEquals(
            "继承范围内（到委派那条为止，不含它）的对话逐条相同",
            parent.modelMessages().dropLast(1),
            child.modelMessages().dropLast(1),
        )
        assertTrue(Role.User == child.modelMessages().last().role)
    }
}
