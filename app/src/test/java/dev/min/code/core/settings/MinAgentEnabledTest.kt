package dev.min.code.core.settings

import dev.min.code.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** Min Agent 的实验开关：存过的值优先，没存过时按构建类型（debug 开、release 关）。 */
class MinAgentEnabledTest {
    @Test fun `an unset switch follows the build type`() {
        assertEquals(true, minAgentEnabledOf(null, debugBuild = true))
        assertEquals(false, minAgentEnabledOf(null, debugBuild = false))
    }

    @Test fun `a stored value wins over the build type in both directions`() {
        assertEquals(false, minAgentEnabledOf(false, debugBuild = true))
        assertEquals(true, minAgentEnabledOf(true, debugBuild = false))
    }

    @Test fun `the default of a fresh AppSettings is the build default`() {
        assertEquals(BuildConfig.DEBUG, AppSettings().minAgentEnabled)
    }
}
