package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.net.URI

internal val MCP_IDLE_CALL = CallId("mcp-idle")
private const val OMIT = "（不提供此字段）"
private val CREDENTIAL_FIELD = Regex("password|passwd|secret|token|api[ _-]*key|credential", RegexOption.IGNORE_CASE)

internal data class McpFormField(val name: String, val schema: JsonObject, val required: Boolean, val question: UserQuestion)

internal fun mcpForm(server: String, message: String, schema: JsonObject): List<McpFormField>? {
    if (schema["type"]?.jsonPrimitive?.content != "object") return null
    if (schema.keys.any { it !in setOf("type", "title", "description", "properties", "required", "additionalProperties", "\$schema") }) return null
    val properties = schema["properties"] as? JsonObject ?: return null
    val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
    if (!properties.keys.containsAll(required)) return null
    return properties.map { (name, raw) ->
        val field = raw as? JsonObject ?: return null
        if (field.keys.any { it !in setOf("type", "title", "description", "enum", "enumNames", "default", "minimum", "maximum", "minLength", "maxLength") }) return null
        val type = (field["type"] as? JsonPrimitive)?.content ?: return null
        if (type !in setOf("string", "number", "integer", "boolean")) return null
        val enum = field["enum"] as? JsonArray
        if (enum != null && (enum.isEmpty() || enum.any { it !is JsonPrimitive || it is JsonNull || it.content == OMIT })) return null
        if (enum != null && enum.any { rawValue ->
            val value = rawValue.jsonPrimitive
            when (type) {
                "string" -> !value.isString
                "boolean" -> value.isString || value.booleanOrNull == null
                "integer" -> value.isString || value.longOrNull == null
                else -> value.isString || value.doubleOrNull?.isFinite() != true
            }
        }) return null
        val choices = enum?.map { QuestionOption(it.jsonPrimitive.content, "") }
            ?: if (type == "boolean") listOf(QuestionOption("true", "是"), QuestionOption("false", "否")) else emptyList()
        McpFormField(name, field, name in required, UserQuestion(
            "MCP 服务器 $server 请求：$message\n${field["description"]?.jsonPrimitive?.content ?: name}",
            name.take(12), choices + if (name in required) emptyList() else listOf(QuestionOption(OMIT, "省略可选字段")), inputType = type,
        ))
    }
}

