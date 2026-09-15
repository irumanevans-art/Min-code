package dev.min.code.core.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Claude Code 官方 CLI 的 headless stream-json 协议（NDJSON over stdin/stdout）。
 *
 * 启动形态见 [ClaudeCodeManager]；CLI 从 stdin 读 user / control_response 帧，
 * 向 stdout 写事件帧。
 *
 * 下面的 schema 对照官方 CLI 二进制（@anthropic-ai/claude-code，最近一次完整校对
 * v2.1.271+；2.1.270 的 permission_denials / bashEditDiff 等仍有效）中内嵌的 zod schema。
 * 2.1.271–272 无 stream-json 形状大改（MCP-only resume、`-p` Monitor 截止等在 CLI 侧）。
 * 未知字段/类型一律宽容忽略以保持向后兼容，但**必填字段一个都不能少** —— CLI 对入站帧做严格校验，
 * 缺字段会被静默丢弃或报 "canUseTool returned a schema-invalid permission result"。
 */
sealed interface ClaudeCodeEvent {
    /** system/init：会话建立，给出 session_id / 工具集 / 模型 */
    data class Init(
        val sessionId: String,
        val model: String?,
        val tools: List<String>,
    ) : ClaudeCodeEvent

    /**
     * CLI 给会话拟的标题。
     *
     * transcript 里其实是**两行**：
     * - `{"type":"custom-title","customTitle":"…"}` —— 人手 `/rename`（或 rename_session）
     * - `{"type":"ai-title","aiTitle":"…"}` —— Haiku 自动拟名（首轮结束后异步写入）
     *
     * 以前只认 custom-title，无头会话几乎永远落不到人手改名那一行，列表就一直停在
     * 第一条用户消息上。两行都映射到这里；磁盘摘要里 **custom-title 覆盖 ai-title**。
     */
    data class CustomTitle(val title: String) : ClaudeCodeEvent

    /** assistant 消息里的文本块（整块，非增量） */
    data class AssistantText(val text: String) : ClaudeCodeEvent

    /**
     * 用户消息。实时流里不会出现（除非开 `--replay-user-messages`），
     * 只有回放 transcript 重建历史时才产生 —— 这样历史和实时走同一条渲染路径。
     */
    data class UserMessage(val text: String) : ClaudeCodeEvent

    /** assistant 消息里的思考块（整块，非增量） */
    data class Thinking(val text: String) : ClaudeCodeEvent

    /**
     * `--include-partial-messages` 下的增量帧。整条 assistant 消息随后仍会完整送达，
     * 因此消费方必须把增量渲染进"临时缓冲"，等 [AssistantText] 到达时丢弃缓冲，避免文本翻倍。
     */
    data class PartialText(val text: String, val thinking: Boolean) : ClaudeCodeEvent

    /** 一条 assistant 消息开始，用于清空上一轮的增量缓冲 */
    data object PartialStart : ClaudeCodeEvent

    /**
     * 这条 assistant 消息**到目前为止**的累计输出 token（SSE 的 `message_delta.usage`）。
     *
     * 注意是「这条消息」的累计值，不是整轮的 —— 一轮里有几次工具往返就有几条消息，
     * 累加要在消息边界（[PartialStart]）处结转，直接相加会翻倍。
     *
     * 用它而不是拿字符数估算：估出来的数写在界面上和真实计费对不上，
     * 而这一栏存在的意义正是"这一轮到底吐了多少"。代价是它随消息边界跳变而不是平滑增长。
     */
    data class OutputTokens(val cumulativeForMessage: Int) : ClaudeCodeEvent

    /** assistant 发起的工具调用 */
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject,
    ) : ClaudeCodeEvent

    /** 工具执行结果（type=user 的 tool_result 块） */
    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean,
        /**
         * Bash 改文件后的 unified diff（CLI 2.1.269+，`tool_use_result.bashEditDiff`）。
         * 只给界面看；喂给模型的仍是 [content] 那串 stdout/stderr。
         */
        val editDiff: String? = null,
    ) : ClaudeCodeEvent

    /**
     * control_request/can_use_tool：权限确认，必须用 [encodeClaudeCodePermissionResponse] 应答。
     *
     * 只有在启动参数里带了 `--permission-prompt-tool stdio` 时 CLI 才会发这个帧；
     * 否则需要审批的工具会被直接终局拒绝（详见 [ClaudeCodeManager] 的启动参数注释）。
     */
    data class PermissionRequest(
        val requestId: String,
        val toolName: String,
        val input: JsonObject,
        /** CLI 给出的人类可读说明，例如 "Run `ls -la` in /workspace" */
        val description: String? = null,
        /** 触发审批的原因（rule / mode / hook / classifier ...） */
        val decisionReason: String? = null,
        /** 被拦截的路径，越权写入时出现 */
        val blockedPath: String? = null,
        val toolUseId: String? = null,
        /**
         * CLI 随请求附带的**现成 PermissionUpdate 对象**（`permission_suggestions`）。
         * 这就是"始终允许"的实现方式：把用户选中的那条原样放进 allow 应答的
         * `updatedPermissions` 里，CLI 自己去写规则，之后同类调用不再询问。
         */
        val suggestions: List<PermissionSuggestion> = emptyList(),
    ) : ClaudeCodeEvent

    /**
     * 其他我们不支持的 control_request（request_user_dialog 等）。
     * **必须回一个 error control_response**，否则 CLI 会一直等待应答直到超时。
     */
    data class UnsupportedControlRequest(
        val requestId: String,
        val subtype: String,
    ) : ClaudeCodeEvent

    /** CLI 对我们发出的 control_request（如 interrupt）的失败应答 */
    data class ControlError(val requestId: String, val error: String) : ClaudeCodeEvent

    /**
     * CLI 对我们发出的 control_request 的成功应答。
     * `payload` 是 `response.response`，形状随 subtype 变（list_models 给模型目录、
     * initialize 给 commands/models、get_plan 给计划正文……），由发起方按 requestId 认领。
     */
    data class ControlOk(val requestId: String, val payload: JsonObject) : ClaudeCodeEvent

    /** 一轮任务结束 */
    data class Result(
        val isError: Boolean,
        val subtype: String,
        val durationMs: Long?,
        val numTurns: Int?,
        val totalCostUsd: Double?,
        val sessionId: String?,
        val resultText: String?,
        /**
         * 本轮被路径规则等拦下、没真正执行的工具（CLI `permission_denials`）。
         * 2.1.269 起 path-scoped 的 Read/Edit/Write 也会进这里。
         */
        val permissionDenials: List<PermissionDenial> = emptyList(),
    ) : ClaudeCodeEvent

    /** `result.permission_denials[]` 的一项（v2.1.270：`tool_name` / `tool_use_id` / `tool_input`） */
    data class PermissionDenial(
        val toolName: String,
        val toolUseId: String,
        val input: JsonObject = JsonObject(emptyMap()),
    )

    /** 其他 system 提示（compact_boundary / api_error / model_fallback ...） */
    data class SystemNote(val text: String, val isError: Boolean = false) : ClaudeCodeEvent

    /**
     * 安全分类器 / 配额等把模型换掉。Note 仍会进聊天流；这条让 Manager 同步 chip 状态。
     * [fallbackModel] 为空表示拒答且没有可退模型（只提示、不改状态）。
     */
    data class ModelFallback(
        val originalModel: String?,
        val fallbackModel: String?,
    ) : ClaudeCodeEvent

    /**
     * 瞬时运行状态。**必须原地替换，不能往聊天流里追加** —— 一轮任务能刷几十条。
     *
     * 两个来源：
     * - `system/status`  → [phase]，取值 idle/busy/working/waiting/thinking/responding/compacting
     * - `system/task_summary` → [detail]，CLI 自己的说明是
     *   "Mid-turn progress line from the debounced classifier … the same live phrase"，
     *   也就是桌面端顶上那句会变的短语；`detail` 为 null 表示回到空闲、清掉。
     */
    data class Status(val phase: String? = null, val detail: String? = null) : ClaudeCodeEvent

    /**
     * `system/api_retry`：请求失败但可重试，CLI 即将退避重发。
     * 不显示的话用户只看到界面卡住不动，完全不知道正在重试。
     */
    data class ApiRetry(
        val attempt: Int?,
        val maxRetries: Int?,
        val retryDelayMs: Int?,
        /** 连接类错误（超时等）时为 null */
        val errorStatus: Int?,
        val message: String?,
        /** `error.formatted`，中转站给人看的原句，里面经常带着 HTTP 502 这类字 */
        val formatted: String? = null,
    ) : ClaudeCodeEvent

    /**
     * 子 agent 干的一件事，[parentToolUseId] 是发起它的那次 Task 调用的 `tool_use` id。
     *
     * ## 为什么要单独包一层
     *
     * CLI 把子 agent 的消息和主线程的消息**混在同一条 stdout 上**发出来，靠帧顶层的
     * `parent_tool_use_id` 区分（主线程是 null）。之前这里不看这个字段，后果有两个：
     *
     * 1. 子 agent 的思考和工具调用被平铺进主会话流，和主 agent 自己干的活混成一锅，
     *    读的人分不出哪条是谁做的；
     * 2. 更糟的是子 agent 的 `stream_event` 增量会写进**同一个**流式缓冲区 ——
     *    主 agent 正在打字时子 agent 一说话，主 agent 那段正文就被冲掉了。
     *
     * 包一层之后，这些事件由 [ClaudeCodeManager] 挂到对应那条 Task 工具卡底下，
     * 展开就能看子任务的完整过程；官方终端只给一个折叠的计数行，看不到里面。
     */
    data class Subagent(
        val parentToolUseId: String,
        val event: ClaudeCodeEvent,
    ) : ClaudeCodeEvent

    /**
     * 子 agent / 后台任务的生命周期。对应 `task_started` / `task_progress` / `task_updated`。
     * 同样是原地更新一个任务表，而不是往聊天流里追加。
     */
    data class TaskEvent(
        val taskId: String,
        /** running / completed / failed / killed / paused / pending；null 表示这一帧不改状态 */
        val status: String? = null,
        val description: String? = null,
        val subagentType: String? = null,
        val backgrounded: Boolean? = null,
        val totalTokens: Int? = null,
        val toolUses: Int? = null,
        val durationMs: Long? = null,
        val lastToolName: String? = null,
        val summary: String? = null,
        val error: String? = null,
    ) : ClaudeCodeEvent
}

