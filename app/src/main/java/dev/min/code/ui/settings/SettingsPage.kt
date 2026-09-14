package dev.min.code.ui.settings

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.BuildConfig
import dev.min.code.R
import dev.min.code.core.settings.AppLanguage
import dev.min.code.core.settings.ThemeMode
import dev.min.code.ui.components.BackButton
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkIconButton
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkSwitch
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.InkTopBar
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.components.SettingRow
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.nav.Screen
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.LocalDarkMode
import dev.min.code.ui.theme.LocalFormSwitch
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import org.koin.androidx.compose.koinViewModel

/**
 * App 设置。只有四样：连接、npm 源、主题、关于——没有"设置页里的设置页"。
 * token / 地址改完点「应用」才落盘：每敲一个字就写 DataStore 没必要，也让人不确定改没改。
 */
@Composable
fun SettingsPage(vm: SettingsVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    var token by rememberSaveable(settings.token) { mutableStateOf(settings.token) }
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val dirty = token.trim() != settings.token || baseUrl.trim().trimEnd('/') != settings.baseUrl
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
                Text(
                    stringResource(R.string.settings_connection_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    InkTextButton(
                        onClick = {
                            vm.setToken(token.trim())
                            vm.setBaseUrl(baseUrl.trim())
                        },
                        enabled = dirty && token.isNotBlank(),
                    ) { Text(stringResource(R.string.common_apply)) }
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
 * 昼 / 夜 / 跟随系统。
 *
 * 真正会换形态时走「换形」：一团新形态颜色的墨从这个分段控件的位置落下、涨满整屏，
 * 主题在遮盖之下切换，然后墨化开。目标形态和当前一样（比如从「浅色」切到白天的「跟随系统」）
 * 就直接写设置，不演。
 */
@Composable
private fun ThemePicker(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    val modes = listOf(
        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
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
