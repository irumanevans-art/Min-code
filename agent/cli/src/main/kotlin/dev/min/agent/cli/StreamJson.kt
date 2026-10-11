package dev.min.agent.cli

import dev.min.agent.kernel.BackgroundJobInfo
import dev.min.agent.kernel.BackgroundJobKind
import dev.min.agent.kernel.BackgroundJobStatus
import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.LiveUpdate
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.SkillKind
import dev.min.agent.spi.SubagentSpawned
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.PrintStream
import java.util.UUID

/** tool_progress 心跳的间隔（SDK：每 30 秒一条）。测试里调小，免得真等 */
@Volatile
internal var streamHeartbeatMs: Long = 30_000L

/**
 * `--output-format stream-json` 的渲染器（CC：newline-delimited JSON for real-time streaming）：每行一个
 * JSON 对象，形状照 Agent SDK 的 `SDKMessage`（字段名与可选性照 TypeScript SDK 参考页；Min 没有的字段省略、不编）：
 *
 * - `system`/`init`：会话元数据（session_id、cwd、model、tools、mcp_servers、permissionMode、slash_commands、
 *   skills、plugins、agents、output_style、uuid；stdin 输入时还有 `capabilities`）；
 * - `assistant`：**一个 content block 一行**（SDK：「One API turn can produce several assistant messages that share a
 *   `message.id`」；headless 样本 33 的原文也写「每个 content block 一条」），`message` 是 Anthropic 的消息形状
 *   （id / type / role / content 块 / model / stop_reason / usage，取自这条消息落盘的完成事件；停止原因与用量在整条消息
 *   结束时才有，所以前面的块 `stop_reason` 是 null、没有 usage，最后一块才带）；
 * - `user`：工具结果一行（tool_result 块），带 `parent_tool_use_id`；`--replay-user-messages` 时 stdin 来的用户消息
 *   也回显成这种行（带 `isReplay: true`）；
 * - `stream_event`（`--include-partial-messages`）：Anthropic 流事件的最小子集——message_start / content_block_start /
 *   content_block_delta（text_delta、thinking_delta、input_json_delta）/ content_block_stop / message_delta / message_stop；
 * - 其它 `system` 子类型（api_retry、status、compact_boundary、hook_*、permission_denied、informational）由会话日志里的
 *   事件翻出来（[systemRows]）；子 agent 与后台命令的 `task_started` / `task_updated` / `task_notification` /
 *   `background_tasks_changed` 由子会话日志与 Hub 的后台任务表翻出来；
 * - `--forward-subagent-text` 把子 agent 的消息行也发出来（带 `parent_tool_use_id`，CC :93）；
 * - 每个回合一行 `result`（json 模式那个结果对象）——中途出错照样以结果行收尾；多轮（stdin 输入）时每轮一行。
 *
 * 数据从运行时状态与会话日志来：助手消息看 history 的增长、工具结果看 toolResults 的新键、系统消息从日志尾部（按 seq 游标，
 * 每条事件只读一次）翻。开始前先把已有的都记成「已见过」（-p 恢复会话时不重放旧对话）；收尾时再扫一遍补齐收集协程可能漏掉的尾巴，
 * 然后才发结果行。几个收集协程并发写同一个输出：所有改状态 / 写行的路径都在 [mutex] 里，行与行不会交错、流块的开合不会乱序
 */