/**
 * 一条权限建议。[raw] 是 CLI 给的 PermissionUpdate 对象，**必须原样回传**——
 * 它的形状（zod schema，v2.1.246 二进制）是按 `type` 判别的联合：
 *
 * - `{type:"addRules", rules:[{toolName, ruleContent?}], behavior:"allow"|"deny"|"ask", destination}`
 * - `{type:"replaceRules"|"removeRules", ...}`
 * - `{type:"setMode", mode, destination}`
 * - `{type:"addDirectories"|"removeDirectories", directories:[...], destination}`
 *
 * destination ∈ `session | localSettings | projectSettings | userSettings | flagSettings | policySettings`。
 *
 * 我们不去建模它 —— 字段会随 CLI 版本变，自己拼一个反而容易被 schema 校验打回
 * （"malformed updatedPermissions ignored"）。只解析出一个按钮文案 [label]，
 * 回传时把 [raw] 原封不动塞回去。
 */
data class PermissionSuggestion(val label: String, val raw: JsonObject)

private val protocolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** result 帧的 subtype 里，只有 "success" 不是错误 */
private const val RESULT_SUCCESS = "success"

/**
 * 解析一行 NDJSON 为事件序列。assistant/user 消息含多个 content 块时展开为多个事件；
 * 空行、非 JSON、未知类型返回空列表（宽容降级，不打断会话）。
 *
 * **所有字段访问都必须走本文件底部那组 `as?` 安全访问器**，绝不能用 kotlinx 的
 * `.jsonArray` / `.jsonObject` / `.jsonPrimitive` —— 那三个是硬转换，类型不符直接抛
 * `IllegalArgumentException("Element class ... is not a JsonArray")`。异常会从
 * [parseClaudeCodeEvents] 一路冒到 ClaudeCodeManager.readLoop 的 catch，把**整条 stdout
 * 读取循环**打死（CLI 进程还活着还在写，App 却再也不读了），表现为"会话中断"。
 *
 * 真实踩过的坑：`type:"user"` 的 `message.content` 在协议里是 `string | ContentBlock[]`
 * 二选一 —— 工具结果是数组，而纯文本用户消息、本地斜杠命令的回显
 * （`<command-name>/model</command-name>`、`<local-command-stdout>…</local-command-stdout>`）
 * 都是**裸字符串**。
 */
fun parseClaudeCodeEvents(line: String): List<ClaudeCodeEvent> {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith("{")) return emptyList()
    val obj = runCatching { protocolJson.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
        ?: return emptyList()
    val type = obj.str("type") ?: return emptyList()

    return when (type) {
        "system" -> when (val subtype = obj.str("subtype")) {
            "init" -> listOf(
                ClaudeCodeEvent.Init(
                    sessionId = obj.str("session_id").orEmpty(),
                    model = obj.str("model"),
                    tools = obj.arr("tools")
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty(),
                )
            )

            else -> systemNote(subtype, obj)
        }

        // 顶层的 parent_tool_use_id 非空 = 这一帧是某个子 agent 干的，见 [Subagent]
        "custom-title", "ai-title" -> titleFromFrame(obj, type)
            ?.let { listOf(ClaudeCodeEvent.CustomTitle(it)) }
            .orEmpty()

        "assistant" -> expandAssistantMessage(obj).underSubagent(obj.str("parent_tool_use_id"))

        "user" -> expandToolResults(obj).underSubagent(obj.str("parent_tool_use_id"))

        // 子 agent 的增量**直接丢掉**：流式缓冲区只有一个，把它的 token 混进去会把
        // 主 agent 正在生成的那段正文冲掉。子 agent 的内容靠上面那条整块消息补齐，
        // 打字机效果对一个折叠在卡片里的子任务也没有意义
        "stream_event" ->
            if (obj.str("parent_tool_use_id").isNullOrBlank()) expandStreamEvent(obj) else emptyList()

        "result" -> {
            val subtype = obj.str("subtype").orEmpty()
            listOf(
                ClaudeCodeEvent.Result(
                    // success 变体没有 is_error 字段；错误变体才有。两边都覆盖。
                    isError = obj.bool("is_error")
                        ?: (subtype.isNotEmpty() && subtype != RESULT_SUCCESS),
                    subtype = subtype,
                    durationMs = obj.long("duration_ms"),
                    numTurns = obj.int("num_turns"),
                    totalCostUsd = obj.double("total_cost_usd"),
                    sessionId = obj.str("session_id"),
                    resultText = obj.str("result"),
                    permissionDenials = parsePermissionDenials(obj.arr("permission_denials")),
                )
            )
        }

        "control_request" -> {
            val request = obj.obj("request") ?: return emptyList()
            val requestId = obj.str("request_id").orEmpty()
            when (val subtype = request.str("subtype")) {
                "can_use_tool" -> listOf(
                    ClaudeCodeEvent.PermissionRequest(
                        requestId = requestId,
                        toolName = request.str("tool_name").orEmpty(),
                        input = request.obj("input") ?: JsonObject(emptyMap()),
                        description = request.str("description"),
                        decisionReason = request.str("decision_reason"),
                        blockedPath = request.str("blocked_path"),
                        toolUseId = request.str("tool_use_id"),
                        suggestions = request.arr("permission_suggestions")
                            ?.mapNotNull { it as? JsonObject }
                            ?.mapNotNull { update ->
                                permissionSuggestionLabel(update)
                                    ?.let { PermissionSuggestion(it, update) }
                            }
                            .orEmpty(),
                    )
                )

                null -> emptyList()
                else -> listOf(ClaudeCodeEvent.UnsupportedControlRequest(requestId, subtype))
            }
        }

        "control_response" -> {
            val response = obj.obj("response") ?: return emptyList()
            val requestId = response.str("request_id").orEmpty()
            when (response.str("subtype")) {
                "error" -> listOf(
                    ClaudeCodeEvent.ControlError(requestId, response.str("error").orEmpty())
                )

                // 载荷形状随 subtype 变，非对象（true / null / 字符串）也要能认领，
                // 否则发起方会一直等到超时——"切换模型失败：CLI 未应答"就是这么来的
                "success" -> listOf(
                    ClaudeCodeEvent.ControlOk(
                        requestId = requestId,
                        payload = response.obj("response") ?: JsonObject(emptyMap()),
                    )
                )

                else -> emptyList()
            }
        }

        else -> emptyList()
    }
}

