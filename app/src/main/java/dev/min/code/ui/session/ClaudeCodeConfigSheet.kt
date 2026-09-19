package dev.min.code.ui.session

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeCodeConfigStore
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.MCP_TYPE_HTTP
import dev.min.code.core.claudecode.MCP_TYPE_SSE
import dev.min.code.core.claudecode.MCP_TYPE_STDIO
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete02

/**
 * 交互式斜杠命令的原生面板。
 *
 * `/mcp`、`/agents`、`/memory`、`/config`、`/permissions`、`/hooks` 在桌面端都是 TUI 里的
 * 交互式编辑器，在无头模式下压根渲染不出来。但它们改的全都是 Rootfs 里的几个文件
 * （见 [ClaudeCodeConfigStore]），所以这里用原生表单直接改那些文件 ——
 * 功能是等价的，手机上还比 TUI 好用。
 *
 * **改动何时生效**：MCP 服务器、agent 定义、settings.json 都是 CLI **启动时**读的，
 * 所以面板上明说"下次开会话生效"，而不是让用户改完发现没反应。CLAUDE.md 是每轮读的，
 * 改完立刻生效。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ClaudeCodeConfigSheet(
    command: LocalSlash,
    vm: ClaudeCodeVM,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    InkSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (command) {
                LocalSlash.MCP -> McpSection(vm)
                LocalSlash.AGENTS -> AgentsSection(vm)
                // /init 是发给 CLI 的一条消息，发完就该回到会话看它干活，所以顺手关面板
                LocalSlash.MEMORY -> MemorySection(vm, onRunInit = { vm.runInit(); onDismiss() })
                LocalSlash.CONFIG -> ConfigSection(vm)
                LocalSlash.HOOKS -> PermissionsSection(vm)
                // 会话类命令走 ClaudeCodeSettingsSheet，不该走到这里
                else -> Text(stringResource(R.string.config_unsupported))
            }
        }
    }
}

/** 题跋 + 一行说明 */
@Composable
private fun SectionHeader(title: String, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(title)
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 列表 ↔ 编辑器之间的切换：落墨进、飞白出。
 *
 * 编辑器退场那几百毫秒里 `editing` 已经是 null 了，它还得把最后一份草稿画完，
 * 所以这里记住最后一份非空值；它是普通对象不是 state，不会引起额外重组。
 */
@Composable
private fun <T : Any> rememberLastDraft(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return value ?: (holder[0] as T?)
}

/** 列表页与编辑页之间的切换动画壳 */
@Composable
private fun EditorSwitch(
    editing: Boolean,
    editor: @Composable () -> Unit,
    list: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = editing,
        transitionSpec = { InkMotion.enter togetherWith InkMotion.exit },
        label = "editorSwitch",
    ) { isEditing ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isEditing) editor() else list()
        }
    }
}

/** 一行 删除 图标：删除是判定，朱砂色 */
@Composable
private fun DeleteButton(onClick: () -> Unit) {
    InkIconButton(
        icon = HugeIcons.Delete02,
        contentDescription = stringResource(R.string.common_delete),
        onClick = onClick,
        tint = MaterialTheme.sea.vermilion,
        size = 36.dp,
        iconSize = 16.dp,
    )
}

// ---------------------------------------------------------------------------
// /mcp
// ---------------------------------------------------------------------------

@Composable
private fun McpSection(vm: ClaudeCodeVM) {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<ClaudeCodeConfigStore.McpServer>>(emptyList()) }
    var editing by remember { mutableStateOf<ClaudeCodeConfigStore.McpServer?>(null) }
    var editingOriginalName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { servers = vm.loadMcpServers() }

    val draft = rememberLastDraft(editing)
    EditorSwitch(
        editing = editing != null,
        editor = {
            val server = draft ?: return@EditorSwitch
            McpEditor(
                server = server,
                onChange = { editing = it },
                onCancel = { editing = null },
                onSave = {
                    scope.launch {
                        vm.saveMcpServer(server, editingOriginalName)
                        servers = vm.loadMcpServers()
                        editing = null
                    }
                },
            )
        },
        list = {
            SectionHeader(stringResource(R.string.config_mcp_title), stringResource(R.string.config_mcp_hint))
            if (servers.isEmpty()) {
                Text(
                    stringResource(R.string.config_mcp_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            servers.forEach { server ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editingOriginalName = server.name
                            editing = server
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${server.type} · ${server.summary}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetbrainsMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DeleteButton(
                        onClick = {
                            scope.launch {
                                vm.deleteMcpServer(server.name)
                                servers = vm.loadMcpServers()
                            }
                        },
                    )
                }
                InkDivider()
            }
            InkButton(
                onClick = {
                    editingOriginalName = null
                    editing = ClaudeCodeConfigStore.McpServer(name = "")
                },
                tone = InkButtonTone.Paper,
                icon = HugeIcons.Add01,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.config_mcp_add)) }
        },
    )
}

