package dev.min.agent.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 权限规则进日志的形状。单独一个文件放，理由同 MemoryEvents.kt。
 */

@Serializable
enum class RuleKindData {
    @SerialName("allow") Allow,
    @SerialName("ask") Ask,
    @SerialName("deny") Deny,
}

/**
 * 一条权限规则的原样记录：写法与 Claude Code 相同（`Bash(git *)`），[source] 是来自哪一层（managed / user / project / local / session），
 * [baseDir] 是规则里 `/x` 这种路径相对的目录。解析成规则对象是权限引擎的事——日志里存原文，引擎改了解析也不用迁移日志。
 */
@Serializable
data class RuleEntry(
    val kind: RuleKindData,
    val text: String,
    val source: String,
    val baseDir: GuestPath? = null,
)

/**
 * 会话创建时读到的权限规则快照（settings 各层合并后的 allow / ask / deny）。整个会话内冻结，与记忆文件同理：
 * 判定能只凭日志复现，磁盘上的 settings 中途被改也不会悄悄换掉正在跑的会话的规矩（要重载就追加一条本事件）。
 * 子 agent 继承父会话的这一份（连同父会话里「总是允许」攒下的规则）。[notes] 只给人看（跳过了哪些规则、为什么）。
 */
@Serializable
@SerialName("permission_rules_loaded")
data class PermissionRulesLoaded(
    val rules: List<RuleEntry>,
    val notes: List<String> = emptyList(),
) : SessionEvent
