package dev.min.code.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.min.code.core.claudecode.ClaudeCodeInstaller
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.RootfsSources
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.SettingsStore
import dev.min.code.ui.terminal.WorkspaceTerminalSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.WorkspaceShellStatus

/**
 * 安装向导的状态机：连接（token + 中转地址）→ Linux 环境 → Claude Code CLI。
 *
 * 三步都是幂等的，向导可以在任一步被打断后重新进入：每次 [refresh] 都从磁盘和设置
 * 重新判定"走到哪了"，不记"我以为的进度"。
 */
class SetupVM(
    private val settingsStore: SettingsStore,
    private val workspaceRepository: WorkspaceRepository,
    private val installer: ClaudeCodeInstaller,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
) : ViewModel() {

    enum class Step { CONNECTION, ROOTFS, CLI, DONE }

    data class State(
        val loading: Boolean = true,
        val settings: AppSettings = AppSettings(),
        val rootfsReady: Boolean = false,
        val rootfsBroken: Boolean = false,
        val nodeInstalled: Boolean = false,
        val cliInstalled: Boolean = false,
        val cliIncomplete: Boolean = false,
        val cliVersion: String? = null,
        /** 正在跑的那一步；null = 空闲 */
        val busy: Step? = null,
        val detail: String = "",
        /** 0..1；null = 不确定进度 */
        val progress: Float? = null,
        val error: String? = null,
    ) {
        val step: Step
            get() = when {
                settings.token.isBlank() -> Step.CONNECTION
                !rootfsReady -> Step.ROOTFS
                !cliInstalled -> Step.CLI
                else -> Step.DONE
            }
        val ready: Boolean get() = !loading && step == Step.DONE
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val settings = settingsStore.current()
            val workspace = workspaceRepository.getById(workspaceId)
            val cli = runCatching { installer.status(workspaceId) }.getOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    settings = settings,
                    rootfsReady = workspace?.shellStatus == WorkspaceShellStatus.READY.name,
                    rootfsBroken = workspace?.shellStatus == WorkspaceShellStatus.BROKEN.name,
                    nodeInstalled = cli?.nodeInstalled == true,
                    cliInstalled = cli?.claudeInstalled == true,
                    cliIncomplete = cli?.cliIncomplete == true,
                    cliVersion = cli?.cliVersion,
                )
            }
        }
    }

    fun saveConnection(token: String, baseUrl: String) {
        viewModelScope.launch {
            settingsStore.setToken(token)
            settingsStore.setBaseUrl(baseUrl.ifBlank { AppSettings.DEFAULT_BASE_URL })
            refresh()
        }
    }

    /**
     * 装 rootfs。按 [RootfsSources.candidates] 的顺序试，一个源失败换下一个 ——
     * 国内直连 cdimage.ubuntu.com 经常连不上，之前只有一个地址时首次安装大量失败。
     * 传了 [url] 就只用它（用户手填的地址）。
     */
    fun installRootfs(url: String? = null) {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = Step.ROOTFS, error = null, progress = null, detail = "准备下载…") }
            terminalSessionManager.closeWorkspace(workspaceId)
            val sources = RootfsSources.urlsFor(url)
            var lastError: Throwable? = null
            for ((index, source) in sources.withIndex()) {
                val label = if (sources.size == 1) "" else if (index == 0) "（官方源）" else "（镜像源）"
                try {
                    workspaceRepository.installRootfs(workspaceId, source) { p -> onRootfsProgress(p, label) }
                    lastError = null
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            _state.update {
                it.copy(
                    busy = null,
                    detail = "",
                    progress = null,
                    error = lastError?.let { e -> "Linux 环境安装失败：${e.message ?: e}" },
                )
            }
            refresh()
        }
    }

    private fun onRootfsProgress(p: RootfsInstallProgress, label: String) {
        val total = p.totalBytes
        val fraction = if (total != null && total > 0) (p.bytesRead.toFloat() / total).coerceIn(0f, 1f) else null
        val detail = when (p.stage) {
            me.rerere.workspace.RootfsInstallStage.DOWNLOADING ->
                "下载 Ubuntu 基础镜像$label ${p.bytesRead / MB} MB" + (total?.let { " / ${it / MB} MB" } ?: "")
            me.rerere.workspace.RootfsInstallStage.EXTRACTING ->
                "解压 ${p.entriesExtracted} 个文件" + (p.currentEntry?.let { " · $it" } ?: "")
            me.rerere.workspace.RootfsInstallStage.INSTALLED -> "安装完成"
        }
        _state.update { it.copy(detail = detail, progress = fraction) }
    }

    /** 装 Node + CLI；wrapper 在但二进制缺失时也走这里（安装器只补缺的部分） */
    fun installCli(useNpmMirror: Boolean) {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            settingsStore.setUseNpmMirror(useNpmMirror)
            _state.update { it.copy(busy = Step.CLI, error = null, progress = null, detail = "") }
            runCatching {
                installer.install(workspaceId, useNpmMirror = useNpmMirror) { s ->
                    when (s) {
                        is ClaudeCodeInstaller.InstallState.Downloading ->
                            _state.update { it.copy(detail = s.detail, progress = s.progress) }
                        is ClaudeCodeInstaller.InstallState.Running ->
                            _state.update { it.copy(detail = s.detail, progress = null) }
                        is ClaudeCodeInstaller.InstallState.Done ->
                            _state.update { it.copy(cliVersion = s.cliVersion ?: it.cliVersion) }
                        is ClaudeCodeInstaller.InstallState.Failed ->
                            _state.update { it.copy(error = s.message) }
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e.toString()) }
            }
            _state.update { it.copy(busy = null, detail = "", progress = null) }
            refresh()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
