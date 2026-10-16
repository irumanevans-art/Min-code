package dev.min.agent.kernel

import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.QuestionId
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HttpPolicyData
import dev.min.agent.spi.RuleEntry
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.AutoMemoryInfo
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.QuestionOutcome
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.SkillsLoaded
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.spi.UserQuestion
import kotlinx.serialization.json.JsonObject

/**
 * 会话在某个 seq 上的完整状态 = 日志前缀的折叠结果（[fold]）。不可变：每条事件产生一个新状态。
 * 内核里没有任何别的地方保存会话状态——这是「日志即真相」在代码里的样子（docs/agent/02-总体架构.md §1）。
 */
data class SessionState(
    val header: LogHeader,
    /** 会话开始时的设置。系统提示里的环境信息由它和 [header] 推出，保证同一段日志组出同一个前缀 */
    val created: SessionCreated,
    val lastSeq: Long,
    val settings: SessionSettings,
    /** 收到了、还没提升成正式消息、也没被撤回的输入，按到达顺序 */
    val inbox: List<PendingInput>,
    /** 会话里收到过的全部输入 id：同一个 id 重发（通知栏重复点击、断网重连）只收一次 */
    val seenInputs: Set<ClientMsgId>,
    /** 模型看得到的对话（工具结果单独放在 [toolResults]，投影时按调用顺序插回） */
    val history: List<HistoryEntry>,
    val toolResults: Map<CallId, ToolResult>,
    /** 正在进行的回合；null = 空闲 */
    val turn: TurnState?,
    /** 已结束回合累计的用量 */
    val usage: Usage,
    /**
     * 会话创建时冻结的记忆文件（最后一条 MemoryLoaded）。进系统提示的稳定前缀，会话内不变。
     * 有默认值：没有记忆文件的会话日志里根本没有那条事件
     */
    val memory: List<LoadedMemoryFile> = emptyList(),
    /** 切目录后已交给模型的指令另存，子会话继承它们时不改父会话稳定提示前缀。 */
    val cwdMemory: List<LoadedMemoryFile> = emptyList(),
    /**
     * 会话创建时冻结的 skill 清单（最后一条 SkillsLoaded，连同当时的清单预算设置）。系统提示里的清单只由它拼，会话内不变。
     * 没有 skill 的会话日志里没有那条事件，此时为 null
     */
    val skills: SkillsLoaded? = null,
    /** 嵌套目录里懒加载来的 skills（SkillsDiscovered 累加）：可按名字调用，不进冻结的清单 */
    val nestedSkills: List<dev.min.agent.spi.SkillEntry> = emptyList(),
    /** 已经检查过的嵌套 `.claude/skills` 目录（有没有 skill 都记），同一个目录不重复查 */
    val checkedSkillDirs: Set<String> = emptySet(),
    /** skill `allowed-tools` 的本轮放行（SkillToolsGranted 追加、SkillToolsCleared 清空）：重开会话时从日志恢复，范围不会比原来大 */
    val skillGrants: List<String> = emptyList(),
    /** security-guidance 已提醒 / 已报告的去重表（SecurityGuidanceSeen 追加）：重开会话时从日志恢复，同一条不再重复提醒 */
    val securityWarned: Set<String> = emptySet(),
    val securityReported: Set<String> = emptySet(),
    /** `--add-dir` 里被删掉的 skill 名（SkillsRescanned）：冻结清单里还有它，但不再算可调用的 */
    val removedSkills: Set<String> = emptySet(),
    /**
     * 会话创建时冻结的 output style（最后一条 OutputStyleLoaded）。系统提示的样式段只由它拼，会话内不变；
     * 分身继承这份快照（前缀与父会话逐字相同），普通子 agent 不用样式。没设 / Default / 找不到时为 null
     */
    val outputStyle: dev.min.agent.spi.OutputStyleLoaded? = null,
    /**
     * 已交卷的结构化输出（最后一条 StructuredOutputDelivered，`--json-schema` 用）。null = 没交或没开。
     * 交了卷的回合随即收尾，一个回合最多采纳一条；最终答案从日志直接重建
     */
    val structuredOutput: dev.min.agent.spi.StructuredOutputDelivered? = null,
    val subagentReport: dev.min.agent.spi.SubagentReportReady? = null,
    val workflowEvents: List<dev.min.agent.spi.SessionEvent> = emptyList(),
    val workflowSettingsDisabled: Boolean = false,
    /** 展示用事件同样由日志折叠；不进入 modelMessages，避免 UI 说明改变模型历史。 */
    val presentation: List<SessionPresentation> = emptyList(),
    /** 会话的名字（最后一条 session_renamed；--name 建会话或 /rename 改名）。界面列表与按名续接用，不进模型上下文 */
    val name: String? = null,
    /** 子会话的来历；根会话为 null（docs/agent/detail/子agent设计.md §2.2） */
    val origin: SubagentOrigin? = null,
    /** 本会话委派出去的子 agent，按委派顺序 */
    val children: Map<SessionId, ChildSummary> = emptyMap(),
    /** 最近结束的那个回合。子 agent 的报告取它的结局 */
    val lastTurn: EndedTurn? = null,
    /**
     * 本会话是分支出来的（最后一条 session_branched）；null = 不是。界面据此显示「分支自 …」，
     * 组请求时沿用事件里冻结的环境事实与缓存键（前缀与原会话逐字相同，提示缓存接得上）
     */
    val branched: dev.min.agent.spi.SessionBranched? = null,
    /** 会话创建时冻结的权限规则（最后一条 PermissionRulesLoaded） */
    val networkSettings: dev.min.agent.spi.NetworkSettings = dev.min.agent.spi.NetworkSettings(),
    val networkPending: Map<ApprovalId, dev.min.agent.spi.NetworkApprovalRequested> = emptyMap(),
    val networkDecisions: List<dev.min.agent.spi.NetworkDecision> = emptyList(),
    val rules: List<RuleEntry> = emptyList(),
    val permissionNotes: List<String> = emptyList(),
    val additionalDirectories: List<GuestPath> = emptyList(),
    /**
     * 会话中途用 `/add-dir`（或 SDK 的 register_repo_root）加的目录（[dev.min.agent.spi.DirectoryAdded] 折叠），已并进 [additionalDirectories]。
     * 单列一份是因为它们和 settings 里写的目录不同：原文说 `--add-dir` / `/add-dir` 的目录还会加载 `.claude/skills`、commands、agents，
     * settings 里列的只授予文件访问；也因为设置重读（ConfigChange 生效）整份替换 [additionalDirectories] 时，这些不能丢
     */
    val addedDirectories: List<GuestPath> = emptyList(),
    val autoReviewSettings: dev.min.agent.spi.AutoReviewSettingsLoaded = dev.min.agent.spi.AutoReviewSettingsLoaded(),
    val advisor: dev.min.agent.spi.AdvisorConfigured = dev.min.agent.spi.AdvisorConfigured(),
    val autoReviewDenials: Int = 0,
    val lastAutoReview: dev.min.agent.spi.AutoReviewed? = null,
    val inheritedAutoUserContext: List<String> = emptyList(),
    val forkAutoUserSeedIds: Set<MessageId> = emptySet(),
    val autoReviewConsecutiveDenials: Int = 0,
    val autoReviewTripped: Boolean = false,
    val modelCosts: Map<String, Double> = emptyMap(),
    val unpricedModelCalls: Int = 0,
    /** 本会话里人选「总是允许」攒下的规则，按答复顺序；来源记为 session */
    val sessionRules: List<RuleEntry> = emptyList(),
    /**
     * 最近一次完成的模型请求的用量：界面据它算「上下文用了多少」（输入 + 缓存读写 ÷ 窗口）；还没请求过为 null。
     * 压缩改写了模型视图之后清空——那次的用量已经不代表下一次请求的大小
     */
    val lastRequestUsage: Usage? = null,
    /**
     * 最近一次完成的模型回复是哪个模型给的（具体型号，取自 [ModelRequestStarted.model]；还没回复过为 null）。
     * 切到窗口更小的模型时，对话超出新窗口的那次摘要要由它来做（CC 2.1.301），而会话设置里的模型此刻已经是新的了。
     * 与 [lastRequestUsage] 不同，压缩 / 回退不清它：它回答的是「这段对话是谁在撑着」，不是「上次请求多大」
     */
    val lastReplyModel: ModelRef? = null,
    /** 提示缓存的账：相邻两次请求比出来的失效与原因（[CacheWatch]），界面的用量段读它 */
    val cacheWatch: CacheWatch = CacheWatch(),
    /** 模型视图里被遮蔽的工具结果 → 原文所在的会话附件（docs/agent/detail/压缩设计.md §1） */
    val masked: Map<CallId, String?> = emptyMap(),
    /** 最后一次摘要：模型视图里到 [CompactionView.through] 为止的对话换成它 */
    val compaction: CompactionView? = null,
    /** 挂在某次摘要消息末尾的 hook 上下文（SessionStart source = compact），按压缩 id；再压缩时旧的一并作废 */
    val compactionContexts: Map<String, List<String>> = emptyMap(),
    /** 连续失败的自动压缩次数，到 3 次熔断（成功一次清零） */
    val compactionFailures: Int = 0,
    /** 开始了还没有结局的压缩（进程死在中途时恢复用） */
    val openCompaction: String? = null,
    /**
     * 本会话的任务清单，按创建顺序（TaskCreate / TaskUpdate 工具维护，由 TaskCreated / TaskUpdated / TaskDeleted 折叠）。
     * 只属于本会话：子 agent 各有自己的一份，不与父会话共享。有默认值：没用过任务工具的会话日志里没有这些事件
     */
    val tasks: List<TaskRecord> = emptyList(),
    /** 已分配过的最大任务编号。删除任务不回退它，编号因此不会被复用 */
    val taskSeq: Int = 0,
    /** `/goal` 的目标（goal_* 事件折叠）。没有目标的会话日志里没有这些事件，默认值即「没有目标」 */
    val goal: GoalState = GoalState(),
    /** 会话创建时冻结的 hooks（最后一条 HooksLoaded；docs/agent/detail/hooks设计.md §1） */
    val hooks: List<HookSourceData> = emptyList(),
    /** 会话中途登记的 frontmatter hooks（skill / command 被调用时、子 agent 创建时）；与上面的快照分开存：不随 settings 层去重、也不继承给孙会话 */
    val componentHooks: List<dev.min.agent.spi.ComponentHooksRegistered> = emptyList(),
    /** 同一条 HooksLoaded 带的 http 两道闸（各生效层合并后的）；null = 没有任何一道闸 */
    val hooksHttpPolicy: HttpPolicyData? = null,
    /** hooks 被整体禁用的原因（最后一条 HooksLoaded 的 disabledBy）；null = 没禁用。`/goal` 据此拒绝运行 */
    val hooksDisabledBy: dev.min.agent.spi.HooksDisabledBy? = null,
    /** hook 加的上下文，按挂靠的消息（用户消息，或带工具调用的助手消息——挂在它的工具结果后面）；模型视图里渲染成 system-reminder */
    val hookContexts: Map<MessageId, List<String>> = emptyMap(),
    /** 还没有消息可挂的 hook 上下文（SessionStart），挂到下一条人发的消息上 */
    val pendingHookContexts: List<String> = emptyList(),
    /** 等着挂到下一条提升为正式消息的输入上的 hook 上下文（SubagentStart，不区分来源） */
    val pendingInputContexts: List<String> = emptyList(),
    /** 模型最后被告知的世界状态（[WorldFacts]）；null = 还没提升过消息，或压缩把告知过的那段摘要掉了 */
    val world: WorldFacts? = null,
    val webSearches: Int = 0,
    /** 会话创建时冻结的自动记忆状态（最后一条 MemoryLoaded 带的）；null = 没开。系统提示里自动记忆那一段只由它与 [memory] 拼 */
    val autoMemory: AutoMemoryInfo? = null,
    /**
     * 按需加载的记忆（MemoryAttached），按挂靠的助手消息：模型视图里渲染在那条消息的工具结果之后（hook 上下文之后）。
     * 去重靠 [memoryPathsInContext]，不靠它
     */
    val memoryAttachments: Map<MessageId, List<LoadedMemoryFile>> = emptyMap(),
    /**
     * 按需加载时「内容已在上下文里、所以没再注入」的路径（[dev.min.agent.spi.MemoryAttached.covered]），同样按挂靠的助手消息记：
     * 压缩把那条消息摘要掉时它们和 [memoryAttachments] 一起移出 [memoryPathsInContext]
     */
    val memoryCovered: Map<MessageId, List<GuestPath>> = emptyMap(),
    /**
     * 此刻已在模型上下文里的记忆文件路径 = 创建时快照里的 + 还留在模型视图里的按需加载的；同一路径不再按需加载第二次。
     * 压缩把挂了它们的消息摘要掉之后，那些路径从集合里移出（内容已不在视图里），再碰到就重新加载（设计 §1.2）
     */
    val memoryPathsInContext: Set<GuestPath> = emptySet(),
    /** 本会话的检查点，按建立顺序（检查点与rewind设计 §1）。回退对话不删它们，回退列表只列消息还在的（[liveCheckpoints]） */
    val checkpoints: List<CheckpointRecord> = emptyList(),
    /** 带内容 id 的编辑记录，按落盘顺序；回退对话不删（改动确实发生过，恢复代码时要用） */
    val editLog: List<EditRecord> = emptyList(),
    /** 回退对话能回到的点，按消息顺序；回退到某点时它与它之后的点一起去掉 */
    val rewindPoints: List<RewindPoint> = emptyList(),
    /** 被回退掉的对话，只给界面折叠显示 */
    val rewound: List<RewoundSegment> = emptyList(),
    /** 做过的恢复代码，按顺序（「撤销这次回滚」从这里找回滚前快照） */
    val restores: List<dev.min.agent.spi.FilesRestored> = emptyList(),
    /** 最后一份 MCP 服务器清单（MCP设计 §11）；没接 MCP 的会话为 null */
    val mcpServers: dev.min.agent.spi.McpServersLoaded? = null,
    /** 最后一份生效的 MCP 工具快照：工具表 = 内置工具 + 它（经 [visibleMcpTools] 过滤） */
    val mcpTools: dev.min.agent.spi.McpToolsSnapshot? = null,
    /** 最近一次会话回顾；null = 还没生成过。自动回顾据它判断「别连着来两次」 */
    val recap: RecapView? = null,
    /** 最后一条事件落盘的时刻：重开时据此知道会话静置了多久（[resumeAdvice]） */
    val lastEventAtMs: Long = 0,
    val mcpToolSearch: dev.min.agent.spi.McpToolSearchConfigured? = null,
    val loadedMcpTools: List<String> = emptyList(),
    /**
     * 被 API 拒收过 inputSchema 的 MCP 工具全名 → 那次 400 的原文摘要。由 [dev.min.agent.spi.McpToolSchemaRejected] 折叠；
     * 组请求时这些工具改成无参数空 object（不从表里删除）。同一工具只记第一次。
     */
    val rejectedMcpSchemas: Map<String, String> = emptyMap(),
    /** 模型用 EndConversation 结束了这个会话（[dev.min.agent.spi.ConversationEnded]）：之后的用户输入不再开回合 */
    val ended: Boolean = false,
    val mcpQuestions: Map<QuestionId, AskedQuestion> = emptyMap(),
    val mcpUrls: Map<QuestionId, McpUrlPending> = emptyMap(),
    val worktree: dev.min.agent.spi.WorktreeBinding? = null,
    val finishedWorktrees: List<dev.min.agent.spi.WorktreeFinished> = emptyList(),
    val cwdWatchPaths: List<GuestPath> = emptyList(),
    /**
     * Bash 的 `cd` 带过来的 shell 当前目录（[dev.min.agent.spi.ShellCwdChanged] 折叠）；null = 就在会话目录。
     * 与 [settings] 的 cwd 是两回事：权限范围、记忆、worktree 看会话目录，这里只决定下一条 Bash 从哪开始。会话目录一换（cwd_changed）就清空
     */
    val shellCwd: GuestPath? = null,
    /**
     * 本会话作为负责人建的团队（最后一条 team_created 起，由 team_* 事件折叠；解散后仍保留，`active` 为假）。
     * 只有根会话会有；成员的日志里没有它，成员要知道自己的团队去看根会话（[SessionHub.teamMembers]）
     */
    val team: TeamState? = null,
    /** 本会话收到的团队邮件（team_message），按到达顺序；给界面消息流用，模型看到的是同一次提交里的 input_received */
    val teamMail: List<TeamMailItem> = emptyList(),
    /** 本会话收到的本机跨会话消息（peer_message），按到达顺序；给界面用，模型看到的是同一次提交里的 input_received（origin = peer） */
    val peerMessages: List<dev.min.agent.spi.PeerMessage> = emptyList(),
    /** 本会话的定时任务（含已结束的，`status` 区分），按创建顺序；由 cron_* 事件折叠（Crons.kt） */
    val crons: List<dev.min.agent.spi.CronTask> = emptyList(),
    /** 已分配过的最大定时任务编号：编号在会话里只指一条任务，取消 / 过期后不复用 */
    val cronSeq: Int = 0,
)

