package dev.min.code.core.engine.codex

import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.engine.AgentSessionRegistry
import dev.min.code.core.engine.AttachResult
import dev.min.code.core.engine.CONTINUE_PROMPT
import dev.min.code.core.engine.CloseReason
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.EngineAvailability
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.ResumeMode
import dev.min.code.core.engine.ResumeReason
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.StartOptions
import dev.min.code.core.engine.UserInput
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit

/**
 * B9：Codex 作为统一注册表里的第三个引擎，从建会话到删会话走一遍真的 [CodexEngine] + [AgentSessionRegistry]
 * （app-server 是剧本进程）：在线摘要（前台服务 / 通知读它）、停止只停本轮、▶ 续跑、重开停在半路的旧会话后发话 / ▶ 先接回进程、关会话。
 */
class CodexEngineRegistryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class Script(private vararg val triggers: Pair<String, List<String>>) : Process() {
        private val output = ByteArrayOutputStream()
        private val inputWriter = PipedOutputStream()
        private val input = PipedInputStream(inputWriter, 1 shl 16)
        private val fired = mutableSetOf<Int>()

        @Volatile
        var destroyed = false
            private set

        init {
            Thread {
                while (!destroyed) {
                    val written = lines()
                    triggers.forEachIndexed { index, (pattern, lines) ->
                        if (index !in fired && written.any { it.contains(pattern) }) {
                            fired += index
                            lines.forEach { emit(it) }
                        }
                    }
                    Thread.sleep(2)
                }
            }.apply { isDaemon = true }.start()
        }