/**
 * 给一批事件套上「这是子 agent 干的」这层信封。[parentToolUseId] 为空表示主线程，原样返回。
 */
private fun List<ClaudeCodeEvent>.underSubagent(parentToolUseId: String?): List<ClaudeCodeEvent> =
    if (parentToolUseId.isNullOrBlank()) this
    else map { ClaudeCodeEvent.Subagent(parentToolUseId, it) }

/**
 * 把一条完整 assistant 消息展开为内容块事件序列（text / thinking / tool_use）。
 *
 * `message.content` 是 `string | ContentBlock[]`：老版本 CLI 和部分 transcript 行
 * 会直接给一个裸字符串，这里当成单个 text 块处理，绝不能硬转数组。
 */
fun expandAssistantMessage(obj: JsonObject): List<ClaudeCodeEvent> {
    return when (val content = obj.obj("message")?.get("content")) {
        is JsonPrimitive -> content.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(ClaudeCodeEvent.AssistantText(it)) }
            .orEmpty()

        is JsonArray -> content.mapNotNull { block ->
            val blockObj = block as? JsonObject ?: return@mapNotNull null
            when (blockObj.str("type")) {
                "text" -> ClaudeCodeEvent.AssistantText(blockObj.str("text").orEmpty())
                "thinking" -> ClaudeCodeEvent.Thinking(blockObj.str("thinking").orEmpty())
                "tool_use" -> ClaudeCodeEvent.ToolUse(
                    id = blockObj.str("id").orEmpty(),
                    name = blockObj.str("name").orEmpty(),
                    input = blockObj.obj("input") ?: JsonObject(emptyMap()),
                )

                else -> null
            }
        }

        else -> emptyList()
    }
}

/**
 * 展开 type=user 消息里的 tool_result 块。
 *
 * `message.content` 是裸字符串时表示这是一条**真的用户文本**（键盘输入、或本地斜杠命令的
 * 回显），里面不可能有 tool_result —— 返回空列表交给调用方，别去硬转数组。
 *
 * 帧顶层的 `tool_use_result`（或 camelCase `toolUseResult`）是工具的完整 Output 对象，
 * **不进模型上下文**。CLI 2.1.269+ 的 Bash 会在这里挂 `bashEditDiff`；同帧里多块
 * tool_result 时只把 diff 挂到**第一块**上（Bash 一轮只有一个结果）。
 */
fun expandToolResults(obj: JsonObject): List<ClaudeCodeEvent.ToolResult> {
    val content = obj.obj("message")?.get("content") as? JsonArray ?: return emptyList()
    val editDiff = parseBashEditDiff(
        obj.obj("tool_use_result") ?: obj.obj("toolUseResult"),
    )
    var attachedDiff = false
    return content.mapNotNull { block ->
        val blockObj = block as? JsonObject ?: return@mapNotNull null
        if (blockObj.str("type") != "tool_result") return@mapNotNull null
        val text = when (val rawContent = blockObj["content"]) {
            is JsonPrimitive -> rawContent.contentOrNull.orEmpty()
            is JsonArray -> rawContent.mapNotNull { part ->
                (part as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
            }.joinToString("\n")

            else -> ""
        }
        val diff = if (!attachedDiff && editDiff != null) {
            attachedDiff = true
            editDiff
        } else {
            null
        }
        ClaudeCodeEvent.ToolResult(
            toolUseId = blockObj.str("tool_use_id").orEmpty(),
            content = text,
            isError = blockObj.bool("is_error") == true,
            editDiff = diff,
        )
    }
}

/**
 * `result.permission_denials`：`[{tool_name, tool_use_id, tool_input}]`（v2.1.270 XF schema）。
 * 缺字段或类型不对就跳过该项，绝不让整条 result 帧解析失败。
 */
internal fun parsePermissionDenials(arr: JsonArray?): List<ClaudeCodeEvent.PermissionDenial> {
    if (arr == null) return emptyList()
    return arr.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val name = obj.str("tool_name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ClaudeCodeEvent.PermissionDenial(
            toolName = name,
            toolUseId = obj.str("tool_use_id").orEmpty(),
            input = obj.obj("tool_input") ?: JsonObject(emptyMap()),
        )
    }
}

/**
 * 从 Bash 的 `tool_use_result.bashEditDiff` 拼出 unified diff 文本（给工具卡展开态用）。
 *
 * schema（v2.1.270）：
 * ```
 * bashEditDiff?: {
 *   files: [{filePath, hunks:[{oldStart,oldLines,newStart,newLines,lines:string[]}],
 *            created?:true, deleted?:true}],
 *   moreFiles: number, changedFiles?: string[],
 *   unavailable?:true, skipped?:true, shared?:true
 * }
 * ```
 * `unavailable` / `skipped`、或没有任何 hunk，返回 null（界面继续只显示 stdout）。
 */
