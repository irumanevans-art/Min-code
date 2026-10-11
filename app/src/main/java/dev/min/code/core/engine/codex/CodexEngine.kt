package dev.min.code.core.engine.codex

import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.claudecode.CwdPath
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.codex.CodexDraftStore
import dev.min.code.core.codex.CodexPermissionPreset
import dev.min.code.core.codex.orUnset
import dev.min.code.core.codex.readCodexRollout
import dev.min.code.core.codex.withPermission
import dev.min.code.core.engine.AgentEngine
import dev.min.code.core.engine.AgentSession
import dev.min.code.core.engine.CloseReason
import dev.min.code.core.engine.EngineAvailability
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.LiveSessionSummary
import dev.min.code.core.engine.SessionCatalog
import dev.min.code.core.engine.StartOptions
import dev.min.code.core.engine.toLiveSummary
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Codex 引擎（设计 §4.2）：单例 [CodexAppServerManager] 一次承载一个会话（`maxConcurrent = 1`）。
 * **B4 不接线**：没有人构造它，manager 一行没改。
 *
 * - [create]：`prepare()`（写 config.toml）-> `manager.start(options)` -> 等 thread 就绪拿到 threadId。
 *   通用 [AgentSession.ref] 要 threadId，而 Codex 到握手完成才有，所以 create 是「等握手」的挂起调用；
 * - [open]：只读回放（`manager.restore`），**不起进程**，与 `CodexVM.openSession` 一致（点开看看和继续跑是两件事）。
 *   rollout 的最后一个回合没收尾就留一条结局，▶ 据此出现；接回进程（`thread/resume`）在 ▶ 里（[relaunch]）。
 *
 * @param prepare `CodexRuntime.prepare()`：每次起进程前把非机密的路由配置写进 guest
 * @param codexHome `CodexRuntime.codexHome()`
 * @param defaultOptions 新会话的默认（连接配置里的模型 / 强度），[StartOptions] 里给了的覆盖它
 * @param searchFiles `WorkspaceRepository.mentionFiles`
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodexEngine internal constructor(
    private val manager: CodexAppServerManager,
    private val scope: CoroutineScope,
    override val availability: StateFlow<EngineAvailability>,
    private val prepare: suspend () -> Unit,
    private val codexHome: () -> File,
    private val metaStore: ClaudeCodeSessionMetaStore? = null,
    drafts: CodexDraftStore? = null,
    private val defaultOptions: () -> CodexAppServerManager.Options = { CodexAppServerManager.Options() },
    private val searchFiles: suspend (String, Int) -> List<String> = { _, _ -> emptyList() },
    private val handshakeWaitMs: Long = CodexAppServerManager.HANDSHAKE_TIMEOUT_MS + 5_000,
    /** B5–B8 保留旧监督路径，B9 并页后才向统一注册表报告，避免重复通知与计数。 */
    private val publishLive: Boolean = true,
) : AgentEngine {
    override val kind: EngineKind = EngineKind.Codex
    override val capabilities: EngineCapabilities = EngineCapabilities.Codex

    private val codexCatalog = CodexSessionCatalog(codexHome, metaStore, drafts, manager)
    override val catalog: SessionCatalog = codexCatalog

    private val current = MutableStateFlow<CodexSession?>(null)

    /**
     * 在线会话：进程活着（含启动中）就是在线，[LiveSessionSummary.keepAlive] 同样 = 进程活着
     * （app-server 是 App 的子进程，没有前台服务撑着切出去接个电话就没了）。只读回放（Idle）不在线。
     */
    override val live: StateFlow<List<LiveSessionSummary>> = current.flatMapLatest { session ->
        if (!publishLive || session == null) {
            flowOf(emptyList())
        } else {
            session.state.map { s ->
                if (s.status == SessionStatus.Running || s.status == SessionStatus.Starting) {
                    listOf(s.toLiveSummary(keepAlive = true))
                } else {
                    emptyList()
                }
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun create(options: StartOptions): Result<AgentSession> = runCatching {
        check(!manager.isLive.value) { "Codex 同一时间只能有一个在线会话" }
        withContext(Dispatchers.IO) { prepare() }
        check(manager.start(threadOptions(options), resumeThreadId = null)) { "app-server 起不来（已有进程在跑）" }
        // 等握手：Running 且拿到 threadId = 成功；Failed / Closed = 失败（原因在 errorMessage）
        val settled = withTimeoutOrNull(handshakeWaitMs) {
            manager.state.first { (it.status == SessionStatus.Running && it.threadId != null) || it.status == SessionStatus.Failed || it.status == SessionStatus.Closed }
        } ?: error("Codex 握手超时")
        if (settled.status != SessionStatus.Running) error(settled.errorMessage ?: "Codex 会话没起来")
        attach(checkNotNull(settled.threadId))
    }

    override suspend fun open(id: String): Result<AgentSession> = runCatching {
        current.value?.takeIf { it.ref.id == id && manager.state.value.threadId == id }?.let { return@runCatching it }
        val summary = codexCatalog.summaries().firstOrNull { it.threadId == id } ?: error("找不到这个 Codex 会话：$id")
        val rollout = withContext(Dispatchers.IO) { readCodexRollout(summary.file) }
        // 正跑着别的会话时 restore 返回 false，不覆盖实时状态
        check(manager.restore(items = rollout.items, threadId = id, endedMidTurn = rollout.endedMidTurn)) { "Codex 正在运行另一个会话，先停掉它" }
        attach(id)
    }

    private fun attach(threadId: String): CodexSession {
        val titles = metaStore?.items ?: MutableStateFlow(emptyMap())
        val externals = titles.map { metas ->
            CodexMapContext(title = metas[threadId]?.title?.takeIf { it.isNotBlank() })
        }.stateIn(scope, SharingStarted.Eagerly, CodexMapContext(title = titles.value[threadId]?.title))
        val session = CodexSession(manager, threadId, scope, externals, metaStore, searchFiles, ::relaunch)
        current.value = session
        return session
    }

    /**
     * ▶ 要先把进程接回来：写好路由配置、`thread/resume` 同一条线程，等握手收尾。排队的话与 ▶ 的结局由 manager 的
     * `start` 带过去（同一条线程）。这条线程在面板里改过的设置也由 manager 带着（`customizedThreadId`），没改过就用连接配置的默认。
     */
    private suspend fun relaunch(threadId: String): Boolean {
        withContext(Dispatchers.IO) { prepare() }
        if (!manager.start(defaultOptions(), resumeThreadId = threadId)) return false
        val settled = withTimeoutOrNull(handshakeWaitMs) {
            manager.state.first { (it.status == SessionStatus.Running && it.threadId != null) || it.status == SessionStatus.Failed || it.status == SessionStatus.Closed }
        }
        return settled?.status == SessionStatus.Running
    }

    override suspend fun closeAll(reason: CloseReason) {
        current.value?.close(reason)
        manager.stop()
        current.value = null
    }

    /** 通用的开局参数 -> Codex 的 [CodexAppServerManager.Options]：只覆盖给了的项，其余用连接配置的默认 */
    private fun threadOptions(options: StartOptions): CodexAppServerManager.Options {
        var result = defaultOptions().copy(cwd = CwdPath.normalize(options.cwd))
        options.modelId?.let { result = result.copy(model = it.orUnset()) }
        options.effortId?.let { result = result.copy(effort = it.orUnset()) }
        options.engineOptions[CodexStateMapper.OPTION_SUMMARY]?.let { result = result.copy(summary = it.orUnset()) }
        options.modeId?.let { id -> CodexPermissionPreset.entries.firstOrNull { it.id == id }?.let { result = result.withPermission(it) } }
        return result
    }
}
