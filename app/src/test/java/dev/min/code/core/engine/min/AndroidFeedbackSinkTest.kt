package dev.min.code.core.engine.min

import dev.min.agent.spi.FeedbackDraft
import dev.min.code.ui.viewer.ViewerInbox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SendFeedback 在手机上的出口：排队的草稿在写入时就用 App 的已知秘密去凭证（名单由引擎绑进收件箱）。 */
class AndroidFeedbackSinkTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a queued draft is scrubbed with the bound secrets before it is stored`() = runBlocking {
        ViewerInbox.bindDrafts(tmp.newFolder("drafts"))
        ViewerInbox.bindSecrets { setOf("relay-token-plain-7a1b2c3d") }
        assertTrue(AndroidFeedbackSink().queue(FeedbackDraft("t", "key relay-token-plain-7a1b2c3d", sessionLog = "{\"x\":\"relay-token-plain-7a1b2c3d\"}")))
        val stored = ViewerInbox.drafts.value.single().draft
        assertFalse(stored.body + stored.sessionLog, (stored.body + stored.sessionLog).contains("relay-token-plain"))
        ViewerInbox.bindSecrets { emptySet() }
    }

    @Test fun `a secret source that fails leaves only the shape fallback, and the draft is still queued`() = runBlocking {
        ViewerInbox.bindDrafts(tmp.newFolder("drafts2"))
        ViewerInbox.bindSecrets { error("Keystore 解不开") }
        assertEquals(emptySet<String>(), ViewerInbox.knownSecrets())
        assertTrue(AndroidFeedbackSink().queue(FeedbackDraft("t", "正文 sk-ant-api03-SHAPEDKEY0123456789")))
        assertFalse(ViewerInbox.drafts.value.single().draft.body.contains("SHAPEDKEY"))
        ViewerInbox.bindSecrets { emptySet() }
    }
}
