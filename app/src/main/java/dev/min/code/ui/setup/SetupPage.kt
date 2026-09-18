package dev.min.code.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.settings.isInsecureBaseUrl
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkCheckbox
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkLineProgress
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.LoadingScreen
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.paperGrain
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.rememberAnimationsEnabled
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import org.koin.androidx.compose.koinViewModel

/**
 * 三步向导：连接 → Linux 环境 → Claude Code CLI。
 *
 * 三面共用一个骨架：`1 — 2 — 3` 进度 + 标题 + 一句话说明，长解释收进「详细说明」折叠区。
 * 每一步只有一个主按钮；进行中的下载/解压在按钮上方给墨线进度和一行状态。
 *
 * @param onReady 三步都完成时回调（宿主据此切到会话页）
 */
@Composable
fun SetupPage(
    onReady: () -> Unit,
    vm: SetupVM = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.ready) { if (state.ready) onReady() }

    when {
        // 判定"走到哪一步"要读磁盘和设置，那几百毫秒给整页的铺纸研墨，不给一个孤零零的转圈
        state.loading -> LoadingScreen(status = stringResource(R.string.setup_checking))

        state.step == SetupVM.Step.CONNECTION -> ConnectionStep(state, onSave = vm::saveConnection)
        state.step == SetupVM.Step.ROOTFS -> RootfsStep(state, onInstall = vm::installRootfs, onDismissError = vm::dismissError)
        state.step == SetupVM.Step.CLI -> CliStep(state, onInstall = vm::installCli, onDismissError = vm::dismissError)
        else -> Unit
    }
}

@Composable
private fun SetupStep(
    step: Int,
    title: String,
    summary: String,
    detail: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var showDetail by rememberSaveable(step) { mutableStateOf(false) }
    // 表单只保留纸纹，保证输入和状态信息有稳定的对比度。
    Box(
        Modifier
            .fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                // 平板/横屏上不拉满：一行 600dp 的说明文字没法读
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StepIndicator(step)
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (detail != null) {
                    InkTextButton(onClick = { showDetail = !showDetail }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(if (showDetail) R.string.setup_detail_hide else R.string.setup_detail_show))
                    }
                    AnimatedVisibility(visible = showDetail, enter = InkMotion.expand, exit = InkMotion.collapse) {
                        detail()
                    }
                }
                content()
            }
        }
    }
}

/**
 * `1 — 2 — 3`：三粒点、两段线。走过的点填金（人已经做完的事），当前的点是墨圈、
 * 圈心一粒湛在呼吸（这一步是活的），还没到的点只是一圈淡线。颜色变化都有过渡。
 */
@Composable
private fun StepIndicator(step: Int) {
    val palette = MaterialTheme.sea
    val scheme = MaterialTheme.colorScheme
    val animations = rememberAnimationsEnabled()
    // 脉冲值只在绘制阶段读：每帧重绘那粒点，不重组整行
    val pulse: State<Float> = if (animations) {
        rememberInfiniteTransition(label = "step").animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "stepPulse",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..3).forEach { i ->
            val done = i < step
            val current = i == step
            val fill by animateColorAsState(
                if (done) palette.sea else Color.Transparent,
                InkMotion.effect(),
                label = "fill",
            )
            val edge by animateColorAsState(
                when {
                    done -> palette.sea
                    current -> scheme.onSurface
                    else -> scheme.outlineVariant
                },
                InkMotion.effect(),
                label = "edge",
            )
            val azure = palette.sea
            Canvas(Modifier.size(9.dp)) {
                val r = 3.5.dp.toPx()
                drawCircle(fill, radius = r, center = center)
                drawCircle(edge, radius = r, center = center, style = Stroke(width = 1.5.dp.toPx()))
                if (current) drawCircle(azure.copy(alpha = pulse.value), radius = 1.6.dp.toPx(), center = center)
            }
            if (i < 3) {
                val line by animateColorAsState(
                    if (i < step) palette.sea else scheme.outlineVariant,
                    InkMotion.effect(),
                    label = "line",
                )
                Box(
                    Modifier
                        .padding(horizontal = 6.dp)
                        .size(width = 20.dp, height = 1.5.dp)
                        .background(line),
                )
            }
        }
    }
}

@Composable
private fun DetailCard(text: String) {
    Notice(text = text, tone = NoticeTone.Info)
}

/** 出错提示：出现 / 消失都洇开、收起，而不是整块跳出跳没 */
@Composable
private fun ErrorCard(error: String?, onDismiss: () -> Unit) {
    // 消失动画期间 error 已经是 null 了，留住最后一条文案让它能收完
    var last by remember { mutableStateOf(error) }
    if (error != null) last = error
    AnimatedVisibility(visible = error != null, enter = InkMotion.expand, exit = InkMotion.collapse) {
        Notice(
            text = last.orEmpty(),
            tone = NoticeTone.Error,
            maxLines = 12,
            action = {
                InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) }
            },
        )
    }
}

