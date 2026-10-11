package dev.min.code.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactViewerPolicyTest {
    @Test
    fun `the viewer cannot read local files or content providers`() {
        val s = ArtifactViewerPolicy.settings
        assertFalse(s.allowFileAccess)
        assertFalse(s.allowContentAccess)
        assertFalse(s.allowFileAccessFromFileUrls)
        assertFalse(s.allowUniversalAccessFromFileUrls)
    }

    @Test
    fun `the viewer has no base origin and no dom storage, so nothing is kept or shared with the app`() {
        assertNull(ArtifactViewerPolicy.BASE_URL)
        assertFalse(ArtifactViewerPolicy.settings.domStorageEnabled)
    }

    @Test
    fun `javascript stays on because interactive artifacts need it, and the rest of the sandbox carries the risk`() {
        assertTrue(ArtifactViewerPolicy.settings.javaScriptEnabled)
    }

    @Test
    fun `only inline data and about blank requests are allowed, every network or local request is blocked`() {
        assertTrue(ArtifactViewerPolicy.allowRequest("data:image/png;base64,AAAA"))
        assertTrue(ArtifactViewerPolicy.allowRequest("about:blank"))
        assertFalse(ArtifactViewerPolicy.allowRequest("https://example.com/tracker.js"))
        assertFalse(ArtifactViewerPolicy.allowRequest("http://127.0.0.1:8080/"))
        assertFalse(ArtifactViewerPolicy.allowRequest("file:///data/data/dev.min.code/files/secret"))
        assertFalse(ArtifactViewerPolicy.allowRequest("content://dev.min.code.fileprovider/x"))
        assertFalse(ArtifactViewerPolicy.allowRequest("blob:https://x/1"))
        assertFalse(ArtifactViewerPolicy.allowRequest("javascript:alert(1)"))
    }

    // ---- 请求拦截管不到的出口：WebSocket、fetch 之外的连接由页面自己的 CSP 关掉 ----

    @Test
    fun `the page gets a content security policy that allows no network connection of any kind`() {
        val csp = ArtifactViewerPolicy.CONTENT_SECURITY_POLICY
        val directives = csp.split(';').map { it.trim() }.filter { it.isNotEmpty() }.associate { it.substringBefore(' ') to it.substringAfter(' ', "") }
        assertEquals("'none'", directives["default-src"])
        // shouldInterceptRequest 看不到 WebSocket：connect-src 是唯一能关掉 ws:/wss: 的地方
        val connect = directives.getValue("connect-src")
        for (scheme in listOf("http:", "https:", "ws:", "wss:", "*")) assertFalse(connect, connect.split(' ').contains(scheme))
        for (name in listOf("img-src", "script-src", "style-src", "font-src", "media-src")) {
            val sources = directives.getValue(name).split(' ')
            assertFalse("$name: $sources", sources.any { it == "*" || it == "http:" || it == "https:" })
        }
        assertEquals("'none'", directives["form-action"])
        assertEquals("'none'", directives["base-uri"])
        assertTrue(ArtifactViewerPolicy.settings.blockNetworkLoads)
    }

    @Test
    fun `the policy goes right after the doctype so the page keeps standards mode, or first when there is none`() {
        val meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"${ArtifactViewerPolicy.CONTENT_SECURITY_POLICY}\">"
        assertEquals("<!DOCTYPE html>$meta<html><body>x</body></html>",
            ArtifactViewerPolicy.document("<!DOCTYPE html><html><body>x</body></html>"))
        assertEquals("\uFEFF  <!doctype HTML >$meta\n<p>y</p>", ArtifactViewerPolicy.document("\uFEFF  <!doctype HTML >\n<p>y</p>"))
        assertEquals("$meta<p>z</p>", ArtifactViewerPolicy.document("<p>z</p>"))
    }

    @Test
    fun `the scheme check ignores case and does not fall for a data prefix inside a host`() {
        assertTrue(ArtifactViewerPolicy.allowRequest("DATA:text/plain,hi"))
        assertFalse(ArtifactViewerPolicy.allowRequest("https://data.example.com/"))
        assertEquals(false, ArtifactViewerPolicy.allowRequest(""))
    }
}
