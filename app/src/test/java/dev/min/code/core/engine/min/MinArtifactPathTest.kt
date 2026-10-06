package dev.min.code.core.engine.min

import dev.min.agent.protocol.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MinArtifactPathTest {
    private val session = SessionId("s_1")

    @Test fun subdirectoryNamesUsedByTheKernelAreAccepted() {
        assertEquals("/root/.min/artifacts/s_1/tool-results/c1.txt", artifactPath(session, "tool-results/c1.txt").value)
        assertEquals("/root/.min/artifacts/s_1/mcp/c1-1-image.png", artifactPath(session, "mcp/c1-1-image.png").value)
        assertEquals("/root/.min/artifacts/s_1/plain.txt", artifactPath(session, "plain.txt").value)
    }

    @Test fun namesThatCouldEscapeTheArtifactDirectoryAreRejected() {
        for (bad in listOf("", "..", ".", "a/../b", "../x", "/abs", "a//b", "a/", "a b", "a\b")) {
            try {
                artifactPath(session, bad)
                fail("应拒绝：$bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
