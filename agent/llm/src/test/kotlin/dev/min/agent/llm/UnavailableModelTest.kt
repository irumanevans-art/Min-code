package dev.min.agent.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnavailableModelTest {
    @Test
    fun `404 mentioning the model is unavailable but a bare 404 is not`() {
        assertTrue(UnavailableModel.matches(404, """{"error":{"message":"The model `gpt-9` does not exist"}}"""))
        assertTrue(UnavailableModel.matches(404, """{"type":"error","error":{"type":"not_found_error","message":"model: claude-old"}}"""))
        // 地址写错的 404：没有「model」字样，是请求有问题，换模型也一样挂
        assertFalse(UnavailableModel.matches(404, "<html>404 Not Found</html>"))
        assertFalse(UnavailableModel.matches(404, "denied"))
    }

    @Test
    fun `retired and decommissioned wording is recognised on other 4xx`() {
        assertTrue(UnavailableModel.matches(400, "The model claude-2 has been deprecated and is no longer available"))
        assertTrue(UnavailableModel.matches(410, "this model was retired on 2026-06-01"))
        assertTrue(UnavailableModel.matches(400, """{"code":"model_not_found"}"""))
        assertFalse(UnavailableModel.matches(400, "messages.0.content: Input should be a valid list"))
    }

    @Test
    fun `auth rate limit timeout and size statuses never count even when the body says model`() {
        for (status in listOf(401, 403, 408, 413, 429)) {
            assertFalse("status $status", UnavailableModel.matches(status, "model_not_found"))
        }
        assertFalse(UnavailableModel.matches(500, "model_not_found"))
        assertFalse(UnavailableModel.matches(529, "model overloaded"))
    }

    @Test
    fun `stream errors carry no status and rely on the wording alone`() {
        assertTrue(UnavailableModel.matches(null, "model_not_found: nope"))
        assertFalse(UnavailableModel.matches(null, "something else went wrong"))
    }
}