internal fun parseBashEditDiff(toolUseResult: JsonObject?): String? {
    val diff = toolUseResult?.obj("bashEditDiff") ?: return null
    if (diff.bool("unavailable") == true || diff.bool("skipped") == true) return null
    val files = diff.arr("files") ?: return null
    val parts = ArrayList<String>()
    for (element in files) {
        val file = element as? JsonObject ?: continue
        val path = file.str("filePath")?.takeIf { it.isNotBlank() } ?: continue
        val hunks = file.arr("hunks") ?: continue
        val hunkText = buildString {
            for (hunkEl in hunks) {
                val hunk = hunkEl as? JsonObject ?: continue
                val lines = hunk.arr("lines") ?: continue
                if (lines.isEmpty()) continue
                val oldStart = hunk.int("oldStart") ?: 0
                val oldLines = hunk.int("oldLines") ?: 0
                val newStart = hunk.int("newStart") ?: 0
                val newLines = hunk.int("newLines") ?: 0
                append("@@ -").append(oldStart).append(',').append(oldLines)
                    .append(" +").append(newStart).append(',').append(newLines)
                    .append(" @@\n")
                for (lineEl in lines) {
                    val line = (lineEl as? JsonPrimitive)?.contentOrNull ?: continue
                    append(line)
                    if (!line.endsWith('\n')) append('\n')
                }
            }
        }.trimEnd()
        if (hunkText.isBlank()) continue
        val status = when {
            file.bool("created") == true -> "new file mode"
            file.bool("deleted") == true -> "deleted file mode"
            else -> null
        }
        parts += buildString {
            if (status != null) append(status).append('\n')
            append("--- a/").append(path).append('\n')
            append("+++ b/").append(path).append('\n')
            append(hunkText)
        }
    }
    val more = diff.int("moreFiles") ?: 0
    if (more > 0) {
        parts += "… 还有 $more 个文件的改动未展开"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

/**
 * `--include-partial-messages` 下的 stream_event 帧：`event` 字段是原始的 Anthropic SSE 事件。
 * 只取文本/思考增量用于打字机效果，工具入参增量交给整块 assistant 消息处理。
 */
private fun expandStreamEvent(obj: JsonObject): List<ClaudeCodeEvent> {
    val event = obj.obj("event") ?: return emptyList()
    return when (event.str("type")) {
        "message_start" -> listOf(ClaudeCodeEvent.PartialStart)

        // `message_delta` 带的是**这条消息到目前为止**的累计输出 token
        // （Anthropic SSE 规范：delta 里是 stop_reason 之类的顶层变更，usage 挂在同级）。
        // 有的版本把 usage 塞进 delta 里，两处都看一眼。
        "message_delta" -> (event.obj("usage") ?: event.obj("delta")?.obj("usage"))
            ?.int("output_tokens")
            ?.let { listOf(ClaudeCodeEvent.OutputTokens(it)) }
            .orEmpty()

        "content_block_delta" -> {
            val delta = event.obj("delta") ?: return emptyList()
            when (delta.str("type")) {
                "text_delta" -> delta.str("text")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { listOf(ClaudeCodeEvent.PartialText(it, thinking = false)) }
                    .orEmpty()

                "thinking_delta" -> delta.str("thinking")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { listOf(ClaudeCodeEvent.PartialText(it, thinking = true)) }
                    .orEmpty()

                else -> emptyList()
            }
        }

        else -> emptyList()
    }
}

/**
 * 发送一条用户消息（stream-json input 格式）。
 *
 * `parent_tool_use_id` 在 CLI 的 schema 里是 `e().nullable()`，官方 SDK 也总是显式写 null。
 * 实测省略它当前不会被拒（CLI 解析是宽容的），但显式带上更贴合官方 SDK 的线格式，
 * 也避免将来 schema 收紧时踩坑。
 *
 * `origin.kind = "human"` 是键盘输入的来源标记：CLI 对此的说明是"缺失会被当作未署名，
 * 并在严格 isHuman() 信任门处 fail closed"。实测该字段会被原样回显在 result 帧里。
 *
 * [images] 走 Anthropic 标准的 image content block。**不能靠"把图片路径写进文本里"代替** ——
 * 那样只是让模型去 Read 一个文件，多绕一次工具调用，而且截图这种临时图片根本不该先落盘。
 * 图片块排在文本前面：Anthropic 的提示工程指南明确建议图在前、问题在后。
 */
fun encodeClaudeCodeUserMessage(
    text: String,
    images: List<ClaudeCodeImage> = emptyList(),
): String =
    buildJsonObject {
        put("type", "user")
        put("parent_tool_use_id", JsonNull)
        put("message", buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                images.forEach { image ->
                    add(buildJsonObject {
                        put("type", "image")
                        put("source", buildJsonObject {
                            put("type", "base64")
                            put("media_type", image.mediaType)
                            put("data", image.base64)
                        })
                    })
                }
                if (text.isNotEmpty()) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                }
            })
        })
        put("origin", buildJsonObject { put("kind", "human") })
    }.toString()

/** 一张待发送的图片，已经解码/缩放/编码好 */
data class ClaudeCodeImage(
    /** `image/png` / `image/jpeg` / `image/webp` / `image/gif` —— Anthropic 只认这四种 */
    val mediaType: String,
    val base64: String,
)

/**
 * 权限应答：allow 或 deny。
 *
 * 信封形状取自 CLI 自身的构造函数：
 * `{type:"control_response", response:{subtype:"success", request_id, response:<result>}}`。
 * **`subtype` 不能省** —— 缺了会命中 schema 校验失败。
 *
 * result 的 schema：
 * - `{behavior:"allow", updatedInput?:object, updatedPermissions?:array, toolUseID?:string}`
 * - `{behavior:"deny", message:string, interrupt?:boolean}`
 *
 * allow 时**不回传 updatedInput**：它是可选的，且 CLI 会拿它重新跑一遍工具入参 schema 校验，
 * 原样回显反而多一次失败机会（"Permission handler updatedInput for X failed schema validation"）。
 * 只有将来支持"改参数后批准"时才需要带上。
 *
 * [updatedPermissions] 则相反，是"始终允许"的**唯一**实现途径：把 can_use_tool 请求里
 * `permission_suggestions` 给的那条 PermissionUpdate 原样放回来，CLI 就会把规则写进
 * 对应的 destination，之后同类调用不再询问。不传的话每一次 Bash / Edit 都要重新点一遍。
 */
fun encodeClaudeCodePermissionResponse(
    requestId: String,
    allow: Boolean,
    denyMessage: String? = null,
    updatedPermissions: List<JsonObject> = emptyList(),
    updatedInput: JsonObject? = null,
): String = buildJsonObject {
    put("type", "control_response")
    put("response", buildJsonObject {
        put("subtype", "success")
        put("request_id", requestId)
        put("response", buildJsonObject {
            if (allow) {
                put("behavior", "allow")
                if (updatedPermissions.isNotEmpty()) {
                    put("updatedPermissions", buildJsonArray { updatedPermissions.forEach { add(it) } })
                }
                // AskUserQuestion 就是靠它把用户的选择送回去的，见 [buildAskUserQuestionAnswer]
                if (updatedInput != null) put("updatedInput", updatedInput)
            } else {
                put("behavior", "deny")
                put("message", denyMessage?.takeIf { it.isNotBlank() } ?: "User denied")
            }
        })
    })
}.toString()

// ---------------------------------------------------------------------------
// AskUserQuestion
// ---------------------------------------------------------------------------

/** AskUserQuestion 的一个选项 */
data class ClaudeCodeQuestionOption(
    val label: String,
    val description: String? = null,
    /** 选项预览（代码片段 / ASCII 草图），仅单选时可能出现 */
    val preview: String? = null,
)

/** AskUserQuestion 里的一问。一次调用带 1–4 问，每问 2–4 个选项。 */
data class ClaudeCodeQuestion(
    val question: String,
    /** 极短标签（≤12 字），CLI 用它做 chip */
    val header: String? = null,
    val multiSelect: Boolean = false,
    val options: List<ClaudeCodeQuestionOption> = emptyList(),
)

/** 这次权限请求是不是「向用户提问」而不是「要不要允许某个工具」 */
const val ASK_USER_QUESTION_TOOL = "AskUserQuestion"

/**
 * 解析 AskUserQuestion 的入参。
 *
 * 形状是 `{questions:[{question, header, multiSelect, options:[{label, description, preview}]}]}` ——
 * **注意是复数 `questions` 数组**，不是单个 `question` 字段。之前折叠态摘要读的是
 * `input["question"]`，永远取不到，所以卡片上只显示兜底的"向用户提问"。
 */
fun parseAskUserQuestions(input: JsonObject): List<ClaudeCodeQuestion> =
    input["questions"].asJsonArrayOrNull()
        ?.mapNotNull { it.asJsonObjectOrNull() }
        ?.mapNotNull { obj ->
            val question = obj["question"].asStringOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            ClaudeCodeQuestion(
                question = question,
                header = obj["header"].asStringOrNull(),
                multiSelect = obj["multiSelect"].asBooleanOrNull() == true,
                options = obj["options"].asJsonArrayOrNull()
                    ?.mapNotNull { it.asJsonObjectOrNull() }
                    ?.mapNotNull { opt ->
                        opt["label"].asStringOrNull()?.takeIf { it.isNotBlank() }?.let { label ->
                            ClaudeCodeQuestionOption(
                                label = label,
                                description = opt["description"].asStringOrNull(),
                                preview = opt["preview"].asStringOrNull(),
                            )
                        }
                    }
                    .orEmpty(),
            )
        }
        .orEmpty()

