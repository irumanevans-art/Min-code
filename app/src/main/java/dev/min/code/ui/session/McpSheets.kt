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
import dev.min.code.ui.components.openInExternalBrowser
import dev.min.code.ui.components.ToastType
import dev.min.code.ui.components.LocalToaster
import dev.min.code.core.engine.UrlRequest
import dev.min.code.core.engine.QuestionAnswerInput
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.InteractionAnswer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
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
    val resume by extras.resumePrompt.collectAsStateWithLifecycle()
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
        resume != null -> ResumeAdviceSheet(resume!!, onAnswer = extras::answerResume)
        else -> RewindOverlay(extras)
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
internal fun CardSheet(onDismiss: () -> Unit, dismissible: Boolean, content: @Composable () -> Unit) {
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
internal fun CardTitle(text: String) {
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

/**
 * MCP 的 url 模式提问：服务器请用户去一个网址完成一件事（授权、付款、填敏感信息）。MCP 规范的两个 MUST 在这里落实：
 * 一，**完整 URL 与域名**摆在用户面前（域名单独放大、在 URL 里高亮，URL 里夹带用户名的写警告——`https://好域名@坏域名/` 这类障眼法）；
 * 二，**不预取、不自动打开**：只有用户点「打开」这一下才用外部浏览器（Custom Tab）打开，没有浏览器就留在卡上说明、不当作同意。
 * 「打开」的答复是选 `y`（内核据此回 accept），「拒绝」是 Decline。
 */
@Composable
internal fun McpUrlSheet(pending: PendingInteraction.Question, request: UrlRequest, onAnswer: (InteractionAnswer) -> Unit) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val noBrowser = stringResource(R.string.mcp_url_no_browser)
    val parts = remember(request.url, request.domain) { splitUrlHost(request.url, request.domain) }
    CardSheet(onDismiss = {}, dismissible = false) {
        CardTitle(stringResource(R.string.mcp_url_title, pending.server.orEmpty()))
        if (request.message.isNotBlank()) Text(request.message, style = MaterialTheme.typography.bodyMedium)
        MonoBlock(stringResource(R.string.mcp_url_domain), request.domain, emphasized = true)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.mcp_url_full), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = buildAnnotatedString {
                    if (parts == null) append(request.url) else {
                        append(parts.before)
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.sea.ink)) { append(parts.host) }
                        append(parts.after)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
            )
        }
        if (parts?.hasUserInfo == true) {
            Text(stringResource(R.string.mcp_url_userinfo_warning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.sea.vermilion)
        }
        Text(
            stringResource(R.string.mcp_url_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InkDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton(onClick = { onAnswer(InteractionAnswer.Decline) }, modifier = Modifier.weight(1f), tone = InkButtonTone.Vermilion) {
                Text(stringResource(R.string.mcp_url_decline))
            }
            InkButton(
                onClick = {
                    // 打开不了（没有外部浏览器）就别替用户点头：卡留着，他可以拒绝
                    if (context.openInExternalBrowser(request.url)) {
                        onAnswer(InteractionAnswer.Answer(listOf(QuestionAnswerInput(pending.questions.firstOrNull()?.question.orEmpty(), listOf("y")))))
                    } else {
                        toaster.show(noBrowser, ToastType.Error)
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.mcp_url_open)) }
        }
    }
}

/** URL 拆成「主机之前 / 主机 / 主机之后」，给高亮用。[hasUserInfo] = 主机前面还有 `用户名@` */
internal data class UrlParts(val before: String, val host: String, val after: String, val hasUserInfo: Boolean)

/**
 * 在 [url] 里定位主机名 [domain]。按 URL 语法找（authority = `//` 到下一个 `/ ? #`，主机在最后一个 `@` 之后、端口之前），
 * 而不是在整串里找第一处 [domain]：`https://evil.com.attacker.net@evil.com/` 的主机是后面那个，字符串搜索会高亮到前面的伪装部分上。
 * 找不到（形状不对、主机与 [domain] 对不上）返回 null，调用方整串原样显示、不高亮。
 */
internal fun splitUrlHost(url: String, domain: String): UrlParts? {
    val schemeEnd = url.indexOf("://").takeIf { it > 0 } ?: return null
    val authorityStart = schemeEnd + 3
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) url.length else it }
    val authority = url.substring(authorityStart, authorityEnd)
    val at = authority.lastIndexOf('@')
    val hostPort = authority.substring(at + 1)
    // IPv6 字面量 [::1]:8080 里的冒号不是端口分隔；其余主机名里没有冒号，第一个冒号起是端口
    val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
    if (!host.equals(domain, ignoreCase = true)) return null
    val start = authorityStart + at + 1
    return UrlParts(url.substring(0, start), url.substring(start, start + host.length), url.substring(start + host.length), hasUserInfo = at >= 0)
}

/**
 * 重开一个又老又大的会话：说清为什么问（隔了多久、上下文多大、提示缓存早过期了所以完整续接要按全价重读一遍，
 * 有价格时给估算花费），再让人选「完整续接」（什么都不做）还是「从摘要续接」（先压成摘要，便宜但丢细节）。
 * 不能滑掉：两个按钮就是出口，滑走等于替人选了完整续接。
 */
@Composable
internal fun ResumeAdviceSheet(prompt: ResumePrompt, onAnswer: (summarize: Boolean) -> Unit) {
    val advice = prompt.advice
    val cost = advice.rereadCost?.let { stringResource(R.string.resume_advice_cost, it) }.orEmpty()
    CardSheet(onDismiss = {}, dismissible = false) {
        CardTitle(stringResource(R.string.resume_advice_title))
        Text(
            stringResource(R.string.resume_advice_body, idleText(advice.idleMs), tokensText(advice.contextTokens), cost),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.resume_advice_summary_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InkDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton(onClick = { onAnswer(false) }, modifier = Modifier.weight(1f), tone = InkButtonTone.Paper, enabled = !prompt.working) {
                Text(stringResource(R.string.resume_advice_full))
            }
            InkButton(onClick = { onAnswer(true) }, modifier = Modifier.weight(1f), busy = prompt.working) {
                Text(stringResource(R.string.resume_advice_summarize))
            }
        }
    }
}

/** 「约 3 小时」「约 2 天」：取最大的整单位，不够一小时按分钟 */
@Composable
private fun idleText(idleMs: Long): String {
    val minutes = (idleMs / 60_000).toInt()
    return when {
        minutes >= 60 * 24 -> stringResource(R.string.duration_days, minutes / (60 * 24))
        minutes >= 60 -> stringResource(R.string.duration_hours, minutes / 60)
        else -> stringResource(R.string.duration_minutes, minutes.coerceAtLeast(1))
    }
}

private fun tokensText(tokens: Long): String = if (tokens >= 1000) "${tokens / 1000}k" else tokens.toString()
