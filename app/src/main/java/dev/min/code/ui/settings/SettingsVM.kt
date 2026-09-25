package dev.min.code.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.min.code.AppScope
import dev.min.code.BuildConfig
import dev.min.code.core.claudecode.ClaudeSubscription
import dev.min.code.core.device.AgentDisplaySession
import dev.min.code.core.settings.AppLanguage
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.SkinStyle
import dev.min.code.core.settings.ThemeMode
import dev.min.code.privileged.PrivilegedClient
import dev.min.code.privileged.PrivilegedStarter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsVM(
    private val store: SettingsStore,
    private val privilegedClient: PrivilegedClient,
    private val privilegedStarter: PrivilegedStarter,
    private val agentDisplay: AgentDisplaySession,
    private val appScope: AppScope,
    private val subscription: ClaudeSubscription,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val privilegedState: StateFlow<PrivilegedClient.State> = privilegedClient.state
    val agentDisplayActive: StateFlow<AgentDisplaySession.Active?> = agentDisplay.active

    val lastPrivilegedError: String? get() = privilegedClient.lastError

    /** 纯流量冷启动需要「附近的设备」才能开仅本地热点 */
    fun needsNearbyWifiPermission(): Boolean = privilegedStarter.needsNearbyWifiPermission()

    /**
     * 用户明确放弃那串打不开的密文，腾位置重填。
     *
     * 连接表的增删改查搬去了 `ProvidersVM`，但这一条留在设置页：它不是「管某一条
     * 供应商」，而是整份凭据存储的状态 —— 密文打不开时供应商页整页是只读的，
     * 那个页面自己没法把自己解开。
     */
    fun discardUnreadableCredentials() = viewModelScope.launch { store.discardUnreadableCredentials() }

    private val _claudeAuthBusy = MutableStateFlow(false)

    /** 正在换路 / 登出：设置页据此把那两个控件压成忙碌，免得连点两下 */
    val claudeAuthBusy: StateFlow<Boolean> = _claudeAuthBusy.asStateFlow()

    /**
     * 换回供应商这条路（不登出，订阅的登录留着）。
     *
     * 走 [AppScope]：换路是「落盘 → 投影 → 路由 → 重起空闲会话」一串，半路被离开页面取消掉
     * 会停在一个不一致的地方
     */
    fun useClaudeProvider() = runClaudeAuth { subscription.useProvider() }

    private val _claudeLogoutFailed = MutableStateFlow(false)

    /**
     * 上一次「退出订阅登录」里 CLI 的登出没成（CLI 不在或退出码非 0）。那时仍已切回供应商，
     * 但凭证可能还在 rootfs 里 —— 设置页据此常驻一条提示，直到用户点掉或再换一次路。
     * 不用 toast：这句话要读完，而且说的是一件还没了结的事。
     */
    val claudeLogoutFailed: StateFlow<Boolean> = _claudeLogoutFailed.asStateFlow()

    /** 换路之后正忙、仍在用旧连接的会话。和登录面板读的是同一份，见 [ClaudeSubscription.staleSessions] */
    val claudeStaleSessions: StateFlow<List<String>> = subscription.staleSessions

    fun restartClaudeStaleSessions() = subscription.restartStaleSessions()

    fun dismissClaudeStaleSessions() = subscription.dismissStaleSessions()

    fun dismissClaudeLogoutFailed() {
        _claudeLogoutFailed.value = false
    }

    /**
     * 退出订阅登录：CLI 自己的 `auth logout`，然后切回供应商（登出没成也切，理由见 [ClaudeSubscription.logout]）。
     * 供应商表不动。CLI 登出成功时 [onSignedOut] 在主线程上回调；没成时改由 [claudeLogoutFailed] 常驻说明
     */
    fun logoutClaudeSubscription(onSignedOut: () -> Unit) = runClaudeAuth {
        if (subscription.logout()) onSignedOut() else _claudeLogoutFailed.value = true
    }

    private fun runClaudeAuth(block: suspend () -> Unit) {
        if (_claudeAuthBusy.value) return
        _claudeAuthBusy.value = true
        // 又换了一次路：上一次登出失败的那句话已经不是眼下的状况了
        _claudeLogoutFailed.value = false
        appScope.launch {
            try {
                block()
            } finally {
                _claudeAuthBusy.value = false
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { store.setThemeMode(mode) }
    fun setSkin(style: SkinStyle) = viewModelScope.launch { store.setSkin(style) }
    fun setAppLanguage(language: AppLanguage) = viewModelScope.launch { store.setAppLanguage(language) }
    fun setShareDeviceStorage(enabled: Boolean) = viewModelScope.launch { store.setShareDeviceStorage(enabled) }
    fun setControlDevice(enabled: Boolean) = viewModelScope.launch {
        store.setControlDevice(enabled)
        // 记成「这一版已经引导过」：开开关时设置页会自己跳无障碍，别再弹一次更新提示
        if (enabled) store.setA11yPromptVersion(BuildConfig.VERSION_CODE)
    }

    fun setDeviceWriteAllowlist(packages: Set<String>) =
        viewModelScope.launch { store.setDeviceWriteAllowlist(packages) }

    /** 复制给电脑 `adb shell` 用的拉起命令；同时进入 Waiting 态 */
    fun privilegedLaunchCommand(): String {
        privilegedStarter.markWaitingForExternalStart()
        return privilegedStarter.launchCommand()
    }

    /**
     * 用配对码把这台设备交给 adbd 授权（一次性）。成功后顺势拉起壳进程。
     * 失败时 [privilegedState] 进 Failed，设置页读 [lastPrivilegedError]。
     */
    fun pairAndStartViaWireless(code: String, port: Int) = appScope.launch {
        privilegedStarter.pairAndStart(code, port)
    }

    /**
     * 已配过对时直接扫端口并拉起。失败（含未配对）时 [privilegedState] 进 Failed，
     * 设置页读 [lastPrivilegedError]。
     *
     * 走 [AppScope] 而不是 viewModelScope：切网、离开设置页都会拆掉 ViewModel，
     * 那会把正在扫端口 / 等壳进程的 job 取消掉，红字变成毫无意义的 "Job was cancelled"。
     */
    fun startPrivilegedViaWireless() = appScope.launch {
        privilegedStarter.startViaLocalAdb()
    }

    fun startAgentDisplaySession() = viewModelScope.launch {
        runCatching { agentDisplay.start() }
            .onFailure { privilegedClient.markFailed(it.message ?: "start session failed") }
    }

    fun stopAgentDisplaySession() {
        agentDisplay.stop()
    }
}
