package dev.min.code.ui.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.claudecode.RelayModelsResult
import dev.min.code.core.claudecode.fetchRelayModelIds
import dev.min.code.core.settings.ApiFormat
import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.AuthHeader
import dev.min.code.core.settings.ClaudeSwitch
import dev.min.code.core.settings.withClaudeSwitch
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkSwitch
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.SettingRow
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.sea
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Download01

/**
 * 编辑面板里那几块「不是每次都要动」的东西：取模型、几颗开关、方言与认证方式。
 *
 * 全部落在 [ApiProfile] 已有的字段和 `env` 上，**这里不引入第二份存储**——
 * 开关只是 env 里的已知键（[ClaudeSwitch]），取模型写的也是 env 里那三档别名。
 */

/** 模型 id 可以往哪几个位置放。四档都是环境变量，见 `ApiProfile.env` */
private enum class ModelSlot(val key: String, val label: Int) {
    MAIN("ANTHROPIC_MODEL", R.string.providers_models_target_main),
    HAIKU("ANTHROPIC_DEFAULT_HAIKU_MODEL", R.string.providers_models_target_haiku),
    SONNET("ANTHROPIC_DEFAULT_SONNET_MODEL", R.string.providers_models_target_sonnet),
    OPUS("ANTHROPIC_DEFAULT_OPUS_MODEL", R.string.providers_models_target_opus),
}

private sealed interface ModelsState {
    data object Idle : ModelsState
    data object Running : ModelsState
    data class Ok(val ids: List<String>) : ModelsState
    data class Failed(val text: String) : ModelsState
}

/**
 * 「这家都卖哪些模型」。
 *
 * 取不到不是错误状态：不少中转压根没实现这个端点，那时手填 id 照样能用，
 * 所以失败的话只说一句，不拦任何操作（同 `RelayProbe` 的规矩）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ModelPicker(profile: ApiProfile, onPick: (ApiProfile) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var state by remember(profile.baseUrl) { mutableStateOf<ModelsState>(ModelsState.Idle) }
    var slot by remember { mutableStateOf(ModelSlot.MAIN) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.providers_models_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        InkTextButton(
            enabled = state != ModelsState.Running && profile.token.isNotBlank(),
            icon = HugeIcons.Download01,
            onClick = {
                state = ModelsState.Running
                scope.launch {
                    // 文案走 context 而不是 stringResource：这里是协程里，不是组合里
                    state = when (val result = fetchRelayModelIds(profile.baseUrl, profile.token)) {
                        is RelayModelsResult.Ok -> ModelsState.Ok(result.ids)
                        RelayModelsResult.Unauthorized, RelayModelsResult.NoToken ->
                            ModelsState.Failed(context.getString(R.string.providers_models_unauthorized))
                        is RelayModelsResult.NotSupported ->
                            ModelsState.Failed(context.getString(R.string.providers_models_unsupported, result.code))
                        is RelayModelsResult.Failed ->
                            ModelsState.Failed(context.getString(R.string.providers_models_failed, result.reason))
                    }
                }
            },
        ) {
            Text(
                stringResource(
                    if (state == ModelsState.Running) {
                        R.string.providers_fetch_models_running
                    } else {
                        R.string.providers_fetch_models
                    },
                ),
            )
        }
    }
    Hint(stringResource(R.string.providers_models_map_hint))

    // 四档映射随时能手填：DeepSeek 这类 Anthropic 兼容基址本来就没有列表，
    // 不能等「取模型」成功才画出输入框。
    ModelSlot.entries.forEach { target ->
        val current = profile.env[target.key].orEmpty()
        InkTextField(
            value = current,
            onValueChange = { text ->
                val trimmed = text.trim()
                onPick(
                    profile.copy(
                        env = if (trimmed.isEmpty()) profile.env - target.key
                        else profile.env + (target.key to trimmed),
                    ),
                )
            },
            label = stringResource(target.label),
            placeholder = target.key,
            singleLine = true,
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    when (val current = state) {
        is ModelsState.Failed -> Hint(current.text, error = true)
        is ModelsState.Ok -> {
            Text(
                stringResource(R.string.providers_models_target),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InkSegmented(
                options = ModelSlot.entries.map { stringResource(it.label) },
                selected = slot.ordinal,
                onSelect = { slot = ModelSlot.entries[it] },
                modifier = Modifier.fillMaxWidth(),
            )
            // 列表可能上百条，给个高度上限，不然它会把底下的保存按钮推出屏幕
            FlowRow(
                Modifier.fillMaxWidth().heightIn(max = 190.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                current.ids.forEach { id ->
                    InkChip(
                        label = id,
                        monospace = true,
                        selected = profile.env[slot.key] == id,
                        onClick = { onPick(profile.copy(env = profile.env + (slot.key to id))) },
                    )
                }
            }
        }
        else -> Unit
    }
}

private const val ENV_MAX_CONTEXT = "CLAUDE_CODE_MAX_CONTEXT_TOKENS"
private const val ENV_AUTO_COMPACT = "CLAUDE_CODE_AUTO_COMPACT_WINDOW"

/** 上下文量快捷值：芯片文案 → 写入 env 的数字串 */
private val CONTEXT_PRESETS = listOf(
    "200k" to "200000",
    "256k" to "262144",
    "786k" to "786432",
    "1M" to "1000000",
)

