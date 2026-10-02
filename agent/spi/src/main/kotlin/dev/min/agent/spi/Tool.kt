package dev.min.agent.spi

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 一个内置工具。工具只通过 [ToolContext] 里的端口碰外部世界，只返回结果、不改会话状态——
 * 所以每个工具都能在桌面 JVM 上用假端口单测（docs/agent/03-核心契约.md §5）。
 */
interface Tool<I : Any> {
    val name: String

    /** JSON Schema（draft 2020-12 的子集，各家都认的那部分） */
    val inputSchema: JsonObject

    val traits: ToolTraits

    /** 按模型族给不同描述：同一个工具对 GPT 系和 Claude 系的最佳说法不一样 */
    fun description(profile: HarnessProfile): String

    /** 校验并解析模型给的入参。错误信息是写给模型看的，要让它能自己改对 */
    fun parse(raw: JsonObject): ToolInput<I>

    /** 这次调用要碰哪些路径 / 命令 / 域名。纯函数，不执行任何东西 */
    fun permissionAsk(input: I, ctx: ToolContext): PermissionAsk

    /** 执行。进度若干条，最后一条必须是 [ToolProgress.Done] */
    fun run(input: I, ctx: ToolContext): Flow<ToolProgress>
}

sealed interface ToolInput<out I> {
    data class Valid<I>(val value: I) : ToolInput<I>
    data class Invalid(val messageForModel: String) : ToolInput<Nothing>
}

/**
 * 默认值全部取保守的一侧：不声明就当作「会写、不能并发、中断后结果未知」。
 * 学 Claude Code 的做法——漏标一个工具的代价应该是慢一点，而不是出事。
 */
data class ToolTraits(
    val readOnly: Boolean = false,
    val concurrencySafe: Boolean = false,
    val resumePolicy: ResumePolicy = ResumePolicy.Unknown,
    /** 只露名字，schema 经 ToolSearch 按需加载 */
    val deferred: Boolean = false,
    /** 给模型的结果超过这么多字符就落文件，只给预览 + 路径 */
    val resultBudgetChars: Int = DEFAULT_RESULT_BUDGET_CHARS,
    /**
     * 每次都要人批准，在权限引擎**之前**判定：任何模式（含 bypass）与规则都不能替人放行。
     * 给「人批准本身就是这一步的意义」的工具用（ExitPlanMode：人看过计划才能动手）。
     */
    val alwaysAsk: Boolean = false,
) {
    companion object {
        /**
         * 与 Claude Code Bash 的内联上限同量级（其文档写 30,000 字符，见 research/inventory/claude-code/01）。
         * M1 用评测集校准。
         */
        const val DEFAULT_RESULT_BUDGET_CHARS = 30_000
    }
}

sealed interface ToolProgress {
    /** 给界面看的状态行（「正在搜索 …」） */
    data class Status(val text: String) : ToolProgress

    /** 增量输出（shell 的一段 stdout），只推界面，不落盘 */
    data class Output(val text: String) : ToolProgress

    data class Done(val result: ToolResult) : ToolProgress
}

/**
 * 工具结果。[content] 是模型看到的（超预算时只是预览）；全文在 [artifact] 指向的会话附件里，
 * 模型需要时用 Read / Grep 去取。
 */
@Serializable
data class ToolResult(
    val content: List<Part>,
    val isError: Boolean = false,
    val artifact: String? = null,
    /** 全文字符数；null = [content] 就是全文 */
    val fullChars: Long? = null,
    /**
     * 这次调用读过或写过的文件，以及它们当时的状态。不发给模型；内核据此回答「这个文件上次看到时是什么样」
     * （[ToolContext.lastSeen]），编辑类工具靠它做「先读后写、读后没被外部改过」的检查——
     * 这条检查因此也是从日志推出来的，进程重启后照样有效。
     */
    val touched: List<FileStamp> = emptyList(),
    /**
     * 这次调用对会话本身的影响（如进出计划模式）。不发给模型；工具不直接改会话状态，
     * 由内核在写 ToolOutcome 的同一次提交里落成对应的设置事件。
     */
    val effects: List<SessionEffect> = emptyList(),
)

/** 某一刻看到的文件状态 */
@Serializable
data class FileStamp(val path: GuestPath, val size: Long, val modifiedMs: Long)

/** 工具执行时能用的东西。只有端口，没有会话状态的写权限 */
interface ToolContext {
    val sessionId: SessionId
    val callId: CallId
    val cwd: GuestPath
    val fs: WorkspaceFs
    val search: Search
    val exec: Exec
    val artifacts: ArtifactSink

    /** 本会话里最近一次（读或写）看到 [path] 时它的状态；没看过为 null。由内核从日志里的 [ToolResult.touched] 推出 */
    fun lastSeen(path: GuestPath): FileStamp?

    /** 委派子 agent。有默认值：不涉及子 agent 的工具与测试替身不用管它 */
    val agents: AgentPort get() = AgentPort.Unavailable

    /**
     * 本会话任务清单的只读视图（任务工具用）。有默认值（空清单）：与任务无关的工具和测试替身不用管它。
     * 清单是**本会话自己的**：子 agent 的会话看到的是它自己那份，不是父会话的。
     */
    val tasks: TaskBoard get() = TaskBoard()

    /** 调用 skill（Skill 工具用）。有默认值：其余工具与测试替身不用管它 */
    val skills: SkillPort get() = SkillPort.Unavailable

    /** 会话当前的权限模式（只读）。与人交互的工具据此判断：dontAsk 下不提问，计划模式工具看自己是否在计划模式里 */
    val mode: PermissionMode

    /** 最近一次切进计划模式之前的模式；从没进过为 null。ExitPlanMode 获批后回到这里 */
    val modeBeforePlan: PermissionMode?

    /**
     * 向用户提问并等答复。提问先落盘（[QuestionAsked]），答复由界面经运行时命令写进日志（[QuestionAnswered]）；
     * 这里等的是日志折叠出的状态，不是内存回调。可能等很久（用户不在场）；回合被中断时随之取消。
     */
    suspend fun ask(questions: List<UserQuestion>): QuestionOutcome
}

/** 模型族：决定工具描述、编辑形态、提示词的调法（docs/agent/04-子系统设计.md §4.2） */
@Serializable
enum class ModelFamily {
    @SerialName("claude") Claude,
    @SerialName("gpt") Gpt,
    @SerialName("gemini") Gemini,
    @SerialName("deepseek") DeepSeek,
    @SerialName("glm") Glm,
    @SerialName("kimi") Kimi,
    @SerialName("qwen") Qwen,
    @SerialName("minimax") MiniMax,
    @SerialName("doubao") Doubao,
    @SerialName("other") Other,
}

@Serializable
enum class EditStyle {
    /** 精确替换（Claude Code 的 Edit） */
    @SerialName("str_replace") StrReplace,

    /** 无行号补丁（Codex 的 apply_patch），GPT 系更擅长 */
    @SerialName("apply_patch") ApplyPatch,
}

data class HarnessProfile(val family: ModelFamily, val editStyle: EditStyle)
