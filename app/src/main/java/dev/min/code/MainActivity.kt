package dev.min.code

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.min.code.core.device.MinAccessibilityService
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.service.EXTRA_OPEN_CLAUDE_CODE
import dev.min.code.core.service.EXTRA_OPEN_CODEX
import dev.min.code.core.settings.AppLanguage
import dev.min.code.core.settings.AppLocale
import dev.min.code.core.settings.LocalePrefs
import dev.min.code.core.settings.ProviderLinkInbox
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.isProviderDeepLink
import dev.min.code.core.settings.SkinStyle
import dev.min.code.core.settings.ThemeMode
import dev.min.code.ui.about.AboutPage
import dev.min.code.ui.components.InkToastHost
import dev.min.code.ui.components.LoadingScreen
import dev.min.code.ui.components.LocalToaster
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.components.rememberInkToaster
import dev.min.code.ui.files.WorkspaceDetailPage
import dev.min.code.ui.codex.CodexPage
import dev.min.code.ui.files.WorkspaceFileEditorPage
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.nav.Navigator
import dev.min.code.ui.nav.Screen
import dev.min.code.ui.session.ClaudeCodePage
import dev.min.code.ui.providers.ProvidersPage
import dev.min.code.ui.settings.SettingsPage
import dev.min.code.ui.settings.SettingsSectionPage
import dev.min.code.ui.terminal.WorkspaceTerminalPage
import dev.min.code.ui.theme.FormSwitchController
import dev.min.code.ui.theme.FormSwitchHost
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.LocalFormSwitch
import dev.min.code.ui.theme.LocalTilt
import dev.min.code.ui.theme.MinTheme
import dev.min.code.ui.theme.rememberTilt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.android.ext.android.inject
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource

class MainActivity : ComponentActivity() {
    private val settingsStore: SettingsStore by inject()

    /**
     * attachBaseContext 时用的语言。API 33+ 永远是 SYSTEM（那边语言归系统管，
     * Configuration 已经是改好的），26..32 才是真值 —— 设置里换语言之后要拿它比对，
     * 不一样就重建，否则包在 base context 里的旧 locale 会一直用到下次冷启动。
     */
    private var attachedLanguage: AppLanguage = AppLanguage.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        attachedLanguage = LocalePrefs.read(newBase)
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    /** 当前回退栈；onNewIntent 拿它把通知点击路由到会话页 */
    private var navStack: MutableList<NavKey>? = null

