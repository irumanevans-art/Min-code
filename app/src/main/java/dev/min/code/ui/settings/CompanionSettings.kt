package dev.min.code.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.companion.CompanionKind
import dev.min.code.core.companion.CompanionNote
import dev.min.code.core.engine.StartChoices
import dev.min.code.core.settings.CompanionSettings
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.InkDialog
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkSwitch
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.components.SettingRow
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.nav.Screen
import org.koin.androidx.compose.koinViewModel

/**
 * 「情感特供」：开关、后台整理用的模型、记忆本身。记忆只在 App 里（见 `core/companion`），
 * 这一页是用户唯一能看到、改、删它的地方。
 */
@Composable
internal fun CompanionSettingsPage(vm: CompanionVM = koinViewModel()) {
    val config by vm.config.collectAsStateWithLifecycle()
    val memory by vm.memory.collectAsStateWithLifecycle()
    val choices by vm.choices.collectAsStateWithLifecycle()
    var pickingModel by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CompanionNote?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Text(
        stringResource(R.string.companion_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingRow(
        title = stringResource(R.string.companion_toggle_title),
        subtitle = stringResource(if (config.enabled) R.string.companion_toggle_on else R.string.companion_toggle_off),
        onClick = { vm.setEnabled(!config.enabled) },
        trailing = { InkSwitch(checked = config.enabled, onCheckedChange = { vm.setEnabled(it) }) },
    )
    SettingRow(
        title = stringResource(R.string.companion_model_title),
        subtitle = modelSubtitle(config, choices),
        onClick = { pickingModel = true },
    )
    if (config.enabled && (config.profileId == null || config.modelId == null)) {
        Notice(text = stringResource(R.string.companion_model_missing), tone = NoticeTone.Warn)
    }
    if (config.enabled) {
        InkButton(onClick = vm::flushNow, tone = InkButtonTone.Paper, compact = true) {
            Text(stringResource(R.string.companion_flush_now))
        }
    }

    InkDivider(Modifier.padding(vertical = 4.dp), brush = true)
    SectionTitle(stringResource(R.string.companion_memory_title, memory.notes.size))
    if (memory.notes.isEmpty()) {
        Text(
            stringResource(R.string.companion_memory_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    for (kind in CompanionKind.entries) {
        val notes = memory.notes.filter { it.kind == kind }.sortedByDescending { it.updatedAt }
        if (notes.isEmpty()) continue
        Text(
            kindLabel(kind),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notes.forEach { note -> SettingRow(title = note.text, onClick = { editing = note }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkButton(onClick = { adding = true }, tone = InkButtonTone.Paper, compact = true) {
            Text(stringResource(R.string.companion_add))
        }
        if (memory.notes.isNotEmpty()) {
            InkButton(onClick = { confirmClear = true }, tone = InkButtonTone.Vermilion, compact = true) {
                Text(stringResource(R.string.companion_clear))
            }
        }
    }

    if (pickingModel) {
        CompanionModelDialog(config, choices, onDismiss = { pickingModel = false }) { profile, model ->
            vm.setModel(profile, model)
            pickingModel = false
        }
    }
    editing?.let { note ->
        NoteDialog(
            title = stringResource(R.string.companion_edit_title),
            initialKind = note.kind,
            initialText = note.text,
            kindEditable = false,
            onDismiss = { editing = null },
            onDelete = { vm.deleteNote(note.id); editing = null },
            onSave = { _, text -> vm.editNote(note.id, text); editing = null },
        )
    }
    if (adding) {
        NoteDialog(
            title = stringResource(R.string.companion_add),
            initialKind = CompanionKind.LIFE,
            initialText = "",
            kindEditable = true,
            onDismiss = { adding = false },
            onDelete = null,
            onSave = { kind, text -> vm.addNote(kind, text); adding = false },
        )
    }
    RikkaConfirmDialog(
        show = confirmClear,
        title = stringResource(R.string.companion_clear),
        confirmText = stringResource(R.string.companion_clear_confirm),
        dismissText = stringResource(R.string.team_cancel),
        onConfirm = { vm.clear(); confirmClear = false },
        onDismiss = { confirmClear = false },
    ) { Text(stringResource(R.string.companion_clear_body)) }
}

@Composable
private fun kindLabel(kind: CompanionKind) = stringResource(
    if (kind == CompanionKind.FEELING) R.string.companion_kind_feeling else R.string.companion_kind_life,
)

@Composable
private fun modelSubtitle(config: CompanionSettings, choices: StartChoices?): String {
    val profileId = config.profileId
    val modelId = config.modelId
    if (profileId == null || modelId == null) return stringResource(R.string.companion_model_none)
    val label = choices?.profiles?.firstOrNull { it.id == profileId }?.label
        ?: return stringResource(R.string.companion_model_gone, modelId)
    return "$label · $modelId"
}

/** 供应商档 + 模型：和 Min 开始面板一样的选法（档的芯片、模型芯片、表里没有就手填 id） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanionModelDialog(
    config: CompanionSettings,
    choices: StartChoices?,
    onDismiss: () -> Unit,
    onSave: (profileId: String, modelId: String) -> Unit,
) {
    val profiles = choices?.profiles.orEmpty()
    var profileId by remember { mutableStateOf(config.profileId?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id) }
    val current = profiles.firstOrNull { it.id == profileId }
    var modelId by remember { mutableStateOf(config.modelId.orEmpty()) }
    var typed by remember { mutableStateOf("") }
    val chosen = typed.trim().ifEmpty { modelId }
    val navController = LocalNavController.current
    InkDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.companion_model_title),
        confirmButton = {
            InkTextButton(
                enabled = profileId != null && chosen.isNotBlank(),
                onClick = { profileId?.let { onSave(it, chosen) } },
            ) { Text(stringResource(R.string.settings_web_search_save)) }
        },
        dismissButton = { InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.team_cancel)) } },
    ) {
        if (profiles.isEmpty()) {
            Notice(
                text = stringResource(R.string.companion_model_no_profiles),
                tone = NoticeTone.Warn,
                action = {
                    InkTextButton(onClick = { onDismiss(); navController.navigate(Screen.Providers) }) {
                        Text(stringResource(R.string.start_min_open_providers), style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
            return@InkDialog
        }
        SectionTitle(stringResource(R.string.start_min_provider))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            profiles.forEach { p ->
                InkChip(label = p.label, selected = p.id == profileId, onClick = {
                    if (p.id != profileId) { profileId = p.id; modelId = p.defaultModelId.orEmpty() }
                })
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
                models.forEach { m ->
                    InkChip(label = m.label, selected = typed.isBlank() && m.id == modelId, onClick = { modelId = m.id; typed = "" }, monospace = true)
                }
            }
        }
        InkTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.start_min_model_other),
            singleLine = true,
            monospace = true,
        )
        Text(
            stringResource(R.string.companion_model_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoteDialog(
    title: String,
    initialKind: CompanionKind,
    initialText: String,
    kindEditable: Boolean,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (CompanionKind, String) -> Unit,
) {
    var kind by remember { mutableStateOf(initialKind) }
    var text by remember { mutableStateOf(initialText) }
    InkDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = {
            InkTextButton(enabled = text.isNotBlank(), onClick = { onSave(kind, text) }) {
                Text(stringResource(R.string.settings_web_search_save))
            }
        },
        dismissButton = { InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.team_cancel)) } },
    ) {
        if (kindEditable) {
            InkSegmented(
                options = CompanionKind.entries.map { kindLabel(it) },
                selected = CompanionKind.entries.indexOf(kind),
                onSelect = { kind = CompanionKind.entries[it] },
            )
        }
        InkTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        onDelete?.let {
            InkButton(onClick = it, tone = InkButtonTone.Vermilion, compact = true) {
                Text(stringResource(R.string.companion_delete))
            }
        }
    }
}
