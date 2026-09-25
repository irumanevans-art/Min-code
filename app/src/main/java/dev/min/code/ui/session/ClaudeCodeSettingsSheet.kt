package dev.min.code.ui.session

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodeModelCatalog
import dev.min.code.core.claudecode.ClaudeCodePermissionMode
import dev.min.code.core.claudecode.ReasoningLevel
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.InkDivider

/**
 * 会话设置：模型 / 权限模式 / 思考强度 / 提示缓存 / 工作目录 / 斜杠命令。
 *
 * ## 为什么合并成一个面板
 *
 * 之前这几样是底栏上五到七个横向滚动的 chip。手机上一屏只看得见两个半，后面的
 * 要横划才能发现 —— 横向滚动是移动端最差的发现机制之一，用户根本不知道右边还有东西。
 *
 * 而这些控件的实际使用频率都很低：权限模式基本开局定一次，effort 和工作目录更少，
 * 模型偶尔换。为几个低频操作常驻一整行、还要横划，性价比是负的。
 * 合并之后只剩一行摘要（`opus-5 · Manual · high`），点开就是全部，不用探索。
 * 那行摘要后来也从底栏挪进了输入胶囊的「+」菜单（见 [ComposerPlus]），入口是菜单里的第二栏。
 *
 * ## 为什么是手风琴而不是长列表
 *
 * 模型目录加上隐藏别名有十几项，六个分区平铺开会变成一条很长的滚动条，
 * "有哪几类设置"这个信息反而被淹掉。折叠起来先给六行概览（每行带当前值），
 * 点哪个展开哪个 —— 结构本身就是目录。
 */
internal enum class SettingsSection(@StringRes val labelRes: Int) {
    MODEL(R.string.session_section_model),
    MODE(R.string.session_section_mode),
    EFFORT(R.string.session_section_effort),
    CACHE(R.string.session_section_cache),
    CWD(R.string.session_section_cwd),
    COMMANDS(R.string.session_section_commands),
}

/**
 * 权限模式那一行的说明。
 *
 * 放在这里而不是 [ClaudeCodePermissionMode] 里：`label` 是官方术语（Manual /
 * Accept edits），不该翻译也不随语言变；而这句说明是纯粹给人读的，
 * 留在 core 的枚举里就等于永远抽不出去。
 */
