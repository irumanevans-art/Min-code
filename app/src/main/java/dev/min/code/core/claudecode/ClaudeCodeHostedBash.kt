package dev.min.code.core.claudecode

import android.util.Log
import dev.min.code.core.rootfs.DEFAULT_CWD
import dev.min.code.core.service.AgentServiceHost
import dev.min.code.core.session.ChatItem
import dev.min.code.util.LocalUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** CLI 侧的 Bash 工具名；托管与本地预览的判定都靠它认命令 */
internal const val TOOL_BASH = "Bash"

/**
 * 后台 / 长驻 Bash 的托管，和由此（以及工具输出里）冒出来的本机预览地址。
 *
 * 从 [ClaudeCodeManager] 里整段搬出来的。该不该托管、等结局、结局怎么告诉模型是 HostedBash.kt 里的纯函数；
 * 这里管的是它和**会话**的接缝：什么时候托管（有权限闸门 / 没有闸门）、迟到的 deny 还能不能发、
 * 结局落到哪张工具卡上、预览地址往哪发。
 *
 * 依赖收窄成这条路真用到的几样：进程表、会话状态、当前 IO 作用域、轮次号 / 打断标记（判断 deny 过没过期）、
 * 应答 CLI 的出口。**不认识 Manager**，也不碰 Context。
 */
