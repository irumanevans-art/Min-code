package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * settings 的层级、合并与容错。期望逐条对照 CC 文档（research/inventory/claude-code/02-hooks-settings-env-cli.md §3、
 * code.claude.com/docs/en/settings 与 permissions）：managed > local > project > user；列表合并、标量高层覆盖；
 * `/x` 路径规则的锚点；项目目录只看 cwd；坏文件跳过而不是抛异常。
 */
class SettingsLoaderTest {
    private val fs = InMemoryFs()
    private val managed = "/etc/claude-code/managed-settings.json"

    private fun load(cwd: String = "/workspace", home: String = "/root") =
        runBlocking { SettingsLoader.load(fs, GuestPath(cwd), GuestPath(home)) }

    private fun SettingsSnapshot.rules() = merged.ruleSpecs().map { "${it.source} ${it.kind} ${it.text} @${it.baseDir}" }

    @Test
    fun `no files at all is not an error and leaves no notes`() {
        val s = load()
        assertTrue(s.layers.isEmpty())
        assertTrue(s.notes.isEmpty())
        assertFalse(s.managedPolicyBroken)
        assertTrue(s.merged.ruleSpecs().isEmpty())
        assertNull(s.merged.model)
    }

    @Test
    fun `scalars come from the highest layer that sets them in the documented order`() {
        fs.file("/root/.claude/settings.json", """{"model":"user","outputStyle":"u-style","language":"zh"}""")
        fs.file("/workspace/.claude/settings.json", """{"model":"project","outputStyle":"p-style"}""")
        fs.file("/workspace/.claude/settings.local.json", """{"model":"local"}""")
        assertEquals("local", load().merged.model)
        assertEquals("p-style", load().merged.outputStyle)
        assertEquals("zh", load().merged.language)

        fs.file(managed, """{"model":"managed"}""")
        assertEquals("managed", load().merged.model)
        assertEquals(listOf(ConfigSource.User, ConfigSource.Project, ConfigSource.Local, ConfigSource.Managed), load().layers.map { it.source })
    }

    @Test
    fun `lists merge across layers and permission rules keep their source and anchor`() {
        fs.file("/root/.claude/settings.json", """{"permissions":{"allow":["Bash(ls *)"],"deny":["Read(/secrets/**)"]}}""")
        fs.file("/workspace/.claude/settings.json", """{"permissions":{"allow":["Edit(/src/**)"],"ask":["Bash(git push *)"]}}""")
        fs.file("/workspace/.claude/settings.local.json", """{"permissions":{"allow":["Bash(ls *)","WebFetch(domain:example.com)"]}}""")
        fs.file(managed, """{"permissions":{"deny":["Bash(rm -rf *)"]}}""")
        val s = load()
        // 高层优先：managed、local、project、user；同层内 allow、ask、deny
        assertEquals(
            listOf(
                "managed Deny Bash(rm -rf *) @/etc/claude-code",
                "local Allow Bash(ls *) @/workspace",
                "local Allow WebFetch(domain:example.com) @/workspace",
                "project Allow Edit(/src/**) @/workspace",
                "project Ask Bash(git push *) @/workspace",
                "user Allow Bash(ls *) @/root/.claude",
                "user Deny Read(/secrets/**) @/root/.claude",
            ),
            s.rules(),
        )
        // 合并后的纯列表去重，不带来源
        assertEquals(listOf("Bash(ls *)", "Edit(/src/**)", "WebFetch(domain:example.com)"), s.merged.permissions.allow)
    }

    @Test
    fun `the same rule text in two layers stays two specs because their anchors differ`() {
        fs.file("/root/.claude/settings.json", """{"permissions":{"deny":["Read(/x/**)"]}}""")
        fs.file("/workspace/.claude/settings.json", """{"permissions":{"deny":["Read(/x/**)","Read(/x/**)"]}}""")
        assertEquals(
            listOf("project Deny Read(/x/**) @/workspace", "user Deny Read(/x/**) @/root/.claude"),
            load().rules(),
        )
    }

