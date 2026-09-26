package dev.min.code.ui.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.claudecode.RelayProbeResult
import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.CodexAuthMode
import dev.min.code.core.settings.CodexProfile
import dev.min.code.core.settings.DeepLinkParse
import dev.min.code.core.settings.ProviderSync
import dev.min.code.core.settings.UnifiedProfile
import dev.min.code.core.settings.matchesProviderQuery
import dev.min.code.ui.components.BackButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDialog
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkRadio
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkSwitch
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.InkTopBar
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.components.rememberExternalBrowserOpener
import dev.min.code.ui.components.SwipeToDelete
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.components.SettingRow
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import dev.min.code.ui.theme.seaFill
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Exchange01
import me.rerere.hugeicons.stroke.PencilEdit02
import me.rerere.hugeicons.stroke.PlusSign
import org.koin.androidx.compose.koinViewModel

/**
 * # 供应商
 *
 * 手上常年挂着几家中转站的人，换一家不该每次重敲一遍 key。这一页就是那张表。
 *
 * 它从设置页里独立出来，因为设置页的自我定位是「只有四样，没有设置页里的设置页」——
 * 预设库 + 托管开关 + 拖拽排序塞进去就把它撑成二级菜单了。设置页那边只留一行入口，
 * 副题写当前是哪家。
 *
 * ## 这一页要说清楚的三件不一致
 *
 * 切换是立刻的，但**有三处天然跟不上**，它们必须摆在明面上而不是让用户自己撞见：
 *
 * 1. 正忙的会话——env 在进程启动时就固化了，重起它会掐掉正跑的一轮，所以只打角标。
 * 2. 已经开着的终端页签——同理，里面可能正跑着东西。
 * 3. 托管写文件失败——会话和终端照常能用，只是文件没跟上。先说前者。
 */
@Composable
fun ProvidersPage(vm: ProvidersVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var codexTab by remember { mutableStateOf(false) }
    var transferSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            InkTopBar(
                title = stringResource(R.string.providers_title),
                navigationIcon = { BackButton() },
                actions = {
                    InkIconButton(
                        icon = HugeIcons.Exchange01,
                        contentDescription = stringResource(R.string.providers_transfer_title),
                        onClick = { transferSheet = true },
                        // 密文打不开时导入会被存储层挡掉，导出只会导出一张空表 —— 两头都没意义
                        enabled = !settings.credentialsUnreadable,
                    )
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
                InkSegmented(
                    options = listOf(
                        stringResource(R.string.providers_tab_claude),
                        stringResource(R.string.providers_tab_codex),
                    ),
                    selected = if (codexTab) 1 else 0,
                    onSelect = { codexTab = it == 1 },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            // 密文打不开时整页只读：这时所有写入都被存储层挡着，按钮还能点的话
            // 用户会点十次然后认为 App 卡死了
            if (settings.credentialsUnreadable) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(20.dp)) {
                    Notice(
                        text = stringResource(R.string.settings_credentials_unreadable),
                        tone = NoticeTone.Error,
                    )
                }
            } else if (codexTab) {
                CodexTab(vm, settings)
            } else {
                ClaudeTab(vm, settings)
            }
        }
    }

    if (transferSheet) ProviderTransferSheet(vm = vm, onDismiss = { transferSheet = false })
    DeepLinkConfirm(vm)
}

/**
 * 一条链接导进来之前的那一眼。
 *
 * 预览把要导的东西摊开说：叫什么、哪个地址、key 的头尾。**不显示完整 key** ——
 * 这张确认框常常是在别人面前打开的（链接多半是刚从聊天里点进来的）。
 */
