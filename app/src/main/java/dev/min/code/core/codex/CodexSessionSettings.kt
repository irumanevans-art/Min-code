package dev.min.code.core.codex

/**
 * Codex 会话设置里的纯逻辑：权限档 ↔ 两个协议值、思考摘要的取值、「继续」时哪些设置跟着走。
 * 界面文案不在这里（见 `ui/codex/CodexSettingsSheet.kt`），这里只放能单测的映射。
 */

/**
 * 权限档。协议上是两个正交的值——什么时候问人（[CodexApprovalPolicy]）和能碰哪里（[CodexSandbox]）——
 * 手机上让人先想清楚两个维度再组合太费劲，所以照 codex CLI 的 `/permissions`（旧名 `/approvals`）
 * 给三档预设，底下的两个值在面板上照样看得见。
 *
 * 三档的组合抄自官方 `codex-rs/utils/approval-presets/src/lib.rs`（rust-v0.157.0 核对）：
 * read-only = on-request + read-only，auto = on-request + workspace-write，
 * full-access = never + danger-full-access。官方 0.157 把 auto 的显示名改成了「Default」，
 * id 仍是 `auto`；这里沿用 Auto，因为「默认」在这张面板上已经表示「不传、用 Codex 自己的」。
 *
 * **和官方不同的一处**：官方 auto 档断网（要联网得问），我们的 [AUTO] 一直带 `networkAccess = true`
 * （见 [CodexAppServerManager.Options.networkAccess]：guest 里 npm / pip 断网就全挂）。这是接入以来的既有行为，
 * 这里不改，只在说明里写明。
 */
enum class CodexPermissionPreset(
    /** 官方预设的 id，也是日志 / 排查时说的名字 */
    val id: String,
    val approvalPolicy: CodexApprovalPolicy,
    val sandbox: CodexSandbox,
) {
    /** 只读；要改文件、联网都得先问（readOnly 的 networkAccess 缺省即 false） */
    READ_ONLY("read-only", CodexApprovalPolicy.ON_REQUEST, CodexSandbox.READ_ONLY),

    /** 工作目录 + /workspace 可写、可联网；越界时模型自己来问 */
    AUTO("auto", CodexApprovalPolicy.ON_REQUEST, CodexSandbox.WORKSPACE_WRITE),

    /** 不问、不设沙箱。整个 rootfs 交出去 */
    FULL_ACCESS("full-access", CodexApprovalPolicy.NEVER, CodexSandbox.DANGER_FULL_ACCESS),
    ;

    /** 面板上「底下实际的两个值」那一行：turn/start 里真正写出去的 approvalPolicy 与 sandboxPolicy.type */
    val wireSummary: String get() = codexPermissionWire(approvalPolicy, sandbox)

    companion object {
        /**
         * 新会话的默认档。必须和 [CodexAppServerManager.Options] 的缺省值是同一对
         * （有单测钉住）——改这里就是改所有人的权限，不能顺手动。
         */
        val DEFAULT = AUTO

        /** 两个协议值落在哪一档；不是任何一档的组合（比如以后接了 untrusted）返回 null，界面上直接显示原值 */
        fun of(approvalPolicy: CodexApprovalPolicy, sandbox: CodexSandbox): CodexPermissionPreset? =
            entries.firstOrNull { it.approvalPolicy == approvalPolicy && it.sandbox == sandbox }
    }
}

/** `on-request · workspaceWrite`：和线上一个字不差，方便对着 codex 的文档 / 报错查 */
fun codexPermissionWire(approvalPolicy: CodexApprovalPolicy, sandbox: CodexSandbox): String =
    "${approvalPolicy.wire} · ${sandbox.wire}"

/**
 * 思考摘要（turn/start 的 `summary`）的合法取值，按官方 `ReasoningSummary`（`codex-rs/protocol/src/config_types.rs`，
 * `rename_all = "lowercase"`，rust-v0.157.0 核对）。null = 不传，走 config.toml / Codex 自己的默认（官方缺省是 auto）。
 *
 * 为什么给这一项：OpenAI 的推理模型不回原始思考，会话流里那段「思考」全靠摘要——想看它为什么这么改时要 detailed，
 * 嫌会话流太长时要 none。Claude 那边没有对应物，所以这是 Codex 独有的一栏。
 */
val CODEX_REASONING_SUMMARIES = listOf("auto", "concise", "detailed", "none")

/** 面板里填的值 → 存进 Options 的值：空白一律当「不传」，免得把空串写上线（Codex 对空串的校验比缺字段严） */
internal fun String?.orUnset(): String? = this?.trim()?.takeIf(String::isNotBlank)

/** 换权限档：只动那两个协议值，可写根、联网跟着连接走 */
fun CodexAppServerManager.Options.withPermission(preset: CodexPermissionPreset): CodexAppServerManager.Options =
    copy(approvalPolicy = preset.approvalPolicy, sandbox = preset.sandbox)

/** 当前落在哪一档，见 [CodexPermissionPreset.of] */
val CodexAppServerManager.Options.permissionPreset: CodexPermissionPreset?
    get() = CodexPermissionPreset.of(approvalPolicy, sandbox)

/**
 * 「继续」同一条会话时，面板里改过的那几项跟着走。
 *
 * 不带过来的话，一条被人调成「只读」的会话停一下再继续，就悄悄回到了「自动」——权限在用户不知情时放宽了。
 * 带的只是会话设置（模型 / 强度 / 摘要 / 权限 / 工作目录）；可写根、联网是连接层的事，照新值。
 */
fun CodexAppServerManager.Options.withSessionSettingsOf(
    previous: CodexAppServerManager.Options,
): CodexAppServerManager.Options = copy(
    model = previous.model,
    effort = previous.effort,
    summary = previous.summary,
    approvalPolicy = previous.approvalPolicy,
    sandbox = previous.sandbox,
    cwd = previous.cwd,
)
