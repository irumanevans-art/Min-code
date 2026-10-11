package dev.min.code.core.engine.min

import android.content.Context
import android.util.Log
import dev.min.agent.host.UrlConnectionTransport
import dev.min.agent.host.CasWorkspaceSnapshots
import dev.min.agent.spi.catchingNonCancellation
import dev.min.agent.kernel.HubLimits
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionState
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.SessionEvent
import dev.min.agent.store.FileSessionStore
import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.ui.viewer.ViewerInbox
import dev.min.code.core.claudecode.ComposerDraft
import dev.min.code.core.claudecode.ComposerDraftStore
import dev.min.code.core.claudecode.DraftImage
import dev.min.code.core.engine.AgentEngine
import dev.min.code.core.engine.AgentSession
import dev.min.code.core.engine.CloseReason
import dev.min.code.core.engine.EngineAvailability
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.LiveSessionSummary
import dev.min.code.core.engine.SessionCatalog
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SessionSignal
import dev.min.code.core.engine.StartOptions
import dev.min.code.core.engine.UserInput
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.rootfs.currentBindMounts
import dev.min.code.core.rootfs.findMentionFiles
import dev.min.code.core.engine.StartChoices
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.WorkspaceShellContext
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File

/**
 * Min Agent 引擎的组合根（设计 5.1，取代 M2-A 的 `MinAgentHost`）：一个进程一个 [SessionHub]，
 * 持有整套依赖（文件系统、exec、按档解析模型、工具表），再给每个在线的根会话造一个 [MinAgentSession]。
 *
 * ## 几个选择
 *
 * - **Hub 的协程不在 `AppScope` 上**：`AppScope` 是 `Dispatchers.Main`，M2-A 直接拿它当内核的 scope，
 *   于是每次折叠与 JSON 编码都在界面线程上。这里另起 `Dispatchers.Default` 的子作用域（仍挂在 AppScope 的 Job 下，随应用结束）。
 * - **模型按会话里的 `ModelRef.providerId` 解析**（[MinModelResolver]），不看当前是不是订阅模式（设计 10.2 拍板 3）。
 * - **设备存储开关空闲时生效**（拍板 5）：挂载快照按「代」换（[MinHostGeneration]），
 *   [hub.running] 为空时才换，所以一个回合从头到尾用同一代；换代不要求重开会话。
 * - **驻留上限**：[MAX_RESIDENT] 棵树。只回收没被 [create] / [open] 交出去后又 [closeSession] 的会话之外的、空闲的那些
 *   （界面上打开着的会话被回收等于把它的运行时抽走）。引擎不知道注册表里谁在界面上，所以「交出去」就是钉住，直到关闭；
 *   目前只有启动恢复开的会话不被钉住。B5 注册表能报告 attached 之后再细化。
 *
 * @param enabled 实验开关（release 默认关，B7 接 `SettingsStore`）；B3 默认开，供 debug 入口使用
 */
