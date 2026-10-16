package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.BackgroundShell
import dev.min.code.core.claudecode.ClaudeCodeConfigStore
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodePermissionMode
import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.claudecode.ComposerDraftStore
import dev.min.code.core.claudecode.OutputTail
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
import dev.min.code.core.service.LocalService
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn

/**
 * Claude Code 引擎（设计 §4.1）：包住现有的 [ClaudeCodeSessionRegistry]（多会话、并发上限 3、pruneDead），
 * 把它持有的每个 [ClaudeCodeManager] 套成 [ClaudeCodeSession]。注册表和 manager 的行为保留；
 * B5 接线后也观察旧页面创建的会话，B9 再把旧注册表的逻辑拆进来。
 *
 * @param availability 环境是否就绪（CLI 装好、已连接）。由组合根算好传进来：这个类不认识 installer / settings
 * @param currentProfile 当前激活的供应商档 id 与「id -> 名字」表，映射出「会话还在用旧档」的角标
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeCodeEngine internal constructor(
    private val registry: ClaudeCodeSessionRegistry,
    private val scope: CoroutineScope,
    override val availability: StateFlow<EngineAvailability>,
    private val metaStore: ClaudeCodeSessionMetaStore? = null,
    drafts: ComposerDraftStore? = null,
    private val configStore: ClaudeCodeConfigStore? = null,
    services: StateFlow<List<LocalService>> = MutableStateFlow(emptyList()),
    currentProfile: StateFlow<Pair<String?, Map<String, String>>> = MutableStateFlow(null to emptyMap()),
    private val stopHostedService: (String) -> Unit = {},
    private val readShellOutput: suspend (BackgroundShell, sessionId: String?) -> OutputTail? = { _, _ -> null },
) : AgentEngine {
    override val kind: EngineKind = EngineKind.ClaudeCode
    override val capabilities: EngineCapabilities = EngineCapabilities.ClaudeCode

    override val catalog: SessionCatalog = ClaudeSessionCatalog(
        probe = registry::configProbe,
        metaStore = metaStore,
        drafts = drafts,
        isLive = { registry.get(it)?.isLive == true },
    )

    private val sessions = MutableStateFlow<Map<String, ClaudeCodeSession>>(emptyMap())

    private val context: StateFlow<ClaudeMapContext> = combine(services, currentProfile) { svc, (id, labels) ->
        ClaudeMapContext(services = svc, currentProfileId = id, profileLabels = labels)
    }.stateIn(scope, SharingStarted.Eagerly, ClaudeMapContext())

    /**
     * 在线会话（进程活着）的摘要，供 Supervisor / 前台服务 / 抽屉用。CC 的 [LiveSessionSummary.keepAlive] 就是
     * 「进程还活着」；崩了的壳（Closed / Failed）不算在线，与 `ClaudeCodeSessionRegistry.LiveSession.isLive` 同口径。
     */
    // B5–B8 页面仍经旧注册表建会话；在线来源必须跟着它，不能只看新接口交出去的包装。
    // 保留 Closed / Failed 那一帧供监督器判退出，只把 keepAlive 置假。
    override val live: StateFlow<List<LiveSessionSummary>> = combine(registry.liveSessions, context) { live, external ->
        live.mapNotNull { summary ->
            registry.get(summary.key)?.let { manager ->
                // 状态与保活必须来自同一帧：旧聚合流可能还在 Idle，而 manager 已经 Running。
                val state = ClaudeStateMapper.map(claudeRef(summary.key), manager.state.value, external)
                state.takeUnless { it.status == SessionStatus.Idle }?.toLiveSummary(
                    keepAlive = state.status == SessionStatus.Running || state.status == SessionStatus.Starting,
                )
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun create(options: StartOptions): Result<AgentSession> = runCatching {
        val base = registry.configProbe().lastOptions()
        val mode = options.modeId?.let(ClaudeCodePermissionMode::fromWire) ?: base.permissionMode
        val ultracode = options.effortId == ClaudeStateMapper.OPTION_ULTRACODE
        // 空串 = 明确「跟随默认」（[ClaudeStateMapper.FOLLOW_DEFAULT]），与 null（没说，沿用上次）不同：
        // 抽屉里「新会话」要照搬当前会话的设置，当前会话没选过模型就该是没选过，而不是借上次存的那个
        val manager = registry.newSession(
            base.copy(
                skipPermissions = mode == ClaudeCodePermissionMode.BYPASS,
                permissionMode = mode,
                model = if (options.modelId == null) base.model else options.modelId.ifEmpty { null },
                effort = if (ultracode) null else if (options.effortId == null) base.effort else options.effortId.ifEmpty { null },
                ultracode = ultracode,
                cwd = options.cwd,
                promptCacheTtl = options.engineOptions[ClaudeStateMapper.OPTION_CACHE_TTL] ?: base.promptCacheTtl,
            ),
        ) ?: throw AtCapacityException(capabilities.maxConcurrent)
        // register 在 newSession 里同步完成并把它设成活跃项，所以此刻的 activeKey 就是新会话的 key
        attach(checkNotNull(registry.activeKey.value) { "注册表没有活跃会话" }, manager)
    }

    override suspend fun open(id: String): Result<AgentSession> = runCatching {
        // 通知按钮只包装已有进程，不切活跃项、不重启；点击直达时由 MainActivity 同步旧活跃项。
        registry.get(id)?.takeIf { it.isLive }?.let { manager ->
            return@runCatching sessions.value[id]?.takeIf { it.manager === manager } ?: attach(id, manager)
        }
        val existing = sessions.value[id]
        if (existing != null && existing.manager.isLive) return@runCatching existing
        val manager = registry.openSession(id) ?: throw AtCapacityException(capabilities.maxConcurrent)
        attach(id, manager)
    }

    override fun peek(id: String): AgentSession? = registry.get(id)?.takeIf { it.isLive }?.let { sessionFor(id) }

    /** 取或建：同一个 manager 只有一个包装。建的同时丢掉旧注册表已经不认的那些 */
    private fun attach(key: String, manager: ClaudeCodeManager): ClaudeCodeSession = synchronized(sessions) {
        // 旧注册表登记 manager 的同时就把它设成活跃项，[activeSession] 在另一个线程上立刻来取同一个包装，
        // 而 create / open 紧接着也要取：两边必须拿到同一个，否则后到的把先到的撤掉，界面就绑在一个已经停了订阅的壳上
        sessions.value[key]?.takeIf { it.manager === manager }?.let { return it }
        val session = ClaudeCodeSession(
            manager = manager, key = key, parentScope = scope, externals = context, metaStore = metaStore,
            configStore = configStore, stopHostedService = stopHostedService, readShellOutput = readShellOutput,
        )
        // 注册表可能在 newSession / openSession 里 pruneDead 掉了别的会话：只留它还认的，丢掉的撤掉它订阅的流
        val old = sessions.value
        val kept = old.filterKeys { registry.get(it) != null && it != key }
        (old - kept.keys).values.forEach { it.release() }
        sessions.value = kept + (key to session)
        session
    }

    /**
     * 旧注册表里 [key] 这个会话套成的通用会话；没有这个会话返回 null。**不看进程死活、不会重启它**：
     * 页面要显示崩掉 / 已结束的会话，`open` 对死进程会重开，不能用在这里。
     * 同一个 manager 返回同一个包装（订阅只建一份）。
     */
    fun sessionFor(key: String): ClaudeCodeSession? {
        val manager = registry.get(key) ?: return null
        return attach(key, manager)
    }

    /**
     * 界面当前显示的会话：旧注册表的活跃项（它至今仍是「谁是活跃会话」的唯一来源，B9 才把它拆进来）套成通用会话。
     * B6 的页面只经这里读状态、发命令；建 / 开 / 关会话仍走旧注册表，原因见 `SessionVM` 头注释。
     */
    val activeSession: StateFlow<ClaudeCodeSession?> = registry.activeKey
        .map { key -> key?.let(::sessionFor) }
        // 初值同步取：页面（VM）在转屏 / 回页面时重建，会话早就在跑，第一帧读到 null 会闪出启动面板
        .stateIn(scope, SharingStarted.Eagerly, registry.activeKey.value?.let(::sessionFor))

    override suspend fun closeAll(reason: CloseReason) {
        registry.closeAll()
        sessions.getAndUpdate { emptyMap() }.values.forEach { it.release() }
    }

}

/** 达到并发上限。通用注册表在调用前已经判过上限，这里是兜底（两处各算各的，不会互相冲突） */
class AtCapacityException(val limit: Int) : IllegalStateException("已达同时在线会话上限（$limit）")
