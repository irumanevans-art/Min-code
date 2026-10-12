package dev.min.agent.kernel

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.Note
import dev.min.agent.testkit.FakeFs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ConfigChange（官方 hooks 页「ConfigChange」、settings 页「When edits take effect」）：会话中途设置文件变了触发 hook；
 * hook 能拦下这次变更（exit 2 / decision:block，policy_settings 除外），被拦的变更不进运行中的会话；放行的当场生效（权限规则、hooks 等），
 * 其它等下一个会话。安全相关：拦截要守得住（别的文件变了整体重读时被拦的文件仍读旧内容）、policy_settings 不可被拦、
 * 重读不绕过工作区信任、被拦时不给人也不给模型任何消息。
 */
class ConfigChangeHookTest {
    @get:Rule val tmp = TemporaryFolder()
    private val rigs = mutableListOf<HookTestRig>()
    @After fun tearDown() = rigs.forEach { it.close() }

    private val userFile = "/home/u/.claude/settings.json"
    private val projectFile = "/workspace/.claude/settings.json"
    private val localFile = "/workspace/.claude/settings.local.json"
    private val managedFile = "/etc/claude-code/managed-settings.json"
    private val hook = """"hooks":{"ConfigChange":[{"hooks":[{"type":"http","url":"https://hooks.example/cc"}]}]}"""

    private fun deny(vararg rules: String) = """"permissions":{"deny":[${rules.joinToString(",") { "\"$it\"" }}]}"""

    private fun rig(
        http: FakeHttp = FakeHttp(),
        trust: Boolean = true,
        settingsInputs: dev.min.agent.ext.config.SettingsInputs = dev.min.agent.ext.config.SettingsInputs(),
        fs: FakeFs = FakeFs().apply { mkdir("/workspace"); put(userFile, "{$hook}") },
        block: suspend HookTestRig.() -> Unit,
    ) = runBlocking {
        val rig = HookTestRig(tmp.newFolder().toPath(), fs = fs, http = http, memoryHome = GuestPath("/home/u"), trust = trust, settingsInputs = settingsInputs)
            .also { rigs += it }
        withTimeout(30_000) { rig.block() }
    }

    private suspend fun HookTestRig.open() = session(mode = PermissionMode.BypassPermissions).also { it.fileWatches.arm() }

    private fun SessionRuntime.denied(text: String) = state.value.rules.any { it.text == text }
    private fun HookTestRig.configChanges() = http.bodies("ConfigChange")

    @Test fun `a changed settings file fires ConfigChange with its source and path, and the matcher selects the source`() = rig(
        fs = FakeFs().apply {
            mkdir("/workspace")
            put(userFile, """{"hooks":{"ConfigChange":[
                {"matcher":"project_settings","hooks":[{"type":"http","url":"https://hooks.example/project"}]},
                {"matcher":"user_settings","hooks":[{"type":"http","url":"https://hooks.example/user"}]}]}}""")
        },
    ) {
        val rt = open()
        fs.put(projectFile, "{${deny("Bash(rm *)")}}")
        rt.fileWatches.pollNow()
        assertEquals(listOf("https://hooks.example/project"), http.sent.map { it.url })
        val body = http.sent.single().body
        assertTrue(body, body.contains("\"source\":\"project_settings\"") && body.contains("\"file_path\":\"$projectFile\"") && body.contains("\"hook_event_name\":\"ConfigChange\""))
        fs.put(localFile, "{}")
        rt.fileWatches.pollNow()
        assertEquals("local_settings 没有配 hook", 1, http.sent.size)
    }

