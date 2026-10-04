package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.CheckpointTaken
import dev.min.agent.spi.ConversationRewound
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.FileEdit
import dev.min.agent.spi.FilesRestored
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PathChangeKind
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolResult
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSnapshots
import dev.min.agent.testkit.FakeTool
import dev.min.agent.testkit.READ_ONLY
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.WRITES
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import dev.min.agent.tools.EditTool
import dev.min.agent.tools.ReadTool
import dev.min.agent.tools.TaskCreateTool
import dev.min.agent.tools.WriteTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 检查点与 rewind 在会话里的样子（docs/agent/detail/检查点与rewind设计.md）：真日志、真的编辑工具、内存文件系统与内存快照仓库。
 * 「Shell」是个直接改文件系统的写工具，替 Bash 演「编辑工具以外的改动」。
 */
class CheckpointTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = FakeFs()
    private val snapshots = FakeSnapshots(fs)
    private val env = mutableMapOf<String, String>()
    private val header = LogHeader(LOG_FORMAT_VERSION, SessionId("s1"), createdAtMs = 0)
    private var counter = 0

    /** 替 Bash 演改文件：content 为 null 时删掉 */
    private val shell = FakeTool("Shell", WRITES) { input ->
        val path = (input["path"] as JsonPrimitive).content
        val content = (input["content"] as? JsonPrimitive)?.content
        if (content == null) fs.delete(path) else fs.put(path, content)
        ToolResult(listOf(TextPart("ok")))
    }

    private val tools: List<Tool<*>> = listOf(ReadTool, WriteTool, EditTool, TaskCreateTool, shell)

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun deps(model: ModelClient, tools: List<Tool<*>>) = testDeps(model, tools).let { base ->
        KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = tools,
            host = HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts, snapshots = snapshots),
            platform = base.platform, zone = base.zone, retry = base.retry,
            env = { env[it] },
        )
    }

    private suspend fun session(model: ModelClient, extra: List<Tool<*>> = emptyList()) = SessionRuntime.create(
        store, header, SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions),
        deps(model, tools + extra), scope,
    )

    private suspend fun events(): List<SessionEvent> = store.open(SessionId("s1"))!!.read().toList().map { it.event }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    private suspend fun SessionRuntime.say(text: String) {
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        awaitIdle()
    }

    private fun write(id: String, path: String, content: String) = ToolCallPart(CallId(id), "Write", args("file_path" to path, "content" to content))
    private fun read(id: String, path: String) = ToolCallPart(CallId(id), "Read", args("file_path" to path))
    private fun edit(id: String, path: String, old: String, new: String) =
        ToolCallPart(CallId(id), "Edit", args("file_path" to path, "old_string" to old, "new_string" to new))
    private fun sh(id: String, path: String, content: String?) = ToolCallPart(CallId(id), "Shell", args("path" to path, "content" to content))

    private fun tool(vararg calls: ToolCallPart) = reply(*calls, stop = StopReason.ToolUse)
    private fun done(text: String = "done") = reply(TextPart(text))

    private fun ModelRequest.plainText(): String = messages.joinToString("\n") { m ->
        m.parts.joinToString("\n") { p ->
            when (p) {
                is TextPart -> p.text
                is ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("") { it.text }
                else -> ""
            }
        }
    }

    private fun SessionRuntime.point(index: Int) = state.value.rewindPoints[index].messageId

    @Test
    fun `each user turn takes a checkpoint before any write tool runs`() = run {
        fs.put("/workspace/a.txt", "one\n")
        // 快照慢一点：没有工具闸的话，写工具会抢在它前面
        snapshots.beforeSnapshot = { delay(150) }
        val rt = session(ScriptedModel(tool(write("c1", "/workspace/b.txt", "new")), done()))
        rt.say("建个文件")

        val ev = events()
        val taken = ev.filterIsInstance<CheckpointTaken>().single()
        assertTrue("检查点在写工具开始之前落盘", ev.indexOf(taken) < ev.indexOfFirst { it is ToolEffectPending })
        val manifest = assertNotNullAndGet(taken.manifest)
        val changes = snapshots.store(GuestPath("/workspace")).diff(manifest, null)
        assertEquals("快照里是写之前的工作区", listOf("/workspace/b.txt" to PathChangeKind.Added), changes.map { it.path.value to it.kind })
        assertEquals(rt.point(0), taken.messageId)
        assertLogIsTruth(rt)
    }

    @Test
    fun `read-only tools do not wait for the snapshot`() = run {
        val ran = CompletableDeferred<Unit>()
        val probe = FakeTool("Probe", READ_ONLY) { ran.complete(Unit); ToolResult(listOf(TextPart("ok"))) }
        // 快照要等只读工具先跑：工具闸要是连只读工具也拦，这里就互相等到超时
        snapshots.beforeSnapshot = { withTimeout(5_000) { ran.await() } }
        val rt = session(ScriptedModel(tool(ToolCallPart(CallId("p1"), "Probe", args())), done()), extra = listOf(probe))
        rt.say("看看")
        assertNotNull("快照照常拍成", events().filterIsInstance<CheckpointTaken>().single().manifest)
        assertLogIsTruth(rt)
    }

    @Test
    fun `tools that delegate work wait for the snapshot like write tools`() = run {
        // 委派类工具自己只读，派出去的子 agent 却会写：它也得等快照
        val delegate = FakeTool("Delegate", READ_ONLY.copy(delegates = true)) { ToolResult(listOf(TextPart("ok"))) }
        snapshots.beforeSnapshot = { delay(150) }
        val rt = session(ScriptedModel(tool(ToolCallPart(CallId("d1"), "Delegate", args())), done()), extra = listOf(delegate))
        rt.say("派个活")
        val ev = events()
        assertTrue(ev.indexOfFirst { it is CheckpointTaken } < ev.indexOfFirst { it is ToolEffectPending })
        assertLogIsTruth(rt)
    }

    @Test
    fun `a whole-tree restore is refused when the pre-restore snapshot cannot be taken`() = run {
        val rt = twoTurns()
        snapshots.unavailable = "磁盘满了"
        val outcome = rt.rewind(rt.point(1), RewindScope.Code)
        assertTrue(outcome is RewindOutcome.Refused)
        assertEquals("什么都没动", "changed", fs.text("/workspace/b.txt"))
        assertTrue(events().none { it is FilesRestored })
        assertLogIsTruth(rt)
    }

    @Test
    fun `editing tools store the exact bytes before and after the write`() = run {
        val original = "\uFEFFone\r\ntwo\r\n"
        fs.put("/workspace/a.txt", original)
        val rt = session(
            ScriptedModel(
                tool(read("r1", "/workspace/a.txt")),
                tool(edit("e1", "/workspace/a.txt", "two", "TWO")),
                tool(write("w1", "/workspace/c.txt", "hi")),
                done(),
            ),
        )
        rt.say("改")
        val edits = events().filterIsInstance<ToolOutcome>().flatMap { it.result.fileEdits }
        val e = edits.single { it.path.value == "/workspace/a.txt" }
        assertEquals("原文原样（BOM、CRLF 都在）", original, snapshots.content(assertNotNullAndGet(e.before)))
        assertEquals(fs.text("/workspace/a.txt"), snapshots.content(assertNotNullAndGet(e.after)))
        val w = edits.single { it.path.value == "/workspace/c.txt" }
        assertTrue(w.created)
        assertNull("新建的文件没有原文", w.before)
        assertEquals("hi", snapshots.content(assertNotNullAndGet(w.after)))
        assertLogIsTruth(rt)
    }

    /** 第一轮新建 a.txt；第二轮改 a.txt（编辑工具）、改 b.txt 与新建 c.txt（Shell） */
    private suspend fun twoTurns(): SessionRuntime {
        fs.put("/workspace/b.txt", "orig")
        val rt = session(
            ScriptedModel(
                tool(write("w1", "/workspace/a.txt", "A1")), done("one"),
                tool(read("r1", "/workspace/a.txt")),
                tool(edit("e1", "/workspace/a.txt", "A1", "A2"), sh("s1", "/workspace/b.txt", "changed"), sh("s2", "/workspace/c.txt", "new")),
                done("two"),
                done("three"),
            ),
        )
        rt.say("第一轮")
        rt.say("第二轮")
        return rt
    }

    @Test
    fun `rewinding code restores edits and shell changes alike, and tells the model`() = run {
        val rt = twoTurns()
        val historyBefore = rt.state.value.history

        val preview = assertNotNullAndGet(rt.previewRewind(rt.point(1)))
        assertTrue(preview.canRestoreCode)
        assertEquals(setOf("/workspace/a.txt", "/workspace/b.txt", "/workspace/c.txt"), preview.changes.map { it.path.value }.toSet())

        val outcome = rt.rewind(rt.point(1), RewindScope.Code) as RewindOutcome.Done
        assertEquals("A1", fs.text("/workspace/a.txt"))
        assertEquals("orig", fs.text("/workspace/b.txt"))
        assertNull(fs.text("/workspace/c.txt"))
        val restored = assertNotNullAndGet(outcome.restored)
        assertEquals(listOf("/workspace/c.txt"), restored.deleted.map { it.value })
        assertNotNull("先拍了回滚前快照", restored.preRestore)
        assertNull(outcome.rewound)
        assertEquals("只恢复代码不动对话", historyBefore, rt.state.value.history)

        rt.say("第三轮")
        val model = rt.deps.models.resolve(TEST_MODEL).client as ScriptedModel
        val last = model.requests.last().plainText()
        assertTrue(last, last.contains("恢复到了之前某一轮开始时的状态") && last.contains("/workspace/c.txt"))
        assertLogIsTruth(rt)
    }

    @Test
    fun `undoing a restore brings back what was there before it`() = run {
        val rt = twoTurns()
        val restored = (rt.rewind(rt.point(1), RewindScope.Code) as RewindOutcome.Done).restored!!
        val undo = rt.undoRestore(restored.restoreId) as RewindOutcome.Done
        assertEquals(restored.restoreId, undo.restored!!.undoOf)
        assertEquals("A2", fs.text("/workspace/a.txt"))
        assertEquals("changed", fs.text("/workspace/b.txt"))
        assertEquals("new", fs.text("/workspace/c.txt"))
        assertLogIsTruth(rt)
    }

    @Test
    fun `rewinding the conversation keeps settings and usage, reverts tasks, and keeps the cache prefix`() = run {
        val model = ScriptedModel(
            done("one"),
            tool(ToolCallPart(CallId("t1"), "TaskCreate", args("subject" to "做事", "description" to "细节"))), done("two"),
            done("three"),
        )
        val rt = session(model)
        rt.say("第一轮")
        val afterFirst = rt.state.value.history
        rt.say("第二轮")
        val secondRequest = model.requests[1]
        assertEquals(1, rt.state.value.tasks.size)
        rt.changeSettings(mode = PermissionMode.AcceptEdits)
        val usage = rt.state.value.usage

        val outcome = rt.rewind(rt.point(1), RewindScope.Conversation) as RewindOutcome.Done
        assertEquals(listOf<Part>(TextPart("第二轮")), outcome.prompt)
        val s = rt.state.value
        assertEquals(afterFirst, s.history)
        assertTrue("任务清单回到那时候", s.tasks.isEmpty())
        assertEquals("权限模式是会话范围的，保持现状", PermissionMode.AcceptEdits, s.settings.mode)
        assertEquals("用量不回退", usage, s.usage)
        assertEquals("回退掉的对话留给界面", 3, s.rewound.single().entries.size)
        assertEquals(1, s.rewindPoints.size)

        rt.say("换个说法")
        val next = model.requests.last()
        assertEquals("工具表不变", secondRequest.tools, next.tools)
        assertEquals("前缀是回退前请求的前缀", secondRequest.messages.dropLast(1), next.messages.dropLast(1))
        assertLogIsTruth(rt)
    }

    @Test
    fun `rewinding to the first message empties the conversation`() = run {
        val rt = session(ScriptedModel(done("one"), done("again")))
        rt.say("第一轮")
        rt.rewind(rt.point(0), RewindScope.Conversation) as RewindOutcome.Done
        assertTrue(rt.state.value.history.isEmpty())
        rt.say("重来")
        assertEquals(2, rt.state.value.history.size)
        assertLogIsTruth(rt)
    }

    @Test
    fun `rewinding both restores code and conversation`() = run {
        val rt = twoTurns()
        val point = rt.point(1)
        val outcome = rt.rewind(point, RewindScope.Both) as RewindOutcome.Done
        assertNotNull(outcome.restored)
        assertEquals(point, outcome.rewound!!.toMessageId)
        assertEquals("orig", fs.text("/workspace/b.txt"))
        assertEquals(1, rt.state.value.rewindPoints.size)
        // 对话也回去了：代码恢复的说明不该留给模型（那几轮它已经看不见了）
        assertTrue(rt.state.value.pendingHookContexts.isEmpty())
        assertLogIsTruth(rt)
    }

    @Test
    fun `without a whole-tree snapshot, edit records still restore what the editing tools changed`() = run {
        snapshots.unavailable = "工作区太大"
        val rt = twoTurns()
        assertTrue(events().filterIsInstance<CheckpointTaken>().all { it.manifest == null && it.reason == "工作区太大" })
        val outcome = rt.rewind(rt.point(1), RewindScope.Code) as RewindOutcome.Done
        assertEquals("A1", fs.text("/workspace/a.txt"))
        assertEquals("Shell 的改动没有快照就撤不回", "changed", fs.text("/workspace/b.txt"))
        assertNull(outcome.restored!!.preRestore)
        assertLogIsTruth(rt)
    }

    @Test
    fun `the environment switch turns checkpoints off entirely`() = run {
        env["CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING"] = "1"
        val rt = twoTurns()
        assertTrue(events().none { it is CheckpointTaken })
        assertTrue(snapshots.snapshots.isEmpty())
        assertTrue(events().filterIsInstance<ToolOutcome>().flatMap { it.result.fileEdits }.all { it.before == null && it.after == null })
        val preview = assertNotNullAndGet(rt.previewRewind(rt.point(1)))
        assertFalse("只能回退对话", preview.canRestoreCode)
        assertTrue(rt.rewind(rt.point(1), RewindScope.Code) is RewindOutcome.Refused)
        assertTrue(rt.rewind(rt.point(1), RewindScope.Conversation) is RewindOutcome.Done)
        assertLogIsTruth(rt)
    }

    @Test
    fun `rewind is refused while a turn is running, and a message sent mid-turn is not a rewind point`() = run {
        val hold = CompletableDeferred<Unit>()
        val model = ScriptedModel(
            done("one"),
            { request -> flow { hold.await(); emitAll(done("two")(request)) } },
            done("steer"),
        )
        val rt = session(model)
        rt.say("第一轮")
        rt.send(ClientMsgId("u-second"), listOf(TextPart("第二轮")))
        while (rt.state.value.turn == null || rt.state.value.rewindPoints.size < 2) delay(10)
        assertTrue(rt.rewind(rt.point(0), RewindScope.Conversation) is RewindOutcome.Refused)
        rt.send(ClientMsgId("u-steer"), listOf(TextPart("插一句")), Delivery.Steer)
        hold.complete(Unit)
        rt.awaitIdle()
        assertEquals("插话并进当前回合，不记回退点", 2, rt.state.value.rewindPoints.size)
        assertLogIsTruth(rt)
    }

    @Test
    fun `undoing calls restores each file to before the earliest of them`() = run {
        val rt = session(
            ScriptedModel(
                tool(write("w1", "/workspace/a.txt", "A1")),
                tool(read("r1", "/workspace/a.txt")),
                tool(edit("e1", "/workspace/a.txt", "A1", "A2")),
                done(),
            ),
        )
        rt.say("建了再改")
        val outcome = rt.restoreCalls(listOf(CallId("e1"))) as RewindOutcome.Done
        assertEquals("A1", fs.text("/workspace/a.txt"))
        assertEquals(listOf(CallId("e1")), outcome.restored!!.calls)
        rt.restoreCalls(listOf(CallId("w1"), CallId("e1"))) as RewindOutcome.Done
        assertNull("新建的文件撤到最早就是删掉", fs.text("/workspace/a.txt"))
        assertLogIsTruth(rt)
    }

    @Test
    fun `the store is told which snapshots and contents the session still needs`() = run {
        val rt = twoTurns()
        val (manifests, contents) = snapshots.retained.getValue(rt.sessionId)
        val expectedManifests = events().filterIsInstance<CheckpointTaken>().mapNotNull { it.manifest }.toSet()
        assertEquals(expectedManifests, manifests)
        val edits: List<FileEdit> = events().filterIsInstance<ToolOutcome>().flatMap { it.result.fileEdits }
        assertTrue(edits.flatMap { listOfNotNull(it.before, it.after) }.all { it in contents })
        assertLogIsTruth(rt)
    }

    @Test
    fun `rewind events survive reopening the session`() = run {
        val rt = twoTurns()
        rt.rewind(rt.point(1), RewindScope.Both) as RewindOutcome.Done
        val reopened = SessionRuntime.open(store, rt.sessionId, rt.deps, scope)!!
        assertEquals(rt.state.value, reopened.state.value)
        assertTrue(events().any { it is ConversationRewound } && events().any { it is FilesRestored })
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