    /** 设置读到之前系统 Splash 不撤：否则先按默认浅色画一帧，再翻成深色，会闪一下 */
    @Volatile private var settingsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !settingsLoaded }
        // 标记轻微退场，避免冷启动时出现放大的深色遮挡。
        splash.setOnExitAnimationListener { provider ->
            val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
            provider.iconView.animate()
                .scaleX(1.04f).scaleY(1.04f).alpha(0f)
                .setDuration(180).setInterpolator(ease)
                .start()
            provider.view.animate()
                .alpha(0f)
                .setDuration(220).setInterpolator(ease)
                .withEndAction { provider.remove() }
                .start()
        }
        setContent {
            val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
            val loaded = settings != null
            SideEffect { if (loaded) settingsLoaded = true }
            // API 26..32 换语言要自己重建：locale 是在 attachBaseContext 里包进 base context 的，
            // 不重建就一直是启动那一刻的那套字。33+ 由系统重建，这里恒不成立。
            val language = settings?.appLanguage
            LaunchedEffect(language) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                    language != null && language != attachedLanguage
                ) {
                    recreate()
                }
            }
            // 调试钩子：`adb shell am start … --ez min.debug.loading true` 把「铺纸研墨」加载页定住 4 秒，
            // 供人眼核对——正常情况下环境判定只要几十毫秒，这一屏根本来不及看。正式包忽略这个 extra
            var hold by remember { mutableStateOf(BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_DEBUG_LOADING, false) == true) }
            LaunchedEffect(hold) {
                if (hold) {
                    delay(4_000)
                    hold = false
                }
            }
            // 设置还没读出来时的兜底和默认值同一条：跟随系统 + 海，
            // 免得从没选过的人先闪一帧浅色、或者选了别的风格的人先闪一帧蓝
            MinTheme(
                mode = settings?.themeMode ?: ThemeMode.SYSTEM,
                style = settings?.skin ?: SkinStyle.SEA,
            ) {
                if (hold) LoadingScreen(detail = "debug · $EXTRA_DEBUG_LOADING") else Root()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeProviderLink(intent)
        // 通知（保活 / 审批提醒）点进来直接落到会话页；它是首页，清到栈底即可
        if (intent.getBooleanExtra(EXTRA_OPEN_CLAUDE_CODE, false)) {
            navStack?.let { stack ->
                while (stack.size > 1) stack.removeLastOrNull()
            }
        }
        // Codex 的通知落 Codex 页：清栈后把它顶上去
        if (intent.getBooleanExtra(EXTRA_OPEN_CODEX, false)) {
            navStack?.let { stack ->
                while (stack.size > 1) stack.removeLastOrNull()
                if (stack.lastOrNull() != Screen.Codex) stack.add(Screen.Codex)
            }
        }
    }

    /**
     * `minc://` / `ccswitch://` 进来：链接先放进收件格，再把供应商页顶上去，由它开确认框。
     *
     * **这里不入库**。链接可以来自任何地方（聊天记录、网页、二维码），而它带的是一个
     * 会被立刻拿去发请求的 key —— 必须有人看一眼再点头，见 `ProviderDeepLink.kt`。
     */
    private fun routeProviderLink(intent: Intent) {
        val link = intent.data?.toString()?.takeIf { isProviderDeepLink(it) } ?: return
        ProviderLinkInbox.offer(link)
        navStack?.let { stack ->
            while (stack.size > 1) stack.removeLastOrNull()
            stack.add(Screen.Providers)
        }
    }

    @Composable
    private fun Root() {
        val backStack = rememberNavBackStack(Screen.Session)
        val navigator = remember(backStack) { Navigator(backStack) }
        LaunchedEffect(backStack) {
            navStack = backStack
            // 冷启动那一次：栈是在这里才有的，onCreate 时还没有，routeProviderLink 无处可推
            routeProviderLink(intent)
        }
        val (toaster, toastState) = rememberInkToaster()
        val tilt = rememberTilt()
        val formSwitch = remember { FormSwitchController() }
        val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()

        CompositionLocalProvider(
            LocalNavController provides navigator,
            LocalToaster provides toaster,
            LocalTilt provides tilt,
            // LocalSeaField 由 MinTheme 提供 —— 它要跟着风格换（纹理的构图不同，铺法也不同），
            // 留在这里的话换风格时海还是按上一张图的倍率铺的
            LocalFormSwitch provides formSwitch,
        ) {
            FormSwitchHost(formSwitch) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    NavDisplay(
                        backStack = backStack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onBack = { backStack.removeLastOrNull() },
                        // 页面切换 = 落墨 / 飞白：新页从右侧 1/8 处淡入落定，旧页淡出微缩。
                        // 不做整页横滑——那是"翻页"，而这里是同一本日志上换一张纸
                        transitionSpec = {
                            if (backStack.size == 1) {
                                fadeIn(InkMotion.effect()) togetherWith fadeOut(InkMotion.effectFast())
                            } else {
                                (fadeIn(InkMotion.effect()) + slideInHorizontally(InkMotion.spatial<IntOffset>()) { it / 8 }) togetherWith
                                    (fadeOut(InkMotion.effectFast()) + scaleOut(targetScale = 0.98f, animationSpec = InkMotion.effect()))
                            }
                        },
                        popTransitionSpec = {
                            (fadeIn(InkMotion.effect()) + scaleIn(initialScale = 0.98f, animationSpec = InkMotion.spatial())) togetherWith
                                (fadeOut(InkMotion.effectFast()) + slideOutHorizontally(InkMotion.spatial<IntOffset>()) { it / 8 })
                        },
                        predictivePopTransitionSpec = {
                            (fadeIn(InkMotion.effect()) + scaleIn(initialScale = 0.98f, animationSpec = InkMotion.spatial())) togetherWith
                                (fadeOut(InkMotion.effectFast()) + slideOutHorizontally(InkMotion.spatial<IntOffset>()) { it / 8 })
                        },
                        entryProvider = entryProvider {
                            entry<Screen.Session> { ClaudeCodePage() }
                            entry<Screen.Codex> { CodexPage() }
                            entry<Screen.Files> { WorkspaceDetailPage(workspaceId) }
                            entry<Screen.Terminal> { WorkspaceTerminalPage(workspaceId) }
                            entry<Screen.FileEditor> { key ->
                                WorkspaceFileEditorPage(
                                    id = workspaceId,
                                    area = WorkspaceStorageArea.valueOf(key.area),
                                    path = key.path,
                                )
                            }
                            entry<Screen.Settings> { SettingsPage() }
                            entry<Screen.SettingsSection> { key -> SettingsSectionPage(key.section) }
                            entry<Screen.Providers> { ProvidersPage() }
                            entry<Screen.About> { AboutPage() }
                        },
                    )
                    InkToastHost(toastState, Modifier.align(Alignment.BottomCenter))
                    AccessibilityReenablePrompt()
                }
            }
        }
    }

    /**
     * 覆盖安装会解绑无障碍。意愿仍开着、服务却没连上、这一版还没弹过 → 启动时弹一次，
     * 点「去开启」跳系统无障碍页。点取消也记成这一版已提示，避免每次冷启动都烦。
     */
    @Composable
    private fun AccessibilityReenablePrompt() {
        val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
        val current = settings ?: return
        val should = current.controlDevice &&
            !MinAccessibilityService.connected() &&
            current.a11yPromptVersion != BuildConfig.VERSION_CODE
        var show by remember(should) { mutableStateOf(should) }
        if (!show) return
        val scope = rememberCoroutineScope()
        fun dismiss() {
            show = false
            scope.launch { settingsStore.setA11yPromptVersion(BuildConfig.VERSION_CODE) }
        }
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.device_control_a11y_reenable_title),
            confirmText = stringResource(R.string.device_control_a11y_reenable_go),
            dismissText = stringResource(R.string.common_cancel),
            destructive = false,
            onConfirm = {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                dismiss()
            },
            onDismiss = { dismiss() },
        ) {
            Text(stringResource(R.string.device_control_a11y_reenable_body))
        }
    }
}

private const val EXTRA_DEBUG_LOADING = "min.debug.loading"
