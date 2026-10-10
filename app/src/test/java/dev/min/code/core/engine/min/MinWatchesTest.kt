package dev.min.code.core.engine.min

import dev.min.agent.kernel.BackgroundJobInfo
import dev.min.agent.kernel.BackgroundJobKind
import dev.min.agent.kernel.BackgroundJobStatus
import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.HostPorts
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.tools.MonitorTool
import dev.min.code.core.engine.BackgroundTask
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.SessionCommand
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 内核任务表里的 watch 怎么进界面的任务条：映射口径，以及点「停止」走的是内核的任务表（真 Hub、真 MinAgentSession） */
class MinWatchesTest {
    private fun job(
        kind: BackgroundJobKind = BackgroundJobKind.Monitor, status: BackgroundJobStatus = BackgroundJobStatus.Running,
        exit: Int? = null, command: String? = "tail -f app.log", ws: String? = null,
    ) = BackgroundJobInfo(
        id = "mon_1", session = SessionId("s"), kind = kind, description = "盯日志", command = command, wsUrl = ws,
        outputPath = GuestPath("/root/.min/exec-logs/h1.log"), startedAtMs = 1_000, deadlineAtMs = 301_000,
        status = status, endedAtMs = if (status == BackgroundJobStatus.Running) null else 2_000, exitCode = exit, events = 7,
    )

    @Test fun `watch 映射成任务条上的一项，带事件数、期限、输出路径与命令`() {
        val t = watchTasks(listOf(job())).single()
        assertTrue(t.monitor); assertEquals(BackgroundTask.Status.Running, t.status)
        assertEquals("mon_1", t.id); assertEquals("盯日志", t.label); assertEquals("tail -f app.log", t.command)
        assertEquals(7, t.events); assertEquals(301_000L, t.deadlineAt); assertEquals("/root/.min/exec-logs/h1.log", t.logRef)
        assertEquals(1_000L, t.startedAt); assertNull(t.endedAt)
    }

    @Test fun `后台命令不在这里出（它走进程表与回执归属），免得一条命令列两行`() {
        assertTrue(watchTasks(listOf(job(kind = BackgroundJobKind.Shell))).isEmpty())
    }

    @Test fun `结局的口径：退出码 0 或未知是完成，非 0 是失败，到期单列，被停就是被停`() {
        fun status(s: BackgroundJobStatus, exit: Int? = null) = watchTasks(listOf(job(status = s, exit = exit))).single().status
        assertEquals(BackgroundTask.Status.Done, status(BackgroundJobStatus.Exited, 0))
        assertEquals(BackgroundTask.Status.Done, status(BackgroundJobStatus.Exited, null))
        assertEquals(BackgroundTask.Status.Failed, status(BackgroundJobStatus.Exited, 2))
        assertEquals(BackgroundTask.Status.Expired, status(BackgroundJobStatus.TimedOut))
        assertEquals(BackgroundTask.Status.Stopped, status(BackgroundJobStatus.Stopped))
        assertEquals(BackgroundTask.Status.Failed, status(BackgroundJobStatus.Failed))
    }

    @Test fun `WebSocket 源没有命令：命令栏写地址`() {
        assertEquals("ws wss://feed.example/x", watchTasks(listOf(job(command = null, ws = "wss://feed.example/x"))).single().command)
    }

    @Test fun `审批卡上的 WebSocket 地址只到路径，查询串里的 token 不上屏`() {
        fun input(url: String) = JsonObject(mapOf("description" to JsonPrimitive("x"), "ws" to JsonObject(mapOf("url" to JsonPrimitive(url)))))
        assertEquals("wss://feed.example.com:8443/v1/stream", monitorWsAddress(input("wss://feed.example.com:8443/v1/stream?token=SECRET&a=1#frag")))
        assertEquals("feed.example.com:8443", monitorWsHost(input("wss://feed.example.com:8443/v1/stream?token=SECRET")))
        assertEquals("127.0.0.1:18771", monitorWsHost(input("ws://127.0.0.1:18771/feed")))
        assertEquals("host", monitorWsHost(input("ws://host")))
        assertNull(monitorWsAddress(JsonObject(mapOf("command" to JsonPrimitive("tail -f x")))))
        assertNull(monitorWsAddress(input("not a url ::")))
    }

    // ---- 真 Hub + 真 MinAgentSession ----

