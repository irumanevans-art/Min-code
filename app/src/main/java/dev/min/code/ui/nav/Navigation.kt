package dev.min.code.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 全部页面。会话页是首页；向导在没装好之前顶替它 */
sealed interface Screen : NavKey {
    @Serializable
    data object Session : Screen

    @Serializable
    data object Setup : Screen

    /** 工作区文件页；[area] 是 WorkspaceStorageArea.name，[path] 相对路径 */
    @Serializable
    data class Files(val area: String = "FILES", val path: String = "") : Screen

    @Serializable
    data object Terminal : Screen

    @Serializable
    data class FileEditor(val area: String, val path: String) : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data object About : Screen
}

/**
 * 极简的导航器：一个可变回退栈。Navigation3 本身只是渲染栈顶，栈的增删由这里负责。
 * API 名字对齐 RikkaHub 的 Navigator，搬来的页面调用不用改。
 */
class Navigator(private val backStack: MutableList<NavKey>) {
    fun navigate(screen: Screen, launchSingleTop: Boolean = false) {
        if (launchSingleTop && backStack.lastOrNull() == screen) return
        backStack.add(screen)
    }

    fun clearAndNavigate(screen: Screen) {
        backStack.clear()
        backStack.add(screen)
    }

    fun popBackStack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    val current: NavKey? get() = backStack.lastOrNull()
}

val LocalNavController = staticCompositionLocalOf<Navigator> {
    error("No Navigator provided")
}
