package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主题默认值：从没选过的设备跟随系统。DataStore 的读取兜底（`KEY_THEME` 缺省 → SYSTEM）
 * 要设备才测得到；但「默认值是什么」这条决策落在数据类默认值上，是纯逻辑，必须有网。
 */
class ThemeModeTest {

    @Test
    fun theme_defaults_to_system_when_never_set() {
        assertEquals(ThemeMode.SYSTEM, AppSettings().themeMode)
    }
}