/** [afterTurn] 是生成回顾时最近结束的那个回合：之后没有新回合就不再自动生成 */
data class RecapView(val text: String, val trigger: dev.min.agent.spi.RecapTrigger, val afterTurn: TurnId?)

/**
 * 回退链生效中的状态。[model] 是现在用的（链上可能已经走到第二个），[primary] 是会话设置里的主模型；
 * [reason] / [message] 是促成**最近一次**切换的失败，界面据此写「正在用回退模型 X（主模型 Y 过载）」
 */
data class FallbackInUse(
    val primary: ModelRef,
    val model: ModelRef,
    val reason: dev.min.agent.spi.ModelFailureKind,
    val message: String,
)

data class CompactionView(
    /** 对应 ContextCompacted.compactionId：SessionStart(compact) 的上下文挂靠键 */
    val compactionId: String,
    val summary: String,
    val through: MessageId,
    val transcriptPath: String?,
)

/** 父会话视角的一个子 agent：委派时的样子 + 哪些回合的报告已经交付 */
data class ChildSummary(
    val child: SessionId,
    val callId: CallId,
    val agentType: String,
    val fork: Boolean,
    val background: Boolean,
    val description: String,
    val name: String?,
    /** 已交付给本会话的报告对应子会话的哪些回合（经收件箱，或前台调用的工具结果） */
    val reportedTurns: Set<TurnId> = emptySet(),
    /** 委派它的那次调用已经被回退掉了：它的报告不再进收件箱，续聊找不到它（检查点与rewind设计 §4.4） */
    val rewound: Boolean = false,
)

