package dev.min.code.ui.session

import android.app.Application
import dev.min.agent.spi.catchingNonCancellation
import dev.min.code.R
import dev.min.code.core.engine.AgentSession
import dev.min.code.core.engine.AgentSessionRegistry
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.McpControl
import dev.min.code.core.engine.McpLoginCancelled
import dev.min.code.core.engine.McpLoginPrompt
import dev.min.code.core.engine.McpServerView
import dev.min.code.core.engine.RecapOutcome
import dev.min.code.core.engine.ResumeAdviceView
import dev.min.code.core.engine.RewindControl
import dev.min.code.core.engine.RewindResultView
import dev.min.code.core.engine.RewindScopeView
import dev.min.code.core.engine.RewindStorageRow
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.ScheduleControl
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SessionSignal
import dev.min.code.core.engine.StartOptions
import dev.min.code.core.engine.TeamControl
import dev.min.code.core.engine.TrustProbe
import dev.min.code.core.engine.WorkspaceTrustControl
import dev.min.code.core.service.AWAY_RECAP_AFTER_MS
import dev.min.code.core.service.AppForeground
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.WorkspaceTrust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 会话页上「Min 后来长出来」的那些控件的状态与动作：工作区信任卡、MCP 批准卡、MCP 面板、登录确认。
 * 不是 ViewModel：它的生命周期就是 [SessionVM]（由它造、用它的 scope），拆出来只为不让 SessionVM 再长。
 *
 * 一切读 [shown]（页面此刻显示的会话）的能力：没有对应控制面（[AgentSession.mcp] 为 null）的会话，这里的流都是空的，
 * 页面不用判引擎。
 */
