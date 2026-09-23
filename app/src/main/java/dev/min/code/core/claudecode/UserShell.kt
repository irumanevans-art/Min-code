package dev.min.code.core.claudecode

import dev.min.code.core.relay.RelayController
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.currentShellCredentialEnv

/*
 * 输入框里以 `!` 开头的一行：Min 直接在 rootfs 里跑，不经模型 —— 终端里 Claude Code 的 bash 模式。
 *
 * 为什么要 Min 自己做：无头 stream-json 模式下 CLI 不认 `!`（2.1.280 实测），`!pwd` 会被当成
 * 一句话交给模型，模型猜出意图再去调 Bash 工具：多一轮 API、还可能要批准。
 *
 * 跑完的输入输出照终端里的格式（`<bash-input>` / `<bash-stdout>` / `<bash-stderr>`）攒着，
 * 跟着下一条消息一起交给模型，这样「!git status 然后说『把这些改了』」是通的。
 * 终端里是各自单独一条 user 消息；无头模式每条 user 消息都会开一轮，所以只能附在下一条前面。
 */

/** 这一行是不是 `!` 命令；是的话返回要跑的命令。中文输入法打出的全角「！」也算 */
internal fun userShellCommand(text: String): String? {
    val trimmed = text.trim()
    val first = trimmed.firstOrNull() ?: return null
    if (first != '!' && first != '！') return null
    return trimmed.drop(1).trim().takeIf { it.isNotEmpty() }
}

internal data class UserShellRun(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

/** 交给模型的那段上下文，格式照终端里 bash 模式写进 transcript 的样子；太长的输出掐头留尾 */
internal fun userShellContext(run: UserShellRun): String =
    "<bash-input>${run.command}</bash-input>\n" +
        "<bash-stdout>${run.stdout.trimEnd().keepTail()}</bash-stdout>" +
        "<bash-stderr>${run.stderr.trimEnd().keepTail()}${if (run.timedOut) "\n(timed out)" else ""}</bash-stderr>"

/**
 * 回放 transcript 时把附在消息前面的那几段摘掉，只留人说的话 ——
 * 不摘的话，用户气泡开头会是一大段裸 XML。
 */
internal fun stripUserShellContext(text: String): String = text.replace(SHELL_CONTEXT_BLOCK, "").trimStart()

/** 工具卡上显示的结果：输出原样，退出码非 0 / 超时时在末尾说一句 */
internal fun userShellCardResult(run: UserShellRun): String = buildString {
    val out = listOf(run.stdout.trimEnd(), run.stderr.trimEnd()).filter { it.isNotEmpty() }.joinToString("\n")
    append(out)
    val tail = when {
        run.timedOut -> "timed out after ${USER_SHELL_TIMEOUT_MS / 1000}s"
        run.exitCode != 0 -> "exit ${run.exitCode}"
        else -> null
    }
    if (tail != null) {
        if (isNotEmpty()) append("\n")
        append("[$tail]")
    }
}

/**
 * 在会话当前目录跑一条命令。环境和终端页签是同一套（Node 的 PATH、当前供应商的凭据），
 * 这样 `!claude --version`、`!npm test` 的结果和在终端里敲的一样。
 */
internal suspend fun runUserShell(
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
    settingsStore: SettingsStore,
    relay: RelayController?,
    cwd: String,
    command: String,
): UserShellRun {
    val env = buildMap {
        putAll(ClaudeCodeInstaller.nodeEnv())
        put("DEBIAN_FRONTEND", "noninteractive")
        putAll(currentShellCredentialEnv(settingsStore, relay))
    }
    // proot 的 -w 永远是 /workspace；会话可能在别的目录（甚至 /root 下），先 cd 过去
    val result = workspaceRepository.executeCommand(
        id = workspaceId,
        command = "cd -- ${shellQuote(cwd)} && $command",
        timeoutMillis = USER_SHELL_TIMEOUT_MS,
        env = env,
    )
    return UserShellRun(command, result.exitCode, result.stdout, result.stderr, result.timedOut)
}

private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

private fun String.keepTail(): String =
    if (length <= USER_SHELL_CONTEXT_CHARS) this else "…" + takeLast(USER_SHELL_CONTEXT_CHARS)

private val SHELL_CONTEXT_BLOCK = Regex(
    """^\s*(<bash-input>[\s\S]*?</bash-input>\s*<bash-stdout>[\s\S]*?</bash-stdout>\s*<bash-stderr>[\s\S]*?</bash-stderr>\s*)+""",
)

/** 一条 `!` 命令最多跑多久。长驻的东西（起服务、watch）该去终端 */
internal const val USER_SHELL_TIMEOUT_MS = 120_000L

/** 每段输出交给模型时最多留多少字：够看清结果，又不至于一条 `!cat` 就把上下文塞满 */
private const val USER_SHELL_CONTEXT_CHARS = 8_000
