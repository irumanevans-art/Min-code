package dev.min.code.core.engine.min

import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.ShellCwdProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.workspace.ProcessTreeKill
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.WorkspaceShellContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 窄进程接缝让 JVM 验证 Android 上最容易漏掉的取消与回收时序。 */
internal interface ProotProcessHost {
    fun launch(context: WorkspaceShellContext, log: File): Process
    fun reap(process: Process)
    fun pid(process: Process): Int?
}

internal class AndroidProotProcessHost(private val runner: ProotShellRunner) : ProotProcessHost {
    override fun launch(context: WorkspaceShellContext, log: File): Process {
        // 合并输出在 guest 内完成，仍由唯一 runner 拼 proot argv / env / bind；文件从第一字节起持续可读。
        val redirected = context.copy(command = "exec >${shellQuote("/root/.min/exec-logs/${log.name}")} 2>&1\n${context.command}")
        return runner.launch(redirected) ?: error(runner.checkAvailability(redirected) ?: "无法启动 proot")
    }
    override fun reap(process: Process) { ProcessTreeKill.reap(process) }
    override fun pid(process: Process): Int? = ProcessTreeKill.pidOf(process)
}

internal class ProotExec(private val base: WorkspaceShellContext, private val host: ProotProcessHost) : Exec {
    override val guestIsolation = dev.min.agent.policy.GuestIsolationPolicy.forCommand()
    private val running = ConcurrentHashMap<String, Running>()
    private val logDir = File(base.linuxDir, "root/.min/exec-logs")

    override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult = withContext(Dispatchers.IO) {
        // 模型 Bash 不接收任意 env；只有 hook 执行器的独立参数槽可以经进程启动口运输真值。
        // TRACEPARENT 是例外：内核在遥测开着时按 W3C 格式给出（trace id 与 span id 都是哈希出的十六进制，不含任何用户内容），值不合格式一样拒绝。
        require(command.env.all { (k, v) ->
            k in setOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "all_proxy", "no_proxy") ||
                Regex("MIN_PLUGIN_[0-9]+_(?:ARG|ENV)_[0-9]+").matches(k) ||
                (k == "TRACEPARENT" && Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}").matches(v))
        }) { "Min Bash 仅接受宿主代理、W3C TRACEPARENT 及插件 hook 参数环境" }
        require(options.timeoutMs > 0 && options.maxOutputChars >= 0)
        val started = System.nanoTime()
        val id = UUID.randomUUID().toString().replace("-", "")
        check(logDir.mkdirs() || logDir.isDirectory)
        val log = File(logDir, "$id.log").apply { createNewFile() }
        // 结束目录写进 guest 里的临时文件、不走输出流（[ShellCwdProbe]）：日志与通知里不会出现标记，输出截断也丢不了
        val cwdFile = if (command.trackCwd) File(logDir, "$id.cwd") else null
        val script = if (cwdFile != null) ShellCwdProbe.wrap(command.script, "/root/.min/exec-logs/$id.cwd") else command.script
        val process = host.launch(agentCommandContext(base, command.cwd, script).copy(env = command.env,
            isolation = dev.min.agent.policy.GuestIsolationPolicy.forCommand(command.network)), log)
        val task = Running(id, process, log, options.timeoutMs, cwdFile) { running.remove(id) }
        running[id] = task
        task.start()
        try {
            process.outputStream.close()
            val foreground = options.foregroundBudgetMs
            val budget = minOf(foreground ?: options.timeoutMs, options.timeoutMs).coerceAtLeast(0)
            val code = if (budget == 0L) null else withTimeoutOrNull(budget) { task.exit.await() }
            when {
                code != null -> task.result(options.maxOutputChars, (System.nanoTime() - started) / 1_000_000)
                foreground != null && foreground < options.timeoutMs ->
                    ExecResult.Backgrounded(ExecHandle(id, host.pid(process), GuestPath("/root/.min/exec-logs/$id.log")),
                        clippedLog(log, options.maxOutputChars).first)
                else -> {
                    task.reap()
                    val (output, truncated) = clippedLog(log, options.maxOutputChars)
                    ExecResult.TimedOut(output, truncated)
                }
            }
        } catch (e: CancellationException) {
            task.reap()
            throw e
        } catch (e: Throwable) {
            task.reap()
            throw e
        }
    }

    override suspend fun kill(handle: ExecHandle) = withContext(Dispatchers.IO) {
        // 只回收本实例拥有的进程，重启后的旧 pid 可能已被系统复用，不能盲杀。
        running[handle.id]?.reap()
        Unit
    }

    /** 按自己登记的 Process 判断存活，不起探测进程（N3）；表里没有 = 已被等待线程摘除 = 不在跑 */
    override suspend fun isAlive(handle: ExecHandle): Boolean? = withContext(Dispatchers.IO) {
        // 看「还在不在运行表里」而不是 `process.isAlive`：进程一退 isAlive 立刻为假，而等待线程要稍后（Android 的
        // waitFor 是轮询的，实测要晚上几百毫秒）才记下退出码，[exitOf] 在这段空档里是 null——模拟器上后台命令结束的通知
        // 就因此写成了「拿不到退出码」。表项在退出码记下之后才摘，两件事就一致了
        running.containsKey(handle.id)
    }

    override suspend fun exitOf(handle: ExecHandle): Int? = exits[handle.id]

    /** 最近结束的后台进程的退出码；只留 [MAX_REMEMBERED_EXITS] 条，长会话里表不能一直长 */
    private val exits = java.util.Collections.synchronizedMap(object : java.util.LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > MAX_REMEMBERED_EXITS
    })

    /** Android 33 以下没有 `ProcessHandle.current()`；owner 记号要的是本进程 pid，宿主侧自己知道 */
    override fun currentPid(): Long = android.os.Process.myPid().toLong()

    /** 判 owner（前一世的 App 进程）死活：读宿主自己的 /proc，不起探测进程。目录项在就是活着 */
    override suspend fun isPidAlive(pid: Long): Boolean? = withContext(Dispatchers.IO) {
        java.io.File("/proc/$pid").exists()
    }

    private inner class Running(val id: String, val process: Process, val log: File, val timeoutMs: Long, val cwdFile: File?, val done: () -> Unit) {
        val exit = CompletableDeferred<Int>()
        /** 命令跑完时 shell 所在的 guest 目录；进程一退就在等待线程里读走并删文件（转后台事后才结束的也一样不留），早于 [exit] 完成 */
        @Volatile var endCwd: GuestPath? = null
        private val reaped = AtomicBoolean()
        @Volatile private var timedOut = false

        fun reap() { if (reaped.compareAndSet(false, true)) host.reap(process) }

        fun start() {
            // guest 已把输出写文件，宿主仅排空 proot 诊断管道，避免管道满后整条命令卡住。
            listOf(process.inputStream, process.errorStream).forEach { stream ->
                Thread({ try { stream.use { it.copyTo(object : java.io.OutputStream() {
                    override fun write(b: Int) = Unit
                    override fun write(b: ByteArray, off: Int, len: Int) = Unit
                }) } } catch (_: IOException) {} },
                    "min-proot-drain").apply { isDaemon = true; start() }
            }
            Thread({
                try {
                    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { timedOut = true; reap() }
                    val code = process.waitFor()
                    cwdFile?.let { endCwd = takeCwdFile(it) }
                    // 先记退出码、再由 done 摘出运行表：[isAlive] 一说「不在了」，[exitOf] 就必须已经查得到
                    exits[id] = code
                    exit.complete(code)
                } catch (e: Throwable) { exit.completeExceptionally(e) }
                finally { done() }
            }, "min-proot-wait").apply { isDaemon = true; start() }
        }

        fun result(max: Int, duration: Long): ExecResult {
            val (output, clipped) = clippedLog(log, max)
            return if (timedOut) ExecResult.TimedOut(output, clipped)
            else ExecResult.Exited(exit.getCompleted(), output, clipped, duration, endCwd = endCwd)
        }
    }
}

