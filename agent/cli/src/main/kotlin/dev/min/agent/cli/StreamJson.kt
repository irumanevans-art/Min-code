package dev.min.agent.cli

import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.LiveUpdate
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.PrintStream

/**
 * `--output-format stream-json` 的渲染器（CC：newline-delimited JSON for real-time streaming）：每行一个
 * JSON 对象。第一行 system/init（会话元数据）；每条完整的助手消息一行 `assistant`（content 用 Anthropic 的
 * 块形状）；工具结果一行 `user`（tool_result 块）；`--include-partial-messages` 额外给 text 增量的
 * `stream_event`；`--forward-subagent-text` 把子 agent 的消息行也发出来（带 `parent_tool_use_id`，CC :93）；
 * 最后一行是 json 模式那个结果对象——中途出错照样以结果行收尾。
 *
 * 数据从运行时状态来：助手消息看 history 的增长、工具结果看 toolResults 的新键。开始前先把已有的都记成
 * 「已见过」（-p 恢复会话时不重放旧对话）；收尾时再扫一遍状态补齐收集协程可能漏掉的尾巴，然后才发结果行
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

    private class Seen(val assistants: MutableSet<MessageId>, val results: MutableSet<CallId>)

    private lateinit var scope: CoroutineScope

    fun start(scope: CoroutineScope) {
        this.scope = scope
        emitInit()
        seen[null] = seenOf(runtime.state.value)
        jobs += scope.launch {
            runtime.state.collect { state -> emitNew(null, state, parentToolUseId = null) }
        }
        if (args.includePartialMessages) {
            jobs += scope.launch {
                runtime.live.collect { update ->
                    if (update is LiveUpdate.Text && update.text.isNotEmpty()) {
                        line(buildJsonObject {
                            put("type", "stream_event")
                            put("event", buildJsonObject {
                                // Min 的增量与协议线无关（OpenAI / Anthropic 都收成同一种），合成最小 Anthropic 形状
                                put("type", "content_block_delta")
                                put("index", 0)
                                put("delta", buildJsonObject { put("type", "text_delta"); put("text", update.text) })
                            })
                            put("session_id", runtime.sessionId.value)
                            put("parent_tool_use_id", JsonNull)
                        })
                    }
                }
            }
        }
        if (args.forwardSubagentText) {
            jobs += scope.launch {
                app.hub().online.collect { online -> online.forEach { watchChild(it, scope) } }
            }
        }
    }

    /** 结果行之前：把在线子会话都盯上、再同步扫一遍补齐尾巴，然后收尾。中途出错也走这里 */
    suspend fun finish(result: JsonObject) {
        if (args.forwardSubagentText) app.hub().online.value.forEach { watchChild(it, scope) }
        emitNew(null, runtime.state.value, parentToolUseId = null)
        childRuntimes.values.forEach { child ->
            val parentCall = child.state.value.origin?.parentCall?.value
            if (parentCall != null) emitNew(child.sessionId.value, child.state.value, parentToolUseId = parentCall)
        }
        line(result)
        jobs.forEach(Job::cancel)
    }

    /** 一个子会话上线就开始盯它的状态（幂等）；委派它的那次 Agent 调用 id 就是它的 parent_tool_use_id。
     *  子会话都是本运行期间新建的，不预标「已见过」——挂上之前落的消息也要转发（根会话才要跳过旧的） */
    private suspend fun watchChild(id: SessionId, scope: CoroutineScope) {
        if (id == runtime.sessionId || id.value in childRuntimes) return
        val child = app.hub().get(id) ?: return
        val origin = child.state.value.origin ?: return
        if (origin.root != runtime.sessionId) return
        seen[id.value] = Seen(HashSet(), HashSet())
        childRuntimes[id.value] = child
        jobs += scope.launch {
            child.state.collect { state ->
                origin.parentCall.value.let { call -> emitNew(id.value, state, parentToolUseId = call) }
            }
        }
    }

    /** init 行：会话元数据（model、tools、MCP 服务器）；没有连不上的服务器就整个省略 mcp_server_errors 键 */
    private fun emitInit() {
        val state = runtime.state.value
        line(buildJsonObject {
            put("type", "system")
            put("subtype", "init")
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
            val errors = state.mcpServers?.servers.orEmpty().filter { it.state != "connected" && it.reason != null }
                .map { "${it.name}: ${it.reason}" }
            if (errors.isNotEmpty()) put("mcp_server_errors", JsonArray(errors.map { JsonPrimitive(it) }))
        })
    }

    /** 把 [state] 里还没发过的助手消息与工具结果发出来（收集协程与收尾各调一遍，靠 [seen] 去重） */
    private fun emitNew(key: String?, state: SessionState, parentToolUseId: String?) {
        val mark = seen.getOrPut(key) { Seen(HashSet(), HashSet()) }
        state.history.forEach { entry ->
            if (entry is HistoryEntry.Assistant && mark.assistants.add(entry.messageId)) {
                val sessionId = if (key == null) runtime.sessionId.value else key
                line(buildJsonObject {
                    put("type", "assistant")
                    put(
                        "message",
                        buildJsonObject {
                            put("id", entry.messageId.value)
                            put("type", "message")
                            put("role", "assistant")
                            put("content", JsonArray(entry.parts.mapNotNull(::block)))
                        },
                    )
                    put("session_id", sessionId)
                    if (parentToolUseId != null) put("parent_tool_use_id", parentToolUseId) else put("parent_tool_use_id", JsonNull)
                })
            }
        }
        state.toolResults.forEach { (callId, result) ->
            if (mark.results.add(callId)) {
                val sessionId = if (key == null) runtime.sessionId.value else key
                line(buildJsonObject {
                    put("type", "user")
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
                    put("session_id", sessionId)
                    if (parentToolUseId != null) put("parent_tool_use_id", parentToolUseId) else put("parent_tool_use_id", JsonNull)
                })
            }
        }
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
        else -> null
    }

    private fun seenOf(state: SessionState) =
        Seen(state.history.filterIsInstance<HistoryEntry.Assistant>().map { it.messageId }.toMutableSet(), state.toolResults.keys.toMutableSet())

    private fun line(obj: JsonObject) {
        synchronized(out) { out.println(obj.toString()) }
    }
}
