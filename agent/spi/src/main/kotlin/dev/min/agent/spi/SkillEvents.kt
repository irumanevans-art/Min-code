package dev.min.agent.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * skill 清单进日志的形状，以及工具调用 skill 的端口。单独一个文件放，理由同 MemoryEvents.kt。
 */

@Serializable
enum class SkillKind {
    /** `.claude/skills/<名字>/SKILL.md` */
    @SerialName("skill") Skill,

    /** `.claude/commands/` 下（含子目录）的 `.md`（文档：与 skill 同样工作，skill 同名时让位） */
    @SerialName("command") Command,
}

/**
 * 清单里的一项：只有「怎么列出来、怎么找到它」需要的字段，**没有正文**（正文在被调用时才读，见 [SkillPort]）。
 * [description] / [whenToUse] 是原文，截断与预算在拼系统提示时做（预算取决于模型窗口，不该写死进日志）。
 */
@Serializable
data class SkillEntry(
    val name: String,
    val description: String,
    /** 定义文件的路径：skill 的目录 = 它的父目录 */
    val path: GuestPath,
    val kind: SkillKind = SkillKind.Skill,
    val whenToUse: String? = null,
    /** 来自哪一层：managed / user / project */
    val source: String? = null,
    val argumentHint: String? = null,
    /** frontmatter `arguments` 声明的具名参数，按位置 */
    val arguments: List<String> = emptyList(),
    /** true = 不列给模型、模型调用也会被拒（只有用户能用 `/名字` 调） */
    val disableModelInvocation: Boolean = false,
    val userInvocable: Boolean = true,
    /** frontmatter 的 `context`（现在只认 `fork`）：斜杠叠放规则要用，与正文同批冻结 */
    val context: String? = null,
    /** frontmatter 的 `hooks:` 原文（与 settings 里的 hooks 同一格式）：调用这个 skill / command 时登记进会话，本会话余下回合生效 */
    val hooks: JsonObject? = null,
    /** frontmatter 的 `agent`（`context: fork` 时的子 agent 类型，缺省 general-purpose）；旧日志缺这个键 = null */
    val agent: String? = null,
    /** frontmatter 的 `background`（只对 `context: fork` 有意义，缺省 true）；旧日志缺这个键 = true */
    val background: Boolean = true,
    /** frontmatter 的 `allowed-tools`：调用它的那一轮免审批的工具（照文档：到用户下一条消息为止）；旧日志缺这个键 = 空 */
    val allowedTools: List<String> = emptyList(),
)

/**
 * 会话创建时读到的 skill / command 清单快照。整个会话内冻结，与记忆、权限规则同理：系统提示里的清单只由它拼出，
 * 磁盘上 `.claude/skills` 中途改了不影响正在跑的会话的稳定前缀（提示缓存不断），重新打开会话也不重读。
 * 子 agent 继承父会话的这一份（分身必须与父会话前缀逐字相同）。
 *
 * [listingBudgetFraction] / [listingMaxDescChars] 是当时 settings 里的 `skillListingBudgetFraction` / `skillListingMaxDescChars`
 * （没设为 null，用 Claude Code 的默认值 0.01 与 1536）；连同清单一起冻结，同一段日志拼出同一个提示。
 * [notes] 只给人看（谁覆盖了谁、哪个文件被跳过），不进模型上下文。
 */
@Serializable
@SerialName("skills_loaded")
data class SkillsLoaded(
    val entries: List<SkillEntry>,
    val listingBudgetFraction: Double? = null,
    val listingMaxDescChars: Int? = null,
    val notes: List<String> = emptyList(),
) : SessionEvent

/** 模型调 Skill 工具的结果 */
sealed interface SkillInvocation {
    /** [content] 是渲染好（参数已替换）的正文，原样交给模型 */
    data class Loaded(val name: String, val content: String) : SkillInvocation

    /** 不能调（不存在、禁止模型调用、文件读不了）；[messageForModel] 写给模型，让它换条路 */
    data class Rejected(val messageForModel: String) : SkillInvocation
}

/**
 * 工具调 skill 的端口。实现者（内核）按会话冻结的清单找 skill、读正文、渲染；工具只负责入参与结果的形状。
 * 有默认实现的 [ToolContext.skills] 指向 [Unavailable]：不涉及 skill 的工具与测试替身不用管它。
 */
interface SkillPort {
    suspend fun invoke(name: String, args: String?): SkillInvocation

    object Unavailable : SkillPort {
        override suspend fun invoke(name: String, args: String?) = SkillInvocation.Rejected("当前环境没有可用的 skills。")
    }
}

/**
 * 嵌套目录里的 skills：模型第一次读写那个子目录的文件时加载（见 `dev.min.agent.ext.config.SkillDiscovery`），之后整个会话可按名字调用。
 * 它**不改**会话清单（[SkillsLoaded] 冻结不变，系统提示的前缀不断）；[directories] 是这次检查过的 `.claude/skills` 路径，
 * 有没有 skill 都记，同一个目录不重复查。[entries] 的名字已按重名规则定好（嵌套的重名取 `相对目录:名字`）。
 */
@Serializable
@SerialName("skills_discovered")
data class SkillsDiscovered(
    val entries: List<SkillEntry>,
    val directories: List<String>,
    val notes: List<String> = emptyList(),
    /**
     * 触发这次发现的那批工具调用所在的助手消息：新 skill 以 system-reminder 追加在它后面的工具结果里告诉模型（CC：加载后「stay available」，
     * 系统提示里冻结的清单不动，前缀不断）。null = 只登记、不通知（子 agent 继承父会话的这一份时）
     */
    val assistantMessageId: dev.min.agent.protocol.MessageId? = null,
) : SessionEvent

/**
 * `--add-dir` 目录里的 `.claude/skills` 在会话中途有变化（CC skills 文档：「picks up the change within the current session, without a restart」；
 * 命令与 agents 不监视）。内核在用户每次开新回合前重扫一遍，有差异才写这条。[entries] 是新增或改动过的（同名替换旧的），
 * [removed] 是没了的名字。折叠进 `SessionState.nestedSkills` / `removedSkills`（冻结的清单不动），并以 system-reminder 附在下一条用户消息后告诉模型。
 */
@Serializable
@SerialName("skills_rescanned")
data class SkillsRescanned(
    val entries: List<SkillEntry>,
    val removed: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) : SessionEvent

/**
 * skill 的 `allowed-tools` 本轮放行（CC 文档：「Tools Claude can use without asking permission during the turn that invokes this skill.
 * The grant clears when you send your next message」）。[rules] 是**已代入 `${CLAUDE_SKILL_DIR}`** 的权限规则文字：
 * 日志里记的就是实际放行的那几条，重开会话时按日志恢复，不再回头读磁盘上的 skill（skill 文件中途改宽了也不会让放行变大）。
 * [skill] 只给人看。折叠进 `SessionState.skillGrants`（追加去重）；拒绝与询问规则仍在它之上。
 */
@Serializable
@SerialName("skill_tools_granted")
data class SkillToolsGranted(val skill: String, val rules: List<String>) : SessionEvent

/** 用户发了下一条消息：本轮放行到此为止（照文档）。折叠时清空 `SessionState.skillGrants` */
@Serializable
@SerialName("skill_tools_cleared")
data class SkillToolsCleared(val reason: String = "user_message") : SessionEvent
