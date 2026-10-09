package dev.min.code.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.engine.TeamMemberPhase
import dev.min.code.core.engine.TeamMemberView
import dev.min.code.core.engine.TeamMessageView
import dev.min.code.core.engine.TeamTaskStatus
import dev.min.code.core.engine.TeamTaskView
import dev.min.code.core.engine.TeamView
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.LiveDot
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.components.Seal
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea

/**
 * 团队页（B10）：成员（状态含排队 / 搁浅、在做的事、模型）、共享任务清单（认领人、依赖、状态）、成员间消息流。
 * 搁浅的成员行上有「放行」，进行中的团队有「解散」（要确认）。
 *
 * 点成员行跳到它的线程（成员就是负责人的子会话，对话页和别的子 agent 同一个）。
 * 解散后页面还能看，清单冻结、按钮收起。
 */
@Composable
internal fun TeamSheet(
    team: TeamView,
    onOpenMember: (threadId: String) -> Unit,
    onRelease: (member: String?) -> Unit,
    onDisband: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDisband by remember { mutableStateOf(false) }
    InkSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TeamHeader(team)
            MembersSection(team, onOpenMember, onRelease)
            TasksSection(team.tasks)
            MessagesSection(team.messages)
            if (team.active) {
                InkButton(onClick = { confirmDisband = true }, tone = InkButtonTone.Paper, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.team_disband))
                }
            }
        }
    }
    RikkaConfirmDialog(
        show = confirmDisband,
        title = stringResource(R.string.team_disband_confirm_title),
        confirmText = stringResource(R.string.team_disband_confirm),
        dismissText = stringResource(R.string.team_cancel),
        onConfirm = {
            confirmDisband = false
            onDisband()
        },
        onDismiss = { confirmDisband = false },
    ) {
        Text(stringResource(R.string.team_disband_confirm_text))
    }
}

@Composable
private fun TeamHeader(team: TeamView) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Seal()
            Text(stringResource(R.string.team_title, team.teamId), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
            Text(
                stringResource(if (team.active) R.string.team_state_active else R.string.team_state_disbanded),
                style = MaterialTheme.typography.labelSmall,
                color = if (team.active) MaterialTheme.sea.seaDeep else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        team.disbandNote?.let {
            Text(stringResource(R.string.team_disband_note, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun MembersSection(team: TeamView, onOpenMember: (String) -> Unit, onRelease: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(stringResource(R.string.team_members_title, team.members.size))
        if (team.active && team.stalledCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.team_stalled_hint, team.stalledCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (team.stalledCount > 1) {
                    InkButton(onClick = { onRelease(null) }, tone = InkButtonTone.Paper, compact = true) { Text(stringResource(R.string.team_release_all)) }
                }
            }
        }
        Column {
            team.members.forEachIndexed { index, member ->
                if (index > 0) InkDivider()
                MemberRow(member, active = team.active, onOpen = onOpenMember, onRelease = { onRelease(member.name) })
            }
        }
    }
}

@Composable
private fun MemberRow(member: TeamMemberView, active: Boolean, onOpen: (String) -> Unit, onRelease: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val open = member.threadId
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (open != null) Modifier.clickable(role = Role.Button) { onOpen(open) } else Modifier)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 6.dp).width(6.dp)) { PhaseMark(member.phase) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(member.name, style = MaterialTheme.typography.bodySmall, fontFamily = JetbrainsMono, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(
                    phaseLabel(member.phase),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (member.phase == TeamMemberPhase.Stalled) scheme.error else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            listOfNotNull(member.agentType?.let { subagentTypeLabel(it) }, member.model).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            member.currentTask?.let {
                Text(stringResource(R.string.team_member_task, it), style = MaterialTheme.typography.bodySmall, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (active && member.phase == TeamMemberPhase.Stalled) {
            InkButton(onClick = onRelease, tone = InkButtonTone.Paper, compact = true) { Text(stringResource(R.string.team_release)) }
        }
    }
}

/** 状态记号：在跑是呼吸的海点，搁浅是朱，排队 / 空闲 / 没加入是静止的石墨点（同切换条的口径） */
@Composable
private fun PhaseMark(phase: TeamMemberPhase) {
    val palette = MaterialTheme.sea
    when (phase) {
        TeamMemberPhase.Running -> LiveDot(palette.sea, size = 6.dp)
        TeamMemberPhase.Stalled -> Seal(size = 6.dp, color = palette.vermilion)
        else -> Seal(size = 6.dp, color = palette.graphite)
    }
}

@Composable
private fun phaseLabel(phase: TeamMemberPhase): String = stringResource(
    when (phase) {
        TeamMemberPhase.NotJoined -> R.string.team_phase_notjoined
        TeamMemberPhase.Queued -> R.string.team_phase_queued
        TeamMemberPhase.Stalled -> R.string.team_phase_stalled
        TeamMemberPhase.Running -> R.string.team_phase_running
        TeamMemberPhase.Idle -> R.string.team_phase_idle
    },
)

@Composable
private fun TasksSection(tasks: List<TeamTaskView>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(stringResource(R.string.team_tasks_title, tasks.size))
        if (tasks.isEmpty()) {
            Text(stringResource(R.string.team_tasks_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column {
                tasks.forEachIndexed { index, task ->
                    if (index > 0) InkDivider()
                    TaskRow(task)
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TeamTaskView) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text("#${task.id}", style = MaterialTheme.typography.labelSmall, fontFamily = JetbrainsMono, color = scheme.onSurfaceVariant)
            Text(
                task.subject,
                style = MaterialTheme.typography.bodySmall,
                color = if (task.status == TeamTaskStatus.Completed) scheme.onSurfaceVariant else scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(
                    when (task.status) {
                        TeamTaskStatus.Pending -> R.string.team_task_pending
                        TeamTaskStatus.InProgress -> R.string.team_task_in_progress
                        TeamTaskStatus.Completed -> R.string.team_task_done
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (task.status == TeamTaskStatus.InProgress) MaterialTheme.sea.seaDeep else scheme.onSurfaceVariant,
            )
            Text(
                task.owner ?: stringResource(R.string.team_task_unowned),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetbrainsMono,
                color = scheme.onSurfaceVariant,
            )
            if (task.waitingOn.isNotEmpty()) {
                Text(
                    stringResource(R.string.team_task_waiting, task.waitingOn.joinToString(" ") { "#$it" }),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessagesSection(messages: List<TeamMessageView>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(stringResource(R.string.team_messages_title, messages.size))
        if (messages.isEmpty()) {
            Text(stringResource(R.string.team_messages_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    messages.forEach { MessageRow(it) }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: TeamMessageView) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            "${message.from} → ${message.to ?: stringResource(R.string.team_message_all)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(message.text, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}
