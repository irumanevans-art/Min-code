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
    override val tasks = MinBackgroundTasks()
    override val lookup: MinModelLookup = resolver
    override val maxRetryAttempts = 5

    @Volatile private var generation: MinHostGeneration = buildGeneration()
    private val ports = MinHostPorts({ generation }, tasks, dev.min.agent.spi.Clock { System.currentTimeMillis() })

    internal val snapshots = CasWorkspaceSnapshots(File(context.filesDir, "agent").toPath(), { generation.fs }, MinKernelDeps.MEMORY_HOME)
    private val kernelDeps = MinKernelDeps.build(resolver, ports, snapshots = snapshots)

    /** 内核看到的长寿进程端口（已套配额）。以后 McpManager 从这里拿；debug 探针用它验证 proot 里的进程往返与回收 */
    internal val processes: dev.min.agent.spi.ProcessPort get() = kernelDeps.host.processes

    override val hub = SessionHub(
        store = store,
        deps = kernelDeps,
        scope = scope,
        limits = HubLimits(maxDepth = 3, maxRunningChildren = 10, maxConcurrentChildRequests = 3),
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

    init {
        scope.launch { tasks.watch() }
        // 设备存储开关等变了：空闲时换一代挂载（一个回合从头到尾用同一代）
        scope.launch {
            combine(settings.settings, hub.running) { _, running -> running }.collect { if (it.isEmpty()) refreshGeneration() }
        }
        scope.launch { catchingNonCancellation { recoverInterrupted() }.onFailure { Log.w(TAG, "startup recovery failed", it) } }
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

    // ---- AgentEngine ----

    override suspend fun create(options: StartOptions): Result<AgentSession> = guarded {
        val current = settings.current()
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
        )
        lastUsed.remember(profile.id, modelId)
        register(runtime, pin = true).also { index.update(runtime.state.value) }
    }

    override suspend fun open(id: String): Result<AgentSession> = guarded {
        settings.current()
        val sid = SessionId(id)
        openLock.withLock {
            sessions.value[sid]?.let { touch(sid, pin = true); return@withLock it }
            val runtime = checkNotNull(hub.open(sid)) { text.sessionNotFound() }
            register(runtime, pin = true)
        }
    }

    override suspend fun closeAll(reason: CloseReason) {
        sessions.value.values.toList().forEach { closeSession(it, reason) }
    }

    private suspend fun guarded(block: suspend () -> MinAgentSession): Result<AgentSession> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun register(runtime: dev.min.agent.kernel.SessionRuntime, pin: Boolean): MinAgentSession {
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

    /** 超过 [MAX_RESIDENT] 棵树时，卸下最久没碰的、没被钉住的空闲会话 */
    private suspend fun evictIfNeeded() {
        val over = sessions.value.size - MAX_RESIDENT
        if (over <= 0) return
        val candidates = synchronized(this) {
            sessions.value.filterKeys { it !in pinned }.keys.sortedBy { touched[it] ?: 0L }
        }
        for (id in candidates.take(over)) sessions.value[id]?.let { closeSession(it, CloseReason.Evicted) }
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

    override fun renameSession(sessionId: String, title: String) {
        meta.update(SessionRef(EngineKind.MinAgent, sessionId).storageKey) { it.copy(title = title.ifBlank { null }) }
    }

    override fun rememberDefaultModel(profileId: String, modelId: String) = lastUsed.rememberModel(profileId, modelId)

    override suspend fun killTask(taskId: String) {
        val tracked = tasks.find(taskId) ?: return
        ports.exec.kill(tracked.handle)
    }

    override suspend fun readTaskOutput(taskId: String, maxChars: Int): String? = withContext(Dispatchers.IO) {
        val logRef = tasks.find(taskId)?.handle?.logPath ?: return@withContext null
        catchingNonCancellation { clippedLog(generation.fs.hostPath(logRef).toFile(), maxChars).first }.getOrNull()
    }

    override suspend fun searchFiles(cwd: String, query: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        catchingNonCancellation { findMentionFiles(generation.fs.hostPath(GuestPath(cwd)).toFile(), cwd, query, limit) }.getOrDefault(emptyList())
    }

    override fun saveWithdrawnDraft(ref: SessionRef, input: UserInput) {
        val images = input.images.mapIndexed { i, img -> DraftImage("image-${i + 1}", img.mediaType, img.base64) }
        drafts.save(ref.storageKey, ComposerDraft(text = input.text, images = images))
    }

    override suspend fun updateIndex(state: SessionState) = index.update(state)

    private companion object {
        const val TAG = "MinAgentEngine"
        const val MAX_RESIDENT = 8
    }
}
