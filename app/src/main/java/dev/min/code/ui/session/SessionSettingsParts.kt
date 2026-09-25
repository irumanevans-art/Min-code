package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.service.ClaudeCodeForegroundService
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkSpinner
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.Seal
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01

/*
 * 会话设置面板的外观件：Claude（ClaudeCodeSettingsSheet）和 Codex（CodexSettingsSheet）两张面板共用这一份。
 * 两个引擎的设置项不一样，但「一方印的标题、忙碌横幅、每行带当前值的手风琴、选中项的海边、底下的保活状态」
 * 是同一种界面——只写在这里，别在哪一边再抄一份。
 */

/**
 * 整张面板的框：`InkSheet` + 一粒 `Seal` 的标题 + 忙碌横幅 + 内容 + 底下固定的保活状态。
 *
 * [busy] 时压一条横幅：选项变灰不够，sheet 开着时用户盯着一排死控件，以为没点上。
 * [busyLabel] 和 [busy] 分开传，是为了横幅收起的那一下里文案还是调用方算出来的那句，不闪成别的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionSettingsFrame(
    title: String,
    busy: Boolean,
    busyLabel: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    InkSheet(
        onDismissRequest = onDismiss,
        // 设置面板一开就该给足高度：半展开状态下手风琴一展开就顶到底，
        // 又会退化成"下半截够不着"的老毛病。把 PartiallyExpanded 从允许状态里去掉
        // 就是旧 API 的 skipPartiallyExpanded=true。
        sheetState = rememberBottomSheetState(
            SheetValue.Hidden,
            setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            SheetTitle(title)
            AnimatedVisibility(
                visible = busy,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                SettingsBusyBanner(busyLabel)
            }
            content()
            KeepAliveStatus()
        }
    }
}

/** sheet 的标题：一方印 + 楷书 */
@Composable
private fun SheetTitle(text: String) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Seal()
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsBusyBanner(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InkSpinner(size = 14.dp, color = MaterialTheme.sea.sea)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.sea.sea,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 后台保活是否真的生效。Claude 和 Codex 的会话由同一个前台服务撑着，所以两张面板底下都挂这一行。
 *
 * 放在设置面板最底下、不占一个分区 —— 平时不需要看，但它失效时的现象
 * （切出去回来任务断了、一串连接中断）和网络问题长得一模一样，
 * 没有这一行就只能靠猜。ColorOS / realme UI 这类 ROM 会在系统侧直接拒发 FGS 类型权限。
 */
@Composable
private fun KeepAliveStatus() {
    val state by ClaudeCodeForegroundService.keepAlive.collectAsStateWithLifecycle()
    val (textRes, isError) = when (state) {
        ClaudeCodeForegroundService.KeepAlive.Active ->
            R.string.session_keepalive_active to false

        ClaudeCodeForegroundService.KeepAlive.Rejected ->
            R.string.session_keepalive_rejected to true

        ClaudeCodeForegroundService.KeepAlive.Expired ->
            R.string.session_keepalive_expired to true

        ClaudeCodeForegroundService.KeepAlive.Stopped ->
            R.string.session_keepalive_none to false
    }
    Notice(
        text = stringResource(textRes),
        tone = if (isError) NoticeTone.Error else NoticeTone.Info,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * 一个可折叠分区：标题行常驻并显示当前值，点开才展开内容；箭头随之翻转。
 *
 * [section] 是调用方自己的分区枚举（两个引擎的分区不一样），[open] 为 null = 全部折叠。
 */
@Composable
internal fun <S : Any> AccordionSection(
    section: S,
    label: String,
    value: String,
    open: S?,
    onToggle: (S?) -> Unit,
    content: @Composable () -> Unit,
) {
    val expanded = open == section
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, InkMotion.spatial(), label = "chevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(if (expanded) null else section) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            HugeIcons.ArrowDown01,
            contentDescription = null,
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer { rotationZ = rotation },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    AnimatedVisibility(visible = expanded, enter = InkMotion.expand, exit = InkMotion.collapse) {
        Column { content() }
    }
    InkDivider()
}

/** 一张输入纸条 + 右侧的「应用」。输入的是模型名 / 路径，等宽 */
@Composable
internal fun SettingsInlineInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean = true,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InkTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            label = label,
            placeholder = placeholder,
            singleLine = true,
            monospace = true,
            enabled = enabled,
        )
        InkTextButton(
            onClick = onApply,
            enabled = enabled && value.isNotBlank(),
            modifier = Modifier.padding(bottom = 4.dp),
        ) { Text(stringResource(R.string.common_apply)) }
    }
}

/** 分区里的一行小字：说明、作用域、回读到的真实值 */
@Composable
internal fun SettingsSubHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * 一个可选项。选中的那一行是金的水洗底 + 左边一道金边：这是"你选的"。
 *
 * 说明超出 [subtitleMaxLines] 时给一个「展开全文」——之前是直接截成省略号，
 * 而技能那类说明动辄 500 字（dataviz 那条整段都是触发条件），**省略号后面的内容
 * 在整个 App 里没有任何地方能看到**，等于白写。截断本身是对的：列表要像目录；
 * 缺的只是一个出口。
 */
@Composable
internal fun OptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    monospaceTitle: Boolean = false,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
) {
    // 按 subtitle 取 key：切换中英文时说明整段换掉，展开状态和"有没有截断"都要重算
    var expanded by remember(subtitle) { mutableStateOf(false) }
    var truncated by remember(subtitle) { mutableStateOf(false) }
    val palette = MaterialTheme.sea
    val background by animateColorAsState(
        if (selected) palette.seaWash else Color.Transparent,
        InkMotion.effect(),
        label = "optionBackground",
    )
    val edge by animateFloatAsState(if (selected) 1f else 0f, InkMotion.spatial(), label = "optionEdge")
    val gold = palette.sea
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .background(background)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .drawBehind {
                // 金边从中间长出来，不是整条突然出现
                if (edge > 0f) {
                    val x = 1.dp.toPx()
                    val half = (size.height / 2f - size.height * 0.22f) * edge
                    val cy = size.height / 2f
                    drawLine(
                        color = gold,
                        start = Offset(x, cy - half),
                        end = Offset(x, cy + half),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospaceTitle) JetbrainsMono else null,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    // 只在折叠态记录溢出，且只在值真的变了时写 —— 在 onTextLayout 里
                    // 无条件赋值会让"测量→写状态→重组→再测量"转成死循环
                    onTextLayout = { result ->
                        if (!expanded && result.hasVisualOverflow != truncated) {
                            truncated = result.hasVisualOverflow
                        }
                    },
                )
                if (truncated || expanded) {
                    Text(
                        text = stringResource(if (expanded) R.string.session_collapse else R.string.session_expand_full),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.seaDeep,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            // 自己吃掉点击，否则点"展开全文"会被外层当成选中这一项
                            .clickable { expanded = !expanded },
                    )
                }
            }
        }
    }
}