    @Test
    fun `project settings are read from cwd only and never from a parent directory`() {
        fs.file("/workspace/.claude/settings.json", """{"model":"parent"}""")
        val s = load(cwd = "/workspace/app")
        assertNull(s.merged.model)
        assertTrue(s.layers.isEmpty())
    }

    @Test
    fun `inside a git repository the local file at the repo root is read too and wins, anchored at cwd`() {
        fs.file("/workspace/.git/HEAD", "ref: refs/heads/main")
        fs.file("/workspace/.claude/settings.local.json", """{"model":"root-local","permissions":{"allow":["Edit(/root-rule/**)"]}}""")
        fs.file("/workspace/app/.claude/settings.local.json", """{"model":"cwd-local","permissions":{"allow":["Edit(/cwd-rule/**)"]}}""")
        val s = load(cwd = "/workspace/app")
        assertEquals("root-local", s.merged.model)
        assertEquals(
            listOf("local Allow Edit(/root-rule/**) @/workspace/app", "local Allow Edit(/cwd-rule/**) @/workspace/app"),
            s.rules(),
        )
    }

    @Test
    fun `a git file instead of a directory marks a repository root as well`() {
        fs.file("/workspace/.git", "gitdir: /elsewhere")
        fs.file("/workspace/.claude/settings.local.json", """{"model":"root-local"}""")
        assertEquals("root-local", load(cwd = "/workspace/app").merged.model)
    }

    @Test
    fun `a repository root that is the home directory is not consulted`() {
        fs.file("/root/.git/HEAD", "x")
        fs.file("/root/.claude/settings.local.json", """{"model":"home-local"}""")
        assertNull(load(cwd = "/root/proj").merged.model)
    }

    @Test
    fun `cwd equal to home reads the shared file once as the user layer`() {
        fs.file("/root/.claude/settings.json", """{"permissions":{"allow":["Bash(ls *)"]}}""")
        val s = load(cwd = "/root", home = "/root")
        assertEquals(listOf(ConfigSource.User), s.layers.map { it.source })
        assertEquals(listOf("user Allow Bash(ls *) @/root/.claude"), s.rules())
    }

    @Test
    fun `allowManagedPermissionRulesOnly keeps only the managed rules`() {
        fs.file("/root/.claude/settings.json", """{"permissions":{"allow":["Bash(ls *)"]}}""")
        fs.file("/workspace/.claude/settings.json", """{"permissions":{"deny":["Bash(rm *)"]}}""")
        fs.file(managed, """{"allowManagedPermissionRulesOnly":true,"permissions":{"allow":["Read"]}}""")
        val s = load()
        assertEquals(listOf("managed Allow Read @/etc/claude-code"), s.rules())
        assertTrue(s.merged.allowManagedPermissionRulesOnly)
        assertTrue(s.notes.single().contains("2 条"))
    }

    @Test
    fun `allowManagedPermissionRulesOnly set outside managed is ignored with a note`() {
        fs.file("/root/.claude/settings.json", """{"permissions":{"allow":["Bash(ls *)"]}}""")
        fs.file("/workspace/.claude/settings.json", """{"allowManagedPermissionRulesOnly":true,"allowManagedHooksOnly":true}""")
        val s = load()
        assertEquals(listOf("user Allow Bash(ls *) @/root/.claude"), s.rules())
        assertFalse(s.merged.allowManagedPermissionRulesOnly)
        assertFalse(s.merged.allowManagedHooksOnly)
        assertEquals(2, s.notes.count { it.contains("/workspace/.claude/settings.json") && it.contains("managed") })
    }

    @Test
    fun `managed drop-ins merge after the main file in name order and ignore hidden and non-json files`() {
        fs.file(managed, """{"model":"main","permissions":{"deny":["A"]},"env":{"X":"1","Y":"main"}}""")
        fs.file("/etc/claude-code/managed-settings.d/20-b.json", """{"model":"b","permissions":{"deny":["B","A"]},"env":{"Y":"b"}}""")
        fs.file("/etc/claude-code/managed-settings.d/10-a.json", """{"model":"a","permissions":{"deny":["C"]}}""")
        fs.file("/etc/claude-code/managed-settings.d/.hidden.json", """{"model":"hidden"}""")
        fs.file("/etc/claude-code/managed-settings.d/30-notes.txt", """{"model":"txt"}""")
        val s = load()
        assertEquals(1, s.layers.size)
        assertEquals("b", s.merged.model)
        assertEquals(listOf("A", "C", "B"), s.merged.permissions.deny)
        assertEquals(mapOf("X" to "1", "Y" to "b"), s.merged.env)
        assertTrue(s.notes.isEmpty())
    }

