package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import dev.min.code.ui.theme.seaFill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.ui.components.InkSpinner
import dev.min.code.ui.richtext.MarkdownBlock
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.MinKai
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01

/**
 * 会话流的版式骨架：一条贯穿始终的左轨道，每条记录挂一个标记符。
 *
 * ## 为什么不是聊天气泡
 *
 * Claude Code 做的是**按顺序干活**（想 → 调工具 → 拿结果 → 再想 → 回答），不是对话。
 * 它自己的 TUI 就是一条 transcript。用左右分栏的气泡渲染它有三个问题：
 * 用户消息右对齐白白吃掉约 15% 可读宽度；助手正文、工具卡、思考块、系统提示四种容器
 * 样式互相抢视觉权重；工具活动明明是主体内容，却被挤成气泡之间的灰色小卡。
 *
 * 单列 + 轨道解决的正是这三点：所有内容共用一条左边界，眼睛只扫一列；
 * 标记符负责区分类型，容器就不必再承担这个职责，于是助手正文可以完全不加容器
 * ——散文就该长得像散文。
 *
 * ## 轨道在 LazyColumn 里怎么连续
 *
 * 每个条目自己画自己那一段：`drawBehind` 里画一条从顶到底的竖线。相邻条目之间
 * **不能有间距**（所以列表用的是 `contentPadding` 而不是 `Arrangement.spacedBy`，
 * 留白放在条目内部），线段才会首尾相接看着像一条。末条轨道跟随实测内容高度，
 * 在正文下方独立保留 38 dp，用开放回线和一枚金切面收尾。
 *
 * ## 物质
 *
 * 轨道是墨金双丝；标记的颜色说的是"谁 / 什么状态"：你说的话是金切面，正在跑的是湛，
 * 出错的是朱砂叉，其余是石墨。全页只有这一套颜色规则（见 DESIGN.md）。
 */

/** 条目内部的上下留白。列表不加间距，全靠这个撑开，轨道才连得上 */
private val ENTRY_PADDING = 7.dp

/**
 * 标记符形状。**每个形状都编码一条真实的区分**，不是装饰：
 * 保留枚举名称以兼容调用方，画法为金切面=用户、开放结=思考、双流线=运行中、
 * 镂刻切面=已完成、朱色交叉切面=失败、双刻线=提示，无标记=助手正文。
 */
internal enum class RailMarker { None, Dot, Ring, SquareFilled, SquareOutline, Cross, Dash }

// ---------------------------------------------------------------------------
// 分组：把连续的"干活"折成一块
// ---------------------------------------------------------------------------

/**
 * 会话流的一个渲染单元。
 *
 * ## 为什么要分组
 *
 * 一轮任务里"思考 → 调工具 → 看结果 → 再思考"能滚出几十条，读完之后它们就再也不值
 * 一屏了 —— 真正要回头看的是**你说了什么**和**它答了什么**。全部平铺的结果是：
 * 想找上一句回答要往回滑十几屏，而中间全是已经跑完、不会再变的工具日志。
 *
 * 所以连续的工作过程折成一块，跑完自动收起，只留一行"12 步（Bash 5 · Read 3 · 思考 3）"。
 * 需要复盘时点开，里面还是原来那套完整渲染，一条都不少。
 *
 * 生成过程中**不折叠** —— 那时候"它现在在干什么"正是唯一重要的事。
 */
internal sealed interface TranscriptBlock {
    /** 稳定的列表 key：取块内第一条的 id。块变长时它不变，所以不会触发重建 */
    val key: String

    /** 单独成条：用户消息、助手正文、以及出错的系统提示 */
    data class Single(val item: ClaudeCodeManager.ChatItem) : TranscriptBlock {
        override val key: String get() = item.id
    }

    /** 连续的工作过程（思考 / 工具调用 / 普通系统提示），至少两条才成块 */
    data class Work(val items: List<ClaudeCodeManager.ChatItem>) : TranscriptBlock {
        override val key: String get() = items.first().id
    }
}

