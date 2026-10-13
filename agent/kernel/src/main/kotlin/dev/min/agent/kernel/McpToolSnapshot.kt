package dev.min.agent.kernel

import dev.min.agent.mcp.McpToolDef
import dev.min.agent.spi.McpToolEntry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** 一台连上过的服务器列出的工具：[McpManager] 取快照时的输入 */
internal class ListedTools(val server: String, val alwaysLoad: Boolean, val defs: List<McpToolDef>)

/** 服务器在工具 `_meta` 里声明「调用前要有人在场」（界面表单一类）的键 */
private const val META_REQUIRES_INTERACTION = "anthropic/requiresUserInteraction"

/** 服务器在工具 `_meta` 里声明的单次结果字符数上限的键 */
private const val META_MAX_RESULT_CHARS = "anthropic/maxResultSizeChars"

/** 服务器在工具 `_meta` 里声明「每回合都带上、不走工具搜索」的键 */
private const val META_ALWAYS_LOAD = "anthropic/alwaysLoad"

/**
 * 把各服务器列出的工具整形成快照里的条目（纯函数，不碰连接状态）：schema 过不了整形的排除并往 [notes] 记一条说明；
 * 名字统一分配（[McpNames]，同名冲突与长度由它处理）；其余元数据取自工具的 `_meta`。
 * 返回的顺序 = 服务器顺序 + 各自的工具顺序，调用方据此再按配置顺序排
 */
internal fun shapeListedTools(listed: List<ListedTools>, notes: MutableList<String>): List<McpToolEntry> {
    val shaped = ArrayList<Triple<ListedTools, McpToolDef, McpShaping.Shaped.Ok>>()
    for (server in listed) for (def in server.defs) {
        when (val s = McpShaping.shape(def.inputSchema)) {
            is McpShaping.Shaped.Ok -> shaped += Triple(server, def, s)
            is McpShaping.Shaped.Excluded -> notes += "MCP 服务器 ${server.server} 的工具 ${def.name} 没有加载：${s.reason}"
        }
    }
    val names = McpNames.assign(shaped.map { (e, d, _) -> e.server to d.name })
    return shaped.map { (e, d, s) ->
        McpToolEntry(
            name = names.getValue(e.server to d.name),
            server = e.server,
            tool = d.name,
            description = McpShaping.truncate(listOfNotNull(s.descriptionPrefix, d.description ?: d.title).joinToString("\n")),
            inputSchema = s.schema,
            readOnlyHint = d.annotations?.readOnlyHint == true,
            requiresUserInteraction = (d.meta?.get(META_REQUIRES_INTERACTION) as? JsonPrimitive)?.booleanOrNull == true,
            maxResultChars = (d.meta?.get(META_MAX_RESULT_CHARS) as? JsonPrimitive)?.intOrNull,
            alwaysLoad = e.alwaysLoad || (d.meta?.get(META_ALWAYS_LOAD) as? JsonPrimitive)?.booleanOrNull == true,
        )
    }
}
