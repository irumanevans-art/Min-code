package dev.min.agent.ext.plugins

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginVersionsTest {
    @Test fun BUG_semver_numeric_prereleases_sort_numerically() {
        val versions = listOf("1.10.0-beta.2", "1.10.0-beta.10", "1.10.0-alpha", "1.9.0", "1.10.0")
        assertEquals(listOf("1.9.0", "1.10.0-alpha", "1.10.0-beta.2", "1.10.0-beta.10", "1.10.0"), versions.sortedWith(compareBySemanticVersion { it }))
    }
}
