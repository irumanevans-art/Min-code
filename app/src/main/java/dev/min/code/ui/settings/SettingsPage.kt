package dev.min.code.ui.settings

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
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
import dev.min.code.core.settings.AppLanguage
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.SkinStyle
import dev.min.code.core.settings.ThemeMode
import dev.min.code.ui.components.BackButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkSwitch
import dev.min.code.ui.components.InkTextButton
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
import org.koin.androidx.compose.koinViewModel

/**
 * App 设置。只有五样：连接、npm 源、后台、外观、关于——没有"设置页里的设置页"。
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
                SectionTitle(stringResource(R.string.settings_section_connection))
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
                // 那张表搬进了供应商页（预设库 + 托管开关 + 拖拽排序塞不进这里，
                // 设置页的规矩是「只有四样，没有设置页里的设置页」）。这里只留一行，
                // 副题回答唯一一个在设置页里值得问的问题：现在用的是哪家
                SettingRow(
                    title = stringResource(R.string.providers_title),
                    subtitle = settings.activeProfile?.displayName()
                        ?: stringResource(R.string.settings_connection_empty),
                    enabled = !settings.credentialsUnreadable,
                    onClick = { navController.navigate(Screen.Providers) },
                    trailing = {
                        settings.activeProfile?.let { profile ->
                            Text(
                                profile.maskedToken(),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetbrainsMono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
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
                SkinPicker(current = settings.skin, onPick = vm::setSkin)

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
