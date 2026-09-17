package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 只测纯映射那一半。真正「有没有落到进程上」测不了 —— 那要么是系统的 LocaleManager，
 * 要么是 attachBaseContext，两个都得在设备上跑。那部分靠实机验（见 1.1.10 的验收记录）。
 */
class AppLocaleTest {
    @Test
    fun tag_of_each_language() {
        assertEquals("zh-CN", AppLocale.tagOf(AppLanguage.ZH))
        assertEquals("en", AppLocale.tagOf(AppLanguage.EN))
        // 跟随系统没有标签：要的是「清空 per-app locale」，不是「设成某种语言」
        assertNull(AppLocale.tagOf(AppLanguage.SYSTEM))
    }

    @Test
    fun from_tag_only_looks_at_primary_subtag() {
        // 系统回给我们的不一定是当初写进去的那个串
        assertEquals(AppLanguage.ZH, AppLocale.fromTag("zh-CN"))
        assertEquals(AppLanguage.ZH, AppLocale.fromTag("zh-Hans-CN"))
        assertEquals(AppLanguage.ZH, AppLocale.fromTag("zh"))
        assertEquals(AppLanguage.EN, AppLocale.fromTag("en-US"))
        assertEquals(AppLanguage.EN, AppLocale.fromTag("EN"))
    }

    @Test
    fun from_tag_rejects_unknown() {
        // 认不出来要返回 null，不能悄悄当成「跟随系统」—— 那会把用户的选择抹掉
        assertNull(AppLocale.fromTag("ja-JP"))
        assertNull(AppLocale.fromTag(""))
    }

    @Test
    fun round_trip() {
        listOf(AppLanguage.ZH, AppLanguage.EN).forEach {
            assertEquals(it, AppLocale.fromTag(AppLocale.tagOf(it)!!))
        }
    }
}
