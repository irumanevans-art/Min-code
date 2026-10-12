package dev.min.code.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.codex.CodexPermissionPreset
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.EngineAvailability
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.StartChoices
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.theme.sea
import dev.min.code.ui.components.HeroCanvas
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkCheckbox
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.SeaSurface
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.theme.InkMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.ArrowTurnBackward

/**
 * 开始面板顶部那条引擎分段要的东西（设计 §9.2 B7）。分开打包而不是散着传：它们是一个整体——
 * Min 关着（[minVisible] 为假）时分段里没有 Min，也不会选中它。
 *
 * B9 起 Codex 与另两个引擎并列：点「Codex」选中它，下面出现安装 / 登录状态与开始（[codex]）。
 * [codex] 为 null（测试、预览）时分段里没有 Codex。
 */
internal data class StartEngines(
    val minVisible: Boolean,
    val availability: Map<EngineKind, EngineAvailability>,
    val minChoices: StartChoices?,
    /** 默认选中的引擎 = 上次用的 */
    val initial: EngineKind,
    val onChoose: (EngineKind) -> Unit,
    val codex: CodexStart? = null,
) {
    /** 分段里有几个引擎可选：只有 Claude Code 时整条分段不出现 */
    val segments: List<EngineKind>
        get() = buildList {
            add(EngineKind.ClaudeCode)
            if (codex != null) add(EngineKind.Codex)
            if (minVisible) add(EngineKind.MinAgent)
        }

    companion object {
        /** 只有 Claude Code（Min 关着、测试、预览） */
        val ClaudeOnly = StartEngines(false, emptyMap(), null, EngineKind.ClaudeCode, {})
    }
}

