package dev.min.code.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.agent.llm.Cost
import dev.min.code.R
import dev.min.code.core.engine.ScheduleView
import dev.min.code.core.engine.ScheduledStatus
import dev.min.code.core.engine.ScheduledTaskView
import dev.min.code.core.engine.min.ResCronText
import dev.min.code.core.engine.min.formatCost
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.theme.JetbrainsMono
import java.text.DateFormat
import java.util.Date

/**
 * 会话设置里的「定时任务」段（M5）：这个会话里模型建的定时任务——日程、提示词、下次什么时候、每次最多花多少，
 * 进行中的能删。清单与模型的 CronList 是同一份。全局开关关着时只提示一句（任务还在，只是不会触发）。
 */
@Composable
internal fun ScheduleSection(view: ScheduleView, onDelete: (String) -> Unit) {
    var deleting by remember { mutableStateOf<String?>(null) }
    Column {
        if (!view.enabled) {
            Notice(text = stringResource(R.string.schedule_off_note), tone = NoticeTone.Warn, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        view.tasks.forEachIndexed { index, task ->
            if (index > 0) InkDivider()
            ScheduledTaskRow(task, onDelete = { deleting = task.id })
        }
    }
    RikkaConfirmDialog(
        show = deleting != null,
        title = stringResource(R.string.schedule_delete_title),
        confirmText = stringResource(R.string.schedule_delete),
        dismissText = stringResource(R.string.team_cancel),
        onConfirm = {
            deleting?.let(onDelete)
            deleting = null
        },
        onDismiss = { deleting = null },
    ) {
        Text(stringResource(R.string.schedule_delete_text))
    }
}

@Composable
private fun ScheduledTaskRow(task: ScheduledTaskView, onDelete: () -> Unit) {
    val context = LocalContext.current
    val text = remember(context) { ResCronText(context) }
    val scheme = MaterialTheme.colorScheme
    val active = task.status == ScheduledStatus.Active
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${task.id} · ${text.schedule(task.cron, task.intervalMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetbrainsMono,
                    color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(kindLabel(task, active), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            Text(task.prompt, style = MaterialTheme.typography.bodySmall, color = if (active) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                metaLine(task, active),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (active) InkButton(onClick = onDelete, tone = InkButtonTone.Paper, compact = true) { Text(stringResource(R.string.schedule_delete)) }
    }
}

/** 一次性 / 唤醒 / 循环 + 不在进行中时的状态词 */
@Composable
private fun kindLabel(task: ScheduledTaskView, active: Boolean): String = when {
    active -> stringResource(if (task.wakeup) R.string.schedule_wakeup else if (task.recurring) R.string.schedule_recurring else R.string.schedule_once)
    else -> stringResource(
        when (task.status) {
            ScheduledStatus.Fired -> R.string.schedule_status_fired
            ScheduledStatus.Cancelled -> R.string.schedule_status_cancelled
            ScheduledStatus.Expired, ScheduledStatus.Active -> R.string.schedule_status_expired
        },
    )
}

@Composable
private fun metaLine(task: ScheduledTaskView, active: Boolean): String {
    val clock = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    return listOfNotNull(
        task.nextFireAtMs?.takeIf { active }?.let { stringResource(R.string.schedule_next, clock.format(Date(it))) },
        task.budgetUsd?.let { stringResource(R.string.schedule_budget, formatCost(Cost(it, "USD"))) } ?: stringResource(R.string.schedule_no_budget),
        task.fires.takeIf { it > 0 }?.let { stringResource(R.string.schedule_fires, it) },
    ).joinToString(" · ")
}