@Composable
private fun McpEditor(
    server: ClaudeCodeConfigStore.McpServer,
    onChange: (ClaudeCodeConfigStore.McpServer) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    SectionHeader(stringResource(R.string.config_mcp_edit_title), stringResource(R.string.config_mcp_edit_hint))
    InkTextField(
        value = server.name,
        onValueChange = { onChange(server.copy(name = it)) },
        label = stringResource(R.string.config_name),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(MCP_TYPE_STDIO, MCP_TYPE_HTTP, MCP_TYPE_SSE).forEach { type ->
            InkChip(
                label = type,
                selected = server.type == type,
                onClick = { onChange(server.copy(type = type)) },
                monospace = true,
            )
        }
    }
    if (server.type == MCP_TYPE_STDIO) {
        InkTextField(
            value = server.command,
            onValueChange = { onChange(server.copy(command = it)) },
            label = stringResource(R.string.config_mcp_command),
            placeholder = "npx",
            singleLine = true,
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        InkTextField(
            // 参数按空格拆。带空格的单个参数确实表达不了，但 MCP 服务器的命令行
            // 几乎都是 `-y @scope/pkg` 这种形状；真需要引号的场景让用户去改 .claude.json
            value = server.args.joinToString(" "),
            onValueChange = { onChange(server.copy(args = it.split(' ').filter(String::isNotBlank))) },
            label = stringResource(R.string.config_mcp_args),
            placeholder = "-y @modelcontextprotocol/server-filesystem /workspace",
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        InkTextField(
            value = server.env.entries.joinToString("\n") { "${it.key}=${it.value}" },
            onValueChange = { onChange(server.copy(env = parseKeyValueLines(it))) },
            label = stringResource(R.string.config_mcp_env),
            monospace = true,
            minHeight = 80.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        InkTextField(
            value = server.url,
            onValueChange = { onChange(server.copy(url = it)) },
            label = stringResource(R.string.config_mcp_url),
            placeholder = "https://example.com/mcp",
            singleLine = true,
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        InkTextField(
            value = server.headers.entries.joinToString("\n") { "${it.key}=${it.value}" },
            onValueChange = { onChange(server.copy(headers = parseKeyValueLines(it))) },
            label = stringResource(R.string.config_mcp_headers),
            monospace = true,
            minHeight = 80.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkTextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_cancel)) }
        InkButton(
            onClick = onSave,
            enabled = server.name.isNotBlank() &&
                (if (server.isRemote) server.url.isNotBlank() else server.command.isNotBlank()),
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.common_save)) }
    }
}

// ---------------------------------------------------------------------------
// /agents
// ---------------------------------------------------------------------------

@Composable
private fun AgentsSection(vm: ClaudeCodeVM) {
    val scope = rememberCoroutineScope()
    var agents by remember { mutableStateOf<List<ClaudeCodeConfigStore.AgentDefinition>>(emptyList()) }
    var editing by remember { mutableStateOf<ClaudeCodeConfigStore.AgentDefinition?>(null) }

    LaunchedEffect(Unit) { agents = vm.listAgents() }

    val draft = rememberLastDraft(editing)
    EditorSwitch(
        editing = editing != null,
        editor = {
            val agent = draft ?: return@EditorSwitch
            AgentEditor(
                draft = agent,
                onChange = { editing = it },
                onCancel = { editing = null },
                onSave = {
                    scope.launch {
                        vm.saveAgent(agent)
                        agents = vm.listAgents()
                        editing = null
                    }
                },
            )
        },
        list = {
            SectionHeader(stringResource(R.string.config_agent_title), stringResource(R.string.config_agent_hint))
            if (agents.isEmpty()) {
                Text(
                    stringResource(R.string.config_agent_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                agents.forEach { agent ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editing = agent }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                // 用户级不加后缀：那是默认的那一档，标出来只是噪声
                                if (agent.userScope) {
                                    agent.name
                                } else {
                                    stringResource(R.string.config_agent_project, agent.name)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                agent.description.ifBlank { agent.fileName },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DeleteButton(
                            onClick = {
                                scope.launch {
                                    vm.deleteAgent(agent)
                                    agents = vm.listAgents()
                                }
                            },
                        )
                    }
                    InkDivider()
                }
            }
            InkButton(
                onClick = {
                    editing = ClaudeCodeConfigStore.AgentDefinition(fileName = "", name = "")
                },
                tone = InkButtonTone.Paper,
                icon = HugeIcons.Add01,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.config_agent_add)) }
        },
    )
}

