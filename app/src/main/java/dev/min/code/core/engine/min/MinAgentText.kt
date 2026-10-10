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

    /** 定时任务到点触发送进会话的那条输入：说明它不是人发的话 */
    fun cronFired(cronId: String): String

    /** 定时任务把空闲的会话叫醒：回合状态行与转录分隔线共用 */
    fun cronWoke(): String

    /** 续跑分隔线的正文，不含两侧的破折号 */
    fun continued(atMs: Long?): String

    /** 子 agent 报告唤醒了父会话：回合状态行与转录里的分隔线共用这一句 */
    fun subagentWoke(): String

    /** 后台 asyncRewake hook 唤醒了空闲会话：状态行与转录分隔线共用这一句 */
    fun hookWoke(): String
    fun agentMessage(agentType: String, name: String?): String
    fun reviewedReport(): String = "已审查"
    fun warningReport(): String = "附警告"
    fun unavailableReport(): String = "未能审查"
    fun attachment(): String
    fun redactedThinking(): String

    /** 会话回顾那一行：「回顾 · 刚才在……」 */
    fun recapLine(recap: String): String

    /** 回退折叠行：「已回退的 3 条消息」 */
    fun rewoundTitle(count: Int): String

    /** 恢复代码的折叠行标题：「恢复了 5 个文件，删除了 1 个，跳过 2 个」（跳过为 0 时不写）；[kind] 决定开头 */
    fun restoredTitle(kind: RestoreKind, restored: Int, deleted: Int, skipped: Int): String
    fun restoredLine(path: String): String
    fun deletedLine(path: String): String
    fun skippedLine(path: String, reason: String): String

    fun mode(mode: PermissionMode): String
    fun modeWarning(mode: PermissionMode): String?
    fun effort(effort: Effort): String
    fun modelOtherProvider(modelId: String, providerLabel: String): String
    fun modelConservative(): String
    fun providerRemoved(): String

    fun optionAllow(): String
    fun networkApprovalSummary(): String = "这个域名将被访问，请确认"
    fun networkAllowOnce(): String = "允许一次"
    fun networkAllowAlways(): String = "总是允许"
    fun networkDeny(): String = "拒绝"
    fun networkDecision(domain: String, port: Int, outcome: String, reason: String): String = "网络 $domain:$port · $outcome · $reason"
    fun optionDeny(): String
    fun optionDenyAndStop(): String

    /** 「总是允许」按钮：[rule] 是 CC 语法的规则原文（`Bash(git status)`），原样显示 */
    fun optionWorkflowRemember(): String = optionAlways(RuleLayer.Session, "Workflow")
    fun optionAlways(layer: RuleLayer, rule: String): String

    /** 规则写进文件之后的一句轻提示 / 没写成的原因 */
    fun ruleSaved(layer: RuleLayer): String
    fun ruleNotSaved(reason: String): String
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

/** 一次恢复代码是怎么来的：回退到某个检查点 / 撤销上一次回退 / 撤掉某几次编辑调用（改动面板里的撤销） */
enum class RestoreKind { Checkpoint, Undo, Calls }

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
    "stopped-by-budget" -> R.string.min_agent_report_budget_stopped
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
    override fun cronFired(cronId: String) = s(R.string.min_agent_cron_fired, cronId)
    override fun cronWoke() = s(R.string.min_agent_cron_woke)
    override fun continued(atMs: Long?) = timed(s(R.string.min_agent_continued), atMs)
    override fun subagentWoke() = s(R.string.min_agent_woke)
    override fun hookWoke() = s(R.string.min_agent_hook_woke)

    override fun agentMessage(agentType: String, name: String?) = s(R.string.min_agent_message_from, name ?: agentType)
    override fun reviewedReport() = s(R.string.min_agent_report_reviewed)
    override fun warningReport() = s(R.string.min_agent_report_warning)
    override fun unavailableReport() = s(R.string.min_agent_report_unavailable)
    override fun attachment() = s(R.string.min_agent_attachment)
    override fun redactedThinking() = s(R.string.min_agent_redacted_thinking)

    override fun recapLine(recap: String) = s(R.string.min_recap_line, recap)

    override fun rewoundTitle(count: Int) = s(R.string.min_rewound_title, count)

    override fun restoredTitle(kind: RestoreKind, restored: Int, deleted: Int, skipped: Int): String {
        val base = s(
            when (kind) {
                RestoreKind.Checkpoint -> R.string.min_restored_checkpoint
                RestoreKind.Undo -> R.string.min_restored_undo
                RestoreKind.Calls -> R.string.min_restored_calls
            },
            restored, deleted,
        )
        return if (skipped > 0) base + s(R.string.min_restored_skipped, skipped) else base
    }

    override fun restoredLine(path: String) = s(R.string.min_restored_line, path)
    override fun deletedLine(path: String) = s(R.string.min_deleted_line, path)
    override fun skippedLine(path: String, reason: String) = s(R.string.min_skipped_line, path, reason)

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

    override fun modeWarning(mode: PermissionMode) = when (mode) {
        PermissionMode.BypassPermissions -> s(R.string.min_agent_mode_bypass_warning)
        // 切到 auto 要点一下确认：把话说在前面——审查时用户的消息与工具入参会发给审查模型所在的供应商
        PermissionMode.Auto -> s(R.string.min_agent_mode_auto_warning)
        else -> null
    }

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
    override fun networkApprovalSummary() = s(R.string.network_approval_summary)
    override fun networkAllowOnce() = s(R.string.network_allow_once)
    override fun networkAllowAlways() = s(R.string.network_allow_always)
    override fun networkDeny() = s(R.string.network_deny)
    override fun networkDecision(domain: String, port: Int, outcome: String, reason: String) = s(R.string.network_decision_notice, domain, port, outcome, reason)
    override fun optionDeny() = s(R.string.min_agent_option_deny)
    override fun optionDenyAndStop() = s(R.string.min_agent_option_deny_stop)
    override fun ruleSaved(layer: RuleLayer): String = context.getString(
        if (layer == RuleLayer.User) R.string.min_agent_rule_saved_user else R.string.min_agent_rule_saved_local,
    )
    override fun ruleNotSaved(reason: String): String = context.getString(R.string.min_agent_rule_not_saved, reason)
    override fun optionWorkflowRemember(): String = context.getString(R.string.workflow_remember_project)
    override fun optionAlways(layer: RuleLayer, rule: String): String = context.getString(
        when (layer) {
            RuleLayer.Session -> R.string.min_agent_option_always_session
            RuleLayer.Local -> R.string.min_agent_option_always_local
            RuleLayer.User -> R.string.min_agent_option_always_user
        },
        rule,
    )
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
}
