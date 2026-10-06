package dev.min.code.ui.session

import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry

/**
 * 只对活跃会话生效的操作统一走这里。没有活跃会话时静默忽略 ——
 * UI 在没有会话时显示的是启动面板，本来就点不到这些控件。
 *
 * 挂在注册表上而不是某个 VM 里：[SessionVM] 和 [SessionListVM] 都要用，
 * 「谁是活跃会话」的唯一来源是注册表，两边各抄一份判断就会有第二个答案。
 */
internal inline fun ClaudeCodeSessionRegistry.onActive(block: (ClaudeCodeManager) -> Unit) {
    active()?.let(block)
}

/**
 * 新建会话时的默认配置（含上次选的工作目录）。
 * 有活跃会话时取它的选项，没有（刚启动 App）退回磁盘上记的那份，
 * 不能退回 SessionOptions() 的默认值 —— 那会把用户上次选的模型 / effort 丢掉。
 */
internal fun ClaudeCodeSessionRegistry.lastOptions(): ClaudeCodeManager.SessionOptions =
    active()?.state?.value?.options ?: configProbe().lastOptions()
