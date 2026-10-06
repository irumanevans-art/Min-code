package dev.min.agent.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * MCP 的会话快照（docs/agent/detail/MCP设计.md §5.2、§11）。工具表 = 内置工具 + 最后一份 [McpToolsSnapshot]；
 * 请求只凭日志重建，所以服务器给的工具定义与说明原样（截断、整形之后）落盘。请求头、环境变量的值永远不进日志。
 */

/** 一台服务器在快照那一刻的样子（给界面与说明用，不进模型） */
@Serializable
data class McpServerInfo(
    val name: String,
    /** managed / local / project / user */
    val scope: String,
    /** stdio / http / sse / ws */
    val transport: String,
    /** pending / connected / failed / needs-auth / disabled / not-approved / untrusted / denied */
    val state: String,
    /** 不是 connected 时给人看的一句话 */
    val reason: String? = null,
    val toolCount: Int = 0,
)

/** 会话创建（以及之后每次服务器集合变化生效）时的服务器清单。整份替换上一份 */
@Serializable
@SerialName("mcp_servers_loaded")
data class McpServersLoaded(
    val servers: List<McpServerInfo>,
    val notes: List<String> = emptyList(),
) : SessionEvent

/** 生效的一个 MCP 工具。[name] 是给模型的全名（`mcp__服务器__工具`，清洗、限长之后）；路由用原始的 [server] / [tool] */
@Serializable
data class McpToolEntry(
    val name: String,
    val server: String,
    val tool: String,
    /** 截到上限之后的说明 */
    val description: String,
    /** 整形之后的 inputSchema（根级 anyOf / oneOf / allOf 已压平） */
    val inputSchema: JsonObject,
    /** 服务器自称只读：只用来决定能不能并行，不用来免审批（规范：注解不可信） */
    val readOnlyHint: Boolean = false,
    /** `_meta["anthropic/requiresUserInteraction"]`：任何模式都问人、不给「总是允许」 */
    val requiresUserInteraction: Boolean = false,
    /** `_meta["anthropic/maxResultSizeChars"]`（上限 500,000）；null = 用默认的结果预算 */
    val maxResultChars: Int? = null,
    val alwaysLoad: Boolean = false,
)

/**
 * 生效的 MCP 工具与各服务器的说明（`instructions`，截断之后）。会话内每次生效的变化追加一条，整份替换：
 * 变化只在**回合开始时**生效（设计 §5.3），不在回合中途改工具表
 */
@Serializable
@SerialName("mcp_tools_snapshot")
data class McpToolsSnapshot(
    val tools: List<McpToolEntry>,
    /** 服务器名 → 说明，按服务器名排序后进系统提示 */
    val instructions: Map<String, String> = emptyMap(),
    /** 给人看的变化说明（「新增 github 的 12 个工具」）；首份快照通常没有 */
    val note: String? = null,
    /** 资源服务器晚连上时，与工具集合一起在下一回合更新。 */
    val resourceServers: List<String> = emptyList(),
) : SessionEvent

@Serializable
@SerialName("mcp_tool_search_configured")
data class McpToolSearchConfigured(val enabled: Boolean, val estimatedTokens: Int, val contextWindow: Int, val thresholdPercent: Double, val strategy: String = "auto") : SessionEvent

@Serializable
@SerialName("mcp_tools_loaded")
data class McpToolsLoaded(val names: List<String>) : SessionEvent
