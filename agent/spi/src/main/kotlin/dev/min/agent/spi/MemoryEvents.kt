package dev.min.agent.spi

import dev.min.agent.protocol.MessageId
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

    /** cwd 之下子目录里的 `CLAUDE.md` 一类（含其下没有 `paths:` 的规则）：模型读到那个子树里的文件时才加载，随 [MemoryAttached] 进日志 */
    @SerialName("nested") Nested,

    /** 带 `paths:` 的规则：模型读到匹配的文件时才注入，随 [MemoryAttached] 进日志 */
    @SerialName("path_rule") PathRule,

    /** 自动记忆的索引 `MEMORY.md`（模型自己写的笔记）：进系统提示里自动记忆那一段，不当「项目说明」 */
    @SerialName("auto") Auto,
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
    /** 自动记忆开着时的目录与索引用量；null = 没开（系统提示里就没有自动记忆那一段） */
    val autoMemory: AutoMemoryInfo? = null,
) : SessionEvent

/**
 * 自动记忆（docs/agent/detail/记忆按需加载与自动记忆设计.md §2）在会话创建时的状态，随 [MemoryLoaded] 冻结。
 * 索引正文在 [MemoryLoaded.files] 里（来源 [MemorySource.Auto]，已按行数与字节数截断）；这里只记提示要用的两样：
 * 记忆目录，以及索引有多少行、加载进来多少行（模型据此知道该不该整理）。索引不存在时两个行数都是 0。
 */
@Serializable
data class AutoMemoryInfo(
    val directory: GuestPath,
    val totalLines: Int = 0,
    val loadedLines: Int = 0,
)

/**
 * 工具调用碰到文件之后按需加载的记忆（子目录的 CLAUDE.md、带 `paths:` 的规则），同一批调用合成一条。
 * 挂在**发起这批调用的助手消息**上，模型视图里渲染在那批工具结果之后（hook 上下文之后）。
 * [files] 的 content 是进模型的原样文本，与会话创建时的快照同一套加工（规则去 frontmatter、超长截断带说明）；
 * [notes] 只给人看。
 *
 * [covered]：这批里**没有**再注入、但内容已经在模型上下文里的记忆路径——因内容与同批另一个文件相同而跳过的
 * （`CLAUDE.md` 与 `AGENTS.md` 内容一样），或模型刚直接 Read 过它本身（内容已在工具结果里）。折叠把它们并进
 * 「已在上下文里的记忆路径」，之后不会因为它们没被注入过而在下一批又当新文件注入一遍；它们跟着挂靠的助手消息，
 * 那条消息被压缩摘要掉时一起移出。默认空，不落盘。
 */
@Serializable
@SerialName("memory_attached")
data class MemoryAttached(
    val assistantMessageId: MessageId,
    val files: List<LoadedMemoryFile>,
    val notes: List<String> = emptyList(),
    val covered: List<GuestPath> = emptyList(),
) : SessionEvent
