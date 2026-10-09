package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.engine.min.reportStatusRes
import dev.min.code.core.session.ChatItem
import dev.min.code.ui.richtext.MarkdownBlock
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import java.util.Locale

/**
 * 子 agent 交来的报告（B10）：类型 · 名字、任务描述、状态、工具次数 / token / 耗时，正文默认折叠；
 * 点卡片（除了正文的开合）跳到那个子 agent 的线程，和 Agent 工具卡上的「查看子 agent 对话」同一个入口 [LocalOpenSubagent]。
 *
 * 没做完 / 出错的（[ChatItem.AgentReport.isError]）整张按失败色画，和失败的工具卡一样站在工作过程之外单独成条，
 * 免得被折进「N 步」里看不见。
 */
@Composable
internal fun AgentReportEntry(item: ChatItem.AgentReport, isFirst: Boolean, isLast: Boolean) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val open = LocalOpenSubagent.current
    val target = item.threadId?.takeIf { open != null }
    val scheme = MaterialTheme.colorScheme
    val statusColor = if (item.isError) scheme.error else MaterialTheme.sea.seaDeep
    TranscriptEntry(
        marker = if (item.isError) RailMarker.Cross else RailMarker.SquareOutline,
        isFirst = isFirst,
        isLast = isLast,
        tone = if (item.isError) RailTone.Error else RailTone.Ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .then(if (target != null) Modifier.clickable(role = Role.Button) { open?.invoke(target) } else Modifier),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    reportTitle(item),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetbrainsMono,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    reportStatusRes(item.status)?.let { stringResource(it) } ?: item.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                )
            }
            item.description?.let { description ->
                Text(description, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val stats = reportStats(item)
            if (stats.isNotEmpty()) {
                Text(stats, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (target != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.agent_open_conversation_empty), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.sea.seaDeep)
                    Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.sea.seaDeep)
                }
            }
        }
        if (item.body.isNotBlank()) {
            Text(
                stringResource(if (expanded) R.string.agent_report_collapse else R.string.agent_report_expand),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(vertical = 2.dp),
            )
            AnimatedVisibility(visible = expanded, enter = InkMotion.expand, exit = InkMotion.collapse) {
                SelectionContainer { MarkdownBlock(content = item.body, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
            }
        }
    }
}

/** 卡片标题：「类型」或「类型 · 名字」；fork 的类型名换成给人看的「分身」 */
@Composable
private fun reportTitle(item: ChatItem.AgentReport): String {
    val type = subagentTypeLabel(item.agentType)
    return item.name?.takeIf { it.isNotBlank() }?.let { "$type · $it" } ?: type
}

/** 「3 次工具调用 · 1.2k tokens · 4.5s」：哪一项缺就少哪一项 */
@Composable
private fun reportStats(item: ChatItem.AgentReport): String = listOfNotNull(
    item.toolCalls?.let { stringResource(R.string.min_agent_report_calls, it) },
    item.tokens?.let { stringResource(R.string.min_agent_report_tokens, compactCount(it)) },
    item.durationMs?.let { compactSeconds(it) },
).joinToString(" · ")

internal fun compactCount(n: Long): String = if (n >= 1000) String.format(Locale.ROOT, "%.1fk", n / 1000.0) else n.toString()
internal fun compactSeconds(ms: Long): String = String.format(Locale.ROOT, "%.1fs", ms / 1000.0)
