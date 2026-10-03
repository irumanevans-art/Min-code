package dev.min.agent.host

import dev.min.agent.spi.LiveProcess
import dev.min.agent.spi.ProcessSpawnException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 一个进程的整棵后代树，在关停时用来回收。**进程树不是进程**：父进程一死，子孙就改挂到 init 下，
 * 事后再查就找不到了（LocalExec 与 ProcessTreeKill 都踩过），所以必须趁父进程还活着取快照；
 * 快照只增不减，之后每次 [refresh] 再并入新出现的后代。
 */
interface ProcessTree {
    /** 重新发现后代并并入快照。可能较慢（要扫进程表），所以与廉价的 [anyAlive] 分开：关停的每一档开始前调一次 */
    fun refresh()

    /** 对快照里还活着的每个成员发 SIGTERM（Windows 上等同强杀） */
    fun terminate()

    /** 对快照里还活着的每个成员发 SIGKILL */
    fun kill()

    /** 快照里（含根）是否还有活着的成员。只看已知成员，不重新发现，可以高频轮询 */
    fun anyAlive(): Boolean

    /** 回收彻底做完，不会再 [refresh] 了：释放发现手段占着的东西（比如临时文件） */
    fun close() {}
}

/** 进程树的回收手段。桌面用 [HandleProcessReaper]（`ProcessHandle`），Android 用 /proc 自底向上（见 app 的 ProotProcessPort） */
fun interface ProcessReaper {
    /** 在 [process] 还活着时取快照 */
    fun capture(process: Process): ProcessTree
}

/**
 * 基于 `ProcessHandle.descendants()` 的回收：桌面（Windows / Linux / macOS）通用。
 *
 * [newDiscoverer] 造平台补充的发现函数：给它目前已知的成员 pid，返回它还能找到的其它后代 pid。Windows 上 Git Bash（msys）的
 * fork + exec 会让孙进程在 Windows 眼里没有父进程（`ProcessHandle.parent()` 为空），`descendants()` 看不到它们，
 * 只有 msys 自己的进程表（`ps -W`）认得——CLI 在那里传入 msys 版本的发现函数。
 */
class HandleProcessReaper(
    /** 每个进程树一份：发现函数可以带状态（比如记住已经见过的 msys pid，父进程死后仍能顺藤摸瓜） */
    private val newDiscoverer: () -> (known: List<Long>) -> List<Long> = { { emptyList() } },
    /** 回收做完时调（见 [ProcessTree.close]） */
    private val onClose: () -> Unit = {},
) : ProcessReaper {
    override fun capture(process: Process): ProcessTree = HandleTree(process.toHandle(), newDiscoverer(), onClose)

    private class HandleTree(
        private val root: ProcessHandle,
        private val discover: (List<Long>) -> List<Long>,
        private val onClose: () -> Unit,
    ) : ProcessTree {
        private val members = LinkedHashMap<Long, ProcessHandle>()

        init {
            addTree()
        }

        /** 根与所有还活着的已知成员的 Java 层后代 */
        @Synchronized
        private fun addTree() {
            members[root.pid()] = root
            // 只展开还活着的成员：已死成员的子孙查不到，但它们在更早的快照里已经记下了
            for (member in members.values.toList()) {
                if (member.isAlive) member.descendants().forEach { members.putIfAbsent(it.pid(), it) }
            }
        }

        @Synchronized
        override fun refresh() {
            addTree()
            val known = members.values.filter { it.isAlive }.map { it.pid() }
            for (pid in discover(known)) {
                if (pid in members) continue
                ProcessHandle.of(pid).ifPresent { members[pid] = it }
            }
            addTree()
        }

        @Synchronized
        private fun alive(): List<ProcessHandle> = members.values.filter { it.isAlive }

        override fun terminate() = alive().forEach { it.destroy() }

        override fun kill() = alive().forEach { it.destroyForcibly() }

        override fun anyAlive(): Boolean = alive().isNotEmpty()

        override fun close() = onClose()
    }
}

/** 不带平台补充的默认回收（Linux / macOS，以及测试） */
val JvmProcessReaper: ProcessReaper = HandleProcessReaper()

