package dev.min.code.core.codex

import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerManagerTest {

    @Test
    fun `handshake starts a thread and streams a turn`() = runBlocking {
        val process = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/start" to listOf("""{"id":"2","result":{"thread":{"id":"thread-1"}}}"""),
            "turn/start" to listOf(
                """{"id":"3","result":{"turn":{"id":"turn-1"}}}""",
                """{"method":"item/agentMessage/delta","params":{"itemId":"m1","delta":"hel"}}""",
                """{"method":"item/agentMessage/delta","params":{"itemId":"m1","delta":"lo"}}""",
                """{"method":"item/completed","params":{"item":{"type":"agentMessage","id":"m1","text":"hello"}}}""",
                """{"method":"turn/completed","params":{"turn":{"id":"turn-1","status":"completed"}}}""",
            ),
        )
        val manager = CodexAppServerManager { process }

        assertTrue(manager.start())
        await { manager.state.value.status == SessionStatus.Running }
        assertEquals("thread-1", manager.state.value.threadId)

        assertTrue(manager.sendTurn("hello"))
        await { !manager.state.value.busy && manager.state.value.turnId == "turn-1" }

        // 权威 item 到了之后，增量缓冲必须清空，否则界面上文字会翻倍
        assertEquals("", manager.state.value.streamingText)
        val texts = manager.state.value.items.filterIsInstance<ChatItem.AssistantText>()
        assertEquals(listOf("hello"), texts.map { it.text })

        val writes = process.writtenLines()
        assertTrue(writes[0].contains(""""method":"initialize""""))
        // 握手第二步漏了的话，后面每个请求都会被服务端拒掉
        assertEquals("""{"method":"initialized","params":{}}""", writes[1])
        assertTrue(writes[2].contains(""""method":"thread/start""""))
        assertTrue(writes[3].contains(""""method":"turn/start""""))
        manager.close()
    }

    /**
     * 核心回归：上一版的 State 只有一根 streamingText，每轮清空 ——
     * 发第二条消息时第一条的回答就没了。历史必须留着。
     */
    @Test
    fun `earlier turns stay in the transcript`() = runBlocking {
        val process = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/start" to listOf("""{"id":"2","result":{"threadId":"t"}}"""),
            "turn/start" to listOf(
                """{"method":"item/completed","params":{"item":{"type":"agentMessage","id":"a1","text":"第一答"}}}""",
                """{"method":"turn/completed","params":{"turn":{"id":"turn-1","status":"completed"}}}""",
            ),
        )
        val manager = CodexAppServerManager { process }
        manager.start()
        await { manager.state.value.status == SessionStatus.Running }

        manager.sendTurn("第一问")
        await { !manager.state.value.busy }

        process.queue(
            """{"method":"item/completed","params":{"item":{"type":"agentMessage","id":"a2","text":"第二答"}}}""",
            """{"method":"turn/completed","params":{"turn":{"id":"turn-2","status":"completed"}}}""",
        )
        manager.sendTurn("第二问")
        await { manager.state.value.items.count { it is ChatItem.AssistantText } == 2 }

        val items = manager.state.value.items
        assertEquals(
            listOf("第一问", "第二问"),
            items.filterIsInstance<ChatItem.UserText>().map { it.text },
        )
        assertEquals(
            listOf("第一答", "第二答"),
            items.filterIsInstance<ChatItem.AssistantText>().map { it.text },
        )
        manager.close()
    }

    /** item/completed 是权威最终态，要整条替换掉 item/started 那一版 */
    @Test
    fun `completed item replaces the started one instead of duplicating`() = runBlocking {
        val process = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/start" to listOf(
                """{"id":"2","result":{"threadId":"t"}}""",
                """{"method":"item/started","params":{"item":{"type":"commandExecution","id":"c1","command":"ls"}}}""",
                """{"method":"item/completed","params":{"item":{"type":"commandExecution","id":"c1","command":"ls","status":"completed","exitCode":0,"aggregatedOutput":"a\n"}}}""",
            ),
        )
        val manager = CodexAppServerManager { process }
        manager.start()
        await { manager.state.value.items.filterIsInstance<ChatItem.ToolCall>().size == 1 }
        await {
            manager.state.value.items.filterIsInstance<ChatItem.ToolCall>()
                .single().status == ChatItem.ToolCall.Status.Done
        }

        val tool = manager.state.value.items.filterIsInstance<ChatItem.ToolCall>().single()
        assertEquals("Bash", tool.name)
        assertEquals("a\n", tool.result)
        manager.close()
    }

    /**
     * 回归：命令的 stdout 曾经被拼进助手正文。它只能进那张 Bash 卡。
     */
    @Test
    fun `command output goes to the tool card and never into the prose`() = runBlocking {
        val process = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/start" to listOf(
                """{"id":"2","result":{"threadId":"t"}}""",
                """{"method":"item/started","params":{"item":{"type":"commandExecution","id":"c1","command":"ls"}}}""",
                """{"method":"item/commandExecution/outputDelta","params":{"itemId":"c1","delta":"total 12\n"}}""",
                """{"method":"item/commandExecution/outputDelta","params":{"itemId":"c1","delta":"a.kt\n"}}""",
            ),
        )
        val manager = CodexAppServerManager { process }
        manager.start()
        await {
            manager.state.value.items.filterIsInstance<ChatItem.ToolCall>()
                .singleOrNull()?.result == "total 12\na.kt\n"
        }

        assertEquals("", manager.state.value.streamingText)
        manager.close()
    }

    @Test
    fun `approval respects the decisions the server offered`() = runBlocking {
        val process = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/start" to listOf(
                """{"id":"2","result":{"threadId":"t"}}""",
                """{"id":"ap-1","method":"item/commandExecution/requestApproval","params":{"itemId":"c1","command":"git status","cwd":"/workspace","availableDecisions":["accept","decline"]}}""",
            ),
        )
        val manager = CodexAppServerManager { process }
        manager.start()
        await { manager.state.value.pendingApproval?.requestId == "ap-1" }

        // 服务端没给 acceptForSession，就不能往线上发 —— 发了只会让这一轮一直等
        assertFalse(manager.answerApproval(CodexDecision.ACCEPT_FOR_SESSION))
        assertTrue(manager.answerApproval(CodexDecision.ACCEPT))
        assertNull(manager.state.value.pendingApproval)
        // 已经答过的不能再答一次
        assertFalse(manager.answerApproval(CodexDecision.DECLINE))

        await { process.writtenLines().any { it.contains("ap-1") && it.contains("accept") } }
        manager.close()
    }

    /** stderr 是 app-server 唯一会说"我为什么起不来"的地方，不能只排干 */
    @Test
    fun `stderr lands in the transcript`() = runBlocking {
        val process = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/start" to listOf("""{"id":"2","result":{"threadId":"t"}}"""),
            stderr = "node: command not found\n",
        )
        val manager = CodexAppServerManager { process }
        manager.start()
        await { manager.state.value.items.any { it is ChatItem.ProcessOutput } }

        val output = manager.state.value.items.filterIsInstance<ChatItem.ProcessOutput>().first()
        assertEquals(listOf("node: command not found"), output.lines)
        manager.close()
    }

    @Test
    fun `resume reuses the existing thread and stop closes the session`() = runBlocking {
        val process = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/resume" to listOf("""{"id":"2","result":{}}"""),
        )
        val manager = CodexAppServerManager { process }

        assertTrue(manager.start(resumeThreadId = "existing"))
        await { manager.state.value.status == SessionStatus.Running }
        assertEquals("existing", manager.state.value.threadId)
        assertTrue(process.writtenLines().any { it.contains("thread/resume") && it.contains("existing") })

        manager.stop()
        assertEquals(SessionStatus.Closed, manager.state.value.status)
        manager.close()
    }

    /**
     * 回归：握手失败以前只改状态，进程原地不动。
     *
     * 真机上的样子是界面写着 failed，而 proot → bash → node → codex 四层一个不少地
     * 留在进程表里，端口和内存都占着，再点一次启动又叠一套上去。状态和事实必须一致。
     */
    @Test
    fun `a failed handshake destroys the process too`() = runBlocking {
        val process = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","error":{"message":"invalid configuration"}}"""),
        )
        val manager = CodexAppServerManager { process }

        manager.start()
        await { manager.state.value.status == SessionStatus.Failed }
        assertEquals("invalid configuration", manager.state.value.errorMessage)
        // 状态说失败，进程就得真的没了
        await { process.destroyed }
        manager.close()
    }

    /** thread/start 回错同样要收进程，不能只留一行红字 */
    @Test
    fun `a failed thread start destroys the process too`() = runBlocking {
        val process = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/start" to listOf("""{"id":"2","error":{"message":"no such cwd"}}"""),
        )
        val manager = CodexAppServerManager { process }

        manager.start()
        await { manager.state.value.status == SessionStatus.Failed }
        await { process.destroyed }
        manager.close()
    }

    /** 启动失败要落到 Failed 并带上原因，而不是无声无息停在 Starting */
    @Test
    fun `a launcher that throws fails the session with a reason`() {
        val manager = CodexAppServerManager { error("Codex CLI is not installed") }

        assertFalse(manager.start())
        assertEquals(SessionStatus.Failed, manager.state.value.status)
        assertEquals("Codex CLI is not installed", manager.state.value.errorMessage)
        manager.close()
    }

    /**
     * 前台服务就靠这一个布尔决定要不要保活。它多报一点，服务永不收尾、一直按着
     * 唤醒锁耗电；少报一点，切出去接个电话回来进程就没了。
     */
    @Test
    fun `isLive covers starting and running, and nothing else`() = runBlocking {
        val process = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/start" to listOf("""{"id":"2","result":{"threadId":"t"}}"""),
        )
        val manager = CodexAppServerManager { process }

        assertFalse(manager.isLive.value)

        manager.start()
        // Starting 也算活着：进程已经拉起来了，这时候被回收一样是断线
        await { manager.isLive.value }
        await { manager.state.value.status == SessionStatus.Running }
        assertTrue(manager.isLive.value)

        manager.stop()
        await { !manager.isLive.value }
        manager.close()
    }

    @Test
    fun `turn is refused before the thread exists`() {
        val manager = CodexAppServerManager { error("boom") }
        assertFalse(manager.sendTurn("hi"))
        manager.close()
    }

    private suspend fun await(condition: () -> Boolean) {
        repeat(400) {
            if (condition()) return
            delay(5)
        }
        error("等待的条件一直没满足")
    }

    /**
     * 一个假的 app-server：看见匹配 [triggers] 里某个片段的写入，就吐出对应的那几行。
     * 比"按顺序发"可靠 —— 握手是有来有回的，顺序发会在时序上碰运气。
     */
    private class ScriptedProcess(
        private vararg val triggers: Pair<String, List<String>>,
        stderr: String = "",
    ) : Process() {
        private val output = ByteArrayOutputStream()
        private val inputWriter = PipedOutputStream()
        private val input = PipedInputStream(inputWriter, 1 shl 16)
        private val errorBytes = ByteArrayInputStream(stderr.toByteArray())
        private val fired = mutableSetOf<Int>()

        @Volatile
        var destroyed = false
            private set

        init {
            Thread {
                while (!destroyed) {
                    val written = writtenLines()
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

        /** 测试中途追加一批服务端消息（第二轮对话用） */
        fun queue(vararg lines: String) = lines.forEach { emit(it) }

        private fun emit(line: String) {
            runCatching {
                synchronized(inputWriter) {
                    inputWriter.write((line + "\n").toByteArray())
                    inputWriter.flush()
                }
            }
        }

        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = errorBytes
        override fun waitFor(): Int {
            while (!destroyed) Thread.sleep(5)
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int = if (destroyed) 0 else throw IllegalThreadStateException()
        override fun destroy() {
            destroyed = true
            runCatching { inputWriter.close() }
            runCatching { input.close() }
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = !destroyed

        @Synchronized
        fun writtenLines(): List<String> = output.toString(Charsets.UTF_8.name())
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
    }
}
