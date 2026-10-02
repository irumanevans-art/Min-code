package dev.min.code.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 三个引擎统一的注册表（设计 §3.9）：活跃会话、界面打开着的会话、各引擎在线会话的合并视图，
 * 以及 create / open / switchTo / close / submit。`ui/session` 的 `SessionVM`、前台服务、通知接收器
 * 都只读它。原型是 `ClaudeCodeSessionRegistry`（多实例、activeKey、liveSessions、anyLive / anyBusy、上限）。
 *
 * 这一单只写类型与纯逻辑能测的部分，**不接 Koin**；CC 独有的那些逻辑（`pruneDead`、`configProbe`、
 * `reloadConnection`、通知应答的 requestId 校验）属于 `ClaudeCodeEngine`，不在这里。
 *
 * ## 几处选择（设计没写清）
 *
 * - **并发上限**按引擎各算各的：占位数 = 该引擎「在线且保活」的会话 ∪ 界面打开着的会话；
 *   上限取 [EngineCapabilities.maxConcurrent]。打开一个已经占着位的会话（Min 的后台会话）不算新增。
 * - **关掉活跃会话后**活跃项落到剩下的最后一个（与 `ClaudeCodeSessionRegistry.closeSession` 一致），没有就 null。
 * - [submit] 对没打开的会话先经引擎 `open` 取到会话再提交，但**不把它挂进 [attached]**：
 *   通知按钮应答不该把一个会话塞进用户的界面。谁持有那个会话由引擎负责（Min 的 LRU）。
 * - [create] / [open] 在一把锁里做「查上限 + 建」，并发点两下不会超限。
 */
class AgentSessionRegistry(engines: List<AgentEngine>, scope: CoroutineScope) {
    init {
        require(engines.map { it.kind }.toSet().size == engines.size) { "duplicate engine kind: ${engines.map { it.kind }}" }
    }

    val engines: List<AgentEngine> = engines.toList()
    private val byKind: Map<EngineKind, AgentEngine> = engines.associateBy { it.kind }

    /** 保护 attached / activeRef / active 三者一起变，读者看不到半截状态 */
    private val lock = Any()
    private val _attached = MutableStateFlow<List<AgentSession>>(emptyList())
    private val _activeRef = MutableStateFlow<SessionRef?>(null)
    private val _active = MutableStateFlow<AgentSession?>(null)

    /** create / open 的「查上限 + 建」必须是一步 */
    private val attachMutex = Mutex()

    val activeRef: StateFlow<SessionRef?> = _activeRef
    val active: StateFlow<AgentSession?> = _active

    /** 界面打开过、还没关的，按打开顺序 */
    val attached: StateFlow<List<AgentSession>> = _attached