data class EndedTurn(
    val turnId: TurnId,
    val trigger: TurnTrigger,
    val outcome: TurnOutcome,
    val usage: Usage,
    val durationMs: Long,
    val toolCalls: Int,
    /** 回合里最后一次有动静的时间：进程被杀的回合是被杀前最后一条事件，其余就是结束那一刻 */
    val lastActivityAtMs: Long = 0,
)

data class SessionPresentation(
    val record: dev.min.agent.spi.EventRecord,
    val afterMessageId: MessageId?,
    /** 回合被关闭前的已持久片段，不能随 openRequest 一起丢掉。 */
    val partialMessageId: MessageId? = null,
    val partialParts: List<Part> = emptyList(),
)

/** 现在发请求该用的模型：回退链生效中用回退模型，否则是会话设置里的（`opusplan` 按计划模式展开，见 [effectiveModel]） */
val SessionState.activeModel: ModelRef get() = turn?.fallback?.model ?: settings.effectiveModel

/**
 * 会话设置里的模型落到这一刻该用的那个：`opusplan`（CC 的组合别名）在计划模式下是 opus 档、其余是 sonnet 档，
 * 带 `[1m]` 的照带；其它（具体型号、别的别名）原样。纯函数：折叠与运行时用同一份，日志重放不依赖宿主。
 * 别名本身留给宿主的解析器解析成这家供应商的型号（[ModelResolver]）
 */
