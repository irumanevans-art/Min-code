package dev.min.code.ui.codex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.ui.session.AssistantEntry
import dev.min.code.ui.session.CollapseWorkFooter
import dev.min.code.ui.session.CollapsedWorkEntry
import dev.min.code.ui.session.LocalAwaitingToolUseId
import dev.min.code.ui.session.NoteEntry
import dev.min.code.ui.session.ThinkingEntry
import dev.min.code.ui.session.TranscriptBlock
import dev.min.code.ui.session.TranscriptItem
import dev.min.code.ui.session.awaitingToolUseId
import dev.min.code.ui.session.groupTranscript
import dev.min.code.ui.session.rememberTranscriptLabels
import kotlinx.coroutines.flow.collectLatest

/**
 * Codex 的会话流。
 *
 * 渲染的每一条都走 `ui/session` 里那套 —— 同一个 [groupTranscript] 折叠规则、
 * 同一批气泡和工具卡、同一条左轨道。Codex 和 Claude 在屏幕上因此长得一模一样，
 * 差别只在谁在说话。这也是把 ChatItem 提到引擎之上的全部意义：
 * 这个文件只负责"怎么摆"，没有一行在负责"怎么画"。
 *
 * 撤销传 null：那是 Claude 侧的编辑快照，Codex 还没有对应的东西。
 */
@Composable
internal fun CodexTranscript(
    session: CodexAppServerManager.State,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
) {
    val blocks = remember(session.items) { groupTranscript(session.items) }
    // 工具卡文案与 Claude 侧同一份字头包：两个引擎画同一套卡片，文案也同源
    val labels = rememberTranscriptLabels()
    // 手动开合优先于"跑完自动收起"，提在列表外面 —— LazyColumn 会把滚出屏幕的条目
    // 连同它的 remember 一起回收，放在里面的话滑回去时开合状态就没了
    val manualExpanded = remember { mutableStateMapOf<String, Boolean>() }
    val streaming = session.streamingText.isNotBlank() || session.streamingThinking.isNotBlank()
    val error = session.errorMessage?.takeIf { it.isNotBlank() }
    val lastIndex = blocks.lastIndex

    // 新内容到了就跟到底部，但**只在用户本来就在底部时** —— 正往回翻旧消息时
    // 被硬拽到底，比不自动滚还难用。
    // session 是普通参数，LaunchedEffect(listState) 的协程只捕获首次组合那份闭包，
    // snapshotFlow 直读它永远读到旧对象、永不发射。经 rememberUpdatedState 转一手，
    // 每次重组写进 State，snapshotFlow 才跟得上新内容。
    val latest = rememberUpdatedState(session)
    LaunchedEffect(listState) {
        snapshotFlow { latest.value.items.size to latest.value.streamingText.length }
            .collectLatest {
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull()?.index ?: return@collectLatest
                if (last >= layout.totalItemsCount - 2) {
                    listState.animateScrollToItem((layout.totalItemsCount - 1).coerceAtLeast(0))
                }
            }
    }

    // 挂着审批时那张卡显示成「等你批准」而不是转圈。Codex 的 itemId 就是卡的 toolUseId
    //（见 CodexChatMapping），不需要按工具名回退
    val awaiting = awaitingToolUseId(
        items = session.items,
        pendingToolName = null,
        pendingToolUseId = session.pendingApproval?.itemId,
    )

    CompositionLocalProvider(LocalAwaitingToolUseId provides awaiting) {
    LazyColumn(
        state = listState,
        // 条目之间不能留白：一有间距，左轨道就断成一节一节
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        itemsIndexed(blocks, key = { _, block -> block.key }) { index, block ->
            val isFirst = index == 0
            val isLast = index == lastIndex && !streaming && error == null
            Box {
                when (block) {
                    is TranscriptBlock.Single -> TranscriptItem(block.item, isFirst, isLast, labels)

                    is TranscriptBlock.Work -> {
                        // 只有"最后一块 + 还在跑"默认展开：那时候"它现在在干什么"
                        // 正是唯一重要的事
                        val live = session.busy && index == lastIndex
                        val expanded = manualExpanded[block.key] ?: live
                        if (!expanded) {
                            CollapsedWorkEntry(
                                items = block.items,
                                isFirst = isFirst,
                                isLast = isLast,
                                onExpand = { manualExpanded[block.key] = true },
                            )
                        } else {
                            Column {
                                block.items.forEachIndexed { i, item ->
                                    TranscriptItem(
                                        item = item,
                                        isFirst = isFirst && i == 0,
                                        // 展开时块尾还挂着一条"收起"，轨道不能在这里断
                                        isLast = false,
                                        labels = labels,
                                    )
                                }
                                CollapseWorkFooter(
                                    isLast = isLast,
                                    onCollapse = { manualExpanded[block.key] = false },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (streaming) {
            item(key = "streaming") {
                Column {
                    if (session.streamingThinking.isNotBlank()) {
                        ThinkingEntry(
                            text = session.streamingThinking,
                            id = "codex-streaming-thinking",
                            isFirst = blocks.isEmpty(),
                            isLast = session.streamingText.isBlank(),
                            streaming = true,
                        )
                    }
                    if (session.streamingText.isNotBlank()) {
                        AssistantEntry(
                            text = session.streamingText,
                            isFirst = blocks.isEmpty() && session.streamingThinking.isBlank(),
                            isLast = error == null,
                            streaming = true,
                        )
                    }
                }
            }
        }

        // 会话级的错误进流的末尾，而不是浮一条盖住内容的横幅 ——
        // "为什么停了"和"停之前发生了什么"要能一起读
        if (error != null) {
            item(key = "error") {
                NoteEntry(
                    text = error,
                    isError = true,
                    isFirst = blocks.isEmpty() && !streaming,
                    isLast = true,
                )
            }
        }
    }
    }
}
