package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.code.core.claudecode.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodePermissionMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.core.service.ClaudeCodeForegroundService

/**
 * 会话设置：模型 / 权限模式 / 思考强度 / 工作目录 / 斜杠命令。
 *
 * ## 为什么合并成一个面板
 *
 * 之前这五样是底栏上五到七个横向滚动的 chip。手机上一屏只看得见两个半，后面的
 * 要横划才能发现 —— 横向滚动是移动端最差的发现机制之一，用户根本不知道右边还有东西。
 *
 * 而这些控件的实际使用频率都很低：权限模式基本开局定一次，effort 和工作目录更少，
 * 模型偶尔换。为几个低频操作常驻一整行、还要横划，性价比是负的。
 * 合并之后底栏只剩一行摘要（`opus-5 · Manual · high`），点开就是全部，不用探索。
 *
 * ## 为什么是手风琴而不是长列表
 *
 * 模型目录加上隐藏别名有十几项，五个分区平铺开会变成一条很长的滚动条，
 * "有哪几类设置"这个信息反而被淹掉。折叠起来先给五行概览（每行带当前值），
 * 点哪个展开哪个 —— 结构本身就是目录。
 */
internal enum class SettingsSection(val label: String) {
    MODEL("模型"),
    MODE("权限模式"),
    EFFORT("思考强度"),
    CWD("工作目录"),
    COMMANDS("斜杠命令"),
}

/**
 * 本地接管的斜杠命令打开哪个分区（`/model` → 模型分区直接展开）。
 *
 * 返回 null 表示它不属于「会话设置」这张 sheet —— `/mcp`、`/agents` 这些改的是
 * Rootfs 里的配置文件，由 [ClaudeCodeConfigSheet] 接管。
 */