/**
 * 把用户的选择拼成回给 CLI 的 `updatedInput`。
 *
 * 机制：AskUserQuestion 的入参里有一个 `answers` 字段，官方 schema 对它的说明是
 * "User answers collected by the permission component" —— 也就是说，**客户端的职责是把
 * 用户的选择填进这个字段再原样回传**，工具随后就把它当作自己的执行结果。
 * key 是问题原文，value 是选中项的 label（多选用 `, ` 连接）。
 *
 * 必须在**原始入参之上**增补，而不是只回一个 `{answers:...}`：CLI 会拿 updatedInput
 * 重新跑一遍工具入参的 schema 校验，缺了 `questions` 会直接判不合法。
 */
fun buildAskUserQuestionAnswer(input: JsonObject, answers: Map<String, String>): JsonObject =
    buildJsonObject {
        input.forEach { (key, value) -> if (key != "answers") put(key, value) }
        put("answers", buildJsonObject { answers.forEach { (k, v) -> put(k, v) } })
    }

/**
 * 给一条 PermissionUpdate 起个按钮文案。返回 null 表示这条不适合做成"一键批准"按钮
 * （removeRules / replaceRules 这类语义上不是"放宽"，摆在批准对话框里会误导）。
 */
private fun permissionSuggestionLabel(update: JsonObject): String? = when (update.str("type")) {
    "addRules" -> {
        val rules = update.arr("rules")
            ?.mapNotNull { it as? JsonObject }
            ?.map { rule ->
                rule.str("toolName").orEmpty() + (rule.str("ruleContent")?.let { "($it)" } ?: "")
            }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val verb = when (update.str("behavior")) {
            "allow" -> "始终允许"
            "deny" -> "始终拒绝"
            "ask" -> "以后仍然询问"
            else -> null
        }
        if (rules.isEmpty() || verb == null) null
        else "$verb ${rules.joinToString("、")}${destinationSuffix(update.str("destination"))}"
    }

    "setMode" -> update.str("mode")?.let { mode ->
        "切换到「${ClaudeCodePermissionMode.fromWire(mode)?.label ?: mode}」模式"
    }

    "addDirectories" -> update.arr("directories")
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.takeIf { it.isNotEmpty() }
        ?.let { "允许访问 ${it.joinToString("、")}${destinationSuffix(update.str("destination"))}" }

    else -> null
}

/** 规则写到哪一层，直接影响"下次还问不问"，必须让用户看见 */
private fun destinationSuffix(destination: String?): String = when (destination) {
    "session" -> "（仅本次会话）"
    "localSettings" -> "（本机）"
    "projectSettings" -> "（本项目）"
    "userSettings" -> "（全局）"
    else -> ""
}

/** 对我们不支持的 control_request 回一个 error，避免 CLI 空等到超时 */
fun encodeClaudeCodeControlError(requestId: String, error: String): String = buildJsonObject {
    put("type", "control_response")
    put("response", buildJsonObject {
        put("subtype", "error")
        put("request_id", requestId)
        put("error", error)
    })
}.toString()

/** 中断当前任务 */
fun encodeClaudeCodeInterrupt(requestId: String): String = buildJsonObject {
    put("type", "control_request")
    put("request_id", requestId)
    put("request", buildJsonObject {
        put("subtype", "interrupt")
        put("cancel_queued", true)
    })
}.toString()

/**
 * CLI 支持的权限模式。取值取自二进制里的运行时白名单
 * `["acceptEdits","auto","bypassPermissions","default","dontAsk","plan"]`，
 * 传别的值会报 "Cannot set permission mode: must be one of ..."。
 *
 * 只暴露 Claude Desktop 底栏那四档；auto / dontAsk 是内部档位，不放进 UI。
 */
enum class ClaudeCodePermissionMode(val wire: String, val label: String, val desc: String) {
    DEFAULT("default", "Manual", "每次改动前都询问"),
    ACCEPT_EDITS("acceptEdits", "Accept edits", "自动接受文件编辑"),
    PLAN("plan", "Plan", "先出计划，不直接改"),
    BYPASS("bypassPermissions", "Bypass permissions", "接受一切权限请求");

    companion object {
        fun fromWire(wire: String?): ClaudeCodePermissionMode? = entries.find { it.wire == wire }
    }
}

/**
 * 通用 control_request 编码器。信封形状与 [encodeClaudeCodeInterrupt] 一致，
 * 已被 CLI 实际接受（`system/init` 正常返回即证明 flag 与帧格式无误）。
 */
private fun controlRequest(
    requestId: String,
    subtype: String,
    body: JsonObject = JsonObject(emptyMap()),
): String = buildJsonObject {
    put("type", "control_request")
    put("request_id", requestId)
    put("request", buildJsonObject {
        put("subtype", subtype)
        body.forEach { (k, v) -> put(k, v) }
    })
}.toString()

/**
 * 握手。应答里一次给齐 `{commands, agents, output_style, available_output_styles, models, account, pid}`,
 * 斜杠命令列表和模型目录都从这里来。
 */
fun encodeClaudeCodeInitialize(requestId: String): String =
    controlRequest(requestId, "initialize")

/** 切模型。`model` 传 null / "default" 会重置为会话默认模型。 */
fun encodeClaudeCodeSetModel(requestId: String, model: String?): String =
    controlRequest(requestId, "set_model", buildJsonObject {
        if (model.isNullOrBlank()) put("model", JsonNull) else put("model", model)
    })

/** 切权限模式，对应 Desktop 底栏的 Manual / Accept edits / Plan / Bypass permissions */
fun encodeClaudeCodeSetPermissionMode(requestId: String, mode: ClaudeCodePermissionMode): String =
    controlRequest(requestId, "set_permission_mode", buildJsonObject { put("mode", mode.wire) })

/** 拉取本会话可选的模型目录（远端 worker 的 provider / 设置级联 / 策略决定，必须问而不能自己猜） */
fun encodeClaudeCodeListModels(requestId: String): String =
    controlRequest(requestId, "list_models")

/** 读取 plan 模式下的当前计划正文，喂给 Plan 面板 */
fun encodeClaudeCodeGetPlan(requestId: String): String =
    controlRequest(requestId, "get_plan")

/** 上下文用量，喂给顶栏指示器 */
fun encodeClaudeCodeGetContextUsage(requestId: String): String =
    controlRequest(requestId, "get_context_usage")

/** 本会话累计花费 */
fun encodeClaudeCodeGetSessionCost(requestId: String): String =
    controlRequest(requestId, "get_session_cost")

/**
 * 读取 CLI **实际采用**的配置，而不是我们请求的配置。
 *
 * 实测应答：`{applied:{model, effort, advisor, ultracode}, effective:{...}, sources:[...]}`。
 * `applied.effort` 是 CLI 钳位/解析之后的真实档位 —— 例如传 `--effort ultracode` 时
 * 它是 `xhigh` 且 `applied.ultracode=true`；模型不支持某档时 CLI 会往下压，
 * 这里也能看出来。UI 应该显示这个值，而不是用户点的值，否则会出现
 * "界面显示 max、实际跑 high"的静默降级。
 */
fun encodeClaudeCodeGetSettings(requestId: String): String =
    controlRequest(requestId, "get_settings")

/**
 * 切换 CLI 的工作目录（`/cd` 的无头版）。
 *
 * **参数名是 `path`，不是 `cwd`。** 对照 CLI 2.1.267 的 schema：
 * `{subtype:"set_cwd", path, trust_accepted?, trusted_directory?}`。
 * 之前这里发的是 `cwd`，CLI 侧 `path` 就是 undefined，于是每次切目录都回
 * "set_cwd: invalid request — path must be a non-empty string"，界面上是两行红字。
 * 应答里的 `cwd` 是**返回**字段（切完之后的规范路径），跟请求参数不是一回事。
 *
 * [trustAccepted] / [trustedDirectory] 用于回答 `needs_trust`：CLI 把信任弹窗
 * 委托给宿主，同意之后必须原样回传当时给的 `directory`（见 [ClaudeCodeSetCwdResult]）。
 */
