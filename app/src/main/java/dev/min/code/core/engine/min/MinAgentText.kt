package dev.min.code.core.engine.min

import android.content.Context
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.PermissionMode
import dev.min.code.R
import dev.min.code.core.engine.FailureKind
import dev.min.code.core.engine.SlashCommandView
import java.text.DateFormat
import java.util.Date

/**
 * Min 适配器要写进界面状态的全部文案。投影里不写死中文（CLAUDE.md：文案走 `res/values` + `res/values-zh`），
 * 实现读资源，测试用假实现——所以映射器、交互、续跑这些纯逻辑能在 JVM 上断言「说了哪一句」，不依赖 Android。
 *
 * 内核给出的失败原文（中文）不经过这里：它放在说明的第二行，是详情，不是文案。
 */
interface MinAgentText {
    /** 「已停止 · 14:32」。[atMs] 为 null 时不带时间 */
    fun stopped(atMs: Long?): String
    fun failed(kind: FailureKind?, atMs: Long?): String
    fun processDeath(atMs: Long?): String
    fun limit(): String
    fun hookStopped(atMs: Long?): String
    fun hookFeedback(event: String): String

    /** 续跑分隔线的正文，不含两侧的破折号 */
    fun continued(atMs: Long?): String

    /** 子 agent 报告唤醒了父会话：回合状态行与转录里的分隔线共用这一句 */
    fun subagentWoke(): String

    /** 后台 asyncRewake hook 唤醒了空闲会话：状态行与转录分隔线共用这一句 */
    fun hookWoke(): String
    fun report(report: MinReport, description: String?): String
    fun agentMessage(agentType: String, name: String?): String
    fun attachment(): String
    fun redactedThinking(): String

    fun mode(mode: PermissionMode): String
    fun modeWarning(mode: PermissionMode): String?
    fun effort(effort: Effort): String
    fun modelOtherProvider(modelId: String, providerLabel: String): String
    fun modelConservative(): String
    fun providerRemoved(): String

    fun optionAllow(): String
    fun optionDeny(): String
    fun optionDenyAndStop(): String
    fun optionApprovePlan(): String
    fun optionRevisePlan(): String
    fun delegation(agentType: String, description: String): String

    fun slashCommands(): List<SlashCommandView>
    fun backgroundTask(command: String): String

    /** 引擎不可用 / 建不了会话时给人看的原因 */
    fun engineDisabled(): String
    fun noUsableProvider(): String
    fun needModelId(): String
    fun sessionNotFound(): String
}

/**
 * 子 agent 报告里 `status` 属性（内核 `Reports.statusOf` 写的）对应的文案资源；认不出的返回 null，调用方退回原串。
 * 拆成函数是为了能在 JVM 上断言「内核会产出的每个状态都有文案」，不用读资源。
 */
internal fun reportStatusRes(status: String): Int? = when (status) {
    "completed" -> R.string.min_agent_report_completed
    "failed" -> R.string.min_agent_report_failed
    "interrupted" -> R.string.min_agent_report_interrupted
    "interrupted-by-restart" -> R.string.min_agent_report_restart
    "turn-limit" -> R.string.min_agent_report_limit
    "stopped-by-hook" -> R.string.min_agent_report_hook_stopped
    else -> null
}

/** 读 `res/values*` 的实现。时间用系统短格式，跟着界面语言走 */
class ResMinAgentText(private val context: Context) : MinAgentText {
    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun clock(atMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(atMs))
    private fun dateClock(atMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(atMs))
    private fun timed(base: String, atMs: Long?): String = if (atMs == null) base else "$base · ${clock(atMs)}"

    override fun stopped(atMs: Long?) = timed(s(R.string.min_agent_stopped), atMs)

    override fun failed(kind: FailureKind?, atMs: Long?) = timed(s(R.string.min_agent_failed, kindLabel(kind)), atMs)

    override fun processDeath(atMs: Long?) =
        if (atMs == null) s(R.string.min_agent_process_death_untimed) else s(R.string.min_agent_process_death, dateClock(atMs))

    override fun limit() = s(R.string.min_agent_limit)
    override fun hookStopped(atMs: Long?) = timed(s(R.string.min_agent_hook_stopped), atMs)
    override fun hookFeedback(event: String) = s(R.string.min_agent_hook_feedback, event)
    override fun continued(atMs: Long?) = timed(s(R.string.min_agent_continued), atMs)
    override fun subagentWoke() = s(R.string.min_agent_woke)
    override fun hookWoke() = s(R.string.min_agent_hook_woke)

