package dev.min.code.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.SeaWindow
import dev.min.code.ui.theme.pressScale
import dev.min.code.ui.theme.rememberAnimationsEnabled
import dev.min.code.ui.theme.sea
import dev.min.code.ui.theme.seaFill
import dev.min.code.ui.theme.seaInk
import kotlin.math.PI
import kotlin.math.sin

// ---------------------------------------------------------------------------
// 点 / 题跋 / 界线 / 水平线
// ---------------------------------------------------------------------------

/**
 * 一粒海：行内的小标记（计划横幅、审批标题前）。6dp 的一扇小圆窗。
 * 传 [color] 时改成平面色（朱砂用在出错处）。
 */
@Composable
fun Seal(modifier: Modifier = Modifier, size: Dp = 6.dp, color: Color = Color.Unspecified) {
    if (color == Color.Unspecified) {
        Box(modifier.size(size).seaFill(RoundedCornerShape(50)))
    } else {
        Box(modifier.size(size).background(color, RoundedCornerShape(50)))
    }
}

/**
 * 题跋：小标题 + 一道 hairline 到行尾。所有分区标题都是它。
 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    rule: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        InkDivider(Modifier.weight(1f), color = rule)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 界线：一根 hairline；`brush = true` 是两头淡出的海线（[HorizonLine]） */
@Composable
fun InkDivider(
    modifier: Modifier = Modifier,
    brush: Boolean = false,
    color: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    if (brush) {
        HorizonLine(modifier)
    } else {
        Box(modifier.fillMaxWidth().height(1.dp).background(color))
    }
}

/**
 * 水平线：一道海。两头淡出；[active] 时它是活的——一个低缓的波沿线走过去。
 * 顶栏下缘、输入坞上缘、题跋后面都是它：纸的每一条界都由海收口。
 */
