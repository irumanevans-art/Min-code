package dev.min.code.core.claudecode

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.workspace.ProcessTreeKill
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * 跑着的 `claude` 进程的两头：stdout 按行解析成事件、stderr 按行转交、stdin 串行写，
 * 关的时候先关 stdin 让它自己退，不退再收整棵进程树。
 *
 * 从 [ClaudeCodeManager] 里整段搬出来的。**和 Manager 同寿命**，不是一个进程一个：
 * 写队列只有一条、永远写给**当前**那个进程的 stdin —— 换档时旧进程收尾、新进程接上，
 * 中间入队的行照旧按先后写出去，这和搬出来之前一模一样。
 *
 * 状态怎么变不归这里管：事件、stderr、读写失败、退出都经构造参数里的回调交回 Manager。
 * 这里只保证两件时序上的事 —— [stopping] 先置位再关 stdin；以及只有**当前**进程的退出、
 * stderr 才被当成「这个会话」的事。
 */
class ClaudeCodeCliPipe(
    /** Manager 的作用域：写队列的消费者跑在这上面，活得和 Manager 一样久 */
    scope: CoroutineScope,
    /**
     * stdout 解析出来的每个事件，带上吐出它的那个进程。抛出的异常就地记日志，不让读循环停下。
     * 进程身份要传出去：换档时旧进程的读循环还活着，它的回调不能被答进新进程的 stdin。
     */
    private val onEvent: (ClaudeCodeEvent, Process) -> Unit,
    /** stderr 的一行；[current] = 发出它的就是当前这个进程（换档时旧进程的遗言不算） */
    private val onStderr: (line: String, current: Boolean) -> Unit,
    /** 读 stdout 本身失败了（不是主动停止造成的） */
    private val onReadFailure: (Exception) -> Unit,
    /** 写 stdin 失败了（不是停止 / 换档时迟到的写） */
    private val onWriteFailure: (Throwable) -> Unit,
    /** 当前进程退出了。[unexpected] = 不是我们关的，且退出码非 0 */
    private val onExit: (code: Int?, unexpected: Boolean) -> Unit,
    /** 会话 IO 协程里没接住的异常 */
    private val onUncaught: (Throwable) -> Unit,
) {
    /**
     * stdin 的写入队列。**入队顺序就是字节顺序**。
     *
     * 以前是 `scope.launch { writeMutex.withLock { … } }`：两次 launch 落在 `Dispatchers.IO`
     * 的不同线程上，谁先摸到锁并不保证——两条紧挨着发出的消息有可能在 CLI 那边前后颠倒。
     * 追加消息现在是立刻交棒的，顺序正是这次要保证的东西，所以改成单消费者串行写。
     */
    private val writeQueue = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (line in writeQueue) writeNow(line)
        }
    }

    /** 当前这个进程。换档时旧进程的读循环还活着，调用方靠它判断事件是不是来自当前进程 */
    @Volatile
    var process: Process? = null
        private set

    @Volatile
    private var writer: OutputStreamWriter? = null

    /** 每个会话一组 IO 协程，停止时整组取消 */
    @Volatile
    var sessionScope: CoroutineScope? = null
        private set

    /**
     * 正在主动停止。destroy() 会把 stdout 关掉，readLoop 随即抛 IOException ——
     * 没有这个标志的话，用户点"停止"会被报成"会话中断"。
     */
    @Volatile
    var stopping: Boolean = false
        private set

    /**
     * 接上一个刚起来的进程：它成了「当前」进程，stdin 从此写给它。返回这个会话的 IO 作用域。
     * 读还没开始 —— 调用方先把状态改成 Running 再调 [startReading]，读到的第一帧才不会落在 Starting 上。
     */
    fun attach(proc: Process): CoroutineScope {
        process = proc
        writer = proc.outputStream.writer(Charsets.UTF_8)
        val io = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> onUncaught(e) }
        )
        sessionScope = io
        stopping = false
        return io
    }

    /** 开始读 stdout / stderr，并盯着它退出 */
    fun startReading(proc: Process) {
        val io = sessionScope ?: return
        io.launch { readLoop(proc) }
        io.launch { drainStderr(proc) }
        io.launch {
            val code = runCatching { proc.waitFor() }.getOrNull()
            Log.i(TAG, "claude exited with code $code")
            // 只有**当前这个**进程的退出才说得上"会话结束了"。[detach] 一进门就把 process
            // 置 null，所以我们主动关掉的旧进程走到这里必然 `proc !== process`。
            // 不挡的话，换档重启会被它盖一下：那时状态已经是 Starting（见 Manager.relaunchWith ①），
            // 被盖成 Closed 之后界面闪一下启动面板，这期间用户发的消息还会被 send
            // 当成"会话已关"直接退回输入框。真正关会话的几条路（stopSession / startSession /
            // openSession）本来就各自写了自己的终态，不靠这里。
            if (proc !== process) return@launch
            // 主动停止时 destroy() 必然给出非 0 退出码，别把它报成错误
            onExit(code, !stopping && code != null && code != 0)
        }
    }

    /**
     * stdout 读取循环。
     *
     * **每一行单独 try/catch**：解析一行失败绝不能让整个循环退出。之前 try 包在 while 外面，
     * 于是任何一个字段形状不符（CLI 版本漂移就会发生）都会抛出 IllegalArgumentException，
     * 循环直接 break —— CLI 进程还活着、还在往 stdout 写，App 却再也不读了。表现就是
     * "会话中断: Element class kotlinx.serialization.json.JsonLiteral is not a JsonArray"，
     * 而且此后所有 control_request 都收不到应答（切模型/切权限模式全部超时报"CLI 未应答"）。
     *
     * 只有**读流本身**失败（进程死了、管道关了）才是真的会话中断。
     */
    private fun readLoop(proc: Process) {
        try {
            BufferedReader(proc.inputStream.reader(Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val events = try {
                        parseClaudeCodeEvents(line)
                    } catch (e: Exception) {
                        // 把原始行记下来：不然连"是哪个帧炸的"都无从查起
                        Log.e(TAG, "failed to parse line: ${line.take(1000)}", e)
                        continue
                    }
                    if (events.isEmpty()) {
                        Log.d(TAG, "unhandled line: ${line.take(200)}")
                    }
                    events.forEach { event ->
                        try {
                            onEvent(event, proc)
                        } catch (e: Exception) {
                            Log.e(TAG, "failed to dispatch $event", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (stopping) {
                Log.d(TAG, "readLoop closed by stopSession")
                return
            }
            Log.e(TAG, "readLoop error", e)
            onReadFailure(e)
        }
    }

    /**
     * stderr 按行读，实时反映启动期的报错。
     * 用 readText() 会一路阻塞到进程退出为止，长会话里等于没有诊断信息。
     *
     * 每一行连同「是不是当前进程发的」一起交出去：换档时状态先改 Starting、旧进程随后才慢慢退
     * （见 Manager.relaunchWith 的 ①②），旧进程退出时打的那几行常规噪声正好撞在 Starting 上，
     * 会被当成新进程起不来的原因刷成红字。只有**当前这个**进程的 stderr 才说明得了启动。
     */
    private fun drainStderr(proc: Process) {
        try {
            BufferedReader(proc.errorStream.reader(Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    Log.w(TAG, "claude stderr: $line")
                    onStderr(line, proc === process)
                }
            }
        } catch (_: Exception) {
            // 进程被杀时流会关闭，忽略
        }
    }

    /**
     * 关进程的前半段：置 [stopping]、把进程和 IO 作用域摘下来。之后读循环的报错、
     * 旧进程的退出都不再算「这个会话」的事。
     *
     * 和 [close] 分成两步，是因为 Manager 要在中间清自己的发送队列 —— 原来的 shutdown
     * 就是这个顺序：先置位摘下，再清队列，最后才关 stdin、等它退。
     */
    fun detach(): Detached {
        stopping = true
        val detached = Detached(process, sessionScope)
        process = null
        sessionScope = null
        return detached
    }

    class Detached internal constructor(internal val process: Process?, internal val io: CoroutineScope?)

    /**
     * 真正杀掉进程并回收 IO 协程。
     *
     * 注意进程是 [detach] 时捕获的局部值 —— 旧实现在异步块里再读 `process`，
     * 那时字段已经是 null 了，destroyForcibly() 永远不会执行。
     */
    suspend fun close(detached: Detached) {
        val proc = detached.process
        // 关掉 stdin 就是 stream-json 模式约定的优雅退出信号。
        // **必须给它时间自己退** —— transcript 是 CLI 退出前才落盘的，
        // 直接 destroy() 会让这一轮的 ~/.claude/projects/<cwd>/<id>.jsonl 根本没写出来，
        // 于是"开了好几次会话，历史里却只有一条"。
        runCatching { writer?.close() }
        writer = null

        if (proc != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (!proc.waitFor(GRACEFUL_EXIT_MS, TimeUnit.MILLISECONDS)) {
                        // 自己不退才动手。不能指望 destroy() / destroyForcibly()：在 Android 上
                        // 两个都只是 SIGTERM，proot 扛得住；要先自底向上杀 guest 树再杀宿主
                        // （ProcessTreeKill 类注释，和 Codex / 本地服务同一条路）
                        val reaped = ProcessTreeKill.reap(proc)
                        if (reaped == null) {
                            Log.w(TAG, "shutdown: no host pid, fell back to destroy()")
                        } else {
                            Log.i(TAG, "shutdown: reaped tree=${reaped.first.tree.size} host=${reaped.second}")
                        }
                        proc.waitFor(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)
                    }
                }
            }
        }
        detached.io?.cancel()
    }

    fun write(line: String) {
        val accepted = writeQueue.trySend(line).isSuccess
        if (!accepted) Log.w(TAG, "writeLine: write queue closed")
    }

    /** 只在写入队列那一个消费者协程里跑，所以这里不需要再上锁 */
    private fun writeNow(line: String) {
        val target = writer
        if (target == null) {
            Log.w(TAG, "writeLine: no active writer")
            return
        }
        runCatching {
            target.write(line)
            target.write("\n")
            target.flush()
        }.onFailure { e ->
            // detach 先置 stopping 再由 close 关 stdin，停止 / 换档时迟到的写必然失败；
            // writer 已换人则说明失败的是上一个进程的管道 —— 这两种都不是「这个会话坏了」，
            // 何况此刻的状态可能已经属于下一个会话
            if (stopping || target !== writer) {
                Log.d(TAG, "writeLine dropped: session is closing", e)
                return@onFailure
            }
            Log.e(TAG, "writeLine failed", e)
            onWriteFailure(e)
        }
    }

    private companion object {
        // 和 Manager 共用一个 TAG：排查时 `logcat -s ClaudeCodeManager` 一条命令就能看全
        const val TAG = "ClaudeCodeManager"
        const val SHUTDOWN_GRACE_MS = 2_000L

        /**
         * 关掉 stdin 之后留给 CLI 自己退出的时间。它要在退出前把 transcript 落盘
         * （~/.claude/projects/<cwd>/<session-id>.jsonl），强杀就会丢这一整个会话记录。
         */
        const val GRACEFUL_EXIT_MS = 4_000L
    }
}
