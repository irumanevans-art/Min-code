package dev.min.code.ui.session

import dev.min.code.core.engine.EngineKind

/**
 * 「继续最近的会话」接哪一个（Claude Code 的 `--continue`，桌面 CLI 的 `latestRootSession` 同一口径）：
 * 同一个引擎、同一个工作目录里最近更新的那个根会话（抽屉列表本来就只有根会话）。
 * 比 CLI 多一条：一句话都没说过的空会话不算——那不是「接着聊」，开始面板上的「启动」就是它。
 * CLI 还会跳过 `-p` 跑出来的无头会话；手机上没有无头会话，不用筛。
 * 路径按去掉末尾斜杠比较：`/workspace/` 与 `/workspace` 是同一个目录。
 */
internal fun latestContinuable(sessions: List<SessionListVM.SessionEntry>, engine: EngineKind, cwd: String): SessionListVM.SessionEntry? {
    val dir = cwd.trimEnd('/')
    return sessions
        .filter { it.engine == engine && it.messageCount > 0 && it.cwd?.trimEnd('/') == dir }
        .maxByOrNull { it.updatedAt }
}