@Composable
private fun DeepLinkConfirm(vm: ProvidersVM) {
    val inbox by dev.min.code.core.settings.ProviderLinkInbox.link.collectAsStateWithLifecycle()
    LaunchedEffect(inbox) {
        inbox?.let {
            vm.offerDeepLink(it)
            dev.min.code.core.settings.ProviderLinkInbox.clear()
        }
    }
    val pending by vm.pendingLink.collectAsStateWithLifecycle()
    when (val link = pending) {
        null -> Unit
        is DeepLinkParse.Unsupported -> RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.providers_deeplink_title),
            confirmText = stringResource(R.string.common_confirm),
            dismissText = stringResource(R.string.common_cancel),
            destructive = false,
            onConfirm = vm::dismissDeepLink,
            onDismiss = vm::dismissDeepLink,
        ) {
            Text(stringResource(R.string.providers_deeplink_unsupported, link.what))
        }
        else -> {
            val title: String
            val detail: String
            if (link is DeepLinkParse.Claude) {
                title = link.profile.displayName()
                detail = listOf(link.profile.baseUrl, link.profile.maskedToken(), link.profile.note)
                    .filter { it.isNotBlank() }.joinToString("\n")
            } else {
                val codex = (link as DeepLinkParse.Codex).profile
                title = codex.displayName()
                detail = listOf("Codex", codex.baseUrl, codex.maskedKey(), codex.note)
                    .filter { it.isNotBlank() }.joinToString("\n")
            }
            RikkaConfirmDialog(
                show = true,
                title = stringResource(R.string.providers_deeplink_title),
                confirmText = stringResource(R.string.providers_deeplink_import),
                dismissText = stringResource(R.string.common_cancel),
                destructive = false,
                onConfirm = { vm.acceptDeepLink() },
                onDismiss = vm::dismissDeepLink,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.providers_deeplink_body))
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClaudeTab(vm: ProvidersVM, settings: AppSettings) {
    val pending by vm.pendingOrder.collectAsStateWithLifecycle()
    val probes by vm.probe.collectAsStateWithLifecycle()
    val stale by vm.staleSessions.collectAsStateWithLifecycle()
    val sync by vm.syncOutcome.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val zh = isChineseUi()

    // 拖动途中用 VM 的临时顺序：每挪一格写一次盘的话，一次拖动要写十几次
    val all = pending ?: settings.profiles
    // 表里选中的那家（删不删得、切回来用谁）和 Claude 此刻在用的那家（画不画「使用中」）
    // 走订阅时不是一回事，见 ClaudeConnection.kt
    val activeId = settings.activeProfile?.id
    val inUseId = settings.claudeProviderInUseId
    val connection = claudeConnectionOf(settings)

    var query by remember { mutableStateOf("") }
    // 搜索中**不许拖**：屏幕上是过滤后的顺序，拖动写回的却是整表的索引，
    // 松手之后条目会跳到一个谁也没指定的位置
    val searching = query.isNotBlank()
    val profiles = if (!searching) all else {
        all.filter { matchesProviderQuery(query, it.displayName(), it.baseUrl, it.note, it.label) }
    }

    var editing by remember { mutableStateOf<ApiProfile?>(null) }
    var presetPicker by remember { mutableStateOf(false) }
    var insecure by remember { mutableStateOf<ApiProfile?>(null) }
    var secretsConfirm by remember { mutableStateOf(false) }
    var backupSheet by remember { mutableStateOf(false) }
    var unifiedEditing by remember { mutableStateOf<UnifiedProfile?>(null) }
    var unifiedDeleting by remember { mutableStateOf<UnifiedProfile?>(null) }
    // 左滑松手过了线的那一条，等确认
    var swipeDeleting by remember { mutableStateOf<ApiProfile?>(null) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reorder = rememberReorderState(
        listState = listState,
        scope = scope,
        onMove = { from, to -> vm.dragOrder(from as String, to as String) },
        onSettle = vm::commitOrder,
        // 只有真正的供应商行能换位；搜索框 / 提示 / 统一供应商区都挂着自己的 key，
        // 按绝对下标去动它们会让拖动看上去「闪但不走」
        isReorderable = { key -> profiles.any { it.id == key } },
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item("stale") {
            Centered {
                AnimatedVisibility(
                    connection == ClaudeConnection.Subscription,
                    enter = InkMotion.enter,
                    exit = InkMotion.exit,
                ) {
                    Notice(text = stringResource(R.string.providers_subscription_notice), tone = NoticeTone.Info)
                }
                // 切完还在用旧供应商的会话（和订阅那几处共用这一条提示）
                StaleSessionsNotice(
                    count = stale.size,
                    onRestart = vm::restartStaleSessions,
                    onDismiss = vm::dismissStaleSessions,
                )
                val failed = sync as? ProviderSync.Outcome.Failed
                AnimatedVisibility(failed != null, enter = InkMotion.enter, exit = InkMotion.exit) {
                    Notice(
                        // 先说「不影响用」再说「哪里没跟上」——用户最想知道的是前者
                        text = stringResource(
                            R.string.providers_sync_failed,
                            // reason 是稳定标识（见 ProviderSync.FAILED_*），界面负责翻译
                            if (failed?.reason == ProviderSync.FAILED_SAVE) {
                                stringResource(R.string.providers_save_failed)
                            } else {
                                failed?.reason.orEmpty()
                            },
                            connection.shortLabel(),
                        ),
                        tone = NoticeTone.Error,
                    )
                }
            }
        }

        item("head") {
            Centered {
                ProviderListHead(query = query, onQueryChange = { query = it }, empty = profiles.isEmpty())
            }
        }

        items(profiles, key = { it.id }) { profile ->
            val unified = settings.unifiedProfiles.firstOrNull { it.id == profile.unifiedId }
            Centered {
                ProviderRow(
                    title = profile.displayName(),
                    subtitle = listOf(profile.baseUrl, profile.maskedToken())
                        .filter { it.isNotBlank() }.joinToString("  ·  "),
                    selected = profile.id == inUseId,
                    insecure = profile.insecure,
                    warning = if (profile.missingToken) {
                        stringResource(R.string.providers_missing_token)
                    } else {
                        null
                    },
                    probe = probes[profile.id],
                    dragging = reorder.draggingKey == profile.id,
                    dragOffset = reorder.offsetY,
                    note = profile.note,
                    derivedFrom = unified?.let {
                        stringResource(R.string.providers_unified_derived, it.displayName())
                    }.orEmpty(),
                    onDuplicate = { vm.duplicate(profile.id) },
                    modifier = if (searching) Modifier else Modifier.reorderable(reorder, profile.id),
                    onSelect = {
                        when {
                            // 没 key 的条目：直接打开编辑，而不是切过去等着撞上「未配置」
                            profile.missingToken -> editing = profile
                            profile.needsInsecureConfirm -> insecure = profile
                            else -> vm.activate(profile.id)
                        }
                    },
                    onProbe = { vm.probe(profile) },
                    onEdit = { editing = profile },
                    // 正在用的那条和仅剩的一条不可滑，其余左滑请删
                    deletable = profile.id != activeId && settings.profiles.size > 1,
                    onDelete = { swipeDeleting = profile },
                )
            }
        }

        item("add") {
            Centered {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    InkTextButton(onClick = { presetPicker = true }, icon = HugeIcons.PlusSign) {
                        Text(stringResource(R.string.providers_add_preset))
                    }
                    InkTextButton(
                        onClick = { editing = ApiProfile(id = "") },
                        icon = HugeIcons.PlusSign,
                    ) { Text(stringResource(R.string.providers_add_manual)) }
                }
                InkDivider(Modifier.padding(vertical = 10.dp), brush = true)
            }
        }

        item("unified") {
            Centered {
                SectionTitle(stringResource(R.string.providers_unified_section))
                Hint(stringResource(R.string.providers_unified_hint))
                if (settings.unifiedProfiles.isEmpty()) {
                    Hint(stringResource(R.string.providers_unified_empty))
                }
                settings.unifiedProfiles.forEach { unified ->
                    SettingRow(
                        title = unified.displayName(),
                        subtitle = listOf(
                            unified.claudeBaseUrl.takeIf { it.isNotBlank() },
                            unified.codexBaseUrl.takeIf { it.isNotBlank() },
                            unified.note.takeIf { it.isNotBlank() },
                        ).filterNotNull().joinToString("  ·  "),
                        onClick = { unifiedEditing = unified },
                        trailing = {
                            InkIconButton(
                                icon = HugeIcons.PencilEdit02,
                                contentDescription = stringResource(R.string.providers_unified_edit),
                                onClick = { unifiedEditing = unified },
                                size = 34.dp,
                                iconSize = 17.dp,
                            )
                        },
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    InkTextButton(
                        onClick = { unifiedEditing = UnifiedProfile(id = "") },
                        icon = HugeIcons.PlusSign,
                    ) { Text(stringResource(R.string.providers_unified_add)) }
                }
                InkDivider(Modifier.padding(vertical = 10.dp), brush = true)
            }
        }

        item("guest") {
            Centered {
                GuestConfigSection(
                    settings = settings,
                    onManage = vm::setManageGuestConfig,
                    onSecrets = { on -> if (on) secretsConfirm = true else vm.setGuestConfigIncludesSecrets(false) },
                    onShells = vm::setInjectCredentialsIntoShells,
                    onBackups = { backupSheet = true },
                )
            }
        }
    }

    editing?.let { profile ->
        ProviderEditSheet(
            profile = profile,
            canDelete = profile.id.isNotBlank() && settings.profiles.size > 1,
            onDismiss = { editing = null },
            onSave = { next ->
                vm.save(next, activate = profile.id.isBlank())
                editing = null
            },
            onDelete = {
                vm.delete(profile.id)
                editing = null
            },
        )
    }

    swipeDeleting?.let { profile ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.providers_swipe_delete_title),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.delete(profile.id)
                swipeDeleting = null
            },
            onDismiss = { swipeDeleting = null },
        ) {}
    }

    if (presetPicker) {
        ProviderPresetSheet(
            presets = presets,
            zh = zh,
            onDismiss = { presetPicker = false },
            onPick = { preset ->
                presetPicker = false
                // 预设不带 key，所以应用之后直接进编辑，光标就在该填的地方
                editing = preset.toProfileForEdit(zh)
            },
        )
    }

    insecure?.let { profile ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.settings_connection_insecure_title),
            confirmText = stringResource(R.string.settings_connection_insecure_continue),
            dismissText = stringResource(R.string.common_cancel),
            destructive = false,
            onConfirm = {
                vm.activate(profile.id, acknowledgeInsecure = true)
                insecure = null
            },
            onDismiss = { insecure = null },
        ) {
            Text(stringResource(R.string.settings_connection_insecure_body, profile.baseUrl))
        }
    }

    if (secretsConfirm) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.providers_secrets_confirm_title),
            confirmText = stringResource(R.string.common_confirm),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.setGuestConfigIncludesSecrets(true)
                secretsConfirm = false
            },
            onDismiss = { secretsConfirm = false },
        ) {
            Text(stringResource(R.string.providers_secrets_confirm_body))
        }
    }

    if (backupSheet) ProviderBackupSheet(vm = vm, onDismiss = { backupSheet = false })

    unifiedEditing?.let { unified ->
        UnifiedEditSheet(
            profile = unified,
            canDelete = unified.id.isNotBlank(),
            onDismiss = { unifiedEditing = null },
            onSave = {
                vm.saveUnified(it)
                unifiedEditing = null
            },
            onDelete = {
                unifiedDeleting = unified
                unifiedEditing = null
            },
        )
    }

    unifiedDeleting?.let { unified ->
        val derived = settings.profiles.count { it.unifiedId == unified.id } +
            settings.codexProfiles.count { it.unifiedId == unified.id }
        // 「这家不用了」和「只是不想再联动了」都是合理的意图，替用户猜一个的代价是删掉他的 key，
        // 所以两颗按钮都是明确的选择。**点外面是取消**，不是其中任何一种 ——
        // 走 RikkaConfirmDialog 的话 onDismiss 同时接着「留着」和「点了外面」，
        // 手一滑就删掉了一条统一供应商
        InkDialog(
            onDismissRequest = { unifiedDeleting = null },
            title = stringResource(R.string.providers_unified_delete_title),
            confirmButton = {
                InkTextButton(
                    tone = InkButtonTone.Vermilion,
                    onClick = {
                        vm.deleteUnified(unified.id, deleteDerived = true)
                        unifiedDeleting = null
                    },
                ) { Text(stringResource(R.string.providers_unified_delete_all)) }
            },
            dismissButton = {
                InkTextButton(
                    onClick = {
                        vm.deleteUnified(unified.id, deleteDerived = false)
                        unifiedDeleting = null
                    },
                ) { Text(stringResource(R.string.providers_unified_delete_keep)) }
            },
        ) {
            Text(stringResource(R.string.providers_unified_delete_body, derived))
        }
    }
}

