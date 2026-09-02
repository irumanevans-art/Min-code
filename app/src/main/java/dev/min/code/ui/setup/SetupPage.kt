package dev.min.code.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.ui.theme.JetbrainsMono
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import org.koin.androidx.compose.koinViewModel

/**
 * 三步向导：连接 → Linux 环境 → Claude Code CLI。
 *
 * 三面共用一个骨架：`1 — 2 — 3` 进度 + 标题 + 一句话说明，长解释收进「详细说明」折叠区。
 * 每一步只有一个主按钮；进行中的下载/解压在按钮上方给进度条和一行状态。
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
        state.loading -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }

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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..3).forEach { i ->
                    Text(
                        text = "$i",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                        color = when {
                            i < step -> MaterialTheme.colorScheme.primary
                            i == step -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                    )
                    if (i < 3) {
                        HorizontalDivider(
                            modifier = Modifier.size(width = 20.dp, height = 1.dp),
                            color = if (i < step) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (detail != null) {
                TextButton(onClick = { showDetail = !showDetail }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(if (showDetail) "收起详细说明" else "详细说明", style = MaterialTheme.typography.labelMedium)
                }
                if (showDetail) detail()
            }
            content()
        }
    }
}

@Composable
private fun DetailCard(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(text: String, onDismiss: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onDismiss, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("知道了", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Progress(state: SetupVM.State) {
    val progress = state.progress
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Text(state.detail.ifBlank { "正在处理…" }, style = MaterialTheme.typography.labelSmall, fontFamily = JetbrainsMono)
}

// ---------------------------------------------------------------------------

@Composable
private fun ConnectionStep(state: SetupVM.State, onSave: (String, String) -> Unit) {
    var token by rememberSaveable { mutableStateOf(state.settings.token) }
    var baseUrl by rememberSaveable { mutableStateOf(state.settings.baseUrl) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val insecure = baseUrl.trim().startsWith("http://", ignoreCase = true)

    SetupStep(
        step = 1,
        title = "连接",
        summary = "Claude Code 需要一个 API token 和接口地址。直连 Anthropic 填官方地址；" +
            "用中转站就填中转站给的地址。两项都可以之后在设置里改。",
        detail = {
            DetailCard(
                "对应环境变量 ANTHROPIC_AUTH_TOKEN / ANTHROPIC_BASE_URL，以环境变量注入 Rootfs 内的 " +
                    "claude 进程，只有沙箱内的 Claude Code 能读到。\n\n" +
                    "会话固定注入：CLAUDE_CODE_PROMPT_CACHE_TTL=${ClaudeCodeManager.DEFAULT_PROMPT_CACHE_TTL}" +
                    "（走 API token 时默认只有 5 分钟，断续使用会反复错过缓存窗口）、" +
                    "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1、DISABLE_AUTOUPDATER=1、IS_SANDBOX=1。"
            )
        },
    ) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("ANTHROPIC_BASE_URL") },
            placeholder = { Text("https://api.anthropic.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (insecure) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "这是明文 HTTP 地址：token 和全部对话内容都会以明文经过公网，任何中间节点都能看到。" +
                        "能用 HTTPS 就换成 HTTPS。",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("ANTHROPIC_AUTH_TOKEN") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = if (visible) "隐藏" else "显示")
                }
            },
        )
        Button(
            onClick = { onSave(token.trim(), baseUrl.trim()) },
            enabled = token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存并继续") }
    }
}

@Composable
private fun RootfsStep(state: SetupVM.State, onInstall: (String?) -> Unit, onDismissError: () -> Unit) {
    var customUrl by rememberSaveable { mutableStateOf("") }
    var showCustom by rememberSaveable { mutableStateOf(false) }
    val busy = state.busy == SetupVM.Step.ROOTFS

    SetupStep(
        step = 2,
        title = "Linux 环境",
        summary = "官方 CLI 运行在 App 内置的 Ubuntu（proot）里。首次使用要下载约 30 MB 的基础镜像，" +
            "官方源连不上会自动换镜像。",
        detail = {
            DetailCard(
                "这是一个完整的 Ubuntu 24.04 用户态，Claude Code 可以自己 apt install git / python3 / " +
                    "ripgrep 等工具。装了就一直在 —— 它是 App 数据目录里的真实文件系统，不是一次性容器，" +
                    "重启 App、重开会话都不会丢。代价是每装一个都占空间，可以在文件页查看和清理。\n\n" +
                    "proot 没有 root 权限，靠 ptrace 拦截系统调用来「假装」有一个根文件系统，" +
                    "所以进程密集的任务会比真机 Linux 慢一些，但 CPU 密集的计算（numpy/BLAS）是原生速度。"
            )
        },
    ) {
        if (state.rootfsBroken && !busy) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "上次安装没有完成（下载或解压中断），需要重新安装。",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (busy) Progress(state)
        state.error?.let { ErrorCard(it, onDismissError) }
        if (showCustom) {
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = { Text("自定义 rootfs 地址（tar.gz / tar.xz）") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                enabled = !busy,
            )
        }
        Button(
            onClick = { onInstall(customUrl.trim().takeIf { showCustom && it.isNotBlank() }) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "正在安装…" else "下载并安装 Ubuntu") }
        TextButton(onClick = { showCustom = !showCustom }, enabled = !busy) {
            Text(if (showCustom) "用默认地址" else "用自定义地址", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CliStep(state: SetupVM.State, onInstall: (Boolean) -> Unit, onDismissError: () -> Unit) {
    var useNpmMirror by rememberSaveable { mutableStateOf(state.settings.useNpmMirror) }
    val busy = state.busy == SetupVM.Step.CLI

    SetupStep(
        step = 3,
        title = "Claude Code CLI",
        summary = "在 Ubuntu 里安装 Node.js 22 与官方 @anthropic-ai/claude-code，装完约占 600 MB。" +
            "npm 只负责几 KB 的 wrapper，200 MB 的原生二进制由 App 自己下载（断点续传、官方校验和）。",
        detail = {
            DetailCard(
                "Node.js 优先从官方源下载，连不上才回退镜像；无论从哪下，都拿写死在 App 里的官方 SHA-256 " +
                    "校验，对不上直接删档重来。原生二进制同理，校验和取自官方 npm registry。" +
                    "下载中断可以断点续传，重点一次「安装」即可。\n\n" +
                    "装好后 CLI 不会自动更新（沙箱里没法交互），在侧边栏「环境与更新」里手动更新。"
            )
        },
    ) {
        if (state.cliIncomplete && !busy) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "检测到上次安装不完整：npm 包在，但约 100 MB 的原生二进制没有下完，会话一启动就会报 " +
                        "spawnSync … ENOENT。点下面的按钮只补这一部分。",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (busy) Progress(state)
        state.error?.let { ErrorCard(it, onDismissError) }
        // npm 源单独一个开关而不是跟着 Node 一起自动回退：Node 有官方校验和兜底，
        // claude-code 没有 —— 打开它等于同意从第三方镜像取 CLI 本体，必须是显式选择。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy) { useNpmMirror = !useNpmMirror },
        ) {
            Checkbox(checked = useNpmMirror, onCheckedChange = { useNpmMirror = it }, enabled = !busy)
            Column(Modifier.padding(start = 4.dp)) {
                Text("用淘宝 npm 源安装", style = MaterialTheme.typography.bodySmall)
                Text(
                    "国内快很多。原生二进制仍按官方 registry 的校验和验证；只有几 KB 的 wrapper 来自镜像。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = { onInstall(useNpmMirror) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    busy -> "正在安装…"
                    state.cliIncomplete -> "修复安装（补齐原生二进制）"
                    state.nodeInstalled -> "安装 Claude Code"
                    else -> "安装 Node.js + Claude Code"
                }
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