@StringRes
internal fun ClaudeCodePermissionMode.descRes(): Int = when (this) {
    ClaudeCodePermissionMode.DEFAULT -> R.string.session_mode_default_desc
    ClaudeCodePermissionMode.ACCEPT_EDITS -> R.string.session_mode_accept_edits_desc
    ClaudeCodePermissionMode.PLAN -> R.string.session_mode_plan_desc
    ClaudeCodePermissionMode.BYPASS -> R.string.session_mode_bypass_desc
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
    val settingsBusy = session.applyingSettings || session.applyingEffort || session.stopping
    // 转圈 + 文案跟对话流 LiveTurnEntry 同一句话，状态连续
    val busyLabel = when {
        session.stopping -> stringResource(R.string.session_settings_busy_stopping)
        session.applyingEffort -> stringResource(R.string.session_settings_busy_relaunch)
        else -> stringResource(R.string.session_settings_busy)
    }
    SessionSettingsFrame(
        title = stringResource(R.string.session_settings_title),
        busy = settingsBusy,
        busyLabel = busyLabel,
        onDismiss = onDismiss,
    ) {
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
                    subtitle = stringResource(mode.descRes()),
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
                value = stringResource(R.string.session_commands_count, session.slashCommands.size),
                open = open,
                onToggle = { open = it },
            ) {
                // 不限高、不内嵌滚动：整张 sheet 本来就是一个 verticalScroll，
                // 之前套了一层 heightIn(max) 却没有 scroll，40 个命令只看得见前两个
                Column {
                    // 英文界面下不给这个开关：那时候 CLI 的原文和界面本来就是一种语言，
                    // 没有「要不要译」这个问题了。见 isChineseUi
                    if (isChineseUi()) {
                        DescriptionLanguageSwitch(
                            chinese = chineseDescriptions,
                            commands = session.slashCommands,
                            onChange = onSetChineseDescriptions,
                        )
                    }
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
    }
}

/** 分区外观见 [AccordionSection]；这里只把枚举上的标题资源接过去 */
@Composable
private fun Section(
    section: SettingsSection,
    value: String,
    open: SettingsSection?,
    onToggle: (SettingsSection?) -> Unit,
    content: @Composable () -> Unit,
) = AccordionSection(section, stringResource(section.labelRes), value, open, onToggle, content)

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
            text = stringResource(R.string.session_desc_lang),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        InkChip(label = stringResource(R.string.session_desc_lang_zh), selected = chinese, onClick = { onChange(true) })
        InkChip(label = stringResource(R.string.session_desc_lang_original), selected = !chinese, onClick = { onChange(false) })
    }
    SettingsSubHeader(
        if (chinese) {
            stringResource(R.string.session_desc_lang_hint, commands.size, translated)
        } else {
            stringResource(R.string.session_desc_lang_hint_off)
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
            title = stringResource(R.string.session_model_save_default),
            subtitle = stringResource(
                if (asDefault) {
                    R.string.session_model_save_default_on
                } else {
                    R.string.session_model_save_default_off
                },
            ),
            selected = asDefault,
            enabled = enabled,
            onClick = { asDefault = !asDefault },
        )
        InkDivider(Modifier.padding(vertical = 4.dp))
        OptionRow(
            title = stringResource(R.string.session_model_default),
            subtitle = stringResource(R.string.session_model_default_sub) +
                // 没显式选过模型时，把 CLI 回读的真实值缀上去 —— 否则用户
                // 只看到「默认」，看不出实际在跑哪个
                if (current == null && appliedModel != null) {
                    stringResource(R.string.session_model_applied, appliedModel)
                } else {
                    ""
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
        SettingsSubHeader(
            when {
                relayModels.isNotEmpty() -> stringResource(R.string.session_model_relay_list)
                !relayChecked -> stringResource(R.string.session_model_relay_querying)
                relayError != null -> stringResource(R.string.session_model_relay_error, relayError)
                else -> stringResource(R.string.session_model_relay_empty)
            }
        )
        if (relayModels.isNotEmpty()) {
            if (suffixMatters) {
                OptionRow(
                    title = stringResource(R.string.session_model_1m_title),
                    subtitle = stringResource(
                        R.string.session_model_1m_sub,
                        stringResource(
                            if (longContext) R.string.session_model_1m_on
                            else R.string.session_model_1m_off,
                        ),
                    ),
                    selected = longContext,
                    enabled = enabled,
                    onClick = { longContext = !longContext },
                )
            } else {
                SettingsSubHeader(stringResource(R.string.session_model_native_1m))
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
            SettingsSubHeader(stringResource(R.string.session_model_alias_header))
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
        SettingsInlineInput(
            value = custom,
            onValueChange = { custom = it },
            label = stringResource(R.string.session_model_manual),
            placeholder = "claude-fable-5-1 / opus[1m]",
            enabled = enabled,
            onApply = { pick(custom.trim().ifBlank { null }) },
        )
        if (models.isEmpty()) {
            SettingsSubHeader(stringResource(R.string.session_model_no_catalog))
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
            SettingsSubHeader(
                stringResource(R.string.session_effort_applied, applied) +
                    if (session.appliedUltracode) {
                        stringResource(R.string.session_effort_ultracode_on)
                    } else {
                        ""
                    }
            )
        }
        if (!effortSupportedFor(session)) {
            // CLI 面板原话 "Effort not supported for Haiku"：列一排点了没反应的档位不如直说
            SettingsSubHeader(stringResource(R.string.session_effort_unsupported))
        }
        SettingsSubHeader(stringResource(R.string.session_effort_restart))
        effortLevelsFor(session).forEach { level ->
            val effort = levelToEffort(level)
            OptionRow(
                title = effort ?: stringResource(R.string.session_effort_default),
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
            subtitle = stringResource(R.string.session_effort_ultracode_sub),
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
        SettingsSubHeader(stringResource(R.string.session_cache_restart))
        ClaudeCodeManager.PROMPT_CACHE_TTLS.forEach { ttl ->
            OptionRow(
                title = if (ttl == ClaudeCodeManager.DEFAULT_PROMPT_CACHE_TTL) stringResource(R.string.session_cache_default_suffix, ttl) else ttl,
                subtitle = promptCacheHint(ttl),
                selected = ttl == current,
                monospaceTitle = true,
                enabled = enabled,
                onClick = { onPick(ttl) },
            )
        }
    }
}

@Composable
internal fun promptCacheHint(ttl: String): String? = when (ttl) {
    "5m" -> stringResource(R.string.session_cache_5m)
    "1h" -> stringResource(R.string.session_cache_1h)
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

// ---------------------------------------------------------------------------
// effort 映射（被 ClaudeCodeEffortMappingTest 覆盖）
// ---------------------------------------------------------------------------

/** 档位说明，文案对齐 CLI 自己的描述 */
@Composable
internal fun effortHint(effort: String?): String? = when (effort) {
    "low" -> stringResource(R.string.session_effort_low)
    "medium" -> stringResource(R.string.session_effort_medium)
    "high" -> stringResource(R.string.session_effort_high)
    "xhigh" -> stringResource(R.string.session_effort_xhigh)
    "max" -> stringResource(R.string.session_effort_max)
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
@Composable
internal fun ClaudeCodeManager.SessionState.effortChipLabel(): String {
    // 先取出来再判断：条件里调 stringResource 可读性更差，而它本来就是常量查表
    val fallback = stringResource(R.string.session_effort_chip_default)
    if (options.ultracode || appliedUltracode) return "ultracode"
    return appliedEffort ?: currentEffort ?: fallback
}

/**
 * 底栏摘要里的模型段。
 *
 * 没有显式选过模型时只写"默认"是不够的：用户看不出**实际**在跑哪个模型，
 * 切换失败（CLI 拒绝 / 中转站不供应）时更是完全无感 —— 界面停在"默认"，
 * 看起来就像"点了没反应"。有 `get_settings` 回读的 applied.model 就显示它。
 */
@Composable
internal fun ClaudeCodeManager.SessionState.modelChipLabel(): String {
    val fallback = stringResource(R.string.session_model_default)
    val selected = currentModel
    if (selected != null) {
        return (availableModels + ClaudeCodeManager.HIDDEN_MODEL_ALIASES)
            .firstOrNull { it.value == selected }
            ?.displayName
            ?: selected
    }
    return appliedModel ?: model ?: fallback
}