        fun queue(vararg lines: String) = lines.forEach { emit(it) }
        private fun emit(line: String) {
            kotlin.runCatching { synchronized(inputWriter) { inputWriter.write((line + "\n").toByteArray()); inputWriter.flush() } }
        }

        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { while (!destroyed) Thread.sleep(5); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int = if (destroyed) 0 else throw IllegalThreadStateException()
        override fun destroy() { destroyed = true; kotlin.runCatching { inputWriter.close() }; kotlin.runCatching { input.close() } }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive(): Boolean = !destroyed

        @Synchronized
        fun lines(): List<String> = output.toString(Charsets.UTF_8.name()).lineSequence().filter { it.isNotBlank() }.toList()
        fun turnStarts() = lines().count { it.contains(""""method":"turn/start"""") }
        fun lastTurnText(): String =
            (Json.parseToJsonElement(lines().last { it.contains(""""method":"turn/start"""") }) as JsonObject)["params"]!!
                .jsonObject["input"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    }

    private class Rig(
        val engine: CodexEngine,
        val registry: AgentSessionRegistry,
        val manager: CodexAppServerManager,
        val scope: CoroutineScope,
        val process: () -> Script,
    ) : AutoCloseable {
        override fun close() { scope.cancel(); manager.close() }
    }

    private var current: Script? = null

    private fun rig(codexHome: File, first: Script): Rig {
        current = first
        val manager = CodexAppServerManager { current!! }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = CodexEngine(
            manager = manager, scope = scope,
            availability = MutableStateFlow(EngineAvailability.Ready),
            prepare = {}, codexHome = { codexHome },
        )
        return Rig(engine, AgentSessionRegistry(listOf(engine), scope), manager, scope) { current!! }
    }

    private suspend fun await(what: String, condition: () -> Boolean) {
        repeat(600) { if (condition()) return; delay(5) }
        error("等不到：$what")
    }

    private fun handshake(thread: String, threadId: String, firstId: Int = 1) = arrayOf(
        "\"method\":\"initialize\"" to listOf("""{"id":"$firstId","result":{}}"""),
        thread to listOf("""{"id":"${firstId + 1}","result":{"threadId":"$threadId"}}"""),
    )

    private val busyTurn = """{"id":"3","result":{"turn":{"id":"turn-1"}}}"""
    private fun completed(status: String) = """{"method":"turn/completed","params":{"turn":{"id":"turn-1","status":"$status"}}}"""

    @Test
    fun `create, run, stop only the turn, continue, then close - the live summary follows every step`() = runBlocking<Unit> {
        val script = Script(
            *handshake("thread/start", "t-1"),
            "turn/start" to listOf(busyTurn),
            // 第 5 个请求 = 打断（4 被用户消息的本地 id 占了）
            "turn/interrupt" to listOf("""{"id":"5","result":{}}""", completed("interrupted")),
        )
        rig(temp.newFolder("home"), script).use { r ->
            val created = r.registry.create(EngineKind.Codex, StartOptions(cwd = "/workspace"))
            val session = (created as AttachResult.Ok).session
            assertEquals(SessionRef(EngineKind.Codex, "t-1"), session.ref)
            assertEquals(session.ref, r.registry.activeRef.value)

            await("在线摘要里有它，保活") { r.registry.live.value.any { it.ref == session.ref && it.keepAlive } }
            assertTrue(r.registry.anyKeepAlive.value)

            assertEquals(CommandResult.Accepted, session.submit(SessionCommand.Send("c1", UserInput("第一问"))))
            await("回合在跑") { r.registry.live.value.single().busy }

            assertEquals(CommandResult.Accepted, session.submit(SessionCommand.Interrupt("c2")))
            await("回合收场") { !r.manager.state.value.busy }
            assertFalse("停止只停本轮：进程活着", script.destroyed)
            assertTrue(r.registry.live.value.single().keepAlive)
            await("▶ 出现在摘要里（通知的「继续」按钮读它）") { r.registry.live.value.single().resume != null }

            val offer = session.state.value.resume!!
            assertEquals(ResumeReason.UserStopped, offer.reason)
            assertEquals(ResumeMode.Prompted, offer.mode)
            assertEquals(CommandResult.Accepted, session.submit(SessionCommand.Resume("c3", offer.offerId)))
            await("续跑提示写出") { script.turnStarts() == 2 }
            assertEquals(CONTINUE_PROMPT, script.lastTurnText())

            r.registry.close(session.ref, CloseReason.UserEnded)
            await("进程停了") { script.destroyed }
            await("摘要里 keepAlive 落下") { r.registry.live.value.none { it.keepAlive } }
            assertEquals(null, r.registry.activeRef.value)
        }
    }

    @Test
    fun `an old session left mid-turn reopens read-only with a play key, and the play key relaunches then continues`() = runBlocking<Unit> {
        val home = temp.newFolder("home2")
        val dir = File(home, "sessions/2026/10/11").apply { mkdirs() }
        val id = "0192a000-0000-7000-8000-000000000001"
        File(dir, "rollout-2026-10-11T10-00-00-$id.jsonl").writeText(
            listOf(
                """{"type":"session_meta","payload":{"session_id":"$id","id":"$id","cwd":"/workspace"}}""",
                """{"type":"event_msg","payload":{"type":"task_started","turn_id":"a"}}""",
                """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u1","content":[{"type":"text","text":"跑到一半被杀的活"}]}}}""",
                // 探针里「工具调用中被杀」的真实尾巴：助手说完「我来跑一下」就发了工具调用，没有收尾行
                """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"AgentMessage","id":"a1","text":"I will run a command."}}}""",
            ).joinToString("\n"),
        )
        // 只读回放不起进程；▶ 接回时的那个进程第一个请求是 initialize（1）、thread/resume（2）、然后 turn/start（3）
        val reopened = Script(*handshake("thread/resume", id), "turn/start" to listOf(busyTurn))
        rig(home, reopened).use { r ->
            val ref = SessionRef(EngineKind.Codex, id)
            val opened = r.registry.open(ref)
            val session = (opened as AttachResult.Ok).session
            val state = session.state.value
            assertEquals("没有进程：只读回放", SessionStatus.Idle, state.status)
            assertTrue("页面要把它显示出来、输入框要能收字", state.accepting)
            assertTrue(state.items.any { it is ChatItem.UserText && it.text == "跑到一半被杀的活" })
            val offer = state.resume!!
            assertEquals(ResumeMode.RelaunchThenPrompted, offer.mode)
            assertEquals(ResumeReason.ProcessDeath(null), offer.reason)
            assertEquals("还没有进程，不报在线", true, r.registry.live.value.isEmpty())

            assertEquals(CommandResult.Accepted, session.submit(SessionCommand.Resume("r1", offer.offerId)))
            await("续跑提示写进新进程") { reopened.turnStarts() == 1 }
            assertEquals(CONTINUE_PROMPT, reopened.lastTurnText())
            assertTrue("先 thread/resume 接回同一条线程", reopened.lines().any { it.contains("thread/resume") && it.contains(id) })
            assertEquals(SessionStatus.Running, r.manager.state.value.status)
            // 旧历史还在屏幕上
            assertTrue(r.manager.state.value.items.any { it is ChatItem.UserText && it.text == "跑到一半被杀的活" })
        }
    }

    @Test
    fun `sending a message to a read-only replay relaunches the app-server first, there is no separate resume key`() = runBlocking<Unit> {
        val home = temp.newFolder("home3")
        val dir = File(home, "sessions/2026/10/11").apply { mkdirs() }
        val id = "0192a000-0000-7000-8000-000000000002"
        File(dir, "rollout-2026-10-11T11-00-00-$id.jsonl").writeText(
            listOf(
                """{"type":"session_meta","payload":{"session_id":"$id","id":"$id","cwd":"/workspace"}}""",
                """{"type":"event_msg","payload":{"type":"task_started","turn_id":"a"}}""",
                """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u1","content":[{"type":"text","text":"旧问题"}]}}}""",
                """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"AgentMessage","id":"a1","text":"旧答案"}}}""",
                """{"type":"event_msg","payload":{"type":"task_complete","turn_id":"a"}}""",
            ).joinToString("\n"),
        )
        val script = Script(*handshake("thread/resume", id), "turn/start" to listOf(busyTurn))
        rig(home, script).use { r ->
            val session = (r.registry.open(SessionRef(EngineKind.Codex, id)) as AttachResult.Ok).session
            assertEquals("收尾了的旧会话没有 ▶", null, session.state.value.resume)
            assertEquals(CommandResult.Accepted, session.submit(SessionCommand.Send("s1", UserInput("接着聊"))))
            await("turn/start 写出") { script.turnStarts() == 1 }
            assertEquals("接着聊", script.lastTurnText())
            assertTrue(script.lines().indexOfFirst { it.contains("thread/resume") } < script.lines().indexOfFirst { it.contains("turn/start") })
        }
    }

    @Test
    fun `the catalog lists rollouts, rename is app-side metadata, delete takes the file with it`() = runBlocking<Unit> {
        val home = temp.newFolder("home4")
        val dir = File(home, "sessions/2026/10/11").apply { mkdirs() }
        val id = "0192a000-0000-7000-8000-000000000003"
        val file = File(dir, "rollout-2026-10-11T12-00-00-$id.jsonl")
        file.writeText(
            listOf(
                """{"type":"session_meta","payload":{"session_id":"$id","id":"$id","cwd":"/workspace/app"}}""",
                """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u1","content":[{"type":"text","text":"列表里的标题"}]}}}""",
            ).joinToString("\n"),
        )
        rig(home, Script()).use { r ->
            val summary = r.engine.catalog.list().single()
            assertEquals(SessionRef(EngineKind.Codex, id), summary.ref)
            assertEquals("列表里的标题", summary.title)
            assertEquals("/workspace/app", summary.cwd)
            assertTrue("有第一句话就至少说过一句：开始面板的「继续最近的会话」据此认不是空会话", summary.messageCount > 0)

            assertEquals("没有元数据盘时改名不成立", false, r.engine.renameSession(id, "新名字"))
            assertEquals(dev.min.code.core.engine.DeleteResult.Deleted, r.engine.catalog.delete(id))
            assertFalse(file.exists())
            assertTrue(r.engine.catalog.list().isEmpty())
        }
    }

    @Test
    fun `closing a session and opening the same one again gives a fresh live session, not the closed wrapper`() = runBlocking<Unit> {
        val home = temp.newFolder("home5")
        val dir = File(home, "sessions/2026/10/11").apply { mkdirs() }
        val id = "0192a000-0000-7000-8000-000000000005"
        File(dir, "rollout-2026-10-11T13-00-00-$id.jsonl").writeText(
            listOf(
                """{"type":"session_meta","payload":{"session_id":"$id","id":"$id","cwd":"/workspace"}}""",
                """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u1","content":[{"type":"text","text":"问"}]}}}""",
                """{"type":"event_msg","payload":{"type":"task_complete","turn_id":"a"}}""",
            ).joinToString("\n"),
        )
        rig(home, Script()).use { r ->
            val ref = SessionRef(EngineKind.Codex, id)
            val first = (r.registry.open(ref) as AttachResult.Ok).session
            r.registry.close(ref, CloseReason.UserEnded)

            val second = (r.registry.open(ref) as AttachResult.Ok).session
            assertFalse("关掉的包装的状态流已经停了，不能再交出去", first === second)
            assertEquals(SessionStatus.Idle, second.state.value.status)
            assertEquals(ref, r.registry.activeRef.value)
        }
    }

    @Test
    fun `two messages sent at once to a read-only replay relaunch once - the second waits for it and queues`() = runBlocking<Unit> {
        val home = temp.newFolder("home6")
        val dir = File(home, "sessions/2026/10/11").apply { mkdirs() }
        val id = "0192a000-0000-7000-8000-000000000006"
        File(dir, "rollout-2026-10-11T14-00-00-$id.jsonl").writeText(
            listOf(
                """{"type":"session_meta","payload":{"session_id":"$id","id":"$id","cwd":"/workspace"}}""",
                """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u1","content":[{"type":"text","text":"旧问题"}]}}}""",
                """{"type":"event_msg","payload":{"type":"task_complete","turn_id":"a"}}""",
            ).joinToString("\n"),
        )
        val script = Script(*handshake("thread/resume", id), "turn/start" to listOf(busyTurn))
        rig(home, script).use { r ->
            val session = (r.registry.open(SessionRef(EngineKind.Codex, id)) as AttachResult.Ok).session
            val a = this.async { session.submit(SessionCommand.Send("a", UserInput("第一句"))) }
            val b = this.async { session.submit(SessionCommand.Send("b", UserInput("第二句"))) }
            assertEquals(CommandResult.Accepted, a.await())
            assertEquals("第二句不能因为进程还在接回就被拒掉、话丢了", CommandResult.Accepted, b.await())
            assertEquals("只起了一个进程、只开了一轮；另一句排队", 1, script.turnStarts())
            assertEquals(1, script.lines().count { it.contains("thread/resume") })
            await("另一句进了队列") { r.manager.state.value.queued.size == 1 }
        }
    }

    @Test
    fun `renaming with an empty title clears the hand-made one, the list falls back to the first sentence`() = runBlocking<Unit> {
        val home = temp.newFolder("home7")
        val dir = File(home, "sessions/2026/10/11").apply { mkdirs() }
        val id = "0192a000-0000-7000-8000-000000000007"
        File(dir, "rollout-2026-10-11T15-00-00-$id.jsonl").writeText(
            listOf(
                """{"type":"session_meta","payload":{"session_id":"$id","id":"$id","cwd":"/workspace"}}""",
                """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u1","content":[{"type":"text","text":"第一句话"}]}}}""",
            ).joinToString("\n"),
        )
        val metaFile = temp.newFile("codex-meta.json").also { it.delete() }
        val meta = dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore(metaFile)
        val manager = CodexAppServerManager { Script() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = CodexEngine(
            manager = manager, scope = scope, availability = MutableStateFlow(EngineAvailability.Ready),
            prepare = {}, codexHome = { home }, metaStore = meta,
        )
        try {
            assertTrue(engine.renameSession(id, "  人手改的名字 "))
            assertEquals("人手改的名字", engine.catalog.list().single().title)
            assertTrue(engine.renameSession(id, "   "))
            assertEquals("空名字 = 清掉，回到第一句话", "第一句话", engine.catalog.list().single().title)
        } finally {
            scope.cancel()
            manager.close()
        }
    }

    @Test
    fun `a new session right after ending the running one is not refused - the stop is already visible in the state`() = runBlocking<Unit> {
        val first = Script(*handshake("thread/start", "t-a"))
        rig(temp.newFolder("home8"), first).use { r ->
            val a = (r.registry.create(EngineKind.Codex, StartOptions(cwd = "/workspace")) as AttachResult.Ok).session
            r.registry.close(a.ref, CloseReason.UserEnded)
            // 紧接着建下一条（抽屉的「新会话」/ `/new` 就是先结束再建）：新进程是另一个剧本
            current = Script(*handshake("thread/start", "t-b", firstId = 3))
            val second = r.registry.create(EngineKind.Codex, StartOptions(cwd = "/workspace"))
            assertTrue(second.toString(), second is AttachResult.Ok)
            assertEquals(SessionRef(EngineKind.Codex, "t-b"), (second as AttachResult.Ok).session.ref)
        }
    }
}
