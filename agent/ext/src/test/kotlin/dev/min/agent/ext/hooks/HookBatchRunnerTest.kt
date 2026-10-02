package dev.min.agent.ext.hooks

import dev.min.agent.spi.Clock
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.testkit.FakeExec
import dev.min.agent.testkit.FakeFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.coroutineContext

class HookBatchRunnerTest {
    private val dir = GuestPath("/root/.min/hooks/batch")
    private val cwd = GuestPath("/work")
    private fun runner(exec: Exec, fs: WorkspaceFs = FakeFs()) = HookBatchRunner(exec, fs, IdGenerator { "batch" },
        { "mapped${it.value}" }, Clock { 42 })

    @Test fun `脚本安全引用 args env 路径 命令保持原文且只使用一个 wait`() {
        val exec = FakeExec()
        val h = handler(command = "printf '中文'; echo \"\$VALUE\"")
        val argv = handler().copy(command = "program with space", args = listOf("it's", "\$(touch bad)", "中文\nline"))
        val script = runner(exec).buildScript(listOf(h, argv), mapOf("GOOD" to "it's \$(bad)",
            "BAD-NAME" to "secret", "9BAD" to "secret"), dir, listOf(2, 3))
        assertTrue(script.script.contains("export GOOD='it'\"'\"'s \$(bad)'"))
        assertFalse(script.script.contains("secret")); assertEquals(2, script.notes.size)
        assertTrue(script.script.contains("'program with space' 'it'\"'\"'s' '\$(touch bad)' '中文\nline'"))
        assertTrue(script.script.contains("sh -c 'printf '"))
        assertTrue(script.script.contains("timeout -k 2 3"))
        assertEquals(2, Regex("\\) &").findAll(script.script).count())
        assertTrue(script.script.endsWith("wait\n"))
        assertTrue(script.script.contains("< 'mapped/root/.min/hooks/batch/in.json'"))
        assertTrue(script.script.contains("> 'mapped/root/.min/hooks/batch/out.0' 2> 'mapped/root/.min/hooks/batch/err.0'"))
    }

    @Test fun `文件协议合批一个 Exec 并保持分离通道顺序和整批预算`() = runBlocking {
        val fs = FakeFs()
        val exec = FakeExec { command, options ->
            if (command.script.startsWith("rm ")) ExecResult.Exited(0, "", false, 1) else {
                assertEquals("""{"prompt":"中文'"}""", fs.text("${dir.value}/in.json"))
                assertEquals(cwd, command.cwd); assertTrue(command.env.isEmpty())
                assertEquals(12_000, options.timeoutMs); assertNull(options.foregroundBudgetMs)
                (0..2).forEach { i ->
                    fs.put("${dir.value}/out.$i", "out$i"); fs.put("${dir.value}/err.$i", "err$i")
                    fs.put("${dir.value}/code.$i", listOf(0, 2, 124)[i].toString())
                    fs.put("${dir.value}/duration.$i", (100 + i).toString())
                }
                ExecResult.Exited(0, "ignored merged", false, 10)
            }
        }
        val runs = runner(exec, fs).run((0..2).map { handler(command = "h$it") }, objectOf("""{"prompt":"中文'"}"""),
            emptyMap(), cwd) { if (it.command == "h0") 7 else 1 }
        assertEquals(listOf(0, 2, 124), runs.map { it.exitCode })
        assertEquals(listOf("out0", "out1", "out2"), runs.map { it.stdout })
        assertEquals(listOf("err0", "err1", "err2"), runs.map { it.stderr })
        assertEquals(listOf(100L, 101L, 102L), runs.map { it.durationMs })
        assertEquals(listOf(false, false, true), runs.map { it.timedOut })
        assertTrue(runs.none { it.failedToStart })
        assertEquals(2, exec.runs.size); assertTrue(exec.runs.last().first.script.startsWith("rm -rf -- 'mapped/root/.min/hooks/batch'"))
        assertEquals(2, fs.writes.size)
        assertTrue(fs.text("${dir.value}/batch.sh")!!.contains("timeout -k 2 7"))
        assertEquals("sh 'mapped/root/.min/hooks/batch/batch.sh'", exec.runs.first().first.script)
    }

    @Test fun `整批超时已写 code 的照常读取缺 code 的同时标超时与未启动`() = runBlocking {
        val fs = FakeFs()
        val exec = FakeExec { cmd, _ ->
            if (cmd.script.startsWith("rm ")) ExecResult.Exited(0, "", false, 1) else {
                fs.put("${dir.value}/code.0", "0"); fs.put("${dir.value}/out.0", "finished")
                fs.put("${dir.value}/out.1", "partial")
                ExecResult.TimedOut("merged", false)
            }
        }
        val runs = runner(exec, fs).run(listOf(handler(), handler(command = "b")), objectOf("{}"), emptyMap(), cwd) { 1 }
        assertFalse(runs[0].timedOut); assertFalse(runs[0].failedToStart); assertEquals("finished", runs[0].stdout)
        assertTrue(runs[1].timedOut); assertTrue(runs[1].failedToStart); assertNull(runs[1].exitCode)
    }

