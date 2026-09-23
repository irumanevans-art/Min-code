package dev.min.code.ui.codex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.claudecode.SessionMeta
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.codex.CodexDecision
import dev.min.code.core.codex.CodexDraftStore
import dev.min.code.core.codex.CodexRuntime
import dev.min.code.core.codex.CodexSessionSummary
import dev.min.code.core.codex.deleteCodexSession
import dev.min.code.core.codex.firstRestorableCodexSession
import dev.min.code.core.codex.listCodexSessions
import dev.min.code.core.codex.readCodexSessionItems
import dev.min.code.core.codex.sortCodexSessions
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import dev.min.code.core.settings.CodexAuthMode
import dev.min.code.core.settings.CodexProfile
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.workspace.WorkspaceStorageArea
import java.io.InputStream

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
    /** 会话元数据（置顶 / 改名）。与 Claude 侧同一个 store 类，threadId 当 key、各存各的文件 */
    private val metaStore: ClaudeCodeSessionMetaStore,
    /** 输入框草稿：threadId → 正文，杀进程再进还在 */
    private val draftStore: CodexDraftStore,
    /** 附件要先落进工作区，Codex 才读得到 —— 它的 cwd 就是 /workspace */
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {

    val runtimeStatus: StateFlow<CodexRuntime.Status> = runtime.status
    val session: StateFlow<CodexAppServerManager.State> = manager.state

    /** 当前输入框的正文。threadId 变了就换那份草稿（见 init 里的收集） */
    val draft = MutableStateFlow("")

    /** 磁盘上那些旧会话，新的在前。空列表就是「还没聊过」 */
    private val _sessions = MutableStateFlow<List<CodexSessionSummary>>(emptyList())

    /**
     * 列表展示顺序：置顶在前，其余按修改时间。置顶标记来自 [sessionMetas]，
     * 改一次元数据两个流各自重排，UI 只管读。
     */
    val sessions: StateFlow<List<CodexSessionSummary>> = combine(
        _sessions,
        metaStore.items,
    ) { list, metas -> sortCodexSessions(list, metas.filterValues { it.pinned }.keys) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 会话元数据原样给 UI：列表要显示人手改过的标题和置顶角标 */
    val sessionMetas: StateFlow<Map<String, SessionMeta>> = metaStore.items

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
        // 草稿跟着 threadId 走：切会话 / 删除 / 新开都从这里换档，
        // 不在各个动作里手工清——那漏掉任何一条新路径都会串稿
        viewModelScope.launch(Dispatchers.IO) {
            manager.state.map(::draftKeyOf).distinctUntilChanged().collect { key ->
                draft.value = draftStore.load(key)
            }
        }
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
            // 列表照样把失败的会话摆出来——用户得能看见它们、删掉它们；
            // 自动恢复才挑有内容的那条，见 firstRestorableCodexSession
            _sessions.value = all
            val (summary, items) = firstRestorableCodexSession(all) { readCodexSessionItems(it.file) }
                ?: return@launch
            manager.restore(items = items, threadId = summary.threadId)
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
                options = threadOptions(),
                resumeThreadId = manager.state.value.threadId,
            )
        }
    }

    /** 另起一条。清掉屏幕上的历史，也不带 threadId —— 磁盘上那条不会被动 */
    fun startNew() {
        viewModelScope.launch {
            runtime.prepare()
            manager.start(options = threadOptions(), resumeThreadId = null)
        }
    }

    /** 连接配置里的模型 / 思考强度；空串 = 不传，用 Codex 自己的默认 */
    private fun threadOptions(): CodexAppServerManager.Options {
        val profile = profile.value
        return CodexAppServerManager.Options(
            model = profile.model.trim().takeIf(String::isNotBlank),
            effort = profile.effort.trim().takeIf(String::isNotBlank),
        )
    }

    /**
     * 换这一条会话的模型 / 思考强度。下一轮生效，不重启进程，也不改连接配置里的默认
     * ——「这一轮让它想久一点」和「我平时用这个档」是两件事。
     */
    fun setModelAndEffort(model: String?, effort: String?) = manager.setModelAndEffort(model, effort)

    fun stop() = manager.stop()

    /** 每次输入都交给草稿库落盘：它按顺序写、快打时合并，见 [CodexDraftStore.saveLater] */
    fun setDraft(text: String) {
        draft.value = text
        draftKeyOf(manager.state.value)?.let { draftStore.saveLater(it, text) }
    }

    fun send(text: String): Boolean {
        val wasNewThread = draftKeyOf(manager.state.value) == NEW_THREAD_DRAFT_KEY
        val ok = manager.sendTurn(composeMessage(text))
        if (ok) {
            // 发出去这一刻线程就有了记录，草稿的键随之换成 threadId；「新会话」名下那份得一并清掉，
            // 否则下次开新会话会冒出这段已经发出去的话
            if (wasNewThread) draftStore.saveLater(NEW_THREAD_DRAFT_KEY, "")
            setDraft("")
            // 只清列表，**不删文件** —— 它已经发给模型了，模型随时会去读
            attachments.value = emptyList()
        }
        return ok
    }

    /**
     * 附件路径附在正文后面，和 Claude 侧同一个写法。
     *
     * 给的是 guest 路径（`/workspace/…`）而不是内容：Codex 有自己的读文件工具，
     * 把几百 KB 塞进 prompt 既烧上下文又不如让它按需去读。
     */
    private fun composeMessage(text: String): String {
        val refs = attachments.value
        if (refs.isEmpty()) return text
        val lines = refs.joinToString(separator = System.lineSeparator()) { it.guestPath }
        return if (text.isBlank()) lines else text + System.lineSeparator() + System.lineSeparator() + lines
    }

    /**
     * 选好的附件。**不落盘**：进程被杀就没了（文件本身还在工作区里，重新引用即可）。
     * 草稿正文那份是落盘的，两者的代价不一样——附件表丢了还能再挑一次，写了一半的话丢了就没了。
     */
    val attachments = MutableStateFlow<List<CodexAttachment>>(emptyList())

    /** 把一个外部文件拷进工作区，成功后挂到附件栏上 */
    fun importFile(fileName: String, inputStream: InputStream, onDone: (Boolean) -> Unit) {
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
            val path = entry?.let { "/workspace/${it.path.trimStart('/')}" }
            if (path != null) {
                attachments.value = attachments.value + CodexAttachment(fileName, path)
            }
            onDone(path != null)
        }
    }

    /**
     * 移除附件 = 真的把文件从工作区删掉。
     * 只从列表里抹掉一行路径是没用的：文件已经躺在 /workspace 里，Codex 照样 ls 得到、读得到。
     */
    fun removeAttachment(attachment: CodexAttachment) {
        attachments.value = attachments.value - attachment
        val rel = attachment.guestPath.removePrefix("/workspace").trimStart('/')
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

    private val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()

    /**
     * `@` 补全的候选。返回的是 guest 路径（`/workspace/…`），正是要写进消息里的那一行。
     *
     * 走工作区仓库而不是 Codex 自己：补全只需要知道盘上有什么文件，
     * 为此跟一个可能没跑起来的 app-server 要目录，既慢又会在会话没启动时整个失灵。
     */
    suspend fun searchFiles(query: String): List<String> =
        runCatching { workspaceRepository.mentionFiles(query) }.getOrDefault(emptyList())

    /**
     * 把排着的第 [index] 条取回输入框。
     *
     * 草稿里已经有东西时接在后面而不是覆盖——取回是为了改一改再发，
     * 顺手把人正在写的半句话冲掉就成了另一种丢稿。
     */
    fun takeQueued(index: Int) {
        val text = manager.takeQueued(index) ?: return
        val current = draft.value
        setDraft(if (current.isBlank()) text else "$current\n$text")
    }

    fun answerApproval(decision: CodexDecision): Boolean = manager.answerApproval(decision)

    /** 置顶 / 取消置顶。只动 App 侧元数据，rollout 文件不碰 */
    fun pinSession(threadId: String, pinned: Boolean) {
        metaStore.update(threadId) { it.copy(pinned = pinned) }
    }

    /** 人手改过的标题，覆盖磁盘摘要里的第一句话；清空即回到自动标题 */
    fun renameSession(threadId: String, title: String) {
        metaStore.update(threadId) { it.copy(title = title.trim().takeIf(String::isNotBlank)) }
    }

    /**
     * 删除一条会话：rollout 文件 + 元数据一起走。
     * 删的是正跑着的那条时先停进程；删的是屏幕上摆着的那条时把回放也摘掉，
     * 不然「列表里没了、屏幕上还在」，看起来就像删除没生效。
     */
    fun deleteSession(summary: CodexSessionSummary) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = manager.state.value
            if (state.threadId == summary.threadId &&
                (state.status == SessionStatus.Running || state.status == SessionStatus.Starting)
            ) {
                manager.stop()
            }
            runCatching { deleteCodexSession(summary.file) }
            metaStore.forget(summary.threadId)
            manager.clearIfIdle(summary.threadId)
            refreshSessions()
        }
    }

    fun saveProfile(profile: CodexProfile) {
        viewModelScope.launch {
            settingsStore.setCodexProfile(profile)
            runtime.refresh()
        }
    }

    /**
     * Codex 侧的全部连接配置。连接面板上那张列表读它 —— 以前这一页只认
     * `id = "default"` 那一条，存储层明明是一张表，界面上却永远只有一行。
     */
    val profiles: StateFlow<List<CodexProfile>> = settingsStore.settings
        .map { it.codexProfiles }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 换一条。app-server 的 env 是启动时固化的，所以这里只改「下次起的是哪条」；
     * `runtime.refresh()` 把 config.toml 重写一遍，真正生效要等下一次 start
     * （面板上由 `codex_connection_restart_hint` 说明）。
     */
    fun activateProfile(id: String) {
        viewModelScope.launch {
            // 明文风险的确认不在这儿放行：那道门归供应商页管，这里替用户点头
            // 会让他此后在那一页也看不到警告
            settingsStore.setActiveCodexProfile(id)
            runtime.refresh()
        }
    }

    /** 新建一条空的中转配置并切过去。内容在同一个面板的下半部分接着填 */
    fun addProfile() {
        viewModelScope.launch {
            settingsStore.addCodexProfile(CodexProfile(id = "", authMode = CodexAuthMode.RELAY))
            runtime.refresh()
        }
    }

    companion object {
        /**
         * 还没发过一条的线程，草稿记在这个键下，而不是它的 threadId。
         *
         * Codex 要等第一轮才给线程落会话文件：新开一个会话、打了半段字、App 被杀，重开之后这个线程
         * 根本恢复不回来，记在它 threadId 下的草稿就再也读不到了。「新会话里没发出去的字」
         * 本来也属于「新会话」—— 重开后点「新会话」，字还在。
         */
        const val NEW_THREAD_DRAFT_KEY = "__new_thread__"

        /** 草稿该记在哪个键下；还没起线程时是 null（只在内存里，不落盘） */
        internal fun draftKeyOf(state: CodexAppServerManager.State): String? {
            val threadId = state.threadId ?: return null
            return if (state.items.none { it is ChatItem.UserText }) NEW_THREAD_DRAFT_KEY else threadId
        }

        const val DEFAULT_PROFILE_ID = "default"
    }
}

/**
 * 一个挂在输入框上的附件：已经拷进工作区的文件。
 *
 * [guestPath] 是 proot 里那一侧的路径（`/workspace/…`），也就是发给 Codex 的那一行。
 */
data class CodexAttachment(val name: String, val guestPath: String)
