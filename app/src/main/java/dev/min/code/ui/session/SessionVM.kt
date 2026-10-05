package dev.min.code.ui.session

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeCodeConfigStore
import dev.min.code.core.claudecode.ClaudeCodeCostLedger
import dev.min.code.core.claudecode.ClaudeCodeImage
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodePermissionMode
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.claudecode.ComposerDraft
import dev.min.code.core.claudecode.ComposerDraftStore
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.rootfs.createCwdFolder
import dev.min.code.core.rootfs.listCwdFolders
import dev.min.code.core.settings.SettingsStore
import me.rerere.workspace.WorkspaceFileEntry

/**
 * 当前会话本身：状态、输入框草稿、发消息与会话内的各种控制。
 *
 * 从原来的 ClaudeCodeVM 拆出来（M2-B B6a，纯搬家，行为不变）。「谁是活跃会话」只有注册表一个来源，
 * 这里的 [session] / [activeSessionKey] 是它的直通，其它 VM（[SessionListVM]、[WorkspaceToolsVM]、
 * [MaintenanceVM]）要读就直接读注册表，不经过本类。本类独占的状态是 [composerDraft] 和 [notice]。
 */
class SessionVM(
    private val context: Application,
    private val registry: ClaudeCodeSessionRegistry,
    private val workspaceRepository: WorkspaceRepository,
    private val configStore: ClaudeCodeConfigStore,
    private val costLedger: ClaudeCodeCostLedger,
    private val settingsStore: SettingsStore,
    private val drafts: ComposerDraftStore,
) : ViewModel() {
    /** 活跃会话的状态（注册表在多个会话间切换时自动跟随） */
    val session = registry.state

    /**
     * 注册表的会话 key。CLI 还没回报 `sessionId` 时状态里是空的，
     * 但草稿必须立刻能落盘，所以输入框钉在这个 key 上。
     */
    val activeSessionKey = registry.activeKey

    /**
     * 输入框草稿。按会话钉在磁盘上，杀进程再进同一会话还在。
     * 打开冲突（顺延的空框对上一个已经有内容的会话）时拒绝切换，当前会话的框不动。
     */
    private val _composerDraft = MutableStateFlow(ComposerDraft.Empty)
    val composerDraft: StateFlow<ComposerDraft> = _composerDraft.asStateFlow()

    /**
     * 按停止键（Esc）在本轮还没产出时撤回的消息，要原样退还给输入框。
     * 和 [composerDraft] 分开：那是可以被重放的状态，这是一次性事件。
     */
    val withdrawnMessages = registry.withdrawnMessages

    /** 今天的累计花费，跨会话、跨重启。底栏那个金额读它 */
    val dailyCostUsd: StateFlow<Double> = costLedger.dailyCostUsd

    /** 命令 / 模型说明显示中文对照还是英文原文 */
    val chineseDescriptions: StateFlow<Boolean> = settingsStore.settings
        .map { it.chineseDescriptions }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setChineseDescriptions(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setChineseDescriptions(enabled) }
    }

    /** 正在跑的会话，供抽屉打运行中标记 */
    val liveSessions = registry.liveSessions

    /** 达到并发上限时给 UI 一个提示 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()

    fun dismissNotice() { _notice.value = null }

    /**
     * 有没有可用的连接（走订阅，或当前生效的供应商带着 token）。没有时启动面板给 [ConnectPrompt]。
     *
     * 跟着 DataStore 走，不在 [MaintenanceVM.refresh] 里读一次：人从供应商页加完一家回来，提示要自己消失，
     * 不能等他去点刷新。初值 true：设置还没读到的那一下不闪「未连接」。
     */
    val connected: StateFlow<Boolean> = settingsStore.settings
        .map { it.claudeConnected }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()

    init {
        // 进程被 FGS 保活、页面重建时，注册表里的会话还在，草稿要从磁盘接回来
        registry.activeKey.value?.let { attachComposer(it) }
    }

    // --- 会话（多会话：注册表持有多个 manager，各自一个 CLI 进程） ---
    fun start(options: ClaudeCodeManager.SessionOptions) {
        if (registry.newSession(options) == null) atCapacity()
        else attachComposer(registry.activeKey.value)
    }

    fun newSession() {
        // 没有活跃会话时（刚启动 App）不能退回 SessionOptions() 的默认值 ——
        // 那会把用户上次选的模型/effort 丢掉，新会话默默跑在 CLI 默认模型上
        val options = registry.lastOptions()
        if (registry.newSession(options) == null) atCapacity()
        else attachComposer(registry.activeKey.value)
    }

    /** 已在跑就直接切过去（不重启进程），否则新起一个并 --resume 续上 */
    fun openSession(id: String) {
        val existing = drafts.load(id)
        if (drafts.hasCarry() && !existing.isEmpty) {
            _notice.value = context.getString(R.string.vm_draft_conflict)
            return
        }
        if (registry.openSession(id) == null) {
            atCapacity()
            return
        }
        if (drafts.hasCarry()) drafts.consumeCarry(id)
        drafts.rememberActive(id)
        _composerDraft.value = existing
    }

    /** 仅切换活跃会话 */
    fun switchTo(key: String) {
        registry.switchTo(key)
        attachComposer(key)
    }

    /** 停掉某个会话的进程并从注册表移除 */
    fun closeSession(key: String) = registry.closeSession(key)

    /**
     * 抽屉行菜单里的「结束会话」：只停进程，transcript 留着，之后点开照样 --resume 续上。
     * 正看着的那个走顶栏停止键同一条路（留在页面上显示「会话已结束」，攥着的消息退回输入框）；
     * 后台的直接从注册表摘掉 —— 不摘的话活跃项以外的死壳要等下一次 pruneDead 才走
     */
    fun endSession(id: String) {
        if (registry.activeKey.value == id) stop() else registry.closeSession(id)
    }

    private fun atCapacity() {
        _notice.value = context.getString(
            R.string.vm_max_sessions,
            ClaudeCodeSessionRegistry.MAX_CONCURRENT,
        )
    }

    /**
     * 删掉的会话正是输入框钉着的那个时，把框清空。草稿的唯一持有者是本类，
     * [SessionListVM.deleteSession] 删完会话后经页面回调到这里，不直接碰 [_composerDraft]。
     */
    fun resetComposerDraft() {
        _composerDraft.value = ComposerDraft.Empty
    }

    fun saveComposerDraft(sessionId: String?, draft: ComposerDraft) {
        val id = sessionId ?: return
        if (id == registry.activeKey.value) _composerDraft.value = draft
        drafts.save(id, draft)
    }

    /** 进后台 / 清进程前拍一张。空框会把指针顺延到下次打开的第一个会话。 */
    fun snapshotComposerOnStop(sessionId: String?, draft: ComposerDraft) {
        val id = sessionId ?: return
        if (id == registry.activeKey.value) _composerDraft.value = draft
        drafts.snapshotOnStop(id, draft)
    }

    private fun attachComposer(sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            _composerDraft.value = ComposerDraft.Empty
            return
        }
        val existing = drafts.load(sessionId)
        if (drafts.hasCarry() && !existing.isEmpty) {
            _notice.value = context.getString(R.string.vm_draft_conflict)
            return
        }
        if (drafts.hasCarry()) drafts.consumeCarry(sessionId)
        drafts.rememberActive(sessionId)
        _composerDraft.value = existing
    }

    // --- 会话内控制（对应 Desktop 底栏），只作用于活跃会话 ---
    fun send(text: String, images: List<ClaudeCodeImage> = emptyList()) =
        registry.onActive { it.send(text, images) }

    /** `@` 提及的文件补全。没有活跃会话时给空表，输入框自然不弹候选。 */
    suspend fun searchFiles(query: String): List<String> =
        registry.active()?.searchFiles(query).orEmpty()

    fun answerPermission(
        allow: Boolean,
        message: String? = null,
        suggestion: dev.min.code.core.claudecode.PermissionSuggestion? = null,
    ) = registry.onActive { it.answerPermission(allow, message, suggestion) }

    /** 应答 AskUserQuestion：把选项填回 updatedInput.answers。空 map = 跳过。 */
    fun answerQuestions(answers: Map<String, String>) = registry.onActive { it.answerQuestions(answers) }

    fun interrupt() = registry.onActive { it.interrupt() }

    /** 子 agent 视图里发的话。图片递不进子 agent（hook 只能带文字），见 [ClaudeCodeManager.sendToSubagent] */
    fun sendToSubagent(toolUseId: String, text: String, images: List<ClaudeCodeImage> = emptyList()) =
        registry.onActive { it.sendToSubagent(toolUseId, text, dropImages = images.size) }

    fun stopSubagent(toolUseId: String) = registry.onActive { it.stopSubagent(toolUseId) }
    fun runShell(command: String) = registry.onActive { it.runShell(command) }
    fun stop() = registry.onActive { it.stopSession() }
    /**
     * 切模型。[asDefault] 对齐 CLI `/model` 的 Enter / `s`：
     * true = 本会话热切 + 写 settings.json 的 `model`（新会话、终端页的 `claude` 都跟着变）；
     * false = 只改这个会话。`/model <名字>` 这种带参数的写法在 CLI 里等于 Enter，也走 true。
     */
    fun setModel(model: String?, asDefault: Boolean = true) {
        registry.onActive { it.setModel(model, asDefault) }
        if (asDefault) viewModelScope.launch { configStore.saveDefaultModel(model) }
    }
    fun setPermissionMode(mode: ClaudeCodePermissionMode) = registry.onActive { it.setPermissionMode(mode) }
    fun applyEffort(effort: String?, ultracode: Boolean = false) = registry.onActive { it.applyEffort(effort, ultracode) }

    /** 提示缓存 TTL（5m / 1h）。和 effort 一样要重启 CLI 续接会话 */
    fun setPromptCacheTtl(ttl: String) = registry.onActive { it.setPromptCacheTtl(ttl) }

    fun refreshModels() = registry.onActive { it.refreshModels() }
    fun refreshPlan() = registry.onActive { it.refreshPlan() }

    fun refreshUsage() {
        // 跨过午夜之后那个数字应该自己归零，而不是等下一次花钱才刷新
        costLedger.refresh()
        registry.onActive { it.refreshUsage() }
    }
    fun setCwd(path: String) = registry.onActive { it.setCwd(path) }

    /** 新建会话时的默认配置（含上次选的工作目录） */
    fun lastOptions(): ClaudeCodeManager.SessionOptions = registry.lastOptions()

    /**
     * 列出某个 guest 路径下的条目，给选文件夹面板用。
     * 失败（目录不存在、越界）走 Result，面板显示原因而不是崩。
     */
    suspend fun listCwdFolders(guest: String): Result<List<WorkspaceFileEntry>> =
        workspaceRepository.listCwdFolders(workspaceId, guest)

    fun createCwdFolder(parent: String, name: String, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch {
            onDone(
                workspaceRepository.createCwdFolder(
                    workspaceId,
                    parent,
                    name,
                    invalidName = context.getString(R.string.vm_invalid_name),
                ),
            )
        }
    }

    /**
     * 撤销一次编辑类工具造成的文件改动（只还原文件，对话历史不变）。
     * [onRestored] 在还原**成功**之后回调（主线程），失败不回调——页面据此才把它标成「已撤销」。
     */
    fun revertToolCall(toolUseId: String, onRestored: () -> Unit = {}) {
        val manager = registry.active() ?: return
        viewModelScope.launch { if (manager.revertToolCall(toolUseId)) onRestored() }
    }

    /**
     * 把一个文件在本会话里的全部改动整批撤回去（从最后一次往前还原；同样只动文件）。
     * [toolUseIds] 按会话顺序（旧 → 新）。[onRestored] 收到**真的还原成功**的那几个。
     */
    fun revertFile(name: String, toolUseIds: List<String>, onRestored: (Set<String>) -> Unit) {
        val manager = registry.active() ?: return
        viewModelScope.launch { onRestored(manager.revertFile(name, toolUseIds)) }
    }

    /** [toolUseIds] 里存过文件快照的那些，决定「改动过的文件」面板里谁有撤销按钮 */
    suspend fun checkpointed(toolUseIds: Collection<String>): Set<String> =
        registry.active()?.checkpointed(toolUseIds).orEmpty()

    /** `/init` 是 prompt 型内置命令，无头模式下当普通消息发过去就会执行 */
    fun runInit() = send("/init")

    // 不在 onCleared 里杀会话：ClaudeCodeManager 是 Koin single，
    // 会话本就该比页面活得久（切到别的页面、转屏都会重建 VM）。
    // 结束会话由用户在标题栏显式点「停止」。
}