/**
 * 把一个 [Process] 包成 [LiveProcess]。桌面与 Android 共用，两边只有「怎么起进程」与「怎么回收树」不同。
 *
 * - 输出由两条守护泵线程读（阻塞读管道不能占协程线程池）：stdout 有界缓冲、满了就让管道反压；
 *   stderr 满了丢最旧的块（只是日志）。流在进程退出且泵读完（最多等 [DRAIN_MS]）后关闭。
 * - 关停顺序见 [shutdown]；[abort] 是不能等的硬回收（调用方被取消时用）。
 */
class JvmLiveProcess(
    private val process: Process,
    private val reaper: ProcessReaper,
    val label: String,
    private val graceMs: Long = GRACE_MS,
) : LiveProcess {
    private val stdoutChannel = Channel<ByteArray>(STDOUT_CHUNKS)
    private val stderrChannel = Channel<ByteArray>(STDERR_CHUNKS, BufferOverflow.DROP_OLDEST)
    private val exitCode = CompletableDeferred<Int>()
    private val stdinLock = Mutex()
    private val tree: ProcessTree = reaper.capture(process)
    private val shutdownStarted = AtomicBoolean()
    private val shutdownDone = CompletableDeferred<Unit>()

    @Volatile
    private var stopped = false
    private val pumps: List<Thread>

    override val stdout: Flow<ByteArray> = stdoutChannel.receiveAsFlow()
    override val stderr: Flow<ByteArray> = stderrChannel.receiveAsFlow()
    override val exit: Deferred<Int> get() = exitCode

    init {
        pumps = listOf(
            pump(process.inputStream, stdoutChannel, "live-out-$label"),
            pump(process.errorStream, stderrChannel, "live-err-$label"),
        )
        Thread({
            val code = try {
                process.waitFor()
            } catch (_: InterruptedException) {
                -1
            }
            // 后台孙进程可能还攥着管道写端、EOF 迟迟不来：最多等一会儿把已到的输出读完
            pumps.forEach { it.join(DRAIN_MS) }
            closeStreams()
            exitCode.complete(code)
        }, "live-wait-$label").apply { isDaemon = true; start() }
    }

    override suspend fun write(bytes: ByteArray) {
        stdinLock.withLock {
            withContext(Dispatchers.IO) {
                val out = process.outputStream
                out.write(bytes)
                out.flush()
            }
        }
    }

    override suspend fun closeStdin() {
        withContext(Dispatchers.IO) {
            try {
                process.outputStream.close()
            } catch (_: IOException) {
                // 进程已经没了，管道早断了
            }
        }
    }

    override suspend fun shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            withContext(NonCancellable) { shutdownDone.await() }
            return
        }
        // 收尾不能被取消打断：半路停下就会留下进程
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                orderlyShutdown()
            } finally {
                shutdownDone.complete(Unit)
            }
        }
    }

    private suspend fun orderlyShutdown() {
        // 关 stdin 可能卡在一个写到一半的 write 持有的锁上（对端不读、管道满），所以放到独立线程，不等它
        Thread({
            try {
                process.outputStream.close()
            } catch (_: IOException) {
            }
        }, "live-stdin-close-$label").apply { isDaemon = true; start() }
        // 趁根进程还活着取一次完整快照：它一退出，孙进程就找不到爸爸了
        tree.refresh()
        // 第一档：等进程自己退出（规范：服务器收到 stdin 关闭应当自行退出）
        process.waitFor(graceMs, TimeUnit.MILLISECONDS)
        // 第二档：进程退了也要扫一遍后代——它们是孤儿了，没人会替我们收
        if (tree.anyAlive()) {
            tree.refresh()
            tree.terminate()
            awaitTreeGone(graceMs)
        }
        // 第三档：SIGKILL
        if (tree.anyAlive()) {
            tree.refresh()
            tree.kill()
            awaitTreeGone(graceMs)
        }
        // 收尾再扫：刚好在被杀前 fork 出来的孩子，要下一轮才发现得了
        sweepKill()
        tree.close()
        pumps.forEach { it.join(DRAIN_AFTER_KILL_MS) }
        closeStreams()
        withTimeoutOrNull(DRAIN_MS) { exitCode.await() }
    }

    /** 反复「发现 + 杀」直到连续两轮树里都没有活的成员。阻塞调用，只在后台线程 / IO 线程里用 */
    private fun sweepKill() {
        var quiet = 0
        repeat(SWEEP_PASSES) {
            tree.refresh()
            if (tree.anyAlive()) {
                quiet = 0
                tree.kill()
                Thread.sleep(SWEEP_PAUSE_MS)
            } else if (++quiet >= 2) {
                return
            }
        }
    }

    /**
     * 不能等的硬回收：直接 SIGKILL 整棵树，不走礼貌的关停顺序。调用方 Job 被取消时用。
     * 可以从任何线程、在不可挂起的回调里调；树扫描放到独立线程，不拖慢调用方。
     */
    fun abort() {
        Thread({
            sweepKill()
            tree.close()
            closeStreams()
        }, "live-abort-$label").apply { isDaemon = true; start() }
    }

    private suspend fun awaitTreeGone(timeoutMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (tree.anyAlive() && System.nanoTime() < deadline) delay(POLL_MS)
    }

    /** 停泵并关两条流：已缓冲的块照常交给收集方，之后流正常结束 */
    private fun closeStreams() {
        stopped = true
        stdoutChannel.close()
        stderrChannel.close()
    }

    private fun pump(stream: InputStream, channel: Channel<ByteArray>, name: String): Thread =
        Thread({
            val buf = ByteArray(CHUNK_BYTES)
            try {
                while (!stopped) {
                    val n = stream.read(buf)
                    if (n < 0 || !offer(channel, buf.copyOf(n))) break
                }
            } catch (_: IOException) {
                // 进程被杀后管道断开是正常结束
            }
        }, name).apply { isDaemon = true; start() }

    /** 放进缓冲区；满了就分段等（等的间隙看一眼是否已停），返回 false 表示不必再读 */
    private fun offer(channel: Channel<ByteArray>, chunk: ByteArray): Boolean {
        while (!stopped) {
            val sent = channel.trySend(chunk)
            if (sent.isSuccess) return true
            if (sent.isClosed) return false
            try {
                if (runBlocking { withTimeoutOrNull(BACKPRESSURE_SLICE_MS) { channel.send(chunk); true } } == true) return true
            } catch (_: ClosedSendChannelException) {
                return false
            }
        }
        return false
    }

    companion object {
        /** 规范的 stdio 关停时限：每一档等 2 秒（Codex 同） */
        const val GRACE_MS = 2_000L
        private const val DRAIN_MS = 2_000L
        private const val DRAIN_AFTER_KILL_MS = 500L
        private const val POLL_MS = 25L
        private const val SWEEP_PASSES = 20
        private const val SWEEP_PAUSE_MS = 100L
        private const val CHUNK_BYTES = 16 * 1024
        private const val STDOUT_CHUNKS = 64
        private const val STDERR_CHUNKS = 256
        private const val BACKPRESSURE_SLICE_MS = 200L
    }
}

