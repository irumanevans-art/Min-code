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
 * 下面的 schema 全部对照官方 CLI 二进制（@anthropic-ai/claude-code v2.1.246）中内嵌的
 * zod schema 校对过，关键约束标注在各处注释里。未知字段/类型一律宽容忽略以保持向后兼容，
 * 但**必填字段一个都不能少** —— CLI 对入站帧做严格校验，缺字段会被静默丢弃或报
 * "canUseTool returned a schema-invalid permission result"。
 */
sealed interface ClaudeCodeEvent {
    /** system/init：会话建立，给出 session_id / 工具集 / 模型 */
    data class Init(
        val sessionId: String,
        val model: String?,
        val tools: List<String>,
    ) : ClaudeCodeEvent

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
    ) : ClaudeCodeEvent

    /** 其他 system 提示（compact_boundary / api_error / model_fallback ...） */
    data class SystemNote(val text: String, val isError: Boolean = false) : ClaudeCodeEvent

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

        "assistant" -> expandAssistantMessage(obj)

        "user" -> expandToolResults(obj)

        "stream_event" -> expandStreamEvent(obj)

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
 */
fun expandToolResults(obj: JsonObject): List<ClaudeCodeEvent.ToolResult> {
    val content = obj.obj("message")?.get("content") as? JsonArray ?: return emptyList()
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
        ClaudeCodeEvent.ToolResult(
            toolUseId = blockObj.str("tool_use_id").orEmpty(),
            content = text,
            isError = blockObj.bool("is_error") == true,
        )
    }
}

/**
 * `--include-partial-messages` 下的 stream_event 帧：`event` 字段是原始的 Anthropic SSE 事件。
 * 只取文本/思考增量用于打字机效果，工具入参增量交给整块 assistant 消息处理。
 */
private fun expandStreamEvent(obj: JsonObject): List<ClaudeCodeEvent> {
    val event = obj.obj("event") ?: return emptyList()
    return when (event.str("type")) {
        "message_start" -> listOf(ClaudeCodeEvent.PartialStart)
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

/** 切换 CLI 的工作目录 */
fun encodeClaudeCodeSetCwd(requestId: String, cwd: String): String =
    controlRequest(requestId, "set_cwd", buildJsonObject { put("cwd", cwd) })

/** 重命名会话 */
fun encodeClaudeCodeRenameSession(requestId: String, title: String): String =
    controlRequest(requestId, "rename_session", buildJsonObject { put("title", title) })

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
 * 解析 CLI 自持久化的 transcript 行（`~/.claude/projects/<cwd>/<uuid>.jsonl`）。
 *
 * 实测这些行的 `user` / `assistant` 帧带的 `message` 字段与 stream-json 完全同构，
 * 只是多包了 `timestamp` / `uuid` / `parentUuid` / `cwd` / `gitBranch` 之类的元数据，
 * 所以内容展开直接复用 [expandAssistantMessage] / [expandToolResults]。
 *
 * 跳过：`isSidechain`（子 agent 的独立线程，混进主线程会很乱）、
 * `isMeta`、以及 `attachment` / `queue-operation` / `atis-latch` 等内部行。
 */
fun parseTranscriptLine(line: String): List<ClaudeCodeEvent> {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith("{")) return emptyList()
    val obj = runCatching { protocolJson.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
        ?: return emptyList()
    if (obj.bool("isSidechain") == true) return emptyList()
    if (obj.bool("isMeta") == true) return emptyList()

    return when (obj.str("type")) {
        "assistant" -> expandAssistantMessage(obj)

        "user" -> {
            // 一条 user 行要么是真的用户输入，要么是回填的 tool_result，二者不会混
            val toolResults = expandToolResults(obj)
            if (toolResults.isNotEmpty()) {
                toolResults
            } else {
                transcriptUserText(obj)?.let { listOf(ClaudeCodeEvent.UserMessage(it)) }.orEmpty()
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

/** 从 transcript 的 user 行里取出纯文本；content 可能是字符串，也可能是内容块数组 */
private fun transcriptUserText(obj: JsonObject): String? {
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
    return text
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
        "api_retry" -> listOf(
            ClaudeCodeEvent.ApiRetry(
                attempt = obj.int("attempt"),
                maxRetries = obj.int("max_retries"),
                retryDelayMs = obj.int("retry_delay_ms"),
                errorStatus = obj.int("error_status"),
                message = obj.obj("error")?.str("message")
                    ?: obj.str("error"),
            )
        )

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
            val error = obj.obj("error")
            val text = error?.str("formatted")
                ?: error?.str("message")
                ?: "API 请求失败"
            val status = error?.int("status")?.let { "（HTTP $it）" }.orEmpty()
            listOf(ClaudeCodeEvent.SystemNote("$text$status", isError = true))
        }

        // 模型被静默换掉：底栏 chip 还显示旧模型，不提示的话完全无感
        "model_fallback", "model_consent_fallback" -> {
            val from = obj.str("original_model")
            val to = obj.str("fallback_model")
            val why = obj.str("trigger")?.let { "（$it）" }.orEmpty()
            val text = obj.str("content")?.takeIf { it.isNotBlank() }
                ?: "模型已回退：${from ?: "?"} → ${to ?: "?"}$why"
            listOf(ClaudeCodeEvent.SystemNote(text, isError = true))
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
