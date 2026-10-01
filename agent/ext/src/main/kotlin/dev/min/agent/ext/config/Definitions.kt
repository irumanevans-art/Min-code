package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * 三种「定义文件」的解析结果。都只是数据：skill 的调用、子 agent 的启动、hooks 的执行是以后的事。
 * 字段表来自 research/inventory/claude-code/03-extensions.md §7.2（skills）、§3.1（子 agent），
 * 以及 skills 文档对 commands 的一句话：「same fields except name and paths」。
 */

/**
 * `<skills 目录>/<名字>/SKILL.md`。[name] 是 frontmatter 的 `name`，缺省取目录名；
 * [description] 缺省取正文第一个非空行（文档原文），所以这里永远是「最终生效」的描述，没有 null。
 * 描述与 [whenToUse] 合计在技能清单里截断于 1,536 字符——那是装配清单的事，这里保留原文。
 */
data class SkillDef(
    val name: String,
    val description: String,
    val path: GuestPath,
    val body: String,
    val source: ConfigSource,
    val whenToUse: String? = null,
    val argumentHint: String? = null,
    val arguments: List<String> = emptyList(),
    val disableModelInvocation: Boolean = false,
    val userInvocable: Boolean = true,
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val model: String? = null,
    val effort: String? = null,
    /** `fork` = 在 fork 出的子 agent 上下文里跑 */
    val context: String? = null,
    val agent: String? = null,
    val background: Boolean = true,
    val hooks: JsonObject? = null,
    val paths: List<String> = emptyList(),
    val shell: String? = null,
    val metadata: JsonObject? = null,
    val license: String? = null,
    val compatibility: String? = null,
)

/**
 * `.claude/commands/` 下的 `.md`。名字 = 相对 commands 目录的子目录用 `:` 连接再接去掉扩展名的文件名
 * （`frontend/component.md` → `frontend:component`）。字段与 skill 相同，但没有 `name` 与 `paths`。
 */
data class CommandDef(
    val name: String,
    val description: String,
    val path: GuestPath,
    val body: String,
    val source: ConfigSource,
    val whenToUse: String? = null,
    val argumentHint: String? = null,
    val arguments: List<String> = emptyList(),
    val disableModelInvocation: Boolean = false,
    val userInvocable: Boolean = true,
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val model: String? = null,
    val effort: String? = null,
    val context: String? = null,
    val agent: String? = null,
    val background: Boolean = true,
    val hooks: JsonObject? = null,
    val shell: String? = null,
    val metadata: JsonObject? = null,
    val license: String? = null,
    val compatibility: String? = null,
)

/**
 * `.claude/agents/` 下的子 agent 定义。[prompt] 是 frontmatter 之后的正文（即子 agent 的系统提示）。
 * [tools] 为 null = 省略 = 「Inherits every tool available to subagents」，与空列表不同。
 * 文档里的未知字段静默忽略，所以这里不保留原始 frontmatter。
 */
data class AgentDef(
    val name: String,
    val description: String,
    val prompt: String,
    val path: GuestPath,
    val source: ConfigSource,
    val tools: List<String>? = null,
    val disallowedTools: List<String> = emptyList(),
    val model: String? = null,
    val permissionMode: String? = null,
    val maxTurns: Int? = null,
    val skills: List<String> = emptyList(),
    /** 内联定义（映射）或已配置的服务器名（字符串），列表里可混用——原样保留 */
    val mcpServers: JsonElement? = null,
    val hooks: JsonObject? = null,
    /** `user` / `project` / `local` */
    val memory: String? = null,
    val background: Boolean? = null,
    val omitClaudeMd: Boolean = false,
    val effort: String? = null,
    /** `worktree` */
    val isolation: String? = null,
    val color: String? = null,
    val initialPrompt: String? = null,
    val experimental: JsonObject? = null,
)

/** 整个 Claude Code 配置的加载结果。[notes] 汇总各部分的说明（跳过了什么、谁覆盖了谁），只给人看 */
data class ClaudeConfig(
    val settings: SettingsSnapshot,
    val skills: List<SkillDef>,
    val commands: List<CommandDef>,
    val agents: List<AgentDef>,
    val notes: List<String>,
)
