package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeCodeEvent
import dev.min.code.core.claudecode.SubagentThread
import dev.min.code.ui.richtext.MarkdownBlock
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.sea

/**
 * 一个子 agent 的完整对话，占满会话页的对话区（顶栏、输入坞照旧浮在上面）。
 *
 * 用的是**和主会话同一套**条目（[TranscriptBlockEntry] → [TranscriptItem]）：子 agent 里的 diff、
 * 命令高亮、待办列表和外面一模一样，工作过程一样会折叠。以前子任务是在 Agent 卡里铺一份
 * 窄一号的专用渲染，两边各长各的。
 *
 * 列表状态自己一份：切回 main 时主会话停在原来的位置，不被这里的滚动带走。
 * 子 agent 结束后照样能看——内容挂在它那张 Agent 卡上，卡一直在。
 *
 * @param pendingPermission 会话挂着的权限请求；子 agent 调的工具要批准时，它的卡要显示成「等你批准」
 */
@Composable
internal fun SubagentConversation(
    thread: SubagentThread,
    labels: TranscriptLabels,
    contentPadding: PaddingValues,
    pendingPermission: ClaudeCodeEvent.PermissionRequest?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val follow = rememberTranscriptFollow(listState)
    FollowTailEffect(listState, follow)
    val blocks = remember(thread.items) { groupTranscript(thread.items) }
    // 开合状态提在列表外面，理由同主会话（LazyColumn 会回收滚出屏幕的条目）
    val manualExpanded = remember(thread.toolUseId) { mutableStateMapOf<String, Boolean>() }
    val awaiting = awaitingToolUseId(
        items = thread.items,
        pendingToolName = pendingPermission?.toolName,
        pendingToolUseId = pendingPermission?.toolUseId,
    )
    val hasPrompt = !thread.prompt.isNullOrBlank()
    CompositionLocalProvider(LocalAwaitingToolUseId provides awaiting) {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            // 条目之间不能留白：一有间距，左轨道就断成一节一节
            contentPadding = contentPadding,
        ) {
            if (hasPrompt) {
                item(key = "prompt") {
                    SubagentPromptEntry(
                        prompt = thread.prompt.orEmpty(),
                        id = thread.toolUseId,
                        isLast = blocks.isEmpty() && !thread.running,
                    )
                }
            }
            itemsIndexed(blocks, key = { _, block -> block.key }) { index, block ->
                TranscriptBlockEntry(
                    block = block,
                    isFirst = index == 0 && !hasPrompt,
                    isLast = index == blocks.lastIndex && !thread.running,
                    live = thread.running && index == blocks.lastIndex,
                    manualExpanded = manualExpanded,
                    labels = labels,
                )
            }
            if (thread.running || (blocks.isEmpty() && !hasPrompt)) {
                item(key = "live") {
                    SubagentLiveEntry(thread, isFirst = blocks.isEmpty() && !hasPrompt)
                }
            }
        }
    }
}

/**
 * 主会话交代给它的任务（Agent 调用的 prompt）。不是人说的话，所以不用你的气泡；
 * 长的默认收成几行，点开看全文。
 */
@Composable
private fun SubagentPromptEntry(prompt: String, id: String, isLast: Boolean) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    TranscriptEntry(marker = RailMarker.Dash, isFirst = true, isLast = isLast) {
        Text(
            stringResource(R.string.agent_prompt_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        )
        AnimatedVisibility(visible = expanded, enter = InkMotion.expand, exit = InkMotion.collapse) {
            SelectionContainer { MarkdownBlock(content = prompt, modifier = Modifier.fillMaxWidth()) }
        }
        if (!expanded) {
            Text(
                prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = PROMPT_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            )
        }
    }
}

/** 末尾那条「它正在干什么」：进度摘要 > 最近的工具 > 工作中。跑完就不挂了 */
@Composable
private fun SubagentLiveEntry(thread: SubagentThread, isFirst: Boolean) {
    TranscriptEntry(
        marker = if (thread.running) RailMarker.SquareFilled else RailMarker.Dash,
        isFirst = isFirst,
        isLast = true,
        tone = if (thread.running) RailTone.Sea else RailTone.Ink,
        active = thread.running,
    ) {
        Text(
            text = if (thread.running) thread.activity ?: stringResource(R.string.agent_live_working)
            else stringResource(R.string.agent_view_empty),
            style = MaterialTheme.typography.labelSmall,
            color = if (thread.running) MaterialTheme.sea.sea else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 任务原文折叠时露几行：够认出是哪件事，又不把它自己的对话挤下去 */
private const val PROMPT_PREVIEW_LINES = 3
