package dev.min.code.ui.settings

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.ui.components.*
import dev.min.agent.ext.config.ConfigSource

@Composable
internal fun NetworkSettingsSection(vm: SettingsVM) {
    val view by vm.network.collectAsStateWithLifecycle()
    val error by vm.networkError.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.loadNetwork() }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val network = view ?: return
    val settings = network.settings
    SettingRow(
        title = stringResource(R.string.network_enabled),
        subtitle = stringResource(R.string.network_limit_hint),
        onClick = { if (!settings.enabledManaged) vm.setNetworkEnabled(!settings.enabled) },
        trailing = { InkSwitch(checked = settings.enabled, enabled = !settings.enabledManaged, onCheckedChange = vm::setNetworkEnabled) },
    )
    if (settings.enabledManaged) Text(stringResource(R.string.network_managed_readonly), style = MaterialTheme.typography.labelSmall)
    SettingRow(title = stringResource(R.string.network_managed_only),
        subtitle = stringResource(if (settings.allowManagedDomainsOnly) R.string.network_managed_on else R.string.network_managed_off))
    for (denied in listOf(false, true)) {
        SectionTitle(stringResource(if (denied) R.string.network_denied else R.string.network_allowed))
        val domains = network.domains.filter { it.denied == denied }
        if (domains.isEmpty()) Text(stringResource(R.string.network_empty), style = MaterialTheme.typography.bodySmall)
        for (entry in domains) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(entry.domain)
                Text(entry.source.label, style = MaterialTheme.typography.labelSmall)
            }
            if (entry.source == ConfigSource.Managed) Text(stringResource(R.string.network_managed_readonly), style = MaterialTheme.typography.labelSmall)
            else InkTextButton(onClick = { vm.removeNetworkDomain(entry) }) { Text(stringResource(R.string.network_remove)) }
        }
    }
}
