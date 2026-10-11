package dev.min.agent.kernel

import dev.min.agent.context.AutoMemory
import dev.min.agent.context.MemoryDiscovery
import dev.min.agent.context.MemoryLoader
import dev.min.agent.ext.config.RuleSpecKind
import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.ext.config.SettingsSnapshot
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.NotificationInput
import dev.min.agent.ext.hooks.SessionStartInput
import dev.min.agent.ext.styles.OutputStyleFiles
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HttpPolicyData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.OutputStyleLoaded
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.ext.config.modelPolicy
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.QuestionId
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.PermissionRulesLoaded
import dev.min.agent.spi.RuleEntry
import dev.min.agent.spi.RuleKindData
import dev.min.agent.spi.InputWithdrawn
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.AutoMemoryInfo
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.MemoryLoaded
import dev.min.agent.spi.QuestionAnswer
import dev.min.agent.spi.QuestionAnswered
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SessionStore
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.spi.answerProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull

private val mcpActivitySequence = java.util.concurrent.atomic.AtomicLong()

/**
 * 一个会话的运行时。会话状态只有一条修改路径：[commit]——先落盘，再折叠进 [state]。
 * 回合跑在 [scope] 里的一个子 Job 上；用户的命令（插话、审批、中断）与回合并发到达，都走 [commit]。
 *
 * 等审批、等插话都不是回调：回合盯着 [state]，命令把答复写进日志，状态一变回合就接着走。
 * 所以进程被杀时不会有「挂在内存里的回调」丢失——它们本来就不存在。
 */
