package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 风格默认值。理由和 [ThemeModeTest] 一样，但这一条更要紧：主题是「跟随系统」这种
 * 中立选项，风格却是三套长得完全不一样的界面——默认值写错，所有升级上来的人开机
 * 看到的是另一个 App。
 */
class SkinStyleTest {

    @Test
    fun skin_defaults_to_sea_so_an_upgrade_never_reskins_anyone() {
        assertEquals(SkinStyle.SEA, AppSettings().skin)
    }
}
