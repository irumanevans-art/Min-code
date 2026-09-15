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
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.network.ListeningPort
import dev.min.code.core.network.NetworkSnapshot
import dev.min.code.core.service.LocalService
import dev.min.code.core.service.LocalServiceStatus
import dev.min.code.core.service.LocalServiceStopReason
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.PaperCard
import dev.min.code.ui.components.Seal
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import dev.min.code.util.LocalUrls

/**
 * 进程表面板：只读同一张 [LocalService] 表 +（可读时）监听端口。
 * 不是第二种启动仪式——没有「填命令启动」大表单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClaudeCodeRuntimeSheet(
    snapshot: NetworkSnapshot?,
    loading: Boolean,
    services: List<LocalService>,
    expandedLogId: String?,
    logOf: (String) -> String,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onStopService: (String) -> Unit,
    onToggleLog: (String) -> Unit,
    onOpenPreview: (String) -> Unit,
) {
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
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Seal()
                    Text(
                        stringResource(R.string.runtime_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                InkTextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.runtime_refresh))
                }
            }

            Text(
                stringResource(R.string.runtime_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle(stringResource(R.string.runtime_section_services))
            val live = services.filter {
                it.status == LocalServiceStatus.Running || it.status == LocalServiceStatus.Starting
            }
            val finished = services.filter {
                it.status == LocalServiceStatus.Exited || it.status == LocalServiceStatus.Failed
            }
            if (live.isEmpty() && finished.isEmpty()) {
                PaperCard {
                    Text(
                        stringResource(R.string.runtime_services_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                live.forEach { svc ->
                    ServiceRow(
                        service = svc,
                        logExpanded = expandedLogId == svc.id,
                        logText = if (expandedLogId == svc.id) logOf(svc.id) else svc.logTail,
                        onStop = { onStopService(svc.id) },
                        onToggleLog = { onToggleLog(svc.id) },
                        onOpen = { port -> onOpenPreview(LocalUrls.loopbackUrl(port)) },
                    )
                }
                finished.takeLast(8).forEach { svc ->
                    ServiceRow(
                        service = svc,
                        logExpanded = expandedLogId == svc.id,
                        logText = if (expandedLogId == svc.id) logOf(svc.id) else svc.logTail,
                        onStop = null,
                        onToggleLog = { onToggleLog(svc.id) },
                        onOpen = null,
                    )
                }
            }

            SectionTitle(stringResource(R.string.runtime_section_ports))
            when {
                loading && snapshot == null -> {
                    Text(
                        stringResource(R.string.runtime_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                snapshot?.portsReadable == false -> {
                    Notice(
                        text = stringResource(R.string.runtime_ports_unreadable),
                        tone = NoticeTone.Info,
                    )
                }
                snapshot == null || snapshot.ports.isEmpty() -> {
                    Text(
                        stringResource(R.string.runtime_ports_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    snapshot.ports.forEach { port ->
                        PortRow(
                            port = port,
                            onOpen = { onOpenPreview(LocalUrls.loopbackUrl(port.port)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceRow(
    service: LocalService,
    logExpanded: Boolean,
    logText: String,
    onStop: (() -> Unit)?,
    onToggleLog: () -> Unit,
    onOpen: ((Int) -> Unit)?,
) {
    PaperCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                service.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                service.command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusLabel(service),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetbrainsMono,
                color = when (service.status) {
                    LocalServiceStatus.Failed -> MaterialTheme.sea.vermilion
                    LocalServiceStatus.Running -> MaterialTheme.sea.seaDeep
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val port = service.port
                if (onOpen != null && port != null &&
                    (service.status == LocalServiceStatus.Running || service.status == LocalServiceStatus.Starting)
                ) {
                    InkButton(onClick = { onOpen(port) }, tone = InkButtonTone.Paper, compact = true) {
                        Text(stringResource(R.string.runtime_open))
                    }
                }
                InkButton(onClick = onToggleLog, tone = InkButtonTone.Paper, compact = true) {
                    Text(stringResource(R.string.runtime_log))
                }
                if (onStop != null) {
                    InkButton(onClick = onStop, tone = InkButtonTone.Vermilion, compact = true) {
                        Text(stringResource(R.string.runtime_svc_stop))
                    }
                }
            }
            if (logExpanded && logText.isNotBlank()) {
                Text(
                    logText.takeLast(4000),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PortRow(port: ListeningPort, onOpen: () -> Unit) {
    PaperCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${port.proto} :${port.port}  ${port.localHost}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            InkButton(onClick = onOpen, tone = InkButtonTone.Paper, compact = true) {
                Text(stringResource(R.string.runtime_open))
            }
        }
    }
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
    val err = service.lastError
    return listOfNotNull(base, reason, err).joinToString(" · ")
}
