package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.BackgroundPort
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.MonitorRequest
import dev.min.agent.spi.MonitorStart
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolResult
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.args
import dev.min.agent.testkit.call
import dev.min.agent.testkit.reject
import dev.min.agent.testkit.parsed
import dev.min.agent.testkit.text
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Monitor / TaskOutput / TaskStop 的入参与权限声明；起、限流、期限在内核（BackgroundJobsTest） */
class BackgroundToolsTest {
    private class Recording : BackgroundPort {
        val adopted = mutableListOf<Triple<ExecHandle, String, String?>>()
        val monitors = mutableListOf<MonitorRequest>()
        val outputs = mutableListOf<Triple<String, Boolean, Long>>()
        var start: MonitorStart = MonitorStart.Started("mon_1", 300_000, GuestPath("/root/.min/exec-logs/h1.log"))
        override suspend fun adoptShell(handle: ExecHandle, command: String, description: String?) { adopted += Triple(handle, command, description) }
        override suspend fun startMonitor(request: MonitorRequest): MonitorStart = start.also { monitors += request }
        override suspend fun taskOutput(taskId: String, block: Boolean, timeoutMs: Long): ToolResult {
            outputs += Triple(taskId, block, timeoutMs)
            return ToolResult(listOf(TextPart("输出 of $taskId")))
        }
    }

    private val bg = Recording()
    private val ctx = TestToolContext().also { it.background = bg }

    private fun ws(url: String, vararg protocols: String): JsonObject = JsonObject(
        mapOf("url" to JsonPrimitive(url)) + if (protocols.isEmpty()) emptyMap() else mapOf("protocols" to JsonArray(protocols.map(::JsonPrimitive))),
    )

    // ---- Monitor 入参 ----

    @Test
    fun `command 与 ws 恰好一个`() {
        assertEquals("`command` 与 `ws` 只能给一个。", MonitorTool.reject("description" to "x", "command" to "tail -f a", "ws" to ws("ws://h/x")))
        assertEquals("需要给 `command`（跑命令）或 `ws`（开 WebSocket）其中一个。", MonitorTool.reject("description" to "x"))
        assertEquals("字段 `command` 不能为空。", MonitorTool.reject("description" to "x", "command" to " "))
        assertEquals("字段 `description` 不能为空。", MonitorTool.reject("description" to " ", "command" to "ls"))
        assertTrue(MonitorTool.reject("command" to "ls").contains("description"))
    }

    @Test
    fun `timeout_ms 最多接受 3600000，超过的拒绝（生效期限由内核钳到 30 分钟）`() {
        assertEquals(3_600_000L, MonitorTool.parsed("description" to "x", "command" to "ls", "timeout_ms" to 3_600_000).timeoutMs)
        assertTrue(MonitorTool.reject("description" to "x", "command" to "ls", "timeout_ms" to 3_600_001).contains("3600000"))
        assertNull(MonitorTool.parsed("description" to "x", "command" to "ls").timeoutMs)
    }

    @Test
    fun `ws 的地址约束：只收 ws 与 wss，不带凭据与空白，只收 ASCII`() {
        fun rejectUrl(url: String) = MonitorTool.reject("description" to "x", "ws" to ws(url))
        assertTrue(rejectUrl("http://h/x").contains("ws://"))
        assertTrue(rejectUrl("https://h/x").contains("ws://"))
        assertTrue(rejectUrl("ws://user:pw@h/x").contains("用户名或密码"))
        assertTrue(rejectUrl("ws://user@h/x").contains("用户名或密码"))
        assertTrue(rejectUrl("ws://h/a b").contains("空白"))
        assertTrue(rejectUrl("ws://h/中").contains("ASCII"))
        assertTrue(rejectUrl("ws://h/\n").contains("空白"))
        assertTrue(rejectUrl("ws:///x").contains("主机"))
        assertTrue(rejectUrl("ws://:80/x").contains("主机"))
        assertEquals("ws://h:8080/feed?a=1", MonitorTool.parsed("description" to "x", "ws" to ws("ws://h:8080/feed?a=1")).ws!!.url)
        assertEquals("WSS://Host/x", MonitorTool.parsed("description" to "x", "ws" to ws("WSS://Host/x")).ws!!.url)
    }

    @Test
    fun `ws 的子协议：合法 token、不重复`() {
        assertEquals(listOf("graphql-ws", "v1.json"), MonitorTool.parsed("description" to "x", "ws" to ws("ws://h", "graphql-ws", "v1.json")).ws!!.protocols)
        assertTrue(MonitorTool.reject("description" to "x", "ws" to ws("ws://h", "has space")).contains("子协议"))
        assertTrue(MonitorTool.reject("description" to "x", "ws" to ws("ws://h", "a", "a")).contains("重复"))
        assertTrue(MonitorTool.reject("description" to "x", "ws" to ws("ws://h", "")).contains("子协议"))
        assertTrue(MonitorTool.reject("description" to "x", "ws" to JsonObject(mapOf("url" to JsonPrimitive("ws://h"), "protocols" to JsonPrimitive("a")))).contains("数组"))
        assertTrue(MonitorTool.reject("description" to "x", "ws" to JsonPrimitive("ws://h")).contains("对象"))
    }

    // ---- Monitor 权限：命令按 Bash 的规则判 ----

