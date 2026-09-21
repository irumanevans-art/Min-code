package dev.min.code.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.min.code.core.claudecode.ClaudeCodeConfigStore
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.claudecode.RelayProbeResult
import dev.min.code.core.claudecode.probeRelay
import dev.min.code.core.codex.CodexRuntime
import dev.min.code.core.codex.parseCodexConfigImport
import dev.min.code.core.relay.RelayController
import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.BACKUP_TAG_CLAUDE_SETTINGS
import dev.min.code.core.settings.backupTag
import dev.min.code.core.settings.ClaudePreset
import dev.min.code.core.settings.CodexPreset
import dev.min.code.core.settings.CodexProfile
import dev.min.code.core.settings.DeepLinkParse
import dev.min.code.core.settings.UnifiedProfile
import dev.min.code.core.settings.parseProviderDeepLink
import dev.min.code.core.settings.ProviderBackup
import dev.min.code.core.settings.ProviderPresetSource
import dev.min.code.core.settings.ProviderPresets
import dev.min.code.core.settings.ProviderSync
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.TransferParse
import dev.min.code.core.settings.buildTransferFile
import dev.min.code.core.settings.mergeClaudeImports
import dev.min.code.core.settings.mergeCodexImports
import dev.min.code.core.settings.parseClaudeSettingsImport
import dev.min.code.core.settings.parseTransferFile
import dev.min.code.core.settings.toProfile
import dev.min.code.core.settings.transferFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 供应商页。
 *
 * 这一层只做三件事：把设置流摆出来、把用户的动作按 §「切换时序」串起来、
 * 把「有哪些地方现在对不上」算成界面能直接画的东西。规则本身都在 core 里，
 * 这里不重复判断。
 */
