package dev.min.code

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.service.EXTRA_OPEN_CLAUDE_CODE
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.SettingsStore
import dev.min.code.ui.about.AboutPage
import dev.min.code.ui.components.LocalToaster
import dev.min.code.ui.components.rememberSystemToaster
import dev.min.code.ui.files.WorkspaceDetailPage
import dev.min.code.ui.files.WorkspaceFileEditorPage
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.nav.Navigator
import dev.min.code.ui.nav.Screen
import dev.min.code.ui.session.ClaudeCodePage
import dev.min.code.ui.settings.SettingsPage
import dev.min.code.ui.terminal.WorkspaceTerminalPage
import dev.min.code.ui.theme.MinTheme
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val settingsStore: SettingsStore by inject()

    /** 当前回退栈；onNewIntent 拿它把通知点击路由到会话页 */
    private var navStack: MutableList<NavKey>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            MinTheme(mode = settings.themeMode) {
                Root()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 通知（保活 / 审批提醒）点进来直接落到会话页；它是首页，清到栈底即可
        if (intent.getBooleanExtra(EXTRA_OPEN_CLAUDE_CODE, false)) {
            navStack?.let { stack ->
                while (stack.size > 1) stack.removeLastOrNull()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Root() {
        val backStack = rememberNavBackStack(Screen.Session)
        val navigator = remember(backStack) { Navigator(backStack) }
        LaunchedEffect(backStack) { navStack = backStack }
        val toaster = rememberSystemToaster()
        val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()

        CompositionLocalProvider(
            LocalNavController provides navigator,
            LocalToaster provides toaster,
        ) {
            NavDisplay(
                backStack = backStack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                onBack = { backStack.removeLastOrNull() },
                transitionSpec = {
                    if (backStack.size == 1) fadeIn() togetherWith fadeOut()
                    else slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 } + fadeOut()
                },
                popTransitionSpec = {
                    slideInHorizontally { -it / 3 } + fadeIn() togetherWith slideOutHorizontally { it }
                },
                predictivePopTransitionSpec = {
                    slideInHorizontally { -it / 3 } + fadeIn() togetherWith slideOutHorizontally { it }
                },
                entryProvider = entryProvider {
                    entry<Screen.Session> { ClaudeCodePage() }
                    entry<Screen.Setup> { ClaudeCodePage() }
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
                    entry<Screen.About> { AboutPage() }
                },
            )
        }
    }
}
