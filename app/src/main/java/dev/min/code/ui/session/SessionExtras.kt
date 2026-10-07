package dev.min.code.ui.session

import android.app.Application
import dev.min.agent.spi.catchingNonCancellation
import dev.min.code.R
import dev.min.code.core.engine.AgentSession
import dev.min.code.core.engine.AgentSessionRegistry
import dev.min.code.core.engine.McpControl
import dev.min.code.core.engine.McpLoginCancelled
import dev.min.code.core.engine.McpLoginPrompt
import dev.min.code.core.engine.McpServerView
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SessionSignal
import dev.min.code.core.engine.StartOptions
import dev.min.code.core.engine.TrustProbe
import dev.min.code.core.engine.WorkspaceTrustControl
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

    /** 设置面板「工作区信任」那一段：当前目录的状态；引擎不管信任时为 null */
    val trustView: StateFlow<TrustView?> = combine(shown, settings.settings) { session, app ->
        if (session?.capabilities?.workspaceTrust != true) return@combine null
        val cwd = session.state.value.cwd
        TrustView(
            cwd = cwd,
            decision = WorkspaceTrust.decision(app.workspaceTrust, cwd),
            ownTrust = WorkspaceTrust.isOwn(app.workspaceTrust, cwd),
            inheritedFrom = WorkspaceTrust.inheritedFrom(app.workspaceTrust, cwd),
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

    private companion object {
        /** 面板与批准卡的刷新间隔：服务器状态变化（连上、掉线）一两秒内要看到，再密没有意义 */
        const val POLL_MS = 1_500L
    }
}

/** 信任卡：[then] 非空 = 答完要接着新建会话 */
internal class TrustPrompt(val probe: TrustProbe, val then: (() -> Unit)?)

/** 当前工作目录的信任状态：[decision] true 信任（含继承）/ false 明确不信任 / null 没问过 */
internal data class TrustView(val cwd: String, val decision: Boolean?, val ownTrust: Boolean, val inheritedFrom: String?)