fun encodeClaudeCodeSetCwd(
    requestId: String,
    path: String,
    trustAccepted: Boolean = false,
    trustedDirectory: String? = null,
): String = controlRequest(requestId, "set_cwd", buildJsonObject {
    put("path", path)
    // 只有真的展示过信任提示才带这两个字段；trust_accepted 为 true 时 CLI 要求
    // trusted_directory 必须是它上一轮 needs_trust 给的那个字符串，逐字相同
    if (trustAccepted && trustedDirectory != null) {
        put("trust_accepted", true)
        put("trusted_directory", trustedDirectory)
    }
})

/**
 * `set_cwd` 的应答。CLI 用一个 `status` 字段分三种结局，**只有 `ok` 才真的换了目录**：
 *
 * - `ok`：`{status:"ok", cwd, changed, transcript_relocated}`，`cwd` 是规范化后的真实路径
 *   （realpath），要用它回填界面 —— 用户点的那个字符串可能带符号链接。
 * - `needs_trust`：`{status:"needs_trust", directory, trust_root?}`。**目录没有切**。
 *   CLI 把信任弹窗交给宿主：给用户看 `directory`，同意后带 `trust_accepted=true` +
 *   `trusted_directory=directory`（逐字回传）重发一次。
 * - `rejected`：`{status:"rejected", reason, message}`，reason ∈
 *   not_found / not_a_directory / blocked_by_rule / busy / unsafe_path。
 *
 * 以前这里不解析 status，`needs_trust` 和 `rejected` 都被当成成功：界面显示切过去了，
 * CLI 其实还在原地。
 */
sealed interface ClaudeCodeSetCwdResult {
    /** [cwd] 是 CLI 规范化后的路径；[changed] 为 false 表示本来就在那儿 */
    data class Ok(val cwd: String, val changed: Boolean) : ClaudeCodeSetCwdResult

    /** 要用户先同意信任 [directory]；[trustRoot] 非空时说明同意的是整个仓库 */
    data class NeedsTrust(val directory: String, val trustRoot: String?) : ClaudeCodeSetCwdResult

    data class Rejected(val reason: String, val message: String) : ClaudeCodeSetCwdResult
}

/** 解析 [encodeClaudeCodeSetCwd] 的应答载荷；形状不认识时按拒绝处理，不要假装成功 */
fun parseClaudeCodeSetCwdResult(payload: JsonObject): ClaudeCodeSetCwdResult =
    when (payload.str("status")) {
        "ok" -> ClaudeCodeSetCwdResult.Ok(
            cwd = payload.str("cwd").orEmpty(),
            changed = payload.bool("changed") ?: true,
        )

        "needs_trust" -> ClaudeCodeSetCwdResult.NeedsTrust(
            directory = payload.str("directory").orEmpty(),
            trustRoot = payload.str("trust_root")?.takeIf { it.isNotBlank() },
        )

        else -> ClaudeCodeSetCwdResult.Rejected(
            reason = payload.str("reason").orEmpty(),
            message = payload.str("message").orEmpty(),
        )
    }

/** 拒绝原因的中文说法。CLI 的 message 是英文的，先给一句能看懂的 */
fun claudeCodeSetCwdRejectionText(result: ClaudeCodeSetCwdResult.Rejected): String {
    val reason = when (result.reason) {
        "not_found" -> "这个目录不存在"
        "not_a_directory" -> "这个路径不是文件夹"
        "blocked_by_rule" -> "设置里的 Cd 规则不允许进这个目录"
        "busy" -> "这一轮对话还在跑，等它结束再切"
        "unsafe_path" -> "路径里有不可见字符，CLI 拒绝了"
        else -> "切换工作目录失败"
    }
    return if (result.message.isBlank()) reason else "$reason（${result.message}）"
}

/** 重命名会话 */
fun encodeClaudeCodeRenameSession(requestId: String, title: String): String =
    controlRequest(requestId, "rename_session", buildJsonObject { put("title", title) })

/**
 * 让 CLI 自己拟会话标题（Haiku 小请求）。
 *
 * 交互式终端会在首轮后自动跑；无头 `-p stream-json` **不会**，所以宿主要主动发。
 * wire 名是 snake_case 的 `generate_session_title`（SDK 方法叫 generateSessionTitle）。
 * [persist] 为 true 时 CLI 会把结果写成 transcript 的 `ai-title` 行。
 * 成功应答形如 `{title:"…"}`。
 */
fun encodeClaudeCodeGenerateSessionTitle(
    requestId: String,
    description: String,
    persist: Boolean = true,
): String = controlRequest(requestId, "generate_session_title", buildJsonObject {
    put("description", description)
    put("persist", persist)
})

/** 思考展示强度。CLI 没有 set_effort，effort 只能靠重启带 --effort，见 ClaudeCodeManager.applyEffort */
fun encodeClaudeCodeSetThinking(
    requestId: String,
    maxThinkingTokens: Int?,
    display: String?,
): String = controlRequest(requestId, "set_max_thinking_tokens", buildJsonObject {
    if (maxThinkingTokens == null) put("max_thinking_tokens", JsonNull)
    else put("max_thinking_tokens", maxThinkingTokens)
    display?.let { put("thinking_display", it) }
})

/**
 * 从标题帧取出文案。`custom-title` 看 customTitle/title/custom_title；
 * `ai-title` 看 aiTitle/ai_title/title。
 */
private fun titleFromFrame(obj: JsonObject, type: String?): String? {
    val raw = when (type) {
        "ai-title" -> obj.str("aiTitle") ?: obj.str("ai_title") ?: obj.str("title")
        else -> obj.str("customTitle") ?: obj.str("title") ?: obj.str("custom_title")
    }
    return raw?.trim()?.takeIf { it.isNotBlank() }
}

/**
 * 解析 CLI 自持久化的 transcript 行（`~/.claude/projects/<cwd>/<uuid>.jsonl`）。
 *
 * 实测这些行的 `user` / `assistant` 帧带的 `message` 字段与 stream-json 完全同构，
 * 只是多包了 `timestamp` / `uuid` / `parentUuid` / `cwd` / `gitBranch` 之类的元数据，
 * 所以内容展开直接复用 [expandAssistantMessage] / [expandToolResults]。
 *
 * 跳过：`isMeta`、以及 `attachment` / `queue-operation` / `atis-latch` 等内部行。
 *
 * 子 agent 的 `isSidechain` 行**不再整行丢掉**：带得出 `parent_tool_use_id` 的挂回对应的
 * Task 工具卡（[ClaudeCodeEvent.Subagent]），带不出的才跳过 —— 平铺进主线程会很乱，
 * 但整段扔掉就等于历史会话里永远看不到子任务干了什么。
 */
fun parseTranscriptLine(line: String): List<ClaudeCodeEvent> {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith("{")) return emptyList()
    val obj = runCatching { protocolJson.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
        ?: return emptyList()
    val type = obj.str("type")
    // 标题行偶尔会带 isMeta，但不能整行丢掉 —— 那是列表标题的来源
    if (obj.bool("isMeta") == true && type != "custom-title" && type != "ai-title") {
        return emptyList()
    }

    val parent = obj.str("parent_tool_use_id")?.takeIf { it.isNotBlank() }
    // 认不出归属的 sidechain 只能扔：挂不上任何一张卡，平铺又会污染主线程
    if (obj.bool("isSidechain") == true && parent == null) return emptyList()

    return when (type) {
        "custom-title", "ai-title" -> titleFromFrame(obj, type)
            ?.let { listOf(ClaudeCodeEvent.CustomTitle(it)) }
            .orEmpty()

        "assistant" -> expandAssistantMessage(obj).underSubagent(parent)

        "user" -> {
            // 一条 user 行要么是真的用户输入，要么是回填的 tool_result，二者不会混
            val toolResults = expandToolResults(obj)
            if (toolResults.isNotEmpty()) {
                toolResults.underSubagent(parent)
            } else if (parent == null) {
                when (val parsed = transcriptUserPayload(obj)) {
                    is TranscriptUser.Human -> listOf(ClaudeCodeEvent.UserMessage(parsed.text))
                    is TranscriptUser.Compact -> listOf(ClaudeCodeEvent.SystemNote(parsed.note))
                    null -> emptyList()
                }
            } else {
                // 子 agent 线程里的"用户消息"是 CLI 回填的任务提示词，不是人说的话
                emptyList()
            }
        }

        else -> emptyList()
    }
}