    @Test
    fun `命令源的权限声明带命令原文，规则只看命令（Bash 的 allow deny 对它同样生效）`() {
        val input = MonitorTool.parsed("description" to "盯错误", "command" to "tail -f app.log | grep --line-buffered ERROR")
        val ask = MonitorTool.permissionAsk(input, ctx)
        assertEquals("Monitor", ask.tool)
        assertEquals(listOf("tail -f app.log | grep --line-buffered ERROR"), ask.commands.map { it.raw })
        assertTrue(ask.domains.isEmpty() && ask.paths.isEmpty())
    }

    @Test
    fun `WebSocket 源的权限声明不带域名：WebFetch 的 allow 不能顺手放行一条长连接`() {
        val ask = MonitorTool.permissionAsk(MonitorTool.parsed("description" to "x", "ws" to ws("wss://feed.example.com/x")), ctx)
        assertEquals("Monitor", ask.tool)
        assertTrue(ask.domains.isEmpty() && ask.commands.isEmpty())
        assertTrue("卡片上要看得到地址", ask.summary.contains("wss://feed.example.com/x"))
        val withToken = MonitorTool.permissionAsk(MonitorTool.parsed("description" to "x", "ws" to ws("wss://feed.example.com/x?token=SECRET#f")), ctx)
        assertTrue("摘要只到路径，token 不进审批摘要", withToken.summary.endsWith("wss://feed.example.com/x") && !withToken.summary.contains("SECRET"))
    }

    @Test
    fun `Monitor 起进程，不标只读`() {
        assertFalse(MonitorTool.traits.readOnly)
        assertFalse(MonitorTool.traits.alwaysAsk)
    }

    // ---- Monitor 执行 ----

    @Test
    fun `启动成功的回执给任务 id、期限、输出路径与停法`() = runTest {
        val r = MonitorTool.call(ctx, "description" to "盯日志", "command" to "tail -f a", "timeout_ms" to 120_000)
        assertFalse(r.isError)
        assertEquals(listOf(MonitorRequest("盯日志", 120_000L, "tail -f a", null)), bg.monitors)
        assertTrue(r.text.contains("mon_1") && r.text.contains("5 分钟") && r.text.contains("/root/.min/exec-logs/h1.log") && r.text.contains("TaskStop"))
    }

    @Test
    fun `没起来的说明原样给模型`() = runTest {
        bg.start = MonitorStart.Rejected("已经有 8 个 watch 在跑")
        val r = MonitorTool.call(ctx, "description" to "x", "command" to "ls")
        assertTrue(r.isError)
        assertEquals("已经有 8 个 watch 在跑", r.text)
    }

    @Test
    fun `ws 请求带着子协议转给内核`() = runTest {
        MonitorTool.call(ctx, "description" to "x", "ws" to ws("ws://h/x", "v1"))
        assertEquals("ws://h/x", bg.monitors.single().ws!!.url)
        assertEquals(listOf("v1"), bg.monitors.single().ws!!.protocols)
        assertNull(bg.monitors.single().command)
    }

    // ---- TaskOutput ----

    @Test
    fun `TaskOutput 默认等 30 秒、上限 10 分钟，转发给内核`() = runTest {
        assertEquals(30_000L, TaskOutputTool.parsed("task_id" to "t1").timeoutMs)
        assertTrue(TaskOutputTool.parsed("task_id" to "t1").block)
        assertEquals(600_000L, TaskOutputTool.parsed("task_id" to "t1", "timeout" to 9_999_999).timeoutMs)
        assertFalse(TaskOutputTool.parsed("task_id" to "t1", "block" to false).block)
        TaskOutputTool.call(ctx, "task_id" to " t1 ", "block" to false, "timeout" to 5)
        assertEquals(Triple("t1", false, 5L), bg.outputs.single())
        assertTrue(TaskOutputTool.reject("task_id" to " ").contains("task_id"))
        assertTrue(TaskOutputTool.traits.readOnly)
    }

    // ---- TaskStop ----

    @Test
    fun `TaskStop 认 task_id，也认 CC 的旧字段 shell_id`() {
        assertTrue(TaskStopTool.parse(args("task_id" to "mon_1")) is ToolInput.Valid)
        assertTrue(TaskStopTool.parse(args("shell_id" to "h1")) is ToolInput.Valid)
        assertTrue(TaskStopTool.parse(args()) is ToolInput.Invalid)
        assertTrue(TaskStopTool.parse(args("shell_id" to " ")) is ToolInput.Invalid)
        assertEquals("停止任务 h1", TaskStopTool.permissionAsk(args("shell_id" to "h1"), ctx).summary)
    }

    // ---- Bash 转后台时登记 ----

    @Test
    fun `Bash 转后台时登记进后台任务表，回执说明通知与停法`() = runTest {
        val handle = ExecHandle("h9", 9, GuestPath("/root/.min/exec-logs/h9.log"))
        ctx.exec.script = { _, _ -> ExecResult.Backgrounded(handle, "booting") }
        val r = BashTool().call(ctx, "command" to "npm run dev", "description" to "开发服务器", "run_in_background" to true)
        assertEquals(listOf(Triple(handle, "npm run dev", "开发服务器")), bg.adopted)
        assertTrue(r.text.contains("h9") && r.text.contains("通知") && r.text.contains("TaskStop") && r.text.contains("/root/.min/exec-logs/h9.log"))
    }

    @Test
    fun `前台跑完的命令不登记`() = runTest {
        ctx.exec.script = { _, _ -> ExecResult.Exited(0, "ok", false, 1) }
        BashTool().call(ctx, "command" to "ls")
        assertTrue(bg.adopted.isEmpty())
    }
}