/**
 * 上下文窗口。写的仍是 [ApiProfile.env] 里那两个键，不另开存储。
 *
 * CLI 对不认识的中转模型 id 默认按 200k 压缩；DeepSeek / Kimi / MiniMax 这类
 * 要靠 `CLAUDE_CODE_MAX_CONTEXT_TOKENS` / `CLAUDE_CODE_AUTO_COMPACT_WINDOW` 说清楚。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ContextWindowSection(env: Map<String, String>, onChange: (Map<String, String>) -> Unit) {
    var open by remember {
        mutableStateOf(env.containsKey(ENV_MAX_CONTEXT) || env.containsKey(ENV_AUTO_COMPACT))
    }
    Foldable(stringResource(R.string.providers_context_title), open) { open = !open }
    AnimatedVisibility(open, enter = InkMotion.expand, exit = InkMotion.collapse) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint(stringResource(R.string.providers_context_hint))
            Text(
                stringResource(R.string.providers_context_max),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CONTEXT_PRESETS.forEach { (label, value) ->
                    InkChip(
                        label = label,
                        monospace = true,
                        selected = env[ENV_MAX_CONTEXT] == value,
                        onClick = {
                            onChange(env + (ENV_MAX_CONTEXT to value) + (ENV_AUTO_COMPACT to value))
                        },
                    )
                }
                InkChip(
                    label = stringResource(R.string.providers_context_clear),
                    selected = false,
                    onClick = {
                        onChange(env - ENV_MAX_CONTEXT - ENV_AUTO_COMPACT)
                    },
                )
            }
            InkTextField(
                value = env[ENV_MAX_CONTEXT].orEmpty(),
                onValueChange = { text ->
                    val trimmed = text.trim()
                    onChange(
                        if (trimmed.isEmpty()) env - ENV_MAX_CONTEXT
                        else env + (ENV_MAX_CONTEXT to trimmed),
                    )
                },
                label = ENV_MAX_CONTEXT,
                placeholder = "1000000",
                singleLine = true,
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
            )
            InkTextField(
                value = env[ENV_AUTO_COMPACT].orEmpty(),
                onValueChange = { text ->
                    val trimmed = text.trim()
                    onChange(
                        if (trimmed.isEmpty()) env - ENV_AUTO_COMPACT
                        else env + (ENV_AUTO_COMPACT to trimmed),
                    )
                },
                label = ENV_AUTO_COMPACT,
                placeholder = "786432",
                singleLine = true,
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 几颗常用开关。底下仍只有 env 一份事实，所以下面那张 env 表里看得见它们 */
@Composable
internal fun ClaudeSwitchRows(env: Map<String, String>, onChange: (Map<String, String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Foldable(stringResource(R.string.providers_switches), open) { open = !open }
    AnimatedVisibility(open, enter = InkMotion.expand, exit = InkMotion.collapse) {
        Column {
            ClaudeSwitch.entries.forEach { toggle ->
                SettingRow(
                    title = stringResource(
                        when (toggle) {
                            ClaudeSwitch.AGENT_TEAMS -> R.string.providers_switch_agent_teams
                            ClaudeSwitch.TOOL_SEARCH -> R.string.providers_switch_tool_search
                            ClaudeSwitch.EFFORT_MAX -> R.string.providers_switch_effort_max
                            ClaudeSwitch.DISABLE_ARTIFACT -> R.string.providers_switch_disable_artifact
                        },
                    ),
                    subtitle = if (toggle == ClaudeSwitch.DISABLE_ARTIFACT) {
                        stringResource(R.string.providers_switch_disable_artifact_hint)
                    } else {
                        toggle.key
                    },
                    onClick = { onChange(env.withClaudeSwitch(toggle, !toggle.isOn(env))) },
                    trailing = {
                        InkSwitch(
                            checked = toggle.isOn(env),
                            onCheckedChange = { on -> onChange(env.withClaudeSwitch(toggle, on)) },
                        )
                    },
                )
            }
            Hint(stringResource(R.string.providers_switches_hint))
        }
    }
}

/** 方言、认证方式、完整端点。折叠着：它们开局定一次，之后几乎不动 */
@Composable
internal fun AdvancedSection(draft: ApiProfile, onChange: (ApiProfile) -> Unit) {
    // 非默认值的时候默认展开：一条走路由的配置，用户下次打开就该一眼看见它走的是路由
    var open by remember(draft.id) {
        mutableStateOf(draft.apiFormat != ApiFormat.ANTHROPIC_MESSAGES || draft.authHeader != AuthHeader.AUTH_TOKEN)
    }
    Foldable(stringResource(R.string.providers_advanced), open) { open = !open }
    AnimatedVisibility(open, enter = InkMotion.expand, exit = InkMotion.collapse) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.providers_api_format),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InkSegmented(
                options = listOf(
                    stringResource(R.string.providers_api_format_anthropic),
                    stringResource(R.string.providers_api_format_chat),
                    stringResource(R.string.providers_api_format_responses),
                ),
                selected = draft.apiFormat.ordinal,
                onSelect = { onChange(draft.copy(apiFormat = ApiFormat.entries[it])) },
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(stringResource(R.string.providers_api_format_hint))

            Text(
                stringResource(R.string.providers_auth_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InkSegmented(
                options = listOf(
                    stringResource(R.string.providers_auth_bearer),
                    stringResource(R.string.providers_auth_api_key),
                ),
                selected = draft.authHeader.ordinal,
                onSelect = { onChange(draft.copy(authHeader = AuthHeader.entries[it])) },
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(stringResource(R.string.providers_auth_hint))

            // 原生直连时地址是交给 CLI 自己拼的，我们插不了手 —— 给一颗点了不生效的开关
            // 比没有这颗开关更糟，所以这里直接置灰并把理由写在副题里
            SettingRow(
                title = stringResource(R.string.providers_full_url),
                subtitle = stringResource(R.string.providers_full_url_hint),
                enabled = draft.apiFormat.needsRelay,
                onClick = if (draft.apiFormat.needsRelay) {
                    { onChange(draft.copy(fullUrlEndpoint = !draft.fullUrlEndpoint)) }
                } else {
                    null
                },
                trailing = {
                    InkSwitch(
                        checked = draft.fullUrlEndpoint && draft.apiFormat.needsRelay,
                        enabled = draft.apiFormat.needsRelay,
                        onCheckedChange = { on -> onChange(draft.copy(fullUrlEndpoint = on)) },
                    )
                },
            )
        }
    }
}

/** 一行可折叠的小标题。折叠是因为这些都是低频项，平铺会把面板撑成一条很长的滚动条 */
@Composable
private fun Foldable(title: String, open: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, InkMotion.spatial(), label = "fold")
    SettingRow(
        title = title,
        onClick = onToggle,
        trailing = {
            Icon(
                imageVector = HugeIcons.ArrowDown01,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp).graphicsLayer { rotationZ = turn },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.sea.vermilion else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
