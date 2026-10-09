package dev.min.code.core.engine

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 引擎无关的会话层（M2-B，设计 `docs/agent/detail/M2-B-界面接入设计.md` §2–§3）。
 *
 * ## 三条规矩（审查时照查）
 *
 * 1. **界面只认 `core/engine`。** 想让界面多看到一样东西，先在 [AgentSessionState] 加字段，由三个适配器
 *    各自填（填不出来就是能力位为假）。只有 `core/engine/min/` 认识内核（`dev.min.agent.*`），
 *    只有 `core/engine/claude/` 认识 CC（`core/claudecode`），只有 `core/engine/codex/` 认识 Codex。
 *    所以这个包里的任何文件都**不许** import 那三处的类型；需要共用的界面类型（`ChatItem`、`SessionStatus`、
 *    `PathScope`）在 `core/session`，可以引用。这就是 `07-工程规范.md` 第 4 条（UI 只认协议）在 App 里的样子。
 * 2. **控件显隐只看能力位**（[EngineCapabilities]）。`ChatItem` 头注释那句「加变体前先问 Codex 填得出来吗」
 *    照样适用：适配器填不出来的东西，不进通用类型。
 * 3. **引擎专有的界面只有一处分派**：`EngineUi`（按 [EngineKind] 给出开始面板附加项、设置面板附加段、
 *    维护面板、配置面板入口）。其余地方出现 `when (engine)` 一律视为缺陷。
 *
 * ## 一个会话的形状
 *
 * 三条流加一个命令入口：[state]（结构化状态，每条持久事件 / 协议帧更新一次）、[stream]（逐字增量，
 * 单独一条，避免整页重组）、[signals]（一次性事件），写入只经 [submit]。
 */
interface AgentSession {
    val ref: SessionRef
    val capabilities: EngineCapabilities

    /** 结构化状态。不含逐字增量 */
    val state: StateFlow<AgentSessionState>

    /** 逐字增量（正文、思考、工具参数草稿、运行中工具输出的尾巴）。只有生成中的那一块在重组 */
    val stream: StateFlow<LiveStream>

    /** 一次性事件：撤回的消息回填输入框、预览 URL、轻提示。`replay = 0` */
    val signals: SharedFlow<SessionSignal>

    /**
     * 唯一的写入口。不支持的命令返回 [CommandResult.Unsupported]（界面本不该露出那个控件，
     * 返回值只为兜底与测试）。实现的开头照这个顺序：
     * [EngineCapabilities.supports] → [CommandDeduper.firstSeen] → 过期判定 → 执行。
     */
    suspend fun submit(command: SessionCommand): CommandResult

    // ---- 查询：无副作用，不走命令 ----

    /**
     * `@` 补全的候选（guest 绝对路径）。设计 §3.10 说默认实现直接调 `findMentionFiles`，
     * 但它要 hostRoot / guestRoot，通用层不知道，所以这里不给默认，由适配器实现。
     */
    suspend fun searchFiles(query: String, limit: Int): List<String>

    /** MCP 服务器面板与批准卡（[EngineCapabilities.mcpServers] 为真才有）；其余引擎 null */
    val mcp: McpControl? get() = null

    /** 重开一个又老又大的会话时的建议（[EngineCapabilities.resumeAdvice]）；没有建议 / 不支持返回 null。只在刚打开时问一次 */
    suspend fun resumeAdvice(): ResumeAdviceView? = null

    /** 选了「从摘要续接」：先把对话压成摘要。压成了返回 true；没压成（有回合在跑、模型失败……）会话照原样完整续接，返回 false */
    suspend fun continueFromSummary(): Boolean = false

    /**
     * 会话回顾（[EngineCapabilities.recap]）。[auto] 为真是宿主判定「人离开了一阵回来了」后自动调的，受自动回顾的闸管
     * （对话够长、上次回顾之后有新回合、设置没关）；手动按「回顾」不受这些限制。不支持的引擎返回 [RecapOutcome.Skipped]
     */
    suspend fun recap(auto: Boolean): RecapOutcome = RecapOutcome.Skipped("")

    /** 团队页的“放行 / 解散”（[EngineCapabilities.team] 为真才有）；其余引擎 null */
    val team: TeamControl? get() = null

    /** 检查点与回退（[EngineCapabilities.rewind] 为真才有）；其余引擎 null */
    val rewind: RewindControl? get() = null

    /** 这次工具调用能不能撤销文件改动（[EngineCapabilities.checkpointRevert] 为假时永远 false） */
    suspend fun hasCheckpoint(toolUseId: String): Boolean = false

    /** [toolUseIds] 里存过文件快照的那些（一次问盘，面板据此决定谁有撤销钮）。默认逐个问 [hasCheckpoint]，B6(b) 补批量版 */
    suspend fun checkpointed(toolUseIds: Collection<String>): Set<String> =
        toolUseIds.filterTo(LinkedHashSet()) { hasCheckpoint(it) }

    /**
     * 把一个文件在本会话里的全部改动整批撤回去（只还原文件，不动对话），返回**真的还原成功**的 toolUseId。
     * 设计 §3.3 的 [SessionCommand.Revert] 只有单条、结果只有受理与否；「整文件撤销」要拿回成功的是哪几条，
     * 命令的返回值装不下，所以补在这里。[checkpointRevert] 为假的引擎返回空集。B6(b) 补
     */
    suspend fun revertFile(name: String, toolUseIds: List<String>): Set<String> = emptySet()

    /** 后台任务的输出（[EngineCapabilities.backgroundTasks] 为假或任务不存在时 null），最多 [maxChars] 个字符 */
    suspend fun readTaskOutput(taskId: String, maxChars: Int): String? = null

    /**
     * 后台任务输出的尾巴，连同「是不是被截过」。[readTaskOutput] 只给文字，面板要在被截时多写一句「只显示最后 N 行」，
     * 所以另给这个；默认由 [readTaskOutput] 推（认为没截）。B6(b) 补
     */
    suspend fun readTaskTail(taskId: String): TaskOutputTail? =
        readTaskOutput(taskId, Int.MAX_VALUE)?.let { TaskOutputTail(it, clipped = false) }

    /** 释放：Claude / Codex 关进程；Min 中断本会话树里在跑的回合，从 Hub 卸下运行时 */
    suspend fun close(reason: CloseReason)
}

/** [AgentSession.readTaskTail] 的结果 */
data class TaskOutputTail(
    val text: String,
    val clipped: Boolean,
    /** 引擎最多保留几行尾巴（被截时面板写「只显示最后 N 行」）。0 = 没说 */
    val lineLimit: Int = 0,
)

/** 为什么关。适配器据此决定要不要保留草稿、要不要中断子 agent */
enum class CloseReason {
    /** 用户在界面里结束这个会话 */
    UserEnded,

    /** 通知上的「停止全部」 */
    StopAll,

    /** 被回收（Min 的 LRU 卸载、CC 的 `pruneDead`），会话本身还在，可以再打开 */
    Evicted,

    /** 会话被删除，随后目录会清掉它的数据 */
    Deleted,
}