/**
 * CLI 把本地斜杠命令拆成两条 user 行写进 transcript：一条是命令信封、一条是命令输出。
 * 它们是内部记账，回放到聊天流里就是一堆裸 XML，标题摘要也会被它们占掉。
 */
private val LOCAL_COMMAND_ENVELOPES = listOf(
    "<command-name>", "<command-message>", "<command-args>", "<local-command-stdout>",
    "<local-command-stderr>", "<system-reminder>",
)

/**
 * 自动压缩之后 CLI 会把摘要写成一条 `type:user` 的 transcript 行，开头是
 * "This session is being continued from a previous conversation."
 * 它不是人说的话，回放成用户气泡就会变成截图里那一大段占位摘要。
 */
private val COMPACT_SUMMARY_PREFIXES = listOf(
    "This session is being continued from a previous conversation.",
    "This session is being continued from a previous conversation",
)

private sealed interface TranscriptUser {
    data class Human(val text: String) : TranscriptUser
    data class Compact(val note: String) : TranscriptUser
}

/** 从 transcript 的 user 行里取出纯文本；content 可能是字符串，也可能是内容块数组 */
private fun transcriptUserText(obj: JsonObject): String? =
    (transcriptUserPayload(obj) as? TranscriptUser.Human)?.text

private fun transcriptUserPayload(obj: JsonObject): TranscriptUser? {
    val content = obj.obj("message")?.get("content") ?: return null
    val text = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { block ->
            (block as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
        }.joinToString("\n")

        else -> ""
    }.trim()
    if (text.isBlank()) return null
    if (LOCAL_COMMAND_ENVELOPES.any { text.startsWith(it) }) return null
    if (COMPACT_SUMMARY_PREFIXES.any { text.startsWith(it) }) {
        return TranscriptUser.Compact(compactSummaryNote(text))
    }
    return TranscriptUser.Human(text)
}

