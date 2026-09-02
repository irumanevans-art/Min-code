package dev.min.code.ui.session

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.ui.richtext.MarkdownBlock

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
 * 留白放在条目内部），线段才会首尾相接看着像一条。首尾两条各自收在标记处，
 * 让整条轨道有明确的起止。
 */

/** 左侧轨道占的宽度；标记符画在它的水平中线上 */
private val RAIL_GUTTER = 26.dp

/** 轨道竖线相对条目左边缘的 x 位置 */
private val RAIL_X = 9.dp

/** 标记符中心距条目顶部的距离——对齐右侧第一行文字的视觉中心 */
private val MARKER_Y = 15.dp

private val RAIL_WIDTH = 1.5.dp
private val MARKER_RADIUS = 3.5.dp

/** 条目内部的上下留白。列表不加间距，全靠这个撑开，轨道才连得上 */
private val ENTRY_PADDING = 7.dp

/**
 * 标记符形状。**每个形状都编码一条真实的区分**，不是装饰：
 * 实心点=你说的话（唯一由人产生的条目），空心环=思考，方块=工具调用
 * （实心在跑 / 空心已完成 / 叉号出错），短横=系统提示，无标记=助手正文。
 */
internal enum class RailMarker { None, Dot, Ring, SquareFilled, SquareOutline, Cross, Dash }

/**
 * 系统动画是否开着。关掉动画（开发者选项 / 省电 / 无障碍）时轨道脉冲要退化成静态高亮，
 * 不能无视用户的系统级选择。
 */
@Composable
private fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
}

/**
 * 一条记录 = 左侧轨道段 + 标记 + 右侧内容。
 *
 * @param active 这条正在进行中。标记会做缓慢的明度脉冲 —— 全页**唯一**的动效，
 *   集中在一个地方比到处转圈更容易读出"它还在动"。
 */
@Composable
internal fun TranscriptEntry(
    marker: RailMarker,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.outline,
    active: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val pulse = if (active && animationsEnabled()) {
        val transition = rememberInfiniteTransition(label = "rail")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "railPulse",
        ).value
    } else {
        1f
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val x = RAIL_X.toPx()
                val markerY = MARKER_Y.toPx()
                val top = if (isFirst) markerY else 0f
                val bottom = if (isLast) markerY else size.height
                if (bottom > top) {
                    drawLine(
                        color = railColor,
                        start = Offset(x, top),
                        end = Offset(x, bottom),
                        strokeWidth = RAIL_WIDTH.toPx(),
                    )
                }
                drawMarker(marker, x, markerY, tint.copy(alpha = tint.alpha * pulse))
            },
    ) {
        Spacer(Modifier.width(RAIL_GUTTER))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = ENTRY_PADDING, horizontal = 0.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

private fun DrawScope.drawMarker(marker: RailMarker, x: Float, y: Float, color: Color) {
    val r = MARKER_RADIUS.toPx()
    val stroke = Stroke(width = RAIL_WIDTH.toPx())
    // 标记要压住轨道线，先用背景色挖一小段，形状才不会被竖线穿过去
    when (marker) {
        RailMarker.None -> Unit

        RailMarker.Dot -> drawCircle(color, radius = r, center = Offset(x, y))

        RailMarker.Ring -> {
            drawCircle(Color.Transparent, radius = r, center = Offset(x, y))
            drawCircle(color, radius = r, center = Offset(x, y), style = stroke)
        }

        RailMarker.SquareFilled -> drawRect(
            color,
            topLeft = Offset(x - r, y - r),
            size = Size(r * 2, r * 2),
        )

        RailMarker.SquareOutline -> drawRect(
            color,
            topLeft = Offset(x - r, y - r),
            size = Size(r * 2, r * 2),
            style = stroke,
        )

        RailMarker.Cross -> {
            drawLine(color, Offset(x - r, y - r), Offset(x + r, y + r), stroke.width)
            drawLine(color, Offset(x - r, y + r), Offset(x + r, y - r), stroke.width)
        }

        RailMarker.Dash -> drawLine(
            color,
            Offset(x - r, y),
            Offset(x + r, y),
            stroke.width,
        )
    }
}

// ---------------------------------------------------------------------------
// 各类条目
// ---------------------------------------------------------------------------

/**
 * 你说的话。全流里**唯一**带底色块的条目 —— 它是这条时间轴上仅有的人类输入，
 * 值得一个能一眼扫到的锚点；其余内容都是机器产物，共用同一片底。
 */
@Composable
internal fun UserEntry(text: String, isFirst: Boolean, isLast: Boolean) {
    TranscriptEntry(
        marker = RailMarker.Dot,
        isFirst = isFirst,
        isLast = isLast,
        tint = MaterialTheme.colorScheme.primary,
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
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
) {
    if (text.isBlank()) return
    TranscriptEntry(marker = RailMarker.None, isFirst = isFirst, isLast = isLast) {
        if (streaming) {
            MarkdownBlock(content = text, modifier = Modifier.fillMaxWidth())
        } else {
            SelectionContainer {
                MarkdownBlock(content = text, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 折叠态最多显示几行；生成中显示尾部，文字就在这几行里向上流动 */
private const val THINKING_PREVIEW_LINES = 3
private const val THINKING_PREVIEW_CHARS = 240

/**
 * 思考。**默认折叠** —— 一轮思考动辄上千字，整段铺开会把工具调用和回答全挤出屏幕。
 *
 * 生成中显示**尾部** 3 行而不是头部：新 token 到达时文字在固定高度里向上滚，
 * 既看得出它在动，又不占地方。跑完只剩一行标题。
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
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Icon(
                if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded || streaming) {
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

/** 系统提示：会话建立、上下文压缩、API 报错、模型回退…… 一行，弱化 */
@Composable
internal fun NoteEntry(text: String, isError: Boolean, isFirst: Boolean, isLast: Boolean) {
    TranscriptEntry(
        marker = RailMarker.Dash,
        isFirst = isFirst,
        isLast = isLast,
        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
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
    TranscriptEntry(
        marker = when (item.status) {
            ClaudeCodeManager.ChatItem.ToolCall.Status.Running -> RailMarker.SquareFilled
            ClaudeCodeManager.ChatItem.ToolCall.Status.Done -> RailMarker.SquareOutline
            ClaudeCodeManager.ChatItem.ToolCall.Status.Error -> RailMarker.Cross
        },
        isFirst = isFirst,
        isLast = isLast,
        active = running,
        tint = when (item.status) {
            ClaudeCodeManager.ChatItem.ToolCall.Status.Error -> MaterialTheme.colorScheme.error
            ClaudeCodeManager.ChatItem.ToolCall.Status.Running -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        },
        modifier = Modifier.clickable { expanded = !expanded },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 这里**不放工具图标**：轨道标记已经编码了状态，右边的工具名又用文字说了一遍
            // 它是什么。一个 360dp 的行放不下四样东西，图标是其中信息量最低的那个，
            // 去掉它把宽度让给摘要——摘要才是"不展开也知道在干什么"的关键。
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = if (item.isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = toolSummary(item.name, item.input),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp)
            } else {
                toolBadge(item)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        if (expanded) ToolCallDetail(item, onRevert = onRevert)
    }
}
