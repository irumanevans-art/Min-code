package dev.min.code.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 订阅登录的授权页绝不能落回 Min 自己（那是 Min 进程里的 WebView，登录态和授权 code 都会落在 Min 手里）。
 */
class ExternalBrowserTest {

    private val self = "dev.min.code"

    @Test
    fun no_candidates_means_no_browser() {
        assertEquals(BrowserPick.None, pickBrowser(emptyList(), null, self))
    }

    @Test
    fun only_ourselves_means_no_browser() {
        assertEquals(BrowserPick.None, pickBrowser(listOf(self), self, self))
    }

    @Test
    fun without_ourselves_the_system_resolves() {
        // 默认浏览器、或者没设默认时的选择器（"android"），都交给系统
        assertEquals(BrowserPick.Implicit, pickBrowser(listOf("com.android.chrome"), "com.android.chrome", self))
        assertEquals(BrowserPick.Implicit, pickBrowser(listOf("com.android.chrome", "org.mozilla.firefox"), "android", self))
    }

    @Test
    fun when_we_are_a_candidate_pin_the_default_browser() {
        val candidates = listOf(self, "org.mozilla.firefox", "com.android.chrome")
        assertEquals(BrowserPick.Package("com.android.chrome"), pickBrowser(candidates, "com.android.chrome", self))
    }

    @Test
    fun when_we_are_the_default_pin_another_browser() {
        val candidates = listOf(self, "org.mozilla.firefox")
        assertEquals(BrowserPick.Package("org.mozilla.firefox"), pickBrowser(candidates, self, self))
        // 选择器里混着我们：也不能交给选择器，免得人点到 Min
        assertEquals(BrowserPick.Package("org.mozilla.firefox"), pickBrowser(candidates, "android", self))
    }
}
