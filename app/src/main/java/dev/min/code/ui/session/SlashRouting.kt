package dev.min.code.ui.session

import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.userShellCommand
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.SlashCommandView

/**
 * 需要在本地处理、不能转发给 CLI 的斜杠命令。
 *
 * `-p --output-format stream-json` 是**无头模式**，没有 TUI —— `/model`、`/mcp` 这类
 * 交互式命令在真终端里会弹选择器或编辑器，在这里 CLI 只能回一段纯文本摘要，
 * 既点不了也改不了。所以在发送前拦下来，改开 App 自己的面板：
 *
 * - 会话类（[LocalSlash.MODEL] / [LocalSlash.EFFORT] / [LocalSlash.PERMISSION] / [LocalSlash.CWD]）
 *   背后走 `list_models` / `set_model` / `--effort` 这些控制请求；
 * - 配置类（[LocalSlash.MCP] / [LocalSlash.AGENTS] / [LocalSlash.MEMORY] / [LocalSlash.CONFIG] /
 *   [LocalSlash.HOOKS]）背后就是 Rootfs 里那几个文件 —— TUI 的那些编辑器本质上也只是
 *   在改它们，所以原生表单能做到完全等价，而且在手机上更好用。
 */
internal enum class LocalSlash {
    MODEL, EFFORT, PERMISSION, CWD,
    MCP, AGENTS, MEMORY, CONFIG, HOOKS,

    /** 另起一条会话（Codex 的 `/new`；只有引擎的斜杠命令表里真有 `new` 才拦，见 [localSlashTarget]） */
    NEW,
}

internal val LOCAL_SLASH_HANDLERS = mapOf(
    "model" to LocalSlash.MODEL,
    "models" to LocalSlash.MODEL,
    "effort" to LocalSlash.EFFORT,
    "thinking" to LocalSlash.EFFORT,
    "permission-mode" to LocalSlash.PERMISSION,
    "cwd" to LocalSlash.CWD,
    "add-dir" to LocalSlash.CWD,
    "mcp" to LocalSlash.MCP,
    "agents" to LocalSlash.AGENTS,
    "memory" to LocalSlash.MEMORY,
    "config" to LocalSlash.CONFIG,
    "statusline" to LocalSlash.CONFIG,
    "output-style" to LocalSlash.CONFIG,
    "hooks" to LocalSlash.HOOKS,
    "permissions" to LocalSlash.HOOKS,
    // Codex 终端里的老叫法（`/approvals`、`/cd`）：习惯了它的人会下意识打，拦下来开对应设置栏，免得白花一轮的钱
    "approvals" to LocalSlash.PERMISSION,
    "cd" to LocalSlash.CWD,
    "new" to LocalSlash.NEW,
)

/** 输入框内容是不是一条该本地接管的斜杠命令；带参数的（`/model opus`）不拦，原样发下去 */
internal fun localSlashTarget(input: String): LocalSlash? {
    val trimmed = input.trim()
    if (!trimmed.startsWith("/") || trimmed.length < 2) return null
    if (trimmed.any { it.isWhitespace() }) return null
    return LOCAL_SLASH_HANDLERS[trimmed.drop(1).lowercase()]
}

/**
 * 按引擎认的 [localSlashTarget]。差别只有两处，都是只对「没有 CC 配置面板」的引擎（Codex）才生效，CC / Min 的行为不变：
 * - `/permissions`：CC 里它是 hooks / 权限规则的配置面板；Codex 里它就是权限档，开权限那一栏；
 * - `/new`、`/approvals`、`/cd`：只有引擎自己的斜杠命令表里列着才拦（[commands]），别的引擎里它们是普通文字。
 */
internal fun localSlashTarget(
    input: String,
    capabilities: dev.min.code.core.engine.EngineCapabilities,
    commands: List<dev.min.code.core.engine.SlashCommandView>,
): LocalSlash? {
    val base = localSlashTarget(input) ?: return null
    val name = input.trim().drop(1).lowercase()
    val listed = commands.any { it.name.equals(name, ignoreCase = true) }
    return when {
        name == "permissions" && capabilities.claudeConfigFiles == dev.min.code.core.engine.ConfigFiles.None -> LocalSlash.PERMISSION
        (name == "new" || name == "approvals" || name == "cd") && !listed -> null
        else -> base
    }
}

