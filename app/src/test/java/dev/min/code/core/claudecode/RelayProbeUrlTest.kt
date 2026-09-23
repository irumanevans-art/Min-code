package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [fetchRelayModelsPayload] 与 [probeRelay] / [fetchRelayModelIds] 共用的 URL 拼接 */
class RelayProbeUrlTest {

    @Test
    fun base_without_v1_gets_v1_models_and_limit() {
        assertEquals("https://relay.example.com/v1/models?limit=1000", relayModelsUrl("https://relay.example.com"))
    }

    @Test
    fun base_with_trailing_v1_does_not_double_up() {
        // 用户从 OpenAI 兼容中转抄来的地址常带着 /v1 —— 曾经拼出 /v1/v1/models
        val withV1 = relayModelsUrl("https://relay.example.com/v1/")
        val withoutV1 = relayModelsUrl("https://relay.example.com")
        assertEquals(withoutV1, withV1)
        assertTrue(withV1.endsWith("/v1/models?limit=1000"))
    }
}
