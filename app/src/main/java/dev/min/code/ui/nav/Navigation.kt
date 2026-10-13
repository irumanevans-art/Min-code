package dev.min.code.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 全部页面。会话页是首页；向导在没装好之前顶替它 */
sealed interface Screen : NavKey {
    @Serializable
    data object Session : Screen

    /** 工作区文件页 */
    @Serializable
    data object Files : Screen

    @Serializable
    data object Terminal : Screen

    /** 枚举在返回栈存档里按名字落盘（见 kotlinx.serialization 对枚举的默认处理）：改名 / 删成员会让旧存档读不回来 */
    @Serializable
    data class FileEditor(val area: me.rerere.workspace.WorkspaceStorageArea, val path: String) : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data object Plugins : Screen

    /** 设置的二级页（连接与安装 / 设备 / 外观与语言…）。枚举按名字落盘，同 [FileEditor] 的说明 */
    @Serializable
    data class SettingsSection(val section: dev.min.code.ui.settings.SettingsSection) : Screen

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
