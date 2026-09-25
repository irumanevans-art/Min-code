package dev.min.code.ui.session

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeCodeConfigStore
import dev.min.code.core.claudecode.ClaudeCodeCostLedger
import dev.min.code.core.claudecode.ClaudeCodeImage
import dev.min.code.core.claudecode.ClaudeCodeInstaller
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodePermissionMode
import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.claudecode.ClaudeCodeSessionStore
import dev.min.code.core.claudecode.ClaudeCodeSessionTransfer
import dev.min.code.core.claudecode.SessionImportReject
import dev.min.code.core.claudecode.SessionMeta
import dev.min.code.core.claudecode.ComposerDraft
import dev.min.code.core.claudecode.ComposerDraftStore
import dev.min.code.core.claudecode.asStringOrNull
import dev.min.code.core.claudecode.CwdPath
import dev.min.code.core.network.NetworkProbe
import dev.min.code.core.network.NetworkSnapshot
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.rootfs.createCwdFolder
import dev.min.code.core.rootfs.listCwdFolders
import dev.min.code.core.service.LocalService
import dev.min.code.core.service.LocalServiceRegistry
import dev.min.code.core.service.LocalServiceStatus
import dev.min.code.core.service.LocalServiceStopReason
import dev.min.code.core.settings.SettingsStore
import dev.min.code.util.ImageUtils
import dev.min.code.util.LocalPreviewBus
import dev.min.code.util.LocalUrls
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

