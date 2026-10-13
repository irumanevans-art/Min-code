package dev.min.code.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.min.code.R
import dev.min.code.core.session.ChatItem

/**
 * 整条会话导出成 markdown。
 *
 * 会话流里每条都包了 `SelectionContainer`，但**跨条选择是做不到的** —— LazyColumn 会把
 * 滚出屏幕的条目回收掉，选区跟着断，所以每条只能各管各的。想把一整轮工作贴到别处时，
 * 逐条选逐条复制不现实，这里一次性给全。
 *
 * 工具调用只导出摘要不导出完整结果：一次 Read 的结果动辄上千行，全塞进去会把真正想要的
 * 对话内容淹掉。要完整结果的话展开那条工具卡单独选中复制。
 */
internal fun List<ChatItem>.toTranscriptText(labels: TranscriptLabels): String = buildString {
    this@toTranscriptText.forEach { item ->
        when (item) {
            is ChatItem.UserText -> append("## ${labels.user}\n\n${item.text}\n\n")
            // 说话人是产品名，不翻
            // 导出是会话记录的口径：MessageDisplay 只改屏显，导出取原文
            is ChatItem.AssistantText -> append("## Claude\n\n${item.transcriptText}\n\n")
            is ChatItem.Thinking -> append("### ${labels.thinking}\n\n${item.text}\n\n")
            is ChatItem.Note -> append("> ${item.text}\n\n")
            // 回退掉的对话不导出（导出的是「现在这条对话」），折叠行本身留一句
            is ChatItem.Folded -> append("> ${item.title}\n\n")
            // 子 agent 的报告：标题行（类型 · 描述 · 状态）加正文，导出时不折叠
            // 发给用户的文件：导出文件名，文件本身不在导出里
            is ChatItem.SentFiles -> append("> ${(listOfNotNull(item.caption) + item.names).joinToString(" · ")}\n\n")
            is ChatItem.AgentReport -> append("> ${item.agentType}${item.description?.let { " · $it" }.orEmpty()} · ${item.status}\n\n${item.body}\n\n")
            // 进程输出导成代码块：贴到别处去查的时候，等宽和原样换行都要保住
            is ChatItem.ProcessOutput ->
                append("```\n${item.lines.joinToString("\n")}\n```\n\n")
            is ChatItem.ToolCall -> {
                val status = when (item.status) {
                    ChatItem.ToolCall.Status.Running -> labels.toolRunning
                    ChatItem.ToolCall.Status.Done -> labels.toolDone
                    ChatItem.ToolCall.Status.Error -> labels.toolError
                }
                append("- `${item.name}` ${toolSummary(item.name, item.input, labels)} — $status\n\n")
            }
        }
    }
}.trim()

/**
 * 会话流表层用到的全部字头。[toTranscriptText]、[ToolEntry]、[ToolCallDetail]、
 * [ToolInputPreview] 和会话搜索都不是 @Composable（或不该逐条取资源），取不到
 * `stringResource`，由调用侧在组合时取好一次性传下去。
 *
 * 带 `%1$d` 的是格式模板，消费方用 `String.format` 现场填数。
 */
internal data class TranscriptLabels(
    val user: String,
    val thinking: String,
    val toolRunning: String,
    val toolDone: String,
    val toolError: String,
    /** 折叠行右侧：这次调用卡在等人批准 */
    val toolAwaiting: String,
    /** Read 折叠行：「101–200 行」 */
    val readRange: String,
    /** Read 折叠行：「从 101 行」 */
    val readFrom: String,
    /** Read 折叠行：「前 50 行」 */
    val readFirst: String,
    /** Edit 折叠行：replace_all 时的「全部替换」 */
    val replaceAll: String,
    /** 行数（Write 的行数、结果行数共用）：「N 行」 */
    val nLines: String,
    /** MultiEdit 的编辑处数：「N 处」 */
    val nEdits: String,
    /** TodoWrite 的进度：「1/3 已完成」 */
    val todoDone: String,
    /** AskUserQuestion 摘不出去内容时的兜底 */
    val askUser: String,
    /** 计划模式工具没有 plan 正文时的兜底 */
    val planMode: String,
    /** 子任务角标：「N 步」（复用工作块的步数文案） */
    val nSteps: String,
    /** diff 公共前缀省略的头，`@@` 前缀是 DiffView 着色的依据，两种语言都得保留 */
    val diffElidedAbove: String,
    /** diff 公共后缀省略的头，同上 */
    val diffElidedBelow: String,
    /** diff 源字段超长截断的尾巴 */
    val diffTruncated: String,
    // 设备操控那六把工具的折叠行摘要。它们经 MCP 挂进来，入参是 index / direction
    // 这种裸值，走不到按文件名 / 按命令那套摘要上去
    val deviceTap: String,
    val deviceInput: String,
    val deviceSwipe: String,
    val deviceOpenApp: String,
    val deviceBack: String,
    val deviceUiTree: String,
)

@Composable
internal fun rememberTranscriptLabels(): TranscriptLabels = TranscriptLabels(
    user = stringResource(R.string.session_export_user),
    thinking = stringResource(R.string.session_export_thinking),
    toolRunning = stringResource(R.string.session_tool_status_running),
    toolDone = stringResource(R.string.session_tool_status_done),
    toolError = stringResource(R.string.session_tool_status_error),
    toolAwaiting = stringResource(R.string.session_tool_status_awaiting),
    readRange = stringResource(R.string.tool_summary_range),
    readFrom = stringResource(R.string.tool_summary_from_line),
    readFirst = stringResource(R.string.tool_summary_first_lines),
    replaceAll = stringResource(R.string.tool_summary_replace_all),
    nLines = stringResource(R.string.tool_summary_n_lines),
    nEdits = stringResource(R.string.tool_summary_n_edits),
    todoDone = stringResource(R.string.tool_summary_todo_done),
    askUser = stringResource(R.string.tool_summary_ask_user),
    planMode = stringResource(R.string.tool_summary_plan),
    nSteps = stringResource(R.string.transcript_work_steps),
    diffElidedAbove = stringResource(R.string.tool_diff_elided_above),
    diffElidedBelow = stringResource(R.string.tool_diff_elided_below),
    diffTruncated = stringResource(R.string.tool_diff_truncated),
    deviceTap = stringResource(R.string.device_tool_tap),
    deviceInput = stringResource(R.string.device_tool_input),
    deviceSwipe = stringResource(R.string.device_tool_swipe),
    deviceOpenApp = stringResource(R.string.device_tool_open_app),
    deviceBack = stringResource(R.string.device_tool_back),
    deviceUiTree = stringResource(R.string.device_tool_ui_tree),
)