internal class ClaudeCodeHostedBash(
    /** Min 的进程表；null（测试里常见）= 不托管，Bash 一律交给 CLI 自己跑 */
    private val services: AgentServiceHost?,
    private val state: MutableStateFlow<ClaudeCodeManager.SessionState>,
    /** 当前进程的 IO 作用域；换档后会变，所以每次现取 */
    private val sessionScope: () -> CoroutineScope?,
    /** 当前轮次号（[ClaudeCodeManager] 每轮自增） */
    private val currentTurn: () -> Int,
    /** 停止键打断的是哪一轮，没有就是 null */
    private val interruptedTurn: () -> Int?,
    /** 应答一条 CLI 发来的 control_request；CLI 已撤回的不写，返回 false */
    private val answerCli: (requestId: String, frame: String) -> Boolean,
    private val newId: () -> String,
) {
    /**
     * 本机预览 URL（loopback）。UI 收集后 **填预览位**（可自动展开一次）。
     * 不经系统浏览器。
     */
    private val _localPreviewUrls = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val localPreviewUrls: SharedFlow<String> = _localPreviewUrls.asSharedFlow()

    /** 权限路径已排他托管的 Bash tool_use_id，避免 ToolUse 上再 start 一次 */
    private val exclusiveToolUses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 这条 tool_use 已经被权限路径排他托管了：卡由托管结局来标，CLI 随后回的 deny 结果不能再改它 */
    fun isExclusive(toolUseId: String): Boolean = toolUseId in exclusiveToolUses

    /** result 的 `permission_denials` 里去重用，见 [deniedByRule] */
    val exclusiveToolUseIds: Set<String> get() = exclusiveToolUses

    /**
     * Manual 等会发 can_use_tool 的模式：后台/长驻 Bash **只**进进程表，
     * deny CLI 原命令，避免双 proot 抢端口。
     *
     * deny **等服务出结论之后**才回（最长 10 秒，见 [awaitHostSettled]）：以前是立刻回「已托管」，
     * 服务一启动就死了模型也不知道，还会让用户去开预览。现在死了就把退出码和日志尾写进 deny，
     * 模型自己去修。等的这段时间 CLI 本来就停在这条权限请求上，不会往下跑。
     *
     * 等待期间会话可能已经变了，这时迟到的 deny 不能再发：
     * - 用户按了停止：CLI 会发 control_cancel_request 撤销这条请求、自己把工具判成被拒并收尾，
     *   不等我们应答（2.1.280 实测，夹具 interrupt_during_permission；之后补来的 deny 它静默丢掉）；
     * - 没按停止、CLI 也撤回了这条请求（钩子先做了决定等，见 [ClaudeCodeManager] 的 onCliWithdrew）：由 [answerCli] 挡掉；
     * - 换档 / 关会话：进程换了，写队列此刻连着的是新进程，request_id 对不上。
     * 服务本身照样留在进程表里，对话流里照样如实告诉用户结局。
     *
     * @return true 表示已接手，调用方不要再挂权限 sheet
     */
    fun tryHostInsteadOfPermission(event: ClaudeCodeEvent.PermissionRequest): Boolean {
        if (!event.toolName.equals(TOOL_BASH, ignoreCase = true)) return false
        val plan = hostedBashPlan(event.input) ?: return false
        val registry = services ?: return false
        val io = sessionScope() ?: return false
        val cwd = state.value.cwd.ifBlank { DEFAULT_CWD }
        val label = event.input["description"].asStringOrNull()?.takeIf { it.isNotBlank() }
            ?: event.description
        val sessionKey = state.value.sessionId
        val turn = currentTurn()
        // 登记要在 CLI 回 tool_result 之前：那条 deny 的结果不能把卡改掉（卡由这里按结局标）
        event.toolUseId?.takeIf { it.isNotBlank() }?.let(exclusiveToolUses::add)
        io.launch {
            val outcome = hostAndSettle(registry, plan, cwd, label, sessionKey)
            Log.i(TAG, "exclusive host (permission path) -> ${outcome::class.simpleName}: ${plan.command.take(80)}")
            val sameSession = sessionScope() === io && interruptedTurn() != turn && currentTurn() == turn
            val told = sameSession && answerCli(
                event.requestId,
                encodeClaudeCodePermissionResponse(
                    requestId = event.requestId,
                    allow = false,
                    denyMessage = hostedDenyMessage(outcome, plan.port),
                ),
            )
            if (!told) {
                Log.i(TAG, "hosted outcome not sent: the turn was stopped, the CLI withdrew the request, or it was relaunched")
            }
            showOutcome(event.toolUseId, outcome, plan.port, toldModel = told)
        }
        return true
    }

    /**
     * 无 permission 闸门时（bypass / 规则已 allow）：若 shouldHost，尝试进表。
     * startFromAgent 对同 command+cwd+port 去重；PortBusy 则只绑预览，不装第二套。
     */
    fun hostWithoutPermissionGate(event: ClaudeCodeEvent.ToolUse) {
        val registry = services ?: return
        val (cleaned, port) = hostedBashPlan(event.input) ?: return
        val cwd = state.value.cwd.ifBlank { DEFAULT_CWD }
        val label = event.input["description"].asStringOrNull()
        val sessionKey = state.value.sessionId
        sessionScope()?.launch {
            val result = registry.startFromAgent(
                command = cleaned,
                cwdGuest = cwd,
                port = port,
                label = label,
                sourceSessionKey = sessionKey,
            )
            result.onSuccess { id ->
                Log.i(TAG, "hosted local service $id (no-gate): ${cleaned.take(80)}")
                if (port != null) offerPreview(LocalUrls.loopbackUrl(port))
            }.onFailure { err ->
                Log.w(TAG, "host (no-gate) skipped/failed: ${err.message}")
                // CLI 可能已占端口：至少把预览位填上
                if (port != null) offerPreview(LocalUrls.loopbackUrl(port))
            }
        }
    }

    /** [text]（工具输出、托管的地址……）里认得出的 loopback 地址发给预览位 */
    fun offerPreview(text: String) {
        if (LocalUrls.isLoopbackHttp(text)) {
            _localPreviewUrls.tryEmit(LocalUrls.normalizeLoopback(text))
            return
        }
        for (u in LocalUrls.findLocalPreviewUrls(text)) {
            _localPreviewUrls.tryEmit(u)
        }
    }

    /** 托管的结局落到对话流和工具卡上；服务真跑起来了才弹预览位 */
    private fun showOutcome(toolUseId: String?, outcome: HostOutcome, port: Int?, toldModel: Boolean) {
        val told = if (toldModel) "。已把原因告诉模型。" else "。模型还不知道它没起来。"
        when (outcome) {
            HostOutcome.Up -> {
                val portNote = port?.let { " · preview http://127.0.0.1:$it" }.orEmpty()
                appendItem(
                    ChatItem.Note(
                        newId(),
                        "已托管到进程表（后台/长驻 Bash，不在会话里再跑一遍）" +
                            (port?.let { " · :$it" } ?: "") +
                            " · 预览位可开",
                    ),
                )
                toolUseId?.let { toolId ->
                    state.update { st ->
                        st.copy(
                            items = st.items.mapToolCall(toolId) {
                                it.copy(
                                    status = ChatItem.ToolCall.Status.Done,
                                    result = "hosted by Min process table$portNote",
                                )
                            },
                        )
                    }
                }
                if (port != null) offerPreview(LocalUrls.loopbackUrl(port))
            }

            is HostOutcome.Died -> {
                hostedEarlyExitNote(outcome.service, toldModel)?.let {
                    appendItem(ChatItem.Note(newId(), it, isError = true))
                }
                toolUseId?.let { markCardFailed(it, "hosted service exited early") }
            }

            HostOutcome.StoppedByUser -> {
                appendItem(ChatItem.Note(newId(), "托管的服务刚启动就被停掉了$told"))
                toolUseId?.let { markCardFailed(it, "hosted service stopped by the user") }
            }

            is HostOutcome.NotStarted -> {
                appendItem(ChatItem.Note(newId(), "托管到进程表失败：${outcome.reason}$told", isError = true))
                toolUseId?.let { markCardFailed(it, "exclusive host failed: ${outcome.reason}") }
            }
        }
    }

    /** 托管没成（起不来 / 一启动就死 / 被停掉）的卡标成 Error */
    private fun markCardFailed(toolUseId: String, result: String) {
        state.update { st ->
            st.copy(
                items = st.items.mapToolCall(toolUseId) {
                    it.copy(status = ChatItem.ToolCall.Status.Error, result = result, isError = true)
                },
            )
        }
    }

    private fun appendItem(item: ChatItem) = state.update { it.copy(items = it.items + item) }

    private companion object {
        // 和 Manager 共用一个 TAG：排查时 `logcat -s ClaudeCodeManager` 一条命令就能看全
        const val TAG = "ClaudeCodeManager"
    }
}
