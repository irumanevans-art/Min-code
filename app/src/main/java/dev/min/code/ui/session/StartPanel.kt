package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.ui.components.HeroCanvas
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkCheckbox
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.SeaSurface
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.theme.InkMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play

@Composable
internal fun StartPanel(
    session: AgentSessionState,
    cliVersion: String?,
    /** (跳过权限确认, 工作目录)。其余设置沿用上次的，由会话层取 */
    onStart: (Boolean, String) -> Unit,
    /** 面板上默认选中的工作目录（上次用过的） */
    defaultCwd: String,
    onListCwd: suspend (String) -> Result<List<me.rerere.workspace.WorkspaceFileEntry>> = EmptyCwdList,
    onCreateCwd: (String, String, (Result<String>) -> Unit) -> Unit = NoopCwdCreate,
    /** false = 还没有可用的供应商，见 [SessionVM.connected] */
    connected: Boolean = true,
    onOpenProviders: () -> Unit = {},
) {
    var skipPermissions by rememberSaveable { mutableStateOf(false) }
    var cwd by rememberSaveable { mutableStateOf(defaultCwd) }
    var pickingCwd by remember { mutableStateOf(false) }
    // 挂在 ConnectPrompt 外面：登录成功那一刻 ConnectPrompt 就收起了，见它的注释
    var subscriptionLogin by remember { mutableStateOf(false) }
    SeaSurface {
    HeroCanvas {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = BiasAlignment(0f, 1f),
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionTitle(stringResource(R.string.workspace_detail_workspace_info))
                // 产品名，不翻
                Text("Claude Code", style = MaterialTheme.typography.headlineSmall)
                CwdPathRow(
                    cwd = cwd,
                    trailing = cliVersion,
                    onClick = { pickingCwd = true },
                )
                Spacer(Modifier.height(10.dp))
                InkDivider()
                AnimatedVisibility(visible = !connected, enter = InkMotion.expand, exit = InkMotion.collapse) {
                    ConnectPrompt(
                        onOpenProviders = onOpenProviders,
                        onSubscriptionLogin = { subscriptionLogin = true },
                    )
                }
                // 上次崩溃过就在这里说一声：没有联网上报，用户不主动去「关于」里看就永远不知道。
                // 只提示、不弹窗——启动面板本来就是"停下来看一眼"的地方
                val context = LocalContext.current
                // 读崩溃记录要碰磁盘：挪到 IO，别卡在启动面板的组合里
                var crashed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    crashed = withContext(Dispatchers.IO) {
                        dev.min.code.core.crash.CrashRecorder.read(context) != null
                    }
                }
                AnimatedVisibility(visible = crashed, enter = InkMotion.expand, exit = InkMotion.collapse) {
                    Notice(
                        text = stringResource(R.string.session_start_crash_notice),
                        tone = NoticeTone.Warn,
                        action = {
                            InkTextButton(onClick = { dev.min.code.core.crash.CrashRecorder.clear(context); crashed = false }) {
                                Text(stringResource(R.string.common_clear), style = MaterialTheme.typography.labelSmall)
                            }
                        },
                    )
                }
                session.errorMessage?.let {
                    Notice(text = it, tone = NoticeTone.Error, maxLines = 6)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { skipPermissions = !skipPermissions }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    // 整行可点，勾选框自己不接点击
                    InkCheckbox(checked = skipPermissions, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.session_start_skip_permissions),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.session_start_skip_permissions_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                InkButton(
                    onClick = { onStart(skipPermissions, cwd) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = HugeIcons.Play,
                ) {
                    Text(stringResource(R.string.session_start_action))
                }
            }
        }
    }
    }
    if (pickingCwd) {
        CwdPickerSheet(
            current = cwd,
            onPick = { cwd = it; pickingCwd = false },
            onDismiss = { pickingCwd = false },
            onList = onListCwd,
            onCreate = onCreateCwd,
        )
    }
    if (subscriptionLogin) {
        ClaudeSubscriptionSheet(onDismiss = { subscriptionLogin = false })
    }
}
