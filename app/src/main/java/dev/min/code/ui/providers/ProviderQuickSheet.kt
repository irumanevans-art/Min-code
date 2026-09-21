package dev.min.code.ui.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.settings.SettingsStore
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkRadio
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import kotlinx.coroutines.flow.map
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 换一家，就地。
 *
 * 供应商页是「管这张表」的地方——增删改、预设库、托管、导入导出。但**最高频的动作只有
 * 一个：换一家**，而它不该要求先离开会话、进两层页面、再退回来。所以抽屉里挂一行
 * 「供应商 · 当前是谁」，点开就是这张单选表，底下留一颗「管理…」通往整页。
 *
 * 缺 key 的条目在这里**不能切过去**：切了也是撞上「未配置 token」，不如直接把人送去
 * 能填 key 的地方。这和列表页 `ProviderRow` 的规矩是同一条。
 */
@Composable
fun ProviderQuickSheet(
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    vm: ProvidersVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val activeId = settings.activeProfile?.id

    InkSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionTitle(stringResource(R.string.providers_title))

            if (settings.profiles.isEmpty()) {
                Text(
                    stringResource(R.string.providers_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(settings.profiles.size, key = { settings.profiles[it].id }) { i ->
                        val profile = settings.profiles[i]
                        QuickRow(
                            title = profile.displayName(),
                            subtitle = profile.baseUrl,
                            warning = if (profile.missingToken) {
                                stringResource(R.string.providers_missing_token)
                            } else {
                                null
                            },
                            selected = profile.id == activeId,
                            onClick = {
                                // 缺 key 的不在这儿切，送去能填 key 的地方
                                if (profile.missingToken) {
                                    onManage()
                                } else {
                                    vm.activate(profile.id, acknowledgeInsecure = profile.insecure)
                                    onDismiss()
                                }
                            },
                        )
                    }
                }
            }

            InkDivider(Modifier.padding(vertical = 6.dp), brush = true)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                InkTextButton(onClick = onManage) {
                    Text(stringResource(R.string.providers_manage_action))
                }
            }
        }
    }
}

@Composable
private fun QuickRow(
    title: String,
    subtitle: String,
    warning: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InkRadio(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                warning ?: subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = if (warning == null) JetbrainsMono else null,
                color = if (warning != null) {
                    MaterialTheme.sea.vermilion
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 当前生效的那家叫什么，给抽屉那一行当副题。
 *
 * 直接订 [SettingsStore]，不经 ViewModel：这里要的只是一个名字，为一行字拉起
 * 一整个 VM（它还要加载预设表、连会话注册表）不值当。
 */
@Composable
fun rememberActiveProviderName(): String {
    val store: SettingsStore = koinInject()
    val flow = remember(store) { store.settings.map { it.activeProfile?.displayName().orEmpty() } }
    val name by flow.collectAsStateWithLifecycle(initialValue = "")
    return name
}
