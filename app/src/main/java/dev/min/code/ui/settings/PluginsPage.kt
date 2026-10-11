package dev.min.code.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.agent.ext.plugins.*
import dev.min.code.R
import dev.min.code.core.engine.min.MinAgentEngine
import dev.min.code.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** 用现有设置控件展示可执行组件；确认之前只读取包，绝不启动 hooks 或 MCP。 */
@Composable
fun PluginsPage(engine: MinAgentEngine = koinInject()) {
    val manager = remember(engine) { engine.pluginMarketplace() }
    val notices by engine.pluginNotices.collectAsState()
    val scope = rememberCoroutineScope()
    var markets by remember { mutableStateOf(emptyList<Marketplace>()) }
    var installed by remember { mutableStateOf(emptyList<InstalledPlugin>()) }
    var catalog by remember { mutableStateOf<MarketplaceCatalog?>(null) }
    var preview by remember { mutableStateOf<PluginInstallPreview?>(null) }
    var installScope by remember { mutableStateOf("user") }
    var source by remember { mutableStateOf("") }
    var warning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingCommand by remember { mutableStateOf<Pair<String, List<PluginCommandRequest>>?>(null) }
    var authUrl by remember { mutableStateOf("") }
    var authHeaders by remember { mutableStateOf("") }
    val latestPreview by rememberUpdatedState(preview)
    DisposableEffect(manager) {
        onDispose { latestPreview?.let { engine.discardPluginPreview(manager, it) } }
    }
    suspend fun reload() { markets = manager.markets(); installed = manager.installed() }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            try { block(); reload() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Plugin operation failed" }
            finally { busy = false }
        }
    }
    fun prepare(id: String) { action {
        val commands = manager.commandsToAccept(id)
        if (commands.isEmpty()) preview = manager.preview(id) else pendingCommand = id to commands
    } }
    LaunchedEffect(manager) { action { reload(); val pending = manager.reconcileSettings(); if (pending.isNotEmpty()) error = pending.joinToString() } }
    val scroll = rememberScrollState()
    Scaffold(topBar = { InkTopBar(title = stringResource(R.string.plugins_title), navigationIcon = { BackButton() }, scrolled = scroll.canScrollBackward) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Notice(it, tone = NoticeTone.Error) }
            notices.forEach { Notice(it) }
            SectionTitle(stringResource(R.string.plugins_markets))
            for (market in markets) {
                SettingRow(title = market.name, subtitle = redactedPluginSource(market.source).toString(), onClick = { action { catalog = manager.browse(market.name) } })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InkTextButton(onClick = { action { catalog = manager.refresh(market.name) } }, enabled = !busy) { Text(stringResource(R.string.plugins_refresh)) }
                    InkTextButton(onClick = { action { manager.removeMarket(market.name); if (catalog?.name == market.name) catalog = null } }, enabled = !busy) { Text(stringResource(R.string.plugins_remove)) }
                }
                SettingRow(title = stringResource(R.string.plugins_auto_update), trailing = { InkSwitch(checked = market.autoUpdate, onCheckedChange = { value -> action { manager.setAutoUpdate(market.name, value) } }) })
                InkDivider()
            }
            InkTextField(source, { source = it }, label = stringResource(R.string.plugins_source), enabled = !busy, maxLines = 3)
            InkTextButton(onClick = { warning = true }, enabled = !busy && source.isNotBlank()) { Text(stringResource(R.string.plugins_add)) }
            InkTextField(authUrl, { authUrl = it }, label = "私有插件源 URL", enabled = !busy, singleLine = true)
            InkTextField(authHeaders, { authHeaders = it }, label = "认证头（JSON，保存后不回显）", enabled = !busy, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            InkTextButton(onClick = { action { engine.savePluginHeaders(authUrl, authHeaders); authHeaders = "" } }, enabled = !busy && authUrl.isNotBlank() && authHeaders.isNotBlank()) { Text("保存认证") }
            catalog?.let { listing ->
                SectionTitle(listing.name)
                for (entry in listing.plugins) {
                    val name = (entry["name"] as kotlinx.serialization.json.JsonPrimitive).content
                    SettingRow(title = name, subtitle = (entry["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                        onClick = { installScope = "user"; prepare("$name@${listing.name}") })
                }
            }
            SectionTitle(stringResource(R.string.plugins_installed))
            for (plugin in installed) {
                SettingRow(title = plugin.id, subtitle = "${plugin.scope} · ${plugin.version}", trailing = { InkSwitch(checked = plugin.enabled, onCheckedChange = { enabled -> action { manager.setEnabled(plugin.id, enabled, plugin.scope) } }) })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InkTextButton(onClick = { action { manager.refresh(plugin.id.substringAfter('@')); installScope = plugin.scope; val commands = manager.commandsToAccept(plugin.id); if (commands.isEmpty()) preview = manager.preview(plugin.id) else pendingCommand = plugin.id to commands } }, enabled = !busy) { Text(stringResource(R.string.plugins_update)) }
                    InkTextButton(onClick = { action { manager.uninstall(plugin.id, plugin.scope) } }, enabled = !busy) { Text(stringResource(R.string.plugins_uninstall)) }
                }
            }
        }
    }
    if (warning) RikkaConfirmDialog(show = true, onDismiss = { warning = false }, title = stringResource(R.string.plugins_add), confirmText = stringResource(R.string.plugins_add), dismissText = stringResource(R.string.plugins_cancel), destructive = false, onConfirm = {
        warning = false; action { manager.add(engine.protectPluginSource(privatePluginSourceInput(source.trim())), confirmed = true); source = "" }
    }) { Column { Text(stringResource(R.string.plugins_warning)); Text(try { redactedPluginSource(privatePluginSourceInput(source.trim())).toString() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "来源格式无效" }) } }
    pendingCommand?.let { (id, commands) ->
        RikkaConfirmDialog(show = true, onDismiss = { pendingCommand = null }, title = "确认插件命令", confirmText = "执行并预览", dismissText = stringResource(R.string.plugins_cancel), destructive = false,
            onConfirm = { pendingCommand = null; action { preview = manager.preview(id, commands.map { it.sha256 }.toSet()) } }) {
            Text(commands.joinToString("\n\n") { "${it.command}\n目标：${redactedPluginCommandTarget(it.target)}" })
        }
    }
    preview?.let { selected ->
        RikkaConfirmDialog(show = true, onDismiss = { action { manager.discard(selected); preview = null } }, title = stringResource(R.string.plugins_confirm), confirmText = stringResource(R.string.plugins_install), dismissText = stringResource(R.string.plugins_cancel), destructive = false, onConfirm = {
            action { try { manager.install(selected, installScope, confirmed = true) } finally { withContext(NonCancellable) { manager.discard(selected) }; preview = null } }
        }) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(selected.componentSummary(), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.plugins_warning))
                Text(stringResource(R.string.plugins_scope))
                Row {
                    for (value in listOf("user", "project", "local")) InkTextButton(onClick = { installScope = value }) { Text(if (installScope == value) "✓ $value" else value) }
                }
            }
        }
    }
}

// 解析器错误可能带完整 JSON；认证输入不能借错误提示再次显示明文。
private fun privatePluginSourceInput(raw: String): kotlinx.serialization.json.JsonObject = try {
    PluginMarketplace.sourceInput(raw)
} catch (e: CancellationException) { throw e }
    catch (_: Exception) { throw IllegalArgumentException("来源格式无效") }