    @Test fun `an allowed change takes effect in the running session, permission rules and hooks`() = rig {
        val rt = open()
        assertFalse(rt.denied("Bash(rm *)"))
        fs.put(projectFile, "{${deny("Bash(rm *)")},\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"http\",\"url\":\"https://hooks.example/stop\"}]}]}}")
        rt.fileWatches.pollNow()
        assertTrue("新规则当场生效", rt.denied("Bash(rm *)"))
        assertTrue("新 hook 当场生效", rt.state.value.hooks.any { it.source == "project" })
        // 又改掉：规则撤掉、hook 撤掉
        fs.put(projectFile, "{}")
        rt.fileWatches.pollNow()
        assertFalse("删掉的规则也要撤掉", rt.denied("Bash(rm *)"))
        assertTrue(rt.state.value.hooks.none { it.source == "project" })
    }

    @Test fun `a blocked change is not applied, and says nothing to the user or the model`() = rig(
        http = FakeHttp { HttpHookResult.Responded(200, """{"decision":"block","reason":"admin approval","systemMessage":"loud","continue":false}""") },
    ) {
        val rt = open()
        fs.put(projectFile, "{${deny("Bash(rm *)")}}")
        rt.fileWatches.pollNow()
        assertFalse("被拦的变更不进会话", rt.denied("Bash(rm *)"))
        assertEquals(1, configChanges().size)
        assertTrue("原文：被拦时不给人、也不给模型任何消息", events(rt).filterIsInstance<Note>().none { it.text == "loud" || it.text.contains("admin approval") })
        assertTrue(events(rt).none { it is dev.min.agent.spi.HookContextAdded })
        assertEquals("同一个变更不反复问", 1, run { rt.fileWatches.pollNow(); configChanges().size })
    }

    @Test fun `a blocked file stays on its old content even when another file changes later`() {
        var blockProject = true
        rig(http = FakeHttp { req: HttpHookRequest ->
            if (blockProject && "\"source\":\"project_settings\"" in req.body) HttpHookResult.Responded(200, """{"decision":"block"}""") else HttpHookResult.Responded(200, "{}")
        }) {
            val rt = open()
            fs.put(projectFile, "{${deny("Bash(rm *)")}}")
            rt.fileWatches.pollNow()
            assertFalse(rt.denied("Bash(rm *)"))
            // 另一个文件变了、放行：整体重读时被拦的 project 文件必须仍读旧内容，不能借这次重读把被拦的变更带进来
            fs.put(userFile, "{$hook,${deny("Bash(curl *)")}}")
            rt.fileWatches.pollNow()
            assertTrue("user 的变更生效", rt.denied("Bash(curl *)"))
            assertFalse("被拦的 project 变更仍然不在", rt.denied("Bash(rm *)"))
            // project 文件自己又变了：hook 再问一次，这次放行，钉住解除，新内容进来
            blockProject = false
            fs.put(projectFile, "{${deny("Bash(rm *)", "Bash(dd *)")}}")
            rt.fileWatches.pollNow()
            assertTrue(rt.denied("Bash(rm *)") && rt.denied("Bash(dd *)") && rt.denied("Bash(curl *)"))
        }
    }

    @Test fun `policy_settings cannot be blocked, the hook still fires and the managed change is applied`() = rig(
        http = FakeHttp { HttpHookResult.Responded(200, """{"decision":"block"}""") },
    ) {
        val rt = open()
        fs.put(managedFile, "{${deny("Bash(sudo *)")}}")
        rt.fileWatches.pollNow()
        assertEquals("hook 照样触发（给企业审计用）", 1, configChanges().size)
        assertTrue(configChanges().single().contains("\"source\":\"policy_settings\""))
        assertTrue("阻断的决定被忽略，企业策略总是生效", rt.denied("Bash(sudo *)"))
    }

    @Test fun `a managed drop-in fragment counts as policy_settings`() = rig {
        val rt = open()
        fs.put("/etc/claude-code/managed-settings.d/10-extra.json", "{${deny("Bash(mkfs *)")}}")
        rt.fileWatches.pollNow()
        assertTrue(configChanges().single().contains("\"source\":\"policy_settings\""))
        assertTrue(rt.denied("Bash(mkfs *)"))
    }

