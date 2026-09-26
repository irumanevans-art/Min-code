package dev.min.code.core.claudecode

import dev.min.code.core.claudecode.ClaudeCodeManager.TaskInfo
import dev.min.code.core.service.LocalService
import dev.min.code.core.service.LocalServiceStatus
import dev.min.code.core.service.LocalServiceStopReason
import dev.min.code.core.session.ChatItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 底栏「N shell」那张表：CLI 的后台 shell 与本会话托管的服务怎么合、输出从哪读 */
class BackgroundShellsTest {

    private fun input(raw: String) = Json.parseToJsonElement(raw) as JsonObject

    private fun shell(
        id: String,
        status: String = "running",
        toolUseId: String? = "toolu_$id",
        description: String = "tick",
        startedAt: Long = 1_000,
        outputFile: String? = null,
    ) = TaskInfo(
        id = id, taskType = "local_bash", toolUseId = toolUseId, description = description,
        status = status, backgrounded = true, startedAt = startedAt, outputFile = outputFile,
    )

    private fun bashCard(id: String, command: String, result: String? = null, name: String = "Bash") = ChatItem.ToolCall(
        id = "item_$id", toolUseId = "toolu_$id", name = name,
        input = input("""{"command":${kotlinx.serialization.json.JsonPrimitive(command)},"run_in_background":true}"""),
        status = ChatItem.ToolCall.Status.Done, result = result,
    )

    private fun service(
        id: String,
        command: String,
        status: LocalServiceStatus = LocalServiceStatus.Running,
        session: String? = "s1",
        startedAt: Long = 2_000,
        exitCode: Int? = null,
        stopReason: LocalServiceStopReason? = null,
    ) = LocalService(
        id = id, label = "svc $id", command = command, cwdGuest = "/workspace", port = null,
        status = status, startedAtEpochMs = startedAt, sourceSessionKey = session,
        exitCode = exitCode, stopReason = stopReason,
    )

    private val result =
        "Command running in background with ID: b1. Output is being written to: /tmp/claude-0/-workspace/s1/tasks/b1.output."

    // region 合表

    @Test
    fun `a CLI shell takes its full command and output file from the Bash card that launched it`() {
        val shells = backgroundShells(
            tasks = listOf(shell("b1")),
            items = listOf(bashCard("b1", "for i in 1 2 3; do echo tick; done", result)),
            services = emptyList(),
            sessionId = "s1",
        )
        val s = shells.single()
        assertEquals("cli:b1", s.key)
        assertEquals("for i in 1 2 3; do echo tick; done", s.command)
        assertEquals("/tmp/claude-0/-workspace/s1/tasks/b1.output", s.outputFile)
        assertEquals(BackgroundShell.State.Running, s.state)
        assertFalse(s.monitor)
    }

    /** 回放的会话、或者卡片被截掉了：命令退回 CLI 给的 description */
    @Test
    fun `without its card a CLI shell falls back to the description`() {
        val s = backgroundShells(listOf(shell("b1", description = "npm run dev")), emptyList(), emptyList(), "s1").single()
        assertEquals("npm run dev", s.command)
        assertNull(s.outputFile)
    }

    @Test
    fun `task_notification's output file wins over the one parsed from the card`() {
        val s = backgroundShells(
            listOf(shell("b1", status = "completed", outputFile = "/tmp/x/b1.output")),
            listOf(bashCard("b1", "echo", result)),
            emptyList(),
            "s1",
        ).single()
        assertEquals("/tmp/x/b1.output", s.outputFile)
        assertEquals(BackgroundShell.State.Completed, s.state)
    }

    /** 子 agent 自己起的后台 shell：卡在 Agent 卡的子条目里 */
    @Test
    fun `a shell launched by a subagent finds its card among the subitems`() {
        val agent = ChatItem.ToolCall(
            id = "a", toolUseId = "toolu_agent", name = "Agent", input = input("{}"),
            status = ChatItem.ToolCall.Status.Running, subItems = listOf(bashCard("b1", "tail -f log")),
        )
        assertEquals("tail -f log", backgroundShells(listOf(shell("b1")), listOf(agent), emptyList(), "s1").single().command)
    }

    @Test
    fun `a Monitor task is marked as one`() {
        val s = backgroundShells(listOf(shell("b1")), listOf(bashCard("b1", "tail -f log", name = "Monitor")), emptyList(), "s1").single()
        assertTrue(s.monitor)
    }

    @Test
    fun `subagents are not shells`() {
        val agent = TaskInfo(id = "a1", taskType = "local_agent", status = "running", backgrounded = true)
        assertTrue(backgroundShells(listOf(agent), emptyList(), emptyList(), "s1").isEmpty())
    }