/** 压缩摘要太长，聊天流里只留一行说明，完整原文不进用户气泡 */
internal fun compactSummaryNote(text: String): String {
    val tokens = Regex("""(\d[\d,]*)\s*tokens""", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
    return if (tokens != null) {
        "上下文已压缩（摘要约 ${tokens.replace(",", "")} tokens）"
    } else {
        "上下文已压缩为摘要，上一轮对话收进这条记录"
    }
}

/**
 * CLI 会源源不断地发 system 帧，其中绝大多数是内部计量，**不该出现在聊天流里**。
 * 实测里刷屏最凶的就是 `thinking_tokens` 和 `status`，一轮能刷几十条。
 *
 * 但反过来也踩过坑：**用户真正需要看到的那几条，字段名并不叫 `text` 或 `message`**，
 * 于是被一起丢掉了。对照 v2.1.246 二进制里的 zod schema：
 *
 *   local_command_output → `{content}`     "Displayed as assistant-style text in the transcript"
 *   informational        → `{content, level}`
 *   api_error            → `{error:{message, status?, formatted, ...}}`   ← formatted 才是给人看的
 *   model_fallback       → `{trigger, original_model, fallback_model, content}`
 *   worker_shutting_down → `{reason}`
 *   task_notification    → `{task_id, status, summary, skip_transcript?, ...}`
 *
 * 只看 `text`/`message` 的话，中转站报错、模型被静默换掉、CLI 即将退出、斜杠命令的输出
 * 全都无声无息。这里按 subtype 显式取值。
 *
 * 规则：
 * 1. 明确的噪声直接丢；
 * 2. 已知的用户可见帧按各自的字段取文案；
 * 3. 其余未知 subtype 仍然只认 `text`/`message`，认不出就丢 ——
 *    宁可漏显示，也不要把内部计量刷给用户。
 */
/**
 * 纯内部计量，永远不进聊天流也不进状态条。
 *
 * 注意这个名单**只放真正没有展示价值的**。曾经把 status / task_* / api_retry 也塞进来，
 * 结果就是"电脑端能看到的等待中、重试、子 agent 运行，手机上全都没有"——
 * 那些帧频率高，但不是没价值，它们的正确去处是原地替换的状态条（[ClaudeCodeEvent.Status]
 * / [ClaudeCodeEvent.TaskEvent]），而不是被丢掉。
 */
private val NOISE_SYSTEM_SUBTYPES = setOf(
    "thinking_tokens", "turn_duration", "post_turn_summary", "thinking",
    "control_request_progress",
    "background_tasks", "background_tasks_changed",
    "commands_changed", "file_snapshot", "files_persisted", "seed_read_state",
    "session_state_changed", "vcs_state_changed", "hook_started", "hook_progress",
    "hook_response", "mcp_status", "memory_recall", "memory_saved", "message_rated",
    "code_change_published", "file_suggestions", "apply_flag_settings", "away_summary",
    "feedback_draft_queued",
)

private fun systemNote(subtype: String?, obj: JsonObject): List<ClaudeCodeEvent> {
    if (subtype == null || subtype in NOISE_SYSTEM_SUBTYPES) return emptyList()
    return when (subtype) {
        // --- 状态条：原地替换，绝不追加 ------------------------------------------

        // {status: idle|busy|working|waiting|thinking|responding|compacting, permissionMode?, ...}
        "status" -> listOf(ClaudeCodeEvent.Status(phase = obj.str("status")))

        // {detail: string|null}；CLI: "the same live phrase" —— 桌面端顶上那句会变的短语，
        // detail 为 null 表示回到空闲，要清掉
        "task_summary" -> listOf(ClaudeCodeEvent.Status(detail = obj.str("detail")))

        // {attempt, max_retries, retry_delay_ms, error_status(nullable), error}
        // error 可能是字符串枚举，也可能是 {message, status, formatted} 对象。
        // 只读字符串时 502 的 formatted 整句会被丢掉。
        "api_retry" -> {
            val errorObj = obj.obj("error")
            listOf(
                ClaudeCodeEvent.ApiRetry(
                    attempt = obj.int("attempt"),
                    maxRetries = obj.int("max_retries"),
                    retryDelayMs = obj.int("retry_delay_ms"),
                    errorStatus = obj.int("error_status")
                        ?: errorObj?.int("status"),
                    message = errorObj?.str("message") ?: obj.str("error"),
                    formatted = errorObj?.str("formatted")
                        ?: errorObj?.str("error")
                        ?: obj.str("formatted"),
                )
            )
        }

        // --- 子 agent / 后台任务：维护一张任务表 ----------------------------------

        // {task_id, description, subagent_type?, is_backgrounded?, tool_use_id?}
        "task_started" -> obj.str("task_id")?.let { id ->
            listOf(
                ClaudeCodeEvent.TaskEvent(
                    taskId = id,
                    status = "running",
                    description = obj.str("description"),
                    subagentType = obj.str("subagent_type"),
                    backgrounded = obj.bool("is_backgrounded"),
                )
            )
        }.orEmpty()

        // {task_id, description, subagent_type?, usage:{total_tokens,tool_uses,duration_ms}, last_tool_name?, summary?}
        "task_progress" -> obj.str("task_id")?.let { id ->
            val usage = obj.obj("usage")
            listOf(
                ClaudeCodeEvent.TaskEvent(
                    taskId = id,
                    description = obj.str("description"),
                    subagentType = obj.str("subagent_type"),
                    totalTokens = usage?.int("total_tokens"),
                    toolUses = usage?.int("tool_uses"),
                    durationMs = usage?.long("duration_ms"),
                    lastToolName = obj.str("last_tool_name"),
                    summary = obj.str("summary"),
                )
            )
        }.orEmpty()

        // {task_id, patch:{status?, description?, error?, ...}}
        "task_updated" -> obj.str("task_id")?.let { id ->
            val patch = obj.obj("patch")
            listOf(
                ClaudeCodeEvent.TaskEvent(
                    taskId = id,
                    status = patch?.str("status"),
                    description = patch?.str("description"),
                    error = patch?.str("error"),
                    backgrounded = patch?.bool("is_backgrounded"),
                )
            )
        }.orEmpty()

        // --- 聊天流里值得留痕的 ---------------------------------------------------

        // CLI 原话："Output from a local slash command (e.g. /voice, /usage).
        // Displayed as assistant-style text in the transcript."
        // 无头模式下这是 /model、/usage 这类命令**唯一**的输出通道。
        "local_command_output" -> obj.str("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(ClaudeCodeEvent.AssistantText(it)) }
            .orEmpty()

        // level: info 只在 transcript 模式显示，别刷给用户；warning 走红字
        "informational" -> {
            val level = obj.str("level")
            if (level == "info") return emptyList()
            obj.str("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(ClaudeCodeEvent.SystemNote(it, isError = level == "warning")) }
                .orEmpty()
        }

        // 中转站 401 / 429 / 5xx 都从这里来。之前整条被丢，用户只看到"卡住不动"
        "api_error" -> {
            val error = obj.obj("error") ?: obj
            val status = error.int("status") ?: obj.int("status") ?: obj.int("error_status")
            val formatted = error.str("formatted") ?: error.str("error")
            val message = error.str("message") ?: obj.str("message")
            val text = formatApiFailure(formatted = formatted, message = message, status = status)
            listOf(ClaudeCodeEvent.SystemNote(text, isError = true))
        }

        // 模型被静默换掉：除了聊天流里的 Note，还要 emit ModelFallback 让 chip 跟着走
        "model_fallback", "model_consent_fallback" -> {
            val from = obj.str("original_model")
            val to = obj.str("fallback_model")
            val why = obj.str("trigger")?.let { "（$it）" }.orEmpty()
            val text = obj.str("content")?.takeIf { it.isNotBlank() }
                ?: "模型已回退：${from ?: "?"} → ${to ?: "?"}$why"
            listOf(
                ClaudeCodeEvent.SystemNote(text, isError = true),
                ClaudeCodeEvent.ModelFallback(from, to),
            )
        }

        // Fable 5.1 / Opus 5 的安全分类器可能拒答（API 层 stop_reason=refusal）。CLI 会按
        // fallbackModel 换一个模型重试（model_refusal_fallback），或者没有可退的就停
        // （model_refusal_no_fallback）。两种都必须说出来；有 fallback 时还要同步 chip。
        // 载荷（v2.1.261）：
        //   {content, original_model, fallback_model?, direction?, scope?, api_refusal_category?,
        //    api_refusal_explanation?, request_id?}
        "model_refusal_fallback", "model_refusal_no_fallback" -> {
            val from = obj.str("original_model")
            val to = obj.str("fallback_model")
            val category = obj.str("api_refusal_category")?.let { "（类别 $it）" }.orEmpty()
            val explanation = obj.str("api_refusal_explanation")?.takeIf { it.isNotBlank() }
            val text = obj.str("content")?.takeIf { it.isNotBlank() }
                ?: if (to != null) {
                    "模型拒绝了这次请求$category，已改用 $to 重试（原模型 ${from ?: "?"}）"
                } else {
                    "模型拒绝了这次请求$category，且没有可回退的模型（${from ?: "?"}）"
                }
            buildList {
                add(ClaudeCodeEvent.SystemNote(explanation?.let { "$text：$it" } ?: text, isError = true))
                add(ClaudeCodeEvent.ModelFallback(from, to))
            }
        }

        "worker_shutting_down" -> listOf(
            ClaudeCodeEvent.SystemNote(
                "CLI 正在退出：${obj.str("reason") ?: "未知原因"}",
                isError = true,
            )
        )

        // 子任务终局：既更新任务表，也在聊天流里留一条（除非 CLI 明确要求别记）
        "task_notification" -> {
            val id = obj.str("task_id")
            val rawStatus = obj.str("status")
            val summary = obj.str("summary")
            val usage = obj.obj("usage")
            val taskEvent = id?.let {
                ClaudeCodeEvent.TaskEvent(
                    taskId = it,
                    status = rawStatus,
                    summary = summary,
                    totalTokens = usage?.int("total_tokens"),
                    toolUses = usage?.int("tool_uses"),
                    durationMs = usage?.long("duration_ms"),
                )
            }
            if (obj.bool("skip_transcript") == true) return listOfNotNull(taskEvent)
            val label = when (rawStatus) {
                "completed" -> "完成"
                "failed" -> "失败"
                "stopped" -> "已停止"
                else -> "结束"
            }
            val tail = summary?.takeIf { it.isNotBlank() }?.let("："::plus).orEmpty()
            listOfNotNull(
                taskEvent,
                ClaudeCodeEvent.SystemNote("子任务$label$tail", isError = rawStatus == "failed"),
            )
        }

        "compact_boundary" -> {
            val meta = obj.obj("compact_metadata")
            val trigger = if (meta?.str("trigger") == "auto") "自动" else "手动"
            val pre = meta?.int("pre_tokens")
            val post = meta?.int("post_tokens")
            val detail = if (pre != null && post != null) "：${pre / 1000}k → ${post / 1000}k" else ""
            listOf(ClaudeCodeEvent.SystemNote("上下文已${trigger}压缩$detail"))
        }

        else -> {
            val text = obj.str("text") ?: obj.str("message")
            if (text.isNullOrBlank()) emptyList()
            else listOf(ClaudeCodeEvent.SystemNote(text))
        }
    }
}

/**
 * 把 API 失败收成一句能用来排查的话：HTTP 状态码、中转站原文、内部 message 都带上。
 * 502 这类网关错误以前经常只剩 "API 请求失败" 或 "未知错误"。
 */
internal fun formatApiFailure(formatted: String?, message: String?, status: Int?): String {
    val body = formatted?.takeIf { it.isNotBlank() }
        ?: message?.takeIf { it.isNotBlank() }
        ?: "API 请求失败"
    val alreadyHasStatus = status != null && (
        Regex("""\bHTTP\s*$status\b""", RegexOption.IGNORE_CASE).containsMatchIn(body) ||
            Regex("""\b$status\b""").containsMatchIn(body)
        )
    return if (status != null && !alreadyHasStatus) "$body（HTTP $status）" else body
}

/** 取字符串字段；缺失、类型不符或 JSON null 一律返回 null */
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

// --- 安全访问器 ---------------------------------------------------------------
// 这一组存在的唯一理由：kotlinx 的 `.jsonObject` / `.jsonArray` / `.jsonPrimitive` 是**硬转换**，
// 类型不符就抛 IllegalArgumentException。在 NDJSON 解析路径上抛异常 = 读取循环整个死掉 =
// "会话中断"。CLI 的 schema 会随版本漂移（本文件对照 v2.1.246 校对，用户实际跑 2.1.247），
// 所以这里一律用 `as?` 降级到 null，宁可少解析一个字段也不要炸掉整条流。

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull

private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull

private fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull

private fun JsonObject.double(key: String): Double? = prim(key)?.doubleOrNull

/** 供 ClaudeCodeManager 复用：同样是不会抛的取值方式 */
internal fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asJsonArrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.asBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

internal fun JsonElement?.asIntOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull
