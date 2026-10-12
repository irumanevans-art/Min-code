package dev.min.code.ui.session

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeCodeCostLedger
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodePermissionMode
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.claudecode.ComposerDraft
import dev.min.code.core.claudecode.ComposerDraftStore
import dev.min.code.core.codex.CodexDraftStore
import dev.min.code.core.codex.CodexRuntime
import dev.min.code.core.settings.CodexAuthMode
import dev.min.code.core.settings.CodexProfile
import dev.min.code.core.engine.codex.CodexStateMapper
import kotlinx.coroutines.Dispatchers
import dev.min.code.core.claudecode.DraftImage
import dev.min.code.core.engine.AgentSession
import dev.min.code.core.engine.AgentSessionRegistry
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.AttachResult
import dev.min.code.core.engine.EngineAvailability
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.CloseReason
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.ImageInput
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.LiveStream
import dev.min.code.core.engine.PendingResume
import dev.min.code.core.engine.RefreshTarget
import dev.min.code.core.engine.ResumeOffer
import dev.min.code.core.engine.SendTarget
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SessionSignal
import dev.min.code.core.engine.StartChoices
import dev.min.code.core.engine.StartOptions
import dev.min.code.core.engine.TaskOutputTail
import dev.min.code.core.engine.UserInput
import dev.min.code.core.engine.claude.ClaudeCodeEngine
import dev.min.code.core.engine.claude.ClaudeStateMapper
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.MENTION_LIMIT
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.rootfs.createCwdFolder
import dev.min.code.core.rootfs.listCwdFolders
import dev.min.code.core.session.SessionStatus
import dev.min.code.core.settings.SettingsStore
import me.rerere.workspace.WorkspaceFileEntry
import java.util.UUID

/**
 * 当前会话本身：状态、输入框草稿、发消息与会话内的各种控制。
 *
 * 从原来的 ClaudeCodeVM 拆出来（M2-B B6a），B6(b) 起页面读的是引擎无关的类型：状态是 [AgentSessionState]，
 * 逐字增量是 [LiveStream]，写入一律是 [SessionCommand] 经 [AgentSession.submit]。
 *
 * ## B9：Codex 也经统一注册表
 *
 * Min 与 Codex 的会话由 [AgentSessionRegistry] 管，页面读的是同一个活跃会话（[agentSession]）；Codex 的安装 / 登录 / 连接方式
 * （`CodexRuntime`、连接配置表）是它自己的环境，开始面板上的 Codex 一栏经本类读写。Codex 的输入框草稿并进统一的草稿盘
 * （旧的 `codex-drafts.json` 一次性迁移，见 [migrateCodexDrafts]）。
 *
 * ## 为什么建 / 开 / 关会话仍走旧注册表
 *
 * 设计 §3.10 写的是经 `AgentSessionRegistry.create/open/close`，但那条路和现行为有三处不一样，B6 要求行为不变：
 * 1. 它先查引擎的 availability，没连供应商时直接 Unavailable；旧页面照样让人点「启动会话」；
 * 2. 它的并发名额算「在线 ∪ 界面打开着的」，崩掉的壳留在 attached 里会一直占位，没有 `pruneDead` 那样的回收；
 * 3. `close` 只摘它自己的 attached，旧注册表里的 manager 还在，`pruneDead` 靠旧的活跃项判「用户正看着」。
 * 所以生命周期仍由 [ClaudeCodeSessionRegistry] 管（B9 把它拆进 `ClaudeCodeEngine` 时一并换掉），**活跃会话只有它一个来源**：
 * [ClaudeCodeEngine.activeSession] 把它的活跃项套成通用会话，本类读状态、发命令都经那一个对象。
 *
 * 本类独占的状态是 [composerDraft] 和 [notice]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionVM(
    private val context: Application,
    private val registry: ClaudeCodeSessionRegistry,
    private val engine: ClaudeCodeEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val costLedger: ClaudeCodeCostLedger,
    private val settingsStore: SettingsStore,
    private val drafts: ComposerDraftStore,
    /** Min 与 Codex 的会话由它管（B6 起 Claude Code 仍走 [registry]，见本类头注释） */
    private val agents: AgentSessionRegistry,
    foreground: dev.min.code.core.service.AppForeground,
    /** Codex 的安装 / 登录状态与 app-server 启动配置 */
    private val codexRuntime: CodexRuntime,
    /** 旧 Codex 页的输入框草稿盘：只在启动时一次性迁进 [drafts]，之后不再写 */
    private val oldCodexDrafts: CodexDraftStore,
) : ViewModel() {
    /** Min 实验开关。关着时 Min 的会话不显示（引擎已被停下、会话文件还在，见 [dev.min.code.core.settings.AppSettings.minAgentEnabled]） */
    val minEnabled: StateFlow<Boolean> = settingsStore.settings.map { it.minAgentEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsStore.snapshot?.minAgentEnabled ?: false)

    /** 统一注册表的活跃会话（Min 或 Codex）。Min 关着时它的会话不显示；Codex 没有开关 */
    private val agentSession: StateFlow<AgentSession?> = combine(agents.active, minEnabled) { session, on ->
        session?.takeIf { shownBy(it, on) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, agents.active.value?.takeIf { shownBy(it, minEnabled.value) })

    /**
     * 显示哪一边。新建 / 打开时显式设（旧注册表的活跃项没变就不会发射），通知等外部切换由 [focusChanges] 跟上。
     * 初值：有 Claude 活跃会话就是它，否则看 Min。
     */
    private val focus = MutableStateFlow(
        if (engine.activeSession.value == null && agentSession.value != null) Shown.Agents else Shown.ClaudeCode,
    )

    init {
        viewModelScope.launch { focusChanges(registry.activeKey, agents.activeRef).collect { focus.value = it } }
    }

    /** 当前活跃的会话（通用接口）。没有时 null */
    private val active: StateFlow<AgentSession?> =
        combine(focus, engine.activeSession, agentSession, ::shownSession)
            .stateIn(viewModelScope, SharingStarted.Eagerly, shownSession(focus.value, engine.activeSession.value, agentSession.value))

    /** 信任卡、MCP 批准 / 面板、登录确认的状态与动作（读页面此刻显示的会话的能力，见 [SessionExtras]） */
    internal val extras = SessionExtras(context, viewModelScope, active, agents, settingsStore, foreground)

    /** Codex 的安装 / 登录状态（开始面板的 Codex 一栏读它） */
    val codexStatus: StateFlow<CodexRuntime.Status> = codexRuntime.status

    /** Codex 的全部连接配置与当前选中的那条（连接方式面板读它） */
    val codexProfiles: StateFlow<List<CodexProfile>> = settingsStore.settings.map { it.codexProfiles }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val codexProfile: StateFlow<CodexProfile> = settingsStore.settings
        .map { it.activeCodexProfile ?: CodexProfile(id = CODEX_DEFAULT_PROFILE_ID) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CodexProfile(id = CODEX_DEFAULT_PROFILE_ID))

    /** 开始面板上 Min 专有的选项（供应商档 / 模型 / 权限模式）；没有 Min 引擎时 null */
    val startChoices: StateFlow<StartChoices?> = (agents.engines.firstOrNull { it.kind == EngineKind.MinAgent }?.startChoices
        ?: MutableStateFlow<StartChoices?>(null))

    /** 各引擎此刻能不能新建会话：开始面板据此把不可用的灰掉、下面写原因和去处 */
    val availability: StateFlow<Map<EngineKind, EngineAvailability>> =
        combine(agents.engines.map { engine -> engine.availability.map { engine.kind to it } }) { it.toMap() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, agents.engines.associate { it.kind to it.availability.value })

    /** 开始面板默认选中的引擎 = 上次用的（没选过是 Claude Code）；Min 关着时退回 Claude Code */
    val startEngine: StateFlow<EngineKind> = combine(settingsStore.settings, minEnabled) { settings, minOn ->
        EngineKind.entries.firstOrNull { it.name == settings.lastStartEngine }
            ?.takeIf { it != EngineKind.MinAgent || minOn } ?: EngineKind.ClaudeCode
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EngineKind.ClaudeCode)

    fun rememberStartEngine(kind: EngineKind) {
        viewModelScope.launch { settingsStore.setLastStartEngine(kind.name) }
        // 选中 Codex 时读一次它的安装 / 登录状态（要在 proot 里跑两条命令，不在每次进页面时无谓地跑）
        if (kind == EngineKind.Codex) refreshCodex()
    }

    /** 活跃会话所属引擎能做什么：页面按它决定哪些控件出现（设计 §3.8），不按引擎种类写分支 */
    val capabilities: StateFlow<EngineCapabilities> = active
        .map { it?.capabilities ?: EngineCapabilities.ClaudeCode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, active.value?.capabilities ?: EngineCapabilities.ClaudeCode)

    /**
     * 活跃会话的状态（注册表在多个会话间切换时自动跟随）。没有活跃会话时给一个 Idle 占位，
     * 页面侧不用到处判空，和改造前一样。
     *
     * 初值取此刻活跃会话的当前状态而不是占位：VM 在转屏 / 回到页面时重建，会话早就在跑，
     * 第一帧不能先闪一下「没有会话」的启动面板。
     */
    val session: StateFlow<AgentSessionState> = active
        .flatMapLatest { it?.state ?: flowOf(NoSession) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, active.value?.state?.value ?: NoSession)

    /** 活跃会话的逐字增量（正文 / 思考）。和 [session] 分开，流式期间只有读它的那一块在重组 */
    val stream: StateFlow<LiveStream> = active
        .flatMapLatest { it?.stream ?: flowOf(LiveStream.Empty) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, active.value?.stream?.value ?: LiveStream.Empty)

    /**
     * 注册表的会话 key。CLI 还没回报 `sessionId` 时状态里是空的，
     * 但草稿必须立刻能落盘，所以输入框钉在这个 key 上。
     */
    val activeSessionKey: StateFlow<String?> = combine(active, registry.activeKey) { session, claudeKey ->
        // Min / Codex 的会话没有「CLI 还没回报 id」那一段，草稿直接按 storageKey 钉
        session?.ref?.takeIf { it.engine != EngineKind.ClaudeCode }?.storageKey ?: claudeKey
    }.stateIn(viewModelScope, SharingStarted.Eagerly, registry.activeKey.value)

    /**
     * 输入框草稿。按会话钉在磁盘上，杀进程再进同一会话还在。
     * 打开冲突（顺延的空框对上一个已经有内容的会话）时拒绝切换，当前会话的框不动。
     */
    private val _composerDraft = MutableStateFlow(ComposerDraft.Empty)
    val composerDraft: StateFlow<ComposerDraft> = _composerDraft.asStateFlow()

    /** 命令被拒、话要原样退回输入框（会话已关掉发不出去）。和 [SessionSignal.Withdrawn] 合并成 [withdrawnMessages] */
    private val returnedMessages = MutableSharedFlow<ComposerDraft>(extraBufferCapacity = 4)

    /**
     * 按停止键（Esc）在本轮还没产出时撤回的消息，要原样退还给输入框。
     * 和 [composerDraft] 分开：那是可以被重放的状态，这是一次性事件。
     *
     * 没人收（页面没组合）时不订阅引擎的信号：manager 靠「有没有订阅者」决定要不要把话落盘兜底，
     * 订阅了又不看就等于把话吞掉。
     */
    val withdrawnMessages: Flow<ComposerDraft> = merge(
        active.flatMapLatest { session ->
            session?.signals?.filterIsInstance<SessionSignal.Withdrawn>()?.map { it.input.toComposerDraft() } ?: emptyFlow()
        },
        returnedMessages,
    )

    /** 会话发来的轻提示（「总是允许」的规则存好了 / 没存成的原因）。页面收着弹 Toast；没人收时引擎不会替它攒 */
    val signalNotices: Flow<SessionSignal.Notice> = active.flatMapLatest { session ->
        session?.signals?.filterIsInstance<SessionSignal.Notice>() ?: emptyFlow()
    }

    /** 会话请界面切到另一个会话（`/branch` 分出来的新会话）。页面收着就照「打开会话」接上 */
    val sessionOpenRequests: Flow<SessionRef> = active.flatMapLatest { session ->
        session?.signals?.filterIsInstance<SessionSignal.OpenSession>()?.map { it.ref } ?: emptyFlow()
    }

    /** 今天的累计花费，跨会话、跨重启。底栏那个金额读它 */
    val dailyCostUsd: StateFlow<Double> = costLedger.dailyCostUsd

    /** 命令 / 模型说明显示中文对照还是英文原文 */
    val chineseDescriptions: StateFlow<Boolean> = settingsStore.settings
        .map { it.chineseDescriptions }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setChineseDescriptions(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setChineseDescriptions(enabled) }
    }

    /** 在线会话的摘要（`keepAlive` = 进程活着），供抽屉打运行中标记、终端页给每个在跑的会话开页签 */
    val liveSessions = engine.live

    /** 达到并发上限时给 UI 一个提示 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()

    fun dismissNotice() { _notice.value = null }

    /**
     * 有没有可用的连接（走订阅，或当前生效的供应商带着 token）。没有时启动面板给 [ConnectPrompt]。
     *
     * 跟着 DataStore 走，不在 [MaintenanceVM.refresh] 里读一次：人从供应商页加完一家回来，提示要自己消失，
     * 不能等他去点刷新。初值 true：设置还没读到的那一下不闪「未连接」。
     */
    val connected: StateFlow<Boolean> = settingsStore.settings
        .map { it.claudeConnected }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * 按下 ▶ 之后、回合还没起来的那一段：键转圈、禁止重复按（`composerKeys` 只认 offerId 仍等于当前 offer 的，过期的自己失效）。
     * 回合一起来 / offer 变了就丢（见 init 里的收集）；命令被拒（过期、会话没了）也丢，并轻提示一句。
     *
     * 声明必须在下面的 init 之前：init 里的收集在主线程立刻执行，属性还没初始化就会读到 null（冷启动崩溃，模拟器验收抓到的）。
     */
    private val _pendingResume = MutableStateFlow<PendingResume?>(null)
    val pendingResume: StateFlow<PendingResume?> = _pendingResume.asStateFlow()

    private val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()

    init {
        // 回合起来了、或 offer 已经不是按下去的那个：转圈该结束了
        viewModelScope.launch {
            session.collect { state ->
                val pending = _pendingResume.value ?: return@collect
                val stillOffered = state.resume?.offerId == pending.offerId || state.threads.any { it.resume?.offerId == pending.offerId }
                if (state.turn != null || !stillOffered) _pendingResume.value = null
            }
        }
        // 进程被 FGS 保活、页面重建时，注册表里的会话还在，草稿要从磁盘接回来
        activeSessionKey.value?.let { attachComposer(it) }
        // 旧 Codex 页的草稿并进统一草稿盘（一次性，之后旧盘是空的）
        viewModelScope.launch(Dispatchers.IO) { migrateCodexDrafts(oldCodexDrafts, drafts) }
    }

    // --- 会话（多会话：注册表持有多个 manager，各自一个 CLI 进程） ---

    /**
     * 启动面板的「启动会话」。设置沿用活跃会话的（没有就是上次用过的那套，引擎自己取），
     * 只有工作目录和「跳过权限」是面板上现选的。
     */
    fun start(request: StartRequest) {
        if (request.engine == EngineKind.MinAgent) {
            createMin(StartOptions(cwd = request.cwd, profileId = request.profileId, modelId = request.modelId, modeId = request.modeId, loadingModes = request.loadingModes))
            return
        }
        if (request.engine == EngineKind.Codex) {
            createAgentNow(EngineKind.Codex, StartOptions(cwd = request.cwd, modeId = request.modeId))
            return
        }
        val base = claudeStartBase()
        create(base.copy(cwd = request.cwd, modeId = if (request.skipPermissions) ClaudeCodePermissionMode.BYPASS.wire else base.modeId))
    }

    /** 抽屉的「新建会话」：照搬当前会话（哪个引擎就还是哪个引擎）的设置再开一个 */
    fun newSession() {
        // 没有活跃会话时（刚启动 App）不能退回默认值 —— 那会把用户上次选的模型/effort 丢掉，
        // 新会话默默跑在 CLI 默认模型上。startOptionsOfActive 在没有活跃会话时交给引擎取上次存的那套
        // 没有活跃会话时跟「上次开始面板选的引擎」走：刚启动 App、上次用的是 Min，点「新建会话」不能悄悄开出一个 Claude Code
        // （模拟器验收抓到的）。Min 没有活跃会话就没有可照搬的设置，档与模型由引擎取最近一次用的
        val engine = active.value?.ref?.engine ?: startEngine.value
        when (engine) {
            EngineKind.MinAgent -> createMin(startOptionsOfActive())
            // Codex 只有一个 app-server：「新会话」就是结束手上这条、另起一条（`/new` 与抽屉的新建都是这个意思）
            EngineKind.Codex -> createAgentNow(EngineKind.Codex, startOptionsOfActive(), endRunning = true)
            EngineKind.ClaudeCode -> create(startOptionsOfActive())
        }
    }

    private fun create(options: StartOptions) {
        viewModelScope.launch {
            // 引擎的 create 里没有真正的挂起点（旧注册表同步建），这里和原来一样同步走完
            if (engine.create(options).isFailure) atCapacity()
            else {
                focus.value = Shown.ClaudeCode
                attachComposer(registry.activeKey.value)
            }
        }
    }

    /** Min 的会话经统一注册表建：先查引擎可用、再查名额，结果各有一句给人看的话 */
    private fun createMin(options: StartOptions) {
        // 信任必须在建会话之前问完：会话创建时项目层 hooks 就冻结进日志了
        extras.gateStart(options, ::createMinNow)
    }

    private fun createMinNow(options: StartOptions) = createAgentNow(EngineKind.MinAgent, options)

    /** 经统一注册表建一个 Min / Codex 会话：先查引擎可用、再查名额，结果各有一句给人看的话 */
    private fun createAgentNow(kind: EngineKind, options: StartOptions, endRunning: Boolean = false) {
        viewModelScope.launch {
            if (kind == EngineKind.Codex) {
                if (endRunning) {
                    agents.attached.value.filter { it.ref.engine == EngineKind.Codex }.forEach { agents.close(it.ref, CloseReason.UserEnded) }
                    // 在线摘要是异步推出来的，刚关完它还报着「在线保活」，注册表会因此数满名额：等它落下再建
                    withTimeoutOrNull(3_000) { agents.live.first { list -> list.none { it.ref.engine == EngineKind.Codex && it.keepAlive } } }
                }
                releaseIdleCodex(except = null)
            }
            when (val result = agents.create(kind, options)) {
                is AttachResult.Ok -> {
                    focus.value = Shown.Agents
                    val key = result.session.ref.storageKey
                    // 旧 Codex 页里「还没起线程时打的字」记在一个固定的「新会话」键下：新会话一起来就把它领过来，原样放进输入框
                    if (kind == EngineKind.Codex) adoptNewThreadDraft(key)
                    attachComposer(key)
                }
                else -> _notice.value = attachFailureText(result)
            }
        }
    }

    /**
     * Codex 同一时间只有一个 app-server，所以只有一个名额；而页面上打开着一个**不在跑**的只读回放（或已结束的会话）
     * 也算占着它（统一注册表按「在线 ∪ 界面打开着」数）。新建 / 打开另一条之前先把没在跑的那个撤掉，
     * 否则翻看旧会话一次就再也开不了第二条（旧 Codex 页没有这个问题：它只有一个屏幕，翻到哪条就是哪条）。
     * 在跑的不动：它占名额是真的，由注册表返回「已满」，让用户先结束它。
     *
     * [except] 是接下来要打开的那条：它若已经在打开着就不用动（注册表直接切过去），**除非**它的进程已经没了（Closed / Failed）——
     * 那是一个死壳，页面早已退回开始面板，再「切过去」什么也看不到，要关掉重新回放才打得开
     */
    private suspend fun releaseIdleCodex(except: SessionRef?) {
        agents.attached.value
            .filter { it.ref.engine == EngineKind.Codex }
            .filter { it.state.value.status != SessionStatus.Running && it.state.value.status != SessionStatus.Starting }
            .filter { it.ref != except || it.state.value.status != SessionStatus.Idle }
            .forEach { agents.close(it.ref, CloseReason.Evicted) }
    }

    private fun adoptNewThreadDraft(storageKey: String) {
        val carried = drafts.load(CODEX_NEW_THREAD_DRAFT_KEY)
        if (carried.isEmpty) return
        drafts.save(storageKey, carried)
        drafts.forget(CODEX_NEW_THREAD_DRAFT_KEY)
    }

    /** 建 / 开会话没成时给人看的那句话 */
    private fun attachFailureText(result: AttachResult): String = when (result) {
        is AttachResult.AtCapacity -> context.getString(R.string.vm_max_sessions, result.max)
        is AttachResult.Unavailable -> availabilityText(context, result.availability)
        is AttachResult.Failed -> result.message
        is AttachResult.Ok -> ""
    }

    /**
     * 照搬活跃会话此刻的设置当作新会话的起点。每一项都显式给：空串是「明确跟随默认」，
     * 与 null（没说，沿用上次存的）不同，见 [ClaudeCodeEngine.create]。
     */
    /** 开始面板选 Claude Code 时的起点：当前活跃的是 Claude 会话就照搬它，否则（没有 / 是 Min）交给引擎取上次存的那套 */
    private fun claudeStartBase(): StartOptions =
        if (active.value?.ref?.engine == EngineKind.ClaudeCode) startOptionsOfActive()
        else StartOptions(cwd = registry.lastOptions().cwd)

    private fun startOptionsOfActive(): StartOptions {
        val current = active.value?.state?.value ?: return StartOptions(cwd = registry.lastOptions().cwd)
        val settings = current.settings
        if (current.ref.engine == EngineKind.Codex) {
            // Codex：同一个工作目录与权限档；模型 / 强度没选过就是没选过（空串 = 明确跟随连接配置的默认）
            return StartOptions(
                cwd = current.cwd,
                modelId = settings.model?.id ?: "",
                modeId = settings.mode.id.takeIf { id -> settings.modes.any { it.id == id } },
                effortId = settings.effort?.id ?: "",
                engineOptions = settings.extras.filter { it.key == CodexStateMapper.OPTION_SUMMARY }.associate { it.key to it.value },
            )
        }
        if (current.ref.engine == EngineKind.MinAgent) {
            // Min：同一个供应商档、同一个模型 / 权限模式 / 思考强度（没选过强度就是没选过，空串 = 明确跟随默认）
            return StartOptions(
                cwd = current.cwd,
                profileId = current.connection?.profileId,
                modelId = settings.model?.id,
                modeId = settings.mode.id,
                effortId = settings.effort?.id ?: "",
                loadingModes = settings.loadingModes,
            )
        }
        val ultracode = settings.extras.firstOrNull { it.key == ClaudeStateMapper.OPTION_ULTRACODE }?.value == "true"
        return StartOptions(
            cwd = current.cwd,
            modelId = settings.model?.id ?: "",
            modeId = settings.mode.id,
            effortId = if (ultracode) ClaudeStateMapper.OPTION_ULTRACODE else settings.effort?.id ?: "",
            engineOptions = settings.extras
                .filter { it.key == ClaudeStateMapper.OPTION_CACHE_TTL }
                .associate { it.key to it.value },
        )
    }

    /** 启动面板上默认选中的工作目录：活跃会话的，没有就是上次用过的；空白退回 /workspace */
    fun defaultCwd(): String =
        (active.value?.state?.value?.cwd ?: registry.lastOptions().cwd).ifBlank { ClaudeCodeManager.DEFAULT_CWD }

    /** 已在跑就直接切过去（不重启进程），否则新起一个并 --resume 续上 */
    fun openSession(ref: SessionRef) {
        if (ref.engine == EngineKind.ClaudeCode) openClaude(ref.id) else openAgent(ref)
    }

    /** Min / Codex 的会话经统一注册表开（已经在线就直接切过去，不重开）。草稿按 storageKey 钉，规矩与 Claude 那边一样 */
    private fun openAgent(ref: SessionRef) {
        val key = ref.storageKey
        val existing = drafts.load(key)
        if (drafts.hasCarry() && !existing.isEmpty) {
            _notice.value = context.getString(R.string.vm_draft_conflict)
            return
        }
        viewModelScope.launch {
            if (ref.engine == EngineKind.Codex) releaseIdleCodex(except = ref)
            when (val result = agents.open(ref)) {
                is AttachResult.Ok -> {
                    focus.value = Shown.Agents
                    if (drafts.hasCarry()) drafts.consumeCarry(key)
                    drafts.rememberActive(key)
                    _composerDraft.value = existing
                    extras.afterOpen(result.session)
                }
                else -> _notice.value = attachFailureText(result)
            }
        }
    }

    private fun openClaude(id: String) {
        val existing = drafts.load(id)
        if (drafts.hasCarry() && !existing.isEmpty) {
            _notice.value = context.getString(R.string.vm_draft_conflict)
            return
        }
        if (registry.openSession(id) == null) {
            atCapacity()
            return
        }
        focus.value = Shown.ClaudeCode
        if (drafts.hasCarry()) drafts.consumeCarry(id)
        drafts.rememberActive(id)
        _composerDraft.value = existing
    }

    /**
     * 抽屉行菜单里的「结束会话」：只停进程，transcript 留着，之后点开照样 --resume 续上。
     * 正看着的那个走顶栏停止键同一条路（留在页面上显示「会话已结束」，攥着的消息退回输入框）；
     * 后台的直接从注册表摘掉 —— 不摘的话活跃项以外的死壳要等下一次 pruneDead 才走
     */
    fun endSession(ref: SessionRef) {
        if (ref.engine != EngineKind.ClaudeCode) {
            viewModelScope.launch { agents.close(ref, CloseReason.UserEnded) }
            return
        }
        if (registry.activeKey.value == ref.id) stop() else registry.closeSession(ref.id)
    }

    private fun atCapacity() {
        _notice.value = context.getString(
            R.string.vm_max_sessions,
            ClaudeCodeSessionRegistry.MAX_CONCURRENT,
        )
    }

    /**
     * 删掉的会话正是输入框钉着的那个时，把框清空。草稿的唯一持有者是本类，
     * [SessionListVM.deleteSession] 删完会话后经页面回调到这里，不直接碰 [_composerDraft]。
     */
    fun resetComposerDraft() {
        _composerDraft.value = ComposerDraft.Empty
    }

    fun saveComposerDraft(sessionId: String?, draft: ComposerDraft) {
        val id = sessionId ?: return
        if (id == activeSessionKey.value) _composerDraft.value = draft
        drafts.save(id, draft)
    }

    /** 进后台 / 清进程前拍一张。空框会把指针顺延到下次打开的第一个会话。 */
    fun snapshotComposerOnStop(sessionId: String?, draft: ComposerDraft) {
        val id = sessionId ?: return
        if (id == activeSessionKey.value) _composerDraft.value = draft
        drafts.snapshotOnStop(id, draft)
    }

    private fun attachComposer(sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            _composerDraft.value = ComposerDraft.Empty
            return
        }
        val existing = drafts.load(sessionId)
        if (drafts.hasCarry() && !existing.isEmpty) {
            _notice.value = context.getString(R.string.vm_draft_conflict)
            return
        }
        if (drafts.hasCarry()) drafts.consumeCarry(sessionId)
        drafts.rememberActive(sessionId)
        _composerDraft.value = existing
    }

    // --- 会话内控制（对应 Desktop 底栏），只作用于活跃会话 ---

    /**
     * 把一条命令交给活跃会话。没有活跃会话时静默忽略 ——
     * UI 在没有会话时显示的是启动面板，本来就点不到这些控件。
     * 在 [viewModelScope]（Main.immediate）里发：不挂起的命令和原来的直接调用一样同步走完。
     */
    private fun command(build: (id: String) -> SessionCommand, onResult: (CommandResult) -> Unit = {}) {
        val target = active.value ?: return
        viewModelScope.launch { onResult(target.submit(build(UUID.randomUUID().toString()))) }
    }

    fun send(text: String, images: List<ImageInput> = emptyList()) {
        val input = UserInput(text.trim(), images)
        if (input.isBlank) return
        command({ SessionCommand.Send(it, input) }) { result ->
            // 没发出去的话（会话关了 / 没起来 / 接不回来，或斜杠命令不存在、预执行被拒）：原样退回输入框，能改了再发；
            // 并说一句为什么——只退回不说原因，人不知道为什么没发出去
            if (result is CommandResult.Rejected) {
                returnedMessages.tryEmit(input.toComposerDraft())
                if (result.message.isNotBlank()) _notice.value = result.message
            }
        }
    }

    /** `@` 提及的文件补全。没有活跃会话时给空表，输入框自然不弹候选。 */
    suspend fun searchFiles(query: String): List<String> =
        active.value?.searchFiles(query, MENTION_LIMIT).orEmpty()

    /** 回答一张交互卡（审批 / 提问 / 计划审批）。[interactionId] 是 [dev.min.code.core.engine.PendingInteraction.id] */
    fun answer(interactionId: String, answer: InteractionAnswer) =
        command({ SessionCommand.Answer(it, interactionId, answer) })

    /** ■。[runQueuedAfter] = 显式的「打断后跑排队」那一档（默认打断并把排队的退回输入框，§10.2 拍板 2） */
    fun interrupt(runQueuedAfter: Boolean = false) = command({ SessionCommand.Interrupt(it, runQueuedAfter = runQueuedAfter) })

    /** ▶：按 offer 里的 id 续跑（主会话或子 agent 的 offer 都走这一条，适配器按 id 认） */
    fun resume(offer: ResumeOffer) {
        _pendingResume.value = PendingResume.forPress(offer)
        command({ SessionCommand.Resume(it, offer.offerId) }) { result ->
            if (result !is CommandResult.Accepted) {
                _pendingResume.value = null
                if (result is CommandResult.Stale) _notice.value = context.getString(R.string.resume_stale)
            }
        }
    }

    /** 点一下排队中的那条：把它从队列里取回（话经 [SessionSignal.Withdrawn] 回到输入框）。[itemId] 是 `ChatItem.UserText.id` */
    fun withdrawQueued(itemId: String) = command({ SessionCommand.WithdrawQueued(it, itemId) }) { result ->
        if (result is CommandResult.Stale) _notice.value = context.getString(R.string.resume_stale)
    }

    /** 子 agent 视图里发的话。图片递不进子 agent（hook 只能带文字），适配器会让 manager 补一条说明 */
    fun sendToSubagent(threadId: String, text: String, images: List<ImageInput> = emptyList()) =
        command({ SessionCommand.Send(it, UserInput(text, images), SendTarget.Subagent(threadId)) })

    fun stopSubagent(threadId: String) = command({ SessionCommand.StopSubagent(it, threadId) })

    fun runShell(command: String) = command({ SessionCommand.RunShell(it, command) })

    /** 顶栏停止键：只停进程，会话留在页面上显示「已结束」（[CloseReason.UserEnded]） */
    fun stop() {
        val target = active.value ?: return
        // Min / Codex 要经统一注册表关：它同时把会话从「界面打开着的」里摘掉，页面才会回到开始面板
        viewModelScope.launch {
            if (target.ref.engine != EngineKind.ClaudeCode) agents.close(target.ref, CloseReason.UserEnded)
            else target.close(CloseReason.UserEnded)
        }
    }

    /**
     * 切模型。[asDefault] 对齐 CLI `/model` 的 Enter / `s`：
     * true = 本会话热切 + 写 settings.json 的 `model`（新会话、终端页的 `claude` 都跟着变，写文件那步在适配器里）；
     * false = 只改这个会话。`/model <名字>` 这种带参数的写法在 CLI 里等于 Enter，也走 true。
     * [model] 为 null = 跟随默认。
     */
    fun setModel(model: String?, asDefault: Boolean = true, profileId: String? = null) =
        command({ SessionCommand.SetModel(it, model.orEmpty(), profileId = profileId, asDefault = asDefault) })

    fun setPermissionMode(modeId: String) = command({ SessionCommand.SetMode(it, modeId) })

    /** 引擎专有的设置项（Codex 的思考摘要…），键同 [dev.min.code.core.engine.EngineOptionView.key] */
    fun setEngineOption(key: String, value: String) = command({ SessionCommand.SetEngineOption(it, key, value) })

    /** [ultracode] 为真时 [effort] 无意义（ultracode 自己就是一档 effort，与强度互斥） */
    fun applyEffort(effort: String?, ultracode: Boolean = false) =
        command({ SessionCommand.SetEffort(it, if (ultracode) ClaudeStateMapper.OPTION_ULTRACODE else effort) })

    /** 提示缓存 TTL（5m / 1h）。和 effort 一样要重启 CLI 续接会话 */
    fun setPromptCacheTtl(ttl: String) =
        command({ SessionCommand.SetEngineOption(it, ClaudeStateMapper.OPTION_CACHE_TTL, ttl) })

    fun refreshModels() = command({ SessionCommand.Refresh(it, setOf(RefreshTarget.Models)) })
    fun refreshPlan() = command({ SessionCommand.Refresh(it, setOf(RefreshTarget.Plan)) })

    fun refreshUsage() {
        // 跨过午夜之后那个数字应该自己归零，而不是等下一次花钱才刷新
        costLedger.refresh()
        command({ SessionCommand.Refresh(it, setOf(RefreshTarget.Usage)) })
    }

    fun setCwd(path: String) = command({ SessionCommand.SetCwd(it, path) })

    /** 设置里「添加附加目录」：内核的 /add-dir。被拒（已在工作目录里、不存在……）时原因经 [addDirectoryRejections] 给界面 */
    fun addDirectory(path: String) = command({ SessionCommand.AddDirectory(it, path) }) { result ->
        if (result is CommandResult.Rejected) addDirectoryRejections.tryEmit(result.message)
    }

    /** 加目录被拒的原因（一次性提示，界面弹 Toast / Snackbar） */
    val addDirectoryRejections = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * 列出某个 guest 路径下的条目，给选文件夹面板用。
     * 失败（目录不存在、越界）走 Result，面板显示原因而不是崩。
     */
    suspend fun listCwdFolders(guest: String): Result<List<WorkspaceFileEntry>> =
        workspaceRepository.listCwdFolders(workspaceId, guest)

    fun createCwdFolder(parent: String, name: String, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch {
            onDone(
                workspaceRepository.createCwdFolder(
                    workspaceId,
                    parent,
                    name,
                    invalidName = context.getString(R.string.vm_invalid_name),
                ),
            )
        }
    }

    /**
     * 撤销一次编辑类工具造成的文件改动（只还原文件，对话历史不变）。
     * [onRestored] 在还原**成功**之后回调（主线程），失败不回调——页面据此才把它标成「已撤销」。
     */
    fun revertToolCall(toolUseId: String, onRestored: () -> Unit = {}) =
        command({ SessionCommand.Revert(it, toolUseId) }) { if (it == CommandResult.Accepted) onRestored() }

    /**
     * 把一个文件在本会话里的全部改动整批撤回去（从最后一次往前还原；同样只动文件）。
     * [toolUseIds] 按会话顺序（旧 → 新）。[onRestored] 收到**真的还原成功**的那几个。
     */
    fun revertFile(name: String, toolUseIds: List<String>, onRestored: (Set<String>) -> Unit) {
        val target = active.value ?: return
        viewModelScope.launch { onRestored(target.revertFile(name, toolUseIds)) }
    }

    /** [toolUseIds] 里存过文件快照的那些，决定「改动过的文件」面板里谁有撤销按钮 */
    suspend fun checkpointed(toolUseIds: Collection<String>): Set<String> =
        active.value?.checkpointed(toolUseIds).orEmpty()

    // --- 后台任务（底栏「N shell」和它的面板） ---

    /** 读一次某个后台任务的输出尾巴（面板在跑时轮询它） */
    suspend fun readTaskOutput(taskId: String): TaskOutputTail? = active.value?.readTaskTail(taskId)

    /**
     * 停掉一个后台任务，各走各的路：CLI 的发 stop_task，托管的（含 bypass 下的孪生）停进程表（适配器分）。
     * 失败返回给用户看的那句话。
     */
    suspend fun stopTask(taskId: String): String? {
        val target = active.value ?: return null
        val result = target.submit(SessionCommand.StopTask(UUID.randomUUID().toString(), taskId))
        return (result as? CommandResult.Rejected)?.message
    }

    /** `/init` 是 prompt 型内置命令，无头模式下当普通消息发过去就会执行 */
    fun runInit() = send("/init")

    // --- Codex 的安装 / 登录 / 连接方式（开始面板的 Codex 一栏） ---

    fun refreshCodex() {
        viewModelScope.launch { codexRuntime.refresh() }
    }

    fun installCodex() {
        viewModelScope.launch { codexRuntime.install() }
    }

    fun saveCodexProfile(profile: CodexProfile) {
        viewModelScope.launch {
            settingsStore.setCodexProfile(profile)
            codexRuntime.refresh()
        }
    }

    /**
     * 换一条。app-server 的 env 是启动时固化的，所以这里只改「下次起的是哪条」；`refresh()` 把 config.toml 重写一遍，
     * 真正生效要等下一次启动（面板上由 `codex_connection_restart_hint` 说明）。
     * 明文风险的确认不在这儿放行：那道门归供应商页管，这里替用户点头会让他此后在那一页也看不到警告
     */
    fun activateCodexProfile(id: String) {
        viewModelScope.launch {
            settingsStore.setActiveCodexProfile(id)
            codexRuntime.refresh()
        }
    }

    /** 新建一条空的中转配置并切过去。内容在同一个面板的下半部分接着填 */
    fun addCodexProfile() {
        viewModelScope.launch {
            settingsStore.addCodexProfile(CodexProfile(id = "", authMode = CodexAuthMode.RELAY))
            codexRuntime.refresh()
        }
    }

    // 不在 onCleared 里杀会话：ClaudeCodeManager 是 Koin single，
    // 会话本就该比页面活得久（切到别的页面、转屏都会重建 VM）。
    // 结束会话由用户在标题栏显式点「停止」。

    /** 这个统一注册表的会话页面显不显示：Min 看实验开关，Codex 没有开关。CC 不在这个注册表里 */
    private fun shownBy(session: AgentSession, minOn: Boolean): Boolean = when (session.ref.engine) {
        EngineKind.Codex -> true
        EngineKind.MinAgent -> minOn
        EngineKind.ClaudeCode -> false
    }

    private companion object {
        /** 没有活跃会话时的占位：Idle，其余全空。和改造前 `SessionState()` 的默认值同一个作用 */
        val NoSession: AgentSessionState =
            AgentSessionState.initial(SessionRef(EngineKind.ClaudeCode, "")).copy(status = SessionStatus.Idle)
    }
}

/**
 * 开始面板上点「启动」时的选择。Claude Code 只看 [skipPermissions]（其余沿用上次，由会话层取）；
 * Min 看供应商档 / 模型 / 权限模式（null = 用最近一次用的，§10.2 拍板 3）。
 */
data class StartRequest(
    val engine: EngineKind,
    val cwd: String,
    val skipPermissions: Boolean = false,
    val profileId: String? = null,
    val modelId: String? = null,
    val modeId: String? = null,
    val loadingModes: dev.min.agent.spi.SessionLoadingModes? = null,
)

/** 引擎不能新建会话时那一行「原因和去处」的文字（开始面板灰掉引擎下面、新建失败的提示都用它） */
internal fun availabilityText(context: Application, availability: EngineAvailability): String = when (availability) {
    EngineAvailability.Ready -> ""
    EngineAvailability.NeedsInstall -> context.getString(R.string.engine_needs_install)
    EngineAvailability.NeedsProvider -> context.getString(R.string.engine_needs_provider)
    is EngineAvailability.Disabled -> availability.reason
}

/** 通用输入 -> 输入框草稿，和原 manager 退还时拼的一样（图片名按序号） */
private fun UserInput.toComposerDraft() = ComposerDraft(
    text = text,
    images = images.mapIndexed { i, img -> DraftImage(name = "图片 ${i + 1}", mediaType = img.mediaType, base64 = img.base64) },
)

/** 没有任何 Codex 连接配置时界面用的占位（与旧 Codex 页同一个 id） */
private const val CODEX_DEFAULT_PROFILE_ID = "default"

/**
 * 旧 Codex 页里「还没起线程时打的字」记在这个键下（那时线程要等第一轮才落会话文件，字只能先挂在「新会话」上）。
 * 统一页里新会话一建就有 threadId，这份字在建好 Codex 会话时领走。键值照旧 Codex 页，迁移才对得上
 */
internal const val CODEX_NEW_THREAD_DRAFT_KEY = "__new_thread__"

/**
 * 旧 Codex 页的输入框草稿（`codex-drafts.json`：threadId → 正文）并进统一草稿盘（按 `SessionRef.storageKey` 存）。
 * **一次性**：搬完就从旧盘里忘掉，所以重复调用什么都不做；统一盘里已经有内容的会话不覆盖（那是新打的）。
 * 「新会话」键下的那份搬到 [CODEX_NEW_THREAD_DRAFT_KEY]，由下一次建 Codex 会话时领走。
 */
internal fun migrateCodexDrafts(old: CodexDraftStore, drafts: ComposerDraftStore) {
    for ((key, text) in old.drafts.value) {
        if (text.isBlank()) {
            old.forget(key)
            continue
        }
        val target = if (key == CODEX_NEW_THREAD_DRAFT_KEY) key else SessionRef(EngineKind.Codex, key).storageKey
        if (drafts.load(target).isEmpty) drafts.save(target, ComposerDraft(text = text))
        old.forget(key)
    }
}