internal class StreamJsonRenderer(
    private val app: DesktopApp,
    private val runtime: SessionRuntime,
    private val args: CliArgs,
    private val out: PrintStream,
    /** 权限询问交给宿主处理（`--permission-prompt-tool stdio`）：宿主自己拒的不再发 `permission_denied`（SDK：那是「没问回调就拒了」的提示） */
    private val hostDecidesPermissions: Boolean = false,
) {
    /** 每个会话一份「已发过什么」；根会话的键是 null */
    private val seen = HashMap<String?, Seen>()
    private val jobs = mutableListOf<Job>()
    private val childRuntimes = LinkedHashMap<String, SessionRuntime>()
    private val mutex = Mutex()

    private class Seen(val assistants: MutableSet<MessageId>, val results: MutableSet<CallId>)

    private lateinit var scope: CoroutineScope

    /** 只读这个进程之后落的事件（旧会话的历史不重读）；子会话都是本次运行建的，从头读 */
    private var fromSeq: Long = 0

    // ---- 当前回合（stdin 多轮：每条用户消息一个回合）----
    /** 本回合回答的那条用户消息的 uuid（stdin 消息带了 uuid 才有）；SDK：只在回合的首条助手消息 / 首个流事件 / 结果上回显 */
    private var turnUserUuid: String? = null
    private var userUuidOnAssistant = false
    private var userUuidOnStream = false

    suspend fun start(scope: CoroutineScope) {
        this.scope = scope
        fromSeq = runtime.state.value.lastSeq + 1
        mutex.withLock { emitInit() }
        seen[null] = seenOf(runtime.state.value)
        jobs += scope.launch {
            runtime.state.collect { state -> mutex.withLock { emitNew(null, state, parentToolUseId = null) } }
        }
        // 瞬时流总要看：thinking_tokens 不依赖 --include-partial-messages；流事件（stream_event）才要那个开关
        jobs += scope.launch {
            runtime.live.collect { update ->
                mutex.withLock {
                    thinkingTokens(update)
                    if (args.includePartialMessages) streamUpdate(update)
                }
            }
        }
        // 心跳：工具在跑时每隔 [streamHeartbeatMs] 发一条 tool_progress（SDK：每 30 秒一条，heartbeat:true），顺带刷新在跑的子 agent 任务进展
        jobs += scope.launch {
            while (true) {
                kotlinx.coroutines.delay(streamHeartbeatMs)
                mutex.withLock { heartbeat() }
            }
        }
        // 子会话总要盯着：task_started / task_updated 来自它们的日志；它们的消息行只在 --forward-subagent-text 时才发
        jobs += scope.launch {
            app.hub().online.collect { online -> online.forEach { watchChild(it, scope) } }
        }
        jobs += scope.launch {
            app.hub().backgroundJobs(runtime.sessionId).collect { infos -> mutex.withLock { emitBackground(infos) } }
        }
    }

    /** 新回合开始：[userUuid] 是 stdin 里这条用户消息自带的 uuid（单轮 -p 没有） */
    suspend fun beginTurn(userUuid: String?) = mutex.withLock {
        turnUserUuid = userUuid
        userUuidOnAssistant = false
        userUuidOnStream = false
    }

    /** `--replay-user-messages`：stdin 来的用户消息原样回显（SDKUserMessageReplay：uuid 必带，没有就现生成一个） */
    suspend fun replayUser(uuid: String, message: JsonObject) = mutex.withLock {
        line(buildJsonObject {
            put("type", "user")
            put("message", message)
            put("parent_tool_use_id", JsonNull)
            put("session_id", runtime.sessionId.value)
            put("uuid", uuid)
            put("isReplay", true)
        })
    }

    /** 回合收尾：把在线子会话盯上、同步扫一遍补齐尾巴、合上没合的流，然后发这个回合的结果行（连续多轮时渲染器继续活着） */
    suspend fun endTurn(result: JsonObject) = mutex.withLock {
        app.hub().online.value.forEach { watchChildLocked(it, scope) }
        emitNew(null, runtime.state.value, parentToolUseId = null)
        childRuntimes.values.forEach { child ->
            val parentCall = child.state.value.origin?.parentCall?.value
            if (parentCall != null) emitNew(child.sessionId.value, child.state.value, parentToolUseId = parentCall)
        }
        closeStream(stop = null, usage = null)
        line(withUserUuid(result))
        turnUserUuid = null
    }

    fun close() { jobs.forEach(Job::cancel) }

    /** 回显用户消息的 uuid：SDK 的 result 只在 stdin 消息自带 uuid 时才带 `user_message_uuid` */
    private fun withUserUuid(result: JsonObject): JsonObject {
        val uuid = turnUserUuid ?: return result
        return JsonObject(result + ("user_message_uuid" to JsonPrimitive(uuid)))
    }

    /** 一个子会话上线就开始盯它的状态（幂等）；委派它的那次 Agent 调用 id 就是它的 parent_tool_use_id。
     *  子会话都是本运行期间新建的，不预标「已见过」——挂上之前落的消息也要转发（根会话才要跳过旧的） */
    private suspend fun watchChild(id: SessionId, scope: CoroutineScope) = mutex.withLock { watchChildLocked(id, scope) }

    private suspend fun watchChildLocked(id: SessionId, scope: CoroutineScope) {
        if (id == runtime.sessionId || id.value in childRuntimes) return
        val child = app.hub().get(id) ?: return
        val origin = child.state.value.origin ?: return
        if (origin.root != runtime.sessionId) return
        seen[id.value] = Seen(HashSet(), HashSet())
        childRuntimes[id.value] = child
        jobs += scope.launch {
            child.state.collect { state ->
                origin.parentCall.value.let { call -> mutex.withLock { emitNew(id.value, state, parentToolUseId = call) } }
            }
        }
    }

    /** init 行：会话元数据（model、tools、MCP 服务器、skills / 命令 / 子 agent / 插件、输出样式）；没有连不上的服务器就整个省略 mcp_server_errors 键 */
    private suspend fun emitInit() {
        val state = runtime.state.value
        val skills = state.skills?.entries.orEmpty()
        line(buildJsonObject {
            put("type", "system")
            put("subtype", "init")
            put("uuid", UUID.randomUUID().toString())
            put("session_id", runtime.sessionId.value)
            put("cwd", state.created.cwd.value)
            put("model", app.modelLabel())
            put("tools", JsonArray(app.toolNames().map { JsonPrimitive(it) }))
            put(
                "mcp_servers",
                JsonArray(state.mcpServers?.servers.orEmpty().map { s ->
                    buildJsonObject { put("name", s.name); put("status", s.state) }
                }),
            )
            // permissionMode 用线上的取值（default / acceptEdits / ……），与 hook stdin 里的同一套
            put(
                "permissionMode",
                dev.min.agent.protocol.AgentJson.encodeToJsonElement(
                    dev.min.agent.protocol.PermissionMode.serializer(), state.settings.mode,
                ).jsonPrimitive.content,
            )
            // 用户能用 `/名字` 调的：skills 与自定义命令（userInvocable 为假的只有模型能调，不列）
            put("slash_commands", JsonArray(skills.filter { it.userInvocable }.map { JsonPrimitive(it.name) }))
            put("skills", JsonArray(skills.filter { it.kind == SkillKind.Skill }.map { JsonPrimitive(it.name) }))
            put("plugins", JsonArray(app.loadedPlugins().map { (name, path) -> buildJsonObject { put("name", name); put("path", path) } }))
            put("agents", JsonArray(app.config().agents.map { JsonPrimitive(it.name) }))
            put("output_style", state.outputStyle?.name ?: "default")
            // 协议行为清单（SDK：feature-detect 用）：stdin 输入时 Min 实现了中断回执与 cancel_queued
            if (args.inputFormat == InputFormat.StreamJson) {
                put("capabilities", JsonArray(listOf("interrupt_receipt_v1", "interrupt_cancel_queued_v1").map(::JsonPrimitive)))
            }
            val errors = state.mcpServers?.servers.orEmpty().filter { it.state != "connected" && it.reason != null }
                .map { "${it.name}: ${it.reason}" }
            if (errors.isNotEmpty()) put("mcp_server_errors", JsonArray(errors.map { JsonPrimitive(it) }))
        })
    }

    // ---- 会话日志尾部：助手消息的元信息 + 系统消息 ----

    /** 一条助手消息落盘的元信息：来自 AssistantMessageCompleted（停止原因、用量）与对应请求的 ModelRequestStarted（模型） */
    private class AssistantMeta(val model: String?, val stop: String?, val usage: Usage?)

    private class Tail(val sessionId: SessionId, var cursor: Long)

    private val tails = HashMap<String?, Tail>()
    private val models = HashMap<RequestId, String>()
    private val metas = HashMap<MessageId, AssistantMeta>()
    private val asks = HashMap<String, ApprovalRequested>()

    /** 读这个会话日志里还没读过的事件（游标按 seq 推进，每条只读一次）。读不到日志不该让事件流断掉：这些行本来就是尽力而为 */
    private suspend fun drain(key: String?, sessionId: String): List<EventRecord> {
        val tail = tails.getOrPut(key) { Tail(SessionId(sessionId), if (key == null) fromSeq else 0) }
        val records = try {
            app.records(tail.sessionId, tail.cursor)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        records.lastOrNull()?.let { tail.cursor = it.seq + 1 }
        return records
    }

    private fun ingest(event: dev.min.agent.spi.SessionEvent) {
        when (event) {
            is ModelRequestStarted -> models[event.requestId] = event.model.modelId
            is AssistantMessageCompleted -> metas[event.messageId] = AssistantMeta(models[event.requestId], event.stop.wireName(), event.usage)
            else -> Unit
        }
    }

    /** 子 agent 的任务：委派它的那次 Agent 调用、是不是后台、深度——结束时的 task_updated 要用 */
    private class Task(val callId: CallId, val background: Boolean, val description: String, val agentType: String, val startedAtMs: Long) {
        /** task_progress 的 usage：子会话日志里累计的工具调用次数与 token（输入 + 输出），最近一次调用的工具名 */
        var toolUses = 0
        var tokens = 0L
        var lastTool: String? = null
        var ended = false
    }

    private val tasks = HashMap<String, Task>()

    /** SDKTaskProgressMessage：子 agent 的进展。summary 要 agentProgressSummaries（模型生成的摘要），Min 没有这个选项，省略 */
    private fun emitTaskProgress(taskId: String, task: Task, sessionId: String) {
        line(sys("task_progress", sessionId) {
            put("task_id", taskId)
            put("tool_use_id", task.callId.value)
            put("description", task.description)
            put("subagent_type", task.agentType)
            put("usage", buildJsonObject {
                put("total_tokens", task.tokens)
                put("tool_uses", task.toolUses)
                put("duration_ms", (System.currentTimeMillis() - task.startedAtMs).coerceAtLeast(0))
            })
            task.lastTool?.let { put("last_tool_name", it) }
        })
    }

    /** 这些命令的全量清单（SDK 的 commands_changed 是全量替换）：启动时的 skills / 命令 + 嵌套目录里懒加载来的，只列用户能用 `/名字` 调的 */
    private fun commandsRow(sessionId: String): JsonObject {
        val state = runtime.state.value
        val entries = (state.skills?.entries.orEmpty() + state.nestedSkills).filter { it.userInvocable }.distinctBy { it.name }
        return sys("commands_changed", sessionId) {
            put("commands", JsonArray(entries.map { e ->
                buildJsonObject {
                    put("name", e.name); put("description", e.description)
                    put("argumentHint", e.argumentHint ?: "")
                }
            }))
        }
    }

    /** 一条日志事件要翻成哪些系统行：通用的交给 [systemRows]，要看会话间关系的（子 agent、审批）在这里 */
    private suspend fun emitSystem(key: String?, record: EventRecord) {
        val sessionId = if (key == null) runtime.sessionId.value else key
        val event = record.event
        // 子会话自己的说明 / 重试不外发（SDK 的 system 消息描述的是整个会话）；只翻与任务、审批有关的
        if (key == null) systemRows(event, sessionId, args.includeHookEvents).forEach(::line)
        when (event) {
            is SubagentSpawned -> {
                // 深度取子会话的来历（根的子 agent = 1）；子会话已经卸下了就按「上一层 + 1」估
                val depth = app.hub().get(event.child)?.state?.value?.origin?.depth ?: (if (key == null) 1 else 2)
                tasks[event.child.value] = Task(event.callId, event.background, event.description, event.agentType, record.atMs)
                line(sys("task_started", sessionId) {
                    put("task_id", event.child.value)
                    put("tool_use_id", event.callId.value)
                    put("description", event.description)
                    put("task_type", "local_agent")
                    put("is_backgrounded", event.background)
                    put("spawn_depth", depth)
                })
            }
            is ApprovalRequested -> asks[event.approvalId.value] = event
            is ApprovalResolved -> {
                val asked = asks.remove(event.approvalId.value)
                val denial = event.choice as? ApprovalChoice.Deny
                // 宿主答的（--permission-prompt-tool stdio）不算「CLI 没问就拒了」；规则 / 模式 / 分类器 / hook / 无人应答的拒绝才是
                val hostAnswered = hostDecidesPermissions && event.decidedBy == DecidedBy.User
                if (asked != null && denial != null && !hostAnswered) {
                    line(sys("permission_denied", sessionId) {
                        put("tool_name", asked.ask.tool)
                        put("tool_use_id", asked.callId.value)
                        if (key != null) put("agent_id", key)
                        when (val by = event.decidedBy) {
                            is DecidedBy.Rule -> { put("decision_reason_type", "rule"); put("decision_reason", by.rule) }
                            is DecidedBy.Mode -> put("decision_reason_type", "mode")
                            DecidedBy.Classifier -> put("decision_reason_type", "classifier")
                            else -> Unit
                        }
                        put("message", denial.note ?: "权限被拒绝")
                    })
                }
            }
            is dev.min.agent.spi.ToolPlanned -> if (key != null) tasks[key]?.let { task ->
                task.toolUses += 1
                task.lastTool = event.tool
                emitTaskProgress(key, task, sessionId)
            }
            is AssistantMessageCompleted -> if (key != null) tasks[key]?.let { it.tokens += event.usage.inputTokens + event.usage.outputTokens }
            is dev.min.agent.spi.SkillsDiscovered -> if (key == null) line(commandsRow(sessionId))
            is TurnEnded -> if (key != null) tasks[key]?.let { task ->
                task.ended = true
                // 子 agent 的一个回合结束 = 它的任务结束：patch 合进 SDK 侧的任务表（status + end_time）
                line(sys("task_updated", sessionId) {
                    put("task_id", key)
                    put("patch", buildJsonObject {
                        put("status", when (event.outcome) {
                            TurnOutcome.Completed -> "completed"
                            is TurnOutcome.Failed -> "failed"
                            is TurnOutcome.Interrupted -> "killed"
                        })
                        put("end_time", record.atMs)
                    })
                })
            }
            else -> Unit
        }
    }

    private fun sys(subtype: String, sessionId: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "system")
        put("subtype", subtype)
        fields()
        put("uuid", UUID.randomUUID().toString())
        put("session_id", sessionId)
    }

    // ---- 后台命令 / watch：Hub 的后台任务表 → task_* 与 background_tasks_changed ----

    private val knownJobs = HashMap<String, BackgroundJobInfo>()
    private var liveSignature: List<String>? = null

    private fun emitBackground(infos: List<BackgroundJobInfo>) {
        val sessionId = runtime.sessionId.value
        infos.forEach { info ->
            val before = knownJobs.put(info.id, info)
            if (before == null) {
                line(sys("task_started", sessionId) {
                    put("task_id", info.id)
                    put("description", info.description)
                    put("task_type", "local_bash")
                    put("is_backgrounded", true)
                })
            }
            if (!info.isRunning && (before == null || before.isRunning)) {
                val status = when (info.status) {
                    BackgroundJobStatus.Exited -> if (info.exitCode == null || info.exitCode == 0) "completed" else "failed"
                    BackgroundJobStatus.Failed -> "failed"
                    else -> "killed"
                }
                line(sys("task_updated", sessionId) {
                    put("task_id", info.id)
                    put("patch", buildJsonObject {
                        put("status", status)
                        info.endedAtMs?.let { put("end_time", it) }
                    })
                })
                // task_notification 的 output_file 是必带的字符串：没有输出文件的任务只发 task_updated，不编路径
                info.outputPath?.let { path ->
                    line(sys("task_notification", sessionId) {
                        put("task_id", info.id)
                        put("status", when (status) { "completed" -> "completed"; "failed" -> "failed"; else -> "stopped" })
                        put("output_file", path.value)
                        put("summary", "${if (info.kind == BackgroundJobKind.Monitor) "watch" else "后台命令"}「${info.description}」" + when (info.status) {
                            BackgroundJobStatus.Exited -> "已退出（退出码 ${info.exitCode ?: 0}）"
                            BackgroundJobStatus.TimedOut -> "到期被收掉"
                            BackgroundJobStatus.Stopped -> "已被停止"
                            else -> "出错结束"
                        })
                    })
                }
            }
        }
        // 在跑的全集（SDK：全量替换）：成员变了才发
        val live = infos.filter { it.isRunning }
        val signature = live.map { "${it.id}|${it.description}" }
        if (signature != liveSignature && (liveSignature != null || signature.isNotEmpty())) {
            line(sys("background_tasks_changed", sessionId) {
                put("tasks", JsonArray(live.map { j ->
                    buildJsonObject { put("task_id", j.id); put("task_type", "local_bash"); put("description", j.description) }
                }))
            })
        }
        liveSignature = signature
    }

    /** 把 [state] 里还没发过的助手消息与工具结果、以及日志里新落的系统消息发出来（收集协程与收尾各调一遍，靠 [seen] 与游标去重；调用方持 [mutex]） */
    private suspend fun emitNew(key: String?, state: SessionState, parentToolUseId: String?) {
        val mark = seen.getOrPut(key) { Seen(HashSet(), HashSet()) }
        val sessionId = if (key == null) runtime.sessionId.value else key
        // 工具开始跑的时刻（心跳的 elapsed_time_seconds 从这里数）：状态里第一次看到它处于副作用已开始
        if (key == null) {
            val now = System.nanoTime()
            state.turn?.calls?.values.orEmpty().forEach { if (it.phase is dev.min.agent.kernel.CallPhase.EffectPending) toolSeen.putIfAbsent(it.callId, now) }
        }
        val records = drain(key, sessionId)
        records.forEach { ingest(it.event) }
        records.forEach { emitSystem(key, it) }
        // 子会话的消息行只在 --forward-subagent-text 时才发（没开时它们只用来出 task_* 行）
        if (key != null && !args.forwardSubagentText) return
        val fresh = state.history.filterIsInstance<HistoryEntry.Assistant>().filter { it.messageId !in mark.assistants }
        fresh.forEach { entry ->
            mark.assistants.add(entry.messageId)
            val info = metas[entry.messageId]
            // 完整消息到了：这条消息的流（若在发）先合上，再发完整行——与 CC 的先流后整条一致
            if (key == null) closeStream(info?.stop, info?.usage)
            val blocks = entry.parts.mapNotNull(::block)
            // 一个 content block 一行，共用 message.id；没有可发的块（全是 Min 独有的块）也给一行空 content，不让这条消息凭空消失
            val rows = if (blocks.isEmpty()) listOf<JsonObject?>(null) else blocks
            rows.forEachIndexed { i, blockJson ->
                val last = i == rows.lastIndex
                line(buildJsonObject {
                    put("type", "assistant")
                    put("uuid", UUID.randomUUID().toString())
                    put("session_id", sessionId)
                    put(
                        "message",
                        buildJsonObject {
                            put("id", entry.messageId.value)
                            put("type", "message")
                            put("role", "assistant")
                            put("content", JsonArray(listOfNotNull(blockJson)))
                            info?.model?.let { put("model", it) }
                            // 停止原因与用量在整条消息结束时才知道：只有最后一块带，前面的块 stop_reason 是 null
                            if (!last) put("stop_reason", JsonNull) else info?.stop?.let { put("stop_reason", it) }
                            if (last) info?.usage?.let { put("usage", usageJson(it)) }
                        },
                    )
                    put("parent_tool_use_id", parentToolUseId?.let(::JsonPrimitive) ?: JsonNull)
                    if (key == null && !userUuidOnAssistant) {
                        userUuidOnAssistant = true
                        turnUserUuid?.let { put("user_message_uuid", it) }
                    }
                })
            }
        }
        state.toolResults.forEach { (callId, result) ->
            if (mark.results.add(callId)) {
                line(buildJsonObject {
                    put("type", "user")
                    put("uuid", UUID.randomUUID().toString())
                    put("session_id", sessionId)
                    put(
                        "message",
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("type", "tool_result")
                                            put("tool_use_id", callId.value)
                                            put(
                                                "content",
                                                JsonArray(result.content.mapNotNull(::block)),
                                            )
                                            put("is_error", result.isError)
                                        },
                                    ) + listOfNotNull(
                                        // 用户的话与发给模型的形状一致：同一条 user 消息里紧随 tool_result 的独立 text 块
                                        result.userText?.let { text -> buildJsonObject { put("type", "text"); put("text", text) } },
                                    ),
                                ),
                            )
                        },
                    )
                    put("parent_tool_use_id", parentToolUseId?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        }
    }

    // ---- thinking_tokens：思考块的 token 估算 ----

    /** 当前思考块：属于哪次请求、累计的 UTF-8 字节数、已经报过的估算值。别的块 / 新请求开始就换新的 */
    private var thinkingRequest: RequestId? = null
    private var thinkingBytes = 0L
    private var thinkingReported = 0L

    /**
     * SDKThinkingTokensMessage：Min 没有逐块的真实 token 数，估算按「UTF-8 字节数 ÷ 4 向上取整」（英文约 4 字节 / token，
     * 中文约 3 字节一个字、也差不多一个 token，量级对得上）。只给「估算」用——SDK 原文也说这是进度估算，最终数看 usage。
     * 增量为 0 的帧（字节数没凑够一个 token）不发
     */
    private fun thinkingTokens(update: LiveUpdate) {
        when (update) {
            is LiveUpdate.Thinking -> {
                if (update.text.isEmpty()) return
                if (thinkingRequest != update.requestId) { thinkingRequest = update.requestId; thinkingBytes = 0; thinkingReported = 0 }
                thinkingBytes += update.text.toByteArray(Charsets.UTF_8).size
                val estimate = (thinkingBytes + 3) / 4
                val delta = estimate - thinkingReported
                if (delta <= 0) return
                thinkingReported = estimate
                line(sys("thinking_tokens", runtime.sessionId.value) {
                    put("estimated_tokens", estimate)
                    put("estimated_tokens_delta", delta)
                })
            }
            // 思考块结束（后面接了正文 / 工具调用）：下一个思考块重新数
            is LiveUpdate.Text, is LiveUpdate.ToolCallStarted -> { thinkingRequest = null }
            else -> Unit
        }
    }

    // ---- tool_progress 心跳与子 agent 的 task_progress ----

    private val toolSeen = HashMap<CallId, Long>()

    private fun heartbeat() {
        val sessionId = runtime.sessionId.value
        val now = System.nanoTime()
        val running = runtime.state.value.turn?.calls?.values.orEmpty()
            .filter { it.phase is dev.min.agent.kernel.CallPhase.EffectPending }
        running.forEach { call ->
            val since = toolSeen.getOrPut(call.callId) { now }
            line(buildJsonObject {
                put("type", "tool_progress")
                put("tool_use_id", call.callId.value)
                put("tool_name", call.tool)
                put("parent_tool_use_id", JsonNull)
                put("elapsed_time_seconds", (now - since) / 1_000_000_000.0)
                put("heartbeat", true)
                put("uuid", UUID.randomUUID().toString())
                put("session_id", sessionId)
            })
        }
        toolSeen.keys.retainAll(running.map { it.callId }.toSet())
        tasks.forEach { (id, task) -> if (!task.ended) emitTaskProgress(id, task, sessionId) }
    }

    // ---- --include-partial-messages：把瞬时流的增量拼成 Anthropic 的流事件 ----

    /** 正在发的那一条流（一次模型请求一条）：块下标自己顺序编；瞬时流不带内容块下标，按「种类变了就换块」切 */
    private class OpenStream(val requestId: RequestId) {
        var nextIndex = 0
        var current: Block? = null
        /** 工具调用在瞬时流里的下标 → 它的块下标（参数增量按它找块） */
        val toolBlocks = HashMap<Int, Int>()
    }

    private class Block(val index: Int, val kind: Kind) { enum class Kind { Text, Thinking, Tool } }

    private var stream: OpenStream? = null
    /** 已经合上的请求：迟到的瞬时增量（两条流先后不保证）不再开新流 */
    private val closedRequests = HashSet<RequestId>()

    private fun streamUpdate(update: LiveUpdate) {
        when (update) {
            is LiveUpdate.Text -> if (update.text.isNotEmpty()) {
                val s = streamFor(update.requestId) ?: return
                val block = blockOf(s, Block.Kind.Text) { buildJsonObject { put("type", "text"); put("text", "") } }
                streamEvent("content_block_delta") {
                    put("index", block.index)
                    put("delta", buildJsonObject { put("type", "text_delta"); put("text", update.text) })
                }
            }
            is LiveUpdate.Thinking -> if (update.text.isNotEmpty()) {
                val s = streamFor(update.requestId) ?: return
                val block = blockOf(s, Block.Kind.Thinking) { buildJsonObject { put("type", "thinking"); put("thinking", "") } }
                streamEvent("content_block_delta") {
                    put("index", block.index)
                    put("delta", buildJsonObject { put("type", "thinking_delta"); put("thinking", update.text) })
                }
            }
            is LiveUpdate.ToolCallStarted -> {
                val s = streamFor(update.requestId) ?: return
                closeBlock(s)
                val index = s.nextIndex++
                s.current = Block(index, Block.Kind.Tool)
                s.toolBlocks[update.index] = index
                // 瞬时流没有调用 id（完整消息里才有），tool_use 块的 id 省略、不编
                streamEvent("content_block_start") {
                    put("index", index)
                    put("content_block", buildJsonObject { put("type", "tool_use"); put("name", update.name); put("input", JsonObject(emptyMap())) })
                }
            }
            is LiveUpdate.ToolInput -> {
                val s = streamFor(update.requestId) ?: return
                val index = s.toolBlocks[update.index] ?: return
                streamEvent("content_block_delta") {
                    put("index", index)
                    put("delta", buildJsonObject { put("type", "input_json_delta"); put("partial_json", update.partialJson) })
                }
            }
            // 工具的输出 / 状态是界面用的，不属于模型这条消息的流
            is LiveUpdate.ToolOutput, is LiveUpdate.ToolStatus -> Unit
        }
    }

    /** 这个请求对应的流：没开就开（先合上上一条），已经合上的请求返回 null（迟到的增量丢掉，完整消息行里有全文） */
    private fun streamFor(requestId: RequestId): OpenStream? {
        stream?.let { if (it.requestId == requestId) return it }
        if (requestId in closedRequests) return null
        closeStream(stop = null, usage = null)
        val s = OpenStream(requestId)
        stream = s
        streamEvent("message_start") {
            put("message", buildJsonObject {
                // 瞬时流里还没有最终的消息 id（完整消息落盘时才分配），用请求 id 占位
                put("id", requestId.value)
                put("type", "message")
                put("role", "assistant")
                put("content", JsonArray(emptyList()))
                put("model", app.modelId())
            })
        }
        return s
    }

    private fun blockOf(s: OpenStream, kind: Block.Kind, start: () -> JsonObject): Block {
        s.current?.let { if (it.kind == kind) return it }
        closeBlock(s)
        val block = Block(s.nextIndex++, kind)
        s.current = block
        streamEvent("content_block_start") { put("index", block.index); put("content_block", start()) }
        return block
    }

    private fun closeBlock(s: OpenStream) {
        val block = s.current ?: return
        s.current = null
        streamEvent("content_block_stop") { put("index", block.index) }
    }

    /** 合上当前的流：content_block_stop → message_delta（停止原因、用量，知道才给）→ message_stop */
    private fun closeStream(stop: String?, usage: Usage?) {
        val s = stream ?: return
        stream = null
        closedRequests += s.requestId
        closeBlock(s)
        streamEvent("message_delta") {
            put("delta", buildJsonObject { stop?.let { put("stop_reason", it) } })
            usage?.let { put("usage", usageJson(it)) }
        }
        streamEvent("message_stop") {}
    }

    private fun streamEvent(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        line(buildJsonObject {
            put("type", "stream_event")
            put("event", buildJsonObject { put("type", type); fields() })
            // stream_event 只给主会话发（SDK：parent_tool_use_id 恒为 null）
            put("parent_tool_use_id", JsonNull)
            put("uuid", UUID.randomUUID().toString())
            put("session_id", runtime.sessionId.value)
            if (!userUuidOnStream) {
                userUuidOnStream = true
                turnUserUuid?.let { put("user_message_uuid", it) }
            }
        })
    }

    /** 协议的 Part → Anthropic 的 content 块；Min 有而 CC 没对应物的块跳过 */
    private fun block(part: dev.min.agent.protocol.Part) = when (part) {
        is TextPart -> buildJsonObject { put("type", "text"); put("text", part.text) }
        is ThinkingPart -> buildJsonObject { put("type", "thinking"); put("thinking", part.text) }
        is ToolCallPart -> buildJsonObject {
            put("type", "tool_use"); put("id", part.callId.value); put("name", part.name); put("input", part.input)
        }
        is ToolResultPart -> buildJsonObject {
            // 结果单独走 user 行；混在 content 里的（历史回放形状）给 text 块，不给裸块
            put("type", "text")
            put("text", part.content.filterIsInstance<TextPart>().joinToString("") { it.text })
        }
        is ImagePart -> (part.source as? ImageSource.Base64)?.let { source ->
            buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject { put("type", "base64"); put("media_type", part.mediaType); put("data", source.data) })
            }
        }
        else -> null
    }

    private fun usageJson(usage: Usage): JsonObject = buildJsonObject {
        // 键名照 Anthropic：cache 写入叫 cache_creation_input_tokens、读取叫 cache_read_input_tokens
        put("input_tokens", usage.inputTokens)
        put("output_tokens", usage.outputTokens)
        put("cache_creation_input_tokens", usage.cacheWriteTokens)
        put("cache_read_input_tokens", usage.cacheReadTokens)
    }

    private fun seenOf(state: SessionState) =
        Seen(state.history.filterIsInstance<HistoryEntry.Assistant>().map { it.messageId }.toMutableSet(), state.toolResults.keys.toMutableSet())

    private fun line(obj: JsonObject) {
        synchronized(out) { out.println(obj.toString()) }
    }
}
