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
                local = null,
                mcpJson = null,
            ),
        )!!
        assertEquals("去重、去空白、忽略非字符串", listOf("Bash(git status)", "Read(/etc/**)"), probe.allowRules)
        assertEquals(listOf("/sdcard/Download", "../shared"), probe.additionalDirectories)
        assertEquals(true, probe.relaxesPermissions)
        assertEquals(emptyList<String>(), probe.hookEvents)
    }

    @Test
    fun `settings local counts too because in the app its allow rules and extra directories take no effect before trust`() {
        // 照 CC：没有 -p 的交互里未信任时不跑 git，local 文件的 allow / additionalDirectories 在信任前一律不生效（LocalSettingsTrust）。
        // 所以信任卡要把它们也列出来；项目与 local 两边的条目合并去重，项目的在前
        val probe = probeOf(
            "/workspace/p",
            ProjectConfigFiles(
                settings = """{"permissions":{"allow":["Bash(git status)"],"additionalDirectories":["/data"]}}""",
                local = """{"permissions":{"allow":["Bash(rm -rf *)","Bash(git status)"," "],"additionalDirectories":["/sdcard/Download","/data"]}}""",
                mcpJson = null,
            ),
        )!!
        assertEquals(listOf("Bash(git status)", "Bash(rm -rf *)"), probe.allowRules)
        assertEquals(listOf("/data", "/sdcard/Download"), probe.additionalDirectories)
        assertEquals(true, probe.relaxesPermissions)
    }

    @Test
    fun `a directory where only settings local relaxes permissions still gets the card`() {
        val onlyAllow = probeOf("/workspace/p", ProjectConfigFiles(null, """{"permissions":{"allow":["Bash(npm test *)"]}}""", null))!!
        assertEquals(listOf("Bash(npm test *)"), onlyAllow.allowRules)
        val onlyDirs = probeOf("/workspace/p", ProjectConfigFiles(null, """{"permissions":{"additionalDirectories":["/tmp/shared"]}}""", null))!!
        assertEquals(listOf("/tmp/shared"), onlyDirs.additionalDirectories)
        assertNull("local 里只有收紧的规则不算放宽", probeOf("/workspace/p", ProjectConfigFiles(null, """{"permissions":{"deny":["Bash(rm *)"],"ask":["Bash(git push *)"]}}""", null)))
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
