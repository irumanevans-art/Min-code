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
import dev.min.code.core.settings.CODEX_WIRE_APIS
import dev.min.code.core.settings.UnifiedProfile
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff

/**
 * 编辑一条统一供应商。
 *
 * 界面上没有「同步到哪几个应用」的勾选框：**地址填了就是同步，留空就是不同步**。
 * 一个勾选框加一个地址栏是两处可以互相矛盾的状态（勾了但地址空着该怎么办？），
 * 而地址本身已经把这件事说清楚了。
 */
@Composable
fun UnifiedEditSheet(
    profile: UnifiedProfile,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (UnifiedProfile) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(profile.id) { mutableStateOf(profile) }
    var revealToken by remember { mutableStateOf(false) }
    val canApply = draft.syncsClaude || draft.syncsCodex

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
                    if (profile.id.isBlank()) R.string.providers_unified_new else R.string.providers_unified_edit,
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            InkTextField(
                value = draft.label,
                onValueChange = { draft = draft.copy(label = it) },
                label = stringResource(R.string.providers_label),
                placeholder = stringResource(R.string.providers_label_hint),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            InkTextField(
                value = draft.token,
                onValueChange = { draft = draft.copy(token = it) },
                label = stringResource(R.string.codex_api_key),
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
                value = draft.claudeBaseUrl,
                onValueChange = { draft = draft.copy(claudeBaseUrl = it) },
                label = stringResource(R.string.providers_unified_claude),
                singleLine = true,
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
            )
            InkTextField(
                value = draft.codexBaseUrl,
                onValueChange = { draft = draft.copy(codexBaseUrl = it) },
                label = stringResource(R.string.providers_unified_codex),
                singleLine = true,
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.providers_unified_side_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (draft.syncsCodex) {
                Text(
                    stringResource(R.string.providers_codex_wire_api),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val wires = CODEX_WIRE_APIS.toList()
                InkSegmented(
                    options = wires,
                    selected = wires.indexOf(draft.wireApi).coerceAtLeast(0),
                    onSelect = { draft = draft.copy(wireApi = wires[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            InkTextField(
                value = draft.note,
                onValueChange = { draft = draft.copy(note = it) },
                label = stringResource(R.string.providers_note),
                placeholder = stringResource(R.string.providers_note_hint),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

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
                    InkTextButton(enabled = canApply, onClick = { onSave(draft) }) {
                        Text(stringResource(R.string.common_apply))
                    }
                }
            }
        }
    }
}