@Composable
private fun CodexTab(vm: ProvidersVM, settings: AppSettings) {
    val pending by vm.codexPendingOrder.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val zh = isChineseUi()
    val all = pending ?: settings.codexProfiles
    val activeId = settings.activeCodexProfile?.id

    var query by remember { mutableStateOf("") }
    // 同 Claude 侧：搜索中不许拖，过滤后的索引和整表对不上
    val searching = query.isNotBlank()
    val profiles = if (!searching) all else {
        all.filter { matchesProviderQuery(query, it.displayName(), it.baseUrl, it.note, it.label) }
    }

    var editing by remember { mutableStateOf<CodexProfile?>(null) }
    var presetPicker by remember { mutableStateOf(false) }
    // 左滑松手过了线的那一条，等确认
    var swipeDeleting by remember { mutableStateOf<CodexProfile?>(null) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reorder = rememberReorderState(
        listState = listState,
        scope = scope,
        onMove = { from, to -> vm.dragCodexOrder(from as String, to as String) },
        onSettle = vm::commitCodexOrder,
        isReorderable = { key -> profiles.any { it.id == key } },
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item("head") {
            Centered {
                ProviderListHead(query = query, onQueryChange = { query = it }, empty = profiles.isEmpty())
            }
        }
        items(profiles, key = { it.id }) { profile ->
            val unified = settings.unifiedProfiles.firstOrNull { it.id == profile.unifiedId }
            Centered {
                ProviderRow(
                    title = profile.displayName(),
                    subtitle = listOfNotNull(
                        profile.authMode.name,
                        profile.baseUrl.takeIf { profile.isRelay },
                        profile.maskedKey().takeIf { it.isNotBlank() },
                    ).joinToString("  ·  "),
                    selected = profile.id == activeId,
                    insecure = profile.insecure,
                    warning = if (profile.missingKey) {
                        stringResource(R.string.providers_missing_token)
                    } else {
                        null
                    },
                    probe = null,
                    dragging = reorder.draggingKey == profile.id,
                    dragOffset = reorder.offsetY,
                    note = profile.note,
                    derivedFrom = unified?.let {
                        stringResource(R.string.providers_unified_derived, it.displayName())
                    }.orEmpty(),
                    onDuplicate = { vm.duplicateCodex(profile.id) },
                    modifier = if (searching) Modifier else Modifier.reorderable(reorder, profile.id),
                    onSelect = { vm.activateCodex(profile.id, acknowledgeInsecure = profile.insecure) },
                    onProbe = null,
                    onEdit = { editing = profile },
                    // 正在用的那条和仅剩的一条不可滑，其余左滑请删
                    deletable = profile.id != activeId && settings.codexProfiles.size > 1,
                    onDelete = { swipeDeleting = profile },
                )
            }
        }
        item("add") {
            Centered {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    InkTextButton(onClick = { presetPicker = true }, icon = HugeIcons.PlusSign) {
                        Text(stringResource(R.string.providers_add_preset))
                    }
                    InkTextButton(
                        onClick = { editing = CodexProfile(id = "", authMode = CodexAuthMode.RELAY) },
                        icon = HugeIcons.PlusSign,
                    ) { Text(stringResource(R.string.providers_add_manual)) }
                }
                // Codex 那边为什么没有「把 key 也写进去」那个开关
                Hint(stringResource(R.string.providers_codex_auth_note))
            }
        }
    }

    editing?.let { profile ->
        CodexEditSheet(
            profile = profile,
            canDelete = profile.id.isNotBlank() && settings.codexProfiles.size > 1,
            onDismiss = { editing = null },
            onSave = {
                vm.saveCodex(it, activate = profile.id.isBlank())
                editing = null
            },
            onDelete = {
                vm.deleteCodex(profile.id)
                editing = null
            },
        )
    }

    swipeDeleting?.let { profile ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.providers_swipe_delete_title),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.deleteCodex(profile.id)
                swipeDeleting = null
            },
            onDismiss = { swipeDeleting = null },
        ) {}
    }

    if (presetPicker) {
        ProviderPresetSheet(
            presets = presets,
            zh = zh,
            codex = true,
            onDismiss = { presetPicker = false },
            onPickCodex = { preset ->
                presetPicker = false
                editing = preset.toProfileForEdit(zh)
            },
        )
    }
}

