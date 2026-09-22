package dev.min.code.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.CODEX_WIRE_APIS
import dev.min.code.core.settings.CODEX_WIRE_API_RESPONSES
import dev.min.code.core.settings.ClaudePreset
import dev.min.code.core.settings.CodexAuthMode
import dev.min.code.core.settings.CodexPreset
import dev.min.code.core.settings.CodexProfile
import dev.min.code.core.settings.ProfileJsonParse
import dev.min.code.core.settings.parseProfileJson
import dev.min.code.core.settings.profileToJsonText
import dev.min.code.core.settings.withJsonParse
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff

/**
 * 编辑一条供应商。两种形态，一个事实。
 *
 * ## 为什么 JSON 那一栏不是另一份存储
 *
 * 桌面版 cc-switch 里那份 JSON 就是存储本身，表单是它的视图。这边反过来：结构化字段
 * 是存储，JSON 是它的双向投影。两个 tab 切换时**就地转换**，不允许各存一份草稿 ——
 * 那等于在一个编辑器里制造两个事实来源，而这整套功能的前提就是只能有一个。
 *
 * 代价是 JSON 只认一种形状（`{"env": {…}}`），顶层其它键会被忽略并报数。
 */
@Composable
fun ProviderEditSheet(
    profile: ApiProfile,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (ApiProfile) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(profile.id) { mutableStateOf(profile) }
    var jsonMode by remember { mutableStateOf(false) }
    var revealToken by remember { mutableStateOf(false) }
    var jsonText by remember(profile.id) { mutableStateOf(profileToJsonText(profile, revealToken = false)) }

    val parsed = if (jsonMode) parseProfileJson(jsonText) else null
    val jsonError = parsed as? ProviderJsonErrorAlias
    val canApply = draft.baseUrl.isNotBlank() && jsonError == null

    InkSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(
                    if (profile.id.isBlank()) R.string.providers_new else R.string.providers_edit,
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            InkSegmented(
                options = listOf(
                    stringResource(R.string.providers_form),
                    stringResource(R.string.providers_json),
                ),
                selected = if (jsonMode) 1 else 0,
                onSelect = { index ->
                    val toJson = index == 1
                    if (toJson) {
                        // 表单 → JSON：拿当前草稿渲染一份
                        jsonText = profileToJsonText(draft, revealToken)
                        jsonMode = true
                    } else {
                        // JSON → 表单：解析得动才让切，否则用户会以为刚写的内容被吃了
                        val ok = parseProfileJson(jsonText) as? ProfileJsonParse.Ok
                        if (ok != null) {
                            draft = draft.withJsonParse(ok)
                            jsonMode = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (jsonMode) {
                InkTextField(
                    value = jsonText,
                    onValueChange = { jsonText = it },
                    minLines = 10,
                    monospace = true,
                    isError = jsonError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                val message = jsonErrorMessage(parsed)
                if (message != null) {
                    Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.sea.vermilion)
                } else {
                    val ignored = (parsed as? ProfileJsonParse.Ok)?.ignoredKeys.orEmpty()
                    if (ignored.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.providers_json_ignored,
                                ignored.size,
                                ignored.joinToString(", "),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(R.string.providers_json_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                InkTextField(
                    value = draft.label,
                    onValueChange = { draft = draft.copy(label = it) },
                    label = stringResource(R.string.providers_label),
                    placeholder = stringResource(R.string.providers_label_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                InkTextField(
                    value = draft.baseUrl,
                    onValueChange = { draft = draft.copy(baseUrl = it) },
                    label = "ANTHROPIC_BASE_URL",
                    placeholder = stringResource(R.string.providers_base_url_hint),
                    singleLine = true,
                    monospace = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                InkTextField(
                    value = draft.token,
                    onValueChange = { draft = draft.copy(token = it) },
                    label = "ANTHROPIC_AUTH_TOKEN",
                    singleLine = true,
                    monospace = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (revealToken) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailing = {
                        InkIconButton(
                            icon = if (revealToken) HugeIcons.ViewOff else HugeIcons.View,
                            contentDescription = stringResource(
                                if (revealToken) R.string.common_hide else R.string.common_show,
                            ),
                            onClick = { revealToken = !revealToken },
                            size = 32.dp,
                            iconSize = 18.dp,
                        )
                    },
                )
                InkTextField(
                    value = draft.note,
                    onValueChange = { draft = draft.copy(note = it) },
                    label = stringResource(R.string.providers_note),
                    placeholder = stringResource(R.string.providers_note_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft.websiteUrl.isNotBlank()) OpenWebsite(draft.websiteUrl)

                ModelPicker(profile = draft, onPick = { draft = it })
                if (draft.env["ANTHROPIC_MODEL"].isNullOrBlank()) {
                    Text(
                        stringResource(R.string.providers_models_missing_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.sea.vermilion,
                    )
                }
                ContextWindowSection(env = draft.env, onChange = { draft = draft.copy(env = it) })
                ClaudeSwitchRows(env = draft.env, onChange = { draft = draft.copy(env = it) })
                AdvancedSection(draft = draft, onChange = { draft = it })

                EnvEditor(
                    env = draft.env,
                    onChange = { draft = draft.copy(env = it) },
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canDelete) {
                    InkTextButton(onClick = onDelete, tone = InkButtonTone.Vermilion) {
                        Text(stringResource(R.string.common_delete))
                    }
                }
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    InkTextButton(
                        enabled = canApply,
                        onClick = {
                            val next = if (jsonMode) {
                                (parseProfileJson(jsonText) as? ProfileJsonParse.Ok)
                                    ?.let { draft.withJsonParse(it) } ?: draft
                            } else {
                                draft
                            }
                            onSave(next)
                        },
                    ) { Text(stringResource(R.string.common_apply)) }
                }
            }
        }
    }
}

/** key/value 行表。空行自动消失，不给「删除」按钮之外的第二种删法 */
@Composable
private fun EnvEditor(env: Map<String, String>, onChange: (Map<String, String>) -> Unit) {
    // 编辑期间用 list 而不是 map：map 的 key 改到一半会和别的撞上、或者空 key 直接丢掉，
    // 于是光标跳走、输入被吃。落库时才收敛成 map
    var rows by remember(env) { mutableStateOf(env.entries.map { it.key to it.value }) }
    fun push(next: List<Pair<String, String>>) {
        rows = next
        onChange(next.filter { it.first.isNotBlank() }.toMap())
    }

    Text(
        stringResource(R.string.providers_env),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    rows.forEachIndexed { index, (k, v) ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InkTextField(
                value = k,
                onValueChange = { push(rows.toMutableList().apply { this[index] = it to v }) },
                placeholder = stringResource(R.string.providers_env_key),
                singleLine = true,
                monospace = true,
                modifier = Modifier.weight(1f),
            )
            InkTextField(
                value = v,
                onValueChange = { push(rows.toMutableList().apply { this[index] = k to it }) },
                placeholder = stringResource(R.string.providers_env_value),
                singleLine = true,
                monospace = true,
                modifier = Modifier.weight(1f),
            )
            InkIconButton(
                icon = HugeIcons.Delete02,
                contentDescription = stringResource(R.string.common_delete),
                onClick = { push(rows.toMutableList().apply { removeAt(index) }) },
                size = 32.dp,
                iconSize = 16.dp,
            )
        }
    }
    InkTextButton(onClick = { push(rows + ("" to "")) }, icon = HugeIcons.PlusSign) {
        Text(stringResource(R.string.providers_env_add))
    }
}

@Composable
fun CodexEditSheet(
    profile: CodexProfile,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (CodexProfile) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(profile.id) { mutableStateOf(profile) }
    var revealKey by remember { mutableStateOf(false) }

    InkSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(if (profile.id.isBlank()) R.string.providers_new else R.string.providers_edit),
                style = MaterialTheme.typography.titleSmall,
            )
            InkTextField(
                value = draft.label,
                onValueChange = { draft = draft.copy(label = it) },
                label = stringResource(R.string.providers_label),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CodexAuthMode.entries.forEach { mode ->
                    InkChip(
                        label = stringResource(
                            when (mode) {
                                CodexAuthMode.CLI -> R.string.codex_auth_cli
                                CodexAuthMode.OPENAI_API_KEY -> R.string.codex_auth_openai
                                CodexAuthMode.RELAY -> R.string.codex_auth_relay
                            },
                        ),
                        selected = draft.authMode == mode,
                        onClick = { draft = draft.copy(authMode = mode) },
                    )
                }
            }
            if (draft.authMode != CodexAuthMode.CLI) {
                InkTextField(
                    value = draft.apiKey,
                    onValueChange = { draft = draft.copy(apiKey = it) },
                    label = stringResource(R.string.codex_api_key),
                    singleLine = true,
                    monospace = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (revealKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailing = {
                        InkIconButton(
                            icon = if (revealKey) HugeIcons.ViewOff else HugeIcons.View,
                            contentDescription = stringResource(
                                if (revealKey) R.string.common_hide else R.string.common_show,
                            ),
                            onClick = { revealKey = !revealKey },
                            size = 32.dp,
                            iconSize = 18.dp,
                        )
                    },
                )
                if (draft.isRelay) {
                    InkTextField(
                        value = draft.baseUrl,
                        onValueChange = { draft = draft.copy(baseUrl = it) },
                        label = stringResource(R.string.codex_base_url),
                        singleLine = true,
                        monospace = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // wire_api 以前是写死的 responses，只提供 /chat/completions 的中转
                    // 在那种写法下永远连不上，而界面上看不出是为什么
                    Text(
                        stringResource(R.string.providers_codex_wire_api),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val wires = CODEX_WIRE_APIS.toList()
                    InkSegmented(
                        options = wires,
                        selected = wires.indexOf(draft.effectiveWireApi).coerceAtLeast(0),
                        onSelect = { draft = draft.copy(wireApi = wires[it]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.providers_codex_wire_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            InkTextField(
                value = draft.model,
                onValueChange = { draft = draft.copy(model = it) },
                label = stringResource(R.string.codex_model),
                placeholder = "gpt-5.1-codex-max",
                singleLine = true,
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
            )
            InkTextField(
                value = draft.note,
                onValueChange = { draft = draft.copy(note = it) },
                label = stringResource(R.string.providers_note),
                placeholder = stringResource(R.string.providers_note_hint),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.websiteUrl.isNotBlank()) OpenWebsite(draft.websiteUrl)

            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canDelete) {
                    InkTextButton(onClick = onDelete, tone = InkButtonTone.Vermilion) {
                        Text(stringResource(R.string.common_delete))
                    }
                }
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    InkTextButton(onClick = { onSave(draft) }) {
                        Text(stringResource(R.string.common_apply))
                    }
                }
            }
        }
    }
}

private typealias ProviderJsonErrorAlias = ProfileJsonParse.Err

@Composable
private fun jsonErrorMessage(parse: ProfileJsonParse?): String? = when (parse) {
    is ProfileJsonParse.Err -> when (parse.reason) {
        ProfileJsonParse.Reason.NOT_JSON -> stringResource(R.string.providers_json_err_not_json)
        ProfileJsonParse.Reason.NOT_AN_OBJECT -> stringResource(R.string.providers_json_err_not_object)
        ProfileJsonParse.Reason.ENV_NOT_AN_OBJECT -> stringResource(R.string.providers_json_err_env)
        ProfileJsonParse.Reason.ENV_VALUE_NOT_A_STRING ->
            stringResource(R.string.providers_json_err_value, parse.detail)
    }
    else -> null
}

/**
 * 预设 → 一条待编辑的草稿。
 *
 * 不直接入表：预设里**没有 key**，直接存进去会在列表上立刻多出一条「待填 API Key」，
 * 而用户刚才的意图是「我要用这家」。所以落到编辑面板，光标就在该填的那一栏旁边。
 */
internal fun ClaudePreset.toProfileForEdit(zh: Boolean): ApiProfile = ApiProfile(
    id = "",
    label = displayName(zh),
    baseUrl = baseUrl,
    presetId = id,
    websiteUrl = websiteUrl,
    env = env,
)

internal fun CodexPreset.toProfileForEdit(zh: Boolean): CodexProfile = CodexProfile(
    id = "",
    label = displayName(zh),
    baseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" },
    authMode = mode,
    model = model,
    effort = effort,
    wireApi = wireApi.ifBlank { CODEX_WIRE_API_RESPONSES },
    presetId = id,
    websiteUrl = websiteUrl,
    env = env,
)
