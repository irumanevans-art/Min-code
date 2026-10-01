package dev.min.agent.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 记忆文件（CLAUDE.md / AGENTS.md / CLAUDE.local.md / .claude/rules / @ 导入）进日志的形状。
 * 单独一个文件放，是为了和同时在改 SessionEvent.kt 的分支互不冲突；sealed 子类同包同模块即可。
 */

/** 一个记忆文件是从哪儿来的。决定它在系统提示里怎么标注，也给界面分组 */
@Serializable
enum class MemorySource {
    /** `~/.claude/CLAUDE.md`：用户对所有项目的偏好 */
    @SerialName("user") User,

    /** cwd 及其祖先里的 `CLAUDE.md`、`.claude/CLAUDE.md`、`AGENTS.md`：随版本库共享 */
    @SerialName("project") Project,

    /** `CLAUDE.local.md`：个人的、不入库的项目说明 */
    @SerialName("local") Local,

    /** `.claude/rules/` 下（递归）没有 `paths:` 的 `.md` 规则，项目级与 `~/.claude/rules/` 都算 */
    @SerialName("rule") Rule,

    /** 被别的记忆文件用 `@路径` 导入的文件 */
    @SerialName("import") Import,
}

/**
 * 加载进会话的一个记忆文件。[content] 是**进模型的原样文本**（规则已去掉 frontmatter，超长已截断并带说明），
 * 系统提示只由它拼出来，不再回头读磁盘——这样请求能从日志逐字节重建（docs/agent/07-工程规范.md 第 6 条）。
 */
@Serializable
data class LoadedMemoryFile(
    val path: GuestPath,
    val source: MemorySource,
    val content: String,
)

/**
 * 会话创建时读到的记忆文件快照。整个会话内冻结：重新打开会话不重读磁盘，系统提示逐字不变，
 * 提示缓存不断（docs/agent/04-子系统设计.md §4.5「记忆文件（会话中冻结快照）」）。
 * 中途改了 CLAUDE.md 要显式刷新（以后的命令），刷新时再追加一条本事件。
 *
 * [notes] 只给人看（跳过了哪些文件、为什么、截断了哪些），不进模型上下文。
 */
@Serializable
@SerialName("memory_loaded")
data class MemoryLoaded(
    val files: List<LoadedMemoryFile>,
    val notes: List<String> = emptyList(),
) : SessionEvent