val SessionSettings.effectiveModel: ModelRef get() = planAwareModel(model, mode)

/**
 * 模型眼里的世界状态，[dev.min.agent.spi.WorldNoted] 比对的基准。[date] 为 null = 还是系统提示里写的会话日期；
 * [model] 为 null = 没记过（第一次提升消息时静默记下，不提醒——系统提示本来就不报模型）
 */
data class WorldFacts(val date: String?, val mode: dev.min.agent.protocol.PermissionMode, val model: String?)

/**
 * 这次提升消息前要不要补一段世界状态增量（docs/agent/04-子系统设计.md §4.5）；没有变化返回 null。
 * [basisDate] 是系统提示里的会话日期，[today] 是此刻的当地日期。模式只看进出计划模式，基准是不在计划里：
 * 会话一开始就在计划模式里，第一条消息就要说——系统提示不随模式变（变了就断缓存），模型只能从这里知道
 */
internal fun worldDelta(state: SessionState, basisDate: String, today: String): dev.min.agent.spi.WorldNoted? {
    val known = state.world
    val mode = state.settings.mode
    val model = state.settings.effectiveModel.modelId
    val lines = buildList {
        if (today != (known?.date ?: basisDate)) {
            add("Date update: today is $today. Dates mentioned earlier in this conversation, including the session date, are no longer current.")
        }
        // 只有进出计划模式改变模型该怎么做事；其它模式之间的切换由权限层兜着，模型不必知道（也就不为它多写一条）
        val knownPlan = (known?.mode ?: dev.min.agent.protocol.PermissionMode.Default) == dev.min.agent.protocol.PermissionMode.Plan
        if ((mode == dev.min.agent.protocol.PermissionMode.Plan) != knownPlan) add(modeLine(mode))
        val knownModel = known?.model
        if (knownModel != null && model != knownModel) {
            add("Model update: this conversation now runs on $model; earlier replies may have been written by $knownModel.")
        }
    }
    return if (lines.isEmpty()) null else dev.min.agent.spi.WorldNoted(today, mode, model, lines.joinToString("\n"))
}

