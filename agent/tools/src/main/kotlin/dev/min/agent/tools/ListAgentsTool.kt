package dev.min.agent.tools

import dev.min.agent.spi.AgentListing
import dev.min.agent.spi.AgentListingKind
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.protocol.TextPart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/**
 * 列出能用 SendMessage 发消息的对象（与 Claude Code 同名，语义照其 tools-reference 的 ListAgents）。
 * 列出：委派出去的子 agent、团队成员，以及本机其他根会话（跨会话邮箱）。云会话与 Remote Control 不在内。
 * 只读：列表来自会话自己的委派记录与团队状态，不碰外部世界。
 */
object ListAgentsTool : Tool<Unit> {
    override val name = "ListAgents"

    override val inputSchema: JsonObject = schemaObject(emptyList())

    override val traits = ToolTraits(readOnly = true, concurrencySafe = true)

    override fun description(profile: HarnessProfile) =
        "列出你能用 SendMessage 发消息的对象：你委派出去的子 agent，以及团队成员（你是负责人时）。" +
            "每一行给出名字（有的子 agent 没有名字）、id、当前状态。SendMessage 的 to 可以填名字或 id。" +
            "本机的其他会话（同一台机器上另一个 Claude 会话）也列在这里，名字重复时用 id 指定。云会话暂时列不出来。"

    // 没有入参；模型多给的字段一律忽略
    override fun parse(raw: JsonObject): ToolInput<Unit> = ToolInput.Valid(Unit)

    override fun permissionAsk(input: Unit, ctx: ToolContext) = dev.min.agent.spi.PermissionAsk(name, "列出可发消息的 agent")

    override fun run(input: Unit, ctx: ToolContext): Flow<ToolProgress> = flow {
        emit(ToolProgress.Done(ToolResult(listOf(TextPart(render(ctx.agents.roster()))))))
    }

    /**
     * 名单的文字（工具结果与 CLI 的 `/list-agents` 共用，两处同一份排版）。
     * 为什么公开：`/list-agents` 要的正是模型看到的这份名单，不另起炉灶。
     */
    fun render(rows: List<AgentListing>): String =
        if (rows.isEmpty()) {
            "本会话没有可以发消息的 agent：你还没有委派过子 agent，也没有团队成员。"
        } else {
            "可以用 SendMessage 发消息的 agent（本会话）：\n" + rows.joinToString("\n") { line(it) }
        }

    private fun line(row: AgentListing): String {
        val state = STATE_LABELS[row.state] ?: row.state
        return when (row.kind) {
            AgentListingKind.Subagent -> {
                val who = row.name?.let { "子 agent「$it」" } ?: "子 agent"
                "- $who · id ${row.id} · $state" + (row.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            }
            AgentListingKind.Teammate -> "- 团队成员「${row.name.orEmpty()}」· id ${row.id} · $state"
            AgentListingKind.Peer -> "- 本机会话「${row.name ?: "未命名"}」· id ${row.id} · $state" + (row.description?.takeIf { it.isNotBlank() }?.let { " · 目录 $it" } ?: "")
        }
    }

    private val STATE_LABELS = mapOf(
        "running" to "运行中",
        "idle" to "空闲",
        "queued" to "排队中",
        "stalled" to "已停（需要继续）",
        "not_started" to "未启动",
    )
}