class ProvidersVM(
    private val store: SettingsStore,
    private val presetSource: ProviderPresetSource,
    private val providerSync: ProviderSync,
    private val registry: ClaudeCodeSessionRegistry,
    private val configStore: ClaudeCodeConfigStore,
    private val codexRuntime: CodexRuntime,
    private val backup: ProviderBackup,
    private val relay: RelayController,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _presets = MutableStateFlow(ProviderPresets.EMPTY)
    val presets: StateFlow<ProviderPresets> = _presets.asStateFlow()

    /**
     * 拖动途中的临时顺序。
     *
     * 拖拽每挪一格都要立刻在屏幕上生效，但**不能每一格写一次 DataStore**——一次拖动
     * 能写十几次，而且每次都会让设置流重新发一遍、列表重组。所以拖动期间用这份覆盖，
     * 松手时才落盘（[commitOrder]），落完清空。null = 没人在拖，直接用设置里的顺序。
     */
    private val _pendingOrder = MutableStateFlow<List<ApiProfile>?>(null)
    val pendingOrder: StateFlow<List<ApiProfile>?> = _pendingOrder.asStateFlow()

    private val _codexPendingOrder = MutableStateFlow<List<CodexProfile>?>(null)
    val codexPendingOrder: StateFlow<List<CodexProfile>?> = _codexPendingOrder.asStateFlow()

    private val _sync = MutableStateFlow<ProviderSync.Outcome>(ProviderSync.Outcome.Skipped)

    /** 最近一次往 Rootfs 投影的结果。失败要常驻在界面上，不能只打日志 */
    val syncOutcome: StateFlow<ProviderSync.Outcome> = _sync.asStateFlow()

    private val _probe = MutableStateFlow<Map<String, ProbeState>>(emptyMap())
    val probe: StateFlow<Map<String, ProbeState>> = _probe.asStateFlow()

    /**
     * 切换之后仍在用旧供应商的会话 key。
     *
     * 空闲的已经自动重起了，这里剩下的是**正忙的那些**——它们不动，由界面打角标 +
     * 给一颗手动重启。见 [ClaudeCodeSessionRegistry.reloadConnection]。
     */
    private val _staleSessions = MutableStateFlow<List<String>>(emptyList())
    val staleSessions: StateFlow<List<String>> = _staleSessions.asStateFlow()

    sealed interface ProbeState {
        data object Running : ProbeState
        data class Done(val result: RelayProbeResult) : ProbeState
    }

    init {
        viewModelScope.launch { _presets.value = presetSource.load() }
    }

    // -----------------------------------------------------------------------
    // Claude 侧
    // -----------------------------------------------------------------------

    /**
     * 切到某一条。顺序是钉死的：先落盘，再投影，最后才动进程。
     *
     * 反过来的话，重起来的进程读到的还是旧 active —— 它在 `launchCli` 里是现读的。
     */
    fun activate(id: String, acknowledgeInsecure: Boolean = false) = viewModelScope.launch {
        store.setActiveProfile(id, acknowledgeInsecure)
        _sync.value = providerSync.apply().claude
        // 方言变了要起 / 停路由；空闲会话重起时会读到新的 base URL
        relay.reconcile()
        _staleSessions.value = registry.reloadConnection()
    }

    fun save(profile: ApiProfile, activate: Boolean) = viewModelScope.launch {
        val id = if (profile.id.isBlank()) {
            store.addProfile(profile, activate = activate)
        } else {
            store.replaceProfile(profile)
            profile.id
        }
        // 改的是当前生效的那条 → 投影和进程都要跟上；改别的条目只是改一张表
        if (id == store.current().activeProfile?.id) {
            _sync.value = providerSync.apply().claude
            relay.reconcile()
            _staleSessions.value = registry.reloadConnection()
        }
        _probe.value = _probe.value - id
    }

    fun delete(id: String) = viewModelScope.launch {
        store.deleteProfile(id)
        _sync.value = providerSync.apply().claude
    }

    /** 复制一条。不切过去、不动投影：副本是拿来改的，还没到用它的时候 */
    fun duplicate(id: String) = viewModelScope.launch { store.duplicateProfile(id) }

    fun duplicateCodex(id: String) = viewModelScope.launch { store.duplicateCodexProfile(id) }

    // -----------------------------------------------------------------------
    // 统一供应商
    // -----------------------------------------------------------------------

    /**
     * 存一条统一供应商。存完要投影一次 —— 它可能刚刚改掉了**当前生效**那条的地址或 key，
     * 而那条现在正被终端和 settings.json 用着。
     */
    fun saveUnified(profile: UnifiedProfile) = viewModelScope.launch {
        store.saveUnifiedProfile(profile)
        _sync.value = providerSync.apply().claude
        relay.reconcile()
        codexRuntime.prepare()
        _staleSessions.value = registry.reloadConnection()
    }

    fun deleteUnified(id: String, deleteDerived: Boolean) = viewModelScope.launch {
        store.deleteUnifiedProfile(id, deleteDerived)
        _sync.value = providerSync.apply().claude
        relay.reconcile()
        codexRuntime.prepare()
    }

    // -----------------------------------------------------------------------
    // 深链导入
    // -----------------------------------------------------------------------

    /**
     * 一条 `minc://` / `ccswitch://` 链接。**解析出来先摆着**，等用户在预览上点头才入库——
     * 链接可以来自任何地方，而它带的是一个会被立刻拿去发请求的 key。
     */
    private val _pendingLink = MutableStateFlow<DeepLinkParse?>(null)
    val pendingLink: StateFlow<DeepLinkParse?> = _pendingLink.asStateFlow()

    fun offerDeepLink(link: String) {
        val parsed = parseProviderDeepLink(link)
        _pendingLink.value = parsed.takeIf { it != DeepLinkParse.NotImport }
    }

    fun dismissDeepLink() {
        _pendingLink.value = null
    }

    /** 确认导入。走的是和文件导入同一套合并规则（去重 / 改名 / 跳过都已经算好） */
    fun acceptDeepLink() = viewModelScope.launch {
        when (val pending = _pendingLink.value) {
            is DeepLinkParse.Claude -> mergeAndStore(listOf(pending.profile), emptyList())
            is DeepLinkParse.Codex -> mergeAndStore(emptyList(), listOf(pending.profile))
            else -> Unit
        }
        _pendingLink.value = null
    }

    fun applyPreset(preset: ClaudePreset, token: String, zh: Boolean) =
        save(preset.toProfile(token, zh), activate = token.isNotBlank())

    /**
     * 拖动途中：只动内存。按 id 换位，不按 LazyColumn 的绝对下标——前面那些固定项
     * （搜索框、分区标题、统一供应商区）也占下标，见 [ReorderState] 的头注释。
     */
    fun dragOrder(fromId: String, toId: String) {
        if (fromId == toId) return
        val current = _pendingOrder.value ?: settings.value.profiles
        val from = current.indexOfFirst { it.id == fromId }
        val to = current.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0) return
        _pendingOrder.value = current.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** 松手：落盘，然后放掉临时顺序 */
    fun commitOrder() {
        val order = _pendingOrder.value ?: return
        viewModelScope.launch {
            store.reorderProfiles(order.map { it.id })
            _pendingOrder.value = null
        }
    }

    fun probe(profile: ApiProfile) {
        if (_probe.value[profile.id] is ProbeState.Running) return
        _probe.value = _probe.value + (profile.id to ProbeState.Running)
        viewModelScope.launch {
            val result = probeRelay(profile.baseUrl, profile.token)
            _probe.value = _probe.value + (profile.id to ProbeState.Done(result))
        }
    }

    /** 用户点了角标上那颗「重启」：把正忙的也重起一遍 */
    fun restartStaleSessions() {
        registry.reloadConnection(includeBusy = true)
        _staleSessions.value = emptyList()
    }

    fun dismissStaleSessions() {
        _staleSessions.value = emptyList()
    }

    // -----------------------------------------------------------------------
    // Codex 侧
    // -----------------------------------------------------------------------

    fun activateCodex(id: String, acknowledgeInsecure: Boolean = false) = viewModelScope.launch {
        store.setActiveCodexProfile(id, acknowledgeInsecure)
        providerSync.apply()
        relay.reconcile()
        // Codex 的 config.toml 也要重写：路由地址 / wire_api 可能变了
        codexRuntime.prepare()
    }

    fun saveCodex(profile: CodexProfile, activate: Boolean) = viewModelScope.launch {
        if (profile.id.isBlank()) store.addCodexProfile(profile, activate = activate)
        else store.updateCodexProfile(profile)
        providerSync.apply()
        relay.reconcile()
        if (activate || profile.id == store.current().activeCodexProfile?.id) {
            codexRuntime.prepare()
        }
    }

    fun deleteCodex(id: String) = viewModelScope.launch { store.deleteCodexProfile(id) }

    fun applyCodexPreset(preset: CodexPreset, apiKey: String, zh: Boolean) =
        saveCodex(preset.toProfile(apiKey, zh), activate = true)

    fun dragCodexOrder(fromId: String, toId: String) {
        if (fromId == toId) return
        val current = _codexPendingOrder.value ?: settings.value.codexProfiles
        val from = current.indexOfFirst { it.id == fromId }
        val to = current.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0) return
        _codexPendingOrder.value = current.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun commitCodexOrder() {
        val order = _codexPendingOrder.value ?: return
        viewModelScope.launch {
            store.reorderCodexProfiles(order.map { it.id })
            _codexPendingOrder.value = null
        }
    }

    // -----------------------------------------------------------------------
    // 托管
    // -----------------------------------------------------------------------

    fun setManageGuestConfig(enabled: Boolean) = viewModelScope.launch {
        store.setManageGuestConfig(enabled)
        // 关掉的那一刻必须立刻清：不清的话文件里会一直留着上一家的 token，
        // 而界面上托管已经显示「关」——这是整套里最坏的一种不一致
        _sync.value = providerSync.apply().claude
    }

    fun setGuestConfigIncludesSecrets(enabled: Boolean) = viewModelScope.launch {
        store.setGuestConfigIncludesSecrets(enabled)
        _sync.value = providerSync.apply().claude
    }

    fun setInjectCredentialsIntoShells(enabled: Boolean) = viewModelScope.launch {
        store.setInjectCredentialsIntoShells(enabled)
    }

    // -----------------------------------------------------------------------
    // 导入 / 导出 / 备份
    // -----------------------------------------------------------------------

    /**
     * 一次搬运的结果。**必须摆到界面上**：静默导入会让人不知道刚导进来的东西在哪儿，
     * 而「跳过了 1 条」不说的话，用户会以为那条丢了。
     */
    sealed interface TransferState {
        data class Exported(val withSecrets: Boolean) : TransferState
        data class Imported(val added: Int, val skipped: Int, val renamed: Int) : TransferState
        data class Failed(val reason: Failure) : TransferState
    }

    enum class Failure { NOT_JSON, UNKNOWN_SCHEMA, EMPTY, NOTHING_FOUND, IO }

    private val _transfer = MutableStateFlow<TransferState?>(null)
    val transfer: StateFlow<TransferState?> = _transfer.asStateFlow()

    private val _backups = MutableStateFlow<List<ProviderBackup.Snapshot>>(emptyList())
    val backups: StateFlow<List<ProviderBackup.Snapshot>> = _backups.asStateFlow()

    fun clearTransferState() {
        _transfer.value = null
    }

    fun suggestedFileName(includeSecrets: Boolean): String =
        transferFileName(SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()), includeSecrets)

    /**
     * 导出。[open] 由界面从 SAF 的 uri 现开——**直接写进用户选的那个位置**，
     * 绝不先落 cacheDir 再分享：那等于把一份可能含明文 key 的文件递给临时选中的任意 App。
     */
    fun exportTo(includeSecrets: Boolean, open: () -> OutputStream?) = viewModelScope.launch {
        val text = buildTransferFile(
            settings = store.current(),
            includeSecrets = includeSecrets,
            now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()),
        )
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val stream = open() ?: return@runCatching false
                stream.use { it.write(text.toByteArray()) }
                true
            }.getOrDefault(false)
        }
        _transfer.value =
            if (ok) TransferState.Exported(includeSecrets) else TransferState.Failed(Failure.IO)
    }

    fun importFromFile(open: () -> InputStream?) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching { open()?.use { it.readBytes().decodeToString() } }.getOrNull()
        } ?: run {
            _transfer.value = TransferState.Failed(Failure.IO)
            return@launch
        }
        when (val parsed = parseTransferFile(text)) {
            is TransferParse.Err -> _transfer.value = TransferState.Failed(
                when (parsed.reason) {
                    TransferParse.Reason.NOT_JSON -> Failure.NOT_JSON
                    TransferParse.Reason.UNKNOWN_SCHEMA -> Failure.UNKNOWN_SCHEMA
                    TransferParse.Reason.EMPTY -> Failure.EMPTY
                },
            )
            is TransferParse.Ok -> mergeAndStore(
                claude = parsed.file.claude.map { it.toProfile() },
                codex = parsed.file.codex.map { it.toProfile() },
            )
        }
    }

    /**
     * 从 Rootfs 里现成的配置导入：`.claude/settings.json` 的 `env` 块、`.codex/config.toml`。
     *
     * Codex 那条**带不出 key** —— 那个文件里本来就没有（见 `CodexConfigToml` 的头注释，
     * key 只活在进程环境里）。导进来的是一条待填 key 的路由，这是对的。
     */
    fun importFromGuest(label: String) = viewModelScope.launch {
        val claude = configStore.readSettingsText()?.let { parseClaudeSettingsImport(it, label) }
        val codex = codexRuntime.readConfigToml()?.let { parseCodexConfigImport(it) }
        if (claude == null && codex == null) {
            _transfer.value = TransferState.Failed(Failure.NOTHING_FOUND)
            return@launch
        }
        mergeAndStore(listOfNotNull(claude), listOfNotNull(codex))
    }

    private suspend fun mergeAndStore(claude: List<ApiProfile>, codex: List<CodexProfile>) {
        val settings = store.current()
        val a = mergeClaudeImports(settings.profiles, claude)
        val b = mergeCodexImports(settings.codexProfiles, codex)
        store.importProfiles(a.added, b.added)
        _transfer.value = TransferState.Imported(
            added = a.added.size + b.added.size,
            skipped = a.skipped + b.skipped,
            renamed = a.renamed + b.renamed,
        )
    }

    fun loadBackups() = viewModelScope.launch { _backups.value = backup.list() }

    /**
     * 把某一份底写回 settings.json。写完立刻重新投影一次——不然文件是旧的、
     * `managedEnvKeys` 是新的，下一次同步会照着一张错的账去删键。
     */
    fun restoreBackup(snapshot: ProviderBackup.Snapshot) = viewModelScope.launch {
        val text = backup.read(snapshot.file)
        val ok = text != null &&
            backupTag(snapshot.name) == BACKUP_TAG_CLAUDE_SETTINGS &&
            configStore.writeSettingsText(text)
        if (!ok) {
            _transfer.value = TransferState.Failed(Failure.IO)
            return@launch
        }
        // 恢复回来的文件里没有我们的托管键了，账要跟着清空，再按当前供应商重写一遍
        store.setManagedEnvKeys(emptySet())
        _sync.value = providerSync.apply().claude
    }
}
