package dev.min.code.core.claudecode

/*
 * CLI 进程没了之后，界面上挂哪句话。从 [ClaudeCodeManager.onCliExit] 拆出来的纯函数。
 */

/**
 * 被 SIGKILL 杀掉时 `waitFor()` 给的退出码：128 + 9。两条路都落在这个数上：proot 自己被杀，是 JVM 按 shell 惯例报的；
 * proot 活着、guest 里的 claude 被杀，是包着它的 `bash -c` 报 137、proot 原样交出来的。
 */
internal const val EXIT_SIGKILL = 137

/**
 * 被 SIGKILL 时给用户看的说明。
 *
 * 为什么能说「多半是子进程上限」：2026-11-07 真机（vivo PD2309，Android 16，两项相关设置都是 null，即系统默认）
 * 的 logcat 里，`am_kill` 以 "Trimming phantom processes" 连着几次杀掉 libproot_exec.so、bash、claude；
 * 上限是全系统所有 App 的子进程合计 32 个，所以别的 App 的后台进程也会挤掉 Min 的会话。
 * 这时 claude 一句话都来不及打印，不说明的话用户只看到会话自己没了。
 * App 不能也不该替用户改这项系统设置，只能告诉他在哪里改。
 */
internal const val CLI_KILLED_BY_SYSTEM =
    "claude 进程被系统强行结束了（exit $EXIT_SIGKILL）。多半是 Android 的子进程上限：全系统的子进程默认最多 32 个，" +
        "超了系统就挑一些杀掉，正在跑的会话也会被杀。手机的开发者选项里如果有「停用子进程限制」，打开它就不会再这样；" +
        "没有这一项的话，少让程序在后台同时跑（开发服务器、浏览器自动化、别的终端 App 等）。"

/**
 * 进程退出后的 errorMessage。[previous] 是退出前已经挂着的那句（多半是从 stderr 认出来的报错）。
 * - 我们自己关的（[unexpected] = false）：不添话，原样保留
 * - 被 SIGKILL：换成 [CLI_KILLED_BY_SYSTEM]。被杀时 claude 来不及留遗言，先前挂着的那句不是这次的原因
 * - 其余：先前的报错更能说明原因，没有才报退出码
 */
internal fun cliExitError(code: Int?, unexpected: Boolean, previous: String?): String? = when {
    !unexpected -> previous
    code == EXIT_SIGKILL -> CLI_KILLED_BY_SYSTEM
    else -> previous ?: "claude 进程退出 (exit $code)"
}
