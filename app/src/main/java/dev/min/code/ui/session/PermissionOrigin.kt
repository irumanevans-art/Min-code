package dev.min.code.ui.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeCodeEvent
import dev.min.code.core.claudecode.ClaudeCodeManager.TaskInfo
import dev.min.code.core.claudecode.FORK_SUBAGENT_TYPE

/**
 * 权限卡 / 提问卡顶上那一行：这张卡是谁要的、后面还排着几张。
 *
 * CLI 2.1.285 起后台子 agent 也会来要权限，主线程早就收尾了卡才弹 —— 不说清来历，
 * 用户会以为是刚才那轮在要。
 */
internal data class PermissionOrigin(
    /** 子 agent 的说明（task_started 的 description）；主线程发的是 null */
    val agent: String?,
    val background: Boolean,
    /** 是分叉子 agent（fork）：卡上说「分身」，不说「子 agent」 */
    val fork: Boolean = false,
    /** 队头之后还排着几张 */
    val queuedAfter: Int,
)

internal fun permissionOriginOf(
    pending: ClaudeCodeEvent.PermissionRequest,
    tasks: List<TaskInfo>,
    queueSize: Int,
    /** 描述为空时退回类型名，类型名给人看的说法由调用方换（fork → 分身） */
    typeName: (String) -> String = { it },
): PermissionOrigin {
    val task = pending.agentId?.let { id -> tasks.firstOrNull { it.id == id } }
    val agent = pending.agentId?.let { id ->
        task?.description?.takeIf { it.isNotBlank() } ?: task?.subagentType?.let(typeName) ?: id.take(8)
    }
    return PermissionOrigin(
        agent = agent,
        background = task?.backgrounded == true,
        fork = task?.subagentType == FORK_SUBAGENT_TYPE,
        queuedAfter = (queueSize - 1).coerceAtLeast(0),
    )
}

/** @param denyHint 卡上有「拒绝」键时为 true：后台子 agent 被拒只停它自己，值得先说一句 */
@Composable
internal fun PermissionOriginLines(origin: PermissionOrigin, denyHint: Boolean) {
    origin.agent?.let { agent ->
        Text(
            stringResource(
                when {
                    origin.fork && origin.background -> R.string.session_permission_from_bg_fork
                    origin.fork -> R.string.session_permission_from_fork
                    origin.background -> R.string.session_permission_from_bg_agent
                    else -> R.string.session_permission_from_agent
                },
                agent,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (denyHint && origin.background) {
        Text(
            stringResource(R.string.session_permission_bg_deny_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (origin.queuedAfter > 0) {
        Text(
            stringResource(R.string.session_permission_queued, origin.queuedAfter),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