private fun modeLine(mode: dev.min.agent.protocol.PermissionMode): String {
    // 与 settings.json / --permission-mode 里的写法一致
    val name = when (mode) {
        dev.min.agent.protocol.PermissionMode.Default -> "default"
        dev.min.agent.protocol.PermissionMode.AcceptEdits -> "acceptEdits"
        dev.min.agent.protocol.PermissionMode.Plan -> "plan"
        dev.min.agent.protocol.PermissionMode.Auto -> "auto"
        dev.min.agent.protocol.PermissionMode.DontAsk -> "dontAsk"
        dev.min.agent.protocol.PermissionMode.BypassPermissions -> "bypassPermissions"
    }
    return if (mode == dev.min.agent.protocol.PermissionMode.Plan) {
        "Mode update: plan mode is on. Investigate and design only; do not edit files or run commands with side effects. " +
            "When the plan is ready, submit it with ExitPlanMode for the user's approval."
    } else {
        "Mode update: plan mode is off; the permission mode is now $name and you may make changes again."
    }
}

internal fun planAwareModel(model: ModelRef, mode: dev.min.agent.protocol.PermissionMode): ModelRef {
    val (name, oneMillion) = dev.min.agent.llm.catalog.ModelAliases.splitSuffix(model.modelId)
    if (name != "opusplan") return model
    val tier = if (mode == dev.min.agent.protocol.PermissionMode.Plan) "opus" else "sonnet"
    return ModelRef(model.providerId, if (oneMillion) "$tier[1m]" else tier)
}