/**
 * 起进程并包成 [LiveProcess] 的公共流程：[start] 真正起进程（阻塞调用，会放到 IO 线程）。
 *
 * 取消：spawn 中途被取消，已经起来的进程立刻回收（`withContext` 返回时会补一次取消检查，结果会被丢掉，
 * 所以进程要先记在外面）；返回之后进程挂在调用方的 Job 上，Job 以取消或失败结束就硬回收，正常结束不管。
 */
suspend fun spawnLive(
    label: String,
    reaper: ProcessReaper,
    graceMs: Long = JvmLiveProcess.GRACE_MS,
    start: () -> Process,
): LiveProcess {
    val created = AtomicReference<JvmLiveProcess?>()
    try {
        withContext(Dispatchers.IO) {
            val process = try {
                start()
            } catch (e: ProcessSpawnException) {
                throw e
            } catch (e: IOException) {
                throw ProcessSpawnException("无法启动「$label」：${e.message}", e)
            }
            created.set(JvmLiveProcess(process, reaper, label, graceMs))
        }
        currentCoroutineContext().ensureActive()
    } catch (e: CancellationException) {
        created.get()?.abort()
        throw e
    }
    val live = checkNotNull(created.get())
    val binding = currentCoroutineContext()[Job]?.invokeOnCompletion { cause -> if (cause != null) live.abort() }
    if (binding != null) live.exit.invokeOnCompletion { binding.dispose() }
    return live
}
