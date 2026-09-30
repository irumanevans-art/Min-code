package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk

/** 一次工具调用的权限判定 */
sealed interface Verdict {
    data class Allow(val by: DecidedBy) : Verdict

    /** [messageToModel] 作为工具结果交给模型，让它换条路而不是原样重试 */
    data class Deny(val by: DecidedBy, val messageToModel: String) : Verdict

    /** 交给人（卡片 / 通知） */
    data object Ask : Verdict
}

/**
 * 权限判定。纯函数：同样的请求 + 模式 → 同样的结论。hooks 与审查模型由内核在它之外编排。
 *
 * M1 只按模式判定；规则（Claude Code 的 `Tool(specifier)` 语法、各层设置）、命令分析（tree-sitter-bash）
 * 在 M2 加进来（docs/agent/04-子系统设计.md §4.4）。形状不变：规则只是在模式之前多几步。
 */
object PolicyEngine {
    fun evaluate(ask: PermissionAsk, readOnly: Boolean, mode: PermissionMode): Verdict {
        // 安全底线：写手机上的真实文件，任何模式（含 bypass）都要人确认
        if (ask.paths.any { it.kind == AccessKind.Write && it.scope == PathScope.Device }) return Verdict.Ask

        val byMode = DecidedBy.Mode(mode)
        return when (mode) {
            PermissionMode.BypassPermissions -> Verdict.Allow(byMode)
            PermissionMode.Plan ->
                if (readOnly) Verdict.Allow(byMode)
                else Verdict.Deny(byMode, "计划模式下只能读不能改。先把计划整理好，用 ExitPlanMode 交给用户确认。")
            PermissionMode.DontAsk ->
                if (readOnly) Verdict.Allow(byMode)
                else Verdict.Deny(byMode, "当前模式不询问用户，这个操作没有被预先允许。")
            PermissionMode.AcceptEdits ->
                if (readOnly || isWorkspaceEditOnly(ask)) Verdict.Allow(byMode) else Verdict.Ask
            // auto 模式的审查模型在 M5 落地，在那之前按 default 处理
            PermissionMode.Default, PermissionMode.Auto ->
                if (readOnly) Verdict.Allow(byMode) else Verdict.Ask
        }
    }

    /** 只改工作区里的文件、不跑命令、不联网：acceptEdits 下直接放行的那一类 */
    private fun isWorkspaceEditOnly(ask: PermissionAsk): Boolean =
        ask.commands.isEmpty() && ask.domains.isEmpty() && ask.paths.isNotEmpty() &&
            ask.paths.all { it.scope == PathScope.Workspace }
}
