package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.BackgroundShell
import dev.min.code.core.claudecode.ClaudeCodeConfigStore
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodePermissionMode
import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.claudecode.ComposerDraftStore
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
    private val readShellOutput: suspend (BackgroundShell) -> String? = { null },
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
        val manager = registry.newSession(
            base.copy(
                skipPermissions = mode == ClaudeCodePermissionMode.BYPASS,
                permissionMode = mode,
                model = options.modelId ?: base.model,
                effort = if (ultracode) null else options.effortId ?: base.effort,
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

    private fun attach(key: String, manager: ClaudeCodeManager): ClaudeCodeSession {
        val session = ClaudeCodeSession(
            manager = manager, key = key, parentScope = scope, externals = context, metaStore = metaStore,
            configStore = configStore, stopHostedService = stopHostedService, readShellOutput = readShellOutput,
        )
        // 注册表可能在 newSession / openSession 里 pruneDead 掉了别的会话：只留它还认的
        sessions.value = sessions.value.filterKeys { registry.get(it) != null } + (key to session)
        return session
    }

    override suspend fun closeAll(reason: CloseReason) {
        registry.closeAll()
        sessions.value = emptyMap()
    }

}

/** 达到并发上限。通用注册表在调用前已经判过上限，这里是兜底（两处各算各的，不会互相冲突） */
class AtCapacityException(val limit: Int) : IllegalStateException("已达同时在线会话上限（$limit）")
