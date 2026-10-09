package dev.min.code.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.engine.McpServerState
import dev.min.code.core.engine.McpServerView
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkSwitch
import dev.min.code.ui.components.LiveDot
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea

/** 手风琴行右边那段摘要：「3 个」，有要处理的（待批准 / 要登录 / 失败）带上「· 1 个要处理」 */
@Composable
internal fun mcpSummary(servers: List<McpServerView>): String {
    if (servers.isEmpty()) return stringResource(R.string.mcp_panel_none_short)
    val attention = servers.count { it.state in ATTENTION }
    return if (attention > 0) stringResource(R.string.mcp_panel_count_attention, servers.size, attention)
    else stringResource(R.string.mcp_panel_count, servers.size)
}

private val ATTENTION = setOf(McpServerState.NotApproved, McpServerState.NeedsAuth, McpServerState.Failed, McpServerState.Untrusted)

/**
 * MCP 服务器面板（设置里的一段）：每台服务器的实时状态、作用域、工具数、stderr 尾巴，以及按状态出现的动作——
 * 重连、本会话开关、登录 / 退出登录、批准、去信任。数据是 [SessionExtras.servers] 的轮询结果，不是日志快照。
 */
@Composable
internal fun McpPanelSection(
    servers: List<McpServerView>,
    busy: Set<String>,
    onReconnect: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onLogin: (String) -> Unit,
    onLogout: (String) -> Unit,
    onApprove: (String, Boolean) -> Unit,
    onTrust: () -> Unit,
) {
    Column {
        if (servers.isEmpty()) {
            SettingsSubHeader(stringResource(R.string.mcp_panel_none))
            return@Column
        }
        servers.forEachIndexed { index, server ->
            if (index > 0) InkDivider(Modifier.padding(horizontal = 16.dp))
            McpServerRow(
                server = server, busy = server.name in busy,
                onReconnect = { onReconnect(server.name) }, onToggle = { onToggle(server.name, it) },
                onLogin = { onLogin(server.name) }, onLogout = { onLogout(server.name) },
                onApprove = { onApprove(server.name, it) }, onTrust = onTrust,
            )
        }
    }
}

