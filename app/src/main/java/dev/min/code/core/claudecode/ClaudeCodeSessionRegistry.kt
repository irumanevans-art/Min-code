package dev.min.code.core.claudecode

import android.content.Context
import android.util.Log
import dev.min.code.core.crash.CrashRecorder
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import java.util.UUID
import dev.min.code.core.session.SessionStatus

/**
 * 多会话注册表：同时持有多个 [ClaudeCodeManager]，每个管着自己的一个 CLI 进程。
 *
 * ## 为什么是这个形状
 *
 * [ClaudeCodeManager] 本来就完整封装了「一个会话」的全部生命周期（进程、stdin/stdout、
 * control_request 关联表、状态流）。所以要支持并发，不需要把它拆开重写 ——
 * 把它从 Koin single 改成 factory，再由本类持有多个实例即可。
 * 切换会话变成「换一个 activeKey」，**不再杀进程**，后台那些会话继续跑。
 *
 * ## 并发上限
 *
 * 每个会话 = 一个 Node 运行时跑在 proot 里，常驻约 200–400 MB。手机上无限开会直接 OOM，
 * 所以硬性限制 [MAX_CONCURRENT] 个**同时在跑**的会话；超了要求先停一个。
 * 注意上限只约束"活着的进程"，历史会话（transcript 在磁盘上）想开多少有多少。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeCodeSessionRegistry(
    private val context: Context,
    private val factory: () -> ClaudeCodeManager,
) {
    data class LiveSession(
        val key: String,
        val status: SessionStatus,
        val model: String?,
        val busy: Boolean,
        val isActive: Boolean,
        /** 这个会话的工作目录（guest 侧绝对路径）。会话终端按它决定 shell 开在哪儿 */
        val cwd: String = "",
        /**
         * 正在等待审批的工具名；没有待批请求时为 null。
         *
         * 后台通知要靠它：App 切到后台时权限 sheet 弹不出来，会话会**无声地**停在这里等到
         * 天荒地老 —— 用户以为任务还在跑，实际上它在等一次点击。
         */
        val pendingPermissionTool: String? = null,
        /** 顶栏那句实时状态（detail 优先，退回 phase），进通知副标题 */
        val statusText: String? = null,
        val errorMessage: String? = null,
        /** CLI 实时拟的标题；抽屉合并时人手改名优先于它 */
        val liveTitle: String? = null,
    ) {
        /**
         * 进程真的还活着。注册表里可能留着状态为 Closed/Failed 的条目（刚崩、还没被
         * [pruneDead] 回收，或者它就是当前活跃项），UI 不能把它们当运行中的会话展示。
         */
        val isLive: Boolean
            get() = status == SessionStatus.Running ||
                status == SessionStatus.Starting
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            // 只跑 stateIn/shareIn，风险低，但 SupervisorJob 的未捕获异常一样会崩 App
            Log.e(TAG, "uncaught exception in registry scope", e)
            CrashRecorder.record(context, Thread.currentThread(), e)
        }
    )

    /** key = CLI 的 session id（新建时我们自己先生成，好在进程起来前就能登记） */
    private val managers = LinkedHashMap<String, ClaudeCodeManager>()
    private val _keys = MutableStateFlow<List<String>>(emptyList())
    private val _activeKey = MutableStateFlow<String?>(null)

    val activeKey: StateFlow<String?> = _activeKey

    private val activeManager: StateFlow<ClaudeCodeManager?> = _activeKey
        .flatMapLatest { key -> flowOf(key?.let { synchronized(managers) { managers[it] } }) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 活跃会话的状态。没有活跃会话时给一个 Idle 默认值，
     * 这样 UI 侧不用到处判空，和改造前的单会话写法保持一致。
     */
    val state: StateFlow<ClaudeCodeManager.SessionState> = activeManager
        .flatMapLatest { it?.state ?: flowOf(ClaudeCodeManager.SessionState()) }
        .stateIn(scope, SharingStarted.Eagerly, ClaudeCodeManager.SessionState())

    /**
     * 活跃会话里被 Esc 撤回、要退还给输入框的消息。
     *
     * 只跟活跃会话：后台会话被撤回的消息塞进当前这个会话的输入框是张冠李戴。
     * `replay = 0` —— 这是一次性事件，补发等于切回来时输入框又被填一遍。
     */
    val withdrawnMessages: SharedFlow<ComposerDraft> = activeManager
        .flatMapLatest { it?.withdrawnMessages ?: emptyFlow() }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    /**
     * 任意会话发现的本机 loopback 预览 URL。
     * 合并所有 live manager，不只活跃会话——后台会话起的服务也要能打开。
     */
    val localPreviewUrls: SharedFlow<String> = _keys
        .flatMapLatest { keys ->
            val flows = keys.mapNotNull { key ->
                synchronized(managers) { managers[key] }?.localPreviewUrls
            }
            when {
                flows.isEmpty() -> emptyFlow()
                flows.size == 1 -> flows.first()
                else -> kotlinx.coroutines.flow.merge(*flows.toTypedArray())
            }
        }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    /**
     * 当前活着的会话列表，供抽屉打运行中标记。
     *
     * 必须真的去 collect 每个 manager 的 state —— 只 combine(keys, activeKey) 再读 `.value`
     * 是不会随子会话状态变化重算的（那样"运行中/忙"的标记会一直停在注册那一刻）。
     */
    val liveSessions: StateFlow<List<LiveSession>> = combine(_keys, _activeKey) { keys, active ->
        keys to active
    }.flatMapLatest { (keys, active) ->
        val flows = keys.mapNotNull { key ->
            val m = synchronized(managers) { managers[key] } ?: return@mapNotNull null
            m.state.map { st ->
                LiveSession(
                    key = key,
                    status = st.status,
                    model = st.model,
                    busy = st.busy,
                    isActive = key == active,
                    cwd = st.cwd,
                    pendingPermissionTool = st.pendingPermission?.toolName,
                    statusText = st.statusDetail ?: st.statusPhase,
                    errorMessage = st.errorMessage,
                    liveTitle = st.liveTitle,
                )
            }
        }
        if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * 至少还有一个会话进程活着。前台服务用它决定「起/停」。
     *
     * 单独开一个 flow 而不是让调用方 `map { it.any { … } }`：服务和通知器是两个订阅方，
     * 各自 map 会各自触发一次重算，而这条链上游是 N 个 manager 的 state 合并，不便宜。
     */
    val anyLive: StateFlow<Boolean> = liveSessions
        .map { list -> list.any { it.isLive } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * 至少有一个会话正在跑一轮任务。**唤醒锁只在这个为 true 时持有** ——
     * 会话开着但闲置时不该按住 CPU（Termux 的 wake lock 是手动开关，一按住就到退出为止，
     * 手机上那样会明显掉电；这里绑到 busy 上是同样的机制、更省电的策略）。
     */
    val anyBusy: StateFlow<Boolean> = liveSessions
        .map { list -> list.any { it.isLive && it.busy } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** 按 key 取 manager；通知里的「允许/拒绝」按钮要绕过 UI 直接应答 */
    fun get(key: String): ClaudeCodeManager? = synchronized(managers) { managers[key] }

    /**
     * 从通知应答权限请求。找不到会话（已退出/已回收）时静默忽略：通知可能比进程活得久。
     */
    fun answerPermission(key: String, allow: Boolean) {
        get(key)?.answerPermission(allow = allow, denyMessage = if (allow) null else "用户在通知里拒绝")
    }

    /** 当前活跃的 manager；没有就返回 null（UI 应先 newSession） */
    fun active(): ClaudeCodeManager? = _activeKey.value?.let { synchronized(managers) { managers[it] } }

    /**
     * 一个**不承载会话**的 manager 实例，只用来做与进程无关的事：
     * 读写 token / baseUrl、列举与删除磁盘上的 transcript。
     *
     * 没有它的话，「一个会话都没起」时连 token 都读不出来（页面第一步就卡住）。
     * 它永远不会被 startSession，所以不占进程、不计入并发上限。
     */
    fun configProbe(): ClaudeCodeManager = probe ?: synchronized(this) {
        probe ?: factory().also { probe = it }
    }

    @Volatile
    private var probe: ClaudeCodeManager? = null

    /**
     * 新建一个会话并切过去。达到并发上限时返回 null，由调用方提示用户。
     */
    fun newSession(options: ClaudeCodeManager.SessionOptions): ClaudeCodeManager? {
        pruneDead()
        if (liveCount() >= MAX_CONCURRENT) {
            Log.w(TAG, "refuse newSession: already $MAX_CONCURRENT live")
            return null
        }
        val key = UUID.randomUUID().toString()
        val manager = factory()
        register(key, manager)
        manager.startSession(options.copy(resumeSessionId = null, newSessionId = key))
        return manager
    }

    /**
     * 打开一个会话。
     * - 已经在跑 → 直接切过去，**不重启进程**
     * - 没在跑 → 新起一个进程并 `--resume` 续上，同时回放 transcript
     *
     * 达到并发上限且该会话不在跑时返回 null。
     */
    fun openSession(sessionId: String): ClaudeCodeManager? {
        synchronized(managers) { managers[sessionId] }?.let { existing ->
            if (existing.isLive) {
                _activeKey.value = sessionId
                return existing
            }
            // 登记着但进程已经死了（崩溃/退出）：丢掉这个壳，下面按"没在跑"重开
            closeSession(sessionId)
        }
        pruneDead()
        if (liveCount() >= MAX_CONCURRENT) {
            Log.w(TAG, "refuse openSession: already $MAX_CONCURRENT live")
            return null
        }
        val manager = factory()
        register(sessionId, manager)
        manager.openSession(sessionId)
        return manager
    }

    /** 只切换活跃会话，不做任何进程操作 */
    fun switchTo(key: String) {
        if (synchronized(managers) { managers.containsKey(key) }) _activeKey.value = key
    }

    /** 停掉某个会话的进程并从注册表移除；活跃项被移除时自动落到另一个 */
    fun closeSession(key: String) {
        val manager = synchronized(managers) { managers.remove(key) } ?: return
        manager.dispose()
        val remaining = synchronized(managers) { managers.keys.toList() }
        _keys.value = remaining
        if (_activeKey.value == key) _activeKey.value = remaining.lastOrNull()
    }

    /** 停掉全部（退出页面/清理时用） */
    fun closeAll() {
        val all = synchronized(managers) { managers.keys.toList() }
        all.forEach(::closeSession)
    }

    /**
     * 回收已经死掉的 manager（进程退出 / 崩溃 / 用户没显式停止）。
     *
     * 没有这一步的话，注册表只增不减：`closeSession()` 是唯一的移除点，而它只在用户手动
     * 停止或删除会话时被调用。会话一崩，界面弹回启动面板，用户点"启动会话"→ 又注册一个新
     * key，**旧的死壳永远留着**。它们会被 [liveSessions] 原样吐出来，在会话抽屉里堆成一排
     * 点不开的"新会话"（`updatedAt = Long.MAX_VALUE`，还全部置顶）。
     *
     * 当前活跃的那个不动 —— 用户正看着它的报错，不能从他眼皮底下抽走。
     */
    fun pruneDead() {
        val active = _activeKey.value
        val dead = synchronized(managers) {
            managers.filter { (key, m) -> key != active && !m.isLive }.keys.toList()
        }
        if (dead.isEmpty()) return
        Log.i(TAG, "pruning ${dead.size} dead session(s)")
        dead.forEach(::closeSession)
    }

    /**
     * 换了供应商之后，让活着的会话重新按新配置起一遍。
     *
     * **正忙的不动。** [ClaudeCodeManager.reloadConnection] 走的是「shutdown + --resume」，
     * 中途那一轮的流式输出会直接没掉 —— 而用户刚才只是在供应商列表上点了一下，
     * 不该因此丢一轮。这些会话原样留着，把 key 交回去，由界面打角标 + 给一颗手动重启。
     *
     * 也**不做**「这一轮跑完自动重启」：延迟生效的副作用比一个看得见的角标更难解释。
     *
     * @param includeBusy 用户在角标上点了「重启」—— 那是一次明确的选择，这时才连正忙的一起重起
     * @return 因为在忙而没动的会话 key
     */
    fun reloadConnection(includeBusy: Boolean = false): List<String> {
        val busy = mutableListOf<String>()
        val all = synchronized(managers) { managers.toMap() }
        all.forEach { (key, manager) ->
            if (!manager.isLive) return@forEach
            if (manager.state.value.busy && !includeBusy) {
                busy += key
            } else {
                manager.reloadConnection()
            }
        }
        if (busy.isNotEmpty()) Log.i(TAG, "reloadConnection skipped ${busy.size} busy session(s)")
        return busy
    }

    fun liveCount(): Int = synchronized(managers) { managers.values.count { it.isLive } }

    fun canStartMore(): Boolean = liveCount() < MAX_CONCURRENT

    private fun register(key: String, manager: ClaudeCodeManager) {
        synchronized(managers) { managers[key] = manager }
        _keys.value = synchronized(managers) { managers.keys.toList() }
        _activeKey.value = key
    }

    companion object {
        private const val TAG = "ClaudeCodeRegistry"

        /**
         * 同时在跑的会话上限。每个会话是一个独立的 Node 进程跑在 proot 里，
         * 常驻 200–400 MB —— 手机上开到第四个基本就开始压缩换页了。
         */
        const val MAX_CONCURRENT = 3
    }
}