/**
 * 哪些条目算"工作过程"。
 *
 * **出错的系统提示不算**：折叠等于把报错藏起来，而报错恰恰是最需要一眼看到的东西。
 * 它单独成条，顺带把一段长工作切成两块 —— 出错前后本来就该分开读。
 */
private fun ClaudeCodeManager.ChatItem.isWork(): Boolean = when (this) {
    is ClaudeCodeManager.ChatItem.Thinking -> true
    is ClaudeCodeManager.ChatItem.ToolCall -> true
    is ClaudeCodeManager.ChatItem.Note -> !isError
    // 进程输出自己已经是一条折叠行了，再折进"N 步"里会被算成一步工作，
    // 而它不是模型干的活。单独成条，和出错的系统提示一样切开前后两段。
    is ClaudeCodeManager.ChatItem.ProcessOutput -> false
    else -> false
}

/**
 * 把扁平的条目列表折成渲染单元。孤零零一条工作不成块 —— 为一条 Bash 套一层
 * "1 步，点开看"只是徒增一次点击。
 */
internal fun groupTranscript(items: List<ClaudeCodeManager.ChatItem>): List<TranscriptBlock> {
    val blocks = ArrayList<TranscriptBlock>(items.size)
    var run = ArrayList<ClaudeCodeManager.ChatItem>()

    fun flush() {
        when {
            run.isEmpty() -> Unit
            run.size == 1 -> blocks += TranscriptBlock.Single(run[0])
            else -> blocks += TranscriptBlock.Work(run)
        }
        run = ArrayList()
    }

    items.forEach { item ->
        if (item.isWork()) {
            run += item
        } else {
            flush()
            blocks += TranscriptBlock.Single(item)
        }
    }
    flush()
    return blocks
}

/**
 * 折叠态那一行写什么。
 *
 * 按工具名计数而不是罗列 —— `Bash 5 · Read 3` 一眼看得出这段在干哪类活，
 * 罗列五条 Bash 只会把行挤满却什么都没说。思考并到一起计数，它没有名字可分。
 */
internal fun workBlockSummary(items: List<ClaudeCodeManager.ChatItem>): String {
    val thinking = items.count { it is ClaudeCodeManager.ChatItem.Thinking }
    val tools = items.filterIsInstance<ClaudeCodeManager.ChatItem.ToolCall>()
    val parts = ArrayList<String>()
    if (thinking > 0) parts += "思考 $thinking"
    // 保持首次出现的顺序：读起来就是这段活的时间线，按字母排反而没有意义
    tools.groupBy { it.name }.forEach { (name, calls) ->
        parts += if (calls.size > 1) "$name ${calls.size}" else name
    }
    val failed = tools.count { it.status == ClaudeCodeManager.ChatItem.ToolCall.Status.Error }
    if (failed > 0) parts += "$failed 个出错"
    return "${items.size} 步" + if (parts.isEmpty()) "" else "（${parts.joinToString(" · ")}）"
}

/**
 * 一条记录 = 左侧轨道段 + 标记 + 右侧内容。
 *
 * @param active 这条正在进行中。局部青色流光限制在轨道内，不覆盖正文。
 */