@Composable
private fun AgentEditor(
    draft: ClaudeCodeConfigStore.AgentDefinition,
    onChange: (ClaudeCodeConfigStore.AgentDefinition) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    SectionHeader(stringResource(R.string.config_agent_edit_title), stringResource(R.string.config_agent_edit_hint))
    InkTextField(
        value = draft.name,
        onValueChange = { onChange(draft.copy(name = it)) },
        label = stringResource(R.string.config_name),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    InkTextField(
        value = draft.description,
        onValueChange = { onChange(draft.copy(description = it)) },
        label = stringResource(R.string.config_agent_description),
        modifier = Modifier.fillMaxWidth(),
    )
    InkTextField(
        value = draft.tools,
        onValueChange = { onChange(draft.copy(tools = it)) },
        label = stringResource(R.string.config_agent_tools),
        singleLine = true,
        monospace = true,
        modifier = Modifier.fillMaxWidth(),
    )
    InkTextField(
        value = draft.model,
        onValueChange = { onChange(draft.copy(model = it)) },
        label = stringResource(R.string.config_agent_model),
        placeholder = "sonnet / opus / haiku",
        singleLine = true,
        monospace = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkChip(
            label = stringResource(R.string.config_agent_scope_user),
            selected = draft.userScope,
            onClick = { onChange(draft.copy(userScope = true)) },
        )
        InkChip(
            label = stringResource(R.string.config_agent_scope_project),
            selected = !draft.userScope,
            onClick = { onChange(draft.copy(userScope = false)) },
        )
    }
    InkTextField(
        value = draft.body,
        onValueChange = { onChange(draft.copy(body = it)) },
        label = stringResource(R.string.config_agent_prompt),
        minHeight = 160.dp,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkTextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_cancel)) }
        InkButton(
            onClick = onSave,
            enabled = draft.name.isNotBlank(),
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.common_save)) }
    }
}

// ---------------------------------------------------------------------------
// /memory
// ---------------------------------------------------------------------------

@Composable
private fun MemorySection(vm: ClaudeCodeVM, onRunInit: () -> Unit) {
    val scope = rememberCoroutineScope()
    var userScope by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var hasProject by remember { mutableStateOf(true) }

    LaunchedEffect(userScope) {
        text = vm.loadMemory(userScope)
        saved = false
    }
    LaunchedEffect(Unit) { hasProject = vm.hasProjectMemory() }

    SectionHeader(
        stringResource(R.string.config_memory_title),
        // 这一条和其它几个不同：CLAUDE.md 是每轮都读的，改完立刻生效
        stringResource(R.string.config_memory_hint),
    )
    // 官方对 CLAUDE.md 的定位就是"上下文"：每轮都读，所以写在这里的东西不用每条消息重复。
    // 最佳实践（best-practices / memory 文档）浓缩成一行，比链接管用
    Text(
        stringResource(R.string.config_memory_tip),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // 空项目最省事的起点是让 CLI 自己扫一遍（/init 是 prompt 型命令，无头模式照样能跑）。
    // 已经有了就降成文字按钮 —— /init 对已有文件会提改进建议，而不是覆盖
    if (!hasProject && !userScope) {
        InkButton(onClick = onRunInit, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.config_memory_init))
        }
    } else if (!userScope) {
        InkTextButton(onClick = onRunInit, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.config_memory_init_existing))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkChip(label = stringResource(R.string.config_memory_scope_project), selected = !userScope, onClick = { userScope = false })
        InkChip(label = stringResource(R.string.config_memory_scope_user), selected = userScope, onClick = { userScope = true })
    }
    InkTextField(
        value = text,
        onValueChange = { text = it; saved = false },
        modifier = Modifier.fillMaxWidth(),
        // 8 行起、16 行封顶：再多就在框内滚，别把整张 sheet 撑成一条长卷
        minLines = 8,
        maxLines = 16,
        minHeight = 200.dp,
        monospace = true,
        textStyle = MaterialTheme.typography.bodySmall,
    )
    InkButton(
        onClick = {
            scope.launch {
                vm.saveMemory(userScope, text)
                saved = true
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(if (saved) R.string.common_saved else R.string.common_save)) }
}

// ---------------------------------------------------------------------------
// /config
// ---------------------------------------------------------------------------