@Composable
private fun McpServerRow(
    server: McpServerView,
    busy: Boolean,
    onReconnect: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onApprove: (Boolean) -> Unit,
    onTrust: () -> Unit,
) {
    val palette = MaterialTheme.sea
    // 失败的 stderr 默认展开（人点进来就是想知道为什么），其余折叠
    var showStderr by remember(server.name, server.state == McpServerState.Failed) { mutableStateOf(server.state == McpServerState.Failed) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(server.name, style = MaterialTheme.typography.titleSmall, fontFamily = JetbrainsMono, modifier = Modifier.weight(1f))
            if (server.state == McpServerState.Connected) LiveDot(palette.ink, size = 6.dp) else Spacer(Modifier.size(6.dp))
            Text(
                stringResource(stateLabel(server)),
                style = MaterialTheme.typography.labelMedium,
                color = if (server.state == McpServerState.Failed) palette.vermilion else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            listOfNotNull(
                scopeLabel(server.scope),
                server.transport,
                if (server.state == McpServerState.Connected || server.toolCount > 0) stringResource(R.string.mcp_panel_tools, server.toolCount) else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 内核给的说明偏工程口吻（带设计文档章节号）；未信任这一种界面自己说人话
        val reason = if (server.state == McpServerState.Untrusted) stringResource(R.string.mcp_untrusted_reason) else server.reason
        reason?.takeIf { it.isNotBlank() && server.state != McpServerState.Connected && !server.sessionOff }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = if (server.state == McpServerState.Failed) palette.vermilion else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (server.signedIn == true) {
            Text(stringResource(R.string.mcp_panel_signed_in), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (server.stderrTail.isNotEmpty()) {
            Text(
                stringResource(if (showStderr) R.string.mcp_panel_stderr_hide else R.string.mcp_panel_stderr_show, server.stderrTail.size),
                style = MaterialTheme.typography.labelSmall,
                color = palette.ink,
                modifier = Modifier.clickable { showStderr = !showStderr },
            )
            if (showStderr) {
                Text(
                    server.stderrTail.takeLast(STDERR_LINES).joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (server.state) {
                McpServerState.Untrusted -> ActionButton(R.string.mcp_action_trust, !busy, onClick = onTrust)
                McpServerState.NotApproved -> {
                    ActionButton(R.string.mcp_approval_allow, !busy) { onApprove(true) }
                    ActionButton(R.string.mcp_approval_deny, !busy, tone = InkButtonTone.Vermilion) { onApprove(false) }
                }
                McpServerState.NeedsAuth -> ActionButton(R.string.mcp_action_login, !busy, busy = busy, onClick = onLogin)
                McpServerState.Connected, McpServerState.Failed, McpServerState.Pending -> {
                    ActionButton(R.string.mcp_action_reconnect, !busy, busy = busy, onClick = onReconnect)
                    if (server.signedIn == true) ActionButton(R.string.mcp_action_logout, !busy, onClick = onLogout)
                }
                McpServerState.Disabled, McpServerState.Denied -> Unit
            }
            // 本会话开关：只对「配置里放行了」的服务器有意义（待批准 / 未信任 / 被策略拒绝的开了也连不上）
            if (server.sessionToggle) {
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.mcp_panel_session_on), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                InkSwitch(checked = !server.sessionOff, onCheckedChange = { onToggle(it) }, enabled = !busy)
            }
        }
    }
}

@Composable
private fun ActionButton(label: Int, enabled: Boolean, tone: InkButtonTone = InkButtonTone.Paper, busy: Boolean = false, onClick: () -> Unit) {
    InkButton(onClick = onClick, enabled = enabled, tone = tone, busy = busy, compact = true) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

private fun stateLabel(server: McpServerView): Int = when {
    server.sessionOff -> R.string.mcp_state_session_off
    else -> when (server.state) {
        McpServerState.Pending -> R.string.mcp_state_pending
        McpServerState.Connected -> R.string.mcp_state_connected
        McpServerState.Failed -> R.string.mcp_state_failed
        McpServerState.NeedsAuth -> R.string.mcp_state_needs_auth
        McpServerState.Disabled -> R.string.mcp_state_disabled
        McpServerState.NotApproved -> R.string.mcp_state_not_approved
        McpServerState.Untrusted -> R.string.mcp_state_untrusted
        McpServerState.Denied -> R.string.mcp_state_denied
    }
}

@Composable
private fun scopeLabel(scope: String): String = when (scope) {
    "project" -> stringResource(R.string.mcp_scope_project)
    "user" -> stringResource(R.string.mcp_scope_user)
    "local" -> stringResource(R.string.mcp_scope_local)
    "managed" -> stringResource(R.string.mcp_scope_managed)
    else -> scope
}

/** stderr 尾巴最多显示几行：再多就是把面板撑成一整页日志，想看全的去 rootfs 里 */
private const val STDERR_LINES = 20

/** 工作区信任一段：当前目录的状态与能做的那一个动作 */
@Composable
internal fun TrustSection(view: TrustView, onAsk: () -> Unit, onSet: (Boolean?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(view.cwd, style = MaterialTheme.typography.labelMedium, fontFamily = JetbrainsMono)
        val state = stringResource(trustStateLabel(view))
        Text(
            view.inheritedFrom?.let { stringResource(R.string.trust_state_inherited, state, it) } ?: state,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.trust_section_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // 这个目录的项目配置会放宽哪些权限：已信任 = 正在生效，没信任 = 信任后才会生效（内核只在受信后才采纳项目层的这两样）
        view.grants?.takeIf { it.relaxesPermissions }?.let { grants ->
            Text(
                stringResource(if (view.decision == true) R.string.trust_grants_on else R.string.trust_grants_off),
                style = MaterialTheme.typography.labelMedium,
            )
            if (grants.allowRules.isNotEmpty()) {
                Text(stringResource(R.string.trust_allow, grants.allowRules.size, previewList(grants.allowRules)), style = MaterialTheme.typography.labelSmall, fontFamily = JetbrainsMono)
            }
            if (grants.additionalDirectories.isNotEmpty()) {
                Text(stringResource(R.string.trust_directories, grants.additionalDirectories.size, previewList(grants.additionalDirectories)), style = MaterialTheme.typography.labelSmall, fontFamily = JetbrainsMono)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                view.ownTrust -> ActionButton(R.string.trust_revoke, true, onClick = { onSet(null) })
                view.decision == true -> ActionButton(R.string.trust_distrust_here, true, onClick = { onSet(false) })
                else -> ActionButton(R.string.mcp_action_trust, true, onClick = onAsk)
            }
        }
    }
}

private fun trustStateLabel(view: TrustView): Int = when (view.decision) {
    true -> R.string.trust_state_trusted
    false -> R.string.trust_state_refused
    null -> R.string.trust_state_unasked
}
