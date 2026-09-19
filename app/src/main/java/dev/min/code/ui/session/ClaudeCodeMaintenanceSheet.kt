package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkCheckbox
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkLineProgress
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.PaperCard
import dev.min.code.ui.components.Seal
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01

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

    InkSheet(
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
            Row(
                modifier = Modifier.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Seal()
                Text(stringResource(R.string.session_drawer_maintenance), style = MaterialTheme.typography.titleMedium)
            }

            // --- 环境信息 ---
            PaperCard(modifier = Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            state.cliIncomplete -> stringResource(R.string.maint_cli_incomplete_version, state.cliVersion)
                            else -> state.cliVersion
                        },
                    )
                }
            }

            if (state.cliIncomplete) {
                Notice(
                    text = stringResource(R.string.maint_cli_incomplete_notice),
                    tone = NoticeTone.Error,
                )
            }

            if (blocked) {
                Notice(
                    text = stringResource(R.string.maint_blocked, liveSessionCount),
                    tone = NoticeTone.Error,
                )
            }

            // --- Claude Code CLI ---
            SectionTitle("Claude Code CLI", modifier = Modifier.padding(top = 4.dp))
            Text(
                stringResource(R.string.maint_cli_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                InkButton(
                    onClick = onCheckUpdate,
                    enabled = !state.checking && state.running == null,
                    tone = InkButtonTone.Paper,
                    compact = true,
                    busy = state.checking,
                ) {
                    Text(stringResource(if (state.checking) R.string.maint_checking else R.string.maint_check_update))
                }
                Text(
                    text = updateHint(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.updateAvailable) MaterialTheme.sea.seaDeep
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = actionable) { useNpmMirror = !useNpmMirror }
                    .padding(vertical = 6.dp),
            ) {
                InkCheckbox(
                    checked = useNpmMirror,
                    onCheckedChange = null,
                    enabled = actionable,
                )
                Column {
                    Text(stringResource(R.string.maint_mirror_title), style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.maint_mirror_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            InkButton(
                onClick = { onUpdateCli(useNpmMirror) },
                enabled = actionable,
                busy = state.running == ClaudeCodeVM.MaintenanceTask.UpdateCli,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.running == ClaudeCodeVM.MaintenanceTask.UpdateCli -> stringResource(R.string.maint_updating)
                        state.cliIncomplete -> stringResource(R.string.maint_repair_install)
                        state.updateAvailable -> stringResource(R.string.maint_update_to, state.latestCliVersion.orEmpty())
                        else -> stringResource(R.string.maint_update_cli)
                    }
                )
            }

            InkDivider()

            // --- Ubuntu 软件包 ---
            SectionTitle(stringResource(R.string.maint_apt_title), modifier = Modifier.padding(top = 4.dp))
            Text(
                stringResource(R.string.maint_apt_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InkButton(
                onClick = onUpgradeApt,
                enabled = actionable,
                tone = InkButtonTone.Paper,
                busy = state.running == ClaudeCodeVM.MaintenanceTask.AptUpgrade,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.running == ClaudeCodeVM.MaintenanceTask.AptUpgrade) stringResource(R.string.maint_apt_upgrading)
                    else stringResource(R.string.maint_apt_update)
                )
            }

            // --- 进行中 / 结果 / 错误 ---
            AnimatedVisibility(visible = state.running != null, enter = InkMotion.expand, exit = InkMotion.collapse) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InkLineProgress(progress = state.progress, modifier = Modifier.fillMaxWidth())
                    Text(
                        state.detail.ifBlank { stringResource(R.string.maint_working) },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.lastResult?.takeIf { state.running == null }?.let {
                Notice(text = it, tone = NoticeTone.Info)
            }
            state.error?.let {
                Notice(text = it, tone = NoticeTone.Error, maxLines = 12)
            }

            InkDivider()

            // --- 修复（默认折叠：这两项都不是日常操作，摆出来只会诱导误点） ---
            val chevron by animateFloatAsState(if (repairOpen) 180f else 0f, InkMotion.spatial(), label = "repairChevron")
            SectionTitle(
                stringResource(R.string.maint_repair),
                modifier = Modifier
                    .clickable { repairOpen = !repairOpen }
                    .padding(vertical = 10.dp),
                trailing = {
                    Icon(
                        HugeIcons.ArrowDown01,
                        contentDescription = stringResource(if (repairOpen) R.string.maint_repair_collapse else R.string.maint_repair_expand),
                        modifier = Modifier
                            .size(14.dp)
                            .graphicsLayer { rotationZ = chevron },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            AnimatedVisibility(visible = repairOpen, enter = InkMotion.expand, exit = InkMotion.collapse) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.maint_node_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InkButton(
                        onClick = onReinstallNode,
                        enabled = actionable,
                        tone = InkButtonTone.Paper,
                        busy = state.running == ClaudeCodeVM.MaintenanceTask.ReinstallNode,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.running == ClaudeCodeVM.MaintenanceTask.ReinstallNode) stringResource(R.string.maint_node_reinstalling)
                            else stringResource(R.string.maint_node_reinstall)
                        )
                    }

                    Notice(
                        text = stringResource(R.string.maint_reinstall_warning),
                        tone = NoticeTone.Error,
                    )
                    InkButton(
                        onClick = onOpenWorkspace,
                        enabled = state.running == null,
                        tone = InkButtonTone.Vermilion,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.maint_reinstall_go))
                    }
                }
            }
        }
    }
}

/** 版本还没读上来时不显示 "-"（会被当成"没装"），而是显式说在读 */
@Composable
private fun placeholder(loading: Boolean): String =
    stringResource(if (loading) R.string.maint_loading else R.string.maint_not_installed)

@Composable
private fun updateHint(state: ClaudeCodeVM.MaintenanceState): String = when {
    state.checking -> ""
    state.latestCliVersion == null -> stringResource(R.string.maint_not_checked)
    state.updateAvailable -> stringResource(R.string.maint_update_available, state.latestCliVersion)
    else -> stringResource(R.string.maint_up_to_date, state.latestCliVersion)
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
            fontFamily = JetbrainsMono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