class ClaudeCodeVM(
    private val context: Application,
    private val registry: ClaudeCodeSessionRegistry,
    private val installer: ClaudeCodeInstaller,
    private val workspaceRepository: WorkspaceRepository,
    private val configStore: ClaudeCodeConfigStore,
    private val costLedger: ClaudeCodeCostLedger,
    private val settingsStore: SettingsStore,
    private val drafts: ComposerDraftStore,
    private val sessionMeta: ClaudeCodeSessionMetaStore,
    private val networkProbe: NetworkProbe,
    private val localServices: LocalServiceRegistry,
) : ViewModel() {
    /** 活跃会话的状态（注册表在多个会话间切换时自动跟随） */
    val session = registry.state

    /**
     * 注册表的会话 key。CLI 还没回报 `sessionId` 时状态里是空的，
     * 但草稿必须立刻能落盘，所以输入框钉在这个 key 上。
     */
    val activeSessionKey = registry.activeKey

    /**
     * 输入框草稿。按会话钉在磁盘上，杀进程再进同一会话还在。
     * 打开冲突（顺延的空框对上一个已经有内容的会话）时拒绝切换，当前会话的框不动。
     */
    private val _composerDraft = MutableStateFlow(ComposerDraft.Empty)
    val composerDraft: StateFlow<ComposerDraft> = _composerDraft.asStateFlow()

    /**
     * 按停止键（Esc）在本轮还没产出时撤回的消息，要原样退还给输入框。
     * 和 [composerDraft] 分开：那是可以被重放的状态，这是一次性事件。
     */
    val withdrawnMessages = registry.withdrawnMessages

    /** 今天的累计花费，跨会话、跨重启。底栏那个金额读它 */
    val dailyCostUsd: StateFlow<Double> = costLedger.dailyCostUsd

    /** 命令 / 模型说明显示中文对照还是英文原文 */
    val chineseDescriptions: StateFlow<Boolean> = settingsStore.settings
        .map { it.chineseDescriptions }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setChineseDescriptions(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setChineseDescriptions(enabled) }
    }

    /** 正在跑的会话，供抽屉打运行中标记 */
    val liveSessions = registry.liveSessions

    /** 达到并发上限时给 UI 一个提示 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()

    fun dismissNotice() { _notice.value = null }

    /**
     * 只对活跃会话生效的操作统一走这里。没有活跃会话时静默忽略 ——
     * UI 在没有会话时显示的是启动面板，本来就点不到这些控件。
     */
    private inline fun onActive(block: (ClaudeCodeManager) -> Unit) {
        registry.active()?.let(block)
    }

    /**
     * 环境是否就绪。装/修的过程在 SetupVM 里，这里只判断"能不能开会话"；
     * rootfs 和 CLI 缺一样，页面就把向导嵌进来。
     *
     * 连接（供应商 token）**不算**在里面：向导只管装环境，没连接时照样进启动面板，
     * 由 [connected] 驱动那里的 [ConnectPrompt] 指路。
     */
    data class SetupState(
        val loading: Boolean = true,
        val rootfsReady: Boolean = false,
        val nodeInstalled: Boolean = false,
        val claudeInstalled: Boolean = false,
        /** wrapper 装上了但原生二进制没到位（上次那个 100 MB 的平台包没下完） */
        val cliIncomplete: Boolean = false,
        /** 已安装的 CLI 版本。@latest 装出来的版本会随时间漂移，出问题时必须能看到装的是哪一版 */
        val cliVersion: String? = null,
        val installError: String? = null,
    ) {
        val ready: Boolean get() = rootfsReady && claudeInstalled
    }

    private val _setup = MutableStateFlow(SetupState())
    val setup = _setup.asStateFlow()

    /**
     * 有没有可用的连接（当前生效的供应商带着 token）。没有时启动面板给 [ConnectPrompt]。
     *
     * 跟着 DataStore 走，不在 [refresh] 里读一次：人从供应商页加完一家回来，提示要自己消失，
     * 不能等他去点刷新。初值 true：设置还没读到的那一下不闪「未连接」。
     * 以后「用 Claude 订阅」接进来，判定也改在这一处。
     */
    val connected: StateFlow<Boolean> = settingsStore.settings
        .map { it.token.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 磁盘上的 transcript（CLI 自己写的） */
    private val _diskSessions = MutableStateFlow<List<ClaudeCodeSessionStore.SessionSummary>>(emptyList())

    /** 抽屉里的一条 */
    data class SessionEntry(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messageCount: Int,
        /** 进程还活着（可以秒切，不用重启） */
        val isLive: Boolean,
        val isActive: Boolean,
        val pinned: Boolean = false,
        val category: String? = null,
        /** 标题来自 CLI 拟名、人手改过的名字，还是第一条消息的截断 */
        val titled: Boolean = false,
        /** 抽屉搜索用的正文片段（磁盘扫描时顺手攒的） */
        val bodyText: String = "",
        /** CLI 已经把 transcript 落过盘。刚开、还没跑完一轮的会话没有文件，也就没有可导出的东西 */
        val onDisk: Boolean = true,
    )

    /**
     * 会话列表 = 磁盘 transcript ∪ 当前活着的会话。
     *
     * 不能只列磁盘：CLI 是**跑完一轮才把 transcript 落盘**的，所以刚开的、还没说过话的
     * 会话在磁盘上根本没有文件 —— 之前"开了好几次会话但列表里只有一条、
     * 正在用的那个还不在里面"就是这个原因。活着的会话必须从内存状态补进去。
     *
     * 但补进来的**必须真的还活着**（`LiveSession.isLive`）。注册表里会留着崩掉/已退出的
     * 条目，之前不加过滤地把它们全渲染成"新会话"、`updatedAt = Long.MAX_VALUE` 还全部置顶，
     * 于是每崩一次就多一行点不开的幽灵会话，把真正的历史挤到看不见的地方。
     */
    val sessions: StateFlow<List<SessionEntry>> =
        combine(_diskSessions, registry.liveSessions, registry.state, sessionMeta.items) { disk, live, active, meta ->
            val running = live.filter { it.isLive }
            val liveIds = running.map { it.key }.toSet()
            val activeId = active.sessionId
            val liveTitles = running.associate { it.key to it.liveTitle }
            val fromDisk = disk.map { d ->
                val extra = meta[d.id] ?: SessionMeta()
                val live = liveTitles[d.id]?.takeIf { it.isNotBlank() }
                SessionEntry(
                    id = d.id,
                    // 人手改名 > CLI 实时拟名 > 磁盘 summarize（custom-title / 首条消息）
                    title = extra.title?.takeIf { it.isNotBlank() } ?: live ?: d.title,
                    updatedAt = d.updatedAt,
                    messageCount = d.messageCount,
                    isLive = d.id in liveIds,
                    isActive = d.id == activeId,
                    pinned = extra.pinned,
                    category = extra.category,
                    titled = extra.title != null || live != null || d.titled,
                    bodyText = d.bodyText,
                )
            }
            val known = fromDisk.map { it.id }.toSet()
            // 活着但磁盘上还没有文件的，补一条占位
            val orphanLive = running.filter { it.key !in known }.map {
                val extra = meta[it.key] ?: SessionMeta()
                val live = it.liveTitle?.takeIf { t -> t.isNotBlank() }
                SessionEntry(
                    id = it.key,
                    title = extra.title?.takeIf { t -> t.isNotBlank() }
                        ?: live
                        ?: context.getString(if (it.busy) R.string.vm_new_session_busy else R.string.vm_new_session),
                    updatedAt = Long.MAX_VALUE, // 还没落盘的排最前
                    messageCount = 0,
                    isLive = true,
                    isActive = it.key == activeId,
                    pinned = extra.pinned,
                    category = extra.category,
                    titled = extra.title != null || live != null,
                    onDisk = false,
                )
            }
            (orphanLive + fromDisk).sortedWith(
                compareByDescending<SessionEntry> { it.pinned }
                    .thenByDescending { it.isLive }
                    .thenByDescending { it.updatedAt },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()

    init {
        refresh()
        // 进程被 FGS 保活、页面重建时，注册表里的会话还在，草稿要从磁盘接回来
        registry.activeKey.value?.let { attachComposer(it) }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val status = installer.status(workspaceId)
                _setup.update {
                    it.copy(
                        loading = false,
                        rootfsReady = status.rootfsReady || (status.nodeInstalled && status.claudeInstalled),
                        nodeInstalled = status.nodeInstalled,
                        claudeInstalled = status.claudeInstalled,
                        cliIncomplete = status.cliIncomplete,
                        cliVersion = status.cliVersion,
                    )
                }
            }.onFailure {
                _setup.update { s -> s.copy(loading = false, installError = it.message) }
            }
            refreshSessions()
        }
    }

    /** 历史会话直接读 CLI 自己写的 transcript，不依赖 App 侧持久化 */
    fun refreshSessions() {
        viewModelScope.launch { reloadSessions() }
    }

    /** 每次重扫领一个号；扫完时号已经不是最新的，说明后面还有一次更晚开始的扫描，结果让给它 */
    private var sessionScanGeneration = 0

    /**
     * 重扫磁盘并等它落进 [_diskSessions]。扫一遍要把每份 transcript 读一遍，几份大会话时是秒级的，
     * 两次扫描可能交错：先开始、后结束的那次（比如回合结束触发的）不能拿旧列表盖掉
     * 刚导入之后那次的结果。
     */
    private suspend fun reloadSessions() {
        val generation = ++sessionScanGeneration
        val listed = runCatching { (registry.active() ?: registry.configProbe()).listSessions() }
            .getOrDefault(emptyList())
        if (generation == sessionScanGeneration) _diskSessions.value = listed
    }

    // -----------------------------------------------------------------------
    // 环境与更新
    //
    // 和上面那套「安装向导」状态分开：向导只在没装好时出现一次，这里是装好之后
    // 长期可用的维护面板，两者的生命周期和错误语义都不一样，混在 SetupState 里
    // 会让「安装失败」和「更新失败」共用同一个字段。
    // -----------------------------------------------------------------------

    /** 面板里能跑的维护动作。同一时刻只允许一个。 */
    enum class MaintenanceTask { UpdateCli, AptUpgrade, ReinstallNode }

    data class MaintenanceState(
        val loading: Boolean = false,
        /** package.json 里的纯 semver（`2.1.258`）。**版本比较只能用这个** */
        val cliVersion: String? = null,
        /** wrapper 在但原生二进制缺失：版本号能读出来，会话却起不来。「更新」会顺手补齐 */
        val cliIncomplete: Boolean = false,
        val latestCliVersion: String? = null,
        val nodeVersion: String? = null,
        val npmVersion: String? = null,
        val osName: String? = null,
        val checking: Boolean = false,
        val running: MaintenanceTask? = null,
        val detail: String = "",
        val progress: Float? = null,
        val error: String? = null,
        val lastResult: String? = null,
    ) {
        val busy: Boolean get() = running != null || checking || loading

        /** 查过最新版、且确实比本地新。没查过时恒为 false，不去猜 */
        val updateAvailable: Boolean
            get() = ClaudeCodeInstaller.isNewerVersion(cliVersion, latestCliVersion)
    }

    private val _maintenance = MutableStateFlow(MaintenanceState())
    val maintenance = _maintenance.asStateFlow()

    // -----------------------------------------------------------------------
    // 进程表 + 本机预览位（同一张表；打开 = 填位，不是第二套 WebView）
    // -----------------------------------------------------------------------

    data class RuntimeState(
        val loading: Boolean = false,
        val snapshot: NetworkSnapshot? = null,
        val expandedLogId: String? = null,
    )

    /**
     * 会话铬件上的预览**位**：有 URL 键才有效；展开是看见面板，关掉只是藏。
     * [autoExpandedFor] 记已经自动展开过的 normalized URL，避免连弹。
     */
    data class PreviewSlot(
        val url: String? = null,
        val expanded: Boolean = false,
        val autoExpandedFor: String? = null,
    )

    private val _runtime = MutableStateFlow(RuntimeState())
    val runtime = _runtime.asStateFlow()
    val localServiceList: StateFlow<List<LocalService>> = localServices.services

    private val _previewSlot = MutableStateFlow(PreviewSlot())
    val previewSlot: StateFlow<PreviewSlot> = _previewSlot.asStateFlow()

    init {
        // Agent / 终端 / 总线发现的 loopback → 填位（可自动展开一次）
        merge(registry.localPreviewUrls, LocalPreviewBus.urls)
            .onEach { bindPreview(it, expand = true, fromAgent = true) }
            .launchIn(viewModelScope)

        // 表里 Running 且有端口 → 静默绑位（不抢展开，除非位还空）。
        // 不认 Starting：一启动就死的服务（命令不存在之类）在 Starting 那一瞬间就会把预览位弹开，
        // 留下一页「网页无法打开」。进程表的就绪窗口只有 2.5 秒，等它一下
        localServices.services
            .onEach { list ->
                val live = list.firstOrNull {
                    it.status == LocalServiceStatus.Running && it.port != null
                } ?: return@onEach
                val port = live.port ?: return@onEach
                val current = _previewSlot.value.url
                if (current.isNullOrBlank()) {
                    bindPreview(LocalUrls.loopbackUrl(port), expand = true, fromAgent = true)
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * @param expand 是否展开面板（进程表「打开」、链接点按 = true）
     * @param fromAgent true 时仅在该 URL 尚未 auto-expand 过时自动展开一次
     */
    fun bindPreview(url: String, expand: Boolean = true, fromAgent: Boolean = false) {
        val normalized = LocalUrls.normalizeLoopback(url)
        if (!LocalUrls.isLoopbackHttp(normalized)) return
        _previewSlot.update { slot ->
            val alreadyAuto = slot.autoExpandedFor == normalized
            val shouldExpand = when {
                expand && fromAgent -> !alreadyAuto || slot.expanded
                expand -> true
                else -> slot.expanded
            }
            slot.copy(
                url = normalized,
                expanded = shouldExpand,
                autoExpandedFor = if (fromAgent || alreadyAuto) normalized else slot.autoExpandedFor,
            )
        }
    }

    fun togglePreview() {
        _previewSlot.update { slot ->
            if (slot.url.isNullOrBlank()) slot
            else slot.copy(expanded = !slot.expanded)
        }
    }

    fun collapsePreview() {
        _previewSlot.update { it.copy(expanded = false) }
    }

    /** 清空位（服务全停、用户明确拆掉时）。平时关掉只用 [collapsePreview]。 */
    fun clearPreview() {
        _previewSlot.value = PreviewSlot()
    }

    fun refreshNetworkSnapshot() {
        viewModelScope.launch(Dispatchers.IO) {
            _runtime.update { it.copy(loading = true) }
            val snap = runCatching { networkProbe.snapshot() }
            _runtime.update { state ->
                snap.fold(
                    onSuccess = { state.copy(loading = false, snapshot = it) },
                    onFailure = { state.copy(loading = false) },
                )
            }
        }
    }

    fun stopLocalService(id: String) {
        localServices.stop(id, LocalServiceStopReason.UserStop)
    }

    fun logOf(id: String): String = localServices.logOf(id)

    fun toggleServiceLog(id: String) {
        _runtime.update {
            it.copy(expandedLogId = if (it.expandedLogId == id) null else id)
        }
    }

    fun dismissMaintenanceError() {
        _maintenance.update { it.copy(error = null) }
    }

    /** 打开面板时拉一次。node/npm 版本要起 proot，所以是异步的，先渲染已知的部分 */
    fun loadEnvironment() {
        viewModelScope.launch {
            _maintenance.update { it.copy(loading = true, error = null) }
            val info = runCatching { installer.environment(workspaceId) }
            _maintenance.update { state ->
                info.fold(
                    onSuccess = {
                        state.copy(
                            loading = false,
                            osName = it.osName,
                            nodeVersion = it.nodeVersion,
                            npmVersion = it.npmVersion,
                            cliVersion = it.cliVersion,
                            cliIncomplete = it.cliIncomplete,
                        )
                    },
                    onFailure = { state.copy(loading = false, error = it.message ?: it.toString()) },
                )
            }
        }
    }

    fun checkCliUpdate() {
        if (_maintenance.value.checking) return
        viewModelScope.launch {
            _maintenance.update { it.copy(checking = true, error = null) }
            val result = runCatching { installer.fetchLatestCliVersion() }
            _maintenance.update { state ->
                result.fold(
                    onSuccess = { state.copy(checking = false, latestCliVersion = it) },
                    onFailure = {
                        state.copy(checking = false, error = context.getString(R.string.vm_check_update_failed, it.message ?: it.toString()))
                    },
                )
            }
        }
    }

    /**
     * 装 / 更新 CLI 用不用淘宝 npm 源。安装向导和「环境与更新」读写的是同一个设置 ——
     * 以前面板上的勾选是临时的，每次打开都是关，设置页里另有一个开关却从没被更新读过。
     */
    val useNpmMirror: StateFlow<Boolean> = settingsStore.settings
        .map { it.useNpmMirror }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setUseNpmMirror(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setUseNpmMirror(enabled) }
    }

    fun updateCli() = runMaintenance(MaintenanceTask.UpdateCli) { onState ->
        installer.updateCli(workspaceId, settingsStore.current().useNpmMirror, onState)
    }

    fun upgradeApt() = runMaintenance(MaintenanceTask.AptUpgrade) { onState ->
        val summary = installer.upgradeApt(workspaceId, onState)
        _maintenance.update { it.copy(lastResult = summary) }
    }

    /** 只重装 Node，不动 CLI —— 修 `/opt/node` 损坏用，不是升级（版本写死在安装器里） */
    fun reinstallNode() = runMaintenance(MaintenanceTask.ReinstallNode) { onState ->
        installer.install(workspaceId, forceNode = true, onState = onState)
    }

    /**
     * 跑一个维护动作。
     *
     * **有会话在跑就直接拒绝**：npm / apt 会原地替换正在被执行的文件，
     * 让它跑下去的结果是当前会话在半路上炸掉，而且现场很难看懂。
     */
    private fun runMaintenance(
        task: MaintenanceTask,
        block: suspend (onState: suspend (ClaudeCodeInstaller.InstallState) -> Unit) -> Unit,
    ) {
        if (_maintenance.value.running != null) return
        // 必须过滤 isLive：注册表里会留着刚崩掉/已结束的壳（当前活跃项不会被 pruneDead 回收），
        // 按 size 数的话，一个已经退出的会话也能把更新按钮锁死
        val live = liveSessions.value.count { it.isLive }
        if (live > 0) {
            _maintenance.update {
                it.copy(
                    // 和面板上那条拦截提示是同一句话，共用一份文案
                    error = context.getString(R.string.maint_blocked, live),
                )
            }
            return
        }
        viewModelScope.launch {
            _maintenance.update {
                it.copy(running = task, error = null, detail = "", progress = null, lastResult = null)
            }
            runCatching {
                block { state ->
                    when (state) {
                        is ClaudeCodeInstaller.InstallState.Downloading -> _maintenance.update {
                            it.copy(detail = state.detail, progress = state.progress)
                        }

                        is ClaudeCodeInstaller.InstallState.Running -> _maintenance.update {
                            it.copy(detail = state.detail, progress = null)
                        }

                        is ClaudeCodeInstaller.InstallState.Done -> _maintenance.update {
                            it.copy(lastResult = state.cliVersion?.let { v -> context.getString(R.string.vm_cli_current, v) } ?: it.lastResult)
                        }

                        is ClaudeCodeInstaller.InstallState.Failed -> _maintenance.update {
                            it.copy(error = state.message)
                        }
                    }
                }
            }.onFailure { e ->
                _maintenance.update { it.copy(error = e.message ?: e.toString()) }
            }
            _maintenance.update { it.copy(running = null, progress = null, detail = "") }
            // 版本变了：面板和安装向导那份状态都要跟上，否则启动面板还显示旧版本号
            loadEnvironment()
            refresh()
        }
    }

    // --- 会话（多会话：注册表持有多个 manager，各自一个 CLI 进程） ---
    fun start(options: ClaudeCodeManager.SessionOptions) {
        if (registry.newSession(options) == null) atCapacity()
        else attachComposer(registry.activeKey.value)
    }

    fun newSession() {
        // 没有活跃会话时（刚启动 App）不能退回 SessionOptions() 的默认值 ——
        // 那会把用户上次选的模型/effort 丢掉，新会话默默跑在 CLI 默认模型上
        val options = registry.active()?.state?.value?.options
            ?: registry.configProbe().lastOptions()
        if (registry.newSession(options) == null) atCapacity()
        else attachComposer(registry.activeKey.value)
    }

    /** 已在跑就直接切过去（不重启进程），否则新起一个并 --resume 续上 */
    fun openSession(id: String) {
        val existing = drafts.load(id)
        if (drafts.hasCarry() && !existing.isEmpty) {
            _notice.value = context.getString(R.string.vm_draft_conflict)
            return
        }
        if (registry.openSession(id) == null) {
            atCapacity()
            return
        }
        if (drafts.hasCarry()) drafts.consumeCarry(id)
        drafts.rememberActive(id)
        _composerDraft.value = existing
    }

    /** 仅切换活跃会话 */
    fun switchTo(key: String) {
        registry.switchTo(key)
        attachComposer(key)
    }

    /** 停掉某个会话的进程并从注册表移除 */
    fun closeSession(key: String) = registry.closeSession(key)

    private fun atCapacity() {
        _notice.value = context.getString(
            R.string.vm_max_sessions,
            ClaudeCodeSessionRegistry.MAX_CONCURRENT,
        )
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            registry.closeSession(id)
            (registry.active() ?: registry.configProbe()).deleteSession(id)
            drafts.forget(id)
            sessionMeta.forget(id)
            if (registry.activeKey.value == id || session.value.sessionId == id) {
                _composerDraft.value = ComposerDraft.Empty
            }
            refreshSessions()
        }
    }

    fun pinSession(id: String, pinned: Boolean) = sessionMeta.update(id) { it.copy(pinned = pinned) }

    fun setSessionCategory(id: String, category: String?) =
        sessionMeta.update(id) { it.copy(category = category?.trim()?.takeIf { name -> name.isNotBlank() }) }

    fun sessionCategories(): List<String> = sessionMeta.categories()

    fun saveComposerDraft(sessionId: String?, draft: ComposerDraft) {
        val id = sessionId ?: return
        if (id == registry.activeKey.value) _composerDraft.value = draft
        drafts.save(id, draft)
    }

    /** 进后台 / 清进程前拍一张。空框会把指针顺延到下次打开的第一个会话。 */
    fun snapshotComposerOnStop(sessionId: String?, draft: ComposerDraft) {
        val id = sessionId ?: return
        if (id == registry.activeKey.value) _composerDraft.value = draft
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

    fun renameSession(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        sessionMeta.update(id) { it.copy(title = trimmed) }
        if (registry.activeKey.value == id) onActive { it.renameSession(trimmed) }
    }

    // --- 会话导出 / 导入（搬 CLI 的 transcript JSONL，规则见 ClaudeCodeSessionTransfer） ---

    sealed interface SessionImportOutcome {
        data class Imported(val sessionId: String, val cwd: String) : SessionImportOutcome
        data class Rejected(val reason: SessionImportReject, val line: Int) : SessionImportOutcome
        /** 同 id 的会话进程还活着：CLI 正往那份文件里追加，这时换掉它两边都会坏 */
        data object Running : SessionImportOutcome
        data object Failed : SessionImportOutcome
    }

    private val sessionTransfer = ClaudeCodeSessionTransfer()

    /** 撞上已有同 id 会话、等用户选覆盖还是取消的那一份。非空时界面弹确认 */
    private var stagedImport: ClaudeCodeSessionTransfer.Staged.Ready? = null
    /** 暂存那一份改写进去的工作目录；确认覆盖时报给用户的得是它，而不是此刻的 lastOptions */
    private var stagedCwd: String = ClaudeCodeManager.DEFAULT_CWD
    private val _pendingImportId = MutableStateFlow<String?>(null)
    val pendingImportId: StateFlow<String?> = _pendingImportId.asStateFlow()

    private suspend fun transcriptLinuxDir() =
        (registry.active() ?: registry.configProbe()).transcriptLinuxDir()

    fun exportSession(id: String, open: () -> OutputStream?, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val linuxDir = transcriptLinuxDir()
            onDone(linuxDir != null && sessionTransfer.export(linuxDir, id, open))
        }
    }

    /**
     * 导入到「当前工作目录」——和新建会话用的是同一个（[lastOptions]），也就是导入的会话
     * 第一次打开、没有自己的偏好时 `--resume` 之后 `set_cwd` 过去的那个目录。
     */
    fun importSession(open: () -> InputStream?, onDone: (SessionImportOutcome) -> Unit) {
        viewModelScope.launch {
            stagedImport?.let { sessionTransfer.discard(it) }
            stagedImport = null
            _pendingImportId.value = null
            val linuxDir = transcriptLinuxDir() ?: return@launch onDone(SessionImportOutcome.Failed)
            val cwd = CwdPath.normalize(lastOptions().cwd)
            when (val staged = sessionTransfer.stage(linuxDir, cwd, open)) {
                ClaudeCodeSessionTransfer.Staged.Failed -> onDone(SessionImportOutcome.Failed)
                is ClaudeCodeSessionTransfer.Staged.Rejected ->
                    onDone(SessionImportOutcome.Rejected(staged.reason, staged.line))
                is ClaudeCodeSessionTransfer.Staged.Ready -> when {
                    isLiveSession(staged.sessionId) -> {
                        sessionTransfer.discard(staged)
                        onDone(SessionImportOutcome.Running)
                    }
                    staged.existing != null -> {
                        stagedImport = staged
                        stagedCwd = cwd
                        _pendingImportId.value = staged.sessionId
                    }
                    else -> commitImport(staged, cwd, onDone)
                }
            }
        }
    }

    /** 冲突确认的两个出口。[overwrite] = false 时丢掉暂存，旧会话原封不动 */
    fun resolveImport(overwrite: Boolean, onDone: (SessionImportOutcome) -> Unit) {
        val staged = stagedImport ?: return
        stagedImport = null
        _pendingImportId.value = null
        viewModelScope.launch {
            when {
                !overwrite -> sessionTransfer.discard(staged)
                // 对话框开着的这段时间里用户可能已经把它打开了
                isLiveSession(staged.sessionId) -> {
                    sessionTransfer.discard(staged)
                    onDone(SessionImportOutcome.Running)
                }
                else -> commitImport(staged, stagedCwd, onDone)
            }
        }
    }

    private suspend fun commitImport(
        staged: ClaudeCodeSessionTransfer.Staged.Ready,
        cwd: String,
        onDone: (SessionImportOutcome) -> Unit,
    ) {
        if (!sessionTransfer.commit(staged)) return onDone(SessionImportOutcome.Failed)
        // 等列表真的换上新的再报成功：提示出来时抽屉里就该已经有这一行了
        reloadSessions()
        onDone(SessionImportOutcome.Imported(staged.sessionId, cwd))
    }

    private fun isLiveSession(id: String) = registry.get(id)?.isLive == true

    /**
     * 把手机上的文件导入沙箱，返回 Claude Code 能直接用的路径。
     *
     * 工作区的 files/ 目录被 bind-mount 到沙箱里的 /workspace（ProotShellRunner 的
     * `-b filesDir:/workspace`），而 CLI 的 cwd 就是 /workspace —— 所以导入之后
     * 直接把 /workspace/<文件名> 写进提示词，Claude Code 用 Read 就能打开。
     *
     * 重名由 WorkspaceFileSystem.resolveConflict 处理，所以要用返回的实际文件名。
     */
    fun importFile(fileName: String, inputStream: InputStream, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val entry = runCatching {
                workspaceRepository.importFile(
                    id = workspaceId,
                    area = WorkspaceStorageArea.FILES,
                    destinationPath = "",
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.getOrNull()
            onDone(entry?.let { "/workspace/${it.path.trimStart('/')}" })
        }
    }

    /**
     * 移除附件 = 真的把文件从工作区删掉。
     * 只从输入框里抹掉一行路径是没用的：文件已经躺在 /workspace 里，
     * Claude Code 照样 ls 得到、读得到。
     */
    fun deleteWorkspaceFile(guestPath: String) {
        val rel = guestPath.removePrefix("/workspace").trimStart('/')
        if (rel.isBlank()) return
        viewModelScope.launch {
            runCatching {
                workspaceRepository.deleteFile(
                    id = workspaceId,
                    area = WorkspaceStorageArea.FILES,
                    path = rel,
                    recursive = false,
                )
            }
        }
    }

    // --- 会话内控制（对应 Desktop 底栏），只作用于活跃会话 ---
    fun send(text: String, images: List<ClaudeCodeImage> = emptyList()) =
        onActive { it.send(text, images) }

    /** `@` 提及的文件补全。没有活跃会话时给空表，输入框自然不弹候选。 */
    suspend fun searchFiles(query: String): List<String> =
        registry.active()?.searchFiles(query).orEmpty()

    /**
     * 相册图片 → image content block。
     *
     * 缩到长边 [IMAGE_MAX_EDGE]：Anthropic 对超过这个尺寸的图会自己缩放，
     * 与其上传原图白花流量和 token，不如在本地缩好再发。统一转 JPEG ——
     * 手机拍的照片本来就是 JPEG，PNG 编同一张图能大好几倍。
     */
    suspend fun loadImage(uri: Uri): ClaudeCodeImage? = withContext(Dispatchers.IO) {
        val bitmap = ImageUtils.loadOptimizedBitmap(context, uri, IMAGE_MAX_EDGE)
            ?: return@withContext null
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, out)
            out.toByteArray()
        }
        bitmap.recycle()
        ClaudeCodeImage(
            mediaType = "image/jpeg",
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

    fun answerPermission(
        allow: Boolean,
        message: String? = null,
        suggestion: dev.min.code.core.claudecode.PermissionSuggestion? = null,
    ) = onActive { it.answerPermission(allow, message, suggestion) }

    /** 应答 AskUserQuestion：把选项填回 updatedInput.answers。空 map = 跳过。 */
    fun answerQuestions(answers: Map<String, String>) = onActive { it.answerQuestions(answers) }

    fun interrupt() = onActive { it.interrupt() }
    fun runShell(command: String) = onActive { it.runShell(command) }
    fun stop() = onActive { it.stopSession() }
    /**
     * 切模型。[asDefault] 对齐 CLI `/model` 的 Enter / `s`：
     * true = 本会话热切 + 写 settings.json 的 `model`（新会话、终端页的 `claude` 都跟着变）；
     * false = 只改这个会话。`/model <名字>` 这种带参数的写法在 CLI 里等于 Enter，也走 true。
     */
    fun setModel(model: String?, asDefault: Boolean = true) {
        onActive { it.setModel(model, asDefault) }
        if (asDefault) viewModelScope.launch { configStore.saveDefaultModel(model) }
    }
    fun setPermissionMode(mode: ClaudeCodePermissionMode) = onActive { it.setPermissionMode(mode) }
    fun applyEffort(effort: String?, ultracode: Boolean = false) = onActive { it.applyEffort(effort, ultracode) }

    /** 提示缓存 TTL（5m / 1h）。和 effort 一样要重启 CLI 续接会话 */
    fun setPromptCacheTtl(ttl: String) = onActive { it.setPromptCacheTtl(ttl) }

    fun refreshModels() = onActive { it.refreshModels() }
    fun refreshPlan() = onActive { it.refreshPlan() }

    fun refreshUsage() {
        // 跨过午夜之后那个数字应该自己归零，而不是等下一次花钱才刷新
        costLedger.refresh()
        onActive { it.refreshUsage() }
    }
    fun setCwd(path: String) = onActive { it.setCwd(path) }

    /** 新建会话时的默认配置（含上次选的工作目录） */
    fun lastOptions(): ClaudeCodeManager.SessionOptions =
        registry.active()?.state?.value?.options ?: registry.configProbe().lastOptions()

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

    /** 撤销一次编辑类工具造成的文件改动（只还原文件，对话历史不变） */
    fun revertToolCall(toolUseId: String) = onActive { it.revertToolCall(toolUseId) }

    // 不在 onCleared 里杀会话：ClaudeCodeManager 是 Koin single，
    // 会话本就该比页面活得久（切到别的页面、转屏都会重建 VM）。
    // 结束会话由用户在标题栏显式点「停止」。

    // -----------------------------------------------------------------------
    // 交互式斜杠命令（/mcp、/agents、/memory、/config、/permissions）
    //
    // 这些命令在 TUI 里是交互式编辑器，无头模式下渲染不出来。它们改的都是 Rootfs 里的
    // 配置文件，所以这里直接透到 ClaudeCodeConfigStore —— 不经过 CLI，也就不受
    // 会话是否在跑的影响（还没开会话时也能改）。
    // -----------------------------------------------------------------------

    suspend fun loadMcpServers() = configStore.loadMcpServers()

    suspend fun saveMcpServer(server: ClaudeCodeConfigStore.McpServer, originalName: String?) =
        configStore.saveMcpServer(server, originalName)

    suspend fun deleteMcpServer(name: String) = configStore.deleteMcpServer(name)

    suspend fun listAgents() = configStore.listAgents()

    suspend fun saveAgent(agent: ClaudeCodeConfigStore.AgentDefinition) = configStore.saveAgent(agent)

    suspend fun deleteAgent(agent: ClaudeCodeConfigStore.AgentDefinition) = configStore.deleteAgent(agent)

    suspend fun loadMemory(userScope: Boolean): String = configStore.loadMemory(memoryScope(userScope))

    suspend fun saveMemory(userScope: Boolean, text: String) =
        configStore.saveMemory(memoryScope(userScope), text)

    private fun memoryScope(userScope: Boolean) = if (userScope) {
        ClaudeCodeConfigStore.MemoryScope.USER
    } else {
        ClaudeCodeConfigStore.MemoryScope.PROJECT
    }

    /** 工作目录里有没有项目级 CLAUDE.md（`/init` 按钮该不该显眼） */
    suspend fun hasProjectMemory(): Boolean = configStore.loadMemory(ClaudeCodeConfigStore.MemoryScope.PROJECT).isNotBlank()

    /** `/init` 是 prompt 型内置命令，无头模式下当普通消息发过去就会执行 */
    fun runInit() = send("/init")

    /** settings.json 里那几个标量字段，取不到就给空串（UI 是纯文本框） */
    suspend fun loadSettings(): Map<String, String> {
        val settings = configStore.loadSettings() ?: return emptyMap()
        return listOf("outputStyle", "statusLine", "model", "maxEffortLevel")
            .mapNotNull { key -> settings[key].asStringOrNull()?.let { key to it } }
            .toMap()
    }

    /**
     * 保存 settings.json 里的标量字段。**空串 = 删除该 key**，
     * 而不是写一个空值 —— CLI 对 `"statusLine": ""` 的处理是当成一个空命令去跑。
     *
     * [maxEffortLevel] 合法值是 low…max；空串删键；非法值保持文件原样不动那一项。
     */
    suspend fun saveSimpleSettings(
        outputStyle: String,
        statusLine: String,
        maxEffortLevel: String = "",
    ) =
        configStore.updateSettings { draft ->
            if (outputStyle.isBlank()) draft.remove("outputStyle")
            else draft["outputStyle"] = JsonPrimitive(outputStyle.trim())
            if (statusLine.isBlank()) draft.remove("statusLine")
            else draft["statusLine"] = JsonPrimitive(statusLine.trim())
            val effortCap = maxEffortLevel.trim()
            when {
                effortCap.isEmpty() -> draft.remove("maxEffortLevel")
                effortCap in ClaudeCodeManager.EFFORT_LEVELS ->
                    draft["maxEffortLevel"] = JsonPrimitive(effortCap)
                // 非法：不动现有键，避免把一个好的上限写成 CLI 会忽略的垃圾
            }
        }

    suspend fun loadPermissionRules(bucket: String) = configStore.loadPermissionRules(bucket)

    suspend fun savePermissionRules(bucket: String, rules: List<String>) =
        configStore.savePermissionRules(bucket, rules)

    private companion object {
        /**
         * 图片缩到的长边像素。Anthropic 对超过 1568px 长边的图会在服务端自己缩，
         * 上传原图只是白花流量 —— 而手机上随手一张截图就是 1080×2400。
         */
        const val IMAGE_MAX_EDGE = 1568

        const val IMAGE_JPEG_QUALITY = 85
    }
}
