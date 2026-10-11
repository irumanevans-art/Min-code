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
    /**
     * 这个调用会派别的会话干活（委派子 agent、给它发消息）：调用本身不写文件，派出去的却可能写。
     * 检查点的工具闸据此让它与写工具一样等快照拍完（检查点与rewind设计 §1），[readOnly] 管不到这一层
     */
    val delegates: Boolean = false,
    /**
     * 不走权限与 PreToolUse hook：调用即执行，deny 规则对它无效（CC 的 EndConversation）。只给「做的事只有它自己」的工具用，
     * 漏标的代价是多问一次人，所以默认 false
     */
    val exemptFromPolicy: Boolean = false,
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
     * 这次调用改了哪些文件、改成什么样。不发给模型；界面的「改动过的文件」面板据此列文件与看 diff，
     * 与 Claude Code 的 `bashEditDiff` / Codex 的 fileChange 是同一样东西（`core/session/ChangedFiles.kt` 两边通吃）。
     * 落盘时随 [ToolOutcome] 一起进日志，所以面板只凭日志就能重建。
     */
    val fileEdits: List<FileEdit> = emptyList(),
    /**
     * 这次调用对会话本身的影响（如进出计划模式）。不发给模型；工具不直接改会话状态，
     * 由内核在写 ToolOutcome 的同一次提交里落成对应的设置事件。
     */
    val effects: List<SessionEffect> = emptyList(),
    /**
     * 用户亲口说的话（问答里的答案与自填、拒绝审批时写的说明）。它不是工具的输出，不能放进 [content]：
     * 官方对 Haiku 5.5 的说明是 tool_result 里的文字会被当作不可信内容，用户中途说的话得是独立的用户文本块，
     * 否则模型会无视用户为什么拒绝、选了什么。所以模型视图把它放在同一条用户消息里、紧随全部 tool_result 之后
     * （`ModelView`），[content] 只留一句事实（「用户已回答」「用户拒绝了」）。
     * 随 `tool_outcome` 落盘，所以请求照样只凭日志重建；旧日志里没有这个字段（用户的话还在 [content] 里），读出来是 null，行为不变。
     */
    val userText: String? = null,
    /** 本次编辑新增的错误；正文也带给模型，单独留字段让工具卡在长结果里仍能明确显示摘要。 */
    val diagnostics: List<LspDiagnostic> = emptyList(),
)

/**
 * 一次文件改动。由编辑类工具在执行成功之后自己填：那时它手里既有改之前的原文、也有新内容，
 * 算 diff 不必再读一次盘。
 *
 * [diff] 是不带 `---` / `+++` 文件头的 hunk 正文（头由界面按路径拼，见 `ChangedFiles.diffPaths`）；
 * 超长时截断，[truncated] 记着砍掉了多少字符。
 */
@Serializable
data class FileEdit(
    val path: GuestPath,
    /** unified diff 的 hunk 正文（含 `@@` 头），没有改动时为 null */
    val diff: String? = null,
    /** 这次是新建（撤销动作是删掉它） */
    val created: Boolean = false,
    /** 这次把文件删了 */
    val deleted: Boolean = false,
    /** [diff] 被截断时砍掉的字符数；0 = 完整 */
    val truncated: Int = 0,
    /** 改之前的内容在快照仓库里的 id（编辑记录，检查点设计 §2.2）；原来不存在、或宿主没有仓库时为 null */
    val before: ContentId? = null,
    /** 改之后的内容 id；删除、或宿主没有仓库时为 null */
    val after: ContentId? = null,
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
    /** 多文件事务去重用真实 guest 路径，避免两个链接别名分别覆盖同一个文件。 */
    fun canonicalPath(path: GuestPath): GuestPath = path
    val search: Search
    val exec: Exec
    suspend fun runShell(command: ShellCommand, options: ExecOptions, allowedDomains: List<String> = emptyList()): ExecResult = exec.run(command, options)
    val artifacts: ArtifactSink

    /** 本会话里最近一次（读或写）看到 [path] 时它的状态；没看过为 null。由内核从日志里的 [ToolResult.touched] 推出 */
    fun lastSeen(path: GuestPath): FileStamp?

    /** 委派子 agent。有默认值：不涉及子 agent 的工具与测试替身不用管它 */
    val agents: AgentPort get() = AgentPort.Unavailable
    val worktrees: WorktreePort get() = WorktreePort.Unavailable

    /** 后台任务（Bash 转后台的命令、Monitor 的 watch）。有默认值（不可用）：其余工具与测试替身不用管它 */
    val background: BackgroundPort get() = BackgroundPort.Unavailable

    /**
     * 本会话任务清单的只读视图（任务工具用）。有默认值（空清单）：与任务无关的工具和测试替身不用管它。
     * 清单是**本会话自己的**：子 agent 的会话看到的是它自己那份，不是父会话的。
     */
    val tasks: TaskBoard get() = TaskBoard()

    /**
     * 本会话定时任务的只读视图（Cron 工具用）。有默认值（不支持）：与定时任务无关的工具和测试替身不用管它。
     * 定时任务是**根会话**的：子 agent 的视图带 [CronBoard.refusal]
     */
    val crons: CronBoard get() = CronBoard()

    /** 调用 skill（Skill 工具用）。有默认值：其余工具与测试替身不用管它 */
    val skills: SkillPort get() = SkillPort.Unavailable
    val lsp: LspPort get() = LspPort.Unavailable
    val web: WebPort get() = WebPort.Unavailable
    val webSearch: WebSearchPort get() = WebSearchPort.Unavailable

    /** 把文件交给用户的设备（SendUserFile）。有默认值（不可用）：其余工具与测试替身不用管它 */
    val userFiles: UserFileSink get() = UserFileSink.Unavailable

    /** 发系统通知（PushNotification）。有默认值（不可用） */
    val push: PushSink get() = PushSink.Unavailable

    /** 在预览位里打开会话产物（Artifact）。有默认值（不可用） */
    val artifactView: PreviewSink get() = PreviewSink.Unavailable

    /** 起草反馈、放进本机草稿队列（SendFeedback）。有默认值（不可用） */
    val feedback: FeedbackSink get() = FeedbackSink.Unavailable

    /** 等后台连接的 MCP 服务器（WaitForMcpServers）。有默认值（不可用） */
    val mcpWait: McpWaitPort get() = McpWaitPort.Unavailable

    /** 按页截取 PDF（Read 的 pages）。有默认值（不可用）：其余工具与测试替身不用管它 */
    val pdfPages: PdfPagePort get() = PdfPagePort.Unavailable
    suspend fun extract(content: String, prompt: String): ToolResult =
        ToolResult(listOf(dev.min.agent.protocol.TextPart("这个环境不能提取网页内容。")), isError = true)

    /**
     * 编辑记录（检查点设计 §2.2）：把一份内容存进本会话工作区的快照仓库，返回内容 id。
     * 编辑类工具在写盘之前存改前原文、写完存改后内容。有默认值（不存，返回 null）：其余工具与测试替身不用管它
     */
    suspend fun preserve(content: ByteArray): ContentId? = null

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

/**
 * 给人看的结果文字（卡片、终端一行摘要）：工具自己的文本输出，后面接用户的话（[ToolResult.userText]）。
 * 用户的话不在 [ToolResult.content] 里，但界面上这张卡本来就该显示「用户答了什么、为什么拒绝」；只有发给模型时它们才要分开。
 */
fun ToolResult.displayText(separator: String = "\n"): String =
    (content.filterIsInstance<dev.min.agent.protocol.TextPart>().map { it.text } + listOfNotNull(userText)).joinToString(separator)
