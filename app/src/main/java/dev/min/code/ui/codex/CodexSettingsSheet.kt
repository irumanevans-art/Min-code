package dev.min.code.ui.codex

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.min.code.R
import dev.min.code.core.codex.CODEX_EFFORT_LEVELS
import dev.min.code.core.codex.CODEX_REASONING_SUMMARIES
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.codex.CodexPermissionPreset
import dev.min.code.core.codex.codexPermissionWire
import dev.min.code.core.codex.permissionPreset
import dev.min.code.ui.session.AccordionSection
import dev.min.code.ui.session.CwdPickerSheet
import dev.min.code.ui.session.OptionRow
import dev.min.code.ui.session.SessionSettingsFrame
import dev.min.code.ui.session.SettingsInlineInput
import dev.min.code.ui.session.SettingsSubHeader
import me.rerere.workspace.WorkspaceFileEntry

/**
 * Codex 的会话设置：模型 / 权限 / 思考强度 / 思考摘要 / 工作目录。
 *
 * 和 Claude 那张（`ClaudeCodeSettingsSheet`）是同一种面板——外观件全在 `ui/session/SessionSettingsParts.kt`，
 * 这里只摆 Codex 自己有的几栏。理由也一样：几样低频设置收成一行行概览，点哪个展开哪个。
 *
 * ## 和 Claude 那张的差别
 *
 * - **点了就算数，没有「正在应用」的等待**：这些值都是 `turn/start` 的逐轮覆盖（官方：this turn and subsequent turns），
 *   存在 manager 里、下一轮带上，不重启进程。所以选项在一轮跑着时也不变灰，横幅只是说一声「从下一轮起」。
 * - **没有提示缓存、没有斜杠命令表**：app-server 协议里没有这两样，不造。
 * - **多了思考摘要**：OpenAI 的推理模型不回原始思考，会话流里那段「思考」全靠摘要。
 */
internal enum class CodexSettingsSection(@StringRes val labelRes: Int) {
    MODEL(R.string.session_section_model),
    PERMISSIONS(R.string.codex_section_permissions),
    EFFORT(R.string.session_section_effort),
    SUMMARY(R.string.codex_section_summary),
    CWD(R.string.session_section_cwd),
}

/** 本地斜杠命令打开哪一栏；null = 不是会话设置的事（`/new`） */
internal fun CodexSlash.toSection(): CodexSettingsSection? = when (this) {
    CodexSlash.MODEL -> CodexSettingsSection.MODEL
    CodexSlash.EFFORT -> CodexSettingsSection.EFFORT
    CodexSlash.PERMISSIONS, CodexSlash.APPROVALS -> CodexSettingsSection.PERMISSIONS
    CodexSlash.CD -> CodexSettingsSection.CWD
    CodexSlash.NEW -> null
}

@StringRes
internal fun CodexPermissionPreset.labelRes(): Int = when (this) {
    CodexPermissionPreset.READ_ONLY -> R.string.codex_permission_read_only
    CodexPermissionPreset.AUTO -> R.string.codex_permission_auto
    CodexPermissionPreset.FULL_ACCESS -> R.string.codex_permission_full_access
}

@StringRes
private fun CodexPermissionPreset.descRes(): Int = when (this) {
    CodexPermissionPreset.READ_ONLY -> R.string.codex_permission_read_only_desc
    CodexPermissionPreset.AUTO -> R.string.codex_permission_auto_desc
    CodexPermissionPreset.FULL_ACCESS -> R.string.codex_permission_full_access_desc
}

/** 权限那一栏的当前值：落在某一档就写档名，否则直接写两个协议值——宁可难看也不能写错 */
@Composable
internal fun CodexAppServerManager.Options.permissionLabel(): String =
    permissionPreset?.let { stringResource(it.labelRes()) } ?: codexPermissionWire(approvalPolicy, sandbox)

@Composable
private fun summaryHint(summary: String): String? = when (summary) {
    "auto" -> stringResource(R.string.codex_summary_auto)
    "concise" -> stringResource(R.string.codex_summary_concise)
    "detailed" -> stringResource(R.string.codex_summary_detailed)
    "none" -> stringResource(R.string.codex_summary_none)
    else -> null
}

