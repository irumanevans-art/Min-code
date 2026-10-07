package dev.min.code.ui.session

import dev.min.code.core.engine.AgentSession
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * 会话页此刻显示的是哪一边。
 *
 * B6 留下的现实：Claude Code 的会话仍由旧 `ClaudeCodeSessionRegistry` 管（它是「谁是活跃会话」的来源），
 * Min 的会话由统一的 `AgentSessionRegistry` 管。两边各有一个「活跃项」，页面要有一个明确的规则决定显示谁：
 * **后写者赢**——用户刚新建 / 打开的那一边，或者通知把某一边切到前台。
 * 规则只在这里一处，B9 把旧注册表拆进引擎之后两边合成一个，这个文件就可以删。
 */
enum class Shown { ClaudeCode, Min }

/**
 * 按 [focus] 选一边显示；那一边没有活跃会话（刚被关掉）就落到另一边，两边都没有才是 null（页面显示开始面板）。
 */
internal fun shownSession(focus: Shown, claude: AgentSession?, min: AgentSession?): AgentSession? = when (focus) {
    Shown.ClaudeCode -> claude ?: min
    Shown.Min -> min ?: claude
}

/**
 * 两边的「活跃项变了」事件合成 [Shown]：哪边最后动了就是哪边。只认非空——一边被关成 null 不该把焦点抢给另一边，
 * 落到另一边由 [shownSession] 的回退负责。
 *
 * 光靠它不够：旧注册表的 `activeKey` 是 StateFlow，用户在 Min 页里点抽屉里**已经是活跃项**的那个 Claude 会话时
 * 值没变、不会发射，所以 VM 在新建 / 打开时还要显式设置焦点（[SessionVM] 里的 `focus`）。这里只管外部来的变化（通知直达等）。
 */
internal fun focusChanges(claudeKeys: Flow<String?>, minRefs: Flow<SessionRef?>): Flow<Shown> = merge(
    claudeKeys.filterNotNull().map { Shown.ClaudeCode },
    minRefs.filterNotNull().filter { it.engine == EngineKind.MinAgent }.map { Shown.Min },
)