data class SessionSettings(
    val cwd: GuestPath,
    val model: ModelRef,
    val mode: PermissionMode,
    val effort: Effort?,
    /**
     * 最近一次切进计划模式之前的模式（折叠时在模式变成 Plan 的那一刻记下）；从没进过为 null。
     * 离开计划模式不清空：它只在下次进入时被覆盖，ExitPlanMode 也只在计划模式里才用它。
     */
    val modeBeforePlan: PermissionMode? = null,
)

data class PendingInput(
    val clientMsgId: ClientMsgId,
    val parts: List<Part>,
    val delivery: Delivery,
    val origin: InputOrigin = InputOrigin.User,
)

sealed interface HistoryEntry {
    val messageId: MessageId

    data class User(
        override val messageId: MessageId,
        val parts: List<Part>,
        /** 界面据此区分用户气泡与子 agent 报告；不影响模型看到的内容 */
        val origin: InputOrigin = InputOrigin.User,
    ) : HistoryEntry

    /**
     * [requestId] 是产出这条回复的模型请求（只有从 `AssistantMessageCompleted` 折出来的有；压缩 / 导入重建的历史没有）。
     * 宿主据此把流式阶段按请求 id 记下的东西（MessageDisplay 的屏显文字）对回这条消息——瞬时流里只有请求 id，没有消息 id
     */
    data class Assistant(override val messageId: MessageId, val parts: List<Part>, val requestId: RequestId? = null) : HistoryEntry {
        val toolCalls: List<ToolCallPart> get() = parts.filterIsInstance<ToolCallPart>()
    }
}

