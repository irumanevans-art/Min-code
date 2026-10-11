package dev.min.code.core.engine

/**
 * 一个引擎能做什么。**控件显隐只看它**，界面不按 [EngineKind] 写分支（规矩 2）。
 * 33 项 × 3 引擎的取值写在下面的 companion 里，由 `EngineCapabilitiesTest` 逐格钉住——
 * 改一格要同时改测试，审查时一眼看得到。
 *
 * 位与命令一一对应的那些，[supports] 能统一兜底；对不上的（引擎专有设置项等）由适配器自己判。
 */
data class EngineCapabilities(
    /** 同时在线的会话上限。Min 在进程内运行不占 Node 进程，6 是暂定值，等真机 PSS 数据再定（设计待确认） */
    val maxConcurrent: Int,
    /** 引擎能不能收图。当前模型吃不吃图是 [SettingsView.modelSupportsImages]，Min 靠它把图片项置灰 */
    val images: Boolean,
    /** 回合在跑时发送是插话还是排队，只影响占位字与气泡标注 */
    val busySend: BusySend,
    val withdrawQueued: Boolean,
    /** 一个字还没出时停止 = 撤回那条消息（Esc 三档的第一档） */
    val withdrawInFlight: Boolean,
    /** 停止键只停本轮。false = 停止键停的是整个会话进程（Codex 在接上 `turn/interrupt` 之前就是这样） */
    val interruptTurn: Boolean,
    val resume: ResumeSupport,
    val approvalAlways: Boolean,
    val questions: Boolean,
    val planMode: Boolean,
    /**
     * 运行中换模型。CC 上下文窗口变了会重启，Codex 下一轮生效，Min 立即生效且允许跨供应商
     * （§10.2 拍板 4）。三家都能换，所以它实际只是「模型行可点」的开关；表里没有 false 的格子。
     */
    val modelHotSwap: Boolean,
    /** 强度能热切。CC 为 false：换强度要重启（[SettingsView.effortNeedsRestart]），命令照样受理，界面先问一句 */
    val effortHotSwap: Boolean,
    val cwdChange: Boolean,
    val userShell: Boolean,
    val checkpointRevert: Boolean,
    val subagents: Boolean,
    /** 对子 agent 说话是直达（Min）还是借 hook 递送（CC，气泡上标 Handoff） */
    val subagentDirectMessage: Boolean,
    val backgroundTasks: Boolean,
    val cost: Boolean,
    val cacheStats: Boolean,
    val liveTitle: Boolean,
    val exportImport: Boolean,
    val processOutput: Boolean,
    /** 重开又老又大的会话时「从摘要续接 / 完整续接」的建议（[AgentSession.resumeAdvice]） */
    val resumeAdvice: Boolean,
    /** 会话回顾：「回顾」入口与离开后自动回顾（[AgentSession.recap]） */
    val recap: Boolean,
    /** 长按用户消息「回到这里」、撤销这次回退、快照仓库占用（[RewindControl]）。内核的检查点只有 Min 有；CC 只能撤文件（[checkpointRevert]） */
    val rewind: Boolean,
    /** 会话里的 MCP 服务器面板、批准卡、登录（[McpControl]）。CC 的 MCP 在它自己的配置面板里，Codex 没有 */
    val mcpServers: Boolean,
    /** 第一次进一个带项目层 hooks / `.mcp.json` 的工作目录前先问信任（[WorkspaceTrustControl]）。CC 有自己的信任对话框，Codex 没有项目层配置 */
    val workspaceTrust: Boolean,
    /** agent 团队的页面：成员、共享清单、消息流、放行搁浅的成员、解散（[TeamControl]）。团队在 Min 内核里，CC 的团队在它自己的 CLI 里 */
    val team: Boolean,
    /** auto 权限模式与它的审查模型（[SessionCommand.SetAutoReviewer]、[SettingsView.autoReview]）。CC 的 auto 模式在它自己的 CLI 里，Codex 没有 */
    val autoReview: Boolean,
    /** 会话的定时任务清单与删除（[ScheduleControl]）。定时任务在 Min 的内核里；CC 的在它自己的 CLI 里，Codex 没有 */
    val schedule: Boolean,
    /** `/mcp /agents /memory /permissions /config` 面板。设计草稿是 Boolean，但表里 Min 写的是「部分」，见 [ConfigFiles] */
    val claudeConfigFiles: ConfigFiles,
    /** 本地顾问模型选择（Min）；其它引擎使用自己的会话设置。 */
    val advisor: Boolean = false,
) {
    /** 这条命令从能力位上看能不能受理。适配器的 `submit` 先问它，false 就直接返回 [CommandResult.Unsupported] */
    fun supports(command: SessionCommand): Boolean = when (command) {
        is SessionCommand.Send -> (command.input.images.isEmpty() || images) &&
            (command.target !is SendTarget.Subagent || subagents)
        is SessionCommand.Resume -> resume != ResumeSupport.None
        is SessionCommand.WithdrawQueued -> withdrawQueued
        is SessionCommand.Answer -> when (command.answer) {
            is InteractionAnswer.Choose -> true
            is InteractionAnswer.Answer, InteractionAnswer.Decline -> questions
        }
        is SessionCommand.SetModel -> modelHotSwap
        is SessionCommand.SetCwd -> cwdChange
        is SessionCommand.RunShell -> userShell
        is SessionCommand.StopSubagent -> subagents
        is SessionCommand.StopTask -> backgroundTasks
        is SessionCommand.Revert -> checkpointRevert
        is SessionCommand.SetAutoReviewer -> autoReview
        is SessionCommand.SetAdvisor -> advisor
        // 没有对应能力位的：停止永远可以；强度 / 模式换不换得了走重启路径，命令照样受理；
        // 引擎专有设置项由适配器按键判（Min 没有任何设置项，自己返回 Unsupported）；改名、刷新都不挑引擎
        is SessionCommand.Interrupt,
        is SessionCommand.SetEffort,
        is SessionCommand.SetMode,
        is SessionCommand.SetEngineOption,
        is SessionCommand.Rename,
        is SessionCommand.Refresh,
        -> true
    }

    /** 逐格文字，固定顺序，与设计 §3.8 的表一致。测试用它钉表，也方便 adb 调试入口打印 */
    fun toTable(): List<Pair<String, String>> = listOf(
        "maxConcurrent" to maxConcurrent.toString(),
        "images" to images.toString(),
        "busySend" to busySend.name,
        "withdrawQueued" to withdrawQueued.toString(),
        "withdrawInFlight" to withdrawInFlight.toString(),
        "interruptTurn" to interruptTurn.toString(),
        "resume" to resume.name,
        "approvalAlways" to approvalAlways.toString(),
        "questions" to questions.toString(),
        "planMode" to planMode.toString(),
        "modelHotSwap" to modelHotSwap.toString(),
        "effortHotSwap" to effortHotSwap.toString(),
        "cwdChange" to cwdChange.toString(),
        "userShell" to userShell.toString(),
        "checkpointRevert" to checkpointRevert.toString(),
        "subagents" to subagents.toString(),
        "subagentDirectMessage" to subagentDirectMessage.toString(),
        "backgroundTasks" to backgroundTasks.toString(),
        "cost" to cost.toString(),
        "cacheStats" to cacheStats.toString(),
        "liveTitle" to liveTitle.toString(),
        "exportImport" to exportImport.toString(),
        "processOutput" to processOutput.toString(),
        "resumeAdvice" to resumeAdvice.toString(),
        "recap" to recap.toString(),
        "rewind" to rewind.toString(),
        "mcpServers" to mcpServers.toString(),
        "workspaceTrust" to workspaceTrust.toString(),
        "team" to team.toString(),
        "autoReview" to autoReview.toString(),
        "schedule" to schedule.toString(),
        "claudeConfigFiles" to claudeConfigFiles.name,
        "advisor" to advisor.toString(),
    )

    companion object {
        val ClaudeCode = EngineCapabilities(
            maxConcurrent = 3,
            images = true,
            busySend = BusySend.Steer,
            withdrawQueued = true,
            withdrawInFlight = true,
            interruptTurn = true,
            resume = ResumeSupport.Prompted,
            approvalAlways = true,
            questions = true,
            planMode = true,
            modelHotSwap = true,
            effortHotSwap = false,
            cwdChange = true,
            userShell = true,
            checkpointRevert = true,
            subagents = true,
            subagentDirectMessage = false,
            backgroundTasks = true,
            cost = true,
            cacheStats = false,
            liveTitle = true,
            exportImport = true,
            processOutput = true,
            resumeAdvice = false,
            recap = false,
            rewind = false,
            mcpServers = false,
            workspaceTrust = false,
            team = false,
            autoReview = false,
            schedule = false,
            claudeConfigFiles = ConfigFiles.Full,
        )

        /**
         * Codex **现状**：停止键仍是杀 app-server（`interruptTurn = false`），所以没有 ▶（`resume = None`）。
         * 设计表里这两格写的是「B8 起 ✓ / Prompted（B8）」，那是终态，见 [CodexWithTurnInterrupt]。
         * 适配器按 manager 里 `turn/interrupt` 接没接上选其一，不要自己 copy。
         */
        val Codex = EngineCapabilities(
            maxConcurrent = 1,
            images = false,
            busySend = BusySend.Queue,
            withdrawQueued = true,
            withdrawInFlight = false,
            interruptTurn = false,
            resume = ResumeSupport.None,
            approvalAlways = true,
            questions = false,
            planMode = false,
            modelHotSwap = true,
            effortHotSwap = true,
            cwdChange = true,
            userShell = false,
            checkpointRevert = false,
            subagents = false,
            subagentDirectMessage = false,
            backgroundTasks = false,
            cost = false,
            cacheStats = false,
            liveTitle = false,
            exportImport = false,
            processOutput = true,
            resumeAdvice = false,
            recap = false,
            rewind = false,
            mcpServers = false,
            workspaceTrust = false,
            team = false,
            autoReview = false,
            schedule = false,
            claudeConfigFiles = ConfigFiles.None,
        )

        /** B8 之后的 Codex：`turn/interrupt` 接上了，停止只停本轮，▶ 用续跑提示（设计表里的终态） */
        val CodexWithTurnInterrupt = Codex.copy(interruptTurn = true, resume = ResumeSupport.Prompted)

        /**
         * Min Agent。`questions` / `planMode` 设计表写「interact 合入后 ✓」，该分支已并进 agent/m0
         * （52423ae），本分支从它切出，所以是 true。`approvalAlways` 仍是 false：内核侧的规则判定已接上（K14），
         * 但「`Always(rule)` 写进用户选的那一层 settings 文件」是宿主的活，随 B7 的审批卡做。
         */
        val MinAgent = EngineCapabilities(
            maxConcurrent = 6,
            images = true,
            busySend = BusySend.Steer,
            withdrawQueued = true,
            withdrawInFlight = false,
            interruptTurn = true,
            resume = ResumeSupport.Native,
            // 内核的规则判定（K14）早已接上；B7 的审批卡把规则写进用户选的那一层 settings（MinPermissionRules）
            approvalAlways = true,
            questions = true,
            planMode = true,
            modelHotSwap = true,
            effortHotSwap = true,
            cwdChange = false,
            userShell = false,
            // 改动面板的撤销走内核的编辑记录（含子 agent 的编辑），见 MinEditRestore
            checkpointRevert = true,
            subagents = true,
            subagentDirectMessage = true,
            backgroundTasks = true,
            // 目录有官方标价（内核按请求记在每个会话的 modelCosts 里，Min 对整棵树求和，见 MinCost.kt）：按币种原样显示，有请求没有价格就不显示
            cost = true,
            cacheStats = true,
            liveTitle = false,
            exportImport = false,
            processOutput = false,
            resumeAdvice = true,
            recap = true,
            rewind = true,
            mcpServers = true,
            workspaceTrust = true,
            team = true,
            autoReview = true,
            advisor = true,
            schedule = true,
            claudeConfigFiles = ConfigFiles.Partial,
        )

        fun of(kind: EngineKind): EngineCapabilities = when (kind) {
            EngineKind.ClaudeCode -> ClaudeCode
            EngineKind.Codex -> Codex
            EngineKind.MinAgent -> MinAgent
        }
    }
}

enum class BusySend {
    /** 插进当前回合（CC：交棒进 stdin；Min：`Delivery.Steer`，当前工具批结束后送达） */
    Steer,

    /** 等本回合结束再发（Codex） */
    Queue,
}

/**
 * 引擎的「继续」支持程度。设计草稿里能力位和 [ResumeOffer.mode] 共用一个 `ResumeMode`，
 * 但两者取值不同（前者有 None、后者有 RelaunchThenPrompted），拆成两个类型让非法组合写不出来。
 */
enum class ResumeSupport {
    None,

    /** 由 Min 代发一句续跑提示（CC、Codex）：`Continue from where you left off.` */
    Prompted,

    /** 引擎自己续（Min 内核的 `resume()`），不往历史里加任何消息 */
    Native,
}

/**
 * Claude 配置面板（`/mcp /agents /memory /permissions /config`）的程度。Min 经 ext-config 读同一批
 * CLAUDE.md、agents、settings，但 MCP 一项不适用，所以是 [Partial]：入口显示，面板里不适用的分区自己隐藏。
 */
enum class ConfigFiles { None, Partial, Full }
