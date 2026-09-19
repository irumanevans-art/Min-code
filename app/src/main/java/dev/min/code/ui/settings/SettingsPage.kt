package dev.min.code.ui.settings

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.BuildConfig
import dev.min.code.R
import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.AppLanguage
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.ThemeMode
import dev.min.code.core.settings.isInsecureBaseUrl
import dev.min.code.core.settings.normalizeBaseUrl
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
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.components.SettingRow
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.nav.Screen
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.LocalDarkMode
import dev.min.code.ui.theme.LocalFormSwitch
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PencilEdit02
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import org.koin.androidx.compose.koinViewModel

/**
 * App 设置。只有四样：连接、npm 源、主题、关于——没有"设置页里的设置页"。
 *
 * 连接是一张**可以存多条**的表：一条 = 备注名 + token + 中转地址。手上常年挂着两三个
 * 中转站的人不该每次换站都重敲一遍 key。改动落盘在对话框里点「应用」那一下，
 * 列表上点一下只是换当前生效的那条。
 */
@Composable
fun SettingsPage(vm: SettingsVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    // null = 对话框关着；id 为 null 的 Edit = 新增
    var editing by remember { mutableStateOf<ProfileEdit?>(null) }
    // null = 没有待确认的明文地址
    var insecureConfirm by remember { mutableStateOf<InsecureConfirm?>(null) }
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            InkTopBar(
                title = stringResource(R.string.settings_title),
                navigationIcon = { BackButton() },
                scrolled = scrollState.canScrollBackward,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle(stringResource(R.string.settings_section_connection))
                if (settings.profiles.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_connection_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val activeId = settings.activeProfile?.id
                    settings.profiles.forEach { profile ->
                        ProfileRow(
                            profile = profile,
                            selected = profile.id == activeId,
                            onSelect = {
                                // 切到一条没确认过的明文地址：先问一次，再切
                                if (profile.needsInsecureConfirm) {
                                    insecureConfirm = InsecureConfirm(profile.baseUrl) {
                                        vm.setActiveProfile(profile.id, acknowledgeInsecure = true)
                                    }
                                } else {
                                    vm.setActiveProfile(profile.id)
                                }
                            },
                            onEdit = { editing = profile.toEdit() },
                        )
                    }
                }
                // 当前生效的地址是明文 http：这条常驻，不是弹一次就算数的。
                // 用「注意」档而不是朱砂——这是风险提示，不是错误：这个配置按下去照常工作
                AnimatedVisibility(
                    visible = settings.insecureBaseUrl,
                    enter = InkMotion.enter,
                    exit = InkMotion.exit,
                ) {
                    Notice(
                        text = stringResource(R.string.settings_connection_insecure_warning),
                        tone = NoticeTone.Warn,
                    )
                }
                Text(
                    stringResource(R.string.settings_connection_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    InkTextButton(
                        onClick = { editing = ProfileEdit(id = null) },
                        icon = HugeIcons.PlusSign,
                    ) { Text(stringResource(R.string.settings_connection_add)) }
                }

                InkDivider(Modifier.padding(vertical = 4.dp), brush = true)

                SectionTitle(stringResource(R.string.settings_section_install))
                SettingRow(
                    title = stringResource(R.string.settings_npm_mirror_title),
                    subtitle = stringResource(R.string.settings_npm_mirror_subtitle),
                    onClick = { vm.setUseNpmMirror(!settings.useNpmMirror) },
                    trailing = {
                        InkSwitch(checked = settings.useNpmMirror, onCheckedChange = { vm.setUseNpmMirror(it) })
                    },
                )

                InkDivider(Modifier.padding(vertical = 4.dp), brush = true)

                SectionTitle(stringResource(R.string.settings_section_background))
                BatteryRow()

                InkDivider(Modifier.padding(vertical = 4.dp), brush = true)

                SectionTitle(stringResource(R.string.settings_section_appearance))
                ThemePicker(current = settings.themeMode, onPick = vm::setThemeMode)

                SectionTitle(stringResource(R.string.settings_section_language))
                LanguagePicker(current = settings.appLanguage, onPick = vm::setAppLanguage)

                InkDivider(Modifier.padding(vertical = 4.dp), brush = true)

                SettingRow(
                    title = stringResource(R.string.settings_about),
                    onClick = { navController.navigate(Screen.About) },
                    trailing = {
                        Text(
                            BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetbrainsMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }

    editing?.let { edit ->
        ProfileDialog(
            edit = edit,
            // 最后一条不给删：删光了下次开会话只会撞上"未配置 token"，
            // 想换内容在这个对话框里改就是了
            canDelete = edit.id != null && settings.profiles.size > 1,
            onDismiss = { editing = null },
            onSave = { label, token, baseUrl ->
                val url = normalizeBaseUrl(baseUrl)
                val insecure = isInsecureBaseUrl(url)
                val save = {
                    // 走到这里要么地址不是明文，要么用户已经放过行：ack 就等于「是不是明文」
                    if (edit.id == null) vm.addProfile(label, token, baseUrl, insecureAck = insecure)
                    else vm.updateProfile(edit.id, label, token, baseUrl, insecureAck = insecure)
                    editing = null
                }
                // 同一条、地址没改、之前确认过 —— 不再问第二遍（升级上来的既有配置就落在这里）
                val known = settings.profiles.firstOrNull { it.id == edit.id }
                    ?.let { it.insecureAck && it.baseUrl == url } == true
                if (insecure && !known) insecureConfirm = InsecureConfirm(url, save) else save()
            },
            onDelete = {
                edit.id?.let { vm.deleteProfile(it) }
                editing = null
            },
        )
    }

    insecureConfirm?.let { pending ->
        // 只劝一次，不拦：点「仍然使用」就照常存、照常发；取消也只是不改设置，
        // 已经在用的连接不受影响。destructive = false —— 这不是删除那一档的判定
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.settings_connection_insecure_title),
            confirmText = stringResource(R.string.settings_connection_insecure_continue),
            dismissText = stringResource(R.string.common_cancel),
            destructive = false,
            onConfirm = {
                pending.proceed()
                insecureConfirm = null
            },
            onDismiss = { insecureConfirm = null },
        ) {
            Text(stringResource(R.string.settings_connection_insecure_body, pending.baseUrl))
        }
    }
}

/**
 * 一件等着用户就明文风险点头的事：[baseUrl] 给对话框显示，[proceed] 是点「仍然使用」之后要做的。
 *
 * 取消就只是把这个状态清掉——**不改任何设置**，也不阻断已经在用的连接。
 */
private class InsecureConfirm(val baseUrl: String, val proceed: () -> Unit)

/** 对话框的初值。[id] 为 null = 新增 */
private data class ProfileEdit(
    val id: String?,
    val label: String = "",
    val token: String = "",
    val baseUrl: String = AppSettings.DEFAULT_BASE_URL,
)

private fun ApiProfile.toEdit() = ProfileEdit(id = id, label = label, token = token, baseUrl = baseUrl)

/**
 * 表里的一行。整行可点 = 切到这一条；右边那颗笔才是改内容。
 *
 * token 在列表上**只露头尾**：设置页是会被人从背后看到的，一整条 key 铺在那里没有道理。
 */
@Composable
private fun ProfileRow(
    profile: ApiProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
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
                    profile.displayName(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Text(
                        stringResource(R.string.settings_connection_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.sea.seaDeep,
                    )
                }
            }
            Text(
                listOf(profile.baseUrl, profile.maskedToken()).filter { it.isNotBlank() }.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetbrainsMono,
                // 明文 http 的中转站会把 Bearer 头裸着送上路，地址本身就该点出来。
                // 用海深而不是朱：这是「注意」，不是判定——这条配置按下去照常工作。
                // 本机回环（localhost / 127.x / 10.0.2.2）不算，那是自己跟自己说话
                color = if (profile.insecure) {
                    MaterialTheme.sea.seaDeep
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        InkIconButton(
            icon = HugeIcons.PencilEdit02,
            contentDescription = stringResource(R.string.settings_connection_edit_action),
            onClick = onEdit,
            size = 34.dp,
            iconSize = 17.dp,
        )
    }
}

/** 新增 / 编辑一条连接。token 默认打码，右边一颗眼睛可以看明文 */
@Composable
private fun ProfileDialog(
    edit: ProfileEdit,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (label: String, token: String, baseUrl: String) -> Unit,
    onDelete: () -> Unit,
) {
    var label by rememberSaveable(edit.id) { mutableStateOf(edit.label) }
    var token by rememberSaveable(edit.id) { mutableStateOf(edit.token) }
    var baseUrl by rememberSaveable(edit.id) { mutableStateOf(edit.baseUrl) }
    var visible by rememberSaveable { mutableStateOf(false) }
    InkDialog(
        onDismissRequest = onDismiss,
        title = stringResource(
            if (edit.id == null) R.string.settings_connection_new else R.string.settings_connection_edit,
        ),
        confirmButton = {
            InkTextButton(
                onClick = { onSave(label, token, baseUrl) },
                enabled = token.isNotBlank(),
            ) { Text(stringResource(R.string.common_apply)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canDelete) {
                    InkTextButton(onClick = onDelete, tone = InkButtonTone.Vermilion) {
                        Text(stringResource(R.string.common_delete))
                    }
                }
                InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    ) {
        InkTextField(
            value = label,
            onValueChange = { label = it },
            label = stringResource(R.string.settings_connection_label),
            placeholder = stringResource(R.string.settings_connection_label_hint),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        InkTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = "ANTHROPIC_BASE_URL",
            singleLine = true,
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        InkTextField(
            value = token,
            onValueChange = { token = it },
            label = "ANTHROPIC_AUTH_TOKEN",
            singleLine = true,
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                InkIconButton(
                    icon = if (visible) HugeIcons.ViewOff else HugeIcons.View,
                    contentDescription = if (visible) {
                        stringResource(R.string.common_hide)
                    } else {
                        stringResource(R.string.common_show)
                    },
                    onClick = { visible = !visible },
                    size = 32.dp,
                    iconSize = 18.dp,
                )
            },
        )
    }
}

/**
 * 电池优化。从系统设置页回来时重新读一次，不然这一行显示的还是旧值。
 * 没关掉时说明文字用朱砂——这是一个会让后台会话被杀的判定，不是普通说明。
 */
@Composable
private fun BatteryRow() {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var ignoringBattery by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SettingRow(
        title = stringResource(R.string.settings_battery_title),
        subtitle = if (ignoringBattery) {
            stringResource(R.string.settings_battery_ok)
        } else {
            stringResource(R.string.settings_battery_warn)
        },
        subtitleColor = if (ignoringBattery) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.sea.vermilion,
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        trailing = {
            Text(
                if (ignoringBattery) {
                    stringResource(R.string.settings_battery_status_ok)
                } else {
                    stringResource(R.string.settings_battery_status_go)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.sea.seaDeep,
            )
        },
    )
}

/**
 * 跟随系统 / 昼 / 夜。跟随系统是默认，排第一（和语言选择器同一规矩）。
 *
 * 真正会换形态时走「换形」：一团新形态颜色的墨从这个分段控件的位置落下、涨满整屏，
 * 主题在遮盖之下切换，然后墨化开。目标形态和当前一样（比如从「浅色」切到白天的「跟随系统」）
 * 就直接写设置，不演。
 */
@Composable
private fun ThemePicker(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    val modes = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
    )
    val formSwitch = LocalFormSwitch.current
    val currentDark = LocalDarkMode.current
    val systemDark = isSystemInDarkTheme()
    var origin by remember { mutableStateOf<Offset?>(null) }
    InkSegmented(
        options = modes.map { it.second },
        selected = modes.indexOfFirst { it.first == current }.coerceAtLeast(0),
        onSelect = { index ->
            val mode = modes[index].first
            val targetDark = when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> systemDark
            }
            if (targetDark == currentDark) {
                onPick(mode)
            } else {
                formSwitch.switch(origin, targetDark) { onPick(mode) }
            }
        },
        modifier = Modifier.onGloballyPositioned { coords ->
            origin = coords.positionInWindow() + Offset(coords.size.width / 2f, coords.size.height / 2f)
        },
    )
}

@Composable
private fun LanguagePicker(current: AppLanguage, onPick: (AppLanguage) -> Unit) {
    val options = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.settings_language_system),
        AppLanguage.ZH to stringResource(R.string.settings_language_zh),
        AppLanguage.EN to stringResource(R.string.settings_language_en),
    )
    InkSegmented(
        options = options.map { it.second },
        selected = options.indexOfFirst { it.first == current }.coerceAtLeast(0),
        onSelect = { onPick(options[it].first) },
        modifier = Modifier.fillMaxWidth(),
    )
}