internal fun LocalSlash.toSection(): SettingsSection? = when (this) {
    LocalSlash.MODEL -> SettingsSection.MODEL
    LocalSlash.EFFORT -> SettingsSection.EFFORT
    LocalSlash.PERMISSION -> SettingsSection.MODE
    LocalSlash.CWD -> SettingsSection.CWD
    LocalSlash.MCP, LocalSlash.AGENTS, LocalSlash.MEMORY,
    LocalSlash.CONFIG, LocalSlash.HOOKS -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClaudeCodeSettingsSheet(
    session: ClaudeCodeManager.SessionState,
    initialSection: SettingsSection?,
    onDismiss: () -> Unit,
    onSetModel: (String?) -> Unit,
    onSetPermissionMode: (ClaudeCodePermissionMode) -> Unit,
    onApplyEffort: (String?, Boolean) -> Unit,
    onSetCwd: (String) -> Unit,
    onPickCommand: (String) -> Unit,
) {
    // 展开的分区。null = 全部折叠，只看五行概览
    var open by remember { mutableStateOf(initialSection) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 设置面板一开就该给足高度：半展开状态下手风琴一展开就顶到底，
        // 又会退化成"下半截够不着"的老毛病。把 PartiallyExpanded 从允许状态里去掉
        // 就是旧 API 的 skipPartiallyExpanded=true。
        sheetState = rememberBottomSheetState(
            SheetValue.Hidden,
            setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            Text(
                "会话设置",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )

            Section(
                section = SettingsSection.MODEL,
                value = session.modelChipLabel(),
                open = open,
                onToggle = { open = it },
            ) {
                ModelPicker(
                    models = session.availableModels,
                    relayModels = session.relayModels,
                    relayChecked = session.relayModelsChecked,
                    relayError = session.relayModelsError,
                    current = session.currentModel,
                    onPick = onSetModel,
                )
            }

            Section(
                section = SettingsSection.MODE,
                value = session.permissionMode.label,
                open = open,
                onToggle = { open = it },
            ) {
                ClaudeCodePermissionMode.entries.forEach { mode ->
                    OptionRow(
                        title = mode.label,
                        subtitle = mode.desc,
                        selected = mode == session.permissionMode,
                        onClick = { onSetPermissionMode(mode) },
                    )
                }
            }

            Section(
                section = SettingsSection.EFFORT,
                value = session.effortChipLabel(),
                open = open,
                onToggle = { open = it },
            ) {
                EffortPicker(session = session, onApply = onApplyEffort)
            }

            Section(
                section = SettingsSection.CWD,
                value = session.cwd,
                open = open,
                onToggle = { open = it },
            ) {
                CwdPicker(current = session.cwd, onPick = onSetCwd)
            }

            if (session.slashCommands.isNotEmpty()) {
                Section(
                    section = SettingsSection.COMMANDS,
                    value = "${session.slashCommands.size} 个",
                    open = open,
                    onToggle = { open = it },
                ) {
                    // 不限高、不内嵌滚动：整张 sheet 本来就是一个 verticalScroll，
                    // 之前套了一层 heightIn(max) 却没有 scroll，40 个命令只看得见前两个
                    Column {
                        session.slashCommands.forEach { cmd ->
                            OptionRow(
                                title = "/${cmd.name}",
                                // 技能描述动辄一段话（dataviz 那条有 500 字），只留两行，
                                // 列表才像目录而不是文档
                                subtitle = cmd.description,
                                subtitleMaxLines = 2,
                                selected = false,
                                monospaceTitle = true,
                                onClick = { onPickCommand(cmd.name) },
                            )
                        }
                    }
                }
            }

            KeepAliveStatus()
        }
    }
}

/**
 * 后台保活是否真的生效。
 *
 * 放在设置面板最底下、不占一个分区 —— 平时不需要看，但它失效时的现象
 * （切出去回来任务断了、一串连接中断）和网络问题长得一模一样，
 * 没有这一行就只能靠猜。ColorOS / realme UI 这类 ROM 会在系统侧直接拒发 FGS 类型权限。
 */
@Composable
private fun KeepAliveStatus() {
    val state by ClaudeCodeForegroundService.keepAlive.collectAsStateWithLifecycle()
    val (text, isError) = when (state) {
        ClaudeCodeForegroundService.KeepAlive.Active ->
            "后台保活已生效，切出去 / 息屏后会话继续跑" to false

        ClaudeCodeForegroundService.KeepAlive.Rejected ->
            "系统拒绝了前台服务：切到后台后会话随时可能被回收。" +
                "去系统设置里给 Min 关掉「省电优化 / 后台限制」再试" to true

        ClaudeCodeForegroundService.KeepAlive.Expired ->
            "本机不接受常规的前台服务类型，退回的类型被系统限制为每天 6 小时，已用完。" +
                "会话还在，但切到后台后随时可能被回收" to true

        ClaudeCodeForegroundService.KeepAlive.Stopped ->
            "没有正在运行的会话" to false
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 一个可折叠分区：标题行常驻并显示当前值，点开才展开内容 */
@Composable
private fun Section(
    section: SettingsSection,
    value: String,
    open: SettingsSection?,
    onToggle: (SettingsSection?) -> Unit,
    content: @Composable () -> Unit,
) {
    val expanded = open == section
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(if (expanded) null else section) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(section.label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    AnimatedVisibility(visible = expanded) {
        Column { content() }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
}

/**
 * 模型选择。
 *
 * CLI 只把**当前选中**的模型放进 `list_models`，所以 Fable 这类没选中时根本不出现，
 * 用户会陷进「选不到 → 不列出 → 更选不到」的死循环。[ClaudeCodeManager.HIDDEN_MODEL_ALIASES]
 * 就是补这一格；再加上自由输入，任何中转站策略下都不会没有出路。
 */
@Composable
private fun ModelPicker(
    models: List<ClaudeCodeManager.ModelOption>,
    relayModels: List<ClaudeCodeManager.RelayModel>,
    relayChecked: Boolean,
    relayError: String?,
    current: String?,
    onPick: (String?) -> Unit,
) {
    var custom by remember { mutableStateOf(current.orEmpty()) }
    // 中转站列表里选模型时要不要带 [1m]。默认跟当前选中项走：用着 opus-5[1m] 的人
    // 换到 fable 多半也想要 1M
    var longContext by remember { mutableStateOf(current?.endsWith("[1m]") == true) }
    val listed = models.map { it.value }.toSet()
    val hidden = ClaudeCodeManager.HIDDEN_MODEL_ALIASES.filter { it.value !in listed }
    val relayIds = relayModels.map { it.id }.toSet()
    // 当前选中项若不在任何目录里（手输的名字），也要显示出来，否则选中项凭空消失
    val orphan = current
        ?.takeIf {
            it !in listed && hidden.none { h -> h.value == it } &&
                it.removeSuffix("[1m]") !in relayIds
        }
        ?.let { ClaudeCodeManager.ModelOption(value = it, displayName = it, hiddenAlias = true) }

    Column {
        OptionRow(
            title = "默认模型",
            subtitle = "由 CLI / 中转站决定",
            selected = current == null,
            onClick = { onPick(null) },
        )
        models.forEach { model ->
            OptionRow(
                title = model.displayName,
                subtitle = model.description,
                selected = model.value == current,
                onClick = { onPick(model.value) },
            )
        }

        // 中转站真实供应的模型：这才是"能选到什么"的事实来源。CLI 的目录只是它自己的
        // 别名表，Fable 5.1 这种新模型在那张表里根本没有对应项
        SubHeader(
            when {
                relayModels.isNotEmpty() -> "中转站供应的模型（GET /v1/models）"
                !relayChecked -> "正在向中转站查询模型列表…"
                relayError != null -> "中转站没有返回模型列表（$relayError），请用别名或手动输入"
                else -> "中转站返回了空的模型列表，请用别名或手动输入"
            }
        )
        if (relayModels.isNotEmpty()) {
            OptionRow(
                title = "选择时启用 1M 上下文",
                subtitle = "给模型 id 加 [1m] 后缀（${if (longContext) "开" else "关"}）",
                selected = longContext,
                onClick = { longContext = !longContext },
            )
            relayModels.forEach { model ->
                val value = if (longContext) "${model.id}[1m]" else model.id
                OptionRow(
                    title = model.displayName ?: model.id,
                    subtitle = if (model.displayName != null) model.id else null,
                    selected = current == value || current == model.id,
                    monospaceTitle = model.displayName == null,
                    onClick = { onPick(value) },
                )
            }
        }

        (hidden + listOfNotNull(orphan)).takeIf { it.isNotEmpty() }?.let { extras ->
            SubHeader("CLI 别名（选中后即会进入上面的列表）")
            extras.forEach { model ->
                OptionRow(
                    title = model.displayName,
                    subtitle = model.description,
                    selected = model.value == current,
                    onClick = { onPick(model.value) },
                )
            }
        }
        InlineInput(
            value = custom,
            onValueChange = { custom = it },
            label = "手动输入",
            placeholder = "claude-fable-5-1[1m] / opus",
            onApply = { onPick(custom.trim().ifBlank { null }) },
        )
        if (models.isEmpty()) {
            SubHeader("CLI 没有返回可选模型目录（取决于中转站策略），请手动输入模型名。")
        }
    }
}

/**
 * 思考强度。
 *
 * 布局对齐 CLI 自己的 TUI：先是 low…max 的阶梯，然后**隔开**一个 ultracode 开关 ——
 * ultracode 不是"比 max 更高的一档"，它等于 xhigh 叠加动态工作流编排
 * （CLI 里也是 `{effort, ultracode}` 两个正交字段）。
 */
@Composable
private fun EffortPicker(
    session: ClaudeCodeManager.SessionState,
    onApply: (String?, Boolean) -> Unit,
) {
    Column {
        session.appliedEffort?.let { applied ->
            SubHeader(
                "CLI 当前实际运行在：$applied" +
                    if (session.appliedUltracode) "（ultracode 已启用）" else ""
            )
        }
        SubHeader("切换档位需要重启 CLI 进程并续接当前会话（CLI 没有 set_effort），过程中不能发消息。")
        effortLevelsFor(session).forEach { level ->
            val effort = levelToEffort(level)
            OptionRow(
                title = effort ?: "默认（由 CLI 决定）",
                subtitle = effortHint(effort),
                selected = !session.options.ultracode && session.currentEffort == effort,
                monospaceTitle = effort != null,
                onClick = { onApply(effort, false) },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        OptionRow(
            title = "Ultracode",
            subtitle = "xhigh + 动态工作流编排。单次推理深度等于 xhigh，比 max 低一档；" +
                "但会并行调度多个子智能体，总消耗通常远高于 max。",
            selected = session.options.ultracode,
            onClick = { onApply(null, true) },
        )
    }
}

/** 工作目录。/workspace 是工作区 files/ 的挂载点，也是导入文件的落点 */
@Composable
private fun CwdPicker(current: String, onPick: (String) -> Unit) {
    var custom by remember { mutableStateOf(current) }
    Column {
        OptionRow(
            title = ClaudeCodeManager.DEFAULT_CWD,
            subtitle = "默认：工作区 files/ 的挂载点，导入的文件都落在这里",
            selected = current == ClaudeCodeManager.DEFAULT_CWD,
            monospaceTitle = true,
            onClick = { onPick(ClaudeCodeManager.DEFAULT_CWD) },
        )
        InlineInput(
            value = custom,
            onValueChange = { custom = it },
            label = "自定义路径",
            placeholder = "/workspace/project",
            onApply = { onPick(custom) },
        )
    }
}

@Composable
private fun InlineInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
        )
        TextButton(onClick = onApply, enabled = value.isNotBlank()) { Text("应用") }
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
internal fun OptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    monospaceTitle: Boolean = false,
    subtitleMaxLines: Int = Int.MAX_VALUE,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospaceTitle) FontFamily.Monospace else null,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// effort 映射（被 ClaudeCodeEffortMappingTest 覆盖）
// ---------------------------------------------------------------------------

/** 档位说明，文案对齐 CLI 自己的描述 */
internal fun effortHint(effort: String?): String? = when (effort) {
    "low" -> "快速直给，开销最小"
    "medium" -> "平衡：常规实现 + 基本验证"
    "high" -> "完整实现，充分测试与说明"
    "xhigh" -> "比 high 更深，略低于最高档"
    "max" -> "最强能力、最深推理。可能消耗过多 token、响应很慢，只在最难的任务上用"
    else -> null
}

/**
 * ReasoningLevel ↔ CLI effort 的映射。
 *
 * CLI 的 `--effort` 阶梯只有 `low/medium/high/xhigh/max` 五档（外加未文档化的
 * 别名 `med`→medium、`ultracode`→xhigh）。**`none` 和 `auto` 都会被拒收**，
 * CLI 打印 "Unknown --effort value '…' — ignoring it" 然后静默用默认值，
 * 所以这两档只能映射成"不传 flag"。
 */
internal fun levelToEffort(level: ReasoningLevel): String? = when (level) {
    ReasoningLevel.OFF, ReasoningLevel.AUTO -> null
    else -> level.effort
}

/**
 * 面板上该显示哪些档。
 *
 * - 去掉 OFF：它和 AUTO 一样都是"不传 flag"，留着就是死档
 * - 若当前模型经 `list_models` 报了 supportedEffortLevels，按它裁剪 ——
 *   否则会出现"界面显示 max、CLI 实际钳到 high"的静默降级
 */
internal fun effortLevelsFor(session: ClaudeCodeManager.SessionState): List<ReasoningLevel> {
    val base = ReasoningLevel.entries.filter { it != ReasoningLevel.OFF }
    val supported = session.availableModels
        .firstOrNull { it.value == session.currentModel }
        ?.supportedEffortLevels
        ?.takeIf { it.isNotEmpty() }
        ?: return base
    // AUTO 永远保留（它代表"不传 flag"，与模型能力无关）
    return base.filter { it == ReasoningLevel.AUTO || it.effort in supported }
        .ifEmpty { listOf(ReasoningLevel.AUTO) }
}

/** 底栏摘要里的 effort 段 —— 优先用 CLI 回读的真实值 */
internal fun ClaudeCodeManager.SessionState.effortChipLabel(): String {
    if (options.ultracode || appliedUltracode) return "ultracode"
    return appliedEffort ?: currentEffort ?: "默认"
}

/**
 * 底栏摘要里的模型段。
 *
 * 没有显式选过模型时只写"默认"是不够的：用户看不出**实际**在跑哪个模型，
 * 切换失败（CLI 拒绝 / 中转站不供应）时更是完全无感 —— 界面停在"默认"，
 * 看起来就像"点了没反应"。有 `get_settings` 回读的 applied.model 就显示它。
 */
internal fun ClaudeCodeManager.SessionState.modelChipLabel(): String {
    val selected = currentModel
    if (selected != null) {
        return (availableModels + ClaudeCodeManager.HIDDEN_MODEL_ALIASES)
            .firstOrNull { it.value == selected }
            ?.displayName
            ?: selected
    }
    return appliedModel ?: model ?: "默认模型"
}
