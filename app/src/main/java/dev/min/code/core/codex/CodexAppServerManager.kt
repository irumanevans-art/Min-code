package dev.min.code.core.codex

import android.util.Log
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import dev.min.code.core.session.appendProcessOutputLine
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.workspace.ProcessTreeKill

fun interface CodexProcessLauncher {
    fun start(): Process
}

/**
 * 管一个 `codex app-server` 进程，把它的 JSONL 事件流转成聊天条目。
 *
 * ## 为什么状态里是 items 而不是一根 streamingText
 *
 * 上一版的 State 只有一个 `streamingText: String`，每次 `sendTurn` 清空 ——
 * 于是发第二条消息时，第一条的回答连同中间跑过的命令一起消失了，整页只剩最后
 * 一段还在生成的文字。它压根不是个会话界面。现在和 Claude 那边同构：
 * 一条 [ChatItem] 列表，增量只驱动「最后那条正在生成的气泡」。
 *
 * ## item 的生命周期
 *
 * `item/started` 建条目、各路 delta 打字、`item/completed` **整条替换**。
 * 官方文档把 completed 称作权威最终态，且明说计划正文允许和增量拼出来的不一致，
 * 所以这里绝不把 delta 攒出来的字符串当结果留下。
 *
 * ## 进程生命周期不归界面管
 *
 * 这个类以前 new 在 CodexVM 里，转个屏或退出页面就 `onCleared` → 进程被杀，
 * 一轮跑到一半的活直接没了。现在它是 DI 里的单例，页面只是它的观察者。
 */