/** `/model fable` 的参数；空参数仍由 [localSlashTarget] 打开模型面板。 */
internal fun modelSlashArgument(input: String): String? {
    val trimmed = input.trim()
    val command = trimmed.substringBefore(' ').lowercase()
    if (command != "/model" && command != "/models") return null
    val argument = trimmed.substringAfter(' ', "").trim()
    return argument.takeIf { it.isNotBlank() && !it.any(Char::isWhitespace) }
}

/**
 * 将 `/model fable5.1` 这类便捷写法解析成中转站返回的真实 id。
 * CLI 的 `fable` 是别名，但 `fable5.1` 通常不是合法别名；只有真实 id 才能固定版本。
 * `[1m]` 后缀保留给 Claude Code 的长上下文选择。
 */
internal fun resolveSlashModel(
    requested: String,
    relayModels: List<ClaudeCodeManager.RelayModel>,
): String {
    val longContext = requested.endsWith("[1m]", ignoreCase = true)
    val base = requested.removeSuffix("[1m]")
    val normalized = base.lowercase().filter(Char::isLetterOrDigit)
    // 无版本号时保留 CLI 别名（如 `fable` / `opus`），不要被中转站列表里的
    // 某个具体版本抢先匹配。
    if (normalized.isBlank() || normalized.none(Char::isDigit)) return requested
    val matched = relayModels.firstOrNull { relay ->
        val relayNormalized = relay.id.lowercase().filter(Char::isLetterOrDigit)
        relayNormalized == normalized ||
            relayNormalized.removePrefix("claude") == normalized ||
            relayNormalized.endsWith(normalized)
    }?.id ?: run {
        // 没打开过模型面板时列表可能还没拉取；Fable 的公开 canonical id 有稳定格式，
        // 先给 CLI 一个可用的固定版本入口。
        Regex("(?i)^fable[- ]?(\\d+)(?:[.]?(\\d+))?$").matchEntire(base)?.let { match ->
            "claude-fable-${match.groupValues[1]}" +
                match.groupValues[2].takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
        }
    } ?: return requested
    return if (longContext) "$matched[1m]" else matched
}

/**
 * 输入框按回车 / 点发送之后这一行该去哪。纯判定，不碰状态；动作由 [ClaudeCodeInputBar] 按结果去做。
 */
internal sealed interface SubmitRoute {
    /** `!` 开头：Min 自己在 rootfs 里跑，不经模型 */
    data class Shell(val command: String) : SubmitRoute

    /** `/model <名字>`：走控制协议切模型。[model] 为 null = 回到默认 */
    data class SetModel(val model: String?) : SubmitRoute

    /** 本地接管的斜杠命令：开设置面板 / 配置面板 / 新会话 */
    data class Local(val target: LocalSlash) : SubmitRoute

    /** 普通消息（含引擎不接管的斜杠命令），原样发给模型 */
    data object SendMessage : SubmitRoute
}

/**
 * 判定一行输入的去向。**先后顺序就是语义**，别调换：
 *
 * 1. `!` 命令（只有引擎有 [EngineCapabilities.userShell] 才算，否则 `!` 只是个普通字符）；
 * 2. 带参数的 `/model <名字>`：必须走控制协议。直接发给无头 CLI 只会得到一条
 *    "Set model..." 文本，App 自己的 options / appliedModel 仍然是旧值。
 *    CLI 里它等于面板按 Enter（「存成新会话默认」），所以调用方按 asDefault = true 处理；
 * 3. 本地接管的斜杠命令。配置面板类（/mcp /agents …）是按 CC 的文件布局做的，
 *    引擎不支持就不接管，照普通消息发；
 * 4. 其余都是普通消息。
 */
internal fun routeSubmit(
    input: String,
    capabilities: EngineCapabilities,
    slashCommands: List<SlashCommandView>,
    relayModels: List<ClaudeCodeManager.RelayModel>,
): SubmitRoute {
    userShellCommand(input)?.takeIf { EngineUi.userShell(capabilities) }?.let { return SubmitRoute.Shell(it) }
    modelSlashArgument(input)?.let { requested ->
        val resolved = resolveSlashModel(requested, relayModels)
        return SubmitRoute.SetModel(resolved.takeUnless { it.equals("default", ignoreCase = true) })
    }
    val local = localSlashTarget(input, capabilities, slashCommands)?.takeIf {
        it == LocalSlash.NEW ||
            it.toSection(capabilities) != null ||
            it.toCodexSection() != null ||
            EngineUi.configCommands(capabilities)
    }
    return if (local != null) SubmitRoute.Local(local) else SubmitRoute.SendMessage
}
