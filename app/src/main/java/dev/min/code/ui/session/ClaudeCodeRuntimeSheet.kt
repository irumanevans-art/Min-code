package dev.min.code.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.network.AddressKind
import dev.min.code.core.network.ListeningPort
import dev.min.code.core.network.NetAddress
import dev.min.code.core.network.NetworkProbe
import dev.min.code.core.network.NetworkSnapshot
import dev.min.code.core.service.LocalService
import dev.min.code.core.service.LocalServiceStatus
import dev.min.code.core.service.LocalServiceStopReason
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.PaperCard
import dev.min.code.ui.components.Seal
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import dev.min.code.util.openLocalUrl
import dev.min.code.util.writeClipboardText

/**
 * 网络可达性 + 长驻本地服务。
 *
 * 侧栏入口，版式对齐 [ClaudeCodeMaintenanceSheet]：信息在上、动作在下。
 * 解决的是 proot 里「像假电脑」的那几件事——别让模型猜 IP、别把 Bash `&` 当成 systemd。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClaudeCodeRuntimeSheet(
    snapshot: NetworkSnapshot?,
    loading: Boolean,
    services: List<LocalService>,
    starting: Boolean,
    startError: String?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onStartService: (label: String, command: String, cwd: String, port: Int?) -> Unit,
    onStopService: (String) -> Unit,
    onClearStartError: () -> Unit,
) {
    val context = LocalContext.current
    var portText by rememberSaveable { mutableStateOf("8080") }
    var showStartForm by rememberSaveable { mutableStateOf(false) }
    var label by rememberSaveable { mutableStateOf("") }
    var command by rememberSaveable { mutableStateOf("python3 -m http.server 8765") }
    var cwd by rememberSaveable { mutableStateOf("/workspace") }
    var svcPort by rememberSaveable { mutableStateOf("8765") }

    LaunchedEffect(Unit) { onRefresh() }

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
                Text(
                    stringResource(R.string.runtime_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                InkButton(
                    onClick = onRefresh,
                    enabled = !loading,
                    tone = InkButtonTone.Paper,
                    compact = true,
                    busy = loading,
                ) {
                    Text(stringResource(R.string.runtime_refresh))
                }
            }

            Text(
                stringResource(R.string.runtime_intro),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (snapshot != null && snapshot.primaryLanHost == null) {
                Notice(
                    text = stringResource(R.string.runtime_no_lan),
                    tone = NoticeTone.Info,
                )
            }
            if (snapshot != null && !snapshot.portsReadable) {
                Notice(
                    text = stringResource(R.string.runtime_ports_unreadable),
                    tone = NoticeTone.Warn,
                )
            }
            if (startError != null) {
                Notice(
                    text = startError,
                    tone = NoticeTone.Error,
                )
            }

            SectionTitle(stringResource(R.string.runtime_section_addresses))
            PaperCard(
                modifier = Modifier.fillMaxWidth(),
                padding = PaddingValues(12.dp),
            ) {
                val addrs = snapshot?.addresses.orEmpty()
                if (addrs.isEmpty()) {
                    Text(
                        if (loading) stringResource(R.string.runtime_loading)
                        else stringResource(R.string.runtime_addresses_empty),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        addrs.forEach { addr ->
                            AddressRow(
                                addr = addr,
                                onCopy = { context.writeClipboardText(addr.host) },
                            )
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.runtime_section_open))
            PaperCard(
                modifier = Modifier.fillMaxWidth(),
                padding = PaddingValues(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "8080",
                        singleLine = true,
                        label = stringResource(R.string.runtime_port_label),
                        monospace = true,
                    )
                    val port = portText.toIntOrNull()
                    val loopback = port?.let { NetworkProbe.formatUrl("http", "127.0.0.1", it) }
                    val phone = port?.let { p ->
                        snapshot?.primaryLanHost?.let { NetworkProbe.formatUrl("http", it, p) }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        InkButton(
                            onClick = { loopback?.let { context.openLocalUrl(it) } },
                            enabled = loopback != null,
                            tone = InkButtonTone.Ink,
                            compact = true,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.runtime_open_loopback))
                        }
                        InkButton(
                            onClick = { phone?.let { context.writeClipboardText(it) } },
                            enabled = phone != null,
                            tone = InkButtonTone.Paper,
                            compact = true,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.runtime_copy_phone))
                        }
                    }
                    if (phone != null) {
                        Text(
                            phone,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetbrainsMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            SectionTitle(stringResource(R.string.runtime_section_ports))
            PaperCard(
                modifier = Modifier.fillMaxWidth(),
                padding = PaddingValues(12.dp),
            ) {
                val ports = snapshot?.ports.orEmpty()
                when {
                    snapshot == null && loading -> {
                        Text(
                            stringResource(R.string.runtime_loading),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    snapshot != null && !snapshot.portsReadable -> {
                        Text(
                            stringResource(R.string.runtime_ports_unreadable_short),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ports.isEmpty() -> {
                        Text(
                            stringResource(R.string.runtime_ports_empty),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ports.take(40).forEach { p ->
                                PortRow(
                                    port = p,
                                    lanHost = snapshot?.primaryLanHost,
                                    onOpen = {
                                        context.openLocalUrl(
                                            NetworkProbe.formatUrl("http", "127.0.0.1", p.port),
                                        )
                                    },
                                    onCopyLan = { host ->
                                        context.writeClipboardText(
                                            NetworkProbe.formatUrl("http", host, p.port),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.runtime_section_services))
            Text(
                stringResource(R.string.runtime_services_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (services.isEmpty()) {
                PaperCard(
                    modifier = Modifier.fillMaxWidth(),
                    padding = PaddingValues(12.dp),
                ) {
                    Text(
                        stringResource(R.string.runtime_services_empty),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    services.asReversed().forEach { svc ->
                        ServiceRow(
                            service = svc,
                            onStop = { onStopService(svc.id) },
                            onOpen = { port ->
                                context.openLocalUrl(
                                    NetworkProbe.formatUrl("http", "127.0.0.1", port),
                                )
                            },
                            onCopy = { port ->
                                val host = snapshot?.primaryLanHost
                                if (host != null) {
                                    context.writeClipboardText(
                                        NetworkProbe.formatUrl("http", host, port),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            if (!showStartForm) {
                InkButton(
                    onClick = {
                        onClearStartError()
                        showStartForm = true
                    },
                    tone = InkButtonTone.Ink,
                    compact = true,
                ) {
                    Text(stringResource(R.string.runtime_start_service))
                }
            } else {
                PaperCard(
                    modifier = Modifier.fillMaxWidth(),
                    padding = PaddingValues(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InkTextField(
                            value = label,
                            onValueChange = { label = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = stringResource(R.string.runtime_svc_label),
                            placeholder = "http.server",
                        )
                        InkTextField(
                            value = command,
                            onValueChange = { command = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = stringResource(R.string.runtime_svc_command),
                            monospace = true,
                        )
                        InkTextField(
                            value = cwd,
                            onValueChange = { cwd = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = stringResource(R.string.runtime_svc_cwd),
                            monospace = true,
                        )
                        InkTextField(
                            value = svcPort,
                            onValueChange = { svcPort = it.filter { ch -> ch.isDigit() }.take(5) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = stringResource(R.string.runtime_svc_port_optional),
                            monospace = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InkButton(
                                onClick = {
                                    onClearStartError()
                                    onStartService(
                                        label,
                                        command,
                                        cwd.ifBlank { "/workspace" },
                                        svcPort.toIntOrNull(),
                                    )
                                },
                                enabled = !starting && command.isNotBlank(),
                                tone = InkButtonTone.Ink,
                                compact = true,
                                busy = starting,
                            ) {
                                Text(stringResource(R.string.runtime_svc_launch))
                            }
                            InkButton(
                                onClick = { showStartForm = false },
                                tone = InkButtonTone.Paper,
                                compact = true,
                            ) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.runtime_section_notes))
            PaperCard(
                modifier = Modifier.fillMaxWidth(),
                padding = PaddingValues(12.dp),
            ) {
                Text(
                    stringResource(R.string.runtime_notes_body),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddressRow(addr: NetAddress, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (addr.primary) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.sea.seaDeep),
            )
        } else {
            Spacer(Modifier.size(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                addr.host,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetbrainsMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                kindLabel(addr.kind) + " · " + addr.iface +
                    if (addr.primary) " · " + stringResource(R.string.runtime_primary_lan) else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        InkButton(onClick = onCopy, tone = InkButtonTone.Paper, compact = true) {
            Text(stringResource(R.string.runtime_copy))
        }
    }
}

@Composable
private fun PortRow(
    port: ListeningPort,
    lanHost: String?,
    onOpen: () -> Unit,
    onCopyLan: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                ":${port.port}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetbrainsMono,
            )
            Text(
                "${port.proto} · ${port.localHost}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = JetbrainsMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        InkButton(onClick = onOpen, tone = InkButtonTone.Paper, compact = true) {
            Text(stringResource(R.string.runtime_open))
        }
        if (lanHost != null) {
            InkButton(
                onClick = { onCopyLan(lanHost) },
                tone = InkButtonTone.Paper,
                compact = true,
            ) {
                Text(stringResource(R.string.runtime_copy_lan))
            }
        }
    }
}

@Composable
private fun ServiceRow(
    service: LocalService,
    onStop: () -> Unit,
    onOpen: (Int) -> Unit,
    onCopy: (Int) -> Unit,
) {
    val running = service.status == LocalServiceStatus.Running ||
        service.status == LocalServiceStatus.Starting
    PaperCard(
        modifier = Modifier.fillMaxWidth(),
        padding = PaddingValues(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    service.label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusLabel(service),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (service.status) {
                        LocalServiceStatus.Failed -> MaterialTheme.sea.vermilion
                        LocalServiceStatus.Running -> MaterialTheme.sea.seaDeep
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                service.command,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            service.lastError?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.sea.vermilion,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    InkButton(onClick = onStop, tone = InkButtonTone.Paper, compact = true) {
                        Text(stringResource(R.string.runtime_svc_stop))
                    }
                }
                service.port?.let { p ->
                    InkButton(
                        onClick = { onOpen(p) },
                        tone = InkButtonTone.Paper,
                        compact = true,
                    ) {
                        Text(stringResource(R.string.runtime_open))
                    }
                    InkButton(
                        onClick = { onCopy(p) },
                        tone = InkButtonTone.Paper,
                        compact = true,
                    ) {
                        Text(stringResource(R.string.runtime_copy_phone))
                    }
                }
            }
        }
    }
}

@Composable
private fun kindLabel(kind: AddressKind): String = when (kind) {
    AddressKind.Loopback -> stringResource(R.string.runtime_kind_loopback)
    AddressKind.LinkLocal -> stringResource(R.string.runtime_kind_linklocal)
    AddressKind.Lan -> stringResource(R.string.runtime_kind_lan)
    AddressKind.Other -> stringResource(R.string.runtime_kind_other)
}

@Composable
private fun statusLabel(service: LocalService): String {
    val base = when (service.status) {
        LocalServiceStatus.Starting -> stringResource(R.string.runtime_status_starting)
        LocalServiceStatus.Running -> stringResource(R.string.runtime_status_running)
        LocalServiceStatus.Exited -> stringResource(R.string.runtime_status_exited)
        LocalServiceStatus.Failed -> stringResource(R.string.runtime_status_failed)
    }
    val reason = when (service.stopReason) {
        LocalServiceStopReason.UserStop -> stringResource(R.string.runtime_reason_user)
        LocalServiceStopReason.StopAll -> stringResource(R.string.runtime_reason_stop_all)
        LocalServiceStopReason.Crash -> stringResource(R.string.runtime_reason_crash)
        LocalServiceStopReason.StartFailed -> stringResource(R.string.runtime_reason_start)
        LocalServiceStopReason.PortBusy -> stringResource(R.string.runtime_reason_port)
        null -> null
    }
    val code = service.exitCode?.let { " · exit $it" }.orEmpty()
    return if (reason != null) "$base · $reason$code" else base + code
}