@Composable
fun HorizonLine(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    thickness: Dp = 1.dp,
    fade: Boolean = true,
) {
    val wave = waveClock(active)
    val amp = 2.2.dp
    // 海染在笔画上，不染整块画布。seaInk 走的是 SrcIn，会把抗锯齿晕开的半透明像素
    // 也染成海；两端的淡出遮罩只盖住笔画中心，晕边就漏成一条纹理细线。
    val sea = MaterialTheme.sea.sea
    Canvas(
        modifier
            .fillMaxWidth()
            .height(if (active) amp * 2 + thickness else thickness),
    ) {
        val w = size.width
        val h = size.height
        val t = wave.value
        val stroke = thickness.toPx()
        val path = Path()
        val steps = if (active) 64 else 1
        val a = amp.toPx() * if (active) 1f else 0f
        for (i in 0..steps) {
            val x = w * i / steps
            // 一个只走一趟的鼓包：中心随 t 从左到右，两侧衰减
            val u = i / steps.toFloat()
            val env = (1f - ((u - t) * 5f).let { it * it }).coerceAtLeast(0f)
            val y = h / 2f - sin((u - t) * 5f * PI.toFloat()) * a * env
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val brush = if (fade) Brush.horizontalGradient(
            0f to Color.Transparent,
            0.12f to sea,
            0.88f to sea,
            1f to Color.Transparent,
        ) else Brush.horizontalGradient(listOf(sea, sea))
        drawPath(path, brush, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** 0→1 反复，2.6 秒一趟；不活跃或系统动画关掉时停在 −1（波在画面外） */
@Composable
private fun waveClock(active: Boolean): State<Float> {
    if (!active || !rememberAnimationsEnabled()) return remember { mutableFloatStateOf(-1f) }
    return rememberInfiniteTransition(label = "horizon").animateFloat(
        initialValue = -0.25f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "wave",
    )
}

/**
 * 品牌：顶栏与关于页用的那一笔手写 Min（[HandMin]）。
 * 桌面图标是两色对角线的 [SeaMark]；界面里不再用那一块方砖。
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    large: Boolean = false,
    reveal: Boolean = false,
    @Suppress("UNUSED_PARAMETER") color: Color = Color.Unspecified,
) {
    HandMin(
        modifier = modifier,
        height = if (large) 72.dp else 28.dp,
        // 顶栏那一笔会压在滚动的正文上，一圈实垫把它从字里托出来（浅色白、深色夜纸）
        halo = !large,
    )
}

// ---------------------------------------------------------------------------
// 容器
// ---------------------------------------------------------------------------

enum class PaperTone { Low, Mid, High }

/**
 * 纸上的一张纸条：浅一阶的底 + hairline。可点时微缩。没有海拔。
 */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    tone: PaperTone = PaperTone.Low,
    padding: PaddingValues = PaddingValues(14.dp),
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
    border: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(when (tone) {
        PaperTone.Low -> scheme.surfaceContainerLow
        PaperTone.Mid -> scheme.surfaceContainer
        PaperTone.High -> scheme.surfaceContainerHigh
    }, InkMotion.effect(), label = "paperTone")
    var m = modifier
    if (onClick != null || onLongClick != null) m = m.pressScale(interaction, 0.985f)
    m = m.clip(shape).background(bg)
    if (border) m = m.border(1.dp, scheme.outlineVariant, shape)
    if (onClick != null || onLongClick != null) {
        m = m.combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    }
    Column(m.padding(padding), content = content)
}

enum class NoticeTone { Info, Warn, Error }

/**
 * 提示条：左侧一道色边说明性质（石墨 = 说明，海 = 注意，朱 = 出错），正文小字。
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Info,
    maxLines: Int = Int.MAX_VALUE,
    action: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = MaterialTheme.sea
    val (bg, fg) = when (tone) {
        NoticeTone.Info -> scheme.surfaceContainer to scheme.onSurfaceVariant
        NoticeTone.Warn -> palette.seaWash to scheme.onSurface
        NoticeTone.Error -> scheme.errorContainer to scheme.onErrorContainer
    }
    Row(
        modifier
            .fillMaxWidth()
            .animateContentSize(InkMotion.spatial())
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .border(1.dp, if (tone == NoticeTone.Error) scheme.error.copy(alpha = 0.3f) else scheme.outlineVariant, MaterialTheme.shapes.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (tone) {
            NoticeTone.Warn -> Box(Modifier.width(3.dp).height(36.dp).seaFill())
            NoticeTone.Error -> Box(Modifier.width(3.dp).height(36.dp).background(scheme.error))
            NoticeTone.Info -> Box(Modifier.width(3.dp).height(36.dp).background(scheme.outlineVariant))
        }
        Row(
            Modifier.weight(1f).padding(start = 10.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (action != null) action()
        }
    }
}

/** 没有内容的一页：一句标题 + 一道水平线 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    intensity: Float = 0.45f,
) {
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(androidx.compose.ui.BiasAlignment(0f, -0.2f))
                .padding(horizontal = 32.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            HorizonLine(Modifier.width(56.dp), fade = false)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 顶栏 / 标签
// ---------------------------------------------------------------------------

/** 兼容旧调用：面板上的前景色。现在顶栏与抽屉都是纸，不再提供；[InkIconButton] 退回石墨 */
val LocalOnPanel = compositionLocalOf { Color.Unspecified }

/**
 * 纸色渐隐。会话页顶底那条（[frostVeil]）更长、更实，从铬件沿接到底色；
 * 这一份留给别的页面当没有那条带时的退路。
 */
@Composable
fun PaperVeil(
    fromTop: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    val paper = MaterialTheme.colorScheme.surface
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        colors = if (fromTop) {
                            listOf(paper, paper.copy(alpha = 0f))
                        } else {
                            listOf(paper.copy(alpha = 0f), paper)
                        },
                    ),
                )
            },
    )
}

/**
 * 浮在会话上的图标底：一只磨砂的圆，hairline。ChatGPT 顶栏左右那两粒白圆就是这个。
 * 有 [LocalFrost] 时采会话流做雾；没有就垫最亮的纸。没有海拔。
 */
@Composable
fun PaperDisc(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val frost = LocalFrost.current
    Box(
        modifier
            .clip(CircleShape)
            .then(
                if (frost != null) {
                    Modifier.frostPane(frost)
                } else {
                    Modifier.background(scheme.surfaceContainerLowest)
                },
            )
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/**
 * 顶栏：纸。标题是墨，副标题等宽石墨；没有分界线——纸和纸之间不需要线。
 * 品牌页（会话首页）用 [brand]，标题换成手绘的 Min。
 *
 * [overlay]：浮在会话流上。自己不再垫一块实色纸，状态栏以外那截留给 [frostVeil]，
 * 按钮走 [PaperDisc]。内容从下面滚过去，越靠近屏沿越被纸色盖住。
 */
@Composable
fun InkTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    brand: Boolean = false,
    scrolled: Boolean = false,
    /** 标题是机器产物（文件名、路径）时用等宽而不是楷书 */
    titleMono: Boolean = false,
    @Suppress("UNUSED_PARAMETER") active: Boolean = false,
    /** 浮在会话流上：透明底，窗口 inset 自己处理 */
    overlay: Boolean = false,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.then(if (overlay) Modifier else Modifier.background(scheme.surface))) {
        TopAppBar(
            windowInsets = if (overlay) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
            title = {
                if (brand) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BrandMark()
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetbrainsMono,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 3.dp),
                            )
                        }
                    }
                } else {
                    Column {
                        Text(
                            title,
                            style = if (titleMono) MaterialTheme.typography.bodyMedium.copy(fontFamily = JetbrainsMono)
                            else MaterialTheme.typography.titleMedium,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetbrainsMono,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                titleContentColor = scheme.onSurface,
                navigationIconContentColor = scheme.onSurfaceVariant,
                actionIconContentColor = scheme.onSurfaceVariant,
            ),
        )
    }
}