@Composable
internal fun LoadingModeToggle(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        InkCheckbox(checked = checked, onCheckedChange = if (enabled) onChange else null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StartPanel(
    session: AgentSessionState,
    cliVersion: String?,
    /** 点「启动」时的选择。Claude Code 只带「跳过权限确认」，Min 带供应商档 / 模型 / 权限模式；其余沿用上次的，由会话层取 */
    onStart: (StartRequest) -> Unit,
    /** 面板上默认选中的工作目录（上次用过的） */
    defaultCwd: String,
    onListCwd: suspend (String) -> Result<List<me.rerere.workspace.WorkspaceFileEntry>> = EmptyCwdList,
    onCreateCwd: (String, String, (Result<String>) -> Unit) -> Unit = NoopCwdCreate,
    /** false = 还没有可用的供应商，见 [SessionVM.connected] */
    connected: Boolean = true,
    onOpenProviders: () -> Unit = {},
    /** 抽屉里的会话：「继续最近的会话」从中挑同引擎、同目录最近的一个（[latestContinuable]） */
    sessions: List<SessionListVM.SessionEntry> = emptyList(),
    onContinue: (SessionListVM.SessionEntry) -> Unit = {},
    engines: StartEngines = StartEngines.ClaudeOnly,
) {
    // 存着的「上次选的」不在这次的分段里（Min 关掉了、没有 Codex）就回到 Claude Code
    var engine by rememberSaveable(engines.initial, engines.minVisible, engines.codex != null) {
        mutableStateOf(engines.initial.takeIf { it in engines.segments } ?: EngineKind.ClaudeCode)
    }
    var codexPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    val minAvailability = engines.availability[EngineKind.MinAgent] ?: EngineAvailability.Ready
    var minProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var minModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var minModelTyped by rememberSaveable { mutableStateOf("") }
    var minModeId by rememberSaveable { mutableStateOf<String?>(null) }
    var minSafe by rememberSaveable { mutableStateOf(false) }
    var minBare by rememberSaveable { mutableStateOf(false) }
    var minRestricted by rememberSaveable { mutableStateOf(false) }
    val minProfile = engines.minChoices?.let { choices ->
        choices.profiles.firstOrNull { it.id == minProfileId }
            ?: choices.profiles.firstOrNull { it.id == choices.defaultProfileId }
            ?: choices.profiles.firstOrNull()
    }
    // 手填的优先；其次点选的（要仍属于当前档）；最后是这个档上次用的 / 第一个
    val minModel = minModelTyped.trim().ifBlank {
        minModelId?.takeIf { id -> minProfile?.models?.any { it.id == id } == true } ?: minProfile?.defaultModelId.orEmpty()
    }
    val minMode = minModeId ?: engines.minChoices?.defaultModeId
    var skipPermissions by rememberSaveable { mutableStateOf(false) }
    var cwd by rememberSaveable { mutableStateOf(defaultCwd) }
    var pickingCwd by remember { mutableStateOf(false) }
    // 挂在 ConnectPrompt 外面：登录成功那一刻 ConnectPrompt 就收起了，见它的注释
    var subscriptionLogin by remember { mutableStateOf(false) }
    SeaSurface {
    HeroCanvas {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = BiasAlignment(0f, 1f),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    // Min / Codex 一段比 Claude Code 的高得多，会一直长到字标的位置：垫一张近乎不透明的纸，字不压在水纹上
                    .then(
                        if (engine == EngineKind.MinAgent || engine == EngineKind.Codex) {
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
                                .padding(14.dp)
                        } else Modifier,
                    ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionTitle(stringResource(R.string.workspace_detail_workspace_info))
                val segments = engines.segments
                if (segments.size > 1) {
                    // 引擎分段：Claude Code · Codex · Min。不可用的字变淡但仍可点，点了在下面写原因和去处。
                    // Codex 的可用与否由它自己的安装 / 登录状态说（下面那一段），不看注册表的 availability
                    InkSegmented(
                        options = segments.map { engineName(it) },
                        selected = segments.indexOf(engine).coerceAtLeast(0),
                        dimmed = segments.withIndex().filter { (_, kind) ->
                            kind == EngineKind.MinAgent && engines.availability[kind]?.isReady == false
                        }.map { it.index }.toSet(),
                        onSelect = { index ->
                            val picked = segments[index]
                            engine = picked
                            engines.onChoose(picked)
                        },
                    )
                }
                // 产品名，不翻
                Text(engineName(engine), style = MaterialTheme.typography.headlineSmall)
                CwdPathRow(
                    cwd = cwd,
                    trailing = if (engine == EngineKind.ClaudeCode) cliVersion else null,
                    onClick = { pickingCwd = true },
                )
                Spacer(Modifier.height(10.dp))
                InkDivider()
                val codexStart = engines.codex
                if (engine == EngineKind.Codex && codexStart != null) {
                    val recent = sessions.filter { it.engine == EngineKind.Codex && it.messageCount > 0 }.maxByOrNull { it.updatedAt }
                    CodexStartSection(
                        codex = codexStart,
                        errorMessage = session.errorMessage,
                        presetId = codexPresetId ?: CodexPermissionPreset.AUTO.id,
                        onPreset = { codexPresetId = it },
                        recentTitle = recent?.title,
                        onContinue = { recent?.let(onContinue) },
                        onStart = {
                            onStart(StartRequest(engine = EngineKind.Codex, cwd = cwd, modeId = codexPresetId ?: CodexPermissionPreset.AUTO.id))
                        },
                    )
                    return@Column
                }
                if (engine == EngineKind.MinAgent) {
                    MinStartSection(
                        availability = minAvailability,
                        choices = engines.minChoices,
                        profileId = minProfile?.id,
                        onProfile = { minProfileId = it; minModelId = null; minModelTyped = "" },
                        modelId = minModel,
                        onModel = { minModelId = it; minModelTyped = "" },
                        typedModel = minModelTyped,
                        onTypedModel = { minModelTyped = it },
                        modeId = minMode,
                        onMode = { minModeId = it },
                        onOpenProviders = onOpenProviders,
                    )
                    SectionTitle(stringResource(R.string.min_loading_modes))
                    LoadingModeToggle("safe", stringResource(R.string.min_safe_desc), minSafe) { minSafe = it; if (it) minBare = false }
                    LoadingModeToggle("bare", stringResource(R.string.min_bare_desc), minBare) { minBare = it; if (it) minSafe = false }
                    LoadingModeToggle("restricted", stringResource(R.string.min_restricted_desc), minRestricted) { minRestricted = it }
                    session.errorMessage?.let { Notice(text = it, tone = NoticeTone.Error, maxLines = 6) }
                    // CC 的 `--continue`：这个目录里最近的那个会话接着聊。没有就不出这个按钮
                    latestContinuable(sessions, EngineKind.MinAgent, cwd)?.let { recent ->
                        InkButton(
                            onClick = { onContinue(recent) },
                            enabled = minAvailability.isReady,
                            modifier = Modifier.fillMaxWidth(),
                            tone = InkButtonTone.Paper,
                            icon = HugeIcons.ArrowTurnBackward,
                        ) {
                            Text(
                                stringResource(R.string.session_continue_recent, recent.title),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                    InkButton(
                        onClick = {
                            onStart(
                                StartRequest(
                                    engine = EngineKind.MinAgent, cwd = cwd,
                                    profileId = minProfile?.id, modelId = minModel.takeIf { it.isNotBlank() }, modeId = minMode,
                                    loadingModes = dev.min.agent.spi.SessionLoadingModes(minSafe, minBare, minRestricted),
                                ),
                            )
                        },
                        enabled = minAvailability.isReady && minProfile != null && minModel.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        icon = HugeIcons.Play,
                    ) {
                        Text(stringResource(R.string.session_start_action))
                    }
                    return@Column
                }
                AnimatedVisibility(visible = !connected, enter = InkMotion.expand, exit = InkMotion.collapse) {
                    ConnectPrompt(
                        onOpenProviders = onOpenProviders,
                        onSubscriptionLogin = { subscriptionLogin = true },
                    )
                }
                // 上次崩溃过就在这里说一声：没有联网上报，用户不主动去「关于」里看就永远不知道。
                // 只提示、不弹窗——启动面板本来就是"停下来看一眼"的地方
                val context = LocalContext.current
                // 读崩溃记录要碰磁盘：挪到 IO，别卡在启动面板的组合里
                var crashed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    crashed = withContext(Dispatchers.IO) {
                        dev.min.code.core.crash.CrashRecorder.read(context) != null
                    }
                }
                AnimatedVisibility(visible = crashed, enter = InkMotion.expand, exit = InkMotion.collapse) {
                    Notice(
                        text = stringResource(R.string.session_start_crash_notice),
                        tone = NoticeTone.Warn,
                        action = {
                            InkTextButton(onClick = { dev.min.code.core.crash.CrashRecorder.clear(context); crashed = false }) {
                                Text(stringResource(R.string.common_clear), style = MaterialTheme.typography.labelSmall)
                            }
                        },
                    )
                }
                session.errorMessage?.let {
                    Notice(text = it, tone = NoticeTone.Error, maxLines = 6)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { skipPermissions = !skipPermissions }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    // 整行可点，勾选框自己不接点击
                    InkCheckbox(checked = skipPermissions, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.session_start_skip_permissions),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.session_start_skip_permissions_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                InkButton(
                    onClick = { onStart(StartRequest(EngineKind.ClaudeCode, cwd, skipPermissions = skipPermissions)) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = HugeIcons.Play,
                ) {
                    Text(stringResource(R.string.session_start_action))
                }
            }
        }
    }
    }
    if (pickingCwd) {
        CwdPickerSheet(
            current = cwd,
            onPick = { cwd = it; pickingCwd = false },
            onDismiss = { pickingCwd = false },
            onList = onListCwd,
            onCreate = onCreateCwd,
        )
    }
    if (subscriptionLogin) {
        ClaudeSubscriptionSheet(onDismiss = { subscriptionLogin = false })
    }
}

/** 产品名，不翻译 */
private fun engineName(kind: EngineKind): String = when (kind) {
    EngineKind.ClaudeCode -> "Claude Code"
    EngineKind.Codex -> "Codex"
    EngineKind.MinAgent -> "Min"
}

/**
 * Min 选中时开始面板上的那一段：供应商（默认最近一次用的档）、模型、权限模式。
 * 引擎不可用时只剩一句原因和去处——点不了「启动」（调用方已按 [availability] 禁用）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinStartSection(
    availability: EngineAvailability,
    choices: StartChoices?,
    profileId: String?,
    onProfile: (String) -> Unit,
    modelId: String,
    onModel: (String) -> Unit,
    typedModel: String,
    onTypedModel: (String) -> Unit,
    modeId: String?,
    onMode: (String) -> Unit,
    onOpenProviders: () -> Unit,
) {
    if (!availability.isReady) {
        val reason = when (availability) {
            EngineAvailability.NeedsInstall -> stringResource(R.string.engine_needs_install)
            EngineAvailability.NeedsProvider -> stringResource(R.string.engine_needs_provider)
            is EngineAvailability.Disabled -> availability.reason
            EngineAvailability.Ready -> ""
        }
        Notice(
            text = reason,
            tone = NoticeTone.Warn,
            action = if (availability == EngineAvailability.NeedsProvider) {
                {
                    InkTextButton(onClick = onOpenProviders) {
                        Text(stringResource(R.string.start_min_open_providers), style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else null,
        )
        return
    }
    val profiles = choices?.profiles.orEmpty()
    val current = profiles.firstOrNull { it.id == profileId }
    SectionTitle(stringResource(R.string.start_min_provider))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { profile ->
            InkChip(label = profile.label, selected = profile.id == profileId, onClick = { onProfile(profile.id) })
        }
    }
    SectionTitle(stringResource(R.string.start_min_model))
    val models = current?.models.orEmpty()
    if (models.isEmpty()) {
        Text(
            stringResource(R.string.start_min_no_models),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            models.forEach { model ->
                InkChip(label = model.label, selected = typedModel.isBlank() && model.id == modelId, onClick = { onModel(model.id) }, monospace = true)
            }
        }
    }
    InkTextField(
        value = typedModel,
        onValueChange = onTypedModel,
        modifier = Modifier.fillMaxWidth(),
        placeholder = stringResource(R.string.start_min_model_other),
        singleLine = true,
        monospace = true,
    )
    val modes = choices?.modes.orEmpty()
    SectionTitle(stringResource(R.string.start_min_mode))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { mode ->
            InkChip(label = mode.label, selected = mode.id == modeId, onClick = { onMode(mode.id) })
        }
    }
    // 绕过权限那一档要把话说在前面（朱字：它是判定）
    modes.firstOrNull { it.id == modeId }?.warning?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.sea.vermilion)
    }
}
