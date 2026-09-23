package dev.min.code.ui.terminal

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.min.code.AppScope
import dev.min.code.core.relay.RelayController
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.shellCredentialEnv
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns workspace terminal sessions independently from the terminal page lifecycle.
 *
 * A page only attaches a [com.termux.view.TerminalView] to the selected tab. Navigating away
 * therefore keeps every shell and its screen buffer alive; a session is finished only when its
 * tab is explicitly closed (or the shell exits by itself).
 */
class WorkspaceTerminalSessionManager internal constructor(
    context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val relay: RelayController? = null,
) {
    private val appContext = context.applicationContext
    private val workspaceStates = MutableStateFlow<Map<String, WorkspaceTerminalTabsState>>(emptyMap())
    private val nextTabId = AtomicLong(1)
    private val creationJobs = mutableMapOf<String, Job>()

    /** 打开终端时要不要自动弹键盘（终端顶栏的开关）；点终端本身照样弹 */
    internal val autoShowKeyboard: Flow<Boolean> =
        settingsStore.settings.map { it.terminalAutoKeyboard }.distinctUntilChanged()

    internal fun setAutoShowKeyboard(enabled: Boolean) {
        appScope.launch { settingsStore.setTerminalAutoKeyboard(enabled) }
    }

    internal fun observeWorkspace(root: String): Flow<WorkspaceTerminalTabsState> =
        workspaceStates
            .map { states -> states[root] ?: WorkspaceTerminalTabsState() }
            .distinctUntilChanged()

    internal fun ensureSession(root: String) {
        launchCreateTab(root = root, onlyIfEmpty = true)
    }

    internal fun createTab(root: String) {
        launchCreateTab(root = root, onlyIfEmpty = false)
    }

    /**
     * 给每个还没有终端的 Claude 会话开一个页签，开在那个会话自己的工作目录里。
     *
     * 按 [SessionTab.key] 去重：会话终端浮层每次打开都会调一次，已经有页签的会话不能再开一个 ——
     * 那样来回开合几次就攒出一屏页签，每个都是一个真 shell 进程。
     * 已经关掉的页签不补：关掉是用户的决定。
     */
    internal fun ensureSessionTabs(root: String, sessions: List<SessionTab>) {
        if (sessions.isEmpty()) return
        launchCreateTab(root = root, onlyIfEmpty = false, sessions = sessions)
    }

    /** 一个要开终端的 Claude 会话：注册表的 key、它的工作目录、页签上写什么 */
    internal data class SessionTab(val key: String, val cwd: String?, val label: String)

    internal fun selectTab(root: String, tabId: Long) {
        updateState(root) { state ->
            if (state.tabs.none { it.id == tabId }) state else state.copy(selectedTabId = tabId)
        }
    }

    internal fun closeTab(root: String, tabId: Long) {
        var closedTab: WorkspaceTerminalTab? = null
        updateState(root) { state ->
            val closedIndex = state.tabs.indexOfFirst { it.id == tabId }
            if (closedIndex < 0) return@updateState state

            closedTab = state.tabs[closedIndex]
            val remainingTabs = state.tabs.filterNot { it.id == tabId }
            val selectedTabId = if (state.selectedTabId == tabId) {
                remainingTabs.getOrNull(closedIndex)?.id
                    ?: remainingTabs.getOrNull(closedIndex - 1)?.id
            } else {
                state.selectedTabId
            }
            state.copy(
                tabs = remainingTabs,
                selectedTabId = selectedTabId,
            )
        }

        // Remove it from observable state before finishing so the finish callback cannot put it
        // back into the UI while the selected TerminalView is being disposed.
        closedTab?.let { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }
    }

    /**
     * Stops all sessions owned by [root] before its rootfs is replaced or the workspace is deleted.
     */
    internal suspend fun closeWorkspace(root: String) = withContext(Dispatchers.Main.immediate) {
        // Wait for rootfs preparation to leave its IO section before callers delete or replace the
        // same files. CancellationException is deliberately rethrown by createTab().
        creationJobs[root]?.cancelAndJoin()

        val state = workspaceStates.getAndUpdate { states -> states - root }[root]
            ?: return@withContext
        state.tabs.forEach { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunning()
        }
    }

    /**
     * @param sessions 非空时按会话建页签（每个会话一个，已有的跳过）；为空时建一个普通页签。
     *   走同一个 [creationJobs] 登记，[closeWorkspace] 才能在删 rootfs 前把它等停。
     */
    private fun launchCreateTab(
        root: String,
        onlyIfEmpty: Boolean,
        sessions: List<SessionTab> = emptyList(),
    ) {
        val running = creationJobs[root]
        if (running != null) {
            // 创建在途时整批请求不能直接丢（这批 sessions 里可能有在途 job 不知道的会话）。
            // 挂到它尾巴上重跑：正常结束就再试一次；被 closeWorkspace 取消则丢弃 ——
            // rootfs 正在删除/替换，此时补建页签只会对着半棵 rootfs 起 shell。
            running.invokeOnCompletion { cause ->
                if (cause == null) launchCreateTab(root, onlyIfEmpty, sessions)
            }
            return
        }

        lateinit var job: Job
        job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (sessions.isEmpty()) {
                    createTab(root = root, onlyIfEmpty = onlyIfEmpty)
                } else {
                    // 已经有页签的会话不再开第二个：浮层每次打开都会调一次，
                    // 否则来回开合几次就攒出一屏页签，每个都是一个真 shell 进程
                    val known = currentState(root).tabs.mapNotNullTo(HashSet()) { it.sessionKey }
                    sessions.filter { it.key !in known }.forEach { session ->
                        createTab(
                            root = root,
                            onlyIfEmpty = false,
                            sessionKey = session.key,
                            cwd = session.cwd,
                            label = session.label,
                        )
                    }
                }
            } finally {
                creationJobs.remove(root, job)
            }
        }
        creationJobs[root] = job
        job.start()
    }

    private suspend fun createTab(
        root: String,
        onlyIfEmpty: Boolean,
        sessionKey: String? = null,
        cwd: String? = null,
        label: String? = null,
    ) = withContext(Dispatchers.Main.immediate) {
        val initialState = currentState(root)
        if (initialState.isCreating || (onlyIfEmpty && initialState.tabs.isNotEmpty())) {
            return@withContext
        }
        updateState(root) { it.copy(isCreating = true) }

        val prepared = if (initialState.readiness == WorkspaceTerminalReadiness.Ready) {
            true
        } else {
            try {
                withContext(Dispatchers.IO) {
                    if (!workspaceRootfsReady(appContext, root)) {
                        false
                    } else {
                        prepareWorkspaceTerminalSession(appContext, root)
                        true
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to prepare terminal for workspace $root", error)
                false
            }
        }

        if (!prepared) {
            updateState(root) {
                it.copy(
                    readiness = WorkspaceTerminalReadiness.NotInstalled,
                    isCreating = false,
                )
            }
            return@withContext
        }

        // 当前供应商的凭据。**开页签的这一刻读一次**，之后这个 shell 的环境就固化了 ——
        // 和会话进程是同一个道理，所以换供应商之后已开的页签仍然是旧的，新开的才是新的。
        // 解密要走 Keystore，别留在主线程上
        val credentials = withContext(Dispatchers.IO) {
            runCatching {
                val settings = settingsStore.current()
                // 方言不是原生时终端里的 claude 也走路由，和会话进程同一个入口。
                // 路由地址只在 App 活着时通 —— 终端页签本来也是
                val override = settings.activeProfile?.let { relay?.claudeBaseUrl(it) }
                shellCredentialEnv(settings, claudeBaseUrlOverride = override)
            }
                .onFailure { Log.w(TAG, "读取供应商凭据失败，终端将没有 key", it) }
                .getOrDefault(emptyMap())
        }

        val tabId = nextTabId.getAndIncrement()
        val tabNumber = currentState(root).nextTabNumber
        val client = WorkspaceTerminalSessionClient(appContext) {
            markFinished(root = root, tabId = tabId)
        }
        val session = runCatching {
            createWorkspaceTerminalSession(
                context = appContext,
                root = root,
                client = client,
                cwd = cwd,
                credentials = credentials,
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to create terminal for workspace $root", error)
        }.getOrNull()

        if (session == null) {
            updateState(root) { it.copy(isCreating = false) }
            return@withContext
        }

        val tab = WorkspaceTerminalTab(
            id = tabId,
            number = tabNumber,
            session = session,
            client = client,
            sessionKey = sessionKey,
            label = label,
        )
        updateState(root) { state ->
            state.copy(
                tabs = state.tabs + tab,
                selectedTabId = tab.id,
                readiness = WorkspaceTerminalReadiness.Ready,
                isCreating = false,
                nextTabNumber = tabNumber + 1,
            )
        }
    }

    private fun markFinished(root: String, tabId: Long) {
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(finished = true) else tab
                },
            ))
        }
    }

    private fun currentState(root: String): WorkspaceTerminalTabsState =
        workspaceStates.value[root] ?: WorkspaceTerminalTabsState()

    private inline fun updateState(
        root: String,
        transform: (WorkspaceTerminalTabsState) -> WorkspaceTerminalTabsState,
    ) {
        workspaceStates.update { states ->
            states + (root to transform(states[root] ?: WorkspaceTerminalTabsState()))
        }
    }

    private companion object {
        const val TAG = "WorkspaceTerminalManager"
    }
}

internal data class WorkspaceTerminalTabsState(
    val tabs: List<WorkspaceTerminalTab> = emptyList(),
    val selectedTabId: Long? = null,
    val readiness: WorkspaceTerminalReadiness = WorkspaceTerminalReadiness.Loading,
    val isCreating: Boolean = false,
    val nextTabNumber: Int = 1,
)

internal data class WorkspaceTerminalTab(
    val id: Long,
    val number: Int,
    val session: TerminalSession,
    val client: WorkspaceTerminalSessionClient,
    val finished: Boolean = false,
    /** 这个页签是给哪个 Claude 会话开的（注册表的 key）。手动开的页签为 null */
    val sessionKey: String? = null,
    /** 页签上写什么。null 时写 [number] */
    val label: String? = null,
)

internal enum class WorkspaceTerminalReadiness {
    Loading,
    Ready,
    NotInstalled,
}
