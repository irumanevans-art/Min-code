package dev.min.code.core.claudecode

/**
 * 思考强度档位。和 Claude Code 的 `--effort` 阶梯一一对应（OFF/AUTO 表示"不传 flag"）。
 * 从 RikkaHub 的 `me.rerere.ai.core.ReasoningLevel` 抄来，去掉了聊天侧用的 budgetTokens。
 */
enum class ReasoningLevel(val effort: String) {
    OFF("none"),
    AUTO("auto"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max"),
}
