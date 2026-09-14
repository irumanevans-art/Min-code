package dev.min.code.ui.session

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.setValue
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Attachment01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Settings02
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkMenuItem
import dev.min.code.ui.components.InkRingProgress
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.pressScale
import dev.min.code.ui.theme.sea
import dev.min.code.ui.theme.seaInk

/**
 * 输入坞：和会话流同一张纸，没有分界线——胶囊本身就是边界。
 */
@Composable
internal fun ComposerDock(
    @Suppress("UNUSED_PARAMETER") busy: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Column(modifier) { content() }
}

/**
 * 胶囊左端的「+」：附件、用量、会话设置全收在这里，胶囊外面不再有任何一行。
 *
 * 以前胶囊下面挂着一条状态行（模型摘要 · 上下文 · 花费）。那一行是**常驻的参考信息**，
 * 却占着手机上最金贵的一条横向空间，还逼得三样东西互相挤（停止键就是这么被挤没的）。
 * 它们的共同点是"想起来才看一眼"，正好是菜单该装的东西。
 *
 * 上下文与花费一栏、会话设置一栏，排在附件那三项前面 —— 开菜单的人多半是来看数的。
 *
 * **停止键没有收进来**：它是 Esc，得一按就停，埋进下拉菜单等于要两次点击。它住在胶囊里。
 */
@Composable
internal fun ComposerPlus(
    enabled: Boolean,
    busy: Boolean,
    canPickImage: Boolean,
    onPickFile: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    /** 上下文占用比例，null = CLI 还没回报 */
    contextRatio: Float? = null,
    /** `880k/1M`。null 时这一栏只显示花费 */
    contextText: String? = null,
    /** 逼近上限：整圈转朱砂 */
    contextWarn: Boolean = false,
    /** `今日 $1.34 · 本次 $0.6500` */
    costText: String? = null,
    /** 当前模型，会话设置那一栏的标题 */
    modelText: String = "",
    /** `权限模式 · 思考强度` */
    modeText: String = "",
    onRefreshUsage: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    Box {
        InkIconButton(
            icon = HugeIcons.PlusSign,
            contentDescription = "附件、用量与会话设置",
            onClick = { open = true },
            enabled = enabled,
            busy = busy,
            size = 38.dp,
            iconSize = 20.dp,
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            if (contextText != null || costText != null) {
                MenuStatusRow(
                    title = contextText ?: "用量",
                    subtitle = costText,
                    titleColor = if (contextWarn) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    onClick = onRefreshUsage,
                    leading = {
                        InkRingProgress(
                            progress = contextRatio,
                            size = 18.dp,
                            color = if (contextWarn) MaterialTheme.sea.vermilion else Color.Unspecified,
                        )
                    },
                )
            }
            if (modelText.isNotBlank()) {
                MenuStatusRow(
                    title = modelText,
                    subtitle = modeText.takeIf { it.isNotBlank() },
                    onClick = { open = false; onOpenSettings() },
                    leading = {
                        Icon(
                            HugeIcons.Settings02,
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            if (contextText != null || costText != null || modelText.isNotBlank()) InkDivider()
            InkMenuItem("添加文件", icon = HugeIcons.Attachment01, onClick = { open = false; onPickFile() })
            InkMenuItem(
                "拍照",
                icon = HugeIcons.Camera01,
                tint = if (canPickImage) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                onClick = { if (canPickImage) { open = false; onTakePhoto() } },
            )
            InkMenuItem(
                "添加图片",
                icon = HugeIcons.Image02,
                tint = if (canPickImage) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                onClick = { if (canPickImage) { open = false; onPickImage() } },
            )
        }
    }
}

/**
 * 菜单里的一栏状态：左边一个记号，右边两行字。
 *
 * 和 [dev.min.code.ui.components.InkMenuItem] 分开是因为这两栏要**两行**和一个自定义记号
 * （潮环），而菜单项那套是单行 + ImageVector。数字用等宽：它们是要对着读的。
 */
@Composable
private fun MenuStatusRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .widthIn(min = 180.dp, max = 300.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = JetbrainsMono,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 发送键：一个朝上的「>」，海做的。
 *
 * - 没东西可发时它是纸上的一道石墨细线（在，但没醒）；
 * - 有内容可发（[enabled]）就醒：线变粗、涨成海，从下方 6dp 浮上来；这扇窗里的海是全 App 唯一在流的海；
 * - 按下去缩一点，松手弹回。
 */
@Composable
internal fun SeaSendKey(
    enabled: Boolean,
    queued: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val description = if (queued) "排队发送" else "发送"
    val awake by animateFloatAsState(if (enabled) 1f else 0f, InkMotion.spatial(), label = "sendAwake")
    val idle = MaterialTheme.colorScheme.outline
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(50))
            .pressScale(interaction, pressedScale = 0.88f)
            .semantics { contentDescription = description }
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 睡着的线：石墨，随醒来淡出
        Chevron(Modifier.size(44.dp).graphicsLayer { alpha = 1f - awake }, color = idle, width = 1.6.dp)
        // 醒着的海：从下方浮上来
        Chevron(
            Modifier
                .size(44.dp)
                .graphicsLayer {
                    alpha = awake
                    translationY = (1f - awake) * 6.dp.toPx()
                }
                .seaInk(flow = true),
            color = Color.Black,
            width = 2.8.dp,
        )
    }
}

/** 一个朝上的「>」：三点两线，圆头 */
@Composable
private fun Chevron(modifier: Modifier, color: Color, width: androidx.compose.ui.unit.Dp) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val half = 7.5.dp.toPx()
        val rise = 4.5.dp.toPx()
        val path = Path().apply {
            moveTo(c.x - half, c.y + rise)
            lineTo(c.x, c.y - rise)
            lineTo(c.x + half, c.y + rise)
        }
        drawPath(path, color, style = Stroke(width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
