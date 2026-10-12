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
import dev.min.code.core.engine.AgentSessionRegistry
import dev.min.code.core.engine.CloseReason
import dev.min.code.core.engine.DeleteResult
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SessionSummary
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID
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
@OptIn(FlowPreview::class)
class SessionListVM(
    private val context: Application,
    private val registry: ClaudeCodeSessionRegistry,
    private val drafts: ComposerDraftStore,
    private val sessionMeta: ClaudeCodeSessionMetaStore,
    /** Min 的会话经它开 / 关（B6 起 Claude Code 仍走 [registry]） */
    private val agents: AgentSessionRegistry,
    /** Min 会话的元数据（置顶、分类、改名），与 Min 引擎读标题的是同一个 */
    private val minMeta: ClaudeCodeSessionMetaStore,
    /** Codex 会话的元数据（置顶、分类、改名），threadId 当 key，与 Codex 引擎读标题的是同一个 */
    private val codexMeta: ClaudeCodeSessionMetaStore,
    settingsStore: SettingsStore,
) : ViewModel() {
    /** Min 实验开关。关着时抽屉里不列 Min 的会话（会话与日志都还在，开回来原样列出） */
    private val minEnabled: StateFlow<Boolean> = settingsStore.settings.map { it.minAgentEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsStore.snapshot?.minAgentEnabled ?: false)

    /** Min 引擎目录里的会话摘要（内核日志的派生索引，含「中断」标记） */
    private val _minSessions = MutableStateFlow<List<SessionSummary>>(emptyList())

    /** Codex 引擎目录里的会话摘要（rollout 文件的头部） */
    private val _codexSessions = MutableStateFlow<List<SessionSummary>>(emptyList())

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
        /** 哪个引擎的会话。抽屉据此打「Min」标签、决定行菜单里有没有导出 */
        val engine: EngineKind = EngineKind.ClaudeCode,
        /** 最后一个回合没结束（Min 启动扫描认出来的「中断」；Codex 要读整个 rollout 才知道，列表里不判），打开后是 ▶ */
        val interrupted: Boolean = false,
        /** 进行中的定时任务数（Min）：抽屉里打「定时」标记 */
        val scheduledTasks: Int = 0,
        /** 会话的工作目录（Min 从索引来）：开始面板的「继续最近的会话」按目录挑。不知道为 null */
        val cwd: String? = null,
    ) {
        val ref: SessionRef get() = SessionRef(engine, id)
    }

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
        combine(
            combine(_diskSessions, registry.liveSessions, registry.state, sessionMeta.items) { disk, live, active, meta ->
                ClaudeInputs(disk, live, active, meta)
            },
            combine(_minSessions, agents.live, minMeta.items, minEnabled) { min, live, meta, on ->
                MinInputs(if (on) min else emptyList(), live, meta)
            },
            combine(_codexSessions, agents.live, codexMeta.items) { codex, live, meta -> MinInputs(codex, live, meta) },
        ) { claude, min, codex ->
            val (disk, live, active, meta) = claude
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
            val fromMin = min.sessions.map { m ->
                val extra = min.meta[m.ref.id] ?: SessionMeta()
                val running = min.live.firstOrNull { it.ref == m.ref }
                SessionEntry(
                    id = m.ref.id,
                    title = extra.title?.takeIf { it.isNotBlank() } ?: m.title,
                    updatedAt = m.updatedAt,
                    messageCount = m.messageCount,
                    isLive = running?.keepAlive == true,
                    // 谁是「正在看的」由页面按当前会话的引用盖上（两边各有一个活跃项，只有页面知道显示的是哪边）
                    isActive = false,
                    pinned = extra.pinned,
                    category = extra.category,
                    titled = true,
                    bodyText = m.bodyText,
                    // Min 的会话没有「落盘文件」可导出（目录的 export 返回不支持）
                    onDisk = false,
                    engine = EngineKind.MinAgent,
                    interrupted = m.interrupted && running == null,
                    scheduledTasks = m.scheduledTasks,
                    cwd = m.cwd,
                )
            }
            val fromCodex = codex.sessions.map { c ->
                val extra = codex.meta[c.ref.id] ?: SessionMeta()
                val running = codex.live.firstOrNull { it.ref == c.ref }
                SessionEntry(
                    id = c.ref.id,
                    title = extra.title?.takeIf { it.isNotBlank() } ?: c.title.ifBlank { context.getString(R.string.codex_session_untitled) },
                    updatedAt = c.updatedAt,
                    messageCount = c.messageCount,
                    isLive = running?.keepAlive == true,
                    // 谁是「正在看的」由页面按当前会话的引用盖上（与 Min 一样，两边各有一个活跃项）
                    isActive = false,
                    pinned = extra.pinned,
                    category = extra.category,
                    titled = true,
                    bodyText = c.bodyText,
                    // Codex 的 rollout 是它自己的文件，不走 CC 的导出 / 导入
                    onDisk = false,
                    engine = EngineKind.Codex,
                    cwd = c.cwd,
                )
            }
            (orphanLive + fromDisk + fromMin + fromCodex).sortedWith(
                compareByDescending<SessionEntry> { it.pinned }
                    .thenByDescending { it.isLive }
                    .thenByDescending { it.updatedAt },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private data class ClaudeInputs(
        val disk: List<ClaudeCodeSessionStore.SessionSummary>,
        val live: List<ClaudeCodeSessionRegistry.LiveSession>,
        val active: ClaudeCodeManager.SessionState,
        val meta: Map<String, SessionMeta>,
    )

    /** Min 与 Codex 的输入同一个形状：目录摘要 + 在线摘要 + 元数据 */
    private data class MinInputs(
        val sessions: List<SessionSummary>,
        val live: List<dev.min.code.core.engine.LiveSessionSummary>,
        val meta: Map<String, SessionMeta>,
    )

    init {
        // Min 的索引在会话事件之后异步更新：会话的回合起落时重列一次，标题、条数、「中断」标记才跟得上。
        // Codex 的 rollout 也是回合里落盘的，同样重列（新建的会话要等第一轮才有文件）
        viewModelScope.launch {
            agents.live.map { list -> list.filter { it.ref.engine != EngineKind.ClaudeCode }.map { it.ref to it.busy } }
                .distinctUntilChanged().debounce(MIN_RELIST_DEBOUNCE_MS).collect { reloadMin(); reloadCodex() }
        }
    }

    private suspend fun reloadMin() {
        // 目录读不出来（索引坏了 / 引擎被关）不该拖垮抽屉：当作没有 Min 会话，下一次重列再试
        _minSessions.value = try {
            agents.engine(EngineKind.MinAgent).catalog.list()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun reloadCodex() {
        // rollout 读不出来（环境还没装、目录不在）不该拖垮抽屉：当作没有 Codex 会话
        _codexSessions.value = try {
            agents.engine(EngineKind.Codex).catalog.list()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 历史会话直接读 CLI 自己写的 transcript，不依赖 App 侧持久化 */
    fun refreshSessions() {
        viewModelScope.launch {
            reloadMin()
            reloadCodex()
            reloadSessions()
        }
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
    fun deleteSession(ref: SessionRef, onActiveDeleted: () -> Unit = {}) {
        if (ref.engine != EngineKind.ClaudeCode) {
            deleteAgent(ref, onActiveDeleted)
            return
        }
        val id = ref.id
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

    /**
     * 删一个 Min / Codex 会话：先把它从「界面打开着的」里摘掉（顺带中断在跑的回合 / 停掉 app-server），再经目录删干净
     * （Min 连根带树；Codex 是 rollout 文件 + 元数据 + 草稿）。在用的会话被删时回调 [onActiveDeleted]，由调用方清输入框；
     * 仍在用 / 删不掉的留给界面一句提示
     */
    private fun deleteAgent(ref: SessionRef, onActiveDeleted: () -> Unit) {
        viewModelScope.launch {
            val wasActive = agents.activeRef.value == ref
            agents.close(ref, CloseReason.Deleted)
            // 删不掉（仍在用 / 失败）时列表重列后这一行还在，用户看得见；元数据只在真删掉之后才忘
            if (agents.engine(ref.engine).catalog.delete(ref.id) == DeleteResult.Deleted) metaOf(ref).forget(ref.id)
            if (wasActive) onActiveDeleted()
            refreshSessions()
        }
    }

    fun pinSession(ref: SessionRef, pinned: Boolean) = metaOf(ref).update(ref.id) { it.copy(pinned = pinned) }

    fun setSessionCategory(ref: SessionRef, category: String?) =
        metaOf(ref).update(ref.id) { it.copy(category = category?.trim()?.takeIf { name -> name.isNotBlank() }) }

    /** 分类名三个引擎共用一张候选表：同一个分类下可以既有 Claude 会话也有 Min / Codex 会话 */
    fun sessionCategories(): List<String> = (sessionMeta.categories() + minMeta.categories() + codexMeta.categories()).distinct()

    fun renameSession(ref: SessionRef, title: String) {
        val trimmed = title.trim()
        // 空名字：Codex 的元数据里清掉人手改的标题（回到第一句话拟的那个）；别的引擎没有这个语义，照旧不处理
        if (trimmed.isBlank() && ref.engine != EngineKind.Codex) return
        if (ref.engine != EngineKind.ClaudeCode) {
            // Min 的名字写进内核日志（在线与否都行），列表与 CLI 读同一个；Codex 只动 App 侧元数据（rollout 不碰）。
            // 都经引擎的 renameSession，列表才不用知道名字存在哪儿
            viewModelScope.launch {
                agents.engine(ref.engine).renameSession(ref.id, trimmed)
                refreshSessions()
            }
            return
        }
        metaOf(ref).update(ref.id) { it.copy(title = trimmed) }
        if (registry.activeKey.value == ref.id) registry.onActive { it.renameSession(trimmed) }
    }

    /**
     * 抽屉行菜单的「从这里分支」：引擎复制出一个新会话，成功后交给 [onBranched] 打开它；被拒（会话正在跑等）把原因交给 [onRefused]。
     * 分支是内核的事，列表只是刷新一下让新会话出现
     */
    fun branchSession(ref: SessionRef, onBranched: (SessionRef) -> Unit, onRefused: (String?) -> Unit) {
        viewModelScope.launch {
            when (val outcome = agents.engine(ref.engine).branchSession(ref.id)) {
                is dev.min.code.core.engine.BranchOutcome.Branched -> {
                    refreshSessions()
                    onBranched(SessionRef(ref.engine, outcome.id))
                }
                is dev.min.code.core.engine.BranchOutcome.Refused -> onRefused(outcome.reason)
            }
        }
    }

    private fun metaOf(ref: SessionRef): ClaudeCodeSessionMetaStore = when (ref.engine) {
        EngineKind.MinAgent -> minMeta
        EngineKind.Codex -> codexMeta
        EngineKind.ClaudeCode -> sessionMeta
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

    private companion object {
        const val MIN_RELIST_DEBOUNCE_MS = 400L
    }
}
