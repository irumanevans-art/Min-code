package dev.min.code.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.BuildConfig
import dev.min.code.R
import dev.min.code.core.device.MinAccessibilityService
import dev.min.code.core.device.PackagePolicyGuard
import dev.min.code.core.rootfs.deviceStorageSettingsIntent
import dev.min.code.core.rootfs.hasDeviceStorageAccess
import dev.min.code.core.settings.AppLanguage
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.SkinStyle
import dev.min.code.core.settings.ThemeMode
import dev.min.code.privileged.PrivilegedClient
import dev.min.code.ui.components.BackButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDivider
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
import dev.min.code.ui.theme.LocalSkin
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import org.koin.androidx.compose.koinViewModel

/**
 * App 设置的首页：三组入口（连接与安装 / 设备 / 外观与语言）加「关于」，
 * 每组点进去是它自己的二级页（[SettingsSectionPage]），和一般设置 App 的层级一样。
 *
 * 连接那一张能存多条的表搬去了 [dev.min.code.ui.providers.ProvidersPage]：它现在带着
 * 预设库、托管开关和拖拽排序，塞回这里就会把设置页撑成二级菜单。这边只留一行入口，
 * 副题回答唯一一个在设置页里值得问的问题——**现在用的是哪家**。
 *
 * 两样东西刻意留下：明文 http 的常驻提醒和「密文打不开」那一段。它们都不是「某一条
 * 供应商」的事，是整份凭据存储的状态。
 */