    private class MemFs : WorkspaceFs {
        val files = ConcurrentHashMap<String, ByteArray>()
        override suspend fun stat(path: GuestPath): FileStat? = files[path.value]?.let { FileStat(FileKind.File, it.size.toLong(), 0) }
        override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray {
            val b = files.getValue(path.value)
            val from = offset.toInt().coerceAtMost(b.size)
            return b.copyOfRange(from, minOf(b.size, from + limit))
        }
        override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) { files[path.value] = bytes }
        override suspend fun list(dir: GuestPath): List<DirEntry> = emptyList()
        override fun scopeOf(path: GuestPath) = PathScope.Workspace
    }

    private class LogExec(val fs: MemFs) : Exec {
        val killed = CopyOnWriteArrayList<String>()
        val alive = ConcurrentHashMap<String, Boolean>()
        override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult {
            val h = ExecHandle("h1", 11, GuestPath("/root/.min/exec-logs/h1.log"))
            fs.files[h.logPath!!.value] = ByteArray(0); alive["h1"] = true
            return ExecResult.Backgrounded(h, "")
        }
        override suspend fun kill(handle: ExecHandle) { killed += handle.id; alive[handle.id] = false }
        override suspend fun isAlive(handle: ExecHandle): Boolean? = alive[handle.id] ?: false
    }

    private val fs = MemFs()
    private val exec = LogExec(fs)

    /** 第一次请求让模型调 Monitor，其后（通知唤醒会话时）一律回一句话 */
    private val model = RoutedModel { req ->
        val step = if (req.messages.size == 1) {
            replyParts(
                listOf(ToolCallPart(CallId("m1"), "Monitor", JsonObject(mapOf("description" to JsonPrimitive("盯日志"), "command" to JsonPrimitive("tail -f app.log"))))),
                StopReason.ToolUse,
            )
        } else answer("ok")
        step(req)
    }
    private val kernel = TestKernel(
        model, tools = listOf(MonitorTool),
        hostPorts = HostPorts(fs, object : dev.min.agent.spi.Search {
            override fun grep(query: dev.min.agent.spi.GrepQuery) = error("unused")
            override fun glob(query: dev.min.agent.spi.GlobQuery) = error("unused")
        }, exec) { _: SessionId -> object : dev.min.agent.spi.ArtifactSink { override suspend fun put(name: String, bytes: ByteArray): String = error("unused") } },
    )

    @After fun tearDown() = kernel.close()

    @Test fun `会话状态里出现 watch，事件数随输出涨，点停止走内核任务表并杀进程`() = runBlocking {
        val rt = kernel.newRoot(mode = PermissionMode.BypassPermissions)
        val session = MinAgentSession(rt, FakeHost(kernel), kernel.scope)
        rt.send(ClientMsgId("u1"), listOf(TextPart("watch the log")))
        val result = awaitValue { rt.state.value.toolResults[CallId("m1")] }
        assertFalse("Monitor 调用应成功：" + result.content.filterIsInstance<TextPart>().joinToString { it.text }, result.isError)
        val watch = awaitValue { session.state.value.tasks.firstOrNull { it.monitor } }
        assertEquals("盯日志", watch.label); assertEquals("tail -f app.log", watch.command)
        assertEquals(BackgroundTask.Status.Running, watch.status); assertNotNull(watch.deadlineAt)
        fs.files["/root/.min/exec-logs/h1.log"] = "a\nb\nc\n".toByteArray()
        val running = awaitValue { session.state.value.tasks.firstOrNull { it.id == watch.id && it.events == 3 } }
        assertEquals(BackgroundTask.Status.Running, running.status)
        assertTrue("有在跑的 watch 就要保活（前台服务）：进程活着它才继续收事件", awaitValue { session.summary.value.takeIf { it.keepAlive } }.keepAlive)

        assertTrue(session.submit(SessionCommand.StopTask("c1", watch.id)) is CommandResult.Accepted)
        val stopped = awaitValue { session.state.value.tasks.firstOrNull { it.id == watch.id && it.status == BackgroundTask.Status.Stopped } }
        assertEquals(listOf("h1"), exec.killed)
        assertEquals("停止后仍留着收到过的事件数", 3, stopped.events)
        // 用户在任务条上停的：模型不知道，补一条通知（已经进了对话或还在收件箱）
        val notices = rt.state.value.let { st ->
            (st.history.filterIsInstance<HistoryEntry.User>().filter { it.origin is InputOrigin.Task }.map { it.parts } +
                st.inbox.filter { it.origin is InputOrigin.Task }.map { it.parts }).map { p -> p.filterIsInstance<TextPart>().joinToString("") { it.text } }
        }
        assertTrue(notices.any { "已被停止" in it })
    }
}
