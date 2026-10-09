package dev.min.code.core.engine.min

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MinWorkspaceTrustTest {
    @Test
    fun `a directory without project config has nothing to trust`() {
        assertNull(probeOf("/workspace/p", ProjectConfigFiles(null, null, null)))
        assertNull("没有 hooks、也没有放宽权限的 settings 不算", probeOf("/workspace/p", ProjectConfigFiles("""{"permissions":{"deny":["Bash(rm *)"],"allow":[]}}""", null, null)))
    }

    @Test
    fun `project allow rules and extra directories are listed because trusting is what makes them take effect`() {
        val probe = probeOf(
            "/workspace/p",
            ProjectConfigFiles(
                settings = """{"permissions":{"allow":["Bash(git status)"," Read(/etc/**) ","Bash(git status)",42,""],"additionalDirectories":["/sdcard/Download","../shared"]}}""",
                local = """{"permissions":{"allow":["Bash(rm -rf *)"]}}""",
                mcpJson = null,
            ),
        )!!
        assertEquals("去重、去空白、忽略非字符串", listOf("Bash(git status)", "Read(/etc/**)"), probe.allowRules)
        assertEquals(listOf("/sdcard/Download", "../shared"), probe.additionalDirectories)
        assertEquals("只看项目层的 settings.json，local 层不受信任门管", false, probe.allowRules.contains("Bash(rm -rf *)"))
        assertEquals(true, probe.relaxesPermissions)
        assertEquals(emptyList<String>(), probe.hookEvents)
    }

    @Test
    fun `a directory that only asks for more rights still gets the card`() {
        val probe = probeOf("/workspace/p", ProjectConfigFiles("""{"permissions":{"additionalDirectories":["/data"]}}""", null, null))!!
        assertEquals(listOf("/data"), probe.additionalDirectories)
        assertEquals(emptyList<String>(), probe.allowRules)
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
