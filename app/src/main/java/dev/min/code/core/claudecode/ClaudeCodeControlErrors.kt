package dev.min.code.core.claudecode

/**
 * control_request 失败应答里的 `error_code`（CLI 2.1.283 起）。
 *
 * 全表（js283 的 `ER`，schema 原话 "new values are additive, so treat an unknown one as absent"）：
 * - set_model：`invalid_request` `catalog_unknown` `restricted_by_org` `unavailable_for_account`
 *   `not_offered` `alias_1m_disabled` `alias_1m_unsupported` `blocked_by_hook` `check_failed`
 * - set_permission_mode：`invalid_mode` `bypass_restricted` `bypass_disabled` `bypass_not_launched`
 *   `auto_mode_settings` `auto_mode_circuit_breaker` `auto_mode_fast_mode` `auto_mode_model` `auto_mode_unavailable`
 * - remote_control：`remote_control_auth` `remote_control_terminal` `remote_control_ended_elsewhere`
 *   `remote_control_policy_disabled`
 *
 * 这里只收 Min 会碰到、且能告诉用户怎么办的那几个；其余照旧显示 CLI 原句。
 */
internal object ControlErrorCode {
    /** set_permission_mode → bypass：进程启动时没开放 bypass（没带 `--allow-dangerously-skip-permissions`） */
    const val BYPASS_NOT_LAUNCHED = "bypass_not_launched"

    /** set_model：这个账号用不了（Opus / Sonnet 的 1M 版本没开通，或组织把这个模型关了） */
    const val UNAVAILABLE_FOR_ACCOUNT = "unavailable_for_account"

    /**
     * 2.1.283 之前的 CLI 不带 code，只能认原句。原句取自 js283 里对应分支的字面量：
     * - "Cannot set permission mode to bypassPermissions because the session was not launched with
     *   --dangerously-skip-permissions"
     * - "Opus with 1M context is not available for your account. …" / "Sonnet with 1M context …" /
     *   "Model 'x' is not currently available for your account…"
     * 等不再需要兼容 2.1.283 以前的 CLI 时整段删掉。
     */
    private val LEGACY_PHRASES = listOf(
        "was not launched with --dangerously-skip-permissions" to BYPASS_NOT_LAUNCHED,
        "not available for your account" to UNAVAILABLE_FOR_ACCOUNT,
        "not currently available for your account" to UNAVAILABLE_FOR_ACCOUNT,
    )

    /** 有 code 用 code，没有就按老版本的原句认；都认不出返回 null */
    fun of(message: String, code: String?): String? =
        code?.takeIf { it.isNotBlank() }
            ?: LEGACY_PHRASES.firstOrNull { (phrase, _) -> message.contains(phrase, ignoreCase = true) }?.second
}

/**
 * 控制请求失败时给用户看的那句。认得的 code 先说人话、再把 CLI 原句括在后面（排查时要用），
 * 认不得的原样返回 CLI 原句 —— 宁可是英文，也不要编一个不对的解释。
 */
internal fun describeControlError(message: String, code: String?): String {
    val hint = when (ControlErrorCode.of(message, code)) {
        ControlErrorCode.BYPASS_NOT_LAUNCHED ->
            "这个会话的 CLI 启动时没开放「跳过权限确认」，中途切不过去。重开这个会话后再切"
        ControlErrorCode.UNAVAILABLE_FOR_ACCOUNT ->
            "当前账号用不了这个模型（订阅或组织没开通，1M 上下文版本最常见）。换一个模型，或选不带 [1m] 的那一项"
        else -> null
    } ?: return message
    return if (message.isBlank()) hint else "$hint（$message）"
}
