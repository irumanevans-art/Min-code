package dev.min.agent.kernel

import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.RejectedToolRef
import dev.min.agent.llm.ToolSpec
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.Durability
import dev.min.agent.spi.McpToolSchemaRejected
import dev.min.agent.spi.McpToolsLoaded
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.SessionEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * MCP 工具的 inputSchema 被 API 拒收之后的降级（CC 2.1.300：该工具在本会话余下请求里不带参数）。
 *
 * 工程规范第 10 条「工具表会话内只追加、不重排、不删除」：这里改的是 schema，**不从表里拿掉工具**。
 * 不改的话每一轮请求都会 400，会话废掉；禁用规则挡掉会让模型再也调不到它，也改前缀。
 * 缓存：从降级那一次起稳定前缀变一次（该工具的 description 末尾多一句、inputSchema 换成空对象），之后会话内再不变。
 */

/** 被拒后发给模型的 schema：无参数的空 object。固定这份，重放字节不变 */
internal val EMPTY_MCP_INPUT_SCHEMA: JsonObject = JsonObject(
    mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap())),
)

/**
 * 追加在工具说明末尾的那句（固定常量，不把 API 原文拼进去，免得供应商换措辞就改前缀）。
 * 告诉模型这个工具的参数定义被拒收、只能无参调用。
 */
internal const val MCP_SCHEMA_REJECTED_NOTE = "（这个工具的参数定义被 API 拒收，只能不带参数调用。）"

/** 把已降级的 MCP 工具换成空 schema，并在说明末尾追加 [MCP_SCHEMA_REJECTED_NOTE] */
internal fun List<ToolSpec>.degradeRejectedMcpSchemas(state: SessionState): List<ToolSpec> {
    if (state.rejectedMcpSchemas.isEmpty()) return this
    return map { spec ->
        if (spec.name !in state.rejectedMcpSchemas) spec
        else spec.copy(description = spec.description + MCP_SCHEMA_REJECTED_NOTE, inputSchema = EMPTY_MCP_INPUT_SCHEMA)
    }
}

/**
 * 这次 400 点名的、且是 MCP、且还没降级过的工具名，按下标 / 名字映射到**这次请求**实际发出的工具表
 * （ToolSearch 延迟加载、alwaysLoad 等会让下标与快照顺序不同）。点名内置工具的不在这里——那是 Min 自己的 bug，照常报错。
 */
internal fun mcpToolsNamedInRejection(e: ModelException, request: ModelRequest, state: SessionState): List<String> {
    if (e.kind != ModelFailureKind.BadRequest || e.rejectedTools.isEmpty()) return emptyList()
    val mcp = state.mcpTools?.tools.orEmpty().map { it.name }.toSet()
    if (mcp.isEmpty()) return emptyList()
    val names = LinkedHashSet<String>()
    for (ref in e.rejectedTools) {
        val name = when (ref) {
            is RejectedToolRef.Index -> request.tools.getOrNull(ref.index)?.name
            is RejectedToolRef.Name -> ref.name.takeIf { n -> request.tools.any { it.name == n } }
        } ?: continue
        if (name in mcp && name !in state.rejectedMcpSchemas) names += name
    }
    return names.toList()
}

/**
 * 认出 MCP 工具的 schema 被拒 → 落 [McpToolSchemaRejected]（与 [ModelRequestFailed] 同一次提交，willRetry=true）并立刻重发。
 * 不走普通重试预算、不等待、不换模型：BadRequest 本身不可重试，这里是「改完请求再发一次」。
 * 返回 true = 调用方该再发；false = 走原来的失败路径。
 */
internal suspend fun retryAfterMcpSchemaRejection(
    rt: SessionRuntime,
    turnId: TurnId,
    requestId: RequestId,
    attempts: TurnRunner.RequestAttempts,
    e: ModelException,
    request: ModelRequest,
): Boolean {
    val names = mcpToolsNamedInRejection(e, request, rt.state.value)
    if (names.isEmpty()) return false
    val reason = e.message ?: e.kind.name
    rt.commit(
        listOf(
            ModelRequestFailed(
                turnId, requestId, attempts.attempt, e.kind, reason, e.httpStatus, willRetry = true,
                retryDelayMs = 0L, maxRetries = 0,
                timedOut = e.timedOut, responseId = e.responseId, traceResponse = e.traceResponse,
            ),
        ) + names.map { McpToolSchemaRejected(it, reason) },
        Durability.Sync,
    )
    attempts.attempt++
    return true
}

/** 子 agent / 分身继承父会话的 MCP 快照，含已被拒 schema 的工具（前缀必须与父会话逐字相同） */
internal fun inheritedMcpEvents(parent: SessionState): List<SessionEvent> = listOfNotNull(
    parent.mcpServers, parent.mcpTools, parent.mcpToolSearch,
    parent.loadedMcpTools.takeIf { it.isNotEmpty() }?.let { McpToolsLoaded(it) },
) + parent.rejectedMcpSchemas.map { (name, reason) -> McpToolSchemaRejected(name, reason) }
