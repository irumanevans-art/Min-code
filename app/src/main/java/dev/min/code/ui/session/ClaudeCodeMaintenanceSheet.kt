package dev.min.code.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01

/**
 * 环境与更新。
 *
 * ## 为什么需要这张面板
 *
 * CLI 是用 `npm install -g @anthropic-ai/claude-code@latest` 装进 Rootfs 的，
 * 而安装器是幂等的：装过之后那条 npm 命令再也不会执行，安装向导也随之从 UI 上消失。
 * 于是手机上那份 CLI 的版本会**永远冻结在第一次安装那天**，官方发了新版也无从知晓。
 * 这张面板就是那条缺失的路径。
 *
 * ## 版式为什么是「信息在上、动作在下」
 *
 * 更新这件事的第一个问题永远是"我现在是哪一版"。先把三行版本摆出来，
 * 再给按钮 —— 反过来的话用户点完更新还是不知道发生了什么。
 *
 * ## 四件事的边界
 *
 * - **Claude Code CLI**：走 npm，唯一真正意义上的"升级"。
 * - **Ubuntu 软件包**：走 apt，管的是 Claude Code 自己 `apt install` 的 git/python3 等，
 *   和 CLI 版本毫无关系，所以单独一块。
 * - **重装 Node**：版本写死在安装器里（要配官方 SHA-256），这里只能重装同一版本修损坏，
 *   **不是升级**，所以收进「修复」而不是摆在更新旁边诱导点击。
 * - **重装 Linux 环境**：会清空整个 Rootfs（连会话记录一起），只留一个跳转链接，
 *   真正的确认在工作区详情页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClaudeCodeMaintenanceSheet(
    state: ClaudeCodeVM.MaintenanceState,
    liveSessionCount: Int,
    onDismiss: () -> Unit,
    onCheckUpdate: () -> Unit,
    onUpdateCli: (useNpmMirror: Boolean) -> Unit,
    onUpgradeApt: () -> Unit,
    onReinstallNode: () -> Unit,
    onOpenWorkspace: () -> Unit,
) {
    var useNpmMirror by rememberSaveable { mutableStateOf(false) }
    var repairOpen by rememberSaveable { mutableStateOf(false) }

    // 有会话在跑时所有写操作都禁掉：npm/apt 会原地替换正在被执行的文件
    val blocked = liveSessionCount > 0
    val actionable = !state.busy && !blocked

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            SheetValue.Hidden,
            setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "环境与更新",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 10.dp),
            )

            // --- 环境信息 ---
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    InfoRow("Linux", state.osName ?: placeholder(state.loading))
                    InfoRow(
                        label = "Node.js",
                        value = listOfNotNull(
                            state.nodeVersion,
                            state.npmVersion?.let { "npm $it" },
                        ).joinToString(" · ").ifBlank { placeholder(state.loading) },
                    )
                    InfoRow(
                        label = "Claude Code",
                        value = when {
                            state.cliVersion == null -> placeholder(state.loading)
                            state.cliIncomplete -> "${state.cliVersion}（不完整）"
                            else -> state.cliVersion
                        },
                    )
                }
            }

            if (state.cliIncomplete) {
                Notice(
                    text = "CLI 的 npm 包在，但原生二进制缺失（上次那个约 100 MB 的平台包没有下完），" +
                        "会话启动会报 spawnSync … ENOENT。点下面「修复安装」补齐。",
                    isError = true,
                )
            }

            if (blocked) {
                Notice(
                    text = "有 $liveSessionCount 个会话正在运行。更新会替换掉正在执行的文件，" +
                        "请先在侧边栏关闭全部会话。",
                    isError = true,
                )
            }

            // --- Claude Code CLI ---
            BlockTitle("Claude Code CLI")
            Text(
                "官方 npm 包 @anthropic-ai/claude-code。安装时装的是 @latest，" +
                    "但那之后不会自动跟进 —— 要新版本得在这里手动更新。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = onCheckUpdate, enabled = !state.checking && state.running == null) {
                    Text(if (state.checking) "查询中…" else "检查更新")
                }
                Text(
                    text = updateHint(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.updateAvailable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            // npm 源单独一个开关而不是跟着自动回退：Node 有官方校验和兜底，
            // claude-code 没有 —— 打开它等于同意从第三方镜像取 CLI 本体，必须是显式选择。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = actionable) { useNpmMirror = !useNpmMirror },
            ) {
                Checkbox(
                    checked = useNpmMirror,
                    onCheckedChange = { useNpmMirror = it },
                    enabled = actionable,
                )
                Column(Modifier.padding(start = 4.dp)) {
                    Text("用淘宝 npm 源更新", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "国内快很多。原生二进制仍按官方 registry 的 sha512 校验，只有几 KB 的 wrapper 来自镜像。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = { onUpdateCli(useNpmMirror) },
                enabled = actionable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.running == ClaudeCodeVM.MaintenanceTask.UpdateCli -> "正在更新…"
                        state.cliIncomplete -> "修复安装（补齐原生二进制）"
                        state.updateAvailable -> "更新到 ${state.latestCliVersion}"
                        else -> "更新 Claude Code CLI"
                    }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

            // --- Ubuntu 软件包 ---
            BlockTitle("Ubuntu 软件包")
            Text(
                "升级 Rootfs 里已装的 apt 包（Claude Code 自己装的 git、python3、ripgrep 等）。" +
                    "和 CLI 版本无关，可能耗时数分钟并消耗流量。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onUpgradeApt,
                enabled = actionable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.running == ClaudeCodeVM.MaintenanceTask.AptUpgrade) "正在升级…"
                    else "更新软件包 (apt)"
                )
            }

            // --- 进行中 / 结果 / 错误 ---
            if (state.running != null) {
                val progress = state.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    state.detail.ifBlank { "正在处理…" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.lastResult?.takeIf { state.running == null }?.let {
                Notice(text = it, isError = false)
            }
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

            // --- 修复（默认折叠：这两项都不是日常操作，摆出来只会诱导误点） ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { repairOpen = !repairOpen }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("修复", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (repairOpen) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = if (repairOpen) "收起修复选项" else "展开修复选项",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (repairOpen) {
                Text(
                    "Node.js 的版本被写死在 App 里（要配一份官方 SHA-256 校验和），" +
                        "所以这里只能重装同一个版本来修损坏的运行时，不是升级。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onReinstallNode,
                    enabled = actionable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.running == ClaudeCodeVM.MaintenanceTask.ReinstallNode) "正在重装…"
                        else "重装 Node.js 运行时"
                    )
                }

                Notice(
                    text = "重装 Linux 环境会清空整个 Rootfs：Claude Code CLI、Node.js、" +
                        "apt 装过的所有工具，以及 ~/.claude 下的全部会话记录都会一起消失，" +
                        "之后要从头装一遍。只有环境彻底坏掉时才这么做。",
                    isError = true,
                )
                OutlinedButton(
                    onClick = onOpenWorkspace,
                    enabled = state.running == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("前往工作区重装 Linux 环境")
                }
            }
        }
    }
}

/** 版本还没读上来时不显示 "-"（会被当成"没装"），而是显式说在读 */
private fun placeholder(loading: Boolean): String = if (loading) "读取中…" else "未安装"

private fun updateHint(state: ClaudeCodeVM.MaintenanceState): String = when {
    state.checking -> ""
    state.latestCliVersion == null -> "未查询"
    state.updateAvailable -> "可更新到 ${state.latestCliVersion}"
    else -> "已是最新（${state.latestCliVersion}）"
}

@Composable
private fun BlockTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Notice(text: String, isError: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