class MinAgentEngine(
    private val context: Context,
    private val settings: SettingsStore,
    private val workspace: WorkspaceRepository,
    private val runner: ProotShellRunner,
    appScope: CoroutineScope,
    private val drafts: ComposerDraftStore,
    private val meta: ClaudeCodeSessionMetaStore,
    override val text: MinAgentText = ResMinAgentText(context),
    private val enabled: StateFlow<Boolean> = MutableStateFlow(true),
    transport: HttpTransport = UrlConnectionTransport(),
    /** 设备操作（读屏、点击……）；null = 没有设备工具（测试）。开关见 [minToolsWithDevice] */
    devices: dev.min.code.core.device.DeviceActions? = null,
    /** 前后台信号：进后台且没有在跑的回合时把没人用的 MCP 连接直接关掉；null = 不管（测试） */
    foreground: dev.min.code.core.service.AppForeground? = null,
) : AgentEngine, MinSessionHost {
    override val kind = EngineKind.MinAgent
    override val capabilities = EngineCapabilities.MinAgent

    private val scope = CoroutineScope(
        SupervisorJob(appScope.coroutineContext[Job]) + Dispatchers.Default + CoroutineName("MinAgentEngine") +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "engine scope exception", e) },
    )

    private val sessionsDir = File(context.filesDir, "agent/sessions")
    private val store = FileSessionStore(sessionsDir.toPath()) { System.currentTimeMillis() }
    private val catalogData = Catalog.bundled()
    private val resolver = MinModelResolver({ settings.snapshot }, transport, text, catalogData)
    private val lastUsed = MinLastUsed(File(context.filesDir, "agent/last-used.json"))
    private val index = MinSessionIndex(File(context.filesDir, "agent/index"), sessionsDir)
    // 诊断包草稿落在私有目录，重开 App 还在（见 FeedbackDraftBox）；绑定读的是几个小 JSON 文件
    init { ViewerInbox.bindDrafts(File(context.filesDir, "agent/feedback-drafts")) }
    override val tasks = MinBackgroundTasks()
    override val workspaceFs: dev.min.agent.spi.WorkspaceFs get() = ports.fs
    override val lookup: MinModelLookup = resolver
    override val maxRetryAttempts = 5

    @Volatile private var generation: MinHostGeneration = buildGeneration()
    override val mcpLoginGate = McpLoginGate()
    override val snapshotRepos: dev.min.agent.host.CasWorkspaceSnapshots? get() = snapshots
    private val ports = MinHostPorts({ generation }, tasks, dev.min.agent.spi.Clock { System.currentTimeMillis() })

    internal val snapshots = CasWorkspaceSnapshots(File(context.filesDir, "agent").toPath(), { generation.fs }, MinKernelDeps.MEMORY_HOME)
    /** 工作区信任：账本在 SettingsStore，内核据它决定项目层 hooks 与 `.mcp.json` 放不放行（见 [MinWorkspaceTrust]） */
    private val trust = MinWorkspaceTrust(settings, { generation.fs }, changed = { reloadMcpOfOnlineSessions() })
    override val workspaceTrust: dev.min.code.core.engine.WorkspaceTrustControl = trust

    /** 设置快照还没读出来时按「没配」：宁可这一刻看不到 WebSearch，也不拿半截配置去搜 */
    private fun webSearchConfig() = settings.snapshot?.webSearch ?: dev.min.code.core.settings.WebSearchConfig()

    private val kernelDeps = MinKernelDeps.build(
        resolver, ports, snapshots = snapshots,
        // 与 Claude Code 引擎那边的 DeviceMcpRegistrar 读同一个设置：controlDevice
        tools = minToolsWithDevice(
            devices, enabled = { settings.snapshot?.controlDevice == true },
            // WebSearch 跟设置走：配好后端的下一个请求起模型就看得到它，清掉配置后就没了（不必重建内核）
            webSearchEnabled = { webSearchConfig().configured },
        ),
        // 联网工具与模型请求共用一个注入的 HTTP 传输：测试替换一处就够，真机上都是宿主侧的 UrlConnection（不经 proot）
        web = dev.min.agent.host.CachedWebPort(dev.min.agent.tools.web.HtmlPageConverter, transport),
        searchBackend = ConfiguredSearchBackend(::webSearchConfig, transport),
        mcpTokenStore = AndroidMcpTokenStore(context), mcpAuthorizer = AndroidMcpAuthorizer(context, mcpLoginGate),
        trustProjectHooks = trust::isTrusted,
        // 界面有 url 卡（McpUrlSheet）：显示完整 URL 与域名，点了才用 Custom Tab 打开
        canAnswerMcpUrlElicitation = true,
        workflowsEnabled = { settings.snapshot?.workflowsEnabled != false },
        workflowApprovals = MinWorkflowApprovals(File(context.filesDir, "agent/workflow-approvals")),
        providerFastModel = resolver::fastModelOf,
        pushSink = AndroidPushSink(context),
        userFileSink = AndroidUserFileSink(context),
        previewSink = AndroidPreviewSink(),
        feedbackSink = AndroidFeedbackSink(),
        pdfPages = AndroidPdfPages(AndroidPdfRasterizer(context.cacheDir)),
    )

    /** 内核看到的长寿进程端口（已套配额）。以后 McpManager 从这里拿；debug 探针用它验证 proot 里的进程往返与回收 */
    internal val processes: dev.min.agent.spi.ProcessPort get() = kernelDeps.host.processes
    internal val execForProbe: dev.min.agent.spi.Exec get() = kernelDeps.host.exec

    override val hub = SessionHub(
        store = store,
        deps = kernelDeps,
        scope = scope,
        limits = HubLimits(
            maxDepth = 3, maxRunningChildren = 10, maxConcurrentChildRequests = 3,
            // 设置项 teamMaxConcurrent：初值取已有的快照，没有就用默认值；之后由 init 里的收集器实时更新
            teamMaxConcurrent = settings.snapshot?.teamMaxConcurrent ?: dev.min.agent.kernel.TeamLimits.DEFAULT_MAX_CONCURRENT,
            // 定时任务花费天花板（默认不设）：同样启动时读一次
            cronMaxBudgetUsd = settings.snapshot?.cronMaxBudgetUsd,
            cronEnabled = settings.snapshot?.cronEnabled ?: true,
        ),
    )

    /** 供应商档变了的信号：模型表、「供应商已删除」跟着重算。带 replay，新订阅者立刻拿到一个 */
    override val settingsTick: Flow<Unit> = settings.settings.map { it.profiles }.distinctUntilChanged().map { }
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    private val sessions = MutableStateFlow<Map<SessionId, MinAgentSession>>(emptyMap())
    private val pinned = HashSet<SessionId>()
    private val touched = HashMap<SessionId, Long>()
    private val openLock = Mutex()

    override val availability: StateFlow<EngineAvailability> = combine(workspace.workspace, settings.settings, enabled) { ws, s, on ->
        when {
            !on -> EngineAvailability.Disabled(text.engineDisabled())
            ws.shellStatus != WorkspaceShellStatus.READY.name -> EngineAvailability.NeedsInstall
            usableAgentProfiles(s).isEmpty() -> EngineAvailability.NeedsProvider
            else -> EngineAvailability.Ready
        }
    }.stateIn(scope, SharingStarted.Eagerly, EngineAvailability.NeedsInstall)

    /** 开始面板的供应商 / 模型 / 权限模式选项：设置一变（连了新档、删了档）就重算 */
    override val startChoices: StateFlow<StartChoices?> = settings.settings.map { s ->
        startChoicesOf(
            s, lastUsed.profileId, lastUsed::modelFor, resolver::modelIds, resolver::defaultModelId,
            minModeChoices(text), modeId(PermissionMode.Default),
        )
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override val live: StateFlow<List<LiveSessionSummary>> = sessions.flatMapLatest { map ->
        if (map.isEmpty()) flowOf(emptyList()) else combine(map.values.map { it.summary }) { it.toList() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val catalog: SessionCatalog = MinSessionCatalog(
        store = store, index = index, sessionsDir = sessionsDir, linuxDir = workspace.linuxDir(),
        deleteTree = hub::delete, release = ::release,
        forgetUi = { ref -> drafts.forget(ref.storageKey); meta.forget(ref.storageKey) },
        isOnline = { id -> SessionId(id) in hub.online.value },
        titleOverride = { id -> meta.get(SessionRef(EngineKind.MinAgent, id).storageKey).title },
    )

    private val recovery = MinStartupRecovery(
        store, index, isOnline = { id -> SessionId(id) in hub.online.value },
        recover = { id ->
            hub.open(id)?.let { index.update(it.state.value) }
            hub.close(id)
        },
    )

    /** 定时任务的定时器：心跳驱动，不保证精确，App 被杀就不跑（见 [HeartbeatScheduler]） */
    internal val cronHeartbeat = HeartbeatScheduler(onError = { Log.w(TAG, "cron heartbeat tick failed", it) })
    private val cronNotifier = CronNoticeNotifier(context)

    init {
        scope.launch { tasks.watch() }
        // 团队并发上限与定时任务花费天花板跟着设置走：构造 Hub 时设置快照可能还没读出来（那时按默认值），读出来之后、以及用户改设置之后都要生效。
        // 值越界（设置里不该有）就保持现状，不能因为一个坏值让引擎起不来
        scope.launch {
            settings.settings.map { Triple(it.teamMaxConcurrent, it.cronMaxBudgetUsd, it.cronEnabled) }.distinctUntilChanged().collect { (team, ceiling, cron) ->
                catchingNonCancellation { hub.updateHostLimits(team, ceiling, cron) }.onFailure { Log.w(TAG, "host limits update refused", it) }
            }
        }
        hub.startScheduling(cronHeartbeat)
        // 定时任务出了事（到期、被花费上限停下、因缺价格跳过、连续超限暂停）：日志里有 Note，这里再发一条系统通知——
        // 无人值守的任务停了，人不在会话页就看不到那条 Note
        scope.launch { hub.cronNotices.collect { notice -> catchingNonCancellation { cronNotifier.notify(notice) }.onFailure { Log.w(TAG, "cron notice notification failed", it) } } }
        // 心跳由引擎自己在进程存活期间打；进程能活多久是前台服务的事
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(HeartbeatScheduler.HEARTBEAT_MS)
                cronHeartbeat.beat()
            }
        }
        // 进后台、手上没有在跑的回合：没人用的 MCP 连接现在就关，不等 2 分钟——进程一旦被系统冻结，linger 的定时器不走，
        // stdio 服务器的子进程就一直挂在 proot 里。回合在后台跑完的那一刻同样触发（running 变空）
        foreground?.let { fg ->
            scope.launch {
                combine(fg.foreground, hub.running) { front, running -> !front && running.isEmpty() }
                    .distinctUntilChanged().filter { it }
                    .collect { catchingNonCancellation { hub.closeUnusedMcp() }.onFailure { Log.w(TAG, "closing unused MCP failed", it) } }
            }
        }
        // 账本在别处改了（设置页、另一个入口）也要让内核看到
        scope.launch { settings.settings.collect { trust.ledger.value = it.workspaceTrust } }
        // 设备存储开关等变了：空闲时换一代挂载（一个回合从头到尾用同一代）
        scope.launch {
            combine(settings.settings, hub.running) { _, running -> running }.collect { if (it.isEmpty()) refreshGeneration() }
        }
        scope.launch { catchingNonCancellation { recoverInterrupted() }.onFailure { Log.w(TAG, "startup recovery failed", it) } }
    }

    /** 信任变了：在线的根会话重读 MCP 配置（连不连项目服务器取决于信任；已连着的共享连接不重连） */
    private suspend fun reloadMcpOfOnlineSessions() {
        for (id in hub.online.value) hub.get(id)?.takeIf { it.state.value.origin == null }?.let { hub.reloadMcp(id) }
    }

    /** 启动扫描：让上次死在回合中途的会话合法收尾（见 [MinStartupRecovery]）。引擎建好时自动跑一次，也可手动触发 */
    suspend fun recoverInterrupted(): List<String> = recovery.run()

    // ---- 宿主端口的一代 ----

    private fun mountKey(): String = currentBindMounts(context, settings).joinToString(";") { "${it.source}->${it.target}" }

    private fun buildGeneration(): MinHostGeneration {
        val mounts = currentBindMounts(context, settings)
        val base = WorkspaceShellContext(
            root = CLAUDE_CODE_WORKSPACE_ID.toString(), command = "", cwd = "",
            filesDir = workspace.filesDir(), linuxDir = workspace.linuxDir(),
            tempDir = File(workspace.workspaceDir(), "tmp"), workingDir = workspace.filesDir(), timeoutMillis = 0,
            bindMounts = mounts, env = emptyMap(), killOnExit = true,
        )
        val fs = agentFileSystem(base)
        return MinHostGeneration(
            fs = fs, search = dev.min.agent.host.JvmSearch(fs), exec = ProotExec(base, AndroidProotProcessHost(runner)),
            mountKey = mounts.joinToString(";") { "${it.source}->${it.target}" },
            processes = ProotProcessPort(base, AndroidProotLiveHost(runner)),
        )
    }

    private fun refreshGeneration() {
        if (mountKey() != generation.mountKey) generation = buildGeneration()
    }

    fun pluginMarketplace(cwd: String = "/workspace"): dev.min.agent.ext.plugins.PluginMarketplace {
        val current = generation
        val home = MinKernelDeps.MEMORY_HOME
        return dev.min.agent.ext.plugins.PluginMarketplace(current.fs,
            dev.min.agent.host.FilePluginSources(current.fs, current.exec, GuestPath(home.value + "/.claude/plugins")), home,
            GuestPath(cwd), { trust.isTrusted(GuestPath(cwd)) }, canonical = current.fs::canonical)
    }
    val pluginNotices = MutableStateFlow<List<String>>(emptyList())
    fun discardPluginPreview(manager: dev.min.agent.ext.plugins.PluginMarketplace, preview: dev.min.agent.ext.plugins.PluginInstallPreview) {
        scope.launch { try { manager.discard(preview) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { } }
    }
    private var pluginUpdateJob: Job? = null
    private fun schedulePluginUpdates(cwd: String) {
        if (pluginUpdateJob?.isActive == true || !dev.min.agent.ext.plugins.pluginAutoUpdatesEnabled(System.getenv())) return
        pluginUpdateJob = scope.launch {
            kotlinx.coroutines.delay(kotlin.random.Random.nextLong(1, 600_001))
            try {
                val market = pluginMarketplace(cwd)
                val pending = market.reconcileSettings()
                pluginNotices.value = pending.map { "第三方市场 $it 等待手动添加与确认" } + market.autoUpdate()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { pluginNotices.value = listOf("插件自动更新失败，请从设置页手动重试。") }
        }
    }

    // ---- AgentEngine ----

    override suspend fun create(options: StartOptions): Result<AgentSession> = guarded {
        check(enabled.value) { text.engineDisabled() }
        val current = settings.current()
        // 内核马上同步问信任：别让它读到收集器还没跟上的旧账本
        trust.ledger.value = current.workspaceTrust
        val profile = checkNotNull(defaultAgentProfile(current, options.profileId, lastUsed.profileId)) { text.noUsableProvider() }
        val modelId = options.modelId?.takeIf { it.isNotBlank() }
            ?: lastUsed.modelFor(profile.id) ?: checkNotNull(resolver.defaultModelId(profile)) { text.needModelId() }
        val selection = selectProvider(profile, modelId, catalogData)
        // 说明先写进同一份日志，打开时用同一 fold 展示，恢复无需重新猜测能力
        val initial: List<SessionEvent> = listOfNotNull(selection.notice?.let { Note(NoteLevel.Info, it) })
        val runtime = hub.create(
            cwd = GuestPath(options.cwd.ifBlank { "/workspace" }),
            model = ModelRef(profile.id, modelId),
            mode = options.modeId?.let(::modeOfId) ?: PermissionMode.Default,
            effort = options.effortId?.let(::effortOfId),
            initial = initial,
            loadingModes = options.loadingModes ?: dev.min.agent.spi.SessionLoadingModes(),
        )
        lastUsed.remember(profile.id, modelId)
        schedulePluginUpdates(runtime.state.value.created.cwd.value)
        register(runtime, pin = true).also { index.update(runtime.state.value) }
    }

    override suspend fun open(id: String): Result<AgentSession> = guarded {
        // 关着时旧通知、旧深链也不能把会话重新拉起来
        check(enabled.value) { text.engineDisabled() }
        trust.ledger.value = settings.current().workspaceTrust
        val sid = SessionId(id)
        openLock.withLock {
            sessions.value[sid]?.let { touch(sid, pin = true); return@withLock it }
            val runtime = checkNotNull(hub.open(sid)) { text.sessionNotFound() }
            register(runtime, pin = true)
        }
    }

    override suspend fun renameSession(id: String, title: String): Boolean {
        val sid = SessionId(id)
        hub.get(sid)?.let { return it.rename(title) != null }
        // 没在线的会话：临时打开写一条 session_renamed 再卸下。摘要不用手动更新——它按日志的大小与修改时间校验，下次列表时重建
        val runtime = hub.open(sid) ?: return false
        return try {
            runtime.rename(title) != null
        } finally {
            hub.close(sid)
        }
    }

    override suspend fun closeAll(reason: CloseReason) {
        sessions.value.values.toList().forEach { closeSession(it, reason) }
        // 「停止全部」之后前台服务可能随即退出：MCP 服务器不能等「无人使用两分钟」，否则进程被杀时成了 proot 里的孤儿
        hub.closeMcpConnections()
    }

    private suspend fun guarded(block: suspend () -> MinAgentSession): Result<AgentSession> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * 迁移：以前改名存在 App 自己的元数据里（`session-meta`），现在改名写内核的 `session_renamed`。
     * 打开 / 新建会话时把旧标题写进内核并清掉元数据；内核里已经有名字的以内核为准，旧标题直接丢（它一定更早）。
     * 没打开过的会话列表仍读元数据（[titleOverride]），打开那一刻才迁
     */
    private suspend fun migrateLegacyTitle(runtime: dev.min.agent.kernel.SessionRuntime) {
        // 旧标题可能存在两种键下：引擎自己写的用带前缀的 storageKey，列表改名写的是裸 id（两边口径本来就不一致，
        // 所以列表里离线会话的改名一直没显示出来）。两个都认，写进内核后两个都清
        val id = runtime.sessionId.value
        val keys = listOf(SessionRef(EngineKind.MinAgent, id).storageKey, id)
        val legacy = keys.firstNotNullOfOrNull { meta.get(it).title?.takeIf(String::isNotBlank) } ?: return
        if (runtime.state.value.name == null) runtime.rename(legacy)
        keys.forEach { key -> if (meta.get(key).title != null) meta.update(key) { it.copy(title = null) } }
    }

    private fun register(runtime: dev.min.agent.kernel.SessionRuntime, pin: Boolean): MinAgentSession {
        scope.launch { catchingNonCancellation { migrateLegacyTitle(runtime) }.onFailure { Log.w(TAG, "legacy title migration failed", it) } }
        val session = synchronized(this) {
            sessions.value[runtime.sessionId] ?: MinAgentSession(runtime, this, scope).also {
                sessions.value = sessions.value + (runtime.sessionId to it)
            }
        }
        touch(runtime.sessionId, pin)
        scope.launch { evictIfNeeded() }
        return session
    }

    private fun touch(id: SessionId, pin: Boolean) = synchronized(this) {
        touched[id] = System.nanoTime()
        if (pin) pinned += id
    }

    /** 超过 [MAX_RESIDENT] 棵树时，卸下最久没碰的、没被钉住、也没有进行中定时任务的空闲会话（理由见 [evictionCandidates]） */
    private suspend fun evictIfNeeded() {
        val over = sessions.value.size - MAX_RESIDENT
        if (over <= 0) return
        val candidates = synchronized(this) {
            evictionCandidates(
                sessions.value.keys, pinned, touched,
                hasActiveSchedule = { id -> hub.get(id)?.state?.value?.crons?.any { it.active } == true },
                over = over,
            )
        }
        for (id in candidates) sessions.value[id]?.let { closeSession(it, CloseReason.Evicted) }
    }

    // ---- 关闭 / 删除 ----

    override suspend fun closeSession(session: MinAgentSession, reason: CloseReason) {
        val id = session.rootId
        when (reason) {
            // 被回收：会话本身还在，有回合在跑 / 有人在等就不卸（hub.close 在有回合时拒绝）
            CloseReason.Evicted -> if (hub.close(id)) drop(id)
            // 删除时随后目录会清：只放掉订阅，树的中断与卸下归 hub.delete
            CloseReason.Deleted -> drop(id)
            CloseReason.UserEnded, CloseReason.StopAll -> {
                session.setStopping(true)
                // 整棵树停下再卸下（后台子 agent 也停）。子 agent 被打断交来的「未做完」报告只进父会话的收件箱、
                // 不唤醒它开新回合（hub.shutdown 的约定），下次打开时随下一句话一起交给模型
                // hub.shutdown 内部已重试几轮；仍卸不下（树里还有回合在跑）返回 false：行为照旧放掉订阅，但留一条警告，别静默
                if (!hub.shutdown(id)) Log.w(TAG, "closeSession($reason): 会话树 ${id.value} 没能卸下，仍有回合在跑；订阅已放掉，运行时留在 hub 里")
                drop(id)
            }
        }
    }

    private fun drop(id: SessionId) {
        val session = synchronized(this) {
            pinned -= id
            touched -= id
            sessions.value[id].also { sessions.value = sessions.value - id }
        }
        session?.release()
        scope.launch(Dispatchers.IO) {
            catchingNonCancellation { snapshots.gc(cleanupPeriodDays = 30) }
                .onFailure { Log.w(TAG, "检查点仓库清理失败，下次关闭会话时重试。") }
        }
    }

    private suspend fun release(id: SessionId) = drop(id)

    // ---- MinSessionHost：会话要从引擎拿的东西 ----

    override fun titleOverride(sessionId: String): Flow<String?> {
        val key = SessionRef(EngineKind.MinAgent, sessionId).storageKey
        return meta.items.map { it[key]?.title?.takeIf(String::isNotBlank) }.distinctUntilChanged()
    }

    override fun rememberDefaultModel(profileId: String, modelId: String) = lastUsed.rememberModel(profileId, modelId)

    override suspend fun killTask(taskId: String) {
        val tracked = tasks.find(taskId) ?: return
        ports.exec.kill(tracked.handle)
    }

    override suspend fun readTaskOutput(taskId: String, maxChars: Int): String? = withContext(Dispatchers.IO) {
        // watch 的输出文件在内核的任务表里（命令源是宿主日志，WebSocket 源是内核自己写的事件文件）
        val logRef = tasks.find(taskId)?.handle?.logPath ?: hub.backgroundJobInfo(taskId)?.outputPath ?: return@withContext null
        catchingNonCancellation { clippedLog(generation.fs.hostPath(logRef).toFile(), maxChars).first }.getOrNull()
    }

    override suspend fun persistAllowRule(layer: RuleLayer, rule: String, cwd: String): PersistRule =
        writeAllowRule(generation.fs, layer, rule, GuestPath(cwd), MinKernelDeps.MEMORY_HOME)

    override suspend fun searchFiles(cwd: String, query: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        catchingNonCancellation { findMentionFiles(generation.fs.hostPath(GuestPath(cwd)).toFile(), cwd, query, limit) }.getOrDefault(emptyList())
    }

    override fun saveWithdrawnDraft(ref: SessionRef, input: UserInput) {
        val images = input.images.mapIndexed { i, img -> DraftImage("image-${i + 1}", img.mediaType, img.base64) }
        drafts.save(ref.storageKey, ComposerDraft(text = input.text, images = images))
    }

    override suspend fun updateIndex(state: SessionState) = index.update(state)

    /** 遥测「立即导出」：把会话日志里的新事件导出到端点与本机 JSONL（默认关，关着什么都不做）。见 [MinTelemetryExporter] */
    suspend fun exportTelemetry(settings: dev.min.code.core.telemetry.TelemetrySettings, headers: String) =
        dev.min.code.core.telemetry.MinTelemetryExporter(
            sessionsDir = sessionsDir,
            stateDir = File(context.filesDir, "agent"),
            transport = UrlConnectionTransport(),
        ).export(settings, headers)

    private companion object {
        const val TAG = "MinAgentEngine"
        const val MAX_RESIDENT = 8
    }
}
