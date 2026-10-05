package dev.min.code.ui.session

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.claudecode.ClaudeCodeSessionStore
import dev.min.code.core.claudecode.ClaudeCodeSessionTransfer
import dev.min.code.core.claudecode.ComposerDraftStore
import dev.min.code.core.claudecode.CwdPath
import dev.min.code.core.claudecode.SessionImportReject
import dev.min.code.core.claudecode.SessionMeta
import java.io.InputStream
import java.io.OutputStream

/**
 * 抽屉里的会话列表：磁盘 + 在线 + 元数据的合并、删除、置顶 / 分类 / 改名、导出 / 导入。
 *
 * 从原来的 ClaudeCodeVM 拆出来（M2-B B6a，纯搬家）。它只**读**注册表（活跃项、在线会话），
 * 不持有任何会话状态：新建 / 打开 / 结束在 [SessionVM]，删到正在用的会话时要清输入框，
 * 由 [deleteSession] 的回调交给 [SessionVM.resetComposerDraft]，草稿仍只有它一个持有者。
 * 磁盘扫描由 [refreshSessions] 触发；环境状态读完那一刻的重扫由页面接 [MaintenanceVM.sessionsStale] 转发过来。
 */
class SessionListVM(
    private val context: Application,
    private val registry: ClaudeCodeSessionRegistry,
    private val drafts: ComposerDraftStore,
    private val sessionMeta: ClaudeCodeSessionMetaStore,
) : ViewModel() {
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

    /**
     * 删除会话。删到的正是活跃会话（含刚被 [ClaudeCodeSessionRegistry.closeSession] 摘掉、
     * 状态里还留着它 id 的那一帧）时回调 [onActiveDeleted]，由调用方清掉输入框。
     */
    fun deleteSession(id: String, onActiveDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            registry.closeSession(id)
            (registry.active() ?: registry.configProbe()).deleteSession(id)
            drafts.forget(id)
            sessionMeta.forget(id)
            if (registry.activeKey.value == id || registry.state.value.sessionId == id) {
                onActiveDeleted()
            }
            refreshSessions()
        }
    }

    fun pinSession(id: String, pinned: Boolean) = sessionMeta.update(id) { it.copy(pinned = pinned) }

    fun setSessionCategory(id: String, category: String?) =
        sessionMeta.update(id) { it.copy(category = category?.trim()?.takeIf { name -> name.isNotBlank() }) }

    fun sessionCategories(): List<String> = sessionMeta.categories()

    fun renameSession(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        sessionMeta.update(id) { it.copy(title = trimmed) }
        if (registry.activeKey.value == id) registry.onActive { it.renameSession(trimmed) }
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
            val cwd = CwdPath.normalize(registry.lastOptions().cwd)
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
}