internal fun mcpFormContent(fields: List<McpFormField>, answers: List<QuestionAnswer>): JsonObject? {
    if (fields.size != answers.size) return null
    val content = linkedMapOf<String, JsonElement>()
    for ((field, answer) in fields.zip(answers)) {
        val text = answer.other ?: answer.selected.singleOrNull() ?: return null
        if (text == OMIT && !field.required) continue
        val value: JsonPrimitive = when (field.schema["type"]!!.jsonPrimitive.content) {
            "string" -> JsonPrimitive(text)
            "boolean" -> JsonPrimitive(text.toBooleanStrictOrNull() ?: return null)
            "integer" -> JsonPrimitive(text.toLongOrNull() ?: return null)
            "number" -> JsonPrimitive(text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null)
            else -> return null
        }
        val enum = field.schema["enum"] as? JsonArray
        val enumValue = enum?.firstOrNull { it == value || (!value.isString && it is JsonPrimitive && !it.isString && it.doubleOrNull == value.doubleOrNull) }
        if (enum != null && enumValue == null) return null
        val number = if (!value.isString) value.doubleOrNull else null
        if (number != null && (number < (field.schema["minimum"]?.jsonPrimitive?.doubleOrNull ?: Double.NEGATIVE_INFINITY) || number > (field.schema["maximum"]?.jsonPrimitive?.doubleOrNull ?: Double.POSITIVE_INFINITY))) return null
        if (value.isString && (text.length < (field.schema["minLength"]?.jsonPrimitive?.intOrNull ?: 0) || text.length > (field.schema["maxLength"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE))) return null
        content[field.name] = enumValue ?: value
    }
    return JsonObject(content)
}

internal fun urlQuestion(request: McpUrlElicitationAsked) = UserQuestion(
    "MCP 服务器 ${request.server} 请求打开网址：${request.message}\n域名：${request.domain}\n完整 URL：${request.url}",
    "打开网址", listOf(QuestionOption("y", "同意后由宿主打开"), QuestionOption("n", "拒绝")),
)

private fun elicitationResult(action: String, content: JsonObject? = null) = buildJsonObject {
    put("action", action); content?.let { put("content", it) }
}

internal suspend fun SessionRuntime.handleMcpElicitation(server: String, params: JsonObject?, callId: CallId? = null): JsonObject {
    if (!deps.canAnswerMcpElicitation || state.value.settings.mode == PermissionMode.DontAsk) return elicitationResult("decline")
    if (mcpClosed.isCompleted) return elicitationResult("cancel")
    val message = (params?.get("message") as? JsonPrimitive)?.content ?: return elicitationResult("decline")
    val mode = (params["mode"] as? JsonPrimitive)?.content ?: "form"
    val linked = callId?.let { id -> state.value.turn?.takeIf { it.calls[id]?.phase is CallPhase.EffectPending } }
    if (callId != null && linked == null) return elicitationResult("cancel")
    val id = QuestionId(deps.ids.next("mcpq"))
    if (mode == "url") {
        val url = (params["url"] as? JsonPrimitive)?.content ?: return elicitationResult("decline")
        val uri = catchingNonCancellation { URI(url) }.getOrNull()
        if (uri?.scheme?.lowercase() !in setOf("http", "https") || uri?.host.isNullOrBlank()) return elicitationResult("decline")
        val request = McpUrlElicitationAsked(id, server, url, uri!!.host, message, (params["elicitationId"] as? JsonPrimitive)?.content, linked?.turnId, callId)
        if (commitIf(listOf(request), Durability.Sync) { current -> callId == null || (current.turn?.turnId == linked?.turnId && current.turn?.calls?.get(callId)?.phase is CallPhase.EffectPending) } == null) return elicitationResult("cancel")
        val answer = try { withTimeoutOrNull(deps.mcpElicitationTimeoutMs) {
            state.first { it.mcpUrls[id]?.action != null || (callId != null && it.pendingMcpUrls.none { p -> p.request.questionId == id }) }
                .mcpUrls[id]?.action
        } } catch (e: CancellationException) { withContext(NonCancellable) { resolveMcpUrl(id, "cancel") }; throw e }
        if (answer == null) resolveMcpUrl(id, "cancel")
        return elicitationResult(state.value.mcpUrls[id]?.action ?: "cancel")
    }
    val schema = params["requestedSchema"] as? JsonObject
    val sensitive = (schema?.get("properties") as? JsonObject)?.any { (name, raw) ->
        CREDENTIAL_FIELD.containsMatchIn(name) || CREDENTIAL_FIELD.containsMatchIn(((raw as? JsonObject)?.get("title") as? JsonPrimitive)?.content.orEmpty())
    } == true
    if (sensitive) {
        commit(listOf(Note(NoteLevel.Warning, "MCP 服务器 $server 请求通过表单收集敏感凭证，已拒绝整份请求。")), Durability.Sync)
        return elicitationResult("decline")
    }
    val fields = if (mode == "form" && schema != null) catchingNonCancellation { mcpForm(server, message, schema) }.getOrNull() else null
    if (fields == null) {
        commit(listOf(Note(NoteLevel.Warning, "MCP 服务器 $server 的 elicitation 表单包含不能映射的 schema，已拒绝整份请求。")), Durability.Sync)
        return elicitationResult("decline")
    }
    val questions = if (fields.isEmpty()) listOf(UserQuestion("MCP 服务器 $server 请求：$message", "确认", listOf(QuestionOption("同意", "确认服务器请求"), QuestionOption("拒绝", "拒绝服务器请求")))) else fields.map { it.question }
    // 检查与追加必须在同一把提交锁里；调用可能在 schema 映射期间已结束。
    if (commitIf(listOf(QuestionAsked(linked?.turnId ?: TurnId("mcp-idle"), id, callId ?: MCP_IDLE_CALL, questions, server, standalone = true)), Durability.Sync) { current ->
        callId == null || (current.turn?.turnId == linked?.turnId && current.turn?.calls?.get(callId)?.phase is CallPhase.EffectPending)
    } == null) return elicitationResult("cancel")
    var answered = try { withTimeoutOrNull(deps.mcpElicitationTimeoutMs) {
        state.first { it.mcpQuestions[id]?.outcome != null || (callId != null && it.pendingMcpQuestions.none { q -> q.questionId == id }) }.mcpQuestions[id]
    } } catch (e: CancellationException) { withContext(NonCancellable) { cancelMcpQuestion(id) }; throw e }
    if (answered?.outcome == null) {
        cancelMcpQuestion(id)
        answered = state.value.mcpQuestions[id]
    }
    if (answered == null || answered.cancelled || answered.outcome == null) return elicitationResult("cancel")
    val outcome = answered.outcome
    if (outcome is QuestionOutcome.Declined) return elicitationResult("decline")
    if (fields.isEmpty()) return if ((outcome as QuestionOutcome.Answered).answers.singleOrNull()?.selected == listOf("同意")) elicitationResult("accept", JsonObject(emptyMap())) else elicitationResult("decline")
    val content = mcpFormContent(fields, (outcome as QuestionOutcome.Answered).answers)
    if (content == null) {
        commit(listOf(Note(NoteLevel.Warning, "MCP 服务器 $server 的表单答案不符合字段类型或约束，已拒绝请求。")), Durability.Sync)
        return elicitationResult("decline")
    }
    return elicitationResult("accept", content)
}

suspend fun SessionRuntime.resolveMcpUrl(id: QuestionId, action: String): Boolean {
    require(action in setOf("accept", "decline", "cancel"))
    return commitIf(listOf(McpUrlElicitationResolved(id, action)), Durability.Sync) { it.mcpUrls[id]?.action == null && id in it.mcpUrls } != null
}

/** 宿主输入流结束等取消需要与用户拒答区分，才能向服务器回 cancel。 */
suspend fun SessionRuntime.cancelMcpQuestion(id: QuestionId) {
    commitIf(listOf(QuestionAnswered(id, declined = true, cancelled = true)), Durability.Sync) { it.mcpQuestions[id]?.outcome == null && id in it.mcpQuestions }
}

internal suspend fun SessionRuntime.cancelMcpElicitations(close: Boolean = true) {
    state.value.mcpQuestions.values.filter { it.outcome == null }.forEach { cancelMcpQuestion(it.questionId) }
    state.value.mcpUrls.values.filter { it.action == null }.forEach { resolveMcpUrl(it.request.questionId, "cancel") }
    if (close) mcpClosed.complete(Unit)
}