    @Test
    fun `invalid JSON skips that file, keeps the others and explains which file`() {
        fs.file("/root/.claude/settings.json", """{"model":"user"}""")
        fs.file("/workspace/.claude/settings.json", """{"model": "oops",""")
        val s = load()
        assertEquals("user", s.merged.model)
        assertEquals(listOf(ConfigSource.User), s.layers.map { it.source })
        assertEquals(1, s.notes.size)
        assertTrue(s.notes.single().contains("/workspace/.claude/settings.json"))
        assertTrue(s.notes.single().contains("不是合法 JSON"))
        assertFalse(s.managedPolicyBroken)
    }

    @Test
    fun `a top-level JSON array is skipped and an empty file counts as an empty object`() {
        fs.file("/root/.claude/settings.json", "[]")
        fs.file("/workspace/.claude/settings.json", "  \n")
        val s = load()
        assertEquals(listOf(ConfigSource.Project), s.layers.map { it.source })
        assertEquals(1, s.notes.size)
        assertTrue(s.notes.single().contains("顶层应是 JSON 对象"))
    }

    @Test
    fun `a broken managed file is flagged because Claude Code would refuse to start`() {
        fs.file(managed, "{not json")
        val s = load()
        assertTrue(s.managedPolicyBroken)
        assertTrue(s.layers.isEmpty())
        assertTrue(s.notes.any { it.contains(managed) })
        assertTrue(s.notes.any { it.contains("拒绝启动") })
    }

    @Test
    fun `an unreadable managed file is flagged too and never throws`() {
        fs.fail(managed)
        val s = load()
        assertTrue(s.managedPolicyBroken)
        assertTrue(s.notes.any { it.contains(managed) && it.contains("读取") })
    }

    @Test
    fun `wrong typed keys are skipped one by one with the file and key named`() {
        fs.file(
            "/workspace/.claude/settings.json",
            """
            {
              "model": 5,
              "outputStyle": "terse",
              "cleanupPeriodDays": 0,
              "autoMemoryEnabled": "yes",
              "disableAllHooks": true,
              "env": {"A": "1", "B": 2, "C": null},
              "enabledPlugins": {"p@m": true, "q@m": "on"},
              "claudeMdExcludes": ["a", 3, ""],
              "hooks": [],
              "permissions": {
                "allow": ["Bash(ls *)", 7, "  "],
                "ask": "Bash",
                "deny": ["Read(.env)"],
                "defaultMode": 1,
                "disableBypassPermissionsMode": true
              }
            }
            """.trimIndent(),
        )
        val s = load()
        val m = s.merged
        assertNull(m.model)
        assertEquals("terse", m.outputStyle)
        assertNull(m.cleanupPeriodDays)
        assertNull(m.autoMemoryEnabled)
        assertTrue(m.disableAllHooks)
        assertEquals(mapOf("A" to "1"), m.env)
        assertEquals(mapOf("p@m" to true), m.enabledPlugins)
        assertEquals(listOf("a"), m.claudeMdExcludes)
        assertTrue(m.hooks.isEmpty())
        assertEquals(listOf("Bash(ls *)"), m.permissions.allow)
        assertTrue(m.permissions.ask.isEmpty())
        assertEquals(listOf("Read(.env)"), m.permissions.deny)
        assertNull(m.permissions.defaultMode)
        assertFalse(m.permissions.disableBypassPermissionsMode)

        val f = "/workspace/.claude/settings.json"
        fun noted(vararg parts: String) = assertTrue("缺少 ${parts.toList()}：${s.notes}", s.notes.any { n -> n.contains(f) && parts.all { it in n } })
        noted("`model`", "期望字符串")
        noted("`cleanupPeriodDays`", "不小于 1")
        noted("`autoMemoryEnabled`", "布尔")
        noted("`env.B`")
        noted("`enabledPlugins.q@m`")
        noted("`claudeMdExcludes[1]`")
        noted("`claudeMdExcludes[2]`", "空字符串")
        noted("`hooks`", "期望对象")
        noted("`permissions.allow[1]`")
        noted("`permissions.allow[2]`", "空字符串")
        noted("`permissions.ask`", "期望字符串数组")
        noted("`permissions.defaultMode`")
        noted("`permissions.disableBypassPermissionsMode`", "disable")
        assertEquals(13, s.notes.size)
    }

