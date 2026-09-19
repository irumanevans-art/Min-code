package dev.min.code.ui.codex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.codex.CodexDecision
import dev.min.code.core.codex.CodexRuntime
import dev.min.code.core.settings.CodexProfile
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Codex 页面的 ViewModel。
 *
 * **不持有进程**：[manager] 是 DI 里的单例，页面只是观察者。以前 manager 是在这里
 * new 出来的，于是转个屏 / 退出页面就 onCleared，正跑着的一轮直接被杀。
 * 同理 [onCleared] 里什么都不关。
 */
class CodexVM(
    private val runtime: CodexRuntime,
    private val manager: CodexAppServerManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val runtimeStatus: StateFlow<CodexRuntime.Status> = runtime.status
    val session: StateFlow<CodexAppServerManager.State> = manager.state

    val profile: StateFlow<CodexProfile> = settingsStore.settings
        .map { it.activeCodexProfile ?: CodexProfile(id = DEFAULT_PROFILE_ID) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CodexProfile(id = DEFAULT_PROFILE_ID),
        )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { runtime.refresh() }
    }

    fun install() {
        viewModelScope.launch { runtime.install() }
    }

    /** 先把路由配置落到 guest 的 config.toml，再起进程 —— 顺序反了会用上一次的中转地址 */
    fun start() {
        viewModelScope.launch {
            runtime.prepare()
            manager.start(CodexAppServerManager.Options())
        }
    }

    fun stop() = manager.stop()

    fun send(text: String): Boolean = manager.sendTurn(text)

    fun answerApproval(decision: CodexDecision): Boolean = manager.answerApproval(decision)

    fun saveProfile(profile: CodexProfile) {
        viewModelScope.launch {
            settingsStore.setCodexProfile(profile)
            runtime.refresh()
        }
    }

    companion object {
        const val DEFAULT_PROFILE_ID = "default"
    }
}
