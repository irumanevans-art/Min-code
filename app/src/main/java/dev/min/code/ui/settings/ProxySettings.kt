package dev.min.code.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.settings.ProxyConfig
import dev.min.code.core.settings.ProxyEdit
import dev.min.code.core.settings.ProxyKind
import dev.min.code.core.settings.ProxyProbe
import dev.min.code.core.settings.applying
import dev.min.code.core.settings.parseProxyLink
import dev.min.code.core.settings.parseProxyPort
import dev.min.code.core.settings.probeProxy
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDialog
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.SettingRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 分段选择器的顺序：关闭 / HTTP / SOCKS5（按下标定位，不依赖枚举声明顺序） */
private val KIND_ORDER = listOf(ProxyKind.OFF, ProxyKind.HTTP, ProxyKind.SOCKS5)

/**
 * 应用内代理（设置项 `proxy`）：照 Telegram，只给 Min 自己挂代理，手机不用开 VPN。
 * **密码只显示「已填」、不回显**；要换就重新输入，要清就点「清除」。
 */
@Composable
internal fun ProxyRow(config: ProxyConfig, onSave: (ProxyEdit) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val upstream = config.upstream()
    SettingRow(
        title = stringResource(R.string.settings_proxy_title),
        subtitle = when {
            config.kind == ProxyKind.OFF -> stringResource(R.string.settings_proxy_off)
            upstream == null -> stringResource(R.string.settings_proxy_incomplete)
            else -> "${kindName(config.kind)} · ${upstream.host}:${upstream.port}"
        },
        onClick = { editing = true },
    )
    if (editing) ProxyDialog(config, onDismiss = { editing = false }, onSave = { onSave(it); editing = false })
}

@Composable
private fun kindName(kind: ProxyKind): String = when (kind) {
    ProxyKind.OFF -> stringResource(R.string.settings_proxy_kind_off)
    ProxyKind.HTTP -> "HTTP"
    ProxyKind.SOCKS5 -> "SOCKS5"
}

@Composable
private fun ProxyDialog(config: ProxyConfig, onDismiss: () -> Unit, onSave: (ProxyEdit) -> Unit) {
    var kind by remember { mutableStateOf(if (config.kind == ProxyKind.OFF && config.host.isBlank()) ProxyKind.SOCKS5 else config.kind) }
    var host by remember { mutableStateOf(config.host) }
    var port by remember { mutableStateOf(if (config.port > 0) config.port.toString() else "") }
    var username by remember { mutableStateOf(config.username) }
    // 输入框里永远是空的起点：已填的密码不回显，空 = 保持不变
    var password by remember { mutableStateOf("") }
    var clearPassword by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<ProxyProbe?>(null) }
    var probing by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var pasteFailed by remember { mutableStateOf(false) }
    fun fill(link: ProxyConfig) {
        kind = link.kind; host = link.host; port = link.port.toString(); username = link.username
        // 链接里没带密码就是没有密码：已存的那个属于之前的配置
        password = link.password; clearPassword = link.password.isEmpty() && config.password.isNotEmpty()
        probe = null; pasteFailed = false
    }

    val portValue = parseProxyPort(port)
    val portInvalid = port.isNotBlank() && portValue == null
    val savedPassword = config.password.isNotEmpty() && username.trim() == config.username && !clearPassword
    fun edit(asKind: ProxyKind = kind) = ProxyEdit(asKind, host, portValue ?: 0, username, password, clearPassword)
    // 要用的那份（关着时也按所选类型算，方便先测再开）
    val candidate = config.applying(edit(if (kind == ProxyKind.OFF) ProxyKind.SOCKS5 else kind)).upstream()
    val canEnable = kind == ProxyKind.OFF || candidate != null

    InkDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_proxy_title),
        confirmButton = {
            InkTextButton(enabled = canEnable, onClick = { probing?.cancel(); onSave(edit()) }) { Text(stringResource(R.string.settings_proxy_save)) }
        },
        dismissButton = { InkTextButton(onClick = { probing?.cancel(); onDismiss() }) { Text(stringResource(R.string.team_cancel)) } },
    ) {
        InkSegmented(
            options = KIND_ORDER.map { kindName(it) },
            selected = KIND_ORDER.indexOf(kind),
            onSelect = { kind = KIND_ORDER[it]; probe = null },
        )
        InkTextField(
            value = host,
            onValueChange = { text ->
                // 粘贴一整条代理链接（socks5://…、http://…、tg://socks?…）就直接拆开填好。
                // 只认粘贴（一次进来好几个字）：逐字敲时打到 `socks5://m` 就已经是条合法链接，会被提前拆掉
                val link = if (text.length - host.length > 1) parseProxyLink(text) else null
                if (link != null) fill(link) else host = text.trim()
                probe = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.settings_proxy_host),
            placeholder = "127.0.0.1",
            singleLine = true,
            monospace = true,
            supporting = stringResource(R.string.settings_proxy_host_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        InkTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5); probe = null },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.settings_proxy_port),
            placeholder = if (kind == ProxyKind.HTTP) "7890" else "1080",
            singleLine = true,
            monospace = true,
            isError = portInvalid,
            supporting = if (portInvalid) stringResource(R.string.settings_proxy_port_invalid) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        InkTextField(
            value = username,
            onValueChange = { username = it; probe = null },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.settings_proxy_username),
            placeholder = stringResource(R.string.settings_proxy_optional),
            singleLine = true,
            monospace = true,
        )
        InkTextField(
            value = password,
            onValueChange = { password = it; probe = null },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.settings_proxy_password),
            placeholder = stringResource(if (savedPassword) R.string.settings_web_search_key_saved else R.string.settings_proxy_optional),
            singleLine = true,
            monospace = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InkButton(
                enabled = candidate != null,
                busy = probing != null,
                onClick = {
                    val target = candidate ?: return@InkButton
                    probe = null
                    probing = scope.launch {
                        try { probe = probeProxy(target) } finally { probing = null }
                    }
                },
                tone = InkButtonTone.Paper,
                compact = true,
            ) { Text(stringResource(if (probing != null) R.string.settings_proxy_testing else R.string.settings_proxy_test)) }
            // 照 Telegram 收剪贴板里的代理链接，但只在点按钮时才读：打开对话框就读会让系统每次弹「已读取剪贴板」
            InkButton(
                onClick = {
                    val link = clipboard.getText()?.text?.let(::parseProxyLink)
                    if (link != null) fill(link) else pasteFailed = true
                },
                tone = InkButtonTone.Paper,
                compact = true,
            ) { Text(stringResource(R.string.settings_proxy_paste)) }
            if (savedPassword) {
                InkButton(onClick = { clearPassword = true; password = ""; probe = null }, tone = InkButtonTone.Paper, compact = true) {
                    Text(stringResource(R.string.settings_proxy_clear_password))
                }
            }
        }
        when (val result = probe) {
            is ProxyProbe.Ok -> Notice(stringResource(R.string.settings_proxy_test_ok, result.millis), tone = NoticeTone.Info)
            is ProxyProbe.Failed -> Notice(stringResource(R.string.settings_proxy_test_failed, result.reason), tone = NoticeTone.Error)
            null -> Unit
        }
        if (pasteFailed) Notice(stringResource(R.string.settings_proxy_paste_none), tone = NoticeTone.Warn)
        Text(
            stringResource(R.string.settings_proxy_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
