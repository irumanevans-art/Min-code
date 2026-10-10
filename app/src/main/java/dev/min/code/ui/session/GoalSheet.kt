package dev.min.code.ui.session

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.engine.GoalStatus
import dev.min.code.core.engine.GoalView
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkSheet

/**
 * 任务条上「◎ 目标」标记点开的详情：条件、状态、评估次数、最近一次的理由（CC：点开能看最近的评估理由），以及清除。
 * 清除经 [onClear] 走 `/goal clear` 这条命令，与输入框里敲的是同一条路。
 */
@Composable
internal fun GoalSheet(goal: GoalView, onClear: () -> Unit, onDismiss: () -> Unit) {
    InkSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.goal_sheet_title), style = MaterialTheme.typography.titleMedium)
            Text(goal.condition, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(goalStatusRes(goal.status)),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(stringResource(R.string.goal_sheet_evaluations, goal.evaluations), style = MaterialTheme.typography.bodySmall)
            goal.pausedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                goal.lastReason ?: stringResource(R.string.goal_sheet_no_reason),
                style = MaterialTheme.typography.bodySmall,
            )
            InkButton(onClick = { onClear(); onDismiss() }, tone = InkButtonTone.Quiet, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.goal_sheet_clear))
            }
        }
    }
}

/** 目标状态的文字资源：任务条的标记与详情面板共用，免得两处各写一份 */
@StringRes
internal fun goalStatusRes(status: GoalStatus): Int = when (status) {
    GoalStatus.Active -> R.string.goal_status_active
    GoalStatus.Deferred -> R.string.goal_status_deferred
    GoalStatus.Paused -> R.string.goal_status_paused
}
