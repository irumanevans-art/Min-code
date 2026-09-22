package dev.min.code.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.min.code.core.device.AgentDisplaySession
import dev.min.code.core.settings.AppLanguage
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.SkinStyle
import dev.min.code.core.settings.ThemeMode
import dev.min.code.privileged.PrivilegedClient
import dev.min.code.privileged.PrivilegedStarter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsVM(
    private val store: SettingsStore,
    private val privilegedClient: PrivilegedClient,
    private val privilegedStarter: PrivilegedStarter,
    private val agentDisplay: AgentDisplaySession,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val privilegedState: StateFlow<PrivilegedClient.State> = privilegedClient.state
    val agentDisplayActive: StateFlow<AgentDisplaySession.Active?> = agentDisplay.active

    val lastPrivilegedError: String? get() = privilegedClient.lastError

    /**
     * 用户明确放弃那串打不开的密文，腾位置重填。
     *
     * 连接表的增删改查搬去了 `ProvidersVM`，但这一条留在设置页：它不是「管某一条
     * 供应商」，而是整份凭据存储的状态 —— 密文打不开时供应商页整页是只读的，
     * 那个页面自己没法把自己解开。
     */
    fun discardUnreadableCredentials() = viewModelScope.launch { store.discardUnreadableCredentials() }

    fun setUseNpmMirror(enabled: Boolean) = viewModelScope.launch { store.setUseNpmMirror(enabled) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { store.setThemeMode(mode) }
    fun setSkin(style: SkinStyle) = viewModelScope.launch { store.setSkin(style) }
    fun setAppLanguage(language: AppLanguage) = viewModelScope.launch { store.setAppLanguage(language) }
    fun setShareDeviceStorage(enabled: Boolean) = viewModelScope.launch { store.setShareDeviceStorage(enabled) }
    fun setControlDevice(enabled: Boolean) = viewModelScope.launch { store.setControlDevice(enabled) }

    /** 复制给电脑 `adb shell` 用的拉起命令；同时进入 Waiting 态 */
    fun privilegedLaunchCommand(): String {
        privilegedStarter.markWaitingForExternalStart()
        return privilegedStarter.launchCommand()
    }

    /**
     * 尝试发现本机无线调试端口并拉起。失败时 [privilegedState] 进 Failed，
     * 设置页读 [lastPrivilegedError]。
     */
    fun startPrivilegedViaWireless() = viewModelScope.launch {
        privilegedClient.markStarting()
        val port = privilegedStarter.discoverConnectPort()
        if (port == null) {
            privilegedClient.markFailed(
                "没扫到本机无线调试端口。请打开「开发者选项 → 无线调试」后重试，或复制 adb 命令到电脑执行。"
            )
            return@launch
        }
        privilegedStarter.startViaLocalAdb(port)
    }

    fun startAgentDisplaySession() = viewModelScope.launch {
        runCatching { agentDisplay.start() }
            .onFailure { privilegedClient.markFailed(it.message ?: "start session failed") }
    }

    fun stopAgentDisplaySession() {
        agentDisplay.stop()
    }
}