data class TurnState(
    val turnId: TurnId,
    val trigger: TurnTrigger,
    val startedAtMs: Long,
    /** 发出去了、还没完成也没失败的那次请求 */
    val openRequest: OpenRequest?,
    /** 本回合的工具调用，按规划顺序 */
    val calls: Map<CallId, CallState>,
    val usage: Usage,
    /** 本回合向用户提的问题，含已答的：答复留在这里，等着的工具从状态里取；回合结束随回合一起清掉 */
    val questions: Map<QuestionId, AskedQuestion> = emptyMap(),
    /** 本回合最后一条事件的时间（折叠时随每条事件更新）。进程被杀时这就是「中断于何时」 */
    val lastEventAtMs: Long = startedAtMs,
    /** Stop hook 本回合已经拦下「结束」几次：到上限就放行，免得两个 hook 把模型困死在回合里 */
    val stopHookBlocks: Int = 0,
    /** 本回合已经提升过人发的消息：只有第一条记回退点 */
    val userPromoted: Boolean = false,
    /** 本回合模型已经回过话：之后提升的人发的消息是插话，不记回退点 */
    val modelReplied: Boolean = false,
    /** 主模型在本回合里倒了、已换成回退链上的某一个：回合结束随回合一起清掉（下一回合先试主模型） */
    val fallback: FallbackInUse? = null,
) {
    /**
     * 还在等用户回答的提问，界面据此弹提问卡片（进程重启后同样从日志重建）。
     * 提问的调用已经不在执行中（不再等了）的不算，免得界面挂着一张答了也没人收的卡片。
     */
    val pendingQuestions: List<AskedQuestion>
        get() = questions.values.filter { it.outcome == null && calls[it.callId]?.phase is CallPhase.EffectPending }
}