    @Test
    fun `permissions that is not an object drops only that key`() {
        fs.file("/workspace/.claude/settings.json", """{"permissions":"all","model":"m"}""")
        val s = load()
        assertEquals("m", s.merged.model)
        assertTrue(s.merged.ruleSpecs().isEmpty())
        assertTrue(s.notes.single().contains("`permissions`"))
    }

    @Test
    fun `auto and bypassPermissions as defaultMode are ignored from project and local but fine from user`() {
        fs.file("/root/.claude/settings.json", """{"permissions":{"defaultMode":"acceptEdits"}}""")
        fs.file("/workspace/.claude/settings.json", """{"permissions":{"defaultMode":"bypassPermissions"}}""")
        fs.file("/workspace/.claude/settings.local.json", """{"permissions":{"defaultMode":"auto"}}""")
        val s = load()
        assertEquals("acceptEdits", s.merged.permissions.defaultMode)
        assertEquals(2, s.notes.size)
        assertTrue(s.notes.all { it.contains("~/.claude/settings.json") })

        fs.file("/root/.claude/settings.json", """{"permissions":{"defaultMode":"auto"}}""")
        fs.file("/workspace/.claude/settings.local.json", """{"permissions":{"defaultMode":"plan"}}""")
        assertEquals("plan", load().merged.permissions.defaultMode) // local 的 plan 高于 user 的 auto
        fs.file("/workspace/.claude/settings.local.json", "{}")
        assertEquals("auto", load().merged.permissions.defaultMode)
    }

    @Test
    fun `the disable locks take the strictest value from any layer`() {
        fs.file("/root/.claude/settings.json", """{"permissions":{"disableBypassPermissionsMode":"disable"}}""")
        fs.file("/workspace/.claude/settings.local.json", """{"disableAutoMode":"disable"}""")
        fs.file("/workspace/.claude/settings.json", """{"permissions":{"disableAutoMode":"enable"}}""")
        val s = load()
        assertTrue(s.merged.permissions.disableBypassPermissionsMode)
        assertTrue(s.merged.permissions.disableAutoMode)
        assertTrue(s.notes.single().contains("permissions.disableAutoMode"))
    }

    @Test
    fun `additionalDirectories merge as raw strings and env merges per variable`() {
        fs.file("/root/.claude/settings.json", """{"permissions":{"additionalDirectories":["~/notes","../lib"]},"env":{"A":"u","B":"u"}}""")
        fs.file("/workspace/.claude/settings.json", """{"permissions":{"additionalDirectories":["../lib","/data"]},"env":{"B":"p"}}""")
        val m = load().merged
        assertEquals(listOf("~/notes", "../lib", "/data"), m.permissions.additionalDirectories)
        assertEquals(mapOf("A" to "u", "B" to "p"), m.env)
    }

