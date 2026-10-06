package dev.min.agent.spi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactNamesTest {

    @Test
    fun `ids that are already safe keep their name`() {
        for (id in listOf("toolu_01AbC", "call_abc-123", "c1", "a.b")) assertEquals(id, ArtifactNames.segment(id))
    }

    @Test
    fun `foreign ids become one safe segment that passes validation`() {
        for (raw in listOf("functions.bash:0", "../../etc/passwd", "a/b", "..", ".", "", "空格 和中文", "x".repeat(500))) {
            val segment = ArtifactNames.segment(raw)
            assertTrue("$raw → $segment", segment.length <= 80)
            assertEquals(segment, ArtifactNames.validate("tool-results/$segment.txt").removePrefix("tool-results/").removeSuffix(".txt"))
        }
    }

    @Test
    fun `ids that clean up to the same text do not collide`() {
        assertNotEquals(ArtifactNames.segment("a:b"), ArtifactNames.segment("a_b"))
        assertNotEquals(ArtifactNames.segment("a:b"), ArtifactNames.segment("a/b"))
        assertEquals(ArtifactNames.segment("a:b"), ArtifactNames.segment("a:b"))
    }

    @Test
    fun `names that could leave the artifact directory are rejected`() {
        for (bad in listOf("", ".", "..", "a/../b", "../x", "/abs", "a//b", "a/", "a b", "a\\b", "a:b")) {
            assertThrows(bad, IllegalArgumentException::class.java) { ArtifactNames.validate(bad) }
        }
        assertEquals("mcp/c1-1-image.png", ArtifactNames.validate("mcp/c1-1-image.png"))
    }
}
