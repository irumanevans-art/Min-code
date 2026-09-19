package dev.min.code.ui.codex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.codex.CodexDecision
import dev.min.code.core.codex.CodexRuntime
import dev.min.code.core.codex.CodexSessionSummary
import dev.min.code.core.codex.listCodexSessions
import dev.min.code.core.codex.readCodexSessionItems
import dev.min.code.core.settings.CodexProfile
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
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

    /** 磁盘上那些旧会话，新的在前。空列表就是「还没聊过」 */
    private val _sessions = MutableStateFlow<List<CodexSessionSummary>>(emptyList())
    val sessions: StateFlow<List<CodexSessionSummary>> = _sessions.asStateFlow()

    val profile: StateFlow<CodexProfile> = settingsStore.settings
        .map { it.activeCodexProfile ?: CodexProfile(id = DEFAULT_PROFILE_ID) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CodexProfile(id = DEFAULT_PROFILE_ID),
        )

    init {
        refresh()
        restoreLatest()
    }

    fun refresh() {
        viewModelScope.launch { runtime.refresh() }
    }

    fun install() {
        viewModelScope.launch { runtime.install() }
    }

    /**
     * 把上一条会话摆回屏幕。**只读文件，不起进程** ——
     * 一进页面就拉起一个 app-server 太霸道，何况用户可能只是来看看上次说了什么。
     */
    private fun restoreLatest() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = runCatching { listCodexSessions(runtime.codexHome()) }
                .getOrDefault(emptyList())
            _sessions.value = all
            val latest = all.firstOrNull() ?: return@launch
            manager.restore(
                items = readCodexSessionItems(latest.file),
                threadId = latest.threadId,
            )
        }
    }

    fun refreshSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            _sessions.value = runCatching { listCodexSessions(runtime.codexHome()) }
                .getOrDefault(emptyList())
        }
    }

    /**
     * 翻到另一条旧会话：把它的内容摆出来，**不自动接上** ——
     * 点开看看和继续跑是两件事，后者花钱。要接着聊再按「继续」。
     *
     * 只在没有会话在跑时可达（入口那边保证），所以不必考虑覆盖实时状态。
     */
    fun openSession(summary: CodexSessionSummary) {
        viewModelScope.launch(Dispatchers.IO) {
            manager.restore(
                items = readCodexSessionItems(summary.file),
                threadId = summary.threadId,
            )
        }
    }

    /**
     * 继续上一条会话：带着 threadId 走 `thread/resume`，Codex 那边的上下文还在，
     * 屏幕上的历史也留着。没有历史时它就是「新开一条」。
     */
    fun start() {
        viewModelScope.launch {
            runtime.prepare()
            manager.start(
                options = CodexAppServerManager.Options(),
                resumeThreadId = manager.state.value.threadId,
            )
        }
    }

    /** 另起一条。清掉屏幕上的历史，也不带 threadId —— 磁盘上那条不会被动 */
    fun startNew() {
        viewModelScope.launch {
            runtime.prepare()
            manager.start(options = CodexAppServerManager.Options(), resumeThreadId = null)
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
