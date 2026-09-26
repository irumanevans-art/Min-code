package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.claudecode.SubagentThread
import dev.min.code.core.session.ChatItem
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.LiveDot
import dev.min.code.ui.components.Seal
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01

/*
 * 子 agent 切换：对齐官方终端底部那一栏（有子 agent 时列出 `main` 和
 * `general-purpose  <它正在做什么>`，选中哪个上面就显示哪个的对话）。
 *
 * 这里只有外观；列谁（switcherThreads）、它是什么状态（subagentThreads）是 core 里的纯函数，
 * 选中了哪个由会话页持有。
 */

/**
 * 打开某个子 agent 的对话（参数是发起它的 Agent 调用的 toolUseId）。null = 这一页不支持（Codex）。
 *
 * 用 CompositionLocal 而不是逐层传参：Agent 卡可能在主会话里，也可能嵌在另一个子 agent 的对话里，
 * 中间隔着 TranscriptItem、折叠块好几层，而每一层要传的都是同一件事（先例 [LocalAwaitingToolUseId]）。
 */
internal val LocalOpenSubagent = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * 输入框上方那一条：`main` + 每个子 agent 一只 chip。[threads] 为空时整条收起、不占高度。
 *
 * @param selected 正在看的子 agent（toolUseId），null = main
 */
@Composable
internal fun AgentSwitcher(
    threads: List<SubagentThread>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = threads.isNotEmpty(),
        enter = InkMotion.expand,
        exit = InkMotion.collapse,
        modifier = modifier,
    ) {
        val label = stringResource(R.string.agent_switcher_label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label }
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 6.dp)
                // chip 进出时整条跟着伸缩，不跳
                .animateContentSize(InkMotion.spatial()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InkChip(
                label = stringResource(R.string.agent_switcher_main),
                selected = selected == null,
                onClick = { onSelect(null) },
                monospace = true,
            )
            threads.forEach { thread ->
                AgentChip(thread, selected = thread.toolUseId == selected, onClick = { onSelect(thread.toolUseId) })
            }
        }
    }
}

/**
 * 一个子 agent：活点 + 类型 + 它在干什么。跑完的淡下去但留着（本轮结束前还能点进去看）。
 * 淡是整只 chip 淡，不是换色——活着靠动，不靠另一种颜色（DESIGN.md「三种物质」）。
 */
@Composable
private fun AgentChip(thread: SubagentThread, selected: Boolean, onClick: () -> Unit) {
    val dim by animateFloatAsState(
        targetValue = if (thread.running || selected) 1f else ENDED_ALPHA,
        animationSpec = InkMotion.effect(),
        label = "agentChipDim",
    )
    InkChip(selected = selected, onClick = onClick, modifier = Modifier.alpha(dim)) { fg ->
        AgentMark(thread, onSea = selected)
        Text(
            thread.agentType,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = JetbrainsMono,
            color = fg,
            maxLines = 1,
        )
        val doing = thread.activity?.takeIf { thread.running } ?: thread.description
        if (doing.isNotBlank()) {
            Text(
                doing,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) fg.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = ACTIVITY_MAX_WIDTH),
            )
        }
    }
}

/** 状态记号：在跑是一粒呼吸的海点，出错是朱，其余（完成 / 停止）是一粒静止的石墨点 */
@Composable
private fun AgentMark(thread: SubagentThread, onSea: Boolean) {
    val palette = MaterialTheme.sea
    when (thread.phase) {
        SubagentThread.Phase.Running -> LiveDot(if (onSea) palette.onSea else palette.sea)
        SubagentThread.Phase.Failed -> Seal(color = palette.vermilion)
        else -> Seal(color = if (onSea) palette.onSea else palette.graphite)
    }
}

@Composable
internal fun subagentPhaseLabel(phase: SubagentThread.Phase): String = stringResource(
    when (phase) {
        SubagentThread.Phase.Running -> R.string.agent_phase_running
        SubagentThread.Phase.Done -> R.string.agent_phase_done
        SubagentThread.Phase.Failed -> R.string.agent_phase_failed
        SubagentThread.Phase.Stopped -> R.string.agent_phase_stopped
    }
)

/**
 * 子 agent 视图顶上那一行：「← main」+「general-purpose · 描述 · 运行中」。挂在顶栏按钮行下面，
 * 跟着顶栏一起浮在对话上（顶栏高度量过再垫进列表，字不会被挡住）。
 */
@Composable
internal fun SubagentViewHeader(thread: SubagentThread, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val backLabel = stringResource(R.string.agent_view_back_label)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onBack)
                .semantics { contentDescription = backLabel }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(HugeIcons.ArrowLeft01, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.sea.seaDeep)
            Text(
                stringResource(R.string.agent_view_back),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.sea.seaDeep,
            )
        }
        AgentMark(thread, onSea = false)
        Text(
            listOf(thread.agentType, thread.description, subagentPhaseLabel(thread.phase))
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Agent 卡展开后的最后一行：点进这个子 agent 自己的对话 */
@Composable
internal fun OpenSubagentRow(card: ChatItem.ToolCall, onOpen: () -> Unit) {
    val steps = card.subItems.size
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (steps > 0) stringResource(R.string.agent_open_conversation, steps)
            else stringResource(R.string.agent_open_conversation_empty),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.sea.seaDeep,
        )
        Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.sea.seaDeep)
    }
}

/** 用户气泡上方那一行小字：这句是说给哪个子 agent 的、怎么送、送到没有 */
@Composable
internal fun HandoffLabel(handoff: ChatItem.Handoff) {
    val text = when (handoff.state) {
        ChatItem.HandoffState.Waiting -> stringResource(R.string.handoff_waiting, handoff.agentType)
        ChatItem.HandoffState.Delivered -> stringResource(R.string.handoff_delivered, handoff.agentType)
        ChatItem.HandoffState.Relayed -> stringResource(R.string.handoff_relayed, handoff.agentType)
        ChatItem.HandoffState.Undelivered -> stringResource(R.string.handoff_undelivered, handoff.agentType)
    }
    val color = when (handoff.state) {
        ChatItem.HandoffState.Undelivered -> MaterialTheme.colorScheme.error
        ChatItem.HandoffState.Delivered -> MaterialTheme.sea.seaDeep
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        // 在路上的那一格带一粒呼吸的点：它在等子 agent 的下一步，不是卡住了
        if (handoff.state == ChatItem.HandoffState.Waiting) LiveDot(MaterialTheme.sea.sea, size = 5.dp)
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }
}

/** 跑完的 chip 淡到多少：看得出「已经结束」，又还读得清字 */
private const val ENDED_ALPHA = 0.55f

/** chip 里「在干什么」那段最多多宽：再宽一屏就只放得下一只 chip */
private val ACTIVITY_MAX_WIDTH = 150.dp
