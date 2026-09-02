package dev.min.code.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.core.settings.ThemeMode
import dev.min.code.ui.components.BackButton
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.nav.Screen
import dev.min.code.ui.theme.CustomColors
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("连接")
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("ANTHROPIC_BASE_URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("ANTHROPIC_AUTH_TOKEN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                        }
                    },
                )
                Text(
                    "改动只对之后新开的会话生效；正在跑的会话用的还是启动时注入的值。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = {
                            vm.setToken(token.trim())
                            vm.setBaseUrl(baseUrl.trim())
                        },
                        enabled = dirty && token.isNotBlank(),
                    ) { Text("应用") }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                SectionTitle("安装源")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.setUseNpmMirror(!settings.useNpmMirror) },
                ) {
                    Checkbox(checked = settings.useNpmMirror, onCheckedChange = { vm.setUseNpmMirror(it) })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text("用淘宝 npm 源装 / 更新 CLI", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "原生二进制仍按官方 registry 的 sha512 校验，镜像只提供字节。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                SectionTitle("后台运行")
                val context = LocalContext.current
                val powerManager = remember { context.getSystemService(android.os.PowerManager::class.java) }
                var ignoringBattery by remember { mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) }
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                // 从系统设置页回来时重新读一次，不然这一行显示的还是旧值
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            ignoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("电池优化", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (ignoringBattery) "已关闭，会话在后台不易被系统回收"
                            else "开着。vivo / OPPO 这类 ROM 会在切出去几分钟后杀掉后台进程，建议把 Min 加入不优化列表",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ignoringBattery) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        if (ignoringBattery) "已关闭" else "去设置",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                SectionTitle("外观")
                val modes = listOf(ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色", ThemeMode.SYSTEM to "跟随系统")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { vm.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(label) }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Screen.About) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("关于 Min", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        dev.min.code.BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}