@Composable
internal fun TranscriptEntry(
    marker: RailMarker,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    tone: RailTone = RailTone.Ink,
    active: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .transcriptRail(marker, isFirst, isLast, tone, active),
    ) {
        Spacer(Modifier.width(TranscriptRailGutter))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(
                    top = ENTRY_PADDING,
                    bottom = ENTRY_PADDING + if (isLast) TranscriptRailTail else 0.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/** 展开 / 收起的箭头：一个 ArrowDown 转 180°，而不是换图标 */
@Composable
private fun Chevron(expanded: Boolean, contentDescription: String?) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = InkMotion.spatial(),
        label = "chevron",
    )
    Icon(
        HugeIcons.ArrowDown01,
        contentDescription = contentDescription,
        modifier = Modifier
            .size(14.dp)
            .graphicsLayer { rotationZ = rotation },
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------------------
// 各类条目
// ---------------------------------------------------------------------------

/**
 * 你说的话。全流里**唯一**带底色块的条目 —— 它是这条时间轴上仅有的人类输入，
 * 值得一个能一眼扫到的锚点；其余内容都是机器产物，共用同一片底。
 * 一只气泡：浅一阶的纸，左缘是一道海（一扇 2.5dp 宽的窗），贴轨道那一角收成小圆角，
 * 其余三角是大圆角——气泡是从轨道上那粒海里长出来的。
 */
@Composable
internal fun UserEntry(text: String, isFirst: Boolean, isLast: Boolean, queued: Boolean = false) {
    TranscriptEntry(
        marker = RailMarker.Dot,
        isFirst = isFirst,
        isLast = isLast,
        tone = RailTone.Sea,
    ) {
        val palette = MaterialTheme.sea
        val scheme = MaterialTheme.colorScheme
        // 排队中：这条还**没有**发给 CLI，按停止键（Esc）能原样撤回。
        // 说出来才不会以为它已经在跑了 —— 上一轮还占着，它得等。
        if (queued) {
            Text(
                text = "排队中 · 按停止可撤回",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        // 贴轨道那一角 4 dp：气泡是从那粒海里长出来的。其余 14 dp。
        // Column 给子项的是横向撑满的约束。外层 Box 把 min 松开，
        // wrapContentWidth 让 Row 按字宽收，widthIn 封顶并允许长句换行。
        val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp)
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .align(Alignment.CenterStart)
                    .widthIn(max = 340.dp)
                    .wrapContentWidth(Alignment.Start)
                    .height(IntrinsicSize.Min)
                    .clip(shape)
                    .background(palette.paper2),
            ) {
                Box(Modifier.width(2.5.dp).fillMaxHeight().seaFill())
                SelectionContainer {
                    Text(
                        text = text,
                        // 人的声音：楷书，比 sans 放大半号
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MinKai, fontSize = 15.sp, lineHeight = 23.sp),
                        color = scheme.onSurface,
                        modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                    )
                }
            }
        }
    }
}

/**
 * 助手正文。**不给容器**是刻意的：加个卡片只会让散文和工具日志抢视觉权重，
 * 而正文本来就该是这一页读起来最舒服的东西。
 *
 * @param streaming 这一条还在生成中。**生成期间绝不能包 [SelectionContainer]**：
 *   Markdown 在不断重渲染，内部可选择的 Text 会频繁注册/注销，与 Compose 选择工具栏
 *   在绘制阶段对 selectable 列表的排序产生并发修改，抛 ConcurrentModificationException。
 *   普通聊天页踩过同一个坑（见 ChatMessage.kt 里那段注释），处理方式也一样：
 *   生成结束、内容稳定之后才允许选中。
 */
@Composable
internal fun AssistantEntry(
    text: String,
    isFirst: Boolean,
    isLast: Boolean,
    streaming: Boolean = false,
    durationMs: Long? = null,
    outputTokens: Int? = null,
) {
    if (text.isBlank()) return
    TranscriptEntry(
        marker = RailMarker.None,
        isFirst = isFirst,
        isLast = isLast,
        active = streaming,
    ) {
        if (streaming) {
            MarkdownBlock(content = text, modifier = Modifier.fillMaxWidth())
        } else {
            SelectionContainer {
                MarkdownBlock(content = text, modifier = Modifier.fillMaxWidth())
            }
            if (durationMs != null || outputTokens != null) {
                AssistantReceipt(durationMs = durationMs, outputTokens = outputTokens)
            }
        }
    }
}

