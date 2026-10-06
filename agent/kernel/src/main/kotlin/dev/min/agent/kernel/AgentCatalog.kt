package dev.min.agent.kernel

import dev.min.agent.ext.config.AgentDef
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.PermissionMode

/**
 * 可委派的子 agent 类型：内置三类，加上 `.claude/agents/` 里的自定义定义（同名时自定义覆盖内置，与 Claude Code 一致）。
 * [notes] 只给人看：哪些字段 Min 还不支持、哪些值认不出而退回了默认。
 */
data class AgentCatalog(val definitions: List<AgentDefinition>, val notes: List<String>) {
    companion object {
        fun of(custom: List<AgentDef>): AgentCatalog {
            val notes = mutableListOf<String>()
            val mapped = custom.map { definitionOf(it, notes) }
            val names = mapped.map { it.name }.toSet()
            return AgentCatalog(BuiltinAgents.all.filterNot { it.name in names } + mapped, notes)
        }

        /** Claude Code 的模型别名：在多供应商的 Min 里没有对应物，退回父会话的模型，而不是让整个定义不能用 */
        private val CLAUDE_MODEL_ALIASES = setOf("sonnet", "opus", "haiku", "fable")

        private fun definitionOf(def: AgentDef, notes: MutableList<String>): AgentDefinition {
            val where = "子 agent「${def.name}」（${def.path}）"
            val model = def.model?.trim()?.takeIf { it.isNotEmpty() }?.let { m ->
                if (m.lowercase() in CLAUDE_MODEL_ALIASES) {
                    notes += "$where 的 model: $m 是 Claude Code 的别名，Min 里改用父会话的模型"
                    null
                } else m
            }
            val mode = def.permissionMode?.let { raw ->
                modeOf(raw) ?: run { notes += "$where 的 permissionMode: $raw 认不出，继承父会话"; null }
            }
            val effort = def.effort?.let { raw ->
                Effort.entries.firstOrNull { it.wireName() == raw.trim().lowercase() }
                    ?: run { notes += "$where 的 effort: $raw 认不出，继承父会话"; null }
            }
            val unsupported = buildList {
                if (def.skills.isNotEmpty()) add("skills")
                if (def.mcpServers != null) add("mcpServers")
                if (def.memory != null) add("memory")
                if (def.isolation != null) add("isolation")
            }
            if (unsupported.isNotEmpty()) notes += "$where 的 ${unsupported.joinToString("、")} Min 暂不支持，已忽略"
            return AgentDefinition(
                name = def.name,
                description = def.description,
                instructions = def.prompt,
                tools = def.tools,
                // CC：带限定符的条目（`Bash(git push *)`）也是整个工具移除
                disallowedTools = def.disallowedTools.map { it.substringBefore('(').trim() },
                model = model,
                permissionMode = mode,
                maxTurns = def.maxTurns,
                effort = effort,
                omitMemory = def.omitClaudeMd,
                alwaysBackground = def.background == true,
                // frontmatter 的 hooks 进子 agent 的会话日志（运行期间生效）；Stop→SubagentStop 的改写在登记时做
                hooks = def.hooks,
                hooksLayer = def.source.label,
            )
        }

        /** frontmatter 的写法与日志里的序列化名相同；`manual` 是 CC 对 default 的另一个叫法 */
        private fun modeOf(raw: String): PermissionMode? = when (raw.trim()) {
            "default", "manual" -> PermissionMode.Default
            "acceptEdits" -> PermissionMode.AcceptEdits
            "plan" -> PermissionMode.Plan
            "auto" -> PermissionMode.Auto
            "dontAsk" -> PermissionMode.DontAsk
            "bypassPermissions" -> PermissionMode.BypassPermissions
            else -> null
        }

        private fun Effort.wireName(): String = when (this) {
            Effort.Off -> "off"
            Effort.Minimal -> "minimal"
            Effort.Low -> "low"
            Effort.Medium -> "medium"
            Effort.High -> "high"
            Effort.XHigh -> "xhigh"
            Effort.Max -> "max"
        }
    }
}
