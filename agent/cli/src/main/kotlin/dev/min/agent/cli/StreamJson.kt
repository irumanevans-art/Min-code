package dev.min.agent.cli

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
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.SkillKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.PrintStream
import java.util.UUID

/**
 * `--output-format stream-json` 的渲染器（CC：newline-delimited JSON for real-time streaming）：每行一个
 * JSON 对象，形状照 Agent SDK 的 `SDKMessage`（字段名与可选性照 TypeScript SDK 参考页；Min 没有的字段省略、不编）：
 *
 * - `system`/`init`：会话元数据（session_id、cwd、model、tools、mcp_servers、permissionMode、slash_commands、
 *   skills、plugins、agents、output_style、uuid）；
 * - `assistant`：每条完整的助手消息一行，`message` 是 Anthropic 的消息形状（id / type / role / content 块 /
 *   model / stop_reason / usage，后三项取自这条消息落盘的完成事件）；
 * - `user`：工具结果一行（tool_result 块），带 `parent_tool_use_id`；`--replay-user-messages` 时 stdin 来的用户消息
 *   也回显成这种行（带 `isReplay: true`）；
 * - `stream_event`（`--include-partial-messages`）：Anthropic 流事件的最小子集——message_start / content_block_start /
 *   content_block_delta（text_delta、thinking_delta、input_json_delta）/ content_block_stop / message_delta / message_stop；
 * - `--forward-subagent-text` 把子 agent 的消息行也发出来（带 `parent_tool_use_id`，CC :93）；
 * - 每个回合一行 `result`（json 模式那个结果对象）——中途出错照样以结果行收尾；多轮（stdin 输入）时每轮一行。
 *
 * 数据从运行时状态来：助手消息看 history 的增长、工具结果看 toolResults 的新键。开始前先把已有的都记成
 * 「已见过」（-p 恢复会话时不重放旧对话）；收尾时再扫一遍状态补齐收集协程可能漏掉的尾巴，然后才发结果行。
 * 几个收集协程并发写同一个输出：所有改状态 / 写行的路径都在 [mutex] 里，行与行不会交错、流块的开合不会乱序
 */
internal class StreamJsonRenderer(
    private val app: DesktopApp,
    private val runtime: SessionRuntime,
    private val args: CliArgs,
    private val out: PrintStream,
) {
    /** 每个会话一份「已发过什么」；根会话的键是 null */
    private val seen = HashMap<String?, Seen>()
    private val jobs = mutableListOf<Job>()
    private val childRuntimes = LinkedHashMap<String, SessionRuntime>()
    private val mutex = Mutex()

    private class Seen(val assistants: MutableSet<MessageId>, val results: MutableSet<CallId>)

    private lateinit var scope: CoroutineScope

    /** 只读这个进程之后落的事件来取助手消息的元信息（旧会话的历史不重读）；子会话都是本次运行建的，从头读 */
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
        if (args.includePartialMessages) {
            jobs += scope.launch {
                runtime.live.collect { update -> mutex.withLock { streamUpdate(update) } }
            }
        }
        if (args.forwardSubagentText) {
            jobs += scope.launch {
                app.hub().online.collect { online -> online.forEach { watchChild(it, scope) } }
            }
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
        if (args.forwardSubagentText) app.hub().online.value.forEach { watchChildLocked(it, scope) }
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
            val errors = state.mcpServers?.servers.orEmpty().filter { it.state != "connected" && it.reason != null }
                .map { "${it.name}: ${it.reason}" }
            if (errors.isNotEmpty()) put("mcp_server_errors", JsonArray(errors.map { JsonPrimitive(it) }))
        })
    }

    /** 一条助手消息落盘的元信息：来自 AssistantMessageCompleted（停止原因、用量）与对应请求的 ModelRequestStarted（模型） */
    private class AssistantMeta(val model: String?, val stop: String?, val usage: Usage?)

    private suspend fun assistantMeta(sessionId: SessionId, from: Long): Map<MessageId, AssistantMeta> {
        val events = try {
            app.records(sessionId, from).map { it.event }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // 读不到日志不该让事件流断掉：这几个字段本来就是可选的，省略即可
            return emptyMap()
        }
        val models = events.filterIsInstance<ModelRequestStarted>().associate { it.requestId to it.model.modelId }
        return events.filterIsInstance<AssistantMessageCompleted>()
            .associate { it.messageId to AssistantMeta(models[it.requestId], it.stop.wireName(), it.usage) }
    }

    /** 把 [state] 里还没发过的助手消息与工具结果发出来（收集协程与收尾各调一遍，靠 [seen] 去重；调用方持 [mutex]） */
    private suspend fun emitNew(key: String?, state: SessionState, parentToolUseId: String?) {
        val mark = seen.getOrPut(key) { Seen(HashSet(), HashSet()) }
        val sessionId = if (key == null) runtime.sessionId.value else key
        val fresh = state.history.filterIsInstance<HistoryEntry.Assistant>().filter { it.messageId !in mark.assistants }
        val meta = if (fresh.isEmpty()) emptyMap() else assistantMeta(SessionId(sessionId), if (key == null) fromSeq else 0)
        fresh.forEach { entry ->
            mark.assistants.add(entry.messageId)
            val info = meta[entry.messageId]
            // 完整消息到了：这条消息的流（若在发）先合上，再发完整行——与 CC 的先流后整条一致
            if (key == null) closeStream(info?.stop, info?.usage)
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
                        put("content", JsonArray(entry.parts.mapNotNull(::block)))
                        info?.model?.let { put("model", it) }
                        info?.stop?.let { put("stop_reason", it) }
                        info?.usage?.let { put("usage", usageJson(it)) }
                    },
                )
                put("parent_tool_use_id", parentToolUseId?.let(::JsonPrimitive) ?: JsonNull)
                if (key == null && !userUuidOnAssistant) {
                    userUuidOnAssistant = true
                    turnUserUuid?.let { put("user_message_uuid", it) }
                }
            })
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
