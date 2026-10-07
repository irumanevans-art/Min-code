package dev.min.code.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.engine.McpLoginPrompt
import dev.min.code.core.engine.McpServerCommandLine
import dev.min.code.core.engine.TrustProbe
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.Seal
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea

/**
 * 会话页上由 [SessionExtras] 驱动的几张卡，一处摆放（页面只调这一个函数）。
 * 一次只显示一张，优先级：信任（它决定后面的东西连不连）> 登录确认（用户刚点的）> 服务器批准（后台发现的）。
 */
@Composable
internal fun SessionExtrasOverlays(extras: SessionExtras) {
    val trust by extras.trustPrompt.collectAsStateWithLifecycle()
    val login by extras.loginPrompt.collectAsStateWithLifecycle()
    val approval by extras.pendingApproval.collectAsStateWithLifecycle()
    when {
        trust != null -> TrustSheet(
            probe = trust!!.probe,
            startsSession = trust!!.then != null,
            onAnswer = extras::answerTrust,
        )
        login != null -> McpLoginSheet(login!!, onAnswer = extras::answerLoginPrompt)
        approval != null -> {
            val server = approval!!
            val command by produceState<McpServerCommandLine?>(null, server) { value = extras.describe(server) }
            McpApprovalSheet(server = server, commandLine = command, onAnswer = { allow -> extras.approve(server, allow) })
        }
    }
}

/**
 * 工作区信任卡（照 Claude Code 的 workspace trust 对话框口径）：说清「信任」会让什么发生，也说清「不信任」照样能用。
 * 不放「始终」之类的捷径：信任是对一个目录的决定，会话设置里随时能撤销。
 */
@Composable
internal fun TrustSheet(probe: TrustProbe, startsSession: Boolean, onAnswer: (Boolean?) -> Unit) {
    CardSheet(onDismiss = { onAnswer(null) }, dismissible = true) {
        CardTitle(stringResource(R.string.trust_title))
        Text(probe.cwd, style = MaterialTheme.typography.labelMedium, fontFamily = JetbrainsMono)
        Text(stringResource(R.string.trust_intro), style = MaterialTheme.typography.bodyMedium)
        if (probe.hookEvents.isNotEmpty()) {
            Bullet(stringResource(R.string.trust_hooks, probe.hookEvents.joinToString(", ")))
        }
        if (probe.mcpServers.isNotEmpty()) {
            Bullet(stringResource(R.string.trust_mcp, probe.mcpServers.joinToString(", ")))
        }
        if (probe.hookEvents.isEmpty() && probe.mcpServers.isEmpty()) {
            Bullet(stringResource(R.string.trust_nothing))
        }
        Text(
            stringResource(R.string.trust_warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.sea.vermilion,
        )
        Text(
            stringResource(R.string.trust_distrust_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InkDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton(
                onClick = { onAnswer(false) },
                modifier = Modifier.weight(1f),
                tone = InkButtonTone.Paper,
            ) { Text(stringResource(if (startsSession) R.string.trust_no_continue else R.string.trust_no)) }
            InkButton(
                onClick = { onAnswer(true) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(if (startsSession) R.string.trust_yes_continue else R.string.trust_yes)) }
        }
        if (startsSession) {
            InkButton(onClick = { onAnswer(null) }, modifier = Modifier.fillMaxWidth(), tone = InkButtonTone.Quiet) {
                Text(stringResource(R.string.trust_cancel))
            }
        }
    }
}

/**
 * 项目 `.mcp.json` 里的新服务器（照 CC：「在 .mcp.json 里发现新的 MCP 服务器」允许 / 拒绝）。
 * 把它**怎么跑**原样摆出来（命令行或地址，环境变量不展开）：用户批准的是「在手机上运行这条命令 / 连这个地址」，
 * 光有一个名字等于没法判断。
 */
@Composable
internal fun McpApprovalSheet(server: String, commandLine: McpServerCommandLine?, onAnswer: (allow: Boolean) -> Unit) {
    CardSheet(onDismiss = {}, dismissible = false) {
        CardTitle(stringResource(R.string.mcp_approval_title))
        Text(server, style = MaterialTheme.typography.titleSmall, fontFamily = JetbrainsMono)
        commandLine?.command?.let { MonoBlock(stringResource(R.string.mcp_approval_command), it) }
        commandLine?.url?.let { MonoBlock(stringResource(R.string.mcp_approval_url), it) }
        Text(
            stringResource(R.string.mcp_approval_warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InkDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton(onClick = { onAnswer(false) }, modifier = Modifier.weight(1f), tone = InkButtonTone.Vermilion) {
                Text(stringResource(R.string.mcp_approval_deny))
            }
            InkButton(onClick = { onAnswer(true) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.mcp_approval_allow))
            }
        }
    }
}

/** 登录确认：浏览器打开之前，让人看到去哪个域名授权、要什么权限；不点「继续」就不会打开 */
@Composable
internal fun McpLoginSheet(prompt: McpLoginPrompt, onAnswer: (proceed: Boolean) -> Unit) {
    CardSheet(onDismiss = {}, dismissible = false) {
        CardTitle(stringResource(R.string.mcp_login_title, prompt.server))
        MonoBlock(stringResource(R.string.mcp_login_domain), prompt.domain, emphasized = true)
        if (prompt.issuer != prompt.domain) MonoBlock(stringResource(R.string.mcp_login_issuer), prompt.issuer)
        MonoBlock(stringResource(R.string.mcp_login_scopes), prompt.scopes ?: stringResource(R.string.mcp_login_scopes_none))
        Text(
            stringResource(R.string.mcp_login_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InkDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton(onClick = { onAnswer(false) }, modifier = Modifier.weight(1f), tone = InkButtonTone.Paper) {
                Text(stringResource(R.string.mcp_login_cancel))
            }
            InkButton(onClick = { onAnswer(true) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.mcp_login_continue))
            }
        }
    }
}

/**
 * 这一组卡共用的底部 sheet 外壳。不可滑掉的（批准、登录）与审批卡同一道理：会话流上方弹出来的瞬间手指还在滑，
 * 一下滑走就等于替用户做了决定，出口只留明确的按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardSheet(onDismiss: () -> Unit, dismissible: Boolean, content: @Composable () -> Unit) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { dismissible || it != SheetValue.Hidden },
    )
    InkSheet(onDismissRequest = onDismiss, sheetState = sheetState, dismissible = dismissible) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

@Composable
private fun CardTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Seal()
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Bullet(text: String) {
    Text("· $text", style = MaterialTheme.typography.bodyMedium)
}

/** 一段需要原样读的东西（命令行、地址、域名）：小标签 + 等宽正文。[emphasized] 用墨色加粗（登录的域名要一眼看到） */
@Composable
private fun MonoBlock(label: String, value: String, emphasized: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            fontFamily = JetbrainsMono,
            color = if (emphasized) MaterialTheme.sea.ink else MaterialTheme.colorScheme.onSurface,
        )
    }
}
