package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodeModelCatalog
import dev.min.code.core.claudecode.ClaudeCodePermissionMode
import dev.min.code.core.claudecode.ReasoningLevel
import dev.min.code.core.service.ClaudeCodeForegroundService
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkSpinner
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.Seal
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01

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
    CACHE("提示缓存"),
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
    chineseDescriptions: Boolean,
    onSetChineseDescriptions: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** (模型, 是否同时存成新会话默认)，语义见 [ModelPicker] */
    onSetModel: (String?, Boolean) -> Unit,
    onSetPermissionMode: (ClaudeCodePermissionMode) -> Unit,
    onApplyEffort: (String?, Boolean) -> Unit,
    onSetPromptCacheTtl: (String) -> Unit,
    onSetCwd: (String) -> Unit,
    onListCwd: suspend (String) -> Result<List<me.rerere.workspace.WorkspaceFileEntry>> = EmptyCwdList,
    onCreateCwd: (String, String, (Result<String>) -> Unit) -> Unit = NoopCwdCreate,
    onPickCommand: (String) -> Unit,
) {
    // 展开的分区。null = 全部折叠，只看五行概览
    var open by remember { mutableStateOf(initialSection) }
    InkSheet(
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
            SheetTitle(stringResource(R.string.session_settings_title))
            val settingsBusy = session.applyingSettings || session.applyingEffort || session.stopping
            // 选项变灰不够：sheet 开着时用户盯着一排死控件，以为没点上。
            // 转圈 + 文案跟对话流 LiveTurnEntry 同一句话，状态连续。
            AnimatedVisibility(
                visible = settingsBusy,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                SettingsBusyBanner(
                    applyingEffort = session.applyingEffort,
                    stopping = session.stopping,
                )
            }

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
                    appliedModel = session.appliedModel,
                    chineseDescriptions = chineseDescriptions,
                    enabled = !settingsBusy,
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
                        enabled = !settingsBusy,
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
                EffortPicker(session = session, enabled = !settingsBusy, onApply = onApplyEffort)
            }

            Section(
                section = SettingsSection.CACHE,
                value = session.options.promptCacheTtl,
                open = open,
                onToggle = { open = it },
            ) {
                PromptCacheTtlPicker(
                    current = session.options.promptCacheTtl,
                    enabled = !settingsBusy,
                    onPick = onSetPromptCacheTtl,
                )
            }

            Section(
                section = SettingsSection.CWD,
                value = session.cwd,
                open = open,
                onToggle = { open = it },
            ) {
                CwdPicker(
                    current = session.cwd,
                    enabled = !settingsBusy,
                    onPick = onSetCwd,
                    onList = onListCwd,
                    onCreate = onCreateCwd,
                )
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
                        DescriptionLanguageSwitch(
                            chinese = chineseDescriptions,
                            commands = session.slashCommands,
                            onChange = onSetChineseDescriptions,
                        )
                        session.slashCommands.forEach { cmd ->
                            OptionRow(
                                title = "/${cmd.name}",
                                // 技能描述动辄一段话（dataviz 那条有 500 字），默认只留两行，
                                // 列表才像目录而不是文档；截断的那条给一个「展开全文」，
                                // 之前是省略号加省略号，想看全没有任何地方可看
                                subtitle = slashCommandDescription(
                                    cmd.name,
                                    cmd.description,
                                    chineseDescriptions,
                                ),
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

/** sheet 的标题：一方印 + 楷书 */
@Composable
private fun SheetTitle(text: String) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Seal()
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsBusyBanner(applyingEffort: Boolean, stopping: Boolean) {
    val label = when {
        stopping -> stringResource(R.string.session_settings_busy_stopping)
        applyingEffort -> stringResource(R.string.session_settings_busy_relaunch)
        else -> stringResource(R.string.session_settings_busy)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InkSpinner(size = 14.dp, color = MaterialTheme.sea.sea)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.sea.sea,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
    Notice(
        text = text,
        tone = if (isError) NoticeTone.Error else NoticeTone.Info,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 一个可折叠分区：标题行常驻并显示当前值，点开才展开内容；箭头随之翻转 */
@Composable
private fun Section(
    section: SettingsSection,
    value: String,
    open: SettingsSection?,
    onToggle: (SettingsSection?) -> Unit,
    content: @Composable () -> Unit,
) {
    val expanded = open == section
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, InkMotion.spatial(), label = "chevron")
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
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            HugeIcons.ArrowDown01,
            contentDescription = null,
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer { rotationZ = rotation },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    AnimatedVisibility(visible = expanded, enter = InkMotion.expand, exit = InkMotion.collapse) {
        Column { content() }
    }
    InkDivider()
}

/**
 * 模型选择。
 *
 * CLI 只把**当前选中**的模型放进 `list_models`，所以 Fable 这类没选中时根本不出现，
 * 用户会陷进「选不到 → 不列出 → 更选不到」的死循环。[ClaudeCodeManager.HIDDEN_MODEL_ALIASES]
 * 就是补这一格；再加上自由输入，任何中转站策略下都不会没有出路。
 */
/**
 * 说明语言开关。
 *
 * CLI 给的说明是英文原文，而这个 App 的界面全是中文 —— 一列中文标题配一段英文说明，
 * 读起来要来回切换语言。但**原文也必须留得住**：译文是查表来的，插件命令、
 * 自定义命令、新版新增的命令都查不到，那时原文才是唯一准确的东西。
 *
 * 覆盖率如实写出来（`38 条里译了 21 条`），而不是含糊说一句"支持中文" ——
 * 用户看到有几条还是英文时，才不会以为是坏了。
 */
@Composable
private fun DescriptionLanguageSwitch(
    chinese: Boolean,
    commands: List<ClaudeCodeManager.SlashCommand>,
    onChange: (Boolean) -> Unit,
) {
    val translated = remember(commands) { translatedCommandCount(commands.map { it.name }) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "说明语言",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        InkChip(label = "中文", selected = chinese, onClick = { onChange(true) })
        InkChip(label = "原文", selected = !chinese, onClick = { onChange(false) })
    }
    SubHeader(
        if (chinese) {
            "${commands.size} 条命令里有 ${translated} 条给得出中文说明，其余（插件 / 自定义 / 新版新增）保持英文原文。"
        } else {
            "显示 CLI 给的英文原文。"
        }
    )
}

@Composable
private fun ModelPicker(
    models: List<ClaudeCodeManager.ModelOption>,
    relayModels: List<ClaudeCodeManager.RelayModel>,
    relayChecked: Boolean,
    relayError: String?,
    current: String?,
    appliedModel: String?,
    chineseDescriptions: Boolean,
    enabled: Boolean = true,
    onPick: (model: String?, asDefault: Boolean) -> Unit,
) {
    // 当前模型改变后（例如 `/model fable` 或 CLI 回读）同步手动输入框，避免面板打开时
    // 仍显示上一个模型，看起来像切换没有生效。
    var custom by remember(current) { mutableStateOf(current.orEmpty()) }
    // 对齐 CLI 面板的两个键：Enter =「存成新会话的默认」（默认行为），s =「仅本会话」。
    // CLI 里 `/model <名字>` 也等于 Enter，所以这里默认开。
    var asDefault by remember { mutableStateOf(true) }
    // 中转站列表里选模型时要不要带 [1m]。默认跟当前选中项走：用着 opus-5[1m] 的人
    // 换到别的多半也想要 1M。原生 1M 的模型（Fable / Opus 5 / Sonnet 5）不受这个开关影响。
    var longContext by remember { mutableStateOf(ClaudeCodeModelCatalog.hasLongContextSuffix(current)) }
    val listed = models.map { it.value }.toSet()
    val listedFamilies = models.map { ClaudeCodeModelCatalog.familyOf(it.resolvedModel ?: it.value) }.toSet()
    // 推荐区：CLI 目录里整个家族都缺席时才补（选中过 Fable 之后 CLI 自己会列出来）
    val promoted = ClaudeCodeManager.HIDDEN_MODEL_ALIASES.filter { alias ->
        alias.value in ClaudeCodeManager.PROMOTED_MODEL_IDS &&
            ClaudeCodeModelCatalog.familyOf(alias.value) !in listedFamilies
    }
    val hidden = ClaudeCodeManager.HIDDEN_MODEL_ALIASES.filter { it.value !in listed && it !in promoted }
    val relayIds = relayModels.map { it.id }.toSet()
    // 当前选中项若不在任何目录里（手输的名字），也要显示出来，否则选中项凭空消失
    val orphan = current
        ?.takeIf {
            it !in listed && hidden.none { h -> h.value == it } && promoted.none { p -> p.value == it } &&
                ClaudeCodeModelCatalog.stripLongContext(it) !in relayIds
        }
        ?.let { ClaudeCodeManager.ModelOption(value = it, displayName = it, hiddenAlias = true) }
    val pick = { value: String? -> if (enabled) onPick(value, asDefault) }
    // 中转站的 [1m] 开关只在列表里真有"加了才是 1M"的模型时才出现 —— 对着一列原生 1M 的
    // 模型问"要不要启用 1M"，正是"咋设置 1m 上下文"这种困惑的来源
    val suffixMatters = relayModels.any { ClaudeCodeModelCatalog.longContextSuffixMeaningful(it.id) }

    Column {
        OptionRow(
            title = "同时存成新会话的默认",
            subtitle = if (asDefault) {
                "写入 ~/.claude/settings.json 的 model，新会话和终端页的 claude 都用它（CLI 面板的 Enter）"
            } else {
                "只改当前会话，其它会话不受影响（CLI 面板的 s 键）"
            },
            selected = asDefault,
            enabled = enabled,
            onClick = { asDefault = !asDefault },
        )
        InkDivider(Modifier.padding(vertical = 4.dp))
        OptionRow(
            title = "默认模型",
            subtitle = buildString {
                append("由 CLI / 中转站决定")
                if (current == null && appliedModel != null) append(" · 当前实际：$appliedModel")
            },
            selected = current == null,
            enabled = enabled,
            onClick = { pick(null) },
        )
        promoted.forEach { model ->
            OptionRow(
                title = model.displayName,
                subtitle = modelDescription(model.value, model.description, chineseDescriptions),
                selected = ClaudeCodeModelCatalog.sameModel(model.value, current),
                enabled = enabled,
                onClick = { pick(model.value) },
            )
        }
        models.forEach { model ->
            OptionRow(
                title = model.displayName,
                subtitle = modelDescription(model.value, model.description, chineseDescriptions),
                selected = model.value == current,
                enabled = enabled,
                onClick = { pick(model.value) },
            )
        }

        // 中转站真实供应的模型：这才是"能选到什么"的事实来源。CLI 的目录只是它自己的
        // 别名表，新模型在那张表里可能根本没有对应项
        SubHeader(
            when {
                relayModels.isNotEmpty() -> "中转站供应的模型（GET /v1/models）"
                !relayChecked -> "正在向中转站查询模型列表…"
                relayError != null -> "中转站没有返回模型列表（$relayError），请用别名或手动输入"
                else -> "中转站返回了空的模型列表，请用别名或手动输入"
            }
        )
        if (relayModels.isNotEmpty()) {
            if (suffixMatters) {
                OptionRow(
                    title = "选择时启用 1M 上下文",
                    subtitle = "给需要的模型加 [1m] 后缀（${if (longContext) "开" else "关"}）。" +
                        "Fable / Opus 5 / Sonnet 5 原生就是 1M，不受影响",
                    selected = longContext,
                    enabled = enabled,
                    onClick = { longContext = !longContext },
                )
            } else {
                SubHeader("这些模型默认就是 1M 上下文，不用另外设置。")
            }
            relayModels.forEach { model ->
                val wantsSuffix = longContext && ClaudeCodeModelCatalog.longContextSuffixMeaningful(model.id)
                val value = if (wantsSuffix) "${model.id}${ClaudeCodeModelCatalog.LONG_CONTEXT_SUFFIX}" else model.id
                val described = modelDescription(model.id, null, chineseDescriptions)
                OptionRow(
                    title = model.displayName ?: model.id,
                    subtitle = described ?: model.displayName?.let { model.id },
                    selected = current == value || current == model.id ||
                        current == "${model.id}${ClaudeCodeModelCatalog.LONG_CONTEXT_SUFFIX}",
                    monospaceTitle = model.displayName == null,
                    enabled = enabled,
                    onClick = { pick(value) },
                )
            }
        }

        (hidden + listOfNotNull(orphan)).takeIf { it.isNotEmpty() }?.let { extras ->
            SubHeader("CLI 别名（选中后即会进入上面的列表）")
            extras.forEach { model ->
                OptionRow(
                    title = model.displayName,
                    subtitle = modelDescription(model.value, model.description, chineseDescriptions),
                    selected = model.value == current,
                    enabled = enabled,
                    onClick = { pick(model.value) },
                )
            }
        }
        InlineInput(
            value = custom,
            onValueChange = { custom = it },
            label = "手动输入",
            placeholder = "claude-fable-5-1 / opus[1m]",
            enabled = enabled,
            onApply = { pick(custom.trim().ifBlank { null }) },
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
    enabled: Boolean = true,
    onApply: (String?, Boolean) -> Unit,
) {
    Column {
        session.appliedEffort?.let { applied ->
            SubHeader(
                "CLI 当前实际运行在：$applied" +
                    if (session.appliedUltracode) "（ultracode 已启用）" else ""
            )
        }
        if (!effortSupportedFor(session)) {
            // CLI 面板原话 "Effort not supported for Haiku"：列一排点了没反应的档位不如直说
            SubHeader("当前模型（Haiku）不支持思考强度，CLI 会忽略 --effort。换 Sonnet / Opus / Fable 才有 low…max 阶梯。")
        }
        SubHeader("切换档位需要重启 CLI 进程并续接当前会话（CLI 没有 set_effort），过程中不能发消息。")
        effortLevelsFor(session).forEach { level ->
            val effort = levelToEffort(level)
            OptionRow(
                title = effort ?: "默认（由 CLI 决定）",
                subtitle = effortHint(effort),
                selected = !session.options.ultracode && session.currentEffort == effort,
                monospaceTitle = effort != null,
                enabled = enabled,
                onClick = { onApply(effort, false) },
            )
        }
        InkDivider(Modifier.padding(vertical = 6.dp))
        OptionRow(
            title = "Ultracode",
            subtitle = "xhigh + 动态工作流编排。单次推理深度等于 xhigh，比 max 低一档；" +
                "但会并行调度多个子智能体，总消耗通常远高于 max。",
            selected = session.options.ultracode,
            enabled = enabled,
            onClick = { onApply(null, true) },
        )
    }
}

/**
 * 提示缓存 TTL。
 *
 * CLI 的默认值**按认证方式自动决定**：订阅在额度内给 1 小时，API key / 中转站只给
 * 5 分钟。我们走的是 `ANTHROPIC_AUTH_TOKEN` + 中转 base URL，属于后者 ——
 * 手机上放下再拿起来常常就过五分钟了，缓存一过期整段上下文按原价重算，
 * 所以这里默认改成 1 小时（[ClaudeCodeManager.DEFAULT_PROMPT_CACHE_TTL]）。
 *
 * 但它不是白拿的：1 小时缓存的**写入**按 2× 计价（5 分钟是 1.25×），
 * 要三次以上命中才回本。一次性问一句就走的人该选 5 分钟，所以这个开关必须给出来。
 *
 * 和 effort 一样是启动期参数，切换要重启 CLI 并续接会话。
 */
@Composable
private fun PromptCacheTtlPicker(
    current: String,
    enabled: Boolean = true,
    onPick: (String) -> Unit,
) {
    Column {
        SubHeader("切换需要重启 CLI 进程并续接当前会话（这是启动期环境变量），过程中不能发消息。")
        ClaudeCodeManager.PROMPT_CACHE_TTLS.forEach { ttl ->
            OptionRow(
                title = if (ttl == ClaudeCodeManager.DEFAULT_PROMPT_CACHE_TTL) "$ttl（默认）" else ttl,
                subtitle = promptCacheHint(ttl),
                selected = ttl == current,
                monospaceTitle = true,
                enabled = enabled,
                onClick = { onPick(ttl) },
            )
        }
    }
}

internal fun promptCacheHint(ttl: String): String? = when (ttl) {
    "5m" -> "缓存写入 1.25× 计价。隔几分钟才回一句就会失效，适合问完就走"
    "1h" -> "缓存写入 2× 计价，命中三次以上才回本。适合开着会话断断续续用一下午"
    else -> null
}

/** 工作目录。展开后直接浏览工作区里的文件夹，不再手填绝对路径。 */
@Composable
private fun CwdPicker(
    current: String,
    enabled: Boolean = true,
    onPick: (String) -> Unit,
    onList: suspend (String) -> Result<List<me.rerere.workspace.WorkspaceFileEntry>>,
    onCreate: (String, String, (Result<String>) -> Unit) -> Unit,
) {
    CwdPickerSheet(
        current = current,
        onPick = { if (enabled) onPick(it) },
        onDismiss = {},
        onList = onList,
        onCreate = onCreate,
        asSheet = false,
    )
}

/** 一张输入纸条 + 右侧的「应用」。输入的是模型名 / 路径，等宽 */
@Composable
private fun InlineInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean = true,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InkTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            label = label,
            placeholder = placeholder,
            singleLine = true,
            monospace = true,
            enabled = enabled,
        )
        InkTextButton(
            onClick = onApply,
            enabled = enabled && value.isNotBlank(),
            modifier = Modifier.padding(bottom = 4.dp),
        ) { Text("应用") }
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

/**
 * 一个可选项。选中的那一行是金的水洗底 + 左边一道金边：这是"你选的"。
 *
 * 说明超出 [subtitleMaxLines] 时给一个「展开全文」——之前是直接截成省略号，
 * 而技能那类说明动辄 500 字（dataviz 那条整段都是触发条件），**省略号后面的内容
 * 在整个 App 里没有任何地方能看到**，等于白写。截断本身是对的：列表要像目录；
 * 缺的只是一个出口。
 */
@Composable
internal fun OptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    monospaceTitle: Boolean = false,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
) {
    // 按 subtitle 取 key：切换中英文时说明整段换掉，展开状态和"有没有截断"都要重算
    var expanded by remember(subtitle) { mutableStateOf(false) }
    var truncated by remember(subtitle) { mutableStateOf(false) }
    val palette = MaterialTheme.sea
    val background by animateColorAsState(
        if (selected) palette.seaWash else Color.Transparent,
        InkMotion.effect(),
        label = "optionBackground",
    )
    val edge by animateFloatAsState(if (selected) 1f else 0f, InkMotion.spatial(), label = "optionEdge")
    val gold = palette.sea
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .background(background)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .drawBehind {
                // 金边从中间长出来，不是整条突然出现
                if (edge > 0f) {
                    val x = 1.dp.toPx()
                    val half = (size.height / 2f - size.height * 0.22f) * edge
                    val cy = size.height / 2f
                    drawLine(
                        color = gold,
                        start = Offset(x, cy - half),
                        end = Offset(x, cy + half),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospaceTitle) JetbrainsMono else null,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    // 只在折叠态记录溢出，且只在值真的变了时写 —— 在 onTextLayout 里
                    // 无条件赋值会让"测量→写状态→重组→再测量"转成死循环
                    onTextLayout = { result ->
                        if (!expanded && result.hasVisualOverflow != truncated) {
                            truncated = result.hasVisualOverflow
                        }
                    },
                )
                if (truncated || expanded) {
                    Text(
                        text = if (expanded) "收起" else "展开全文",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.seaDeep,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            // 自己吃掉点击，否则点"展开全文"会被外层当成选中这一项
                            .clickable { expanded = !expanded },
                    )
                }
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
    // Haiku 整个家族没有 effort（CLI: "Effort not supported for Haiku"）。中转站裸 id
    // （claude-haiku-4-5）不在 list_models 里，靠下面那段查不到，所以先按家族判一次
    if (!effortSupportedFor(session)) return listOf(ReasoningLevel.AUTO)
    val supported = session.availableModels
        .firstOrNull { it.value == session.currentModel }
        ?.supportedEffortLevels
        ?.takeIf { it.isNotEmpty() }
        ?: return base
    // AUTO 永远保留（它代表"不传 flag"，与模型能力无关）
    return base.filter { it == ReasoningLevel.AUTO || it.effort in supported }
        .ifEmpty { listOf(ReasoningLevel.AUTO) }
}

/**
 * 当前模型支不支持 effort。优先看 CLI 回读的真实模型（`applied.model` 是解析后的固定 id），
 * 没有就看用户选的 value（可能是别名 `haiku`），都没有（跟随默认）就当支持。
 */
internal fun effortSupportedFor(session: ClaudeCodeManager.SessionState): Boolean {
    val model = session.appliedModel ?: session.currentModel ?: session.model ?: return true
    return ClaudeCodeModelCatalog.supportsEffort(model)
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
