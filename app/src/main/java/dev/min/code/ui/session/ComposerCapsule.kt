package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.LocalFrost
import dev.min.code.ui.components.frostPane
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.MinKai
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Stop

/**
 * 输入胶囊：Claude 与 Codex 两个会话页共用的那一只（外观见 DESIGN.md「输入坞」）。
 *
 * 只管**长什么样**：胶囊、左端的 [plus] 槽、楷书正文与占位字、右端的中断 + 发送一组、
 * 胶囊以下让出系统导航的那一截。草稿放在哪、发送前要做什么（粘贴还原、斜杠命令拦截、
 * `!` 本地执行）都是调用方的事——两个引擎在这些地方本来就不一样，外观不该跟着分叉。
 *
 * @param enabled 会话能不能收字（Claude：进程在跑；Codex：进程在跑且已有 thread）
 * @param busy 这一轮还在生成：出中断键，发送键换成排队态，占位字换成「追加消息」
 * @param canSend 发送键醒不醒。只挂了附件也算有内容，所以由调用方判断
 */
@Composable
internal fun ComposerCapsule(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    busy: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    plus: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val palette = MaterialTheme.sea
    val fieldInteraction = remember { MutableInteractionSource() }
    val focused by fieldInteraction.collectIsFocusedAsState()
    ComposerDock(busy = busy, modifier = modifier.fillMaxWidth()) {
        // 输入框是一只胶囊：最亮的纸、一圈 hairline，聚焦时边变成海的深处；发送键住在胶囊右端
        val fieldShape = RoundedCornerShape(24.dp)
        val fieldBorder by animateColorAsState(
            if (focused && enabled) palette.seaDeep else MaterialTheme.colorScheme.outlineVariant,
            InkMotion.effect(),
            label = "fieldBorder",
        )
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            val frost = LocalFrost.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(fieldShape)
                    .then(
                        if (frost != null) {
                            Modifier.frostPane(frost)
                        } else {
                            Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        },
                    )
                    .border(1.dp, fieldBorder, fieldShape)
                    .padding(start = 3.dp, end = 3.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                plus()
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp, top = 4.dp, bottom = 4.dp)
                        .onFocusChanged { onFocusChange(it.isFocused) },
                    // 你写的话是人的声音：楷书
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = MinKai,
                    ),
                    cursorBrush = SolidColor(palette.seaDeep),
                    maxLines = 5,
                    enabled = enabled,
                    interactionSource = fieldInteraction,
                    decorationBox = { inner ->
                        val hint = composerHint(enabled = enabled, busy = busy)
                        // 最小高度放在这里而不是外层：字才会在胶囊里垂直居中，多行时再往上长
                        Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty() && hint.isNotEmpty()) {
                                Text(
                                    hint,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = MinKai),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                // 停止键（= Esc）跟发送键并排住在胶囊里。它没有跟着状态行收进「+」——
                // 埋进下拉菜单就要两次点击，而"停下"这件事必须一按就到。
                // 和发送分开而不是把发送变成停止：生成中照样可以追加消息，
                // 换成同一个键的话，想补一句反而把任务打断了。
                //
                // 这两个键自成一组（外层那 4 dp 只隔开输入区和这一组）：它们是同一件事的
                // 两个方向，贴在一起读起来才是一对，而不是两个零件。
                // 间距取负：两个键的盒子都比各自的图形大出一圈（32/16 与 44/15），
                // 留 0 也还有二十多 dp 的空白横在中间。让盒子叠 6 dp，图形之间才真的挨上；
                // 停止键仍有 26 dp 的触控面，比它 16 dp 的图形还宽。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy((-6).dp),
                ) {
                    AnimatedVisibility(
                        visible = busy,
                        enter = InkMotion.expand,
                        exit = InkMotion.collapse,
                    ) {
                        InkIconButton(
                            icon = HugeIcons.Stop,
                            contentDescription = stringResource(R.string.composer_interrupt),
                            onClick = onInterrupt,
                            tint = palette.vermilion,
                            size = 32.dp,
                            iconSize = 16.dp,
                        )
                    }
                    SeaSendKey(
                        enabled = canSend,
                        queued = busy,
                        onClick = onSend,
                    )
                }
            }
        }
        // 胶囊以下不再有任何一行。状态行（模型摘要 · 上下文 · 花费）整条收进了「+」——
        // 它是常驻的参考信息，却占着手机上最金贵的一条横向空间，还逼着三样东西互相挤。
        // 留下的这一截只让出系统导航的位置。
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Spacer(Modifier.height((navBottom / 2).coerceAtLeast(8.dp)))
    }
}

/**
 * 占位字。两个引擎同一句：「意欲何为」不是在问 Claude 还是问 Codex，是在问你。
 * busy 时换成「追加消息」——按下去的含义变了（排队，不是立即发），提示就得跟着变。
 */
@Composable
private fun composerHint(enabled: Boolean, busy: Boolean): String = when {
    !enabled -> stringResource(R.string.composer_hint_not_ready)
    busy -> stringResource(R.string.composer_hint_queue)
    else -> stringResource(R.string.composer_hint)
}

/** 胶囊上方那一条待发附件：横向滑，不换行——多挂几个也只占一行高 */
@Composable
internal fun ComposerChipStrip(
    visible: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = InkMotion.expand, exit = InkMotion.collapse) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** 斜杠命令候选的一行：命令名等宽，说明跟在后面、一行截断 */
@Composable
internal fun SlashSuggestionRow(
    command: String,
    description: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            command,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
        )
        description?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
