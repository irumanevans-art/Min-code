package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProbeClassifyTest {

    @Test
    fun http_404_and_405_mean_no_models_endpoint_not_unreachable() {
        assertEquals(RelayProbeResult.NoModelsEndpoint, classifyProbeHttpStatus(404))
        assertEquals(RelayProbeResult.NoModelsEndpoint, classifyProbeHttpStatus(405))
    }

    @Test
    fun http_401_and_403_mean_unauthorized() {
        assertEquals(RelayProbeResult.Unauthorized, classifyProbeHttpStatus(401))
        assertEquals(RelayProbeResult.Unauthorized, classifyProbeHttpStatus(403))
    }

    @Test
    fun other_http_errors_stay_unreachable() {
        val result = classifyProbeHttpStatus(500)
        assertTrue(result is RelayProbeResult.Unreachable)
        assertEquals("HTTP 500", (result as RelayProbeResult.Unreachable).reason)
    }
}
