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
                else -> Text("该命令由会话设置面板处理")
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
        contentDescription = "删除",
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
            ) { Text("新增服务器") }
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
    SectionHeader("编辑 MCP 服务器", "stdio 在 Rootfs 里起进程；http/sse 连远端地址")
    InkTextField(
        value = server.name,
        onValueChange = { onChange(server.copy(name = it)) },
        label = "名称",
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
            label = "命令",
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
            label = "参数（空格分隔）",
            placeholder = "-y @modelcontextprotocol/server-filesystem /workspace",
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        InkTextField(
            value = server.env.entries.joinToString("\n") { "${it.key}=${it.value}" },
            onValueChange = { onChange(server.copy(env = parseKeyValueLines(it))) },
            label = "环境变量（每行 KEY=VALUE）",
            monospace = true,
            minHeight = 80.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        InkTextField(
            value = server.url,
            onValueChange = { onChange(server.copy(url = it)) },
            label = "地址",
            placeholder = "https://example.com/mcp",
            singleLine = true,
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        InkTextField(
            value = server.headers.entries.joinToString("\n") { "${it.key}=${it.value}" },
            onValueChange = { onChange(server.copy(headers = parseKeyValueLines(it))) },
            label = "请求头（每行 KEY=VALUE）",
            monospace = true,
            minHeight = 80.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkTextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
        InkButton(
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
            ) { Text("新增子 agent") }
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
    SectionHeader("编辑子 agent", "写入 .claude/agents/*.md，下次开会话生效")
    InkTextField(
        value = draft.name,
        onValueChange = { onChange(draft.copy(name = it)) },
        label = "名称",
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    InkTextField(
        value = draft.description,
        onValueChange = { onChange(draft.copy(description = it)) },
        label = "何时使用（description）",
        modifier = Modifier.fillMaxWidth(),
    )
    InkTextField(
        value = draft.tools,
        onValueChange = { onChange(draft.copy(tools = it)) },
        label = "可用工具（逗号分隔，留空=全部）",
        singleLine = true,
        monospace = true,
        modifier = Modifier.fillMaxWidth(),
    )
    InkTextField(
        value = draft.model,
        onValueChange = { onChange(draft.copy(model = it)) },
        label = "模型（留空=继承）",
        placeholder = "sonnet / opus / haiku",
        singleLine = true,
        monospace = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkChip(
            label = "用户级",
            selected = draft.userScope,
            onClick = { onChange(draft.copy(userScope = true)) },
        )
        InkChip(
            label = "项目级",
            selected = !draft.userScope,
            onClick = { onChange(draft.copy(userScope = false)) },
        )
    }
    InkTextField(
        value = draft.body,
        onValueChange = { onChange(draft.copy(body = it)) },
        label = "系统提示词",
        minHeight = 160.dp,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkTextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
        InkButton(
            onClick = onSave,
            enabled = draft.name.isNotBlank(),
            modifier = Modifier.weight(1f),
        ) { Text("保存") }
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
        "记忆（CLAUDE.md）",
        // 这一条和其它几个不同：CLAUDE.md 是每轮都读的，改完立刻生效
        "项目级 = /workspace/CLAUDE.md，用户级 = ~/.claude/CLAUDE.md。保存后下一轮对话即生效。",
    )
    // 官方对 CLAUDE.md 的定位就是"上下文"：每轮都读，所以写在这里的东西不用每条消息重复。
    // 最佳实践（best-practices / memory 文档）浓缩成一行，比链接管用
    Text(
        "这就是 Claude Code 的「上下文」：每轮对话都会读。写常用命令、代码规范、测试方式、踩过的坑；" +
            "代码里能看出来的不写。200 行以内，太长它反而不照做。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // 空项目最省事的起点是让 CLI 自己扫一遍（/init 是 prompt 型命令，无头模式照样能跑）。
    // 已经有了就降成文字按钮 —— /init 对已有文件会提改进建议，而不是覆盖
    if (!hasProject && !userScope) {
        InkButton(onClick = onRunInit, modifier = Modifier.fillMaxWidth()) {
            Text("让 Claude 扫描项目生成 CLAUDE.md（/init）")
        }
    } else if (!userScope) {
        InkTextButton(onClick = onRunInit, modifier = Modifier.fillMaxWidth()) {
            Text("用 /init 检查并补全这份 CLAUDE.md")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkChip(label = "项目", selected = !userScope, onClick = { userScope = false })
        InkChip(label = "用户", selected = userScope, onClick = { userScope = true })
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

    SectionHeader("CLI 配置", "写入 Rootfs 的 ~/.claude/settings.json，下次开会话生效")
    InkTextField(
        value = outputStyle,
        onValueChange = { outputStyle = it; saved = false },
        label = "输出风格 outputStyle",
        placeholder = "default / Explanatory / Learning",
        singleLine = true,
        monospace = true,
        enabled = loaded,
        modifier = Modifier.fillMaxWidth(),
    )
    InkTextField(
        value = statusLine,
        onValueChange = { statusLine = it; saved = false },
        label = "状态行命令 statusLine",
        placeholder = "留空表示不启用",
        singleLine = true,
        monospace = true,
        enabled = loaded,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "思考强度上限 maxEffortLevel（CLI 2.1.267+）",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        "把所有会话的 effort 钳到这一档及以下（含中转 / Bedrock / Vertex）。" +
            "单次会话里仍可选更低；改完要新开或重启会话才生效。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        InkChip(
            label = "不限",
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
        "模型、本轮思考强度、权限模式不在这里改 —— 它们能在运行中热切，走底栏的会话设置。" +
            "在那里选模型并勾上「存成默认」，写进来的就是这个文件的 model 键（和 CLI 的 /model 按 Enter 一样）。",
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
            label = "新增规则",
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