@Composable
internal fun CodexSettingsSheet(
    /** 这条会话此刻的设置（manager 里那份），面板只读它、改动经回调回到 manager */
    options: CodexAppServerManager.Options,
    /** 一轮正在跑：改动要等下一轮 */
    turnRunning: Boolean,
    /** 连接配置里的模型，也就是新会话的默认；空 = 没设 */
    profileModel: String?,
    initialSection: CodexSettingsSection?,
    onDismiss: () -> Unit,
    onSetModel: (String?) -> Unit,
    onSetPermission: (CodexPermissionPreset) -> Unit,
    onSetEffort: (String?) -> Unit,
    onSetSummary: (String?) -> Unit,
    onSetCwd: (String) -> Unit,
    onListCwd: suspend (String) -> Result<List<WorkspaceFileEntry>>,
    onCreateCwd: (String, String, (Result<String>) -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(initialSection) }
    val unset = stringResource(R.string.codex_unset_sub)
    SessionSettingsFrame(
        title = stringResource(R.string.session_settings_title),
        busy = turnRunning,
        busyLabel = stringResource(R.string.codex_settings_busy_turn),
        onDismiss = onDismiss,
    ) {
        SettingsSubHeader(stringResource(R.string.codex_settings_scope))

        Section(CodexSettingsSection.MODEL, options.model ?: stringResource(R.string.session_model_default), open, { open = it }) {
            ModelPicker(current = options.model, profileModel = profileModel, unsetHint = unset, onPick = onSetModel)
        }

        Section(CodexSettingsSection.PERMISSIONS, options.permissionLabel(), open, { open = it }) {
            SettingsSubHeader(stringResource(R.string.codex_permission_wire_hint))
            val current = options.permissionPreset
            if (current == null) {
                SettingsSubHeader(
                    stringResource(R.string.codex_permission_custom, codexPermissionWire(options.approvalPolicy, options.sandbox)),
                )
            }
            CodexPermissionPreset.entries.forEach { preset ->
                OptionRow(
                    title = stringResource(preset.labelRes()),
                    subtitle = stringResource(preset.descRes()) + "\n" + preset.wireSummary,
                    selected = preset == current,
                    onClick = { onSetPermission(preset) },
                )
            }
        }

        Section(
            CodexSettingsSection.EFFORT,
            options.effort ?: stringResource(R.string.codex_effort_default),
            open,
            { open = it },
        ) {
            ValueRows(
                values = CODEX_EFFORT_LEVELS,
                current = options.effort,
                unsetHint = unset,
                hint = { null },
                onPick = onSetEffort,
            )
        }

        Section(
            CodexSettingsSection.SUMMARY,
            options.summary ?: stringResource(R.string.codex_effort_default),
            open,
            { open = it },
        ) {
            SettingsSubHeader(stringResource(R.string.codex_summary_hint))
            ValueRows(
                values = CODEX_REASONING_SUMMARIES,
                current = options.summary,
                unsetHint = unset,
                hint = { summaryHint(it) },
                onPick = onSetSummary,
            )
        }

        Section(CodexSettingsSection.CWD, options.cwd, open, { open = it }) {
            SettingsSubHeader(stringResource(R.string.codex_cwd_hint))
            // asSheet = false：已经在一张 sheet 里了，再套一层两张会互相抢手势
            CwdPickerSheet(
                current = options.cwd,
                onPick = onSetCwd,
                onDismiss = {},
                onList = onListCwd,
                onCreate = onCreateCwd,
                asSheet = false,
            )
        }
    }
}

/** 分区外观见 [AccordionSection]；这里只把枚举上的标题资源接过去 */
@Composable
private fun Section(
    section: CodexSettingsSection,
    value: String,
    open: CodexSettingsSection?,
    onToggle: (CodexSettingsSection?) -> Unit,
    content: @Composable () -> Unit,
) = AccordionSection(section, stringResource(section.labelRes), value, open, onToggle, content)

/**
 * 模型。Codex 这边没有接 `model/list`，所以没有目录可挑：默认 / 连接配置里的那个 / 手输。
 * 当前值是手输的、又不是连接配置那个时，单列一行，免得选中项凭空消失。
 */
@Composable
private fun ModelPicker(
    current: String?,
    profileModel: String?,
    unsetHint: String,
    onPick: (String?) -> Unit,
) {
    var custom by remember(current) { mutableStateOf(current.orEmpty()) }
    Column {
        OptionRow(
            title = stringResource(R.string.session_model_default),
            subtitle = unsetHint,
            selected = current == null,
            onClick = { onPick(null) },
        )
        profileModel?.let { model ->
            OptionRow(
                title = model,
                subtitle = stringResource(R.string.codex_model_profile_default),
                selected = current == model,
                monospaceTitle = true,
                onClick = { onPick(model) },
            )
        }
        if (current != null && current != profileModel) {
            OptionRow(title = current, subtitle = null, selected = true, monospaceTitle = true, onClick = {})
        }
        SettingsInlineInput(
            value = custom,
            onValueChange = { custom = it },
            label = stringResource(R.string.session_model_manual),
            placeholder = "gpt-5.1-codex-max",
            onApply = { onPick(custom.trim().ifBlank { null }) },
        )
    }
}

/** 「默认（不传）」+ 一列协议取值。强度和摘要都是这个形状 */
@Composable
private fun ValueRows(
    values: List<String>,
    current: String?,
    unsetHint: String,
    hint: @Composable (String) -> String?,
    onPick: (String?) -> Unit,
) {
    Column {
        OptionRow(
            title = stringResource(R.string.codex_effort_default),
            subtitle = unsetHint,
            selected = current == null,
            onClick = { onPick(null) },
        )
        // 当前值不在表里（连接配置里手写了一个新版 CLI 才有的档）也得看得见，否则像是什么都没选
        val shown = if (current != null && current !in values) values + current else values
        shown.forEach { value ->
            OptionRow(
                title = value,
                subtitle = hint(value),
                selected = current == value,
                monospaceTitle = true,
                onClick = { onPick(value) },
            )
        }
    }
}