@Composable
fun SettingsPage(vm: SettingsVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    // 放弃那串打不开的密文要再问一次：这一步之后就真的没得救了
    var discardCredentials by remember { mutableStateOf(false) }
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
                // 密文打不开：这不是「没配过」，别让人以为配置丢了就急着重填。
                // 在清掉之前所有写入都被挡着，那串密文还留在盘上。
                if (settings.credentialsUnreadable) {
                    Notice(
                        text = stringResource(R.string.settings_credentials_unreadable),
                        tone = NoticeTone.Error,
                    )
                    InkTextButton(
                        onClick = { discardCredentials = true },
                        tone = InkButtonTone.Vermilion,
                    ) { Text(stringResource(R.string.settings_credentials_discard)) }
                }

                // 一组一页：点进去是那一组的二级页，页里平铺，不再在这一页上展开收起
                SettingsSection.entries.forEach { section ->
                    SettingRow(
                        title = stringResource(section.title),
                        // 连接那一行的副题回答打开设置时最想问的那一句：现在用的是哪家
                        subtitle = if (section == SettingsSection.CONNECTION) {
                            settings.activeProfile?.displayName() ?: stringResource(section.subtitle)
                        } else {
                            stringResource(section.subtitle)
                        },
                        onClick = { navController.navigate(Screen.SettingsSection(section.name)) },
                        trailing = { NextPageArrow() },
                    )
                }

                InkDivider(Modifier.padding(vertical = 4.dp), brush = true)

                SettingRow(
                    title = stringResource(R.string.settings_about),
                    onClick = { navController.navigate(Screen.About) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                BuildConfig.VERSION_NAME,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetbrainsMono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            NextPageArrow()
                        }
                    },
                )
            }
        }
    }

    if (discardCredentials) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.settings_credentials_discard_title),
            confirmText = stringResource(R.string.settings_credentials_discard),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.discardUnreadableCredentials()
                discardCredentials = false
            },
            onDismiss = { discardCredentials = false },
        ) {
            Text(stringResource(R.string.settings_credentials_discard_body))
        }
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
    // 不走 SettingRow：说明文字和右侧的「去设置」并排时，长句会在词中间断开
    // （「后台进 / 程」）。改成上下：说明占满整行，动作单独一行靠右。
    Column(
        Modifier
            .fillMaxWidth()
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(R.string.settings_battery_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (ignoringBattery) {
                stringResource(R.string.settings_battery_ok)
            } else {
                stringResource(R.string.settings_battery_warn)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (ignoringBattery) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.sea.vermilion,
        )
        Text(
            if (ignoringBattery) {
                stringResource(R.string.settings_battery_status_ok)
            } else {
                stringResource(R.string.settings_battery_status_go)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.sea.seaDeep,
            modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
        )
    }
}

/**
 * 跟随系统 / 昼 / 夜。跟随系统是默认，排第一（和语言选择器同一规矩）。
 *
 * 真正会换形态时走「换形」：一团新形态颜色的墨从这个分段控件的位置落下、涨满整屏，
 * 主题在遮盖之下切换，然后墨化开。目标形态和当前一样（比如从「浅色」切到白天的「跟随系统」）
 * 就直接写设置，不演。
 */
/**
 * 把整台设备的共享存储交给 agent。
 *
 * 两个条件：这里的开关 + 系统设置里的「所有文件访问权限」。开关只是意愿，
 * 权限才是真的能不能读到，所以**开关的显示态取两者的与** —— 用户在系统里把权限收回之后，
 * 这一行要立刻退回关闭，而不是继续显示"已开启"却什么都读不到。
 *
 * 权限状态每次回到前台重查（去系统设置授权完回来就是这条路径），和 [BatteryRow] 同一套。
 */
@Composable
private fun DeviceStorageRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasDeviceStorageAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasDeviceStorageAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val on = enabled && granted
    // 开的时候先落设置再去要权限：用户在系统那一页点了允许、回来就直接是开着的，
    // 不必再回来点一次开关
    val toggle = {
        if (on) {
            onToggle(false)
        } else {
            onToggle(true)
            if (!granted) {
                deviceStorageSettingsIntent(context)?.let { intent ->
                    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
    }

    SettingRow(
        title = stringResource(R.string.settings_device_storage_title),
        // 开着的时候不重复说一遍"能读写全部文件"——那是上面那句话的意思。这里只说
        // 两件他一定会撞上的事：挂在哪、哪块拿不到
        subtitle = when {
            on -> stringResource(R.string.settings_device_storage_on)
            enabled && !granted -> stringResource(R.string.settings_device_storage_need_permission)
            else -> stringResource(R.string.settings_device_storage_off)
        },
        // 开着的时候副题发朱：这是全 App 唯一一处"agent 能动 /workspace 以外的东西"，
        // 得让它在设置页上一眼看得见
        subtitleColor = if (on || (enabled && !granted)) {
            MaterialTheme.sea.vermilion
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        onClick = toggle,
        trailing = { InkSwitch(checked = on, onCheckedChange = { toggle() }) },
    )
}

/**
 * 让 agent 读屏幕、替人点击输入。
 *
 * 和 [DeviceStorageRow] 是同一套两段式（开关 = 意愿，系统权限 = 真的能不能），但这里
 * **开关的显示态不取两者的与**：无障碍没授权时工具依然挂在 CLI 上，只是每次调用都回
 * 一句「还没授权」（见 `DeviceMcpRegistrar.apply`）。所以开着就显示开着，差的那一步
 * 用副题说出来，而不是把开关弹回去——弹回去的话用户会以为是自己没点上。
 *
 * 副题里那句「开关是灰的怎么办」不能省：Android 13+ 对侧载应用默认把无障碍开关置灰，
 * 而 Min 正是 GitHub APK 分发，每个人都会撞上。
 */
@Composable
private fun DeviceControlRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(MinAccessibilityService.connected()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = MinAccessibilityService.connected()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val toggle = {
        val next = !enabled
        onToggle(next)
        if (next && !granted) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    SettingRow(
        title = stringResource(R.string.device_control_title),
        subtitle = when {
            enabled && granted -> stringResource(R.string.device_control_on)
            enabled -> stringResource(R.string.device_control_need_permission) + "\n" +
                stringResource(R.string.device_control_restricted)
            else -> stringResource(R.string.device_control_off)
        },
        subtitleColor = if (enabled) {
            MaterialTheme.sea.vermilion
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        onClick = toggle,
        trailing = { InkSwitch(checked = enabled, onCheckedChange = { toggle() }) },
    )
}

/**
 * 设备写操作放行名单。默认拦住支付/银行/短信；人勾上的应用，agent 可以代为操作。
 * 只在「让 agent 操作这台手机」开着时出现。
 */
@Composable
private fun DeviceAllowlist(
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.device_control_allowlist_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.device_control_allowlist_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PackagePolicyGuard.SUGGESTED_ALLOWLIST.forEach { (pkg, label) ->
            val on = selected.any { it.equals(pkg, ignoreCase = true) }
            SettingRow(
                title = label,
                subtitle = pkg,
                onClick = {
                    onChange(if (on) selected.filterNot { it.equals(pkg, ignoreCase = true) }.toSet() else selected + pkg)
                },
                trailing = { InkSwitch(checked = on, onCheckedChange = { checked ->
                    onChange(if (checked) selected + pkg else selected.filterNot { it.equals(pkg, ignoreCase = true) }.toSet())
                }) },
            )
        }
        val extras = selected.filter { pkg ->
            PackagePolicyGuard.SUGGESTED_ALLOWLIST.none { it.first.equals(pkg, ignoreCase = true) }
        }
        extras.forEach { pkg ->
            SettingRow(
                title = pkg,
                onClick = { onChange(selected - pkg) },
                trailing = { InkSwitch(checked = true, onCheckedChange = { onChange(selected - pkg) }) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InkTextField(
                value = custom,
                onValueChange = { custom = it.trim() },
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.device_control_allowlist_add),
                singleLine = true,
            )
            InkTextButton(
                enabled = custom.contains('.') && selected.none { it.equals(custom, ignoreCase = true) },
                onClick = {
                    onChange(selected + custom)
                    custom = ""
                },
            ) { Text(stringResource(R.string.device_control_allowlist_add_action)) }
        }
    }
}

/**
 * 虚拟屏档：不另装 Shizuku，用本机壳服务建一块看不见的屏。
 *
 * 副题必须写清「重启后要再拉起」——否则会被当成静默后台权限。
 * 拉起优先走无线调试扫端口；扫不到就让用户复制 adb 命令到电脑。
 */
@Composable
private fun VirtualDisplayRow(vm: SettingsVM) {
    val context = LocalContext.current
    val privState by vm.privilegedState.collectAsStateWithLifecycle()
    val session by vm.agentDisplayActive.collectAsStateWithLifecycle()

    val subtitle = when (privState) {
        PrivilegedClient.State.Ready -> {
            val active = session
            if (active != null) {
                stringResource(R.string.device_virtual_session_on, active.displayId)
            } else {
                stringResource(R.string.device_virtual_ready)
            }
        }
        PrivilegedClient.State.Starting -> stringResource(R.string.device_virtual_starting)
        PrivilegedClient.State.Failed -> stringResource(
            R.string.device_virtual_failed,
            vm.lastPrivilegedError ?: "?",
        )
        PrivilegedClient.State.Disconnected -> stringResource(R.string.device_virtual_off) +
            "\n" + stringResource(R.string.device_virtual_need_adb)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingRow(
            title = stringResource(R.string.device_virtual_title),
            subtitle = subtitle,
            subtitleColor = when (privState) {
                PrivilegedClient.State.Failed -> MaterialTheme.sea.vermilion
                PrivilegedClient.State.Ready -> MaterialTheme.sea.seaDeep
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InkTextButton(onClick = { vm.startPrivilegedViaWireless() }) {
                Text(stringResource(R.string.device_virtual_start))
            }
            InkTextButton(
                onClick = {
                    val cmd = vm.privilegedLaunchCommand()
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("min-privileged", cmd))
                    Toast.makeText(
                        context,
                        context.getString(R.string.device_virtual_cmd_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            ) {
                Text(stringResource(R.string.device_virtual_copy_cmd))
            }
        }
        if (privState == PrivilegedClient.State.Ready) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (session == null) {
                    InkTextButton(onClick = { vm.startAgentDisplaySession() }) {
                        Text(stringResource(R.string.device_virtual_session_start))
                    }
                } else {
                    InkTextButton(
                        onClick = { vm.stopAgentDisplaySession() },
                        tone = InkButtonTone.Vermilion,
                    ) {
                        Text(stringResource(R.string.device_virtual_session_stop))
                    }
                }
            }
        }
    }
}


/** 设置的三组。标题 / 副题沿用原来折叠组的文案 */
enum class SettingsSection(@androidx.annotation.StringRes val title: Int, @androidx.annotation.StringRes val subtitle: Int) {
    CONNECTION(R.string.settings_group_connection, R.string.settings_group_connection_sub),
    DEVICE(R.string.settings_group_device, R.string.settings_group_device_sub),
    LOOK(R.string.settings_group_look, R.string.settings_group_look_sub),
}

/**
 * 设置的二级页：一组一页，页里平铺。
 *
 * 以前三组是同一页上的折叠块，展开一组、其余几组的标题就被推到屏幕外；改成一般设置 App 的层级：
 * 首页是入口，点进来是这一组。只有真该收着的东西（关于页的版本历史、环境面板的「修复」）才折叠。
 */
@Composable
fun SettingsSectionPage(section: String, vm: SettingsVM = koinViewModel()) {
    val group = SettingsSection.entries.firstOrNull { it.name == section } ?: SettingsSection.CONNECTION
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            InkTopBar(
                title = stringResource(group.title),
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
                when (group) {
                    SettingsSection.CONNECTION -> ConnectionSettings(vm, settings)
                    SettingsSection.DEVICE -> DeviceSettings(vm, settings)
                    SettingsSection.LOOK -> LookSettings(vm, settings)
                }
            }
        }
    }
}

@Composable
private fun ConnectionSettings(vm: SettingsVM, settings: AppSettings) {
    val navController = LocalNavController.current
    SettingRow(
        title = stringResource(R.string.providers_title),
        subtitle = settings.activeProfile?.displayName()
            ?: stringResource(R.string.settings_connection_empty),
        enabled = !settings.credentialsUnreadable,
        onClick = { navController.navigate(Screen.Providers) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                settings.activeProfile?.let { profile ->
                    Text(
                        profile.maskedToken(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NextPageArrow()
            }
        },
    )
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
}

@Composable
private fun DeviceSettings(vm: SettingsVM, settings: AppSettings) {
    DeviceStorageRow(
        enabled = settings.shareDeviceStorage,
        onToggle = vm::setShareDeviceStorage,
    )
    DeviceControlRow(
        enabled = settings.controlDevice,
        onToggle = vm::setControlDevice,
    )
    if (settings.controlDevice) {
        DeviceAllowlist(settings.deviceWriteAllowlist, vm::setDeviceWriteAllowlist)
    }
    VirtualDisplayRow(vm = vm)
    BatteryRow()
}

@Composable
private fun LookSettings(vm: SettingsVM, settings: AppSettings) {
    // 单独成页之后三排分段钮没有上下文了，各配一个小标题
    SectionTitle(stringResource(R.string.settings_theme_label))
    ThemePicker(current = settings.themeMode, onPick = vm::setThemeMode)
    SectionTitle(stringResource(R.string.settings_skin_label))
    SkinPicker(current = settings.skin, onPick = vm::setSkin)
    SectionTitle(stringResource(R.string.settings_language_label))
    LanguagePicker(current = settings.appLanguage, onPick = vm::setAppLanguage)
}

/** 行尾那个「点了会进下一页」的箭头 */
@Composable
internal fun NextPageArrow() {
    Icon(
        HugeIcons.ArrowRight01,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ThemePicker(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    val modes = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
    )
    val systemDark = isSystemInDarkTheme()
    FormSwitchingSegmented(
        options = modes.map { it.second },
        selected = modes.indexOfFirst { it.first == current }.coerceAtLeast(0),
        targetDark = { index ->
            when (modes[index].first) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> systemDark
            }
        },
        targetStyle = { LocalSkin.current.style },
        onSelect = { onPick(modes[it].first) },
    )
}

/**
 * 海 / 云 / Anthropic。
 *
 * 云以前是 Codex 页专属的一张皮，现在和另外两套平级。换风格和换昼夜是同一类事
 * （界面整个变了个样），所以走同一套换形动画 —— 见 [FormSwitchingSegmented]。
 */
@Composable
private fun SkinPicker(current: SkinStyle, onPick: (SkinStyle) -> Unit) {
    val skins = listOf(
        SkinStyle.SEA to stringResource(R.string.settings_skin_sea),
        SkinStyle.CLOUD to stringResource(R.string.settings_skin_cloud),
        SkinStyle.ANTHROPIC to stringResource(R.string.settings_skin_anthropic),
    )
    val currentDark = LocalDarkMode.current
    FormSwitchingSegmented(
        options = skins.map { it.second },
        selected = skins.indexOfFirst { it.first == current }.coerceAtLeast(0),
        targetDark = { currentDark },
        targetStyle = { skins[it].first },
        onSelect = { onPick(skins[it].first) },
    )
}

/**
 * 一个会演「换形」的分段控件：昼夜和风格共用。
 *
 * 目标形态和当前**完全一样**（两个维度都没变）时直接写设置，不演 —— 比如从
 * 「浅色」切到白天的「跟随系统」，画面上什么都不会变，演一遍只是白闪一下。
 */
@Composable
private fun FormSwitchingSegmented(
    options: List<String>,
    selected: Int,
    targetDark: @Composable (Int) -> Boolean,
    targetStyle: @Composable (Int) -> SkinStyle,
    onSelect: (Int) -> Unit,
) {
    val formSwitch = LocalFormSwitch.current
    val currentDark = LocalDarkMode.current
    val currentStyle = LocalSkin.current.style
    // @Composable 的参数不能在回调里调，先在组合阶段把三个选项的目标态算出来
    val targets = options.indices.map { targetDark(it) to targetStyle(it) }
    var origin by remember { mutableStateOf<Offset?>(null) }
    InkSegmented(
        options = options,
        selected = selected,
        onSelect = { index ->
            val (dark, style) = targets[index]
            if (dark == currentDark && style == currentStyle) {
                onSelect(index)
            } else {
                formSwitch.switch(origin, dark, style) { onSelect(index) }
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