@Composable
private fun ConfigSection(vm: ClaudeCodeVM) {
    val scope = rememberCoroutineScope()
    var outputStyle by remember { mutableStateOf("") }
    var statusLine by remember { mutableStateOf("") }
    // null = 不设上限（删掉 maxEffortLevel）；非空必须是 low…max
    var maxEffortLevel by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val settings = vm.loadSettings()
        outputStyle = settings["outputStyle"].orEmpty()
        statusLine = settings["statusLine"].orEmpty()
        maxEffortLevel = settings["maxEffortLevel"]
            ?.takeIf { it in ClaudeCodeManager.EFFORT_LEVELS }
        loaded = true
    }

    SectionHeader(stringResource(R.string.config_cli_title), stringResource(R.string.config_cli_hint))
    InkTextField(
        value = outputStyle,
        onValueChange = { outputStyle = it; saved = false },
        label = stringResource(R.string.config_cli_output_style),
        placeholder = "default / Explanatory / Learning",
        singleLine = true,
        monospace = true,
        enabled = loaded,
        modifier = Modifier.fillMaxWidth(),
    )
    InkTextField(
        value = statusLine,
        onValueChange = { statusLine = it; saved = false },
        label = stringResource(R.string.config_cli_status_line),
        placeholder = stringResource(R.string.config_cli_status_line_placeholder),
        singleLine = true,
        monospace = true,
        enabled = loaded,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.config_cli_max_effort),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        stringResource(R.string.config_cli_max_effort_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        InkChip(
            label = stringResource(R.string.config_cli_effort_unlimited),
            selected = maxEffortLevel == null,
            onClick = { maxEffortLevel = null; saved = false },
            enabled = loaded,
        )
        ClaudeCodeManager.EFFORT_LEVELS.forEach { level ->
            InkChip(
                label = level,
                selected = maxEffortLevel == level,
                onClick = { maxEffortLevel = level; saved = false },
                enabled = loaded,
            )
        }
    }
    Text(
        stringResource(R.string.config_cli_hot_switch_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    InkButton(
        onClick = {
            scope.launch {
                vm.saveSimpleSettings(
                    outputStyle = outputStyle,
                    statusLine = statusLine,
                    maxEffortLevel = maxEffortLevel.orEmpty(),
                )
                saved = true
            }
        },
        enabled = loaded,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(if (saved) R.string.common_saved else R.string.common_save)) }
}

// ---------------------------------------------------------------------------
// /permissions
// ---------------------------------------------------------------------------

@Composable
private fun PermissionsSection(vm: ClaudeCodeVM) {
    val scope = rememberCoroutineScope()
    var bucket by remember { mutableStateOf("allow") }
    var rules by remember { mutableStateOf<List<String>>(emptyList()) }
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(bucket) { rules = vm.loadPermissionRules(bucket) }

    SectionHeader(
        stringResource(R.string.config_perm_title),
        stringResource(R.string.config_perm_hint),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // key 是写进 settings.json 的桶名，不跟着语言走；只有 label 是给人看的
        listOf(
            "allow" to stringResource(R.string.config_perm_allow),
            "ask" to stringResource(R.string.config_perm_ask),
            "deny" to stringResource(R.string.config_perm_deny),
        ).forEach { (key, label) ->
            InkChip(
                label = label,
                selected = bucket == key,
                onClick = { bucket = key },
            )
        }
    }
    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        rules.forEach { rule ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rule,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                DeleteButton(
                    onClick = {
                        val next = rules - rule
                        rules = next
                        scope.launch { vm.savePermissionRules(bucket, next) }
                    },
                )
            }
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        InkTextField(
            value = draft,
            onValueChange = { draft = it },
            label = stringResource(R.string.config_perm_add_label),
            singleLine = true,
            monospace = true,
            modifier = Modifier.weight(1f),
        )
        Box {
            InkButton(
                onClick = {
                    val next = rules + draft.trim()
                    rules = next
                    draft = ""
                    scope.launch { vm.savePermissionRules(bucket, next) }
                },
                enabled = draft.isNotBlank() && draft.trim() !in rules,
            ) { Text(stringResource(R.string.config_perm_add)) }
        }
    }
}

/** `KEY=VALUE` 每行一条；没有 `=` 的行忽略，空 key 忽略 */
internal fun parseKeyValueLines(text: String): Map<String, String> =
    text.lines().mapNotNull { line ->
        val idx = line.indexOf('=')
        if (idx <= 0) return@mapNotNull null
        val key = line.substring(0, idx).trim()
        if (key.isEmpty()) null else key to line.substring(idx + 1).trim()
    }.toMap()