/** 一次提问：哪个调用问的、问了什么、答复（还没答为 null） */
data class AskedQuestion(
    val questionId: QuestionId,
    val callId: CallId,
    val questions: List<UserQuestion>,
    val outcome: QuestionOutcome?,
    val askedAtMs: Long = 0,
    val server: String? = null,
    val mcpTurnId: TurnId? = null,
    val cancelled: Boolean = false,
)

data class McpUrlPending(val request: dev.min.agent.spi.McpUrlElicitationAsked, val action: String? = null, val atMs: Long = 0)

val SessionState.pendingMcpQuestions: List<AskedQuestion>
    get() = mcpQuestions.values.filter { it.outcome == null && (it.mcpTurnId == null || (turn?.turnId == it.mcpTurnId && turn.calls[it.callId]?.phase is CallPhase.EffectPending)) }

val SessionState.pendingMcpUrls: List<McpUrlPending>
    get() = mcpUrls.values.filter { it.action == null && (it.request.callId == null || (turn?.turnId == it.request.turnId && turn?.calls?.get(it.request.callId)?.phase is CallPhase.EffectPending)) }

data class OpenRequest(
    val requestId: RequestId,
    val attempt: Int,
    val messageId: MessageId?,
    /** 已落盘的半截回答（崩溃后给界面看） */
    val segments: List<Part>,
    /** 这次请求发给了哪个模型（[ModelRequestStarted.model]）；完成时记为 [SessionState.lastReplyModel] */
    val model: ModelRef? = null,
)

data class CallState(
    val callId: CallId,
    val tool: String,
    val input: JsonObject,
    val resumePolicy: ResumePolicy,
    val phase: CallPhase,
)

sealed interface CallPhase {
    /** 已规划，权限管线还没给结论 */
    data object Planned : CallPhase

    data class AwaitingApproval(
        val approvalId: ApprovalId,
        val ask: PermissionAsk,
        val requestedAtMs: Long = 0,
        /** 「总是允许」的建议规则与踩底线的原因，原样来自 ApprovalRequested，卡片据此给按钮与解释 */
        val suggestedRule: String? = null,
        val floorReason: String? = null,
    ) : CallPhase

    /** 放行了（规则、hook、模式或人），副作用还没开始 */
    data object Approved : CallPhase

    data class Denied(val note: String?, val fromHost: Boolean = false) : CallPhase

    /** 副作用已开始（ToolEffectPending 已落盘） */
    data class EffectPending(val handle: ExecHandle?) : CallPhase

    data class Done(val result: ToolResult) : CallPhase
}

/**
 * 上一回合是正常结束的（或还没有过回合）：来了报告 / 消息可以自动开回合接着干。
 * 用户、预算、hook 刚停下的会话不行——停了就是停了，输入只进收件箱等人决定（[SessionHub.deliver]）
 */
internal fun SessionState.lastTurnAllowsAutoStart(): Boolean {
    val outcome = lastTurn?.outcome
    return outcome == null || outcome == TurnOutcome.Completed
}

/**
 * 会话里当前可按名字调用的 skill / command：冻结的清单，被 `--add-dir` 重扫改过的同名项换成新的、被删掉的去掉，
 * 再接上嵌套目录懒加载来的。系统提示里的清单只看 [SessionState.skills]（冻结），不走这里。
 */
internal fun SessionState.skillEntries(): List<dev.min.agent.spi.SkillEntry> {
    val overlay = nestedSkills.map { it.name }.toSet()
    return skills?.entries.orEmpty().filter { it.name !in removedSkills && it.name !in overlay } + nestedSkills
}
