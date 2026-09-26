package dev.min.code.ui.session

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.min.code.R
import dev.min.code.core.claudecode.BackgroundShell
import dev.min.code.core.claudecode.OUTPUT_TAIL_LINES
import dev.min.code.core.claudecode.OutputTail
import dev.min.code.ui.components.LiveDot
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.LocalToaster
import dev.min.code.ui.components.Seal
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.components.ToastType
import dev.min.code.ui.richtext.HighlightCodeBlock
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01

/**
 * 点开底栏「N shell」：官方终端的 Shell details。
 *
 * 只有一个时直接进详情；多个时先列表（命令一行 + 状态 + 运行时长），点进详情。
 * 详情：状态、运行时长（在跑时每秒走）、跑在哪（CLI / Min 进程表 / 两边）、命令全文、输出尾部、停止。
 *
 * 看着看着它结束了、甚至从表里消失了（一轮结束时 CLI 的已完成任务会被清掉），详情停在最后一眼，
 * 不跳回列表 —— 用户正在读它的输出。
 *
 * @param readOutput 读一次输出尾巴（IO 在调用方）；在跑时每 [OUTPUT_REFRESH_MS] 读一次
 * @param onStop 停掉它，失败返回给用户看的那句话
 */
@Composable
internal fun BackgroundShellSheet(
    shells: List<BackgroundShell>,
    onDismiss: () -> Unit,
    readOutput: suspend (BackgroundShell) -> OutputTail?,
    onStop: suspend (BackgroundShell) -> String?,
) {
    // 只有一个时直接进详情，和终端一样；返回列表的箭头只在有列表可回时给
    var selectedKey by rememberSaveable { mutableStateOf(shells.singleOrNull()?.key) }
    val lastSeen = remember { mutableStateOf<BackgroundShell?>(null) }
    val selected = selectedKey?.let { key -> shells.firstOrNull { it.key == key } ?: lastSeen.value?.takeIf { it.key == key } }
    if (selected != null) lastSeen.value = selected

    InkSheet(onDismissRequest = onDismiss) {
        AnimatedContent(
            targetState = selected,
            contentKey = { it?.key },
            transitionSpec = { InkMotion.enter togetherWith InkMotion.exit },
            label = "shellSheet",
        ) { shell ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (shell == null) {
                    ShellList(shells, onOpen = { selectedKey = it.key })
                } else {
                    ShellDetail(
                        shell = shell,
                        onBack = if (shells.any { it.key != shell.key }) {
                            { selectedKey = null }
                        } else {
                            null
                        },
                        readOutput = readOutput,
                        onStop = onStop,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String, onBack: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) {
            InkIconButton(
                icon = HugeIcons.ArrowLeft01,
                contentDescription = stringResource(R.string.shell_back),
                onClick = onBack,
                size = 32.dp,
                iconSize = 18.dp,
            )
        } else {
            Seal()
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ShellList(shells: List<BackgroundShell>, onOpen: (BackgroundShell) -> Unit) {
    SheetTitle(stringResource(R.string.shell_sheet_title))
    Column {
        shells.forEachIndexed { i, shell ->
            if (i > 0) InkDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(shell) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StateMark(shell)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        shell.command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetbrainsMono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(stateLabel(shell), runtimeLabel(shell)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                        color = stateColor(shell),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShellDetail(
    shell: BackgroundShell,
    onBack: (() -> Unit)?,
    readOutput: suspend (BackgroundShell) -> OutputTail?,
    onStop: suspend (BackgroundShell) -> String?,
) {
    SheetTitle(stringResource(R.string.shell_details_title), onBack)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Field(stringResource(R.string.shell_status)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StateMark(shell)
                Text(
                    stateLabel(shell),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetbrainsMono,
                    color = stateColor(shell),
                )
            }
        }
        runtimeLabel(shell)?.let { runtime ->
            Field(stringResource(R.string.shell_runtime)) { FieldText(runtime) }
        }
        Field(stringResource(R.string.shell_source)) {
            FieldText(
                listOfNotNull(
                    sourceLabel(shell),
                    stringResource(R.string.shell_monitor).takeIf { shell.monitor },
                ).joinToString(" · "),
            )
        }
    }

    SectionTitle(stringResource(R.string.shell_command))
    HighlightCodeBlock(code = shell.command, language = "bash")

    SectionTitle(stringResource(R.string.shell_output))
    OutputPane(shell, readOutput)

    if (shell.isRunning) {
        val scope = rememberCoroutineScope()
        val toaster = LocalToaster.current
        var stopping by remember(shell.key) { mutableStateOf(false) }
        InkButton(
            onClick = {
                stopping = true
                scope.launch {
                    val failure = onStop(shell)
                    stopping = false
                    failure?.let { toaster.show(it, ToastType.Error) }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            tone = InkButtonTone.Vermilion,
            busy = stopping,
        ) {
            Text(stringResource(R.string.shell_stop))
        }
    }
}

/** 左边一列小标签、右边内容。标签宽度固定，几行的值才对得齐 */
@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        content()
    }
}

@Composable
private fun FieldText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = JetbrainsMono)
}

/**
 * 输出尾巴：等宽、不折行、可横滑；贴底时跟着新输出往下走，往上翻了就不动。
 * 在跑时每 [OUTPUT_REFRESH_MS] 读一次，结束后再读最后一次就停。
 */
@Composable
private fun OutputPane(shell: BackgroundShell, readOutput: suspend (BackgroundShell) -> OutputTail?) {
    val current by rememberUpdatedState(shell)
    val tail by produceState<OutputTail?>(null, shell.key, shell.isRunning) {
        while (true) {
            value = readOutput(current)
            if (!current.isRunning) break
            delay(OUTPUT_REFRESH_MS)
        }
    }
    val text = tail?.text.orEmpty()
    if (text.isEmpty()) {
        Text(
            stringResource(R.string.shell_no_output),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (tail?.clipped == true) {
        Text(
            stringResource(R.string.shell_output_clipped, OUTPUT_TAIL_LINES),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    var follow by remember(shell.key) { mutableStateOf(true) }
    // 手指拖动时才重判「是不是停在底部」：内容自己长高时 value < maxValue 是常态，不代表人往上翻了
    LaunchedEffect(vertical) {
        snapshotFlow { vertical.value to vertical.isScrollInProgress }.collect { (value, dragging) ->
            if (dragging) follow = value >= vertical.maxValue - FOLLOW_SLACK_PX
        }
    }
    LaunchedEffect(vertical) {
        snapshotFlow { vertical.maxValue }.collect { max -> if (follow) vertical.scrollTo(max) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        SelectionContainer {
            Text(
                text,
                fontFamily = JetbrainsMono,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                softWrap = false,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .verticalScroll(vertical)
                    .horizontalScroll(horizontal)
                    .padding(10.dp),
            )
        }
    }
}

/** 状态记号：在跑是呼吸的海点，结束了是一粒静止的点（失败是朱） */
@Composable
private fun StateMark(shell: BackgroundShell) {
    if (shell.isRunning) {
        LiveDot(MaterialTheme.sea.sea)
    } else {
        Seal(color = stateColor(shell))
    }
}

@Composable
private fun stateColor(shell: BackgroundShell): Color = when (shell.state) {
    BackgroundShell.State.Running -> MaterialTheme.sea.seaDeep
    BackgroundShell.State.Failed -> MaterialTheme.sea.vermilion
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun stateLabel(shell: BackgroundShell): String {
    val base = when (shell.state) {
        BackgroundShell.State.Running -> stringResource(R.string.shell_state_running)
        BackgroundShell.State.Completed -> stringResource(R.string.shell_state_completed)
        BackgroundShell.State.Failed -> stringResource(R.string.shell_state_failed)
        BackgroundShell.State.Stopped -> stringResource(R.string.shell_state_stopped)
    }
    // 被停掉的不写退出码：那是信号杀出来的数（进程表里常记成 0），不是它自己的结局
    val exit = shell.exitCode
        ?.takeIf { shell.state == BackgroundShell.State.Completed || shell.state == BackgroundShell.State.Failed }
        ?.let { stringResource(R.string.shell_exit_code, it) }
    return listOfNotNull(base, exit).joinToString(" · ")
}

@Composable
private fun sourceLabel(shell: BackgroundShell): String = when {
    shell.cliTaskId != null && shell.hostedServiceId != null -> stringResource(R.string.shell_source_both)
    shell.cliTaskId != null -> stringResource(R.string.shell_source_cli)
    else -> stringResource(R.string.shell_source_hosted)
}

/**
 * 运行时长：在跑时从 [BackgroundShell.startedAt] 每秒走；结束了定格在结束那一刻，
 * 结束时刻不可知（托管服务）就不写 —— 宁缺，不写一个还在涨的假数。
 */
@Composable
private fun runtimeLabel(shell: BackgroundShell): String? {
    if (shell.startedAt <= 0L) return null
    val now by produceState(System.currentTimeMillis(), shell.key, shell.isRunning) {
        while (shell.isRunning) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val end = if (shell.isRunning) now else shell.endedAt ?: return null
    return formatDuration(end - shell.startedAt)
}

/** 在跑时多久重读一次输出：1.5 秒够看出它在动，又不至于每秒都去读一个可能几 MB 的文件的尾巴 */
private const val OUTPUT_REFRESH_MS = 1_500L

/** 离底部多近算「贴底」 */
private const val FOLLOW_SLACK_PX = 24
