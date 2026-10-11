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

    @Test
    fun `the scheme check ignores case and does not fall for a data prefix inside a host`() {
        assertTrue(ArtifactViewerPolicy.allowRequest("DATA:text/plain,hi"))
        assertFalse(ArtifactViewerPolicy.allowRequest("https://data.example.com/"))
        assertEquals(false, ArtifactViewerPolicy.allowRequest(""))
    }
}