/**
 * 小 chip：纸底、hairline、海色的字。给顶栏 / 抽屉里的次级切换用。
 *
 * [leading] 换掉图标位：要放会动的记号（会话底栏「1 shell」前那粒呼吸的点）时用它。
 */
@Composable
fun PanelChip(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .pressScale(interaction, 0.96f)
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.surfaceContainer)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) {
            leading()
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp).seaInk(), tint = Color.Black)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 14.sp),
            color = MaterialTheme.sea.seaDeep,
            maxLines = 1,
        )
    }
}

data class InkTab(val icon: ImageVector, val label: String)

/**
 * 底部标签（文件页的"环境 / 文件"）。选中项变墨，下面一道海从中间长出来。
 */
@Composable
fun InkBottomTabs(
    tabs: List<InkTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerLow),
    ) {
        InkDivider()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup()) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = index == selected
                val color by animateColorAsState(if (isSelected) scheme.onSurface else scheme.onSurfaceVariant, InkMotion.effect(), label = "tab")
                val underline by animateFloatAsState(if (isSelected) 1f else 0f, InkMotion.spatial(), label = "underline")
                Column(
                    Modifier
                        .weight(1f)
                        .selectable(selected = isSelected, role = androidx.compose.ui.semantics.Role.Tab) { onSelect(index) }
                        .padding(top = 10.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(tab.icon, null, Modifier.size(20.dp), tint = color)
                    Text(tab.label, style = MaterialTheme.typography.labelSmall, color = color)
                    Box(Modifier.width(28.dp * underline).height(2.dp).seaFill(RoundedCornerShape(1.dp)))
                }
            }
        }
    }
}

/** 标签行下面那道指示线（给 TabRow 的 indicator 槽用） */
@Composable
fun InkTabIndicator(modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 8.dp).height(2.dp).fillMaxWidth().seaFill(RoundedCornerShape(1.dp)))
}

/**
 * 菜单项（放在 Material 的 DropdownMenu 里）。
 */
@Composable
fun InkMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier
            .fillMaxWidth()
            .widthIn(min = 160.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// sheet / 对话框
// ---------------------------------------------------------------------------

/** sheet 顶上的提手：一小段海 */
@Composable
fun InkDragHandle() {
    Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(32.dp, 3.dp).seaFill(RoundedCornerShape(2.dp), alpha = 0.85f))
}

/**
 * 底部面板。纸色的底、上圆角、海的提手。全 App 的 sheet 都从这里出。
 *
 * @param dismissible false = 必须按按钮才能关（审批、提问）：不响应返回键，提手也不给
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InkSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = MaterialTheme.sea.scrim,
        dragHandle = { if (dismissible) InkDragHandle() else Spacer(Modifier.height(16.dp)) },
        properties = ModalBottomSheetDefaults.properties(shouldDismissOnBackPress = dismissible),
        content = content,
    )
}

/**
 * 对话框：一张纸落到屏幕中央，标题，右下角按钮。
 */
@Composable
fun InkDialog(
    onDismissRequest: () -> Unit,
    title: String?,
    modifier: Modifier = Modifier,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            AnimatedVisibility(visible = shown, enter = InkMotion.enter, exit = InkMotion.exit) {
                Column(
                    modifier
                        .widthIn(min = 280.dp, max = 440.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (title != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Seal()
                            Text(title, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    // 内容带权重：Column 先量标题和按钮行，内容只拿剩下的高度。
                    // 不加的话，一段长内容（带 verticalScroll 的更新说明）会把高度吃光，
                    // 按钮行被挤成 0 高 —— 用户看到的是一个没有「下载并安装」的对话框
                    Column(
                        Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (dismissButton != null) dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}
