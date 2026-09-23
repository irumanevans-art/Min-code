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

    /**
     * 回归：app-server 发的审批 id 是整数（Rust 侧 RequestId 是 untagged 枚举，
     * Integer(42) 与 String("42") 的相等和哈希都不同）。应答若回成字符串，
     * 服务端回调表查不中，只打一行 could not find callback，这一轮永远停在等审批。
     */
    @Test
    fun `approval answer echoes an integer request id verbatim`() = runBlocking {
        val process = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/start" to listOf(
                """{"id":"2","result":{"threadId":"t"}}""",
                """{"id":42,"method":"item/commandExecution/requestApproval","params":{"itemId":"c1","command":"git status","availableDecisions":["accept"]}}""",
            ),
        )
        val manager = CodexAppServerManager { process }
        manager.start()
        await { manager.state.value.pendingApproval != null }
        assertTrue(manager.answerApproval(CodexDecision.ACCEPT))

        // 写回线上的必须是 "id":42，不是 "id":"42"
        await { process.writtenLines().any { it.contains("\"id\":42") && it.contains("accept") } }
        manager.close()
    }

    /** 计划的 completed 到达时要把流式气泡清掉，不然计划文字和正式条目重复到整轮结束 */
    @Test
    fun `plan deltas clear when the plan item completes`() = runBlocking {
        val process = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/start" to listOf(
                """{"id":"2","result":{"threadId":"t"}}""",
                """{"method":"item/plan/delta","params":{"itemId":"p1","delta":"先"}}""",
                """{"method":"item/plan/delta","params":{"itemId":"p1","delta":"跑测试"}}""",
            ),
        )
        val manager = CodexAppServerManager { process }
        manager.start()
        // 增量先进气泡；completed 晚一步发，否则一次全吐出来连中间态都观察不到
        await { manager.state.value.streamingText == "先跑测试" }

        process.queue(
            """{"method":"item/completed","params":{"item":{"type":"plan","id":"p1","text":"先跑测试"}}}""",
        )
        await {
            manager.state.value.streamingText.isEmpty() &&
                manager.state.value.items.any { it is ChatItem.AssistantText && it.text == "先跑测试" }
        }
        manager.close()
    }

    /** clearIfIdle 只摘「已停且是对的那条 thread」的回放；跑着的和别的 thread 都不动 */
    @Test
    fun `clearIfIdle clears only the matching idle thread`() = runBlocking {
        val process = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/start" to listOf("""{"id":"2","result":{"threadId":"t"}}"""),
        )
        val manager = CodexAppServerManager { process }
        manager.start()
        await { manager.state.value.status == SessionStatus.Running }

        // 别的 thread 不清
        assertFalse(manager.clearIfIdle("other"))
        assertEquals(SessionStatus.Running, manager.state.value.status)

        // 自己的 thread 在跑也不清；停了才清
        assertFalse(manager.clearIfIdle("t"))
        manager.stop()
        await { manager.state.value.status == SessionStatus.Closed }
        assertTrue(manager.clearIfIdle("t"))
        assertEquals(SessionStatus.Idle, manager.state.value.status)
        assertTrue(manager.state.value.items.isEmpty())
        assertNull(manager.state.value.threadId)
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

    // -----------------------------------------------------------------------
    // 排队。codex 自己的语义（TUI 的 Tab、`codex queue --thread --message`）是
    // busy 时把输入压进 FIFO、跑完逐条放出来。app-server 一轮只能有一个 turn，
    // 所以队列只能攒在客户端 —— 下面钉的就是"什么时候攒、什么时候放、什么时候不放"。
    // -----------------------------------------------------------------------

    @Test
    fun `sending while busy queues instead of starting a second turn`() = runBlocking {
        val manager = runningManager(BUSY_TURN)
        assertTrue(manager.sendTurn("第一问"))
        await { manager.state.value.busy }
        // 写 stdin 是异步的（见 CodexAppServerManager.writeQueue）：busy 先亮，turn/start 随后才落到管道上
        await { turnStarts() == 1 }

        assertTrue(manager.sendTurn("排队一"))
        assertTrue(manager.sendTurn("排队二"))

        assertEquals(listOf("排队一", "排队二"), manager.state.value.queued)
        // busy 不该把输入框锁上：打字和发送是两件事，锁上连草稿都存不住
        assertTrue(manager.state.value.canSend)
        assertEquals(1, turnStarts())
        // 排着的还没被模型看见，不能先混进会话流
        assertEquals(listOf("第一问"), userTexts(manager))
        manager.close()
    }

    @Test
    fun `a completed turn releases the next queued message`() = runBlocking {
        val manager = runningManager(BUSY_TURN)
        manager.sendTurn("第一问")
        await { manager.state.value.busy }
        manager.sendTurn("排队一")

        scripted.queue(turnCompleted("completed"))

        await { manager.state.value.queued.isEmpty() }
        await { turnStarts() == 2 }
        // 这时候它才进会话流，顺序就是排队的顺序
        assertEquals(listOf("第一问", "排队一"), userTexts(manager))
        manager.close()
    }

    /**
     * 401 这类错误会一路复现：继续放等于拿同一个错误把整个队列一条条烧掉。
     * 留在队列里才是对的——界面上看得见、点得动、删得掉。
     */
    @Test
    fun `a failed turn keeps the queue instead of burning it`() = runBlocking {
        val manager = runningManager(BUSY_TURN)
        manager.sendTurn("第一问")
        await { manager.state.value.busy }
        manager.sendTurn("排队一")

        scripted.queue(turnCompleted("failed"))

        await { !manager.state.value.busy }
        assertEquals(listOf("排队一"), manager.state.value.queued)
        assertEquals(1, turnStarts())
        manager.close()
    }

    /** 用户自己按的停，更不该接着把排着的放出去 */
    @Test
    fun `an interrupted turn keeps the queue too`() = runBlocking {
        val manager = runningManager(BUSY_TURN)
        manager.sendTurn("第一问")
        await { manager.state.value.busy }
        manager.sendTurn("排队一")

        scripted.queue(turnCompleted("interrupted"))

        await { !manager.state.value.busy }
        assertEquals(listOf("排队一"), manager.state.value.queued)
        assertEquals(1, turnStarts())
        manager.close()
    }

    @Test
    fun `taking a queued message back removes exactly that one`() = runBlocking {
        val manager = runningManager(BUSY_TURN)
        manager.sendTurn("第一问")
        await { manager.state.value.busy }
        manager.sendTurn("排队一")
        manager.sendTurn("排队二")
        manager.sendTurn("排队三")

        assertEquals("排队二", manager.takeQueued(1))
        assertEquals(listOf("排队一", "排队三"), manager.state.value.queued)
        // 越界不能崩，也不能动队列
        assertNull(manager.takeQueued(9))
        assertEquals(listOf("排队一", "排队三"), manager.state.value.queued)
        manager.close()
    }

    /** 一串同样的未知方法只占一行 —— 否则正经内容会被灰字整屏顶出去 */
    @Test
    fun `a burst of the same unknown method collapses into one line`() = runBlocking {
        val manager = runningManager(BUSY_TURN)
        manager.sendTurn("问")
        await { manager.state.value.busy }
        repeat(5) { scripted.queue("""{"method":"thread/weird","params":{}}""") }
        await { notes(manager).size == 1 && notes(manager).first().endsWith("×5") }

        assertEquals(listOf("未知的 codex 方法：thread/weird ×5"), notes(manager))
        manager.close()
    }

    /** 换一个方法、或者中间夹了真内容，都得另起一行：数字不能跑到读过的位置去 */
    @Test
    fun `a different method, or intervening content, starts a new line`() = runBlocking {
        val manager = runningManager(BUSY_TURN)
        manager.sendTurn("问")
        await { manager.state.value.busy }

        scripted.queue("""{"method":"thread/weird","params":{}}""")
        scripted.queue("""{"method":"thread/odd","params":{}}""")
        await { notes(manager).size == 2 }

        // 夹一条真内容，再来同一个方法
        scripted.queue("""{"method":"item/completed","params":{"item":{"type":"agentMessage","id":"m1","text":"在"}}}""")
        scripted.queue("""{"method":"thread/odd","params":{}}""")
        await { notes(manager).size == 3 }

        assertEquals(
            listOf(
                "未知的 codex 方法：thread/weird",
                "未知的 codex 方法：thread/odd",
                "未知的 codex 方法：thread/odd",
            ),
            notes(manager),
        )
        manager.close()
    }

    private fun notes(manager: CodexAppServerManager): List<String> =
        manager.state.value.items.filterIsInstance<ChatItem.Note>().map { it.text }

    /**
     * turn 起来但不收尾，停在 busy —— 好让测试往队列里塞东西。
     * 脚本里不给 turn/completed，那一行由各测试自己在想要的时机 [ScriptedProcess.queue] 进去。
     */
    private val BUSY_TURN = listOf("""{"id":"3","result":{"turn":{"id":"turn-1"}}}""")

    /** 一个握手走完的管理器。排队那几条测试都从这里开始 */
    private lateinit var scripted: ScriptedProcess

    private suspend fun runningManager(turnLines: List<String>): CodexAppServerManager {
        scripted = ScriptedProcess(
            "\"method\":\"initialize\"" to listOf("""{"id":"1","result":{}}"""),
            "thread/start" to listOf("""{"id":"2","result":{"threadId":"t"}}"""),
            "turn/start" to turnLines,
        )
        val manager = CodexAppServerManager { scripted }
        manager.start()
        await { manager.state.value.status == SessionStatus.Running }
        return manager
    }

    private fun turnStarts(): Int =
        scripted.writtenLines().count { it.contains(""""method":"turn/start"""") }

    private fun userTexts(manager: CodexAppServerManager): List<String> =
        manager.state.value.items.filterIsInstance<ChatItem.UserText>().map { it.text }

    private fun turnCompleted(status: String): String =
        """{"method":"turn/completed","params":{"turn":{"id":"turn-1","status":"$status"}}}"""

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