@Composable
private fun Progress(state: SetupVM.State) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InkLineProgress(progress = state.progress, modifier = Modifier.fillMaxWidth())
        Text(
            state.detail.ifBlank { stringResource(R.string.setup_working) },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
internal fun ConnectionStep(state: SetupVM.State, onSave: (String, String) -> Unit) {
    var token by rememberSaveable { mutableStateOf(state.settings.token) }
    var baseUrl by rememberSaveable { mutableStateOf(state.settings.baseUrl) }
    var visible by rememberSaveable { mutableStateOf(false) }
    // 本机回环（localhost / 127.x / 模拟器的 10.0.2.2）走 http 不出设备，不该报警
    val insecure = isInsecureBaseUrl(baseUrl)

    SetupStep(
        step = 1,
        title = stringResource(R.string.setup_step_connection),
        summary = stringResource(R.string.setup_connection_summary),
        detail = {
            DetailCard(
                stringResource(
                    R.string.setup_connection_detail,
                    ClaudeCodeManager.DEFAULT_PROMPT_CACHE_TTL,
                )
            )
        },
    ) {
        InkTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = "ANTHROPIC_BASE_URL",
            placeholder = "https://api.anthropic.com",
            singleLine = true,
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        AnimatedVisibility(visible = insecure, enter = InkMotion.expand, exit = InkMotion.collapse) {
            Notice(
                text = stringResource(R.string.setup_insecure_warning),
                // 「注意」档：这是风险提示，不是错误——填了照样能用。朱砂留给真正的判定
                tone = NoticeTone.Warn,
            )
        }
        InkTextField(
            value = token,
            onValueChange = { token = it },
            label = "ANTHROPIC_AUTH_TOKEN",
            singleLine = true,
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                InkIconButton(
                    icon = if (visible) HugeIcons.ViewOff else HugeIcons.View,
                    contentDescription = stringResource(if (visible) R.string.common_hide else R.string.common_show),
                    onClick = { visible = !visible },
                    size = 32.dp,
                    iconSize = 18.dp,
                )
            },
        )
        InkButton(
            onClick = { onSave(token.trim(), baseUrl.trim()) },
            enabled = token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.setup_save_continue)) }
    }
}

@Composable
private fun RootfsStep(state: SetupVM.State, onInstall: (String?) -> Unit, onDismissError: () -> Unit) {
    var customUrl by rememberSaveable { mutableStateOf("") }
    var showCustom by rememberSaveable { mutableStateOf(false) }
    val busy = state.busy == SetupVM.Step.ROOTFS

    SetupStep(
        step = 2,
        title = stringResource(R.string.setup_step_rootfs),
        summary = stringResource(R.string.setup_rootfs_summary),
        detail = { DetailCard(stringResource(R.string.setup_rootfs_detail)) },
    ) {
        AnimatedVisibility(visible = state.rootfsBroken && !busy, enter = InkMotion.expand, exit = InkMotion.collapse) {
            Notice(text = stringResource(R.string.setup_rootfs_broken), tone = NoticeTone.Error)
        }
        AnimatedVisibility(visible = busy, enter = InkMotion.expand, exit = InkMotion.collapse) {
            Progress(state)
        }
        ErrorCard(state.error, onDismissError)
        AnimatedVisibility(visible = showCustom, enter = InkMotion.expand, exit = InkMotion.collapse) {
            InkTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = stringResource(R.string.setup_rootfs_custom_label),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                monospace = true,
                enabled = !busy,
            )
        }
        InkButton(
            onClick = { onInstall(customUrl.trim().takeIf { showCustom && it.isNotBlank() }) },
            enabled = !busy,
            busy = busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (busy) R.string.setup_installing else R.string.setup_rootfs_install)) }
        InkTextButton(onClick = { showCustom = !showCustom }, enabled = !busy) {
            Text(stringResource(if (showCustom) R.string.setup_rootfs_use_default else R.string.setup_rootfs_use_custom))
        }
    }
}

@Composable
private fun CliStep(state: SetupVM.State, onInstall: (Boolean) -> Unit, onDismissError: () -> Unit) {
    var useNpmMirror by rememberSaveable { mutableStateOf(state.settings.useNpmMirror) }
    val busy = state.busy == SetupVM.Step.CLI

    SetupStep(
        step = 3,
        title = stringResource(R.string.setup_step_cli),
        summary = stringResource(R.string.setup_cli_summary),
        detail = { DetailCard(stringResource(R.string.setup_cli_detail)) },
    ) {
        AnimatedVisibility(visible = state.cliIncomplete && !busy, enter = InkMotion.expand, exit = InkMotion.collapse) {
            Notice(
                text = stringResource(R.string.setup_cli_incomplete),
                tone = NoticeTone.Error,
            )
        }
        AnimatedVisibility(visible = busy, enter = InkMotion.expand, exit = InkMotion.collapse) {
            Progress(state)
        }
        ErrorCard(state.error, onDismissError)
        // npm 源单独一个开关而不是跟着 Node 一起自动回退：Node 有官方校验和兜底，
        // claude-code 没有 —— 打开它等于同意从第三方镜像取 CLI 本体，必须是显式选择。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = !busy) { useNpmMirror = !useNpmMirror }
                .padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            // 整行可点，勾选框自己不接点击，否则一次触摸翻两遍
            InkCheckbox(checked = useNpmMirror, onCheckedChange = null, enabled = !busy)
            Column {
                Text(stringResource(R.string.setup_npm_mirror_title), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.setup_npm_mirror_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        InkButton(
            onClick = { onInstall(useNpmMirror) },
            enabled = !busy,
            busy = busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    when {
                        busy -> R.string.setup_installing
                        state.cliIncomplete -> R.string.setup_cli_repair
                        state.nodeInstalled -> R.string.setup_cli_install
                        else -> R.string.setup_cli_install_all
                    }
                )
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