    @Test
    fun `hooks stay raw per layer and activeHooks applies the managed-only and disable switches`() {
        val hooks = """"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"x"}]}]}"""
        fs.file("/root/.claude/settings.json", "{$hooks}")
        fs.file("/workspace/.claude/settings.json", "{$hooks}")
        assertEquals(listOf(ConfigSource.User, ConfigSource.Project), load().merged.activeHooks().map { it.source })

        fs.file("/workspace/.claude/settings.local.json", """{"disableAllHooks":true}""")
        assertTrue(load().merged.activeHooks().isEmpty()) // 没有 managed hooks 可留

        fs.file(managed, "{$hooks}")
        assertEquals(listOf(ConfigSource.Managed), load().merged.activeHooks().map { it.source }) // 非 managed 的开关关不掉 managed

        fs.file(managed, """{$hooks,"disableAllHooks":true}""")
        assertTrue(load().merged.activeHooks().isEmpty())

        fs.file(managed, """{$hooks,"allowManagedHooksOnly":true}""")
        fs.file("/workspace/.claude/settings.local.json", "{}")
        assertEquals(listOf(ConfigSource.Managed), load().merged.activeHooks().map { it.source })
    }

    @Test
    fun `raw returns the highest layer's value for keys without a typed field`() {
        fs.file("/root/.claude/settings.json", """{"statusLine":{"type":"command","command":"u"},"theme":"dark"}""")
        fs.file("/workspace/.claude/settings.local.json", """{"statusLine":{"type":"command","command":"l"}}""")
        val m = load().merged
        assertEquals("""{"type":"command","command":"l"}""", m.raw("statusLine").toString())
        assertEquals(JsonPrimitive("dark"), m.raw("theme"))
        assertNull(m.raw("nothing"))
    }

    @Test
    fun `a BOM and CRLF line endings do not break parsing`() {
        fs.file("/workspace/.claude/settings.json", "\uFEFF{\r\n  \"model\": \"m\"\r\n}\r\n")
        assertEquals("m", load().merged.model)
    }

    @Test
    fun `an oversized file is skipped with a note instead of being read`() {
        fs.bytes("/workspace/.claude/settings.json", ByteArray((ConfigIo.MAX_BYTES + 1).toInt()))
        val s = load()
        assertTrue(s.layers.isEmpty())
        assertTrue(s.notes.single().contains("超过"))
        assertTrue(fs.reads.isEmpty())
    }

    @Test
    fun `a settings path that is a directory is reported not thrown`() {
        fs.file("/workspace/.claude/settings.json/inner", "x")
        val s = load()
        assertTrue(s.layers.isEmpty())
        assertTrue(s.notes.single().contains("不是普通文件"))
    }

    @Test
    fun `an unreadable user file is skipped with a note and the rest still loads`() {
        fs.fail("/root/.claude/settings.json")
        fs.file("/workspace/.claude/settings.json", """{"model":"m"}""")
        val s = load()
        assertEquals("m", s.merged.model)
        assertTrue(s.notes.single().contains("/root/.claude/settings.json"))
    }

    @Test
    fun `autoMemoryDirectory is honored only from user and managed settings`() {
        // 项目设置能把模型的笔记目录改到任意路径：不认，且记一条说明（设计 §2.1）
        fs.file("/root/.claude/settings.json", """{"autoMemoryDirectory":"~/notes"}""")
        fs.file("/workspace/.claude/settings.json", """{"autoMemoryDirectory":"/tmp/evil"}""")
        fs.file("/workspace/.claude/settings.local.json", """{"autoMemoryDirectory":"/tmp/evil2"}""")
        val s = load()
        // 原样透出（~/ 的展开在内核做，AutoMemoryTest 管）；project / local 的被忽略
        assertEquals("~/notes", s.merged.autoMemoryDirectory)
        assertEquals(2, s.notes.count { it.contains("autoMemoryDirectory") })

        // user 与 managed 之间照常高层优先
        fs.file(managed, """{"autoMemoryDirectory":"/etc/claude-memory"}""")
        assertEquals("/etc/claude-memory", load().merged.autoMemoryDirectory)
        fs.file("/root/.claude/settings.json", "{}")
        assertEquals("/etc/claude-memory", load().merged.autoMemoryDirectory)

        // 什么都没设就是 null（内核按默认位置算）
        fs.file("/root/.claude/settings.json", "{}")
        fs.file("/workspace/.claude/settings.json", "{}")
        fs.file("/workspace/.claude/settings.local.json", "{}")
        fs.file(managed, "{}")
        val none = load()
        assertNull(none.merged.autoMemoryDirectory)
        assertTrue(none.notes.isEmpty())
    }
}