internal class SessionExtras(
    private val context: Application,
    private val scope: CoroutineScope,
    private val shown: StateFlow<AgentSession?>,
    private val agents: AgentSessionRegistry,
    private val settings: SettingsStore,
    private val foreground: AppForeground,
) {
    private val _notices = MutableSharedFlow<SessionSignal.Notice>(extraBufferCapacity = 8)

    /** 动作的结果轻提示（页面和会话自己的 signals 一起接到 toast） */
    val notices: SharedFlow<SessionSignal.Notice> = _notices.asSharedFlow()

    // ---- MCP ----

    private val mcp: StateFlow<McpControl?> = shown.map { it?.mcp }
        .stateIn(scope, SharingStarted.Eagerly, shown.value?.mcp)

    /**
     * 此刻的服务器行，约每 [POLL_MS] 毫秒读一次。轮询而不是订阅：内核的 `statusOf` 是实时读表、没有变化流，
     * 而会话日志里的快照只在回合开头更新，拿它画面板会让掉线的服务器一直显示「已连接」。
     * 没人看（页面不在前台）时 5 秒后停下。
     */
    val servers: StateFlow<List<McpServerView>> = mcp.flatMapLatest { control ->
        if (control == null) flowOf(emptyList()) else flow {
            while (true) {
                catchingNonCancellation { control.servers() }.onSuccess { emit(it) }
                delay(POLL_MS)
            }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** 正在重连 / 登录 / 退出登录的服务器：对应按钮置灰、转圈 */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val handledApprovals = MutableStateFlow<Set<Pair<SessionRef, String>>>(emptySet())

    /**
     * 现在该弹批准卡的服务器名（一次一台，答完下一台）。**答过就不再弹**，不管写文件成没成：
     * 写失败时服务器仍是「待批准」，不记下来的话卡片会立刻又弹出来、刷屏
     */
    val pendingApproval: StateFlow<String?> = combine(shown, servers, handledApprovals) { session, rows, handled ->
        val ref = session?.ref ?: return@combine null
        rows.firstOrNull { it.needsApproval && (ref to it.name) !in handled }?.name
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    val loginPrompt: StateFlow<McpLoginPrompt?> = mcp.flatMapLatest { it?.loginPrompt ?: flowOf(null) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun describe(server: String) = mcp.value?.describe(server)

    fun approve(server: String, allow: Boolean) {
        val control = mcp.value ?: return
        val ref = shown.value?.ref ?: return
        handledApprovals.value += ref to server
        run(server) {
            control.approve(server, allow).onFailure { notice(context.getString(R.string.mcp_approve_failed, server, it.message.orEmpty()), error = true) }
        }
    }

    fun reconnect(server: String) = withControl(server) { control ->
        control.reconnect(server).onFailure { notice(context.getString(R.string.mcp_action_failed, server, it.message.orEmpty()), error = true) }
    }

    fun setEnabled(server: String, enabled: Boolean) = withControl(server) { control ->
        control.setEnabled(server, enabled).onFailure { notice(context.getString(R.string.mcp_action_failed, server, it.message.orEmpty()), error = true) }
    }

    fun login(server: String) = withControl(server) { control ->
        control.login(server)
            .onSuccess { notice(context.getString(R.string.mcp_login_done, server), error = false) }
            .onFailure {
                // 在确认卡上点取消不是出错，一句话带过
                if (it is McpLoginCancelled) notice(context.getString(R.string.mcp_login_cancelled, server), error = false)
                else notice(context.getString(R.string.mcp_action_failed, server, it.message.orEmpty()), error = true)
            }
    }

    fun logout(server: String) = withControl(server) { control ->
        control.logout(server)
            .onSuccess { notice(context.getString(R.string.mcp_logout_done, server), error = false) }
            .onFailure { notice(context.getString(R.string.mcp_action_failed, server, it.message.orEmpty()), error = true) }
    }

    fun answerLoginPrompt(proceed: Boolean) = mcp.value?.answerLoginPrompt(proceed)

    private fun withControl(server: String, block: suspend (McpControl) -> Unit) {
        val control = mcp.value ?: return
        run(server) { block(control) }
    }

    private fun run(server: String, block: suspend () -> Unit) {
        // 同一台服务器的动作不叠：重连到一半再点登录没有意义
        if (server in _busy.value) return
        _busy.value += server
        scope.launch {
            try {
                block()
            } finally {
                _busy.value -= server
            }
        }
    }

    private fun notice(text: String, error: Boolean) {
        _notices.tryEmit(SessionSignal.Notice(text, isError = error))
    }

    // ---- 工作区信任 ----

    private val trustControl: WorkspaceTrustControl?
        get() = agents.engines.firstOrNull { it.capabilities.workspaceTrust }?.workspaceTrust

    private val _trustPrompt = MutableStateFlow<TrustPrompt?>(null)

    /** 正在等人答的信任卡；null = 没有 */
    val trustPrompt: StateFlow<TrustPrompt?> = _trustPrompt.asStateFlow()

    /**
     * 新建会话前的信任闸：这个目录第一次进、且带项目层配置才弹卡，答完再接着 [proceed]；
     * 没有要问的（已决定过、什么都没带、引擎不管信任）直接 [proceed]。信任必须在会话创建**之前**问完——
     * 会话创建时项目层 hooks 就冻结进日志了，创建之后再信任要等下一个新会话才生效。
     */
    fun gateStart(options: StartOptions, proceed: (StartOptions) -> Unit) {
        val control = trustControl
        if (control == null) {
            proceed(options)
            return
        }
        scope.launch {
            val probe = catchingNonCancellation { control.probe(options.cwd) }.getOrNull()
            if (probe == null) proceed(options) else _trustPrompt.value = TrustPrompt(probe, then = { proceed(options) })
        }
    }

    /** 打开一个已有会话之后：目录还没问过信任、且带项目配置，补一张卡（不拦打开；它的 hooks 早在创建时就定了，这张卡管的是 MCP） */
    fun afterOpen(session: AgentSession) {
        askResumeAdvice(session)
        if (!session.capabilities.workspaceTrust) return
        val control = trustControl ?: return
        scope.launch {
            val cwd = session.state.value.cwd
            val probe = catchingNonCancellation { control.probe(cwd) }.getOrNull() ?: return@launch
            _trustPrompt.value = TrustPrompt(probe, then = null)
        }
    }

    /** 面板里「信任这个工作区」：用户之前拒绝过也要能改主意，所以强制探测 */
    fun askTrust(cwd: String) {
        val control = trustControl ?: return
        scope.launch {
            val probe = catchingNonCancellation { control.probe(cwd, force = true) }.getOrNull()
                ?: TrustProbe(cwd, emptyList(), emptyList())
            _trustPrompt.value = TrustPrompt(probe, then = null)
        }
    }

    /** [decision]：true 信任、false 不信任、null 取消（新建会话也一并取消） */
    fun answerTrust(decision: Boolean?) {
        val prompt = _trustPrompt.value ?: return
        _trustPrompt.value = null
        val control = trustControl
        if (decision == null || control == null) return
        scope.launch {
            catchingNonCancellation { control.decide(prompt.probe.cwd, decision) }
                .onFailure { notice(context.getString(R.string.trust_save_failed, it.message.orEmpty()), error = true) }
            prompt.then?.invoke()
        }
    }

    /**
     * 当前目录的项目配置会放宽哪些权限（免问规则、附加目录）：信任段列给人看。探一次磁盘，目录换了才重探；
     * `force` 因为已信任 / 已拒绝的目录 [WorkspaceTrustControl.probe] 默认返回 null（它只回答「还要不要问」）
     */
    private val projectGrants: StateFlow<TrustProbe?> = shown.flatMapLatest { session ->
        val control = trustControl
        if (session?.capabilities?.workspaceTrust != true || control == null) flowOf(null)
        else session.state.map { it.cwd }.distinctUntilChanged().map { cwd -> catchingNonCancellation { control.probe(cwd, force = true) }.getOrNull() }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** 设置面板「工作区信任」那一段：当前目录的状态；引擎不管信任时为 null */
    val trustView: StateFlow<TrustView?> = combine(shown, settings.settings, projectGrants) { session, app, grants ->
        if (session?.capabilities?.workspaceTrust != true) return@combine null
        val cwd = session.state.value.cwd
        TrustView(
            cwd = cwd,
            decision = WorkspaceTrust.decision(app.workspaceTrust, cwd),
            ownTrust = WorkspaceTrust.isOwn(app.workspaceTrust, cwd),
            inheritedFrom = WorkspaceTrust.inheritedFrom(app.workspaceTrust, cwd),
            grants = grants?.takeIf { it.cwd == cwd },
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** 设置面板里改决定：[trusted] 为 null = 撤销（忘掉，下次会再问） */
    fun setTrust(cwd: String, trusted: Boolean?) {
        val control = trustControl ?: return
        scope.launch {
            catchingNonCancellation { control.decide(cwd, trusted) }
                .onFailure { notice(context.getString(R.string.trust_save_failed, it.message.orEmpty()), error = true) }
        }
    }

    // ---- 隔了很久回来：续会话摘要与会话回顾 ----

    private val _resumePrompt = MutableStateFlow<ResumePrompt?>(null)

    /** 重开了一个又老又大的会话、等人选「从摘要续接 / 完整续接」；null = 没有 */
    val resumePrompt: StateFlow<ResumePrompt?> = _resumePrompt.asStateFlow()

    /** 打开已有会话之后问内核一次：值不值得先压成摘要再续（隔了一个多小时且上下文超十万 token，见内核 `resumeAdvice`） */
    private fun askResumeAdvice(session: AgentSession) {
        if (!session.capabilities.resumeAdvice) return
        scope.launch {
            val advice = catchingNonCancellation { session.resumeAdvice() }.getOrNull() ?: return@launch
            _resumePrompt.value = ResumePrompt(session, advice)
        }
    }

    /** [summarize] 为真 = 从摘要续接（先压缩，等压完）；否则什么都不做，照原样完整续接 */
    fun answerResume(summarize: Boolean) {
        val prompt = _resumePrompt.value ?: return
        if (!summarize) {
            _resumePrompt.value = null
            return
        }
        if (prompt.working) return
        _resumePrompt.value = prompt.copy(working = true)
        scope.launch {
            val ok = catchingNonCancellation { prompt.session.continueFromSummary() }.getOrDefault(false)
            _resumePrompt.value = null
            notice(context.getString(if (ok) R.string.resume_advice_done else R.string.resume_advice_failed), error = !ok)
        }
    }

    /** 「回顾」入口：手动要的，不受自动回顾的闸管；结果落在对话末尾的一行里，这里只在没成时说一句 */
    fun requestRecap() {
        val session = shown.value ?: return
        scope.launch { reportRecap(session, auto = false) }
    }

    private suspend fun reportRecap(session: AgentSession, auto: Boolean) {
        if (!session.capabilities.recap || session.state.value.busy) return
        when (val result = catchingNonCancellation { session.recap(auto) }.getOrElse { RecapOutcome.Failed(it.message.orEmpty()) }) {
            is RecapOutcome.Generated -> if (!auto) notice(context.getString(R.string.recap_done), error = false)
            // 自动回顾被闸拦下是常态（对话太短、没有新回合），不打扰；手动要的才说原因
            is RecapOutcome.Skipped -> if (!auto && result.reason.isNotBlank()) notice(context.getString(R.string.recap_skipped, result.reason), error = false)
            is RecapOutcome.Failed -> if (!auto) notice(context.getString(R.string.recap_failed, result.reason), error = true)
        }
    }

    init {
        // 离开超过 AWAY_RECAP_AFTER_MS 回到前台：给正看着的会话自动写一次回顾（内核再按自己的闸决定做不做）
        scope.launch {
            foreground.returns.collect { awayMs ->
                val session = shown.value ?: return@collect
                if (awayMs >= AWAY_RECAP_AFTER_MS) reportRecap(session, auto = true)
            }
        }
    }

    // ---- 自动模式的审查模型 ----

    /** 设置里选审查模型：[modelId] 为 null = 回到默认。内核会核对它解析得出来；不行就把原因告诉用户（模型没换） */
    fun setAutoReviewer(modelId: String?, profileId: String?) {
        val session = shown.value ?: return
        scope.launch {
            val result = session.submit(SessionCommand.SetAutoReviewer(java.util.UUID.randomUUID().toString(), modelId, profileId))
            if (result is CommandResult.Rejected) notice(context.getString(R.string.auto_review_failed, result.message), error = true)
        }
    }

    fun setAdvisor(modelId: String?, profileId: String?) {
        val session = shown.value ?: return
        scope.launch {
            val result = session.submit(SessionCommand.SetAdvisor(java.util.UUID.randomUUID().toString(), modelId, profileId))
            if (result is CommandResult.Rejected) notice(context.getString(R.string.advisor_failed, result.message), error = true)
        }
    }

    // ---- 定时任务 ----

    private val scheduleControl: StateFlow<ScheduleControl?> = shown.map { it?.schedule }
        .stateIn(scope, SharingStarted.Eagerly, shown.value?.schedule)

    /** 清单上的「删除」（界面已经确认过）。已经结束的删不了，说一句 */
    fun deleteScheduled(taskId: String) {
        val control = scheduleControl.value ?: return
        scope.launch {
            val deleted = catchingNonCancellation { control.delete(taskId) }.getOrDefault(false)
            notice(context.getString(if (deleted) R.string.schedule_deleted else R.string.schedule_delete_failed, taskId), error = !deleted)
        }
    }

    // ---- 团队 ----

    private val teamControl: StateFlow<TeamControl?> = shown.map { it?.team }
        .stateIn(scope, SharingStarted.Eagerly, shown.value?.team)

    /** 团队页的「放行」：[member] 为 null 放行全部搁浅的。结果说一句（放行了几位 / 没有要放行的） */
    fun releaseStalled(member: String?) {
        val control = teamControl.value ?: return
        scope.launch {
            val released = catchingNonCancellation { control.releaseStalled(member) }.getOrDefault(0)
            notice(context.getString(if (released > 0) R.string.team_released else R.string.team_release_none, released), error = false)
        }
    }

    /** 团队页的「解散」（界面已经确认过）。成功说一句，没成功把原因告诉用户 */
    fun disbandTeam() {
        val control = teamControl.value ?: return
        scope.launch {
            val problem = catchingNonCancellation { control.disband() }.getOrElse { it.message ?: it.javaClass.simpleName }
            if (problem == null) notice(context.getString(R.string.team_disbanded), error = false)
            else notice(context.getString(R.string.team_disband_failed, problem), error = true)
        }
    }

    // ---- 检查点与回退 ----

    private val rewindControl: StateFlow<RewindControl?> = shown.map { it?.rewind }
        .stateIn(scope, SharingStarted.Eagerly, shown.value?.rewind)

    private val _rewindDialog = MutableStateFlow<RewindDialog?>(null)

    /** 回退对话框：加载预览 / 预览确认 / 撤销确认；null = 没有 */
    val rewindDialog: StateFlow<RewindDialog?> = _rewindDialog.asStateFlow()

    /** 长按「回到这里」：先问内核预览（要恢复 / 删除哪些、撤不回的、会停掉谁），再摆确认框 */
    fun requestRewind(messageId: String) {
        val control = rewindControl.value ?: return
        if (_rewindDialog.value != null) return
        _rewindDialog.value = RewindDialog.Loading(messageId)
        scope.launch {
            val view = catchingNonCancellation { control.preview(messageId) }.getOrNull()
            if (view == null) {
                _rewindDialog.value = null
                notice(context.getString(R.string.rewind_not_available), error = true)
            } else {
                _rewindDialog.value = RewindDialog.Preview(view)
            }
        }
    }

    fun confirmRewind(rewindScope: RewindScopeView) {
        val control = rewindControl.value ?: return
        val dialog = _rewindDialog.value as? RewindDialog.Preview ?: return
        if (dialog.working) return
        _rewindDialog.value = dialog.copy(working = true)
        scope.launch {
            val result = catchingNonCancellation { control.rewind(dialog.view.messageId, rewindScope) }
                .getOrElse { RewindResultView.Refused(it.message.orEmpty()) }
            _rewindDialog.value = null
            reportRewind(result)
        }
    }

    fun requestUndo(restoreId: String) {
        if (rewindControl.value == null || _rewindDialog.value != null) return
        _rewindDialog.value = RewindDialog.Undo(restoreId)
    }

    fun confirmUndo() {
        val control = rewindControl.value ?: return
        val dialog = _rewindDialog.value as? RewindDialog.Undo ?: return
        if (dialog.working) return
        _rewindDialog.value = dialog.copy(working = true)
        scope.launch {
            val result = catchingNonCancellation { control.undoRestore(dialog.restoreId) }
                .getOrElse { RewindResultView.Refused(it.message.orEmpty()) }
            _rewindDialog.value = null
            reportRewind(result)
        }
    }

    fun dismissRewind() {
        val dialog = _rewindDialog.value
        // 正在执行的不能取消：恢复一旦开始就要做完（停在半路会留下改了一半的工作区）
        if (dialog is RewindDialog.Preview && dialog.working || dialog is RewindDialog.Undo && dialog.working) return
        _rewindDialog.value = null
    }

    private fun reportRewind(result: RewindResultView) = when (result) {
        is RewindResultView.Refused -> notice(context.getString(R.string.rewind_failed, result.reason), error = true)
        is RewindResultView.Done -> {
            val parts = listOfNotNull(
                result.restore?.let { context.getString(R.string.rewind_done_code, it.restored, it.deleted, it.skipped) },
                result.rewoundMessages?.let { context.getString(R.string.rewind_done_conversation, it) },
            )
            notice(parts.joinToString("；").ifEmpty { context.getString(R.string.rewind_done_nothing) }, error = false)
        }
    }

    /** 会话流里长按 / 撤销钮的出口；页面按 [dev.min.code.core.engine.EngineCapabilities.rewind] 决定提不提供 */
    val rewindActions = RewindActions(::requestRewind, ::requestUndo)

    // 设置页的快照仓库占用与清理

    private val _storage = MutableStateFlow<List<RewindStorageRow>>(emptyList())
    val storage: StateFlow<List<RewindStorageRow>> = _storage.asStateFlow()

    /** 打开设置里的检查点段时读一次（遍历仓库目录，不是每帧都做的事） */
    fun refreshStorage() {
        val control = rewindControl.value ?: return
        scope.launch { catchingNonCancellation { control.storage() }.onSuccess { _storage.value = it } }
    }

    fun clearStorage(id: String) {
        val control = rewindControl.value ?: return
        scope.launch {
            control.clearStorage(id)
                .onSuccess { notice(context.getString(R.string.checkpoints_cleared), error = false) }
                .onFailure { notice(context.getString(R.string.checkpoints_clear_failed, it.message.orEmpty()), error = true) }
            refreshStorage()
        }
    }

    private companion object {
        /** 面板与批准卡的刷新间隔：服务器状态变化（连上、掉线）一两秒内要看到，再密没有意义 */
        const val POLL_MS = 1_500L
    }
}

/** 信任卡：[then] 非空 = 答完要接着新建会话 */
internal class TrustPrompt(val probe: TrustProbe, val then: (() -> Unit)?)

/** 当前工作目录的信任状态：[decision] true 信任（含继承）/ false 明确不信任 / null 没问过 */
internal data class TrustView(
    val cwd: String,
    val decision: Boolean?,
    val ownTrust: Boolean,
    val inheritedFrom: String?,
    /** 这个目录的项目配置会放宽的权限；还没探出来 / 没有为 null */
    val grants: TrustProbe? = null,
)

/** 重开大会话时的「从摘要续接」提问。[working] = 正在压缩 */
internal data class ResumePrompt(val session: AgentSession, val advice: ResumeAdviceView, val working: Boolean = false)
