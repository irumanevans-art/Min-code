package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.min.code.R
import dev.min.code.core.claudecode.SubagentThread
import dev.min.code.core.claudecode.subagentTypeName
import dev.min.code.core.claudecode.switcherLabels
import dev.min.code.core.session.ChatItem
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.LiveDot
import dev.min.code.ui.components.PanelChipHeight
import dev.min.code.ui.components.Seal
import dev.min.code.ui.components.panelChipSurface
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.pressScale
import dev.min.code.ui.theme.sea
import dev.min.code.ui.theme.seaFill
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Menu03
import kotlin.math.roundToInt

/*
 * 子 agent 切换：对齐官方终端底部那一栏（有子 agent 时列出 `main` 和
 * `general-purpose  <它正在做什么>`，选中哪个上面就显示哪个的对话）。
 *
 * 这里只有外观；列谁（switcherThreads）、叫什么（switcherLabels）、它是什么状态（subagentThreads）
 * 是 core 里的纯函数，选中了哪个由会话页持有。
 */

/**
 * 打开某个子 agent 的对话（参数是发起它的 Agent 调用的 toolUseId）。null = 这一页不支持（Codex）。
 *
 * 用 CompositionLocal 而不是逐层传参：Agent 卡可能在主会话里，也可能嵌在另一个子 agent 的对话里，
 * 中间隔着 TranscriptItem、折叠块好几层，而每一层要传的都是同一件事（先例 [LocalAwaitingToolUseId]）。
 */
internal val LocalOpenSubagent = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * 任务条 leading 槽里的内容：一排墨字页签 `main  Explore  general-purpose 1 …`，选中的底下一道海。
 *
 * 不用 chip：胶囊正上方这一行要轻。2.1.27 是一排带底色带边框的块，压在输入框上太重，
 * 一只块再塞上「在干什么」，一屏只放得下一只半。页签只写名字，「在干什么」在点进去之后的视图顶上。
 *
 * 多了怎么办：页签横滑，还有没露出来的那头淡出，选中的自己滚进视野；到 [LIST_AT] 个或者一行放不下时，
 * 尾巴上多一个「全部 N」，点开 [AgentListSheet] 一眼列全（状态、任务、此刻在干什么）。
 *
 * 不自己占一行 —— 整条的出现和收起归 [SessionTaskStrip]（`showAgents` 由调用方按
 * [threads] 是否为空给）。
 *
 * @param selected 正在看的子 agent（toolUseId），null = main
 */
@Composable
internal fun RowScope.AgentSwitcher(
    threads: List<SubagentThread>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.agent_switcher_label)
    val forkName = stringResource(R.string.agent_type_fork)
    val labels = remember(threads, forkName) { switcherLabels(threads) { subagentTypeName(it, forkName) } }
    val scroll = rememberScrollState()
    var showList by rememberSaveable { mutableStateOf(false) }
    // 「全部 N」出现只会让页签更挤，不会反过来让它消失，所以不会来回闪
    val crowded by remember(threads.size) { derivedStateOf { threads.size >= LIST_AT || scroll.maxValue > 0 } }
    Row(modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        // 整组页签收在一只和右边「N shell」同款的纸底小胶囊里：往上翻时正文从这一行后面滑过
        // （底部的纸色是渐变，到胶囊上沿已经很淡），没有底的字会和正文糊在一起
        Row(
            Modifier
                .weight(1f, fill = false)
                .height(TAB_HEIGHT)
                .panelChipSurface()
                .semantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentTabs(threads, labels, selected, onSelect, scroll, Modifier.weight(1f, fill = false))
            AnimatedVisibility(
                visible = crowded,
                enter = fadeIn(InkMotion.effect()) + expandHorizontally(InkMotion.spatial()),
                exit = fadeOut(InkMotion.effectFast()) + shrinkHorizontally(InkMotion.spatial()),
            ) {
                AllAgentsButton(threads.size, onClick = { showList = true })
            }
        }
    }
    if (showList) {
        AgentListSheet(
            threads = threads,
            labels = labels,
            selected = selected,
            onSelect = {
                onSelect(it)
                showList = false
            },
            onDismiss = { showList = false },
        )
    }
}

/** 一个页签在页签行里的横向位置（px）：给那道海对齐、给自动滚动算视野 */
private data class TabSpan(val x: Float, val width: Float)

