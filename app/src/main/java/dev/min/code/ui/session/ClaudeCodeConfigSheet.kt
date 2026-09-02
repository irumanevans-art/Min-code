package dev.min.code.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete02
import dev.min.code.core.claudecode.ClaudeCodeConfigStore
import dev.min.code.core.claudecode.MCP_TYPE_HTTP
import dev.min.code.core.claudecode.MCP_TYPE_SSE
import dev.min.code.core.claudecode.MCP_TYPE_STDIO

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClaudeCodeConfigSheet(
    command: LocalSlash,
    vm: ClaudeCodeVM,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                LocalSlash.MEMORY -> MemorySection(vm)
                LocalSlash.CONFIG -> ConfigSection(vm)
                LocalSlash.HOOKS -> PermissionsSection(vm)
                // 会话类命令走 ClaudeCodeSettingsSheet，不该走到这里
                else -> Text("该命令由会话设置面板处理")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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

    val draft = editing
    if (draft != null) {
        McpEditor(
            server = draft,
            onChange = { editing = it },
            onCancel = { editing = null },
            onSave = {
                scope.launch {
                    vm.saveMcpServer(draft, editingOriginalName)
                    servers = vm.loadMcpServers()
                    editing = null
                }
            },
        )
        return
    }

    SectionHeader("MCP 服务器", "写入 Rootfs 的 /root/.claude.json，下次开会话生效")
    if (servers.isEmpty()) {
        Text(
            "还没有配置任何 MCP 服务器",
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = {
                scope.launch {
                    vm.deleteMcpServer(server.name)
                    servers = vm.loadMcpServers()
                }
            }) {
                Icon(HugeIcons.Delete02, "删除", Modifier.size(16.dp))
            }
        }
        HorizontalDivider()
    }
    OutlinedButton(
        onClick = {
            editingOriginalName = null
            editing = ClaudeCodeConfigStore.McpServer(name = "")
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(HugeIcons.Add01, null, Modifier.size(15.dp))
        Text("新增服务器", Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun McpEditor(
    server: ClaudeCodeConfigStore.McpServer,
    onChange: (ClaudeCodeConfigStore.McpServer) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    SectionHeader("编辑 MCP 服务器", "stdio 在 Rootfs 里起进程；http/sse 连远端地址")
    OutlinedTextField(
        value = server.name,
        onValueChange = { onChange(server.copy(name = it)) },
        label = { Text("名称") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(MCP_TYPE_STDIO, MCP_TYPE_HTTP, MCP_TYPE_SSE).forEach { type ->
            FilterChip(
                selected = server.type == type,
                onClick = { onChange(server.copy(type = type)) },
                label = { Text(type) },
            )
        }
    }
    if (server.type == MCP_TYPE_STDIO) {
        OutlinedTextField(
            value = server.command,
            onValueChange = { onChange(server.copy(command = it)) },
            label = { Text("命令") },
            placeholder = { Text("npx") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            // 参数按空格拆。带空格的单个参数确实表达不了，但 MCP 服务器的命令行
            // 几乎都是 `-y @scope/pkg` 这种形状；真需要引号的场景让用户去改 .claude.json
            value = server.args.joinToString(" "),
            onValueChange = { onChange(server.copy(args = it.split(' ').filter(String::isNotBlank))) },
            label = { Text("参数（空格分隔）") },
            placeholder = { Text("-y @modelcontextprotocol/server-filesystem /workspace") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = server.env.entries.joinToString("\n") { "${it.key}=${it.value}" },
            onValueChange = { onChange(server.copy(env = parseKeyValueLines(it))) },
            label = { Text("环境变量（每行 KEY=VALUE）") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
        )
    } else {
        OutlinedTextField(
            value = server.url,
            onValueChange = { onChange(server.copy(url = it)) },
            label = { Text("地址") },
            placeholder = { Text("https://example.com/mcp") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = server.headers.entries.joinToString("\n") { "${it.key}=${it.value}" },
            onValueChange = { onChange(server.copy(headers = parseKeyValueLines(it))) },
            label = { Text("请求头（每行 KEY=VALUE）") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(
            onClick = onSave,
            enabled = server.name.isNotBlank() &&
                (if (server.isRemote) server.url.isNotBlank() else server.command.isNotBlank()),
            modifier = Modifier.weight(1f),
        ) { Text("保存") }
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

    val draft = editing
    if (draft != null) {
        SectionHeader("编辑子 agent", "写入 .claude/agents/*.md，下次开会话生效")
        OutlinedTextField(
            value = draft.name,
            onValueChange = { editing = draft.copy(name = it) },
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.description,
            onValueChange = { editing = draft.copy(description = it) },
            label = { Text("何时使用（description）") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.tools,
            onValueChange = { editing = draft.copy(tools = it) },
            label = { Text("可用工具（逗号分隔，留空=全部）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.model,
            onValueChange = { editing = draft.copy(model = it) },
            label = { Text("模型（留空=继承）") },
            placeholder = { Text("sonnet / opus / haiku") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = draft.userScope,
                onClick = { editing = draft.copy(userScope = true) },
                label = { Text("用户级") },
            )
            FilterChip(
                selected = !draft.userScope,
                onClick = { editing = draft.copy(userScope = false) },
                label = { Text("项目级") },
            )
        }
        OutlinedTextField(
            value = draft.body,
            onValueChange = { editing = draft.copy(body = it) },
            label = { Text("系统提示词") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { editing = null }, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = {
                    scope.launch {
                        vm.saveAgent(draft)
                        agents = vm.listAgents()
                        editing = null
                    }
                },
                enabled = draft.name.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("保存") }
        }
        return
    }

    SectionHeader("子 agent", "用户级写进 Rootfs 的 ~/.claude/agents，项目级写进 /workspace/.claude/agents")
    if (agents.isEmpty()) {
        Text(
            "还没有自定义子 agent",
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
                        "${agent.name}${if (agent.userScope) "" else " · 项目"}",
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
                IconButton(onClick = {
                    scope.launch {
                        vm.deleteAgent(agent)
                        agents = vm.listAgents()
                    }
                }) {
                    Icon(HugeIcons.Delete02, "删除", Modifier.size(16.dp))
                }
            }
            HorizontalDivider()
        }
    }
    OutlinedButton(
        onClick = {
            editing = ClaudeCodeConfigStore.AgentDefinition(fileName = "", name = "")
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(HugeIcons.Add01, null, Modifier.size(15.dp))
        Text("新增子 agent", Modifier.padding(start = 8.dp))
    }
}

// ---------------------------------------------------------------------------
// /memory
// ---------------------------------------------------------------------------

@Composable
private fun MemorySection(vm: ClaudeCodeVM) {
    val scope = rememberCoroutineScope()
    var userScope by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(userScope) {
        text = vm.loadMemory(userScope)
        saved = false
    }

    SectionHeader(
        "记忆（CLAUDE.md）",
        // 这一条和其它几个不同：CLAUDE.md 是每轮都读的，改完立刻生效
        "项目级 = /workspace/CLAUDE.md，用户级 = ~/.claude/CLAUDE.md。保存后下一轮对话即生效。",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !userScope, onClick = { userScope = false }, label = { Text("项目") })
        FilterChip(selected = userScope, onClick = { userScope = true }, label = { Text("用户") })
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; saved = false },
        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 360.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
    Button(
        onClick = {
            scope.launch {
                vm.saveMemory(userScope, text)
                saved = true
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (saved) "已保存" else "保存") }
}

// ---------------------------------------------------------------------------
// /config
// ---------------------------------------------------------------------------

@Composable
private fun ConfigSection(vm: ClaudeCodeVM) {
    val scope = rememberCoroutineScope()
    var outputStyle by remember { mutableStateOf("") }
    var statusLine by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val settings = vm.loadSettings()
        outputStyle = settings["outputStyle"].orEmpty()
        statusLine = settings["statusLine"].orEmpty()
        loaded = true
    }

    SectionHeader("CLI 配置", "写入 Rootfs 的 ~/.claude/settings.json，下次开会话生效")
    OutlinedTextField(
        value = outputStyle,
        onValueChange = { outputStyle = it; saved = false },
        label = { Text("输出风格 outputStyle") },
        placeholder = { Text("default / Explanatory / Learning") },
        singleLine = true,
        enabled = loaded,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = statusLine,
        onValueChange = { statusLine = it; saved = false },
        label = { Text("状态行命令 statusLine") },
        placeholder = { Text("留空表示不启用") },
        singleLine = true,
        enabled = loaded,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "模型、思考强度、权限模式这三项不在这里改 —— 它们能在运行中热切，走底栏的会话设置。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = {
            scope.launch {
                vm.saveSimpleSettings(outputStyle = outputStyle, statusLine = statusLine)
                saved = true
            }
        },
        enabled = loaded,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (saved) "已保存" else "保存") }
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
        "权限规则",
        "写入 settings.json 的 permissions。规则形如 Bash(git status:*)、Edit(/workspace/**)、WebFetch",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("allow" to "允许", "ask" to "询问", "deny" to "拒绝").forEach { (key, label) ->
            FilterChip(
                selected = bucket == key,
                onClick = { bucket = key },
                label = { Text(label) },
            )
        }
    }
    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        rules.forEach { rule ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rule,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = {
                    val next = rules - rule
                    rules = next
                    scope.launch { vm.savePermissionRules(bucket, next) }
                }) {
                    Icon(HugeIcons.Delete02, "删除", Modifier.size(15.dp))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("新增规则") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.padding(top = 8.dp)) {
            Button(
                onClick = {
                    val next = rules + draft.trim()
                    rules = next
                    draft = ""
                    scope.launch { vm.savePermissionRules(bucket, next) }
                },
                enabled = draft.isNotBlank() && draft.trim() !in rules,
            ) { Text("添加") }
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
