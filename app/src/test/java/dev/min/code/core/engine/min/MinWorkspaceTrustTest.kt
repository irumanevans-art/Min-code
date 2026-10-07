package dev.min.code.core.engine.min

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MinWorkspaceTrustTest {
    @Test
    fun `a directory without project config has nothing to trust`() {
        assertNull(probeOf("/workspace/p", ProjectConfigFiles(null, null, null)))
        assertNull("没有 hooks 的 settings 不算", probeOf("/workspace/p", ProjectConfigFiles("""{"permissions":{"allow":["Read"]}}""", null, null)))
    }

    @Test
    fun `hook events from both settings files are merged, empty events are skipped`() {
        val probe = probeOf(
            "/workspace/p",
            ProjectConfigFiles(
                settings = """{"hooks":{"PreToolUse":[{"matcher":"*","hooks":[]}],"Stop":[]}}""",
                local = """{"hooks":{"PreToolUse":[{}],"SessionStart":[{}]}}""",
                mcpJson = null,
            ),
        )!!
        assertEquals(listOf("PreToolUse", "SessionStart"), probe.hookEvents)
        assertEquals(emptyList<String>(), probe.mcpServers)
    }

    @Test
    fun `mcp json server names are listed`() {
        val probe = probeOf("/workspace/p", ProjectConfigFiles(null, null, """{"mcpServers":{"github":{"command":"x"},"db":{"url":"http://h"}}}"""))!!
        assertEquals(listOf("github", "db"), probe.mcpServers)
    }

    @Test
    fun `a broken file is treated as absent instead of asking every time`() {
        assertNull(probeOf("/workspace/p", ProjectConfigFiles("{not json", "[]", "oops")))
    }
}