@Composable
private fun AgentTabs(
    threads: List<SubagentThread>,
    labels: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    val spans = remember { mutableStateMapOf<String, TabSpan>() }
    val target = spans[selected ?: MAIN_KEY]
    val lineX = remember { Animatable(0f) }
    val lineWidth = remember { Animatable(0f) }
    val inset = with(LocalDensity.current) { TAB_PADDING.toPx() }
    LaunchedEffect(target) {
        val span = target ?: return@LaunchedEffect
        val x = span.x + inset
        val width = (span.width - 2 * inset).coerceAtLeast(0f)
        if (lineWidth.value == 0f) {
            // 第一次量到：直接落位，不从最左边滑过来
            lineX.snapTo(x)
            lineWidth.snapTo(width)
        } else {
            launch { lineX.animateTo(x, InkMotion.spatial()) }
            launch { lineWidth.animateTo(width, InkMotion.spatial()) }
        }
        // 选中的滚进视野：从 Agent 卡上的「查看子 agent 对话」进来时，它可能正在条的屏外
        val viewport = scroll.viewportSize
        if (viewport > 0) {
            val from = scroll.value
            when {
                span.x < from -> scroll.animateScrollTo(span.x.roundToInt(), InkMotion.spatial())
                span.x + span.width > from + viewport ->
                    scroll.animateScrollTo((span.x + span.width - viewport).roundToInt(), InkMotion.spatial())
            }
        }
    }
    Box(
        modifier
            .fadingEdges(scroll)
            .horizontalScroll(scroll),
    ) {
        Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            AgentTab(
                key = MAIN_KEY,
                label = stringResource(R.string.agent_switcher_main),
                thread = null,
                selected = selected == null,
                onClick = { onSelect(null) },
                spans = spans,
            )
            threads.forEachIndexed { i, thread ->
                AgentTab(
                    key = thread.toolUseId,
                    label = labels[i],
                    thread = thread,
                    selected = thread.toolUseId == selected,
                    onClick = { onSelect(thread.toolUseId) },
                    spans = spans,
                )
            }
        }
        // 选中的那道海：跟着选中滑过去、伸缩成那个页签的字宽。位置和宽度只在布局阶段读，不引起重组
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = LINE_BOTTOM)
                .offset { IntOffset(lineX.value.roundToInt(), 0) }
                .layout { measurable, _ ->
                    val width = lineWidth.value.roundToInt().coerceAtLeast(0)
                    val placeable = measurable.measure(Constraints.fixed(width, LINE_HEIGHT.roundToPx()))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .seaFill(RoundedCornerShape(1.dp)),
        )
    }
}

/**
 * 一个页签：（子 agent 才有的）状态点 + 名字。选中是墨，未选是石墨；跑完的整只淡下去但留着
 * （本轮结束前还能点进去看）—— 活着靠点在呼吸，不靠另一种颜色（DESIGN.md「三种物质」）。
 */
@Composable
private fun AgentTab(
    key: String,
    label: String,
    thread: SubagentThread?,
    selected: Boolean,
    onClick: () -> Unit,
    spans: SnapshotStateMap<String, TabSpan>,
) {
    val scheme = MaterialTheme.colorScheme
    val fg by animateColorAsState(
        if (selected) scheme.onSurface else scheme.onSurfaceVariant,
        InkMotion.effect(),
        label = "agentTabFg",
    )
    val dim by animateFloatAsState(
        targetValue = if (thread == null || thread.running || selected) 1f else ENDED_ALPHA,
        animationSpec = InkMotion.effect(),
        label = "agentTabDim",
    )
    val interaction = remember { MutableInteractionSource() }
    DisposableEffect(key) { onDispose { spans.remove(key) } }
    Row(
        Modifier
            .onPlaced { coords ->
                val span = TabSpan(coords.positionInParent().x, coords.size.width.toFloat())
                if (spans[key] != span) spans[key] = span
            }
            .pressScale(interaction, 0.96f)
            .height(TAB_HEIGHT)
            .clip(RoundedCornerShape(6.dp))
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = TAB_PADDING)
            .alpha(dim),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (thread != null) AgentMark(thread, onSea = false, size = TAB_DOT)
        Text(
            label,
            style = TAB_TEXT,
            fontFamily = JetbrainsMono,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = LABEL_MAX_WIDTH),
        )
    }
}