@Composable
private fun AssistantReceipt(durationMs: Long?, outputTokens: Int?) {
    val parts = listOfNotNull(
        durationMs?.let { "用时 ${formatDuration(it)}" },
        outputTokens?.takeIf { it > 0 }?.let { "↓${formatTokens(it)}" },
    )
    if (parts.isEmpty()) return
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 折叠态最多显示几行；生成中显示尾部，文字就在这几行里向上流动 */
private const val THINKING_PREVIEW_LINES = 3
private const val THINKING_PREVIEW_CHARS = 240

/**
 * 思考。**默认折叠** —— 一轮思考动辄上千字，整段铺开会把工具调用和回答全挤出屏幕。
 *
 * 生成中显示**尾部** 3 行而不是头部：新 token 到达时文字在固定高度里向上滚，
 * 既看得出它在动，又不占地方。跑完只剩一行标题。生成中的环是湛色、脉冲。
 */
@Composable
internal fun ThinkingEntry(
    text: String,
    id: String,
    isFirst: Boolean,
    isLast: Boolean,
    streaming: Boolean = false,
) {
    if (text.isBlank()) return
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    TranscriptEntry(
        marker = RailMarker.Ring,
        isFirst = isFirst,
        isLast = isLast,
        active = streaming,
        tone = if (streaming) RailTone.Sea else RailTone.Ink,
    ) {
        // 折叠开关只挂在标题行上，不再挂整条 —— 正文可以选中之后，
        // "点一下正文就把整块收起来"会让人根本没法读完一段长思考
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Text(
                text = if (streaming) "思考中" else "思考 · ${text.length} 字",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Chevron(expanded = expanded, contentDescription = if (expanded) "收起思考过程" else "展开思考过程")
        }
        AnimatedVisibility(
            visible = expanded || streaming,
            enter = InkMotion.expand,
            exit = InkMotion.collapse,
        ) {
            val body = @Composable {
                Text(
                    text = if (expanded) text else text.takeLast(THINKING_PREVIEW_CHARS).trimStart(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else THINKING_PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 生成中的那段不给选中：理由同 [AssistantEntry]，文字每来一个 token 就重排一次
            if (streaming) body() else SelectionContainer { body() }
        }
    }
}

/**
 * 折叠起来的一段工作过程。
 *
 * 标记用镂刻切面（和已完成的工具调用同一个符号）：这一块整体就是一批跑完的
 * 工具活动，用同一个符号才不会让人以为它是另一种东西。里面有出错的工具时转成朱砂，
 * 否则"某一步炸了"会被折叠彻底藏掉。
 */
@Composable
internal fun CollapsedWorkEntry(
    items: List<ClaudeCodeManager.ChatItem>,
    isFirst: Boolean,
    isLast: Boolean,
    onExpand: () -> Unit,
) {
    val failed = items.any {
        it is ClaudeCodeManager.ChatItem.ToolCall &&
            it.status == ClaudeCodeManager.ChatItem.ToolCall.Status.Error
    }
    TranscriptEntry(
        marker = if (failed) RailMarker.Cross else RailMarker.SquareOutline,
        isFirst = isFirst,
        isLast = isLast,
        tone = if (failed) RailTone.Error else RailTone.Ink,
        modifier = Modifier.clickable(onClick = onExpand),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = workBlockSummary(items),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetbrainsMono,
                color = if (failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                HugeIcons.ArrowDown01,
                contentDescription = "展开这段工作过程",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 展开后挂在末尾的收起按钮。没有它就只能滑到头去找那条折叠行 */
@Composable
internal fun CollapseWorkFooter(isLast: Boolean, onCollapse: () -> Unit) {
    TranscriptEntry(marker = RailMarker.None, isFirst = false, isLast = isLast) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onCollapse)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Icon(
                HugeIcons.ArrowUp01,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "收起这段过程",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 进程原样吐出来的那几行（stderr）。
 *
 * **默认折叠，且不是朱砂** —— 它可见但不报警。里面大多是 Node 和 proot 的例行警告，
 * 染成红的等于天天喊狼来了；真正的故障另有红字那条走 [NoteEntry]。
 * 展开是等宽、可选中的原文：这一条从来不是给人"读"的，是给人"查"的。
 */
@Composable
internal fun ProcessOutputEntry(
    item: ClaudeCodeManager.ChatItem.ProcessOutput,
    isFirst: Boolean,
    isLast: Boolean,
) {
    if (item.lines.isEmpty()) return
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    TranscriptEntry(marker = RailMarker.Dash, isFirst = isFirst, isLast = isLast) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Text(
                text = "进程输出 · ${item.lines.size} 行" +
                    if (item.dropped > 0) "（已略过最早 ${item.dropped} 行）" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Chevron(expanded = expanded, contentDescription = if (expanded) "收起进程输出" else "展开进程输出")
        }
        AnimatedVisibility(visible = expanded, enter = InkMotion.expand, exit = InkMotion.collapse) {
            SelectionContainer {
                Text(
                    text = item.lines.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 系统提示：会话建立、上下文压缩、API 报错、模型回退…… 一行，弱化；出错的是朱砂 */
@Composable
internal fun NoteEntry(text: String, isError: Boolean, isFirst: Boolean, isLast: Boolean) {
    TranscriptEntry(
        marker = if (isError) RailMarker.Cross else RailMarker.Dash,
        isFirst = isFirst,
        isLast = isLast,
        tone = if (isError) RailTone.Error else RailTone.Ink,
    ) {
        // 报错信息尤其需要能复制出去搜——这一条几乎从不是给人"读"的，是给人"查"的
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 工具调用。折叠态一行就要说清"它在干什么"，展开态按工具类型渲染
 * （diff / 语法高亮 / 待办列表），见 [ToolCallDetail]。
 *
 * 正在跑的切面带湛色双流线，右侧显示同色进度；出错是朱色交叉切面。
 *
 * @param onRevert 撤销这次文件修改。只在有快照时传非空 —— 给一个按下去只会报错的按钮，
 *   比没有按钮更糟。
 */
@Composable
internal fun ToolEntry(
    item: ClaudeCodeManager.ChatItem.ToolCall,
    isFirst: Boolean,
    isLast: Boolean,
    onRevert: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val running = item.status == ClaudeCodeManager.ChatItem.ToolCall.Status.Running
    val azure = MaterialTheme.sea.seaDeep
    TranscriptEntry(
        marker = when (item.status) {
            ClaudeCodeManager.ChatItem.ToolCall.Status.Running -> RailMarker.SquareFilled
            ClaudeCodeManager.ChatItem.ToolCall.Status.Done -> RailMarker.SquareOutline
            ClaudeCodeManager.ChatItem.ToolCall.Status.Error -> RailMarker.Cross
        },
        isFirst = isFirst,
        isLast = isLast,
        active = running,
        tone = when (item.status) {
            ClaudeCodeManager.ChatItem.ToolCall.Status.Error -> RailTone.Error
            ClaudeCodeManager.ChatItem.ToolCall.Status.Running -> RailTone.Sea
            else -> RailTone.Ink
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            // 这里**不放工具图标**：轨道标记已经编码了状态，右边的工具名又用文字说了一遍
            // 它是什么。一个 360dp 的行放不下四样东西，图标是其中信息量最低的那个，
            // 去掉它把宽度让给摘要——摘要才是"不展开也知道在干什么"的关键。
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetbrainsMono,
                color = if (item.isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = toolSummary(item.name, item.input),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                InkSpinner(size = 11.dp, color = azure)
            } else {
                toolBadge(item)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = InkMotion.expand,
            exit = InkMotion.collapse,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolCallDetail(item, onRevert = onRevert)
                // 子 agent 干的活挂在这条 Task 底下。官方终端看不进去，这里能
                if (item.subItems.isNotEmpty()) SubagentTranscript(item.subItems)
            }
        }
    }
}