/**
 * 两个 Tab 共用的表头：标题、搜索框、一句提示（空表说空，平时说能长按拖动；搜索中不提拖动，
 * 那时候本来就拖不了）。以前两个 Tab 各抄一份，改搜索交互得记得改两处。
 */
@Composable
private fun ProviderListHead(query: String, onQueryChange: (String) -> Unit, empty: Boolean) {
    SectionTitle(stringResource(R.string.providers_section_all))
    // 一张表常年挂着十几家，翻到底找一条不如搜一下
    InkTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(R.string.providers_search),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
    if (empty) {
        Hint(stringResource(R.string.providers_empty))
    } else if (query.isBlank()) {
        Hint(stringResource(R.string.providers_reorder_hint))
    }
}

/**
 * 表里的一行。整行可点 = 切到这一条（最高频的动作），右边那颗笔才是改内容，
 * 长按才是拖动 —— 三种手势各有各的入口，不互相抢。
 */
@Composable
private fun ProviderRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    insecure: Boolean,
    warning: String?,
    probe: ProvidersVM.ProbeState?,
    dragging: Boolean,
    dragOffset: Float,
    modifier: Modifier = Modifier,
    /** 备注 + 「来自某条统一供应商」，两句都挂在地址下面 */
    note: String = "",
    derivedFrom: String = "",
    onSelect: () -> Unit,
    onProbe: (() -> Unit)?,
    onDuplicate: (() -> Unit)? = null,
    onEdit: () -> Unit,
    /** 左滑可删；正在用的那条和仅剩的一条传 false */
    deletable: Boolean = false,
    /** 过了删除线松手时回调，弹确认框；null = 不包手势层 */
    onDelete: (() -> Unit)? = null,
) {
    val row: @Composable () -> Unit = {
        Row(
            modifier = modifier
                .fillMaxWidth()
                // 拿起来的那一行浮起：抬一档、微微放大，其余行由 LazyColumn 让位
                .graphicsLayer {
                    if (dragging) {
                        translationY = dragOffset
                        scaleX = 1.02f
                        scaleY = 1.02f
                        shadowElevation = 0f
                    }
                }
                .clip(MaterialTheme.shapes.small)
                .then(if (dragging) Modifier.seaFill(MaterialTheme.shapes.small, alpha = 0.12f) else Modifier)
                .clickable(onClick = onSelect)
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InkRadio(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (selected) {
                        Text(
                            stringResource(R.string.providers_active),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.sea.seaDeep,
                        )
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    // 明文 http 用海深而不是朱：这是「注意」不是判定 —— 这条配置按下去照常工作
                    color = if (insecure) MaterialTheme.sea.seaDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val extra = listOf(note, derivedFrom).filter { it.isNotBlank() }.joinToString("  ·  ")
                if (extra.isNotBlank()) {
                    Text(
                        extra,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val probeText = probeLabel(probe)
                if (warning != null || probeText != null) {
                    Text(
                        warning ?: probeText.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (warning != null) MaterialTheme.sea.vermilion else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onProbe != null) {
                InkTextButton(onClick = onProbe) { Text(stringResource(R.string.providers_probe)) }
            }
            if (onDuplicate != null) {
                InkIconButton(
                    icon = HugeIcons.Copy01,
                    contentDescription = stringResource(R.string.providers_duplicate),
                    onClick = onDuplicate,
                    size = 34.dp,
                    iconSize = 17.dp,
                )
            }
            InkIconButton(
                icon = HugeIcons.PencilEdit02,
                contentDescription = stringResource(R.string.providers_edit),
                onClick = onEdit,
                size = 34.dp,
                iconSize = 17.dp,
            )
        }
    }
    // 长按拖动进行中不抢手势：手势层的 enabled 交出去
    if (deletable && onDelete != null) {
        SwipeToDelete(enabled = !dragging, onDelete = onDelete) { row() }
    } else {
        row()
    }
}

@Composable
private fun probeLabel(state: ProvidersVM.ProbeState?): String? = when (state) {
    null -> null
    ProvidersVM.ProbeState.Running -> stringResource(R.string.providers_probe_running)
    is ProvidersVM.ProbeState.Done -> when (val r = state.result) {
        is RelayProbeResult.Reachable ->
            if (r.models >= 0) stringResource(R.string.providers_probe_ok_models, r.models)
            else stringResource(R.string.providers_probe_ok)
        RelayProbeResult.Unauthorized -> stringResource(R.string.providers_probe_unauthorized)
        RelayProbeResult.NoModelsEndpoint -> stringResource(R.string.providers_probe_no_models)
        is RelayProbeResult.Unreachable -> stringResource(R.string.providers_probe_unreachable, r.reason)
        RelayProbeResult.NoToken -> stringResource(R.string.providers_probe_no_token)
    }
}

@Composable
private fun GuestConfigSection(
    settings: AppSettings,
    onManage: (Boolean) -> Unit,
    onSecrets: (Boolean) -> Unit,
    onShells: (Boolean) -> Unit,
    onBackups: () -> Unit,
) {
    SectionTitle(stringResource(R.string.providers_section_guest))
    SettingRow(
        title = stringResource(R.string.providers_manage_title),
        subtitle = stringResource(R.string.providers_manage_subtitle),
        onClick = { onManage(!settings.manageGuestConfig) },
        trailing = { InkSwitch(checked = settings.manageGuestConfig, onCheckedChange = onManage) },
    )
    AnimatedVisibility(settings.manageGuestConfig, enter = InkMotion.expand, exit = InkMotion.collapse) {
        Column(Modifier.padding(start = 12.dp)) {
            SettingRow(
                title = stringResource(R.string.providers_secrets_title),
                subtitle = stringResource(R.string.providers_secrets_subtitle),
                onClick = { onSecrets(!settings.guestConfigIncludesSecrets) },
                trailing = {
                    InkSwitch(checked = settings.guestConfigIncludesSecrets, onCheckedChange = onSecrets)
                },
            )
            // 托管第一次动 settings.json 之前留的底。点错了要能回去
            SettingRow(
                title = stringResource(R.string.providers_backup_title),
                subtitle = stringResource(R.string.providers_backup_subtitle),
                onClick = onBackups,
            )
        }
    }
    SettingRow(
        title = stringResource(R.string.providers_shell_title),
        subtitle = stringResource(R.string.providers_shell_subtitle),
        onClick = { onShells(!settings.injectCredentialsIntoShells) },
        trailing = {
            InkSwitch(checked = settings.injectCredentialsIntoShells, onCheckedChange = onShells)
        },
    )
    Hint(stringResource(R.string.providers_terminal_note))
}

/** 列表项内容统一收在 560 dp 里，和设置页同一条规矩 */
@Composable
private fun Centered(content: @Composable ColumnScopeAlias.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

private typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 界面语言是不是中文。预设的中英文名按它取 */
@Composable
internal fun isChineseUi(): Boolean =
    androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "zh"

@Composable
internal fun OpenWebsite(url: String) {
    val openExternal = rememberExternalBrowserOpener()
    InkTextButton(
        onClick = { openExternal(url) },
        tone = InkButtonTone.Quiet,
    ) { Text(stringResource(R.string.providers_open_website)) }
}