    /** 要审批的模式下 CLI 那边没有任务，托管的服务就是这个会话唯一的后台 shell */
    @Test
    fun `this session's hosted services count, other sessions' do not`() {
        val shells = backgroundShells(
            tasks = emptyList(),
            items = emptyList(),
            services = listOf(service("h1", "npm run dev"), service("h2", "vite", session = "other"), service("h3", "x", session = null)),
            sessionId = "s1",
        )
        assertEquals(listOf("hosted:h1"), shells.map { it.key })
        assertEquals("h1", shells.single().hostedServiceId)
        assertNull(shells.single().cliTaskId)
    }

    @Test
    fun `no session id, no hosted services`() {
        assertTrue(backgroundShells(emptyList(), emptyList(), listOf(service("h1", "vite", session = null)), null).isEmpty())
    }

    /** bypass：同一条命令 CLI 跑一份、进程表托管一份。数成一个，停止时两边都停 */
    @Test
    fun `in bypass the CLI shell and its hosted twin are one shell`() {
        val shells = backgroundShells(
            tasks = listOf(shell("b1")),
            items = listOf(bashCard("b1", "nohup npm run dev &", result)),
            services = listOf(service("h1", "npm run dev")),
            sessionId = "s1",
        )
        val s = shells.single()
        assertEquals("cli:b1", s.key)
        assertEquals("b1", s.cliTaskId)
        assertEquals("h1", s.hostedServiceId)
    }

    /** 从面板里一起停掉之后仍是一行，列表里不出两条一样的命令 */
    @Test
    fun `twins that both ended stay one shell`() {
        val shells = backgroundShells(
            tasks = listOf(shell("b1", status = "killed")),
            items = listOf(bashCard("b1", "npm run dev")),
            services = listOf(service("h1", "npm run dev", LocalServiceStatus.Exited, stopReason = LocalServiceStopReason.UserStop)),
            sessionId = "s1",
        )
        assertEquals("h1", shells.single().hostedServiceId)
        assertEquals(BackgroundShell.State.Stopped, shells.single().state)
    }

    /** 一边已经停了：另一边还在跑，必须作为独立的一行露出来 */
    @Test
    fun `twins are not paired when only one of them runs`() {
        val shells = backgroundShells(
            tasks = listOf(shell("b1", status = "killed")),
            items = listOf(bashCard("b1", "npm run dev")),
            services = listOf(service("h1", "npm run dev")),
            sessionId = "s1",
        )
        assertEquals(listOf("hosted:h1", "cli:b1"), shells.map { it.key })
        assertNull(shells[1].hostedServiceId)
        assertEquals(BackgroundShell.State.Stopped, shells[1].state)
    }

    @Test
    fun `running shells come first in start order, finished ones after, newest first`() {
        val shells = backgroundShells(
            tasks = listOf(
                shell("old", status = "completed", startedAt = 1).copy(endedAt = 10),
                shell("late", startedAt = 300),
                shell("new", status = "failed", startedAt = 2).copy(endedAt = 20),
                shell("early", startedAt = 100),
            ),
            items = emptyList(),
            services = emptyList(),
            sessionId = "s1",
        )
        assertEquals(listOf("cli:early", "cli:late", "cli:new", "cli:old"), shells.map { it.key })
    }

    /** 进程表会一直记着结束了的服务：面板里只留最近几个 */
    @Test
    fun `only the latest few finished hosted services are listed`() {
        val finished = (1..6).map { service("h$it", "cmd $it", LocalServiceStatus.Exited, startedAt = it.toLong(), exitCode = 0) }
        val shells = backgroundShells(emptyList(), emptyList(), finished + service("live", "vite"), "s1")
        assertEquals(listOf("hosted:live", "hosted:h6", "hosted:h5", "hosted:h4"), shells.map { it.key })
    }

    @Test
    fun `a hosted service's end is told apart - stopped, failed, finished`() {
        fun stateOf(svc: LocalService) = backgroundShells(emptyList(), emptyList(), listOf(svc), "s1").single().state
        assertEquals(BackgroundShell.State.Stopped, stateOf(service("a", "x", LocalServiceStatus.Exited, stopReason = LocalServiceStopReason.UserStop)))
        assertEquals(BackgroundShell.State.Failed, stateOf(service("b", "x", LocalServiceStatus.Exited, exitCode = 1)))
        assertEquals(BackgroundShell.State.Failed, stateOf(service("c", "x", LocalServiceStatus.Failed)))
        assertEquals(BackgroundShell.State.Completed, stateOf(service("d", "x", LocalServiceStatus.Exited, exitCode = 0)))
        assertEquals(BackgroundShell.State.Running, stateOf(service("e", "x", LocalServiceStatus.Starting)))
    }

    // endregion

    // region 输出文件路径

