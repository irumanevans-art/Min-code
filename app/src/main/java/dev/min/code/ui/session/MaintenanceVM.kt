package dev.min.code.ui.session

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeCodeInstaller
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.settings.SettingsStore

/**
 * 环境是否就绪（安装向导判定）+ 装好之后的维护面板（CLI 更新、apt、Node）。
 *
 * 从原来的 ClaudeCodeVM 拆出来（M2-B B6a，纯搬家）。两者都只是 [ClaudeCodeInstaller] 的读写，
 * 而维护跑完要紧接着刷新就绪状态（[runMaintenance] 末尾），放在一个类里这一步不用跨 VM。
 * 设计稿 §3.10 把「环境就绪」归 SessionVM（B7 改成按引擎的 availability 再汇总），这一步先不动类型，
 * 就绪状态留在这里；「是否已连接」不依赖安装器，仍在 [SessionVM.connected]。
 */
class MaintenanceVM(
    private val context: Application,
    private val registry: ClaudeCodeSessionRegistry,
    private val installer: ClaudeCodeInstaller,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()

    /**
     * 环境是否就绪。装/修的过程在 SetupVM 里，这里只判断"能不能开会话"；
     * rootfs 和 CLI 缺一样，页面就把向导嵌进来。
     *
     * 连接（供应商 token）**不算**在里面：向导只管装环境，没连接时照样进启动面板，
     * 由 [SessionVM.connected] 驱动那里的 [ConnectPrompt] 指路。
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
     * 每次 [refresh] 读完状态后给一个信号，页面接到就让 [SessionListVM] 重扫磁盘会话。
     * 原来 refresh() 末尾直接调 refreshSessions()；拆开后列表在另一个 VM 里，
     * 用 Channel 而不是 SharedFlow：页面还没开始收（VM 刚建、页面第一帧还没组合）时信号不能丢。
     */
    private val _sessionsStale = Channel<Unit>(Channel.CONFLATED)
    val sessionsStale: Flow<Unit> = _sessionsStale.receiveAsFlow()

    init {
        refresh()
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
            _sessionsStale.trySend(Unit)
        }
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
        val live = registry.liveSessions.value.count { it.isLive }
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
}