class SessionRuntime private constructor(
    private val log: SessionLog,
    initial: SessionState,
    deps: KernelDeps,
    private val scope: CoroutineScope,
    /** 管着它的 Hub；没有时（单会话嵌入、旧测试）工具拿到的子 agent 端口是不可用的 */
    internal val hub: SessionHub? = null,
) {
    // 所有加载点与工具执行都读取本运行时的独立快照，不能改 Hub 共用的进程依赖。
    internal val deps: KernelDeps = deps.forSession(initial.created.loadingModes)
    /** 重开出来的会话才有（[resumeAdvice] 的基线）；新建的为 null */
    @Volatile
    internal var resumeBasis: ResumeBasis? = null

    /**
     * 正在跑的那次定时任务运行的花费记账（[CronEngine] 在触发时立、空闲时清）。模型请求发出前 [ModelBilling] 要看它：
     * 花到上限就不再发。null = 当前没有定时运行在记账
     */
    @Volatile
    internal var cronRun: CronRun? = null

    /** 子会话的模型请求要过 Hub 的并发闸（手机上同时流式的子 agent 有上限）；根会话为 null，不受限 */
    @Volatile
    internal var requestGate: Semaphore? = null

    /**
     * 按需加载记忆的发现器（缓存只在内存里，丢了只是多读几次盘）。没有主目录、或这是个不吃记忆的子 agent
     * （来历里的 [dev.min.agent.spi.SubagentOrigin.omitMemory]：Explore / Plan、omitClaudeMd）时为 null。
     * 由折叠出的来历推出，所以创建与重新打开一致
     */
    private var memoryDiscoveryCwd: GuestPath? = null
    /**
     * 本轮 skill 的 `allowed-tools` 放行（权限规则文字）。文档：「The grant clears when you send your next message」，
     * 所以用户的下一条输入（[send]）清空它。只在内存里：重开会话后不恢复，工具会重新询问——失败关闭，不会比原来放得更宽。
     */
    internal val skillGrants = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** 把 skill 的 `allowed-tools` 记成本轮放行；`${CLAUDE_SKILL_DIR}` 在规则里代入（文档：允许在 allowed-tools 的 Bash 规则里用它） */
    internal fun grantSkillTools(allowed: List<String>, baseDir: String?) {
        for (text in allowed) skillGrants += if (baseDir != null) text.replace("\${CLAUDE_SKILL_DIR}", baseDir) else text
    }

    private var cachedMemoryDiscovery: MemoryDiscovery? = null
    internal val memoryDiscovery: MemoryDiscovery? get() {
        val home = deps.memoryHome
        if (home == null || !deps.memoryEnabled || state.value.origin?.omitMemory == true) return null
        // --bare / --safe-mode：记忆不发现，按需加载（读到匹配文件时注入规则）也一并关掉，
        // 不然启动时跳过的记忆会从这条缝里回来
        if (deps.customizations != Customizations.Full) return null
        val cwd = state.value.settings.cwd
        if (memoryDiscoveryCwd != cwd) {
            memoryDiscoveryCwd = cwd
            cachedMemoryDiscovery = MemoryDiscovery(deps.host.fs, cwd, home, deps.memoryLimits, suppressCwdRules = state.value.worktree?.pinned == true, includeUser = !deps.settingsInputs.restricted)
        }
        return cachedMemoryDiscovery
    }

    internal val worktrees = Worktrees(this)
    internal fun watchNetworkLease(handle: dev.min.agent.spi.ExecHandle, lease: dev.min.agent.spi.NetworkLease, timeoutMs: Long) {
        scope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                    while (deps.host.exec.isAlive(handle) != false) kotlinx.coroutines.delay(1000)
                }
            } finally { lease.close() }
        }
    }
    private val modelSwitchHooks = ModelSwitchHooks(this)
    private val promptExpansion = PromptExpansionHooks(this)
    suspend fun enterWorktree(name: String? = null, path: String? = null): dev.min.agent.spi.ToolResult =
        whileIdle { worktrees.enter(name, path) } ?: dev.min.agent.spi.ToolResult(listOf(dev.min.agent.protocol.TextPart(BUSY)), isError = true)
    suspend fun exitWorktree(remove: Boolean = false): dev.min.agent.spi.ToolResult =
        whileIdle { worktrees.exit(remove) } ?: dev.min.agent.spi.ToolResult(listOf(dev.min.agent.protocol.TextPart(BUSY)), isError = true)
    suspend fun worktreeHasWork(): Boolean? = state.value.worktree?.let {
        dev.min.agent.spi.catchingNonCancellation { worktrees.hasWork(it) }.getOrDefault(true)
    }

    internal suspend fun changeCwd(cwd: GuestPath, worktree: dev.min.agent.spi.WorktreeBinding?) {
        val old = state.value.settings.cwd
        val home = deps.memoryHome
        // --bare / --safe-mode：换目录也不能把被跳过的记忆装回来（权限规则照常重读）
        val memory = if (home != null && deps.memoryEnabled && state.value.origin == null && deps.customizations == Customizations.Full) MemoryLoader.load(deps.host.fs, cwd, home, deps.memoryLimits, includeUser = !deps.settingsInputs.restricted).files else emptyList()
        val snapshot = if (home != null && state.value.origin == null) SettingsLoader.load(deps.host.fs, cwd, home, inputs = deps.settingsInputs) else null
        // 模型限制跟着新目录的设置走（项目层也能写 availableModels）
        snapshot?.let { modelPolicy = it.merged.modelPolicy() }
        val trustProject = deps.trustProjectHooks(cwd)
        val trustedLocal = snapshot?.let { trustedLocalSettings(deps, it, cwd, trustProject || state.value.created.headless) }.orEmpty()
        val rules = snapshot?.merged?.ruleSpecs(trustProject, trustedLocal)?.map { spec -> RuleEntry(
            when (spec.kind) { RuleSpecKind.Allow -> RuleKindData.Allow; RuleSpecKind.Ask -> RuleKindData.Ask; RuleSpecKind.Deny -> RuleKindData.Deny },
            spec.text, spec.source, spec.baseDir,
        ) }
        // 原来的显式附加目录是绝对授权，不随 cwd 重新解释；新项目的设置目录在新 cwd 上解析。
        val dirs = snapshot?.merged?.fileAccessDirectories(cwd, requireNotNull(home), trustProject, trustedLocal)?.let { (state.value.additionalDirectories + it).distinct() }
        commit(listOf(dev.min.agent.spi.CwdChanged(old, cwd, worktree, memory.filter { it.path !in state.value.memoryPathsInContext }, rules, dirs)), Durability.Sync)
        checkpointsBySettings = null
        val hooks = HookDriver(this).run(HookEvent.CwdChanged, null,
            HookJson.encodeToJsonElement(dev.min.agent.ext.hooks.CwdChangedInput(deps.hookShellPath(old), deps.hookShellPath(cwd))).jsonObject)
        val watchPaths = hooks?.runs?.filter { it.exitCode == 0 }?.mapNotNull { run ->
            dev.min.agent.spi.catchingNonCancellation {
                HookJson.decodeFromString(dev.min.agent.ext.hooks.HookOutputCommon.serializer(), run.stdout.trim()).watchPaths
            }.getOrNull()
        }?.lastOrNull()
        if (watchPaths != null) {
            dev.min.agent.spi.catchingNonCancellation {
                val paths = watchPaths.map {
                    require(it.startsWith('/') || Regex("^[A-Za-z]:[/\\\\].*").matches(it)) { "watchPaths 必须是绝对路径。" }
                    Worktrees.resolved(it, cwd)
                }
                commit(listOf(dev.min.agent.spi.CwdWatchPathsChanged(paths)), Durability.Sync)
            }.onFailure { commit(listOf(dev.min.agent.spi.Note(dev.min.agent.spi.NoteLevel.Warning, "CwdChanged watchPaths 无效，保留原监视列表。")), Durability.Sync) }
        }
        hub?.cwdChanged(this)
    }

    private var pluginsInstance: dev.min.agent.ext.plugins.PluginBundle? = null
    internal var lsp: dev.min.agent.spi.LspPort = dev.min.agent.spi.LspPort.Unavailable
    internal var lspServers: List<dev.min.agent.spi.LspServerSpec> = emptyList()
    internal var lspRoot: GuestPath? = null
    private var pluginsAttempted = false
    internal suspend fun plugins(): dev.min.agent.ext.plugins.PluginBundle? {
        pluginsInstance?.let { return it }
        if (pluginsAttempted) return null
        // --bare / --safe-mode：已安装插件不发现（CC :72/:125）；插件带来的 hooks、skills、样式、MCP、monitors 随之全无
        if (deps.customizations != Customizations.Full) return null
        val home = deps.memoryHome ?: return null
        val cwd = state.value.created.cwd
        return try {
        val settings = SettingsLoader.load(deps.host.fs, cwd, home, inputs = deps.settingsInputs)
        dev.min.agent.ext.plugins.PluginBundle.load(deps.host.fs, cwd, home, settings.merged,
            dirs = deps.pluginDirs, canonical = deps.canonicalPath,
            trust = { root -> deps.pluginTrust(root, cwd) },
            secrets = dev.min.agent.ext.plugins.PluginSecretStore(deps.mcpTokenStore),
            installedPath = deps.pluginInstalledPath, shellPath = deps.hookShellPath,
            installIndex = !deps.settingsInputs.restricted,
        ).also { pluginsInstance = it; pluginsAttempted = true }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {
            pluginsAttempted = true
            commit(listOf(Note(NoteLevel.Warning, "插件装载失败，本会话按无插件继续")), Durability.Sync)
            null
        }
    }

    suspend fun readSkillBody(path: GuestPath): String? = plugins()?.readBody(path)
        ?: dev.min.agent.ext.skills.SkillFiles.readBody(deps.host.fs, path)

    private val commitLock = Mutex()
    private val inputPrepareLock = Mutex()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _live = MutableSharedFlow<LiveUpdate>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val live: SharedFlow<LiveUpdate> = _live.asSharedFlow()

    val sessionId: SessionId get() = log.header.sessionId

    private val turnLock = Any()

    /**
     * 已经提交了输入、正要开回合的调用数（[send] / [receive] 的提交与 [startTurnIfIdle] 之间的空档）。
     * 这段空档里收件箱有东西、回合还没起，[isParked] 不能把它当成「停着、没人接」
     */
    private val startsInFlight = java.util.concurrent.atomic.AtomicInteger(0)
    private var turnJob: Job? = null

    /** MCP 工具的调用口（MCP设计 §6）：McpManager 接上之前是不可用的；子会话由 Hub 设成父会话那个 */
    @Volatile
    internal var mcpCaller: McpCaller = McpCaller.Unavailable
    internal val mcpClosed = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Volatile internal var lastMcpActivity: Long = 0

    /** 排着、等下一回合开头生效的 MCP 变化（后连上的服务器、list_changed）。只在内存里：没生效的变化不进日志 */
    private val stagedMcp = java.util.concurrent.atomic.AtomicReference<List<SessionEvent>?>(null)

    /** 会话创建时的首份 MCP 快照：直接写（还没有回合，不必等） */
    internal suspend fun loadMcp(servers: dev.min.agent.spi.McpServersLoaded, tools: dev.min.agent.spi.McpToolsSnapshot) {
        commit(prepareMcpEvents(listOf(servers, tools)), Durability.Sync)
    }

    /** MCP 的变化排到下一回合开头（设计 §5.3）；后排的整份替换先排的 */
    internal fun stageMcp(servers: dev.min.agent.spi.McpServersLoaded?, tools: dev.min.agent.spi.McpToolsSnapshot) {
        stagedMcp.set(listOfNotNull(servers, tools))
    }

    /**
     * 新会话的首份 MCP 快照（McpManager 在后台连服务器）：第一回合开头等它（最多到接上后 5 秒），写进日志再发请求。
     * 只用一次
     */
    @Volatile
    internal var mcpFirstSnapshot: kotlinx.coroutines.Deferred<Pair<dev.min.agent.spi.McpServersLoaded, dev.min.agent.spi.McpToolsSnapshot>>? = null

    internal suspend fun applyStagedMcp() {
        mcpFirstSnapshot?.let { first ->
            // 等的时候被取消（用户在第一回合开头就按了中断）不能丢：保留到下一回合再等，不然这个会话就一直没有 MCP 工具
            val (servers, tools) = first.await()
            mcpFirstSnapshot = null
            // 等的期间排进来的变化是更新的整份快照，直接写它；先写过期的首份再写它，日志里会多一份没用过的快照
            commit(prepareMcpEvents(stagedMcp.getAndSet(null) ?: listOf(servers, tools)), Durability.Sync)
            return
        }
        commit(prepareMcpEvents(stagedMcp.getAndSet(null) ?: return), Durability.Sync)
    }

    private fun prepareMcpEvents(events: List<SessionEvent>): List<SessionEvent> {
        val previous = state.value.mcpToolSearch
        // 旧日志沿用原来的全量工具表；新会话冻结策略，迟到服务器只在快照生效边界重新估算。
        if (state.value.mcpTools != null && previous == null) return events
        val tools = events.filterIsInstance<dev.min.agent.spi.McpToolsSnapshot>().lastOrNull() ?: return events
        val window = deps.models.resolve(state.value.settings.effectiveModel).profile.contextWindow
        val selected = selectMcpSearchMode(tools, window, previous?.strategy ?: deps.env("ENABLE_TOOL_SEARCH"), previous?.denied ?: state.value.deniesToolSearch(deps))
            .let { if (previous?.enabled == true) it.copy(enabled=true) else it }
        return if (selected == previous) events else events + selected
    }

    /** 检查点与回退（检查点与rewind设计） */
    internal val checkpointer = Checkpointer(this)

    /**
     * 后台 hook（`async` / `asyncRewake`）：一个运行时一份，跑着的进程与「只报一次」的记录都在这里——
     * HookDriver 是按需建的（一个运行时会有好几个），状态必须挂在运行时上
     */
    internal val asyncHooks = AsyncHookRunner(this, scope)

    /** 后台 hook 的轮询节奏。默认值即生产值；内核测试调小（真等 30 秒的退回探测间隔没法做确定性用例） */
    internal val asyncHookTimings = AsyncHookTimings()

    /** 后台任务（Bash 转后台的命令、Monitor 的 watch）：一个运行时一张表，会话卸下时一并收掉 */
    internal val background = BackgroundJobs(this, scope)

    /** 后台任务的节奏。默认值即生产值；内核测试调小 */
    internal val backgroundTimings = BackgroundTimings()

    /** 本会话已经启动过的插件 monitor（`插件:名字`）：每个只起一次，不论 `always` 还是首次调用 skill */
    internal val startedPluginMonitors = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** settings 里的 `fileCheckpointingEnabled`（缺省为开）；创建时随那份 settings 一起读，重新打开的会话第一次要用时再读 */
    @Volatile
    private var checkpointsBySettings: Boolean? = null

    /**
     * 本会话工作区（创建时的 cwd）的快照仓库。宿主没给仓库、`CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING` 为真、
     * 或 settings 的 `fileCheckpointingEnabled` 为 false 时为 null（与 CC 同：两个开关有一个关就关）
     */
    internal suspend fun snapshotStore(): dev.min.agent.spi.SnapshotStore? {
        val port = deps.host.snapshots
        if (port === dev.min.agent.spi.WorkspaceSnapshots.Unavailable) return null
        if (isTruthyEnv(deps.env(DISABLE_CHECKPOINTS_ENV))) return null
        val enabled = checkpointsBySettings ?: readCheckpointSetting().also { checkpointsBySettings = it }
        if (!enabled) return null
        // 宿主的仓库出了问题不能拖垮回合或编辑工具：拿不到就当没有仓库
        return dev.min.agent.spi.catchingNonCancellation { port.store(state.value.settings.cwd) }.getOrNull()
    }

    private suspend fun readCheckpointSetting(): Boolean {
        val home = deps.memoryHome ?: return true
        val snapshot = dev.min.agent.spi.catchingNonCancellation { SettingsLoader.load(deps.host.fs, state.value.settings.cwd, home, inputs = deps.settingsInputs) }
            .getOrNull() ?: return true
        return checkpointsEnabledIn(snapshot)
    }

    /** 编辑记录：存一份内容进快照仓库（[dev.min.agent.spi.ToolContext.preserve]）。存不了返回 null，不影响写入 */
    internal suspend fun preserve(content: ByteArray): dev.min.agent.spi.ContentId? {
        val store = snapshotStore() ?: return null
        return dev.min.agent.spi.catchingNonCancellation { store.preserve(content) }.getOrNull()
    }

    /**
     * 追加事件并折叠进状态。整个过程不可取消：落盘成功而状态没更新，内存就和日志对不上了。
     * 折叠失败说明内核写出了不合法的事件序列（bug），异常照常抛出。
     */
    internal suspend fun commit(events: List<SessionEvent>, durability: Durability): List<EventRecord> =
        commitIf(events, durability) { true } ?: emptyList()

    /**
     * 带前置条件的提交：条件检查与写入在同一把锁里，不会出现「检查时成立、写入时已不成立」的空隙
     * （同一条输入并发重发、两端同时答同一个审批）。条件不成立返回 null，什么都不写。
     */
    internal suspend fun commitIf(
        events: List<SessionEvent>,
        durability: Durability,
        precondition: (SessionState) -> Boolean,
    ): List<EventRecord>? {
        if (events.isEmpty()) return emptyList()
        return withContext(NonCancellable) {
            commitLock.withLock {
                // 封存后（已从 Hub 卸下）一条都不写：之后再打开会有新的运行时写同一份日志，两个写者会把日志写坏
                if (sealed || !precondition(_state.value)) return@withLock null
                val records = log.append(events, durability)
                _state.value = records.fold(_state.value) { s, r -> s.apply(r) }
                // 「一次运行」的账本从这里喂（--max-budget-usd）：树里任何会话提交 model_charged 都记到它的根名下。
                // 只数本程提交的事件——重放开来的旧回合不进这本账，预算属于这一次运行
                hub?.budget?.onCommit(_state.value.origin?.root ?: sessionId, events)
                if (events.any { (it is InputReceived && it.origin == InputOrigin.User) || it is dev.min.agent.spi.TurnEnded }) lastMcpActivity = mcpActivitySequence.incrementAndGet()
                records
            }
        }
    }

    /**
     * 会话创建时读一次记忆文件与自动记忆索引，快照写进日志（[MemoryLoaded]）；之后系统提示只从这份快照拼，会话内冻结。
     * 什么都没读到、自动记忆也没开时不写事件。
     */
    private suspend fun loadMemory(settings: SettingsSnapshot?) {
        if (!deps.memoryEnabled) return
        val home = deps.memoryHome ?: return
        // --bare / --safe-mode：CLAUDE.md 与自动记忆都在跳过清单里（CC :72/:125），这次启动一个都不读。
        // 闸门在 deps 上而不是这里看参数：具名子 agent 会自己走这条路径重读，子会话必须跟着父会话的档位
        if (deps.customizations != Customizations.Full) return
        val memory = MemoryLoader.load(deps.host.fs, state.value.created.cwd, home, deps.memoryLimits, includeUser = !deps.settingsInputs.restricted)
        val (autoFiles, autoInfo, autoNotes) = autoMemory(settings, home)
        if (memory.files.isEmpty() && memory.notes.isEmpty() && autoFiles.isEmpty() && autoInfo == null) return
        commit(listOf(MemoryLoaded(memory.files + autoFiles, memory.notes + autoNotes, autoInfo)), Durability.Sync)
    }

    /**
     * 自动记忆（设计 §2）：开关 = settings 各层的 `autoMemoryEnabled`（默认开），`CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`
     * 经 [KernelDeps.env] 强制关；目录 = settings 的 `autoMemoryDirectory`（合并时已只认 user / managed 层），
     * 否则按项目键算的默认位置。开着就返回目录与用量（索引还不存在也是 0/0——提示段要告诉模型能写到哪里）。
     */
    private suspend fun autoMemory(settings: SettingsSnapshot?, home: GuestPath): Triple<List<LoadedMemoryFile>, AutoMemoryInfo?, List<String>> {
        // --restricted（Min 的解释）：自动记忆住在用户目录（~/.claude/projects）下，不开
        if (deps.settingsInputs.restricted) return Triple(emptyList(), null, emptyList())
        val merged = settings?.merged ?: return Triple(emptyList(), null, emptyList())
        val enabled = merged.autoMemoryEnabled ?: true
        if (!enabled || isTruthyEnv(deps.env("CLAUDE_CODE_DISABLE_AUTO_MEMORY"))) return Triple(emptyList(), null, emptyList())
        val override = merged.autoMemoryDirectory
        val directory = when {
            override == null -> AutoMemory.directory(deps.host.fs, state.value.created.cwd, home)
            else -> AutoMemory.directoryOverride(home, override)
        }
        if (override != null && directory == null) {
            // 合并层只认 user / managed，但值仍可能是乱的（相对路径）：按默认位置，给人记一条
            val fallback = AutoMemory.directory(deps.host.fs, state.value.created.cwd, home)
            val index = AutoMemory.loadIndex(deps.host.fs, fallback)
            val note = "settings 的 autoMemoryDirectory=$override 不是绝对路径也不是 ~/ 开头，按默认位置 $fallback 处理"
            return Triple(listOfNotNull(index.file), index.info, listOf(note))
        }
        val index = AutoMemory.loadIndex(deps.host.fs, requireNotNull(directory))
        return Triple(listOfNotNull(index.file), index.info, emptyList())
    }

    /**
     * 会话创建时读一次 settings：权限规则与 hooks 各写一份快照（都在会话内冻结）。
     * 项目层（project / local）的 hooks 只在工作区被信任时才进快照：它们会在手机上执行任意命令（hooks设计 §1）。
     */
    internal val workflowSettingsDisabled: Boolean get() = state.value.workflowSettingsDisabled

    private suspend fun loadSettings(snapshot: SettingsSnapshot) {
        refreshNetworkSettings()
        try {
            val trustProject = deps.trustProjectHooks(state.value.created.cwd)
            val locals = trustedLocalSettings(deps, snapshot, state.value.created.cwd, trustProject || state.value.created.headless)
            val advisor = dev.min.agent.ext.config.advisorSettingsOf(snapshot.merged, state.value.settings.model.providerId, trustProject || state.value.created.headless, locals)
            if (advisor.model != null) changeAdvisor(advisor.model)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { commit(listOf(Note(NoteLevel.Warning, "advisorModel 未启用：${e.message}")), Durability.Sync) }
        if ((snapshot.merged.raw("disableWorkflows") as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true || snapshot.merged.env["CLAUDE_CODE_DISABLE_WORKFLOWS"] == "1")
            commit(listOf(dev.min.agent.spi.WorkflowSettingsDisabled()), Durability.Sync)
        val review = dev.min.agent.ext.config.autoReviewSettingsOf(snapshot.merged)
        val managed = snapshot.merged.layers.lastOrNull { it.source == dev.min.agent.ext.config.ConfigSource.Managed }
        val configured = review.copy(reviewer = deps.autoModeReviewer ?: review.reviewer,
            disableAutoMode = managed?.permissions?.disableAutoMode == true,
            // --restricted（CC :123）拒绝 bypassPermissions：与托管禁用同一条锁（创建、切换、子会话都经它）
            disableBypassPermissionsMode = managed?.permissions?.disableBypassPermissionsMode == true || deps.settingsInputs.restricted)
        if (review.notes.isNotEmpty()) commit(review.notes.map { Note(NoteLevel.Warning, it) }, Durability.Sync)
        if (configured != dev.min.agent.spi.AutoReviewSettingsLoaded()) commit(listOf(configured), Durability.Sync)
        val mode = state.value.settings.mode
        if (mode == PermissionMode.Auto && configured.disableAutoMode || mode == PermissionMode.BypassPermissions && configured.disableBypassPermissionsMode) {
            commit(listOf(SettingsChanged(mode = PermissionMode.Default), Note(NoteLevel.Warning, "这次启动禁用了 $mode（托管策略，或 --restricted），使用 Default 模式")), Durability.Sync)
        }
        loadPermissionRules(snapshot)
        // 先按信任筛层、再算 hooks 的一切键（含 disableAllHooks 与 http 两道闸）：没被信任的项目层一个键都不能影响用户的门禁
        val baseHooks = snapshot.merged.hookSnapshot(deps.trustProjectHooks(state.value.created.cwd))
        // --bare / --safe-mode 对 hooks 的取舍（CC :72/:125）：bare 一律不发现（原文对托管层也没有例外）；
        // safe 只留托管层的（原文点名「managed settings policy still applies, including policy-configured hooks」），
        // 托管插件声明的 hooks 随插件一起不加载（plugins() 在这两种档位下是 null）
        val hooks = when (deps.customizations) {
            Customizations.Bare -> baseHooks.copy(sources = emptyList(), notes = emptyList())
            Customizations.Safe -> baseHooks.copy(sources = baseHooks.sources.filter { it.source == dev.min.agent.ext.config.ConfigSource.Managed })
            Customizations.Full -> {
                val plugins = plugins()
                val pluginHooks = if (snapshot.merged.layers.any { it.source == dev.min.agent.ext.config.ConfigSource.Managed && it.allowManagedHooksOnly == true } ||
                    snapshot.merged.layers.filter { it.source == dev.min.agent.ext.config.ConfigSource.User || it.source == dev.min.agent.ext.config.ConfigSource.Managed || deps.trustProjectHooks(state.value.created.cwd) }.lastOrNull { it.disableAllHooks != null }?.disableAllHooks == true) emptyList()
                else plugins?.hookSources.orEmpty().map { dev.min.agent.ext.config.HookSource(dev.min.agent.ext.config.ConfigSource.Plugin, it) }
                baseHooks.copy(sources = baseHooks.sources + pluginHooks, notes = baseHooks.notes + plugins?.notes.orEmpty())
            }
        }
        val policy = hooks.httpPolicy.takeIf { it.allowedUrls != null || it.allowedEnvVars != null }
            ?.let { HttpPolicyData(it.allowedUrls, it.allowedEnvVars) }
        // 只有闸、没有任何 hook 也没有说明时没有东西可冻结（闸只管 http hook）
        if (hooks.sources.isNotEmpty() || hooks.notes.isNotEmpty() || hooks.disabledBy != null) {
            // http 的两道闸随快照一起冻结：设置只在会话创建时读得到，之后 hook 执行时再看就晚了
            commit(
                listOf(HooksLoaded(hooks.sources.map { HookSourceData(it.source.label, it.hooks) }, hooks.notes, policy, hooks.disabledBy)),
                Durability.Sync,
            )
        }
    }

    /**
     * SessionStart：startup（新建根会话）与 resume（用户打开已有会话，[SessionHub.open] 判定）两条 source，
     * 加的上下文挂到下一条人发的消息上——resume 也走同一条路（CC：hook 的上下文要赶在第一个请求之前到达模型）
     */
    internal suspend fun runSessionStart(source: String) {
        val driver = HookDriver(this)
        val specific = HookJson.encodeToJsonElement(
            SessionStartInput.serializer(),
            SessionStartInput(source = source, model = state.value.settings.model.modelId),
        ).jsonObject
        val outcome = driver.run(HookEvent.SessionStart, source, specific) ?: return
        val events = outcome.contexts.map { HookContextAdded(HookContextTarget.NextUserMessage, HookEvent.SessionStart.name, it) }
        if (events.isNotEmpty()) commit(events, Durability.Sync)
    }

    /**
     * 会话创建时按 `outputStyle` 设置读一次样式文件，快照写进日志（[OutputStyleLoaded]），
     * 会话内冻结（系统提示只由快照拼；改设置只对新会话生效）。没设、Default、找不到（回 Default 并给 Note）
     * 都不写事件——三种情况下系统提示与没有样式时逐字相同
     */
    private suspend fun loadOutputStyle(snapshot: SettingsSnapshot) {
        // --safe-mode：输出样式在跳过清单里（CC :125）；--bare 的清单里没有它，照常
        if (deps.customizations == Customizations.Safe) return
        val wanted = snapshot.merged.outputStyle ?: return
        val home = deps.memoryHome ?: return
        val disk = dev.min.agent.spi.catchingNonCancellation {
            OutputStyleFiles.load(deps.host.fs, state.value.created.cwd, home, includeUser = !deps.settingsInputs.restricted)
        }.getOrElse {
            // 读目录的意外不该让会话建不起来：按找不到处理，给一条说明
            commit(
                listOf(Note(NoteLevel.Warning, "读 output styles 失败（${it.message?.lineSequence()?.firstOrNull() ?: it::class.simpleName}），本会话按 Default。")),
                Durability.Batched,
            )
            return
        }
        val def = OutputStyleFiles.resolve(disk.styles + plugins()?.styles.orEmpty(), wanted)
        when {
            def == null -> commit(
                listOf(Note(NoteLevel.Warning, "settings 里的 outputStyle \"$wanted\" 找不到（用户 / 项目 / managed 目录与内置样式里都没有），本会话按 Default。名字大小写敏感。")),
                Durability.Batched,
            )
            // Default 不改系统提示：不写事件，系统提示与没设样式时逐字相同
            def.body.isBlank() && !def.keepCodingInstructions -> Unit
            else -> commit(
                listOf(
                    OutputStyleLoaded(
                        name = def.name, body = def.body, keepCodingInstructions = def.keepCodingInstructions,
                        description = def.description, notes = disk.notes,
                    ),
                ),
                Durability.Sync,
            )
        }
    }

    /**
     * 这个会话的模型限制（`availableModels` / `deniedModels` / `availableModelsMatch` / `enforceAvailableModels`，[ModelPolicy]）：
     * 根会话读设置时算（创建、续接、换目录），子会话跟随根会话。只在内存里：和 [KernelDeps.modelAllowed] 一样是运行时判断，
     * 每次启动从设置重算，不进日志。两个宿主都经它管住切模型、子 agent、顾问、审查——Android 的设置在 rootfs 里，宿主自己读不到
     */
    @Volatile
    internal var modelPolicy: dev.min.agent.ext.config.ModelPolicy? = null

    /** 根会话按当前目录重读一次模型限制（续接时用；创建与换目录在读设置的地方顺手算） */
    internal suspend fun refreshModelPolicy() {
        if (state.value.origin != null) return
        val home = deps.memoryHome ?: return
        modelPolicy = dev.min.agent.spi.catchingNonCancellation {
            SettingsLoader.load(deps.host.fs, state.value.settings.cwd, home, inputs = deps.settingsInputs).merged.modelPolicy()
        }.getOrNull()
    }

    /**
     * [ref] 能不能用：宿主的判定（[KernelDeps.modelAllowed]）与这个会话的模型限制都放行才行。
     * 别名先交给宿主的解析器落到具体型号再比（被挡的家族别名，宿主能换到允许版本的就算放行）；解析不了的按原样的 id 比
     */
    internal fun modelPermitted(ref: ModelRef): Boolean {
        if (!deps.modelAllowed(ref)) return false
        val policy = modelPolicy ?: state.value.origin?.root?.let { hub?.get(it)?.modelPolicy } ?: return true
        val concrete = dev.min.agent.spi.catchingNonCancellation { deps.models.resolve(ref).profile.ref.modelId }.getOrNull() ?: ref.modelId
        return policy.allows(concrete)
    }

    private suspend fun loadPermissionRules(snapshot: SettingsSnapshot) {
        if (state.value.origin == null) modelPolicy = snapshot.merged.modelPolicy()
        val trustProject = deps.trustProjectHooks(state.value.created.cwd)
        val trustedLocal = trustedLocalSettings(deps, snapshot, state.value.created.cwd, trustProject || state.value.created.headless)
        val notes = snapshot.notes.toMutableList()
        if (!trustProject && snapshot.merged.layers.any { (it.source == dev.min.agent.ext.config.ConfigSource.Project ||
                it.source == dev.min.agent.ext.config.ConfigSource.Local && it.path !in trustedLocal) &&
                (it.permissions.allow.isNotEmpty() || it.permissions.additionalDirectories.isNotEmpty()) }) {
            // 放宽能力的仓库声明需要用户信任；限制能力的 ask / deny 始终保留。
            notes += "this workspace has not been trusted：项目 permissions.allow / additionalDirectories 暂不生效"
        }
        val rules = snapshot.merged.ruleSpecs(trustProject, trustedLocal).map { spec ->
            RuleEntry(
                kind = when (spec.kind) {
                    RuleSpecKind.Allow -> RuleKindData.Allow
                    RuleSpecKind.Ask -> RuleKindData.Ask
                    RuleSpecKind.Deny -> RuleKindData.Deny
                },
                text = spec.text, source = spec.source, baseDir = spec.baseDir,
            )
        }
        // `--add-dir`（CC）：与 permissions.additionalDirectories 同一口径，授予文件访问
        val directories = (snapshot.merged.fileAccessDirectories(state.value.created.cwd, deps.memoryHome ?: GuestPath("/root"), trustProject, trustedLocal) + deps.settingsInputs.addDirs).distinct()
        if (rules.isEmpty() && notes.isEmpty() && directories.isEmpty()) return
        commit(listOf(PermissionRulesLoaded(rules, notes, directories)), Durability.Sync)
    }

    /**
     * 会话创建时读一次 skills / commands 清单，快照写进日志（[dev.min.agent.spi.SkillsLoaded]），会话内冻结（与记忆同理）。
     * 没有主目录就不读；什么都没读到、也没有说明时不写事件。
     */
    private suspend fun loadSkills() {
        val home = deps.memoryHome ?: return
        // --safe-mode：skills 与自定义命令（含托管层的）都不加载（CC :125）。--bare：只加载 --add-dir 目录里的（CC :72、CLI reference）
        if (deps.customizations == Customizations.Safe) return
        val bare = deps.customizations == Customizations.Bare
        val snapshot = SkillSnapshots.load(deps.host.fs, state.value.created.cwd, home, if (bare) null else plugins(), inputs = deps.settingsInputs, addDirsOnly = bare) ?: return
        commit(listOf(snapshot), Durability.Sync)
    }

    internal fun emitLive(update: LiveUpdate) {
        _live.tryEmit(update)
    }

    // ---- 命令 ----

    /**
     * 收一条用户输入。同一个 [clientMsgId] 重发只收一次（返回 false）。
     * 空闲时直接开回合；回合进行中时，[Delivery.Steer] 在当前工具批结束后送达，[Delivery.Queue] 等下一回合。
     */
    suspend fun send(clientMsgId: ClientMsgId, parts: List<Part>, delivery: Delivery = Delivery.Queue, scanMcpMentions: Boolean = false): Boolean = inputPrepareLock.withLock {
        if (clientMsgId in state.value.seenInputs) return@withLock false
        // 会话已由模型结束（EndConversation）：不再开回合，只留一句说明（日志里有，界面与 CLI 都看得到）
        if (state.value.ended) {
            commit(listOf(Note(NoteLevel.Warning, CONVERSATION_ENDED_NOTE)), Durability.Sync)
            return@withLock false
        }
        // 用户的新输入开始新的一轮：上一轮 skill 的 allowed-tools 放行到此为止（文档）
        skillGrants.clear()
        val prepared = if (scanMcpMentions) attachMcpResources(parts, clientMsgId) else parts
        val workflow = prepareSavedWorkflow(clientMsgId, prepared)
        startsInFlight.incrementAndGet()
        try {
            commitIf(listOfNotNull(workflow, InputReceived(clientMsgId, prepared, delivery)), Durability.Sync) { clientMsgId !in it.seenInputs }
                ?: return@withLock false
            startTurnIfIdle(TurnTrigger.User)
        } finally {
            startsInFlight.decrementAndGet()
        }
        true
    }

    /** 撤回还没送达模型的输入（Min 现有的「按 Esc 退回输入框」）。已送达的撤不回，返回 false */
    suspend fun withdraw(clientMsgId: ClientMsgId): Boolean {
        return commitIf(listOf(InputWithdrawn(clientMsgId)), Durability.Sync) { s ->
            s.inbox.any { it.clientMsgId == clientMsgId }
        } != null
    }

    /** 答复一次审批。那次审批已经不在等了（另一端先答了、回合已结束）返回 false */
    suspend fun answerApproval(approvalId: ApprovalId, choice: ApprovalChoice): Boolean {
        if (approvalId in state.value.networkPending) return answerNetworkApproval(approvalId, choice)
        val project = state.value.settings.cwd
        val workflow = state.value.turn?.calls?.values?.firstOrNull {
            it.tool == "Workflow" && (it.phase as? CallPhase.AwaitingApproval)?.approvalId == approvalId
        }
        val remember = workflow != null && choice is ApprovalChoice.Always
        val applied = commitIf(listOf(ApprovalResolved(approvalId, if (remember) ApprovalChoice.Once else choice, DecidedBy.User)), Durability.Sync) { s ->
            s.turn?.calls?.values?.any { (it.phase as? CallPhase.AwaitingApproval)?.approvalId == approvalId } == true
        } != null
        // 只有明确选择本项目不再问才持久化；普通 Once 不增加可跨运行复用的规则。
        if (applied && remember) deps.workflowApprovals.approve(project,
            dev.min.agent.ext.workflow.WorkflowScript.hash(requireNotNull(workflow).input["source"]!!.jsonPrimitive.content))
        return applied
    }

    /**
     * channel 服务器转回来的审批答复（[McpManager.applyVerdict] 已认过 request_id 与来源服务器）。
     * 比人的答复收得更紧：只有「还在等的那次审批」才算；**放行**还要求它没踩安全底线（[CallPhase.AwaitingApproval.floorReason] 为空）且不是 alwaysAsk 的工具——
     * 这两类是「连 bypass 也要人来批」的，不能借远端一句 yes 放过去；拒绝总是收。
     * 只给「这一次」：放行记 [ApprovalChoice.Once]（不写「总是允许」规则），拒绝的说明标为宿主生成（不冒充成用户的话）
     */
    internal suspend fun answerApprovalFromChannel(approvalId: ApprovalId, allow: Boolean, server: String): Boolean {
        val choice = if (allow) ApprovalChoice.Once
        else ApprovalChoice.Deny("已通过 channel「$server」拒绝。", fromHost = true)
        return commitIf(listOf(ApprovalResolved(approvalId, choice, DecidedBy.Channel(server))), Durability.Sync) { s ->
            s.turn?.calls?.values?.any {
                val phase = it.phase as? CallPhase.AwaitingApproval
                phase != null && phase.approvalId == approvalId &&
                    (!allow || (phase.floorReason == null && deps.tools.none { t -> t.name == it.tool && t.traits.alwaysAsk }))
            } == true
        } != null
    }

    /**
     * 回答一次提问。[answers] 与问题一一对应（[answerProblem] 的规则）；对不上抛 [IllegalArgumentException]——
     * 那是界面的 bug，不是用户的选择，什么都不写。那次提问已经不在等了（另一端先答了、回合已结束）返回 false。
     */
    suspend fun answerQuestion(questionId: QuestionId, answers: List<QuestionAnswer>): Boolean {
        if (questionId in state.value.mcpUrls) {
            require(answers.size == 1)
            return resolveMcpUrl(questionId, if (answers.single().selected == listOf("y")) "accept" else "decline")
        }
        // 问题落盘后不再变，在锁外取来校验即可；「还在等」才必须与写入同在一把锁里判定
        val asked = _state.value.turn?.questions?.get(questionId) ?: _state.value.mcpQuestions[questionId] ?: return false
        answerProblem(asked.questions, answers)?.let { throw IllegalArgumentException("答案不合法：$it") }
        return commitIf(listOf(QuestionAnswered(questionId, answers)), Durability.Sync) { it.isAwaitingAnswer(questionId) } != null
    }

    /** 用户关掉提问、不回答：模型会收到「用户没有回答」，按现有信息继续。不在等了返回 false */
    suspend fun declineQuestion(questionId: QuestionId): Boolean =
        if (questionId in state.value.mcpUrls) resolveMcpUrl(questionId, "decline")
        else commitIf(listOf(QuestionAnswered(questionId, declined = true)), Durability.Sync) { it.isAwaitingAnswer(questionId) } != null

    /**
     * 给会话改名（`/rename`）。名字先规整（[normalizeSessionName]）；规整后为空返回 null——
     * 空名拒绝，什么都不写。改名允许与别的会话重名：人是显式改的，不该被悄悄变成别的名字（变体只在 `--name` 建会话时用）
     */
    suspend fun rename(raw: String): String? {
        val name = normalizeSessionName(raw) ?: return null
        commit(listOf(dev.min.agent.spi.SessionRenamed(name)), Durability.Sync)
        return name
    }

    /**
     * 改设置。立即生效（下一次权限判定、下一次请求），不重启任何东西。
     * 返回 PreModelSwitch hook 拒绝切模型的理由（模型没切；mode / effort 照常生效）；没被拒返回 null——宿主靠它别把“没切成”报成成功
     * 参数为 null 表示不改；要把思考档位清回「不控制」用 [clearEffort]（与 [effort] 同给时以 [effort] 为准）
     */
    suspend fun changeSettings(
        model: ModelRef? = null, mode: PermissionMode? = null, effort: Effort? = null, clearEffort: Boolean = false, cwd: GuestPath? = null,
        /** 切模型请求的来源，进 PreModelSwitch 的 `source`：command（/model、/config）/ picker / sdk */
        modelSwitchSource: String = "command",
    ): String? {
        val locks = state.value.autoReviewSettings
        require(!(mode == PermissionMode.Auto && locks.disableAutoMode || mode == PermissionMode.BypassPermissions && locks.disableBypassPermissionsMode)) { "这次启动禁用了这个权限模式（托管策略，或 --restricted）" }
        if (cwd != null && cwd != state.value.settings.cwd) {
            require(state.value.worktree == null) { "隔离会话请用 EnterWorktree / ExitWorktree 切换目录。" }
            require(deps.host.fs.stat(cwd)?.kind == dev.min.agent.spi.FileKind.Directory) { "工作目录不存在：$cwd" }
            whileIdle { changeCwd(cwd, null) } ?: error(BUSY)
        }
        // 切模型先过 PreModelSwitch：被拒就不改模型（mode / effort 照常生效，它们不归这个 hook 管），理由落一条 Note
        val from = state.value.settings.model
        // availableModels 在 PreModelSwitch 之前挡：名单外的模型不让切（CC：/model 直接报错，不问 hook）
        val listed = if (model != null && model != from && !modelPermitted(model)) "模型 ${model.providerId}:${model.modelId} 不在 availableModels 里，没有切换" else null
        val refusal = listed ?: if (model != null && model != from) modelSwitchHooks.refusal(from, model, modelSwitchSource) else null
        if (refusal != null) modelSwitchHooks.noteRefused(refusal)
        val newModel = model.takeIf { refusal == null }
        val clear = clearEffort && effort == null && state.value.settings.effort != null
        if (newModel == null && mode == null && effort == null && !clear) return refusal
        commit(listOf(SettingsChanged(model = newModel, mode = mode, effort = effort, clearEffort = clear)), Durability.Sync)
        // 模型真的换了才通知；同一个模型再设一次不算切换
        if (newModel != null && newModel != from) modelSwitchHooks.applied(from, newModel, modelSwitchSource)
        return refusal
    }

    /** 宿主设置入口；只修改审查模型，不改变会话模型或稳定提示前缀。 */
    suspend fun changeAutoModeReviewer(reviewer: dev.min.agent.spi.AutoModeReviewer?) {
        if (reviewer != null) resolveReviewer(reviewer)
        commit(listOf(state.value.autoReviewSettings.copy(reviewer = reviewer)), Durability.Sync)
    }

    internal fun resolveReviewer(reviewer: dev.min.agent.spi.AutoModeReviewer): ResolvedModel {
        // 显式设置的审查模型受 availableModels 约束（CC）；默认的小模型由宿主的名单管，见 AutoReviewer
        require(modelPermitted(reviewer.model)) { "审查模型 ${reviewer.model.providerId}:${reviewer.model.modelId} 不在 availableModels 里" }
        return deps.reviewerModels?.invoke(reviewer) ?: deps.models.resolve(reviewer.model)
    }

    internal val modelBilling = ModelBilling(this)

    /** 中断当前回合：回合 Job 取消，收尾（每个悬空调用补结果）在回合自己的 finally 里写 */
    fun interrupt() = interrupt(InterruptedBy.User)

    /**
     * 同上，但回合的结局记 [by]（目前只有定时任务的花费上限用 [InterruptedBy.Budget]）。原因先放下再取消：
     * 回合的 catch 里读它，读不到就是用户
     */
    internal fun interrupt(by: InterruptedBy) {
        val job = synchronized(turnLock) { turnJob }?.takeIf { it.isActive } ?: return
        interruptReason = by
        job.cancel()
    }

    @Volatile
    private var interruptReason: InterruptedBy? = null

    /** 取走中断原因（取一次就清掉）；没有 = 用户中断 */
    internal fun takeInterruptReason(): InterruptedBy = interruptReason.also { interruptReason = null } ?: InterruptedBy.User

    /**
     * 这次定时运行的记账：根会话自己持有，树里的子 agent / 团队成员取根会话的那份（[CronRun] 按整棵树累计）。没有运行在记账为 null
     */
    internal fun treeCronRun(): CronRun? {
        val root = _state.value.origin?.root ?: return cronRun
        return hub?.get(root)?.cronRun
    }

    /** 进程被杀恢复后，用户点「继续」：没有新输入也开一回合，让模型看着恢复补上的结果接着做 */
    fun resume() {
        startTurnIfIdle(TurnTrigger.Resume)
    }

    /**
     * 收一条非用户来源的输入（子 agent 的报告 / 消息、父会话的委派 / 消息）。同一个 [clientMsgId] 只收一次（返回 false）。
     * [startTurn] 非空且会话空闲时，以它为触发开回合；为空时只放进收件箱，等用户或下一回合处理。
     */
    internal suspend fun receive(
        clientMsgId: ClientMsgId,
        parts: List<Part>,
        delivery: Delivery,
        origin: InputOrigin,
        startTurn: TurnTrigger?,
        /**
         * 和这条输入**同一次提交**一起落盘的附带事件，排在它前面（团队邮件的 team_message、定时任务的 cron_fired）：
         * 「记了来历」与「输入进了收件箱」要么都在要么都不在，重发被去重时附带事件也不会多写一份
         */
        extra: List<SessionEvent> = emptyList(),
        /** 提交时再判一次的前置条件（与去重条件同在一把锁里）；默认无。定时任务用它保证「触发时确实空闲」 */
        precondition: (SessionState) -> Boolean = { true },
    ): Boolean {
        // 计数在前置条件判过之后、落盘之前加：定时任务的前置条件本身要问 [isParked]，先加就把自己挡住了；
        // 仍在落盘之前，看得到这条输入的人就看得到计数
        var counted = false
        try {
            commitIf(extra + InputReceived(clientMsgId, parts, delivery, origin), Durability.Sync) { s ->
                (clientMsgId !in s.seenInputs && precondition(s)).also { ok ->
                    if (ok && startTurn != null) {
                        startsInFlight.incrementAndGet()
                        counted = true
                    }
                }
            } ?: return false
            startTurn?.let(::startTurnIfIdle)
        } finally {
            if (counted) startsInFlight.decrementAndGet()
        }
        return true
    }

    /**
     * 此刻没有回合、没有维护、也没有排着的输入——定时任务触发的时机（会话空闲）。
     * 只看 state.turn 不够：回合 Job 已经起了、TurnStarted 还没落盘的空档里 state.turn 仍为 null（见 [whileIdle] 的同款处理）
     */
    internal fun isQuiescent(): Boolean = synchronized(turnLock) {
        !closing && turnJob?.isActive != true && maintenanceJob?.isActive != true &&
            _state.value.turn == null && _state.value.inbox.isEmpty()
    }

    /**
     * 没有回合、没有维护、也没有正要开的回合，收件箱里却有输入：没有任何东西会自己把它们接走。
     * 上一回合被停下（用户 / 预算 / hook）、停树时子 agent 的静音报告、队友叫起来的成员回合的报告、进程重启打断的报告……
     * 都只进收件箱等人决定（[SessionHub.deliver]），而这时上一回合可能是正常结束的，所以不能拿「上一回合怎么结束」来认。
     * 定时任务把它当空闲（[CronEngine]）：这次运行的记账照常收尾、到点照常触发。只认「收件箱为空」的话，
     * 记账永远撤不掉（用户下一句话的花费被算进已结束的运行），之后到点也不再触发。
     * 先看状态、最后看 [startsInFlight]：看到了提交进来的输入，就一定看得到它之前加上的计数
     */
    internal fun isParked(): Boolean = synchronized(turnLock) {
        !closing && turnJob?.isActive != true && maintenanceJob?.isActive != true &&
            _state.value.turn == null && _state.value.inbox.isNotEmpty() && startsInFlight.get() == 0
    }

    /** 此刻没有回合在跑（含启动中的），收件箱有无输入不论——团队调度判断「成员是否空闲」用 */
    internal fun isTurnIdle(): Boolean = synchronized(turnLock) {
        !closing && turnJob?.isActive != true && maintenanceJob?.isActive != true && _state.value.turn == null
    }

    @Volatile
    private var compactInstructions: String? = null

    /**
     * 手动压缩（界面的 `/compact [焦点]`）：空闲时开一个只做压缩的回合，返回 true；已有回合在跑返回 false。
     * 焦点只在内存里交给那个回合，回合开始时随 CompactionStarted 落盘
     */
    fun compact(instructions: String? = null): Boolean {
        synchronized(turnLock) {
            if (turnJob?.isActive == true || _state.value.turn != null) return false
            compactInstructions = instructions?.takeIf { it.isNotBlank() }
        }
        startTurnIfIdle(TurnTrigger.Compact)
        return true
    }

    internal fun takeCompactInstructions(): String? = compactInstructions.also { compactInstructions = null }

    /**
     * 会话回顾（[Recapper]）：用提取模型写一行「刚才在做什么、做到哪、下一步」，落 [dev.min.agent.spi.SessionRecap]，只给人看。
     * [dev.min.agent.spi.RecapTrigger.Away] 是宿主判定「人离开一段时间回来了」后调的，受自动回顾的闸管；
     * 失败或跳过都不影响会话，结果里说明原因
     */
    suspend fun recap(trigger: dev.min.agent.spi.RecapTrigger = dev.min.agent.spi.RecapTrigger.Manual): RecapResult =
        Recapper(this).run(trigger)

    /**
     * 空闲时把收件箱里的输入开成一回合（界面的「打断后跑排队那条」，崩溃恢复后补交、由用户确认后处理的报告）。
     * 收件箱空、或已有回合在跑时什么都不做
     */
    fun runInbox() {
        startTurnIfIdle(TurnTrigger.User)
    }

    /** 分身的第一个回合：没有新输入，任务就在继承来的那次 Agent 调用的结果里 */
    internal fun startDelegated() {
        startTurnIfIdle(TurnTrigger.Delegated)
    }

    /**
     * 本会话日志的末尾一段，每行一条 [EventRecord]（与磁盘 JSONL 同格式），供 SendFeedback 的草稿附上。
     * 只留末尾约 [maxChars] 个字符：日志可以很长，诊断包要的是最近发生了什么；更早的整行丢掉，不截半行。
     */
    internal suspend fun sessionLogTail(maxChars: Int): String {
        val lines = ArrayDeque<String>()
        var size = 0
        log.read(1).collect { record ->
            val line = dev.min.agent.protocol.AgentJson.encodeToString(EventRecord.serializer(), record)
            lines.addLast(line)
            size += line.length + 1
            while (size > maxChars && lines.size > 1) size -= lines.removeFirst().length + 1
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    /**
     * 登记一个 skill / command 的 frontmatter hooks（hooks设计 §frontmatter）：本会话余下回合一直生效。
     * 模型调 Skill 工具与用户敲 `/名字` 两条路都到这里（斜杠展开在宿主，那里拿到展开的组件名后调本方法）。
     * 项目层（project / local）要过工作区信任——与 settings 的 hooks 同一条规则（CC：项目 skill 同 settings）。
     * 每次**调用**都重新登记全量（CC 的 invoke 时 register）：整份替换同名的旧登记，`once` 已用掉的也回来
     */
    suspend fun registerComponentHooks(kind: String, name: String, layer: String, hooks: kotlinx.serialization.json.JsonObject) {
        if (hooks.isEmpty()) return
        if (layer.startsWith("plugin:")) {
            val bundle = plugins() ?: return
            val plugin = bundle.batch.plugins.firstOrNull { "plugin:${it.namespace}" == layer } ?: return
            val home = deps.memoryHome ?: return
            val root = deps.canonicalPath(GuestPath(plugin.root)).value
            val userDir = deps.canonicalPath(GuestPath(home.value.trimEnd('/') + "/.claude/plugins")).value.trimEnd('/')
            if (!root.startsWith("$userDir/") && !deps.pluginTrust(GuestPath(plugin.root), state.value.created.cwd)) return
            val filtered = dev.min.agent.ext.plugins.commandPluginHooks(hooks)
            if (filtered.isEmpty()) return
            commit(listOf(dev.min.agent.spi.ComponentHooksRegistered(kind, name, "plugin", filtered)), Durability.Sync)
            return
        }
        if (layer == "project" || layer == "local") {
            val trusted = deps.trustProjectHooks(state.value.created.cwd)
            if (!trusted) {
                commit(
                    listOf(
                        Note(
                            NoteLevel.Warning,
                            "$layer 层「$name」的 frontmatter hooks 带了 ${hooks.keys.count { k -> dev.min.agent.ext.hooks.HookEvent.entries.any { it.name == k } }} 个事件的 hooks，未启用：工作区还没有被信任。",
                        ),
                    ),
                    Durability.Batched,
                )
                return
            }
        }
        commit(listOf(dev.min.agent.spi.ComponentHooksRegistered(kind, name, layer, hooks)), Durability.Sync)
    }

    /**
     * 宿主发系统通知时跑 Notification hooks（hooks设计 §7：内核不发通知，何时算「该通知」由宿主决定）。
     * matcher 匹配通知类型（取值照 CC：permission_prompt、idle_prompt……），只执行、不改内核状态，记一条 [HooksRan]
     */
    suspend fun notifyHooks(type: String, message: String, title: String? = null) {
        val specific = HookJson.encodeToJsonElement(
            NotificationInput.serializer(),
            NotificationInput(message = message, title = title, notificationType = type),
        ).jsonObject
        HookDriver(this).run(HookEvent.Notification, type, specific)
    }

    /**
     * 宿主把用户敲的斜杠命令展开成提示词之后、发送之前调用（hooks.md UserPromptExpansion）。
     * 返回 null = 照常发送；返回理由 = 被 hook 拦下，不要发送，把理由给人看。additionalContext 已经记进日志，随后发的那条消息自带
     */
    suspend fun promptExpansionHooks(expansionType: String, commandName: String, commandArgs: String, commandSource: String?, prompt: String): String? =
        promptExpansion.run(expansionType, commandName, commandArgs, commandSource, prompt)

    // ---- 检查点与回退（检查点与rewind设计 §4） ----

    /** 回退到 [messageId] 之前会发生什么；它不是开启回合的消息（或已被回退掉）时为 null。只读，可在任何时候调用 */
    suspend fun previewRewind(messageId: MessageId): RewindPreview? = checkpointer.preview(messageId)

    /** 回退到 [messageId] 之前。会话必须空闲；期间到达的输入照常进收件箱，回退完不自动开回合 */
    suspend fun rewind(messageId: MessageId, scope: RewindScope): RewindOutcome =
        whileIdle { checkpointer.rewind(messageId, scope) } ?: RewindOutcome.Refused(BUSY)

    /** 撤销一次恢复代码（回到它之前拍的快照） */
    suspend fun undoRestore(restoreId: String): RewindOutcome =
        whileIdle { checkpointer.undoRestore(restoreId) } ?: RewindOutcome.Refused(BUSY)

    /** 按编辑记录撤销几次调用（「改动的文件」面板） */
    suspend fun restoreCalls(calls: List<CallId>): RewindOutcome =
        whileIdle { checkpointer.restoreCalls(calls) } ?: RewindOutcome.Refused(BUSY)

    /**
     * 在会话空闲时做一件维护的事（回退、恢复代码）：期间不开回合，输入照常进收件箱，做完了收件箱里有东西就开回合。
     * 用单独的 Job，不占回合的位置：[interrupt] 停的是回合，不能把做到一半的恢复停在半路（那会留下改了一半的工作区）。
     * 已有回合或别的维护在跑返回 null
     */
    private suspend fun <T> whileIdle(block: suspend () -> T): T? {
        val job = synchronized(turnLock) {
            if (closing || turnJob?.isActive == true || maintenanceJob?.isActive == true || _state.value.turn != null) return null
            // 在锁里就启动：启动之前它的 isActive 是假的，startTurnIfIdle 会从这个空档里溜进去
            scope.async { block() }.also { maintenanceJob = it }
        }
        return try {
            job.await()
        } finally {
            // 撤维护与开回合在同一把锁里：中间不留「收件箱有东西、什么都没在跑」的空档（[isParked] 会把它当成停着）
            synchronized(turnLock) {
                if (maintenanceJob === job) maintenanceJob = null
                startTurnIfIdle(TurnTrigger.User)
            }
        }
    }

    /** 正在做的维护（回退、恢复代码）；与回合互斥 */
    private var maintenanceJob: Job? = null

    /** 等到没有进行中的回合（测试与桌面 CLI 用） */
    suspend fun awaitIdle() {
        synchronized(turnLock) { turnJob }?.join()
    }

    /**
     * 一个回合 Job 里可能连着跑几个回合：一回合正常结束后，回合期间排队的输入接着开下一回合。
     * 放在同一个 Job 里而不是结束回调里再开，是为了让「Job 结束 = 空闲」没有空隙。
     * 被中断或失败的回合不自动开下一回合（学 Claude Code：中断后排队的话退回输入框由人决定）。
     */
    /**
     * 卸下前「关门」：没有回合（含正在启动、还没写 TurnStarted 的）、也没有维护在跑时关门并返回 true。
     * 关门之后不再开回合、不再做维护，但还能写（卸下前要跑 SessionEnd、写它的运行记录）；随后 [sealWrites] 封住写入。
     * 封住之后任何写入都直接拒绝（不落盘）——卸下之后这个对象可能还被别的协程拿着（例如正要交付报告的），
     * 而再次打开会话会建一个新的运行时写同一份日志，两个写者会把日志写坏。被拒的子 agent 报告不会丢：
     * 重新打开时 Hub 会补交没交付的报告
     */
    internal fun sealIfIdle(): Boolean = synchronized(turnLock) {
        if (turnJob?.isActive == true || maintenanceJob?.isActive == true || _state.value.turn != null) return false
        closing = true
        true
    }

    /** 关门之后、卸下之前：从此一条都不写 */
    internal fun sealWrites() {
        sealed = true
    }

    /** 关了门却发现整棵树卸不下（别的会话忙）：撤回，什么都没发生过 */
    internal fun unseal() {
        synchronized(turnLock) {
            closing = false
            sealed = false
        }
    }

    /** 关门了：不再开回合、不再做维护 */
    @Volatile
    private var closing = false

    /** 封住了：不再写日志 */
    @Volatile
    private var sealed = false

    private fun startTurnIfIdle(trigger: TurnTrigger) {
        synchronized(turnLock) {
            if (closing || turnJob?.isActive == true || maintenanceJob?.isActive == true) return
            val s = _state.value
            if (s.turn != null) return
            // 会话已由模型结束（EndConversation）：任何来源都不再开回合。send 之外还有子 agent 的报告、后台任务的通知、
            // hook 唤醒、定时任务、界面的「继续」与压缩，只在 send 里拦的话它们照样把模型叫起来。输入照常进收件箱（日志里看得到）
            if (s.ended) return
            if ((trigger == TurnTrigger.User || trigger == TurnTrigger.Notification || trigger == TurnTrigger.Hook || trigger == TurnTrigger.Cron) && s.inbox.isEmpty()) return
            turnJob = scope.launch {
                val runner = TurnRunner(this@SessionRuntime)
                var next: TurnTrigger? = trigger
                while (next != null) {
                    val completed = runner.run(next)
                    // 排队的输入接续下一回合：按排在最前的输入记触发（日志里看得出是谁唤醒的），见 [continuationTrigger]
                    next = if (completed && _state.value.inbox.isNotEmpty()) continuationTrigger(_state.value.inbox.first().origin) else null
                }
            }
        }
    }

    companion object {
        /**
         * 新建会话。[initial] 是紧跟 SessionCreated 一起写入的事件（子会话的来历、分身继承的对话与记忆），
         * 与 SessionCreated 同一次追加：子会话日志要么完整建好，要么不存在。
         * [loadMemory] 为 false 时不读记忆文件（分身已经在 [initial] 里带了父会话那份）。
         */
        suspend fun create(
            store: SessionStore,
            header: LogHeader,
            created: SessionCreated,
            deps: KernelDeps,
            scope: CoroutineScope,
            initial: List<SessionEvent> = emptyList(),
            loadMemory: Boolean = true,
            loadSettings: Boolean = true,
            loadSkills: Boolean = true,
            hub: SessionHub? = null,
            /** false = 不跑 SessionStart(startup)。分支（Hub.branch）用它：分支是原对话的延续，不是新会话（CC 的 fork 同样不跑） */
            runStartHooks: Boolean = true,
        ): SessionRuntime {
            val events = listOf(created) + initial
            // 先折叠再落盘：初始事件里有折叠不了的（比如分支带来别的会话的 team_created）就在这里失败，磁盘上不留下
            // 一个每次打开都抛的孤儿会话（store.list() 会列出它）。seq 与时间由存储分配，这里的只用来验证
            fold(header, events.mapIndexed { i, e -> EventRecord(i + 1L, deps.clock.nowMs(), e) })
            val log = store.create(header)
            val records = log.append(events, Durability.Sync)
            val runtime = SessionRuntime(log, fold(header, records), deps, scope, hub)
            if (loadSettings && deps.memoryHome == null && deps.autoModeReviewer != null) {
                runtime.commit(listOf(dev.min.agent.spi.AutoReviewSettingsLoaded(reviewer = deps.autoModeReviewer)), Durability.Sync)
            }
            val home = deps.memoryHome
            if (home != null) {
                // 各层 settings 只读一次：权限 / hooks 与自动记忆的开关、目录用同一份快照
                val settings = if (loadMemory || loadSettings) SettingsLoader.load(deps.host.fs, created.cwd, home, inputs = runtime.deps.settingsInputs) else null
                runtime.checkpointsBySettings = settings?.let(::checkpointsEnabledIn)
                if (loadMemory) runtime.loadMemory(settings)
                if (loadSettings) {
                    runtime.loadSettings(requireNotNull(settings))
                    runtime.loadOutputStyle(requireNotNull(settings))
                }
                // InstructionsLoaded（hooks.md :1278）：eager 载入的记忆文件逐文件发。放在 loadSettings 之后，
                // settings 层的 hooks 快照到那一刻才进状态；子会话的组件 hooks 已随 initial 折叠
                if (loadMemory) runtime.fireInstructionsLoaded(runtime.state.value.memory, ::eagerLoadReason)
            }
            // 子会话的清单由 initial 带来（继承父会话那份），不再读磁盘
            val locks = runtime.state.value.autoReviewSettings
            val currentMode = runtime.state.value.settings.mode
            if (currentMode == PermissionMode.Auto && locks.disableAutoMode || currentMode == PermissionMode.BypassPermissions && locks.disableBypassPermissionsMode) {
                // 子会话不重读 settings，但冻结的托管锁仍须在第一个回合前兑现。
                runtime.commit(listOf(SettingsChanged(mode = PermissionMode.Default), Note(NoteLevel.Warning, "这次启动禁用了 $currentMode（托管策略，或 --restricted），使用 Default 模式")), Durability.Sync)
            }
            if (loadSkills) runtime.loadSkills()
            if (runStartHooks && runtime.state.value.origin == null) runtime.runSessionStart("startup")
            return runtime
        }

        /**
         * 打开已有会话：折叠日志，若上次死在回合中途就补上收尾事件（[planRecovery]）。**不自动继续**——
         * 进程被杀往往意味着用户不在场，由界面上的「继续」决定（docs/agent/02-总体架构.md §5）。
         *
         * 记忆文件**不重读**：沿用创建时写进日志的快照。重读的话，磁盘上的 CLAUDE.md 一改，重启后系统提示就变了，
         * 提示缓存整段失效，请求也不再能只凭日志重建（docs/agent/07-工程规范.md 第 6、8 条）。
         */
        suspend fun open(
            store: SessionStore,
            id: SessionId,
            deps: KernelDeps,
            scope: CoroutineScope,
            hub: SessionHub? = null,
        ): SessionRuntime? {
            val log = store.open(id) ?: return null
            val state = fold(log.header, log.read().toList())
            // 静置时间在写恢复事件之前取：补的收尾事件不是用户的动静，不能把会话「唤醒」
            val quietSince = state.lastEventAtMs
            val runtime = SessionRuntime(log, state, deps, scope, hub)
            runtime.commit(planRecovery(state), Durability.Sync)
            // --restricted 拒绝 bypassPermissions，对续接的会话同样成立：模式与锁都是上次启动（可能不受限）写进日志的，
            // 这里不重新兑现的话，--resume / --continue 就把 bypass 原样带进受限会话，之后还能随时切回去
            if (deps.settingsInputs.restricted) restrictedLockEvents(runtime.state.value).takeIf { it.isNotEmpty() }?.let { runtime.commit(it, Durability.Sync) }
            runtime.refreshModelPolicy()
            runtime.resumeBasis = ResumeBasis(quietSince, runtime.state.value.lastTurn?.turnId)
            runtime.cancelMcpElicitations(close = false)
            return runtime
        }
    }
}

/** 受限启动续接一个会话时补上的事件：bypass 锁没上就上锁（与创建时的锁同一个字段），模式还是 bypass 就降为 Default 并说明 */
internal fun restrictedLockEvents(s: SessionState): List<SessionEvent> = buildList {
    if (!s.autoReviewSettings.disableBypassPermissionsMode) add(s.autoReviewSettings.copy(disableBypassPermissionsMode = true))
    if (s.settings.mode == PermissionMode.BypassPermissions) {
        add(SettingsChanged(mode = PermissionMode.Default))
        add(Note(NoteLevel.Warning, "这次启动禁用了 ${PermissionMode.BypassPermissions}（--restricted），续接的会话改用 Default 模式"))
    }
}

/** CC 的单会话关检查点开关 */
internal const val DISABLE_CHECKPOINTS_ENV = "CLAUDE_CODE_DISABLE_FILE_CHECKPOINTING"

private const val BUSY = "会话正在进行中，等这一轮结束再回退。"

/** settings 的 `fileCheckpointingEnabled`：只有明确写 false 才关（缺省为开，与 CC 同） */
internal fun checkpointsEnabledIn(snapshot: SettingsSnapshot): Boolean =
    (snapshot.merged.raw("fileCheckpointingEnabled") as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull != false

/** 环境变量按「真」读：`1` / `true` / `yes`，不分大小写、忽略首尾空白（CC 的环境开关都是这个口径）；没设或别的值为假 */
internal fun isTruthyEnv(value: String?): Boolean = value?.trim()?.lowercase() in setOf("1", "true", "yes")

/** 后台 hook 的轮询节奏：轮询结果文件 / 句柄探活 / `kill -0` 退回探测的最小间隔 */
internal class AsyncHookTimings(
    var pollMs: Long = 500,
    var livenessMs: Long = 5_000,
    /** 退回探测要起进程，比句柄探活稀得多（N3） */
    var fallbackLivenessMs: Long = 30_000,
)

/**
 * 排队的输入接续下一回合时记哪种触发，与这类输入在空闲时送达的口径一致：子 agent 的报告 / 消息、后台任务的通知
 * 记 Notification（空闲时 [SessionHub.deliver] 也是这么开回合的），asyncRewake hook 记 Hook，定时任务记 Cron。
 * 以前除 hook 外一律记 User：报告若在上一回合收尾前到达，接续回合就被记成「用户发起」，同一件事因时序不同记成两样
 */
internal fun continuationTrigger(origin: InputOrigin): TurnTrigger = when (origin) {
    is InputOrigin.Hook -> TurnTrigger.Hook
    is InputOrigin.Agent, is InputOrigin.Task, is InputOrigin.Peer, is InputOrigin.Channel -> TurnTrigger.Notification
    is InputOrigin.Cron -> TurnTrigger.Cron
    InputOrigin.User, is InputOrigin.Parent -> TurnTrigger.User
}