    @Test
    fun `the output path is read from each of the CLI's background sentences`() {
        assertEquals("/tmp/claude-0/-workspace/s1/tasks/b1.output", outputPathFromBashResult(result))
        assertEquals(
            "/tmp/a/b2.output",
            outputPathFromBashResult(
                "Command was moved to the background (ID: b2) so that a message that arrived while it was running " +
                    "can reach you; it was not interrupted. Output is being written to: /tmp/a/b2.output. You will be notified",
            ),
        )
        assertEquals("/tmp/a/b3.output", outputPathFromBashResult("Output is being written to: /tmp/a/b3.output\nnext"))
        assertNull(outputPathFromBashResult("hello"))
    }

    // endregion

    // region 读尾巴

    private fun tempDir(): File = Files.createTempDirectory("bgshell").toFile()

    @Test
    fun `a short file is read whole`() {
        val f = File(tempDir(), "b1.output").apply { writeText("tick 1\ntick 2\n") }
        assertEquals(OutputTail("tick 1\ntick 2", clipped = false), readOutputTail(f))
    }

    @Test
    fun `a missing file reads as nothing`() {
        assertNull(readOutputTail(File(tempDir(), "nope.output")))
    }

    /** 从中间切进去：第一行是残片（可能停在半个汉字上），丢掉 */
    @Test
    fun `a long file keeps only the tail, starting at a whole line`() {
        val f = File(tempDir(), "b1.output").apply { writeText("第一行很长很长\nsecond\nthird\n") }
        val tail = readOutputTail(f, maxBytes = 16)!!
        assertEquals("second\nthird", tail.text)
        assertTrue(tail.clipped)
    }

    @Test
    fun `too many lines keep the last ones`() {
        val tail = shapeOutput((1..10).joinToString("\n") { "l$it" }, maxLines = 3)
        assertEquals(OutputTail("l8\nl9\nl10", clipped = true), tail)
    }

    @Test
    fun `terminal colours are stripped and carriage-return progress keeps its last frame`() {
        val raw = "\u001B[32mok\u001B[0m\r\n 10%\r 50%\r100%\n\u001B]0;title\u0007done"
        assertEquals("ok\n100%\ndone", shapeOutput(raw).text)
    }

    @Test
    fun `an empty output is an empty tail`() {
        assertEquals(OutputTail("", clipped = false), shapeOutput(""))
    }

    // endregion

    // region 输出从哪来

    private fun workspace(): File = tempDir().also { File(it, "linux/tmp").mkdirs(); File(it, "files").mkdirs() }

    private fun cliShell(outputFile: String? = null, hosted: String? = null) = BackgroundShell(
        key = "cli:b1", cliTaskId = "b1", hostedServiceId = hosted, command = "x", description = "x",
        state = BackgroundShell.State.Running, exitCode = null, startedAt = 0, endedAt = null,
        outputFile = outputFile, monitor = false,
    )

    @Test
    fun `the reported output file is read through the rootfs`() {
        val ws = workspace()
        File(ws, "linux/tmp/claude-0/-workspace/s1/tasks").mkdirs()
        File(ws, "linux/tmp/claude-0/-workspace/s1/tasks/b1.output").writeText("tick 7\n")
        val out = readShellOutput(cliShell("/tmp/claude-0/-workspace/s1/tasks/b1.output"), ws, "s1") { "" }
        assertEquals("tick 7", out?.text)
    }

    /** 工具结果和通知都没给路径（比如回放出来的卡被截了）：按布局去 /tmp/claude-* 下找 */
    @Test
    fun `without a reported path the file is found by the CLI's layout, whatever the cwd`() {
        val ws = workspace()
        File(ws, "linux/tmp/claude-0/-workspace-app/s1/tasks").mkdirs()
        File(ws, "linux/tmp/claude-0/-workspace-app/s1/tasks/b1.output").writeText("found\n")
        assertEquals("found", readShellOutput(cliShell(), ws, "s1") { "" }?.text)
        assertEquals(
            File(ws, "linux/tmp/claude-0/-workspace-app/s1/tasks/b1.output"),
            findTaskOutputFile(File(ws, "linux/tmp"), "s1", "b1"),
        )
        assertNull(findTaskOutputFile(File(ws, "linux/tmp"), "s2", "b1"))
    }

    @Test
    fun `with no file the hosted log is shown, and with nothing at all there is no output`() {
        val ws = workspace()
        assertEquals("from log", readShellOutput(cliShell(hosted = "h1"), ws, "s1") { "from log\n" }?.text)
        assertNull(readShellOutput(cliShell(hosted = "h1"), ws, "s1") { "" })
    }

    /** 路径来自 CLI 的输出，不能拿它拼出 rootfs 之外的宿主路径 */
    @Test
    fun `a path that climbs out of the rootfs is not read`() {
        val ws = workspace()
        File(ws, "secret").writeText("no")
        assertNull(readShellOutput(cliShell("/tmp/../../secret"), ws, null) { "" })
    }

    // endregion
}