    @Test fun `reloading never bypasses workspace trust, an untrusted project file adds no allow rule`() = rig(trust = false) {
        val rt = open()
        fs.put(projectFile, """{"permissions":{"allow":["Bash(*)"]}}""")
        rt.fileWatches.pollNow()
        assertTrue("信任闸还在：未信任的项目层放宽不了任何东西", rt.state.value.rules.none { it.text == "Bash(*)" })
        assertEquals("变更照样触发 hook", 1, configChanges().size)
    }

    @Test fun `a directory added mid-session survives a settings reload`() = rig {
        fs.mkdir("/other")
        val rt = open()
        rt.addDirectory(GuestPath("/other"))
        fs.put(userFile, "{$hook,${deny("Bash(x)")}}")
        rt.fileWatches.pollNow()
        assertTrue(rt.denied("Bash(x)"))
        assertTrue(rt.state.value.additionalDirectories.contains(GuestPath("/other")))
    }

    @Test fun `a created or deleted file is a change too`() = rig {
        val rt = open()
        fs.put(localFile, "{}")
        rt.fileWatches.pollNow()
        fs.delete(localFile)
        rt.fileWatches.pollNow()
        assertEquals(2, configChanges().size)
        assertTrue(configChanges().all { it.contains("local_settings") })
    }

    @Test fun `an unchanged file fires nothing, and the baseline is the session start`() = rig {
        val rt = open()
        rt.fileWatches.pollNow()
        rt.fileWatches.pollNow()
        assertTrue(http.sent.isEmpty())
    }

    @Test fun `--restricted watches only the managed files`() = rig(
        settingsInputs = dev.min.agent.ext.config.SettingsInputs(restricted = true),
        fs = FakeFs().apply { mkdir("/workspace"); put(managedFile, "{$hook}") },
    ) {
        val rt = open()
        fs.put(projectFile, "{}")
        fs.put(userFile, "{}")
        rt.fileWatches.pollNow()
        assertTrue("受限启动不读用户 / 项目 / local，也就不盯它们", http.sent.isEmpty())
        fs.put(managedFile, "{$hook,${deny("Bash(x)")}}")
        rt.fileWatches.pollNow()
        assertEquals(1, http.sent.size)
    }

    @Test fun `keys read once at session start stay frozen until the next session`() = rig {
        val rt = open()
        val outputStyleBefore = rt.state.value.outputStyle
        fs.put(userFile, "{$hook,\"outputStyle\":\"Explanatory\",\"model\":\"opus\"}")
        rt.fileWatches.pollNow()
        assertEquals("outputStyle 创建时读一次、冻结进日志", outputStyleBefore, rt.state.value.outputStyle)
        assertEquals(dev.min.agent.testkit.TEST_MODEL, rt.state.value.settings.model)
    }

    // ---- skills ----

    @Test fun `a skill file change fires ConfigChange with source skills, and a block keeps the old list`() {
        var block = true
        rig(http = FakeHttp { if (block) HttpHookResult.Responded(200, """{"decision":"block"}""") else HttpHookResult.Responded(200, "{}") }) {
            val rt = open()
            fs.put("/workspace/.claude/skills/fresh/SKILL.md", "---\ndescription: brand new\n---\nbody")
            say(rt, "go")
            assertTrue(configChanges().any { it.contains("\"source\":\"skills\"") })
            assertTrue("被拦：清单里没有它", rt.state.value.currentSkillEntries().none { it.name == "fresh" })
            val asked = configChanges().size
            say(rt, "again")
            assertEquals("同一份差异不反复问", asked, configChanges().size)
            block = false
            fs.put("/workspace/.claude/skills/fresh/SKILL.md", "---\ndescription: brand new v2\n---\nbody")
            say(rt, "third")
            assertTrue("放行：清单里有了", rt.state.value.currentSkillEntries().any { it.name == "fresh" })
        }
    }
}