private const val MAX_REMEMBERED_EXITS = 256

/** 读出探针写的目录并删掉文件；读不到、不是路径（脚本 `exit` 了没写、写了半截）都是 null——当作这次没换目录 */
internal fun takeCwdFile(file: File): GuestPath? {
    try {
        return ShellCwdProbe.parse(file.readText(Charsets.UTF_8))?.takeIf { it.startsWith("/") }?.let(::GuestPath)
    } catch (_: IOException) {
        return null
    } finally {
        file.delete()
    }
}

/** 后台日志可能很大，只读取有界头尾，UTF-8 解码在各自边界容忍半个字符。 */
internal fun clippedLog(file: File, max: Int): Pair<String, Boolean> = java.io.RandomAccessFile(file, "r").use { input ->
    val byteBudget = max.toLong() * 4
    val size = input.length()
    if (size <= byteBudget) {
        val bytes = ByteArray(size.toInt()).also { input.readFully(it) }
        val text = bytes.toString(Charsets.UTF_8)
        if (text.length <= max) text to false else clipText(text, max) to true
    } else {
        val half = (byteBudget / 2).toInt()
        val head = ByteArray(half).also { input.readFully(it) }.toString(Charsets.UTF_8).take(max / 2)
        input.seek(size - half)
        val tail = ByteArray(half).also { input.readFully(it) }.toString(Charsets.UTF_8).takeLast(max - max / 2)
        "$head\n…（中间输出已省略，全文见日志）…\n$tail" to true
    }
}

private fun clipText(text: String, max: Int): String =
    text.take(max / 2) + "\n…（中间输出已省略，全文见日志）…\n" + text.takeLast(max - max / 2)