    /** 各引擎 `live` 的合并，[LiveSessionSummary.isActive] / [LiveSessionSummary.attached] 在这里填 */
    val live: StateFlow<List<LiveSessionSummary>> = run {
        val perEngine = if (engines.isEmpty()) flowOf(emptyList<List<LiveSessionSummary>>())
        else combine(engines.map { it.live }) { it.toList() }
        combine(perEngine, _attached, _activeRef) { lists, attached, active ->
            mergeLiveSummaries(lists, attached.map { it.ref }.toSet(), active)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    /** 至少一个会话要保活 App 进程。前台服务用它决定起 / 停；单独开一条 flow，几个订阅方不各自重算 */
    val anyKeepAlive: StateFlow<Boolean> = live.map { list -> list.any { it.keepAlive } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** 至少一个会话有回合在跑。唤醒锁只在它为 true 时持有 */
    val anyBusy: StateFlow<Boolean> = live.map { list -> list.any { it.busy } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun engine(kind: EngineKind): AgentEngine =
        byKind[kind] ?: throw NoSuchElementException("no engine registered for $kind")

    suspend fun create(kind: EngineKind, options: StartOptions): CreateResult {
        val engine = engine(kind)
        val availability = engine.availability.value
        if (availability !is EngineAvailability.Ready) return AttachResult.Unavailable(availability)
        return attachMutex.withLock {
            if (occupiedCount(engine) >= engine.capabilities.maxConcurrent) {
                return@withLock AttachResult.AtCapacity(engine.capabilities.maxConcurrent)
            }
            attach(engine.create(options))
        }
    }

    /**
     * 打开一个会话并切过去：已经打开就直接切（不重开）；没打开就经引擎 `open`（回放 / `--resume` / `hub.open`）。
     * 达到上限且它本来不占位时返回 [AttachResult.AtCapacity]。
     */
    suspend fun open(ref: SessionRef): OpenResult {
        synchronized(lock) { _attached.value.firstOrNull { it.ref == ref } }?.let { existing ->
            switchTo(ref)
            return AttachResult.Ok(existing)
        }
        val engine = engine(ref.engine)
        val availability = engine.availability.value
        if (availability !is EngineAvailability.Ready) return AttachResult.Unavailable(availability)
        return attachMutex.withLock {
            // 等锁期间别的调用可能已经打开了它
            synchronized(lock) { _attached.value.firstOrNull { it.ref == ref } }?.let { existing ->
                switchTo(ref)
                return@withLock AttachResult.Ok(existing)
            }
            if (ref !in occupiedRefs(engine) && occupiedCount(engine) >= engine.capabilities.maxConcurrent) {
                return@withLock AttachResult.AtCapacity(engine.capabilities.maxConcurrent)
            }
            attach(engine.open(ref.id))
        }
    }

    /** 只切换活跃会话，不做任何进程操作。没打开的会话忽略 */
    fun switchTo(ref: SessionRef) {
        synchronized(lock) {
            val session = _attached.value.firstOrNull { it.ref == ref } ?: return
            _activeRef.value = ref
            _active.value = session
        }
    }

    /** 关掉一个打开着的会话；活跃项被关时落到剩下的最后一个。没打开的会话忽略 */
    suspend fun close(ref: SessionRef, reason: CloseReason) {
        val session = synchronized(lock) {
            val found = _attached.value.firstOrNull { it.ref == ref } ?: return
            val remaining = _attached.value - found
            _attached.value = remaining
            if (_activeRef.value == ref) {
                _activeRef.value = remaining.lastOrNull()?.ref
                _active.value = remaining.lastOrNull()
            }
            found
        }
        session.close(reason)
    }

    /** 停掉全部（「停止全部」通知、退出页面）。引擎自己负责关它名下所有会话，含没在界面上打开的 */
    suspend fun closeAll(reason: CloseReason) {
        synchronized(lock) {
            _attached.value = emptyList()
            _activeRef.value = null
            _active.value = null
        }
        engines.forEach { it.closeAll(reason) }
    }

    /**
     * 通知按钮、adb 调试入口用：把一条命令交给某个会话。会话没打开就先经引擎 `open` 取到再提交，
     * 不挂进 [attached]（见类注释）。打不开返回 [CommandResult.Rejected]。
     */
    suspend fun submit(ref: SessionRef, command: SessionCommand): CommandResult {
        val attachedSession = synchronized(lock) { _attached.value.firstOrNull { it.ref == ref } }
        if (attachedSession != null) return attachedSession.submit(command)
        val opened = engine(ref.engine).open(ref.id)
        opened.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
            return CommandResult.Rejected(error.message ?: "无法打开会话")
        }
        return opened.getOrThrow().submit(command)
    }

    // ---- 内部 ----

    private fun attach(created: Result<AgentSession>): AttachResult {
        created.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
            return AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
        val session = created.getOrThrow()
        synchronized(lock) {
            // 引擎对同一个会话重复返回时替换而不是重复挂
            _attached.value = _attached.value.filterNot { it.ref == session.ref } + session
            _activeRef.value = session.ref
            _active.value = session
        }
        return AttachResult.Ok(session)
    }

    private fun occupiedRefs(engine: AgentEngine): Set<SessionRef> {
        val running = engine.live.value.filter { it.keepAlive }.map { it.ref }
        val opened = synchronized(lock) { _attached.value.map { it.ref } }.filter { it.engine == engine.kind }
        return (running + opened).toSet()
    }

    private fun occupiedCount(engine: AgentEngine): Int = occupiedRefs(engine).size
}

/** [AgentSessionRegistry.create] / [AgentSessionRegistry.open] 的结果 */
sealed interface AttachResult {
    data class Ok(val session: AgentSession) : AttachResult

    /** 该引擎同时在线的会话已达 [EngineCapabilities.maxConcurrent]，请用户先结束一个 */
    data class AtCapacity(val max: Int) : AttachResult

    data class Unavailable(val availability: EngineAvailability) : AttachResult

    data class Failed(val message: String) : AttachResult
}

/** 设计里 create 与 open 各有一个结果类型，形状一样，合成 [AttachResult] 用别名保留两个名字 */
typealias CreateResult = AttachResult
typealias OpenResult = AttachResult

/**
 * 把各引擎的 live 摘要拼成一张表，填上 [LiveSessionSummary.isActive] / [LiveSessionSummary.attached]。
 * 纯函数：引擎顺序与引擎内顺序都原样保留。
 */
internal fun mergeLiveSummaries(
    perEngine: List<List<LiveSessionSummary>>,
    attached: Set<SessionRef>,
    active: SessionRef?,
): List<LiveSessionSummary> = perEngine.flatten().map {
    it.copy(isActive = it.ref == active, attached = it.ref in attached)
}