    @Test fun `缺失损坏 code 与127标未启动且非法 env note 随结果返回`() = runBlocking {
        val fs = FakeFs()
        val exec = FakeExec { cmd, _ ->
            if (!cmd.script.startsWith("rm ")) {
                fs.put("${dir.value}/code.1", "127"); fs.put("${dir.value}/code.2", "broken")
            }
            ExecResult.Exited(0, "", false, 1)
        }
        val runs = runner(exec, fs).run(List(3) { handler() }, objectOf("{}"), mapOf("BAD-X" to "value"), cwd) { 1 }
        assertTrue(runs.all { it.failedToStart && !it.timedOut })
        assertTrue(runs.all { it.notes.any { note -> note.contains("BAD-X") } })
    }

    @Test fun `空批不建目录不调用 Exec`() = runBlocking {
        val fs = FakeFs(); val exec = FakeExec()
        assertTrue(runner(exec, fs).run(emptyList(), objectOf("{}"), emptyMap(), cwd) { 1 }.isEmpty())
        assertTrue(fs.writes.isEmpty()); assertTrue(exec.runs.isEmpty())
    }

    @Test fun `启动异常不抛给回合但仍尝试清理`() = runBlocking {
        val exec = FakeExec { cmd, _ -> if (cmd.script.startsWith("rm ")) ExecResult.Exited(0, "", false, 0)
            else throw java.io.IOException("cannot start") }
        val v = runner(exec).run(listOf(handler()), objectOf("{}"), emptyMap(), cwd) { 1 }.single()
        assertTrue(v.failedToStart); assertTrue(v.notes.any { it.contains("cannot start") }); assertEquals(2, exec.runs.size)
    }

    @Test fun `协程取消用 NonCancellable 清理之后继续抛取消`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var cleanup = false
        val exec = object : Exec {
            override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult {
                if (command.script.startsWith("rm ")) {
                    coroutineContext.ensureActive(); cleanup = true
                    return ExecResult.Exited(0, "", false, 0)
                }
                entered.complete(Unit); awaitCancellation()
            }
            override suspend fun kill(handle: ExecHandle) = Unit
        }
        val job = async { runner(exec).run(listOf(handler()), objectOf("{}"), emptyMap(), cwd) { 1 } }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertTrue(cleanup)
    }

    @Test fun `写 stdin 被取消也走清理`() = runBlocking {
        val fs = object : WorkspaceFs by FakeFs() {
            override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) { throw CancellationException("cancel write") }
        }
        val exec = FakeExec()
        try { runner(exec, fs).run(listOf(handler()), objectOf("{}"), emptyMap(), cwd) { 1 }; fail("应抛取消") }
        catch (_: CancellationException) { assertEquals(1, exec.runs.size) }
    }

    @Test fun `清理失败可见且不覆盖已经读到的结论`() = runBlocking {
        val fs = FakeFs()
        val exec = FakeExec { cmd, _ ->
            if (cmd.script.startsWith("rm ")) throw java.io.IOException("cleanup failed")
            fs.put("${dir.value}/code.0", "2"); fs.put("${dir.value}/err.0", "deny")
            ExecResult.Exited(0, "", false, 0)
        }
        val v = runner(exec, fs).run(listOf(handler()), objectOf("{}"), emptyMap(), cwd) { 1 }.single()
        assertEquals(2, v.exitCode); assertEquals("deny", v.stderr)
        assertTrue(v.notes.any { it.contains("清理失败") })
    }

    @Test fun `Exec 意外后台化要杀掉句柄再报告启动失败`() = runBlocking {
        val handle = ExecHandle("unexpected")
        val exec = FakeExec { cmd, _ -> if (cmd.script.startsWith("rm ")) ExecResult.Exited(0, "", false, 0)
            else ExecResult.Backgrounded(handle, "") }
        val v = runner(exec).run(listOf(handler()), objectOf("{}"), emptyMap(), cwd) { 1 }.single()
        assertEquals(listOf(handle), exec.killed); assertTrue(v.failedToStart)
    }

    @Test fun `timeout 不存在的降级可见且耗时使用注入时钟兜底`() = runBlocking {
        val fs = FakeFs()
        var tick = 0L
        val exec = FakeExec { cmd, _ ->
            if (!cmd.script.startsWith("rm ")) {
                fs.put("${dir.value}/timeout.missing", "missing"); fs.put("${dir.value}/code.0", "0")
                fs.put("${dir.value}/duration.0", "bad date")
            }
            ExecResult.Exited(0, "", false, 1)
        }
        val r = HookBatchRunner(exec, fs, IdGenerator { "batch" }, { it.value }, Clock { tick.also { tick += 200 } })
        val v = r.run(listOf(handler()), objectOf("{}"), emptyMap(), cwd) { 1 }.single()
        assertEquals(200, v.durationMs); assertTrue(v.notes.any { it.contains("逐处理器超时已退化") })
    }

    @Test fun `不安全批 id 在任何 IO 前拒绝`() = runBlocking {
        val exec = FakeExec(); val fs = FakeFs()
        val r = HookBatchRunner(exec, fs, IdGenerator { "../escape" }, { it.value })
        try { r.run(listOf(handler()), objectOf("{}"), emptyMap(), cwd) { 1 }; fail("应拒绝路径") }
        catch (_: IllegalArgumentException) { assertTrue(fs.writes.isEmpty()); assertTrue(exec.runs.isEmpty()) }
    }
}