/** 页签行尾巴上的「全部 N」：一道竖 hairline 隔开，石墨字，不抢页签的眼 */
@Composable
private fun AllAgentsButton(count: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(width = 0.5.dp, height = 10.dp)
                .background(scheme.outlineVariant.copy(alpha = 0.5f)),
        )
        Row(
            Modifier
                .pressScale(interaction, 0.96f)
                .height(TAB_HEIGHT)
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = TAB_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(HugeIcons.Menu03, contentDescription = null, modifier = Modifier.size(11.dp), tint = scheme.onSurfaceVariant)
            Text(
                stringResource(R.string.agent_switcher_all, count),
                style = TAB_TEXT,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 「全部 N」点开的列表：main + 每个子 agent 一行（名字 · 状态、任务描述、在跑的写它此刻在干什么）。
 * 选中的那行左边一道海，和用户气泡的海边同一个意思：「这是你正对着说话的那个」。点一行就切过去。
 */
@Composable
private fun AgentListSheet(
    threads: List<SubagentThread>,
    labels: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    InkSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Seal()
                Text(stringResource(R.string.agent_list_title, threads.size), style = MaterialTheme.typography.titleMedium)
            }
            Column {
                AgentListRow(
                    title = stringResource(R.string.agent_switcher_main),
                    status = null,
                    detail = stringResource(R.string.agent_list_main_detail),
                    activity = null,
                    selected = selected == null,
                    mark = null,
                    onClick = { onSelect(null) },
                )
                threads.forEachIndexed { i, thread ->
                    InkDivider()
                    AgentListRow(
                        title = labels[i],
                        status = subagentPhaseLabel(thread.phase),
                        failed = thread.phase == SubagentThread.Phase.Failed,
                        detail = thread.description,
                        activity = thread.activity?.takeIf { thread.running },
                        selected = thread.toolUseId == selected,
                        mark = thread,
                        onClick = { onSelect(thread.toolUseId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentListRow(
    title: String,
    status: String?,
    detail: String,
    activity: String?,
    selected: Boolean,
    mark: SubagentThread?,
    onClick: () -> Unit,
    failed: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val edge by animateFloatAsState(if (selected) 1f else 0f, InkMotion.effect(), label = "agentRowEdge")
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .seaFill(RoundedCornerShape(1.dp), alpha = edge),
        )
        // 点和第一行字对齐；main 没有状态，留同样宽的空位，名字对齐成一列
        Box(Modifier.padding(top = 6.dp).width(TAB_DOT)) {
            if (mark != null) AgentMark(mark, onSea = false, size = TAB_DOT)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetbrainsMono,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (status != null) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!activity.isNullOrBlank()) {
                Text(activity, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * 横滑那一排两端的淡出：哪头还有没露出来的页签，哪头就淡成纸 —— 告诉你「那边还有」，
 * 比一截被硬切掉的字体面。遮罩是 DstIn，所以要离屏合成一层。
 */
private fun Modifier.fadingEdges(scroll: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = FADE_WIDTH.toPx()
        if (scroll.canScrollBackward) {
            drawRect(
                brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = fade),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.canScrollForward) {
            drawRect(
                brush = Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - fade, endX = size.width),
                topLeft = Offset(size.width - fade, 0f),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** 状态记号：在跑是一粒呼吸的海点，出错是朱，其余（完成 / 停止）是一粒静止的石墨点 */
@Composable
private fun AgentMark(thread: SubagentThread, onSea: Boolean, size: Dp = 6.dp) {
    val palette = MaterialTheme.sea
    when (thread.phase) {
        SubagentThread.Phase.Running -> LiveDot(if (onSea) palette.onSea else palette.sea, size = size)
        SubagentThread.Phase.Failed -> Seal(size = size, color = palette.vermilion)
        else -> Seal(size = size, color = if (onSea) palette.onSea else palette.graphite)
    }
}

@Composable
internal fun subagentTypeLabel(type: String): String = subagentTypeName(type, stringResource(R.string.agent_type_fork))

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
            listOf(subagentTypeLabel(thread.agentType), thread.description, subagentPhaseLabel(thread.phase))
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
    val agent = subagentTypeLabel(handoff.agentType)
    val text = when (handoff.state) {
        ChatItem.HandoffState.Waiting -> stringResource(R.string.handoff_waiting, agent)
        ChatItem.HandoffState.Delivered -> stringResource(R.string.handoff_delivered, agent)
        ChatItem.HandoffState.Relayed -> stringResource(R.string.handoff_relayed, agent)
        ChatItem.HandoffState.Undelivered -> stringResource(R.string.handoff_undelivered, agent)
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

/** 跑完的页签淡到多少：看得出「已经结束」，又还读得清字 */
private const val ENDED_ALPHA = 0.55f

/** 几个子 agent 起给「全部 N」：三个以上横滑找人已经不如一眼列全 */
private const val LIST_AT = 3

/** 页签字宽上限：「general-purpose 2」放得下，更长的自定义 agent 名截断，不把一行吃掉 */
private val LABEL_MAX_WIDTH = 132.dp

/** 页签高：和右边「N shell」那只标记（PanelChip）齐平 */
private val TAB_HEIGHT = PanelChipHeight
private val TAB_PADDING = 8.dp
private val TAB_TEXT @Composable get() = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp)
private val TAB_DOT = 4.dp
private val LINE_HEIGHT = 1.5.dp
private val LINE_BOTTOM = 2.dp
private val FADE_WIDTH = 20.dp

/** main 在页签位置表里的键（子 agent 用的是 toolUseId，不会撞上） */
private const val MAIN_KEY = "\u0000main"