    override fun report(report: MinReport, description: String?): String {
        val status = reportStatusRes(report.status)?.let { s(it) } ?: report.status
        val parts = listOfNotNull(
            report.name?.let { "${report.agentType} · $it" } ?: report.agentType,
            description?.takeIf { it.isNotBlank() },
            status,
            report.tools?.let { s(R.string.min_agent_report_calls, it) },
            report.tokens?.let { s(R.string.min_agent_report_tokens, compactCount(it)) },
            report.durationMs?.let { compactSeconds(it) },
        )
        return parts.joinToString(" · ") + "\n" + report.body
    }

    override fun agentMessage(agentType: String, name: String?) = s(R.string.min_agent_message_from, name ?: agentType)
    override fun attachment() = s(R.string.min_agent_attachment)
    override fun redactedThinking() = s(R.string.min_agent_redacted_thinking)

    override fun mode(mode: PermissionMode) = s(
        when (mode) {
            PermissionMode.Default -> R.string.min_agent_mode_default
            PermissionMode.AcceptEdits -> R.string.min_agent_mode_accept_edits
            PermissionMode.Plan -> R.string.min_agent_mode_plan
            PermissionMode.Auto -> R.string.min_agent_mode_auto
            PermissionMode.DontAsk -> R.string.min_agent_mode_dont_ask
            PermissionMode.BypassPermissions -> R.string.min_agent_mode_bypass
        },
    )

    override fun modeWarning(mode: PermissionMode) =
        if (mode == PermissionMode.BypassPermissions) s(R.string.min_agent_mode_bypass_warning) else null

    override fun effort(effort: Effort) = s(
        when (effort) {
            Effort.Off -> R.string.min_agent_effort_off
            Effort.Minimal -> R.string.min_agent_effort_minimal
            Effort.Low -> R.string.min_agent_effort_low
            Effort.Medium -> R.string.min_agent_effort_medium
            Effort.High -> R.string.min_agent_effort_high
            Effort.XHigh -> R.string.min_agent_effort_xhigh
            Effort.Max -> R.string.min_agent_effort_max
        },
    )

    override fun modelOtherProvider(modelId: String, providerLabel: String) = "$modelId · $providerLabel"
    override fun modelConservative() = s(R.string.min_agent_model_conservative)
    override fun providerRemoved() = s(R.string.min_agent_provider_removed)

    override fun optionAllow() = s(R.string.min_agent_option_allow)
    override fun optionDeny() = s(R.string.min_agent_option_deny)
    override fun optionDenyAndStop() = s(R.string.min_agent_option_deny_stop)
    override fun optionApprovePlan() = s(R.string.min_agent_option_plan_approve)
    override fun optionRevisePlan() = s(R.string.min_agent_option_plan_revise)
    override fun delegation(agentType: String, description: String) = s(R.string.min_agent_delegation, agentType, description)

    override fun slashCommands() = listOf(
        SlashCommandView("model", s(R.string.min_agent_slash_model)),
        SlashCommandView("effort", s(R.string.min_agent_slash_effort)),
        SlashCommandView("mode", s(R.string.min_agent_slash_mode)),
        SlashCommandView("new", s(R.string.min_agent_slash_new)),
    )

    override fun backgroundTask(command: String) = command.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60)
        ?: s(R.string.min_agent_task_shell)

    override fun engineDisabled() = s(R.string.min_agent_disabled)
    override fun noUsableProvider() = s(R.string.min_agent_no_provider)
    override fun needModelId() = s(R.string.min_agent_need_model)
    override fun sessionNotFound() = s(R.string.min_agent_session_not_found)

    private fun kindLabel(kind: FailureKind?): String = s(
        when (kind) {
            FailureKind.Network -> R.string.min_agent_fail_network
            FailureKind.RateLimited -> R.string.min_agent_fail_rate_limited
            FailureKind.Overloaded -> R.string.min_agent_fail_overloaded
            FailureKind.ContextOverflow -> R.string.min_agent_fail_context
            FailureKind.Auth -> R.string.min_agent_fail_auth
            FailureKind.BadRequest -> R.string.min_agent_fail_bad_request
            FailureKind.ModelUnavailable -> R.string.min_agent_fail_model_unavailable
            FailureKind.Server -> R.string.min_agent_fail_server
            FailureKind.Other, null -> R.string.min_agent_fail_other
        },
    )

    private fun compactCount(n: Long): String = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()
    private fun compactSeconds(ms: Long): String = "%.1fs".format(ms / 1000.0)
}