class CodexAppServerManager(
    private val processLauncher: CodexProcessLauncher,
) : AutoCloseable {

    /** 启动一条线程时要交代清楚的事。默认值是手机上唯一说得通的那套。 */
    data class Options(
        val cwd: String = DEFAULT_CWD,
        val model: String? = null,
        val effort: String? = null,
        /**
         * 只读的话 Codex 连文件都改不了，全放开等于把 rootfs 交出去 ——
         * 工作区可写是唯一说得通的默认。
         */
        val sandbox: CodexSandbox = CodexSandbox.WORKSPACE_WRITE,
        val writableRoots: List<String> = listOf(DEFAULT_CWD),
        /** 关掉的话 guest 里 npm / pip 全部失败，而失败信息只会写在命令输出里 */
        val networkAccess: Boolean = true,
        val approvalPolicy: CodexApprovalPolicy = CodexApprovalPolicy.ON_REQUEST,
    )

    data class State(
        val status: SessionStatus = SessionStatus.Idle,
        val items: List<ChatItem> = emptyList(),
        val threadId: String? = null,
        val turnId: String? = null,
        /** 正在生成的正文。整条消息 completed 时清空并转成正式条目，UI 不要和 items 叠加 */
        val streamingText: String = "",
        val streamingThinking: String = "",
        val pendingApproval: CodexEvent.ApprovalRequest? = null,
        val busy: Boolean = false,
        val errorMessage: String? = null,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val totalTokens: Int? = null,
        val contextWindow: Int? = null,
        /** `thread/status/changed` 的 activeFlags，例如 waitingOnApproval */
        val activeFlags: List<String> = emptyList(),
        val turnStartedAt: Long? = null,
        val lastTurnDurationMs: Long? = null,
    ) {
        val canSend: Boolean get() = status == SessionStatus.Running && threadId != null && !busy
    }

    private enum class RequestKind { Initialize, Thread, Turn }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestIds = AtomicLong(0)
    private val lock = Any()
    private val pendingRequests = mutableMapOf<String, RequestKind>()
    private val _state = MutableStateFlow(State())

    val state: StateFlow<State> = _state.asStateFlow()

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private var waitJob: Job? = null
    /** 用户主动停的，用来区分"关掉了"和"死掉了" */
    private var stopping = false
    private var options = Options()
    private var resumeThreadId: String? = null
    /** 命令输出按 itemId 攒，delta 到达时刷进对应那张工具卡 */
    private val outputBuffers = mutableMapOf<String, StringBuilder>()
    /** 正在流式生成的那条正文 / 思考的 itemId，completed 时用来判断该清哪个缓冲 */
    private var streamingTextItemId: String? = null
    private var streamingThinkingItemId: String? = null

    /** 启动进程并走完 initialize → initialized → thread/start|resume。 */
    fun start(options: Options = Options(), resumeThreadId: String? = null): Boolean = synchronized(lock) {
        val status = _state.value.status
        if (process != null || status == SessionStatus.Starting || status == SessionStatus.Running) {
            return false
        }
        stopping = false
        this.options = options
        this.resumeThreadId = resumeThreadId
        outputBuffers.clear()
        streamingTextItemId = null
        streamingThinkingItemId = null
        _state.value = State(status = SessionStatus.Starting, threadId = resumeThreadId)

        val launched = runCatching { processLauncher.start() }.getOrElse { error ->
            failLocked(error.message ?: error::class.java.simpleName)
            return false
        }
        process = launched
        writer = BufferedWriter(OutputStreamWriter(launched.outputStream, Charsets.UTF_8))
        stdoutJob = readStdout(launched)
        stderrJob = readStderr(launched)
        waitJob = watchExit(launched)

        val id = nextRequestId()
        pendingRequests[id] = RequestKind.Initialize
        if (!writeLineLocked(encodeCodexInitialize(id))) {
            failLocked("写不进 initialize 请求")
            return false
        }
        true
    }

    /** 发一轮。初始化没走完（还没有 threadId）之前一律返回 false。 */
    fun sendTurn(input: String): Boolean = synchronized(lock) {
        val current = _state.value
        val threadId = current.threadId
        if (current.status != SessionStatus.Running || threadId == null || input.isBlank()) return false

        val id = nextRequestId()
        pendingRequests[id] = RequestKind.Turn
        // 用户那条先进列表，不等服务端回显 —— 手机上点了发送却半秒没动静会让人再点一次
        _state.value = current.copy(
            items = current.items + ChatItem.UserText(nextLocalId("user"), input),
            streamingText = "",
            streamingThinking = "",
            turnId = null,
            busy = true,
            errorMessage = null,
            turnStartedAt = System.currentTimeMillis(),
        )
        streamingTextItemId = null
        streamingThinkingItemId = null

        val line = encodeCodexTurnStart(
            requestId = id,
            threadId = threadId,
            text = input,
            model = options.model,
            effort = options.effort,
            sandbox = options.sandbox,
            writableRoots = options.writableRoots,
            networkAccess = options.networkAccess,
            approvalPolicy = options.approvalPolicy,
        )
        if (!writeLineLocked(line)) {
            pendingRequests.remove(id)
            failLocked("写不进 turn/start 请求")
            return false
        }
        true
    }

    /**
     * 应答审批。[decision] 必须在服务端给的 `availableDecisions` 里 ——
     * 回一个它不认的值不会报错，只会让这一轮一直停在那儿等。
     */
    fun answerApproval(decision: CodexDecision): Boolean = synchronized(lock) {
        val pending = _state.value.pendingApproval ?: return false
        val allowed = pending.availableDecisions
        if (allowed.isNotEmpty() && decision.wire !in allowed) return false
        if (!writeLineLocked(encodeCodexApprovalResponse(pending.requestId, decision))) return false
        _state.value = _state.value.copy(pendingApproval = null)
        true
    }

    /**
     * 停掉进程。
     *
     * 杀进程树要走 /proc 遍历加轮询等待，是**阻塞**活儿；以前它在 `synchronized` 块里，
     * 而 stop() 是界面上那个按钮直接调的 —— 主线程拿着锁去遍历 /proc，就是一次 ANR。
     * 现在锁里只做状态交接，真正的 kill 丢到 IO 上。
     */
    fun stop() {
        val doomed = synchronized(lock) { detachLocked(failed = false, message = null) }
        doomed?.let { killAsync(it) }
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    // -----------------------------------------------------------------------
    // 读循环
    // -----------------------------------------------------------------------

    private fun readStdout(activeProcess: Process): Job = scope.launch {
        runCatching {
            BufferedReader(InputStreamReader(activeProcess.inputStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line -> parseCodexEvent(line)?.let(::handleEvent) }
            }
        }.onFailure { Log.w(TAG, "codex stdout 读取结束", it) }
    }

    /**
     * stderr 不能只是排干 —— 那是 app-server 唯一会说「我为什么起不来」的地方
     * （缺 node、认证过期、端口被占）。收进聊天流，默认折叠。
     */
    private fun readStderr(activeProcess: Process): Job = scope.launch {
        runCatching {
            BufferedReader(InputStreamReader(activeProcess.errorStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    synchronized(lock) {
                        _state.value = _state.value.copy(
                            items = appendProcessOutputLine(_state.value.items, line, nextLocalId("stderr")),
                        )
                    }
                }
            }
        }.onFailure { Log.w(TAG, "codex stderr 读取结束", it) }
    }

    private fun watchExit(activeProcess: Process): Job = scope.launch {
        val exitCode = runCatching { activeProcess.waitFor() }.getOrNull() ?: return@launch
        synchronized(lock) {
            // 已经换了一个进程就不要去动新进程的状态
            if (process !== activeProcess) return@synchronized
            writer = null
            process = null
            pendingRequests.clear()
            _state.value = if (stopping) {
                _state.value.copy(status = SessionStatus.Closed, busy = false, pendingApproval = null)
            } else {
                _state.value.copy(
                    status = SessionStatus.Failed,
                    busy = false,
                    pendingApproval = null,
                    errorMessage = "codex app-server 退出，退出码 $exitCode",
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // 事件
    // -----------------------------------------------------------------------

    private fun handleEvent(event: CodexEvent) = synchronized(lock) {
        val current = _state.value
        when (event) {
            is CodexEvent.Response -> handleResponseLocked(event)

            is CodexEvent.ThreadStarted -> _state.value = current.copy(
                status = SessionStatus.Running,
                threadId = event.threadId,
                errorMessage = null,
            )

            is CodexEvent.TurnStarted -> _state.value = current.copy(
                turnId = event.turnId,
                busy = true,
                turnStartedAt = current.turnStartedAt ?: System.currentTimeMillis(),
            )

            is CodexEvent.TurnCompleted -> _state.value = current.copy(
                turnId = event.turnId ?: current.turnId,
                busy = false,
                // 一轮结束时把没来得及 completed 的增量收尾，不然它会一直挂在那儿
                streamingText = "",
                streamingThinking = "",
                items = current.items.settleStreaming(current),
                errorMessage = event.errorMessage ?: current.errorMessage,
                lastTurnDurationMs = current.turnStartedAt?.let { System.currentTimeMillis() - it },
                turnStartedAt = null,
            ).also { streamingTextItemId = null; streamingThinkingItemId = null }

            is CodexEvent.ItemStarted -> {
                val chat = event.item.toChatItem(nextLocalId("item"))
                _state.value = current.copy(items = current.items.upsert(chat))
            }

            is CodexEvent.ItemCompleted -> {
                val chat = event.item.toChatItem(nextLocalId("item"))
                outputBuffers.remove(event.item.id)
                // 权威最终态到了，对应的增量缓冲作废
                val clearText = event.item.id == streamingTextItemId || event.item.type == "agentMessage"
                val clearThinking = event.item.id == streamingThinkingItemId || event.item.type == "reasoning"
                if (clearText) streamingTextItemId = null
                if (clearThinking) streamingThinkingItemId = null
                _state.value = current.copy(
                    items = current.items.upsert(chat),
                    streamingText = if (clearText) "" else current.streamingText,
                    streamingThinking = if (clearThinking) "" else current.streamingThinking,
                )
            }

            is CodexEvent.AgentMessageDelta -> {
                streamingTextItemId = event.itemId ?: streamingTextItemId
                _state.value = current.copy(streamingText = current.streamingText + event.delta)
            }

            is CodexEvent.ReasoningDelta -> {
                streamingThinkingItemId = event.itemId ?: streamingThinkingItemId
                _state.value = current.copy(streamingThinking = current.streamingThinking + event.delta)
            }

            is CodexEvent.ReasoningPartAdded -> {
                val thinking = current.streamingThinking
                // 摘要分段之间空一行，否则几段会连成一堵墙
                if (thinking.isNotBlank() && !thinking.endsWith("\n\n")) {
                    _state.value = current.copy(streamingThinking = thinking.trimEnd() + "\n\n")
                }
            }

            // 命令输出**不进正文**，按 itemId 刷进那张 Bash 卡
            is CodexEvent.CommandOutputDelta -> {
                val itemId = event.itemId ?: return@synchronized
                val buffer = outputBuffers.getOrPut(itemId) { StringBuilder() }.append(event.delta)
                _state.value = current.copy(
                    items = current.items.map { item ->
                        if (item.id == itemId && item is ChatItem.ToolCall) {
                            item.copy(result = buffer.toString())
                        } else {
                            item
                        }
                    },
                )
            }

            is CodexEvent.PlanDelta ->
                _state.value = current.copy(streamingText = current.streamingText + event.delta)

            is CodexEvent.ApprovalRequest ->
                _state.value = current.copy(pendingApproval = event)

            is CodexEvent.TokenUsage -> _state.value = current.copy(
                inputTokens = event.inputTokens ?: current.inputTokens,
                outputTokens = event.outputTokens ?: current.outputTokens,
                totalTokens = event.totalTokens ?: current.totalTokens,
                contextWindow = event.contextWindow ?: current.contextWindow,
            )

            is CodexEvent.ThreadStatus ->
                _state.value = current.copy(activeFlags = event.activeFlags)

            is CodexEvent.Unknown -> Log.d(TAG, "未知的 codex 方法：${event.method}")
        }
    }

    private fun handleResponseLocked(response: CodexEvent.Response) {
        val kind = pendingRequests.remove(response.id) ?: return
        if (response.error != null) {
            val message = response.error.str("message") ?: "codex 请求 ${response.id} 失败"
            if (kind == RequestKind.Turn) {
                // 一轮失败不该把整条会话关掉，用户改一句还能接着问
                _state.value = _state.value.copy(busy = false, errorMessage = message)
            } else {
                failLocked(message)
            }
            return
        }
        when (kind) {
            RequestKind.Initialize -> {
                // 握手第二步：不发这条通知，后面每个请求都会被拒
                writeLineLocked(encodeCodexInitialized())
                val id = nextRequestId()
                pendingRequests[id] = RequestKind.Thread
                val request = resumeThreadId?.let { encodeCodexThreadResume(id, it) }
                    ?: encodeCodexThreadStart(
                        requestId = id,
                        cwd = options.cwd,
                        model = options.model,
                        effort = options.effort,
                        sandbox = options.sandbox,
                        writableRoots = options.writableRoots,
                        networkAccess = options.networkAccess,
                        approvalPolicy = options.approvalPolicy,
                    )
                if (!writeLineLocked(request)) failLocked("写不进 thread 请求")
            }

            RequestKind.Thread -> {
                val threadId = (response.result as? kotlinx.serialization.json.JsonObject)?.let { result ->
                    result.str("threadId") ?: result.obj("thread")?.str("id") ?: result.str("id")
                } ?: resumeThreadId
                if (threadId == null) {
                    failLocked("thread 应答里没有 id")
                } else {
                    _state.value = _state.value.copy(
                        status = SessionStatus.Running,
                        threadId = threadId,
                        errorMessage = null,
                    )
                }
            }

            RequestKind.Turn -> {
                val turnId = (response.result as? kotlinx.serialization.json.JsonObject)
                    ?.let { it.obj("turn")?.str("id") ?: it.str("turnId") }
                if (turnId != null) _state.value = _state.value.copy(turnId = turnId)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    /**
     * 把进程和状态摘下来，返回那个待杀的进程。**只做交接，不做等待** ——
     * 调用方拿到之后在 IO 上杀，见 [stop]。
     */
    private fun detachLocked(failed: Boolean, message: String?): Process? {
        stopping = !failed
        val active = process
        process = null
        runCatching { writer?.close() }
        writer = null
        pendingRequests.clear()
        outputBuffers.clear()
        stdoutJob?.cancel()
        stderrJob?.cancel()
        waitJob?.cancel()
        stdoutJob = null
        stderrJob = null
        waitJob = null
        _state.value = _state.value.copy(
            status = if (failed) SessionStatus.Failed else SessionStatus.Closed,
            busy = false,
            pendingApproval = null,
            streamingText = "",
            streamingThinking = "",
            errorMessage = message,
        )
        return active
    }

    /**
     * 先按 /proc 的 ppid 自底向上杀 guest 进程树，再杀 proot 宿主。
     *
     * 只 destroy() 宿主是不够的：Android 上 `destroyForcibly` 只发 SIGTERM，
     * 而 proot 底下的 node 收不到，会连着端口一起留下来。这条教训见
     * .learnings/LRN-20260918-ANDROID-KILL-LIMITS。
     */
    private fun killAsync(doomed: Process) {
        scope.launch(NonCancellable) {
            runCatching {
                ProcessTreeKill.pidOf(doomed)?.let { pid ->
                    ProcessTreeKill.killTree(pid)
                    ProcessTreeKill.killHost(pid)
                }
            }.onFailure { Log.w(TAG, "杀 codex 进程树失败", it) }
            runCatching { doomed.destroy() }
        }
    }

    /**
     * 会话失败：状态**和进程一起**收掉。
     *
     * 以前这里只改 status。真机上的后果是：握手失败（配置非法、认证过期）之后界面显示
     * "failed"，而 proot → bash → node → codex 四层进程一个不少地留在进程表里，
     * 端口和内存都还占着，再点一次启动又叠一套上去。状态和事实必须一致。
     *
     * [killAsync] 只是往 IO 上扔一个任务，不阻塞，所以持锁调用是安全的。
     */
    private fun failLocked(message: String) {
        val doomed = detachLocked(failed = true, message = message)
        doomed?.let { killAsync(it) }
    }

    private fun writeLineLocked(line: String): Boolean = runCatching {
        val output = writer ?: return false
        output.write(line)
        output.newLine()
        output.flush()
    }.isSuccess

    private fun nextRequestId(): String = requestIds.incrementAndGet().toString()

    private fun nextLocalId(prefix: String): String = "$prefix-${requestIds.incrementAndGet()}"

    /**
     * 一轮结束时把还挂着的增量落成正式条目。
     *
     * 正常情况下每条 item 都会有 `item/completed` 收尾，这里是兜底：turn 被打断、
     * 或服务端漏发 completed 时，用户已经读到的那段文字不该凭空消失。
     */
    private fun List<ChatItem>.settleStreaming(current: State): List<ChatItem> {
        var result = this
        val thinking = current.streamingThinking.trim()
        if (thinking.isNotEmpty() && streamingThinkingItemId == null) {
            result = result.upsert(ChatItem.Thinking(nextLocalId("thinking"), thinking))
        }
        val text = current.streamingText.trim()
        if (text.isNotEmpty() && streamingTextItemId == null) {
            result = result.upsert(ChatItem.AssistantText(nextLocalId("assistant"), text))
        }
        return result
    }

    /** 同 id 的整条替换，没有就追加 —— `item/completed` 靠它覆盖掉 started 那一版 */
    private fun List<ChatItem>.upsert(item: ChatItem): List<ChatItem> {
        val index = indexOfFirst { it.id == item.id }
        if (index < 0) return this + item
        val copy = toMutableList()
        copy[index] = item
        return copy
    }

    companion object {
        private const val TAG = "CodexAppServer"

        /** proot 的挂载点，和 Claude 那边同一个工作区 */
        const val DEFAULT_CWD = "/workspace"
    }
}
