package dev.min.agent.cli

import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ShellCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [Exec] 的桌面实现：在本机起 shell 跑命令。只给桌面用（Android 走 proot，另有实现）。
 *
 * - [hostDirOf] 把会话的 guest cwd 映射成本机目录（Linux/macOS/WSL 上是恒等映射）。
 * - **输出总是同时写进 [logDir] 下以句柄 id 命名的日志文件**：命令转后台后模型还要靠 Read 看它，
 *   所以前台阶段就写，转后台后继续写，不必事后补。[guestLogDir] 是这个目录在 guest 里的路径（桌面 CLI 会把它挂进 RootfsFileSystem）。
 * - stdout 与 stderr 合并成一条流，顺序即到达顺序；stdin 立刻关掉，读 stdin 的命令拿到 EOF 而不是永远挂着。
 * - 杀进程先取整棵树的快照；Windows 再用 msys 进程表补全并反复回收，否则杀掉 bash 后孙进程会成孤儿继续跑。
 */
class LocalExec(
    private val hostDirOf: (GuestPath) -> Path,
    private val logDir: Path,
    private val guestLogDir: GuestPath,
    private val shell: List<String> = listOf("bash", "-lc"),
) : Exec {

    /** 还在跑的进程；进程结束（无论前台后台）由等待线程摘除 */
    private val running = ConcurrentHashMap<String, Pair<Process, LocalExecLaunch>>()

    override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        Files.createDirectories(logDir)
        val logFile = logDir.resolve("$id.log")

        val launch = LocalExecLaunch(shell, logDir, id)
        val process = try {
            val builder = ProcessBuilder(launch.argv(command.script))
                .directory(hostDirOf(command.cwd).toFile())
                .redirectErrorStream(true)
            builder.environment().putAll(command.env)
            builder.start()
        } catch (e: Throwable) {
            launch.cleanup()
            throw e
        }
        val proc = try {
            process.outputStream.close()
            running[id] = process to launch
            Running(id, process, logFile, options.maxOutputChars) {
                try {
                    launch.cleanup()
                } finally {
                    running.remove(id)
                }
            }
        } catch (e: Throwable) {
            launch.killTree(process)
            launch.cleanup()
            running.remove(id)
            throw e
        }

        try {
            val budget = minOf(options.foregroundBudgetMs ?: options.timeoutMs, options.timeoutMs)
            val foreground = options.foregroundBudgetMs
            val backgroundable = foreground != null && foreground < options.timeoutMs
            val exitCode = if (budget <= 0) null else withTimeoutOrNull(budget) { proc.exit.await() }
            when {
                exitCode != null -> {
                    val (output, truncated) = proc.snapshot()
                    ExecResult.Exited(exitCode, output, truncated, (System.nanoTime() - startedAt) / 1_000_000)
                }
                backgroundable -> {
                    val handle = ExecHandle(id, pidOf(process), GuestPath(guestLogDir.value.trimEnd('/') + "/$id.log"))
                    ExecResult.Backgrounded(handle, proc.snapshot().first)
                }
                else -> {
                    launch.killTree(process)
                    proc.awaitFinished()
                    val (output, truncated) = proc.snapshot()
                    ExecResult.TimedOut(output, truncated)
                }
            }
        } catch (e: CancellationException) {
            // 外层取消了：不能把进程留在后台跑
            launch.killTree(process)
            proc.awaitFinished()
            throw e
        }
    }

    override suspend fun kill(handle: ExecHandle) {
        val (process, launch) = running[handle.id] ?: return
        withContext(Dispatchers.IO) { launch.killTree(process) }
    }

    private fun pidOf(process: Process): Int? = try {
        process.pid().toInt()
    } catch (_: UnsupportedOperationException) {
        null
    }

    /**
     * 一个进程的运行状态：泵线程把输出同时写日志与内存收集器，等待线程在进程退出后（并给泵一点时间读完管道）给出退出码。
     * 用两条守护线程而不是协程：阻塞读管道与 waitFor 都是不可取消的阻塞调用，放在协程里会占住 IO 线程池。
     */
    private class Running(
        val id: String,
        private val process: Process,
        logFile: Path,
        maxChars: Int,
        onDone: () -> Unit,
    ) {
        val exit = CompletableDeferred<Int>()
        private val collector = OutputCollector(maxChars)
        private val pump: Thread
        private val waiter: Thread

        init {
            val log = Files.newOutputStream(logFile)
            pump = Thread({
                try {
                    val source = Tee(process.inputStream, log)
                    val reader = InputStreamReader(source, Charsets.UTF_8)
                    val buf = CharArray(8192)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        collector.append(buf, n)
                    }
                } catch (_: java.io.IOException) {
                    // 管道被杀掉时断开是正常结束
                } finally {
                    try {
                        log.close()
                    } catch (_: java.io.IOException) {
                    }
                }
            }, "local-exec-pump-$id").apply { isDaemon = true; start() }
            waiter = Thread({
                val code = try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    -1
                }
                // 后台子孙可能还攥着管道写端，EOF 迟迟不来：最多等 2 秒把已到的输出读完
                pump.join(DRAIN_MS)
                try {
                    onDone()
                } finally {
                    exit.complete(code)
                }
            }, "local-exec-wait-$id").apply { isDaemon = true; start() }
        }

        fun awaitFinished() {
            waiter.join(DRAIN_MS * 2)
        }

        fun snapshot(): Pair<String, Boolean> = collector.snapshot()
    }

    /** 读多少就往日志写多少并立刻落盘，`tail` 与 Read 才看得到实时进展 */
    private class Tee(private val source: InputStream, private val sink: java.io.OutputStream) : InputStream() {
        override fun read(): Int {
            val b = source.read()
            if (b >= 0) sink.write(b)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = source.read(b, off, len)
            if (n > 0) {
                sink.write(b, off, n)
                sink.flush()
            }
            return n
        }
    }

    /** 内存里只留头尾各一半：后台跑几小时的命令不会把内存吃光，完整内容在日志文件里 */
    private class OutputCollector(private val maxChars: Int) {
        private val headCap = maxChars / 2
        private val tailCap = maxChars - headCap
        private val head = StringBuilder()
        private val tail = StringBuilder()
        private var total = 0L

        @Synchronized
        fun append(buf: CharArray, n: Int) {
            total += n
            var from = 0
            if (head.length < headCap) {
                from = minOf(n, headCap - head.length)
                head.append(buf, 0, from)
            }
            if (from < n) {
                tail.append(buf, from, n - from)
                if (tail.length > tailCap * 2) tail.delete(0, tail.length - tailCap)
            }
        }

        @Synchronized
        fun snapshot(): Pair<String, Boolean> {
            if (total <= maxChars) return (head.toString() + tail.toString()) to false
            val keptTail = tail.substring(maxOf(0, tail.length - tailCap))
            val omitted = total - head.length - keptTail.length
            return (head.toString() + "\n…（中间省略了 $omitted 个字符）…\n" + keptTail) to true
        }
    }

    private companion object {
        const val DRAIN_MS = 2_000L
    }
}
