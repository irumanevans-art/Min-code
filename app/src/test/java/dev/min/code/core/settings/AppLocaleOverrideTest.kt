package dev.min.code.core.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [AppLocale.needsOverride]：非 Activity 的文字（内核文案、通知）要不要另包一层 App 语言。
 * 起因：英文系统 + App 选中文时，进程被无障碍服务先拉起，Application 资源是英文，
 * 权限模式和「本会话总是允许」显示成英文。
 */
class AppLocaleOverrideTest {

    @Test
    fun `app chose Chinese while resources are English - override`() {
        assertTrue(AppLocale.needsOverride(current = Locale.ENGLISH, wanted = Locale.forLanguageTag("zh-CN")))
    }

    @Test
    fun `resources already in the chosen language - no override`() {
        assertFalse(AppLocale.needsOverride(current = Locale.forLanguageTag("zh-CN"), wanted = Locale.forLanguageTag("zh-CN")))
    }

    @Test
    fun `same language different region counts as already right`() {
        assertFalse(AppLocale.needsOverride(current = Locale.forLanguageTag("zh-Hans-CN"), wanted = Locale.forLanguageTag("zh-CN")))
    }

    @Test
    fun `follow system - never override`() {
        assertFalse(AppLocale.needsOverride(current = Locale.ENGLISH, wanted = null))
    }

    @Test
    fun `no current locale known - override to the chosen one`() {
        assertTrue(AppLocale.needsOverride(current = null, wanted = Locale.ENGLISH))
    }
}
