package dev.min.agent.tools

import dev.min.agent.testkit.*
import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ResumePolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BashToolTest {
    private val ctx = TestToolContext()

    private fun script(result: ExecResult) {
        ctx.exec.script = { _, _ -> result }
    }

    // ---- parse ----

    @Test
    fun `缺 command 或为空被拒绝`() {
        assertEquals("缺少必填字段 `command`（字符串）。", BashTool.reject())
        assertEquals("字段 `command` 不能为空。", BashTool.reject("command" to "  "))
    }

    @Test
    fun `字段类型与取值校验`() {
        assertEquals("字段 `timeout` 必须是整数，收到 soon。", BashTool.reject("command" to "ls", "timeout" to "soon"))
        assertEquals("字段 `timeout` 不能小于 1，收到 0。", BashTool.reject("command" to "ls", "timeout" to 0))
        assertEquals("字段 `run_in_background` 必须是布尔值（true / false）。", BashTool.reject("command" to "ls", "run_in_background" to "x"))
        assertEquals("字段 `description` 必须是字符串。", BashTool.reject("command" to "ls", "description" to 3))
    }

    @Test
    fun `默认超时 120 秒`() {
        assertEquals(120_000L, BashTool.parsed("command" to "ls").timeoutMs)
    }

    @Test
    fun `超时超过上限钳到 600000，不拒绝`() {
        assertEquals(600_000L, BashTool.parsed("command" to "ls", "timeout" to 9_999_999).timeoutMs)
        assertEquals(600_000L, BashTool.parsed("command" to "ls", "timeout" to 600_000).timeoutMs)
        assertEquals(5_000L, BashTool.parsed("command" to "ls", "timeout" to 5_000).timeoutMs)
    }

    // ---- 三种结果 ----

    @Test
    fun `正常退出 输出加退出码`() = runTest {
        script(ExecResult.Exited(0, "a.txt\nb.txt\n", truncated = false, durationMs = 5))
        val r = BashTool.call(ctx, "command" to "ls")
        assertFalse(r.isError)
        assertEquals("a.txt\nb.txt\n退出码 0", r.text)
    }

    @Test
    fun `退出码非 0 是错误，输出照给全`() = runTest {
        script(ExecResult.Exited(2, "ls: nope: No such file or directory\n", truncated = false, durationMs = 5))
        val r = BashTool.call(ctx, "command" to "ls nope")
        assertTrue(r.isError)
        assertEquals("ls: nope: No such file or directory\n退出码 2", r.text)
    }

    @Test
    fun `无输出时说明无输出`() = runTest {
        script(ExecResult.Exited(0, "", truncated = false, durationMs = 1))
        assertEquals("（无输出）\n退出码 0", BashTool.call(ctx, "command" to "true").text)
    }

    @Test
    fun `端口截过头尾时注明`() = runTest {
        script(ExecResult.Exited(0, "head\n...\ntail", truncated = true, durationMs = 1))
        assertEquals(
            "head\n...\ntail\n（输出过长，中间部分已省略，只保留了头尾）\n退出码 0",
            BashTool.call(ctx, "command" to "yes").text,
        )
    }

    @Test
    fun `超时是错误，说明超时时长与已有输出`() = runTest {
        script(ExecResult.TimedOut("partial\n", truncated = false))
        val r = BashTool.call(ctx, "command" to "sleep 999", "timeout" to 3000)
        assertTrue(r.isError)
        assertEquals("命令超过 3000 毫秒仍未结束，已终止。超时前的输出：\npartial", r.text)
    }

    @Test
    fun `超时且没有输出`() = runTest {
        script(ExecResult.TimedOut("", truncated = false))
        assertEquals(
            "命令超过 120000 毫秒仍未结束，已终止。超时前没有输出。",
            BashTool.call(ctx, "command" to "sleep 999").text,
        )
    }

    @Test
    fun `转入后台不是错误，句柄 pid 日志路径写进结果文字`() = runTest {
        val h = ExecHandle("bg1", pid = 4242, logPath = GuestPath("/tmp/bg1.log"))
        script(ExecResult.Backgrounded(h, "starting...\n"))
        val r = BashTool.call(ctx, "command" to "npm run dev")
        assertFalse(r.isError)
        assertEquals("已转入后台（id bg1，pid 4242，日志 /tmp/bg1.log（用 Read 查看））。命令结束时你会收到通知；用 TaskStop 按 id 停掉它。目前的输出：\nstarting...\n", r.text)
    }

    @Test
    fun `后台没有日志路径也没有输出`() = runTest {
        script(ExecResult.Backgrounded(ExecHandle("bg2"), ""))
        assertEquals("已转入后台（id bg2（没有日志文件））。命令结束时你会收到通知；用 TaskStop 按 id 停掉它。目前还没有输出。", BashTool.call(ctx, "command" to "x").text)
    }

    // ---- 交给端口的参数 ----

    @Test
    fun `前台命令：cwd 与 10 秒前台预算，超时默认值与输出预算`() = runTest {
        BashTool.call(ctx, "command" to "make")
        val (cmd, opts) = ctx.exec.runs.single()
        assertEquals("make", cmd.script)
        assertEquals(GuestPath("/work"), cmd.cwd)
        assertEquals(ExecOptions(timeoutMs = 120_000, foregroundBudgetMs = 10_000, maxOutputChars = 30_000), opts)
    }

    @Test
    fun `run_in_background 时前台预算是 0`() = runTest {
        BashTool.call(ctx, "command" to "npm run dev", "run_in_background" to true)
        assertEquals(0L, ctx.exec.runs.single().second.foregroundBudgetMs)
    }

    @Test
    fun `超大 timeout 在交给端口时已被钳制`() = runTest {
        BashTool.call(ctx, "command" to "x", "timeout" to 10_000_000)
        assertEquals(600_000L, ctx.exec.runs.single().second.timeoutMs)
    }

    @Test
    fun `不产生 touched`() = runTest {
        assertTrue(BashTool.call(ctx, "command" to "touch a").touched.isEmpty())
    }

    // ---- 权限与 traits ----

    @Test
    fun `permissionAsk 带原始命令，summary 优先用 description`() {
        val ask = BashTool.permissionAsk(BashTool.parsed("command" to "git status", "description" to "看工作区状态"), ctx)
        assertEquals("Bash", ask.tool)
        assertEquals("看工作区状态", ask.summary)
        assertEquals(listOf(CommandAccess(raw = "git status")), ask.commands)
        assertTrue(ask.paths.isEmpty())
    }

    @Test
    fun `没有 description 时 summary 是命令，超过 80 字截断`() {
        assertEquals("git status", BashTool.permissionAsk(BashTool.parsed("command" to "git status"), ctx).summary)
        val long = "echo " + "a".repeat(100)
        val summary = BashTool.permissionAsk(BashTool.parsed("command" to long), ctx).summary
        assertEquals(long.take(80) + "…", summary)
    }

    @Test
    fun `traits 是保守默认`() {
        assertFalse(BashTool.traits.readOnly)
        assertFalse(BashTool.traits.concurrencySafe)
        assertEquals(ResumePolicy.Unknown, BashTool.traits.resumePolicy)
    }

    @Test
    fun `description 写明 cd 与 export 不跨调用保留`() {
        val text = BashTool.description(dev.min.agent.spi.HarnessProfile(dev.min.agent.spi.ModelFamily.Claude, dev.min.agent.spi.EditStyle.StrReplace))
        assertTrue(text, text.contains("cd 与 export 不会保留到下一次调用"))
    }
}
