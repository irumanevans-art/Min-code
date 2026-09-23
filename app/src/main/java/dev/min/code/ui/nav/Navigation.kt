package dev.min.code.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 全部页面。会话页是首页；向导在没装好之前顶替它 */
sealed interface Screen : NavKey {
    @Serializable
    data object Session : Screen

    @Serializable
    data object Codex : Screen

    /** 工作区文件页 */
    @Serializable
    data object Files : Screen

    @Serializable
    data object Terminal : Screen

    @Serializable
    data class FileEditor(val area: String, val path: String) : Screen

    @Serializable
    data object Settings : Screen

    /** 设置的二级页。[section] 是 SettingsSection 的 name（连接与安装 / 设备 / 外观与语言） */
    @Serializable
    data class SettingsSection(val section: String) : Screen

    /** 供应商表。从设置页的「连接」区独立出来，那边只留一行入口 */
    @Serializable
    data object Providers : Screen

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
