package dev.min.agent.kernel

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SettingsInputs
import dev.min.agent.ext.config.SkillDef
import dev.min.agent.ext.skills.SkillVars
import dev.min.agent.ext.skills.SlashCommands
import dev.min.agent.ext.skills.SlashResult
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.FakeExec
import dev.min.agent.llm.ModelClient
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentHashMap

/**
 * skill 正文的预执行（`` !`命令` ``）与分身、嵌套目录、`--add-dir`，走真的内核端口：Bash 的权限判定、工作区信任、
 * disableSkillShellExecution、allowed-tools 的本轮放行。命令由 [FakeExec] 执行（记下每一条），不碰真进程。
 */
class SkillShellRunTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = ShellMapFs()
    private val exec = FakeExec()
    private var sessions = 0

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun deps(model: ModelClient, trust: Boolean): KernelDeps {
        val base = testDeps(model, emptyList())
        return KernelDeps(
            clock = base.clock,
            ids = base.ids,
            models = base.models,
            tools = emptyList(),
            host = HostPorts(fs, base.host.search, exec, base.host.artifacts),
            platform = base.platform,
            zone = base.zone,
            retry = base.retry,
            memoryHome = GuestPath("/root"),
            env = { name -> if (name == "CLAUDE_CODE_DISABLE_AUTO_MEMORY") "1" else null },
            trustProjectHooks = { trust },
        )
    }

    private suspend fun session(trust: Boolean = true, model: ModelClient = ScriptedModel()): SessionRuntime {
        val n = ++sessions
        return SessionRuntime.create(
            store, LogHeader(LOG_FORMAT_VERSION, SessionId("skill-run-$n"), 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default), deps(model, trust), newScope(),
        )
    }

    private fun project(name: String, body: String, allowed: List<String> = emptyList(), context: String? = null, agent: String? = null) = SkillDef(
        name = name, description = "d", path = GuestPath("/workspace/.claude/skills/$name/SKILL.md"), body = body,
        source = ConfigSource.Project, allowedTools = allowed, context = context, agent = agent,
    )

    /** 用户敲 `/名字 参数`：走斜杠入口的 expandRun 与内核的用户端口，与 CLI / 手机界面同一条路 */
    private suspend fun typed(rt: SessionRuntime, s: SkillDef, args: String = ""): SlashResult =
        SlashCommands.expandRun("/${s.name} $args".trim(), listOf(s), emptyList(), SkillVars(projectDir = "/workspace"), rt.userSkillRunPort())

    private fun setProjectSettings(json: String) {
        fs.files["/workspace/.claude/settings.json"] = json
    }

    private fun exitedOk(output: String) = ExecResult.Exited(0, output, false, 1)

    // ---- Bash 的权限判定 ----

    @Test
    fun `a command the permission rules allow runs and its output replaces the marker`() = run {
        setProjectSettings("""{"permissions":{"allow":["Bash(make build)"]}}""")
        exec.script = { cmd, _ -> if (cmd.script == "make build") exitedOk("built") else exitedOk("?") }
        val rt = session()
        val r = typed(rt, project("ship", "result: !`make build`"))
        assertTrue("got $r", r is SlashResult.Expanded && r.text.endsWith("result: built"))
        assertEquals(listOf("make build"), exec.runs.map { it.first.script })
        // 没有 foreground 预算：一直等到超时（2 分钟），不转后台
        assertEquals(120_000L, exec.runs.single().second.timeoutMs)
        assertEquals(null, exec.runs.single().second.foregroundBudgetMs)
    }

    @Test
    fun `a deny rule refuses the command before anything runs`() = run {
        setProjectSettings("""{"permissions":{"deny":["Bash(rm *)"]}}""")
        val rt = session()
        val r = typed(rt, project("clean", "!`rm -rf build`"))
        assertTrue("got $r", r is SlashResult.Refused)
        assertTrue((r as SlashResult.Refused).message.contains("Shell command permission check failed for pattern \"rm -rf build\""))
        assertTrue(exec.runs.isEmpty())
    }

    @Test
    fun `a command that would need approval is refused, not asked, outside auto mode`() = run {
        val rt = session()
        val r = typed(rt, project("build", "!`make build`"))
        assertTrue("got $r", r is SlashResult.Refused)
        assertTrue(exec.runs.isEmpty())
    }

    @Test
    fun `allowed tools pre-approve the command for the turn but a deny rule still wins`() = run {
        val rt = session()
        assertTrue(typed(rt, project("build", "!`make build`", allowed = listOf("Bash(make build)"))) is SlashResult.Expanded)
        assertEquals(1, exec.runs.size)

        setProjectSettings("""{"permissions":{"deny":["Bash(make *)"]}}""")
        val denied = session()
        val r = typed(denied, project("build", "!`make build`", allowed = listOf("Bash(make build)")))
        assertTrue("got $r", r is SlashResult.Refused)
        assertEquals(1, exec.runs.size)
    }

    @Test
    fun `the allowed-tools grant clears when the user sends the next message`() = run {
        val rt = session(model = ScriptedModel(reply(TextPart("ok")), reply(TextPart("ok"))))
        val granted = project("build", "!`make build`", allowed = listOf("Bash(make build)"))
        assertTrue(typed(rt, granted) is SlashResult.Expanded)
        assertTrue(rt.send(ClientMsgId("m1"), listOf(TextPart("next")), dev.min.agent.spi.Delivery.Queue))
        rt.awaitIdle()
        // 下一条用户消息之后，上一次调用的放行没了：同一条命令、没有 allowed-tools 的 skill 就要询问，于是被拒
        val plain = project("build2", "!`make build`")
        val again = typed(rt, plain)
        assertTrue("got $again", again is SlashResult.Refused)
    }

    // ---- allowed-tools 的生命周期：落日志，重开会话后原样恢复（不大于原来） ----

    @Test
    fun `the allowed-tools grant is logged and restored when the session is reopened`() = run {
        val rt = session()
        assertTrue(typed(rt, project("build", "!`make build`", allowed = listOf("Bash(make build)"))) is SlashResult.Expanded)
        assertEquals(listOf("Bash(make build)"), rt.state.value.skillGrants)

        val reopened = SessionRuntime.open(store, rt.sessionId, rt.deps, newScope())!!
        assertEquals(listOf("Bash(make build)"), reopened.state.value.skillGrants)
        // 恢复出来的放行真的生效：另一个没有 allowed-tools 的 skill 要跑同一条命令，不再被拒
        assertTrue(typed(reopened, project("plain", "!`make build`")) is SlashResult.Expanded)
        // 但只有原来那条规则：别的命令照样要询问（失败关闭）
        assertTrue(typed(reopened, project("plain2", "!`make install`")) is SlashResult.Refused)
    }

    @Test
    fun `a grant cleared by the next user message stays cleared after reopening`() = run {
        val rt = session(model = ScriptedModel(reply(TextPart("ok"))))
        assertTrue(typed(rt, project("build", "!`make build`", allowed = listOf("Bash(make build)"))) is SlashResult.Expanded)
        assertTrue(rt.send(ClientMsgId("m1"), listOf(TextPart("next")), dev.min.agent.spi.Delivery.Queue))
        rt.awaitIdle()
        assertTrue(rt.state.value.skillGrants.isEmpty())

        val reopened = SessionRuntime.open(store, rt.sessionId, rt.deps, newScope())!!
        assertTrue(reopened.state.value.skillGrants.isEmpty())
        assertTrue(typed(reopened, project("plain", "!`make build`")) is SlashResult.Refused)
    }

    @Test
    fun `a restored grant never outranks a deny rule and the log holds the substituted rule`() = run {
        setProjectSettings("""{"permissions":{"deny":["Bash(make build)"]}}""")
        val rt = session()
        // 调用时 CLAUDE_SKILL_DIR 已代入：日志里记的是实际放行的那条规则，不是模板，重开时不会再读一遍磁盘上的 skill
        rt.grantSkillTools(listOf("Bash(\${CLAUDE_SKILL_DIR}/run.sh)", "Bash(make build)"), "/workspace/.claude/skills/x")
        assertEquals(listOf("Bash(/workspace/.claude/skills/x/run.sh)", "Bash(make build)"), rt.state.value.skillGrants)
        val reopened = SessionRuntime.open(store, rt.sessionId, rt.deps, newScope())!!
        assertEquals(rt.state.value.skillGrants, reopened.state.value.skillGrants)
        assertTrue(typed(reopened, project("plain", "!`make build`")) is SlashResult.Refused)
        assertTrue(exec.runs.isEmpty())
    }

    // ---- 信任与开关 ----

    @Test
    fun `an untrusted workspace does not run a project skill's commands even when pre-approved`() = run {
        val rt = session(trust = false)
        val r = typed(rt, project("build", "!`make build`", allowed = listOf("Bash(make build)")))
        assertTrue("got $r", r is SlashResult.Refused)
        assertTrue((r as SlashResult.Refused).message.contains("没有被信任"))
        assertTrue(exec.runs.isEmpty())
    }

    @Test
    fun `disableSkillShellExecution replaces each command with the policy text and runs nothing`() = run {
        setProjectSettings("""{"disableSkillShellExecution": true, "permissions": {"allow": ["Bash(make build)"]}}""")
        val rt = session()
        val r = typed(rt, project("build", "a !`make build` b"))
        assertTrue("got $r", r is SlashResult.Expanded && r.text.contains("a [shell command execution disabled by policy] b"))
        assertTrue(exec.runs.isEmpty())
    }

    @Test
    fun `a failing command aborts the whole invocation`() = run {
        setProjectSettings("""{"permissions":{"allow":["Bash(make build)"]}}""")
        exec.script = { _, _ -> ExecResult.Exited(2, "boom", false, 1) }
        val rt = session()
        val r = typed(rt, project("build", "!`make build`"))
        assertTrue("got $r", r is SlashResult.Refused && (r as SlashResult.Refused).message.startsWith("Shell command failed for pattern \"make build\""))
    }

    // ---- 参数不能借位执行 ----

    @Test
    fun `a marker typed as an argument is never run`() = run {
        setProjectSettings("""{"permissions":{"allow":["Bash(make build)"]}}""")
        val rt = session()
        val r = typed(rt, project("say", "Say: \$ARGUMENTS"), args = "!`make build`")
        assertTrue("got $r", r is SlashResult.Expanded && r.text.endsWith("Say: !`make build`"))
        assertTrue(exec.runs.isEmpty())
    }

    // ---- 分身 ----

    @Test
    fun `a forked skill without a sub-agent host is refused with a clear reason`() = run {
        val rt = session()
        val r = typed(rt, project("review", "look", context = "fork", agent = "Explore"))
        assertTrue("got $r", r is SlashResult.Refused && (r as SlashResult.Refused).message.contains("没有子 agent"))
    }

    // ---- 嵌套目录 ----

    @Test
    fun `a nested skills directory loads when a file in it is touched, once`() = run {
        fs.files["/workspace/apps/web/.claude/skills/deploy/SKILL.md"] = "---\ndescription: web deploy\n---\nbody"
        val rt = session()
        assertTrue(rt.state.value.nestedSkills.isEmpty())
        rt.discoverNestedSkills(listOf(GuestPath("/workspace/apps/web/src/a.ts")))
        assertEquals(listOf("deploy"), rt.state.value.nestedSkills.map { it.name })
        assertTrue("/workspace/apps/web/.claude/skills" in rt.state.value.checkedSkillDirs)
        // 同一目录再碰到：不重复加载
        rt.discoverNestedSkills(listOf(GuestPath("/workspace/apps/web/src/b.ts")))
        assertEquals(1, rt.state.value.nestedSkills.size)
        assertEquals(listOf("deploy"), typedEntries(rt))
    }

    private fun typedEntries(rt: SessionRuntime): List<String> = rt.state.value.nestedSkills.map { it.name }

    // ---- --add-dir 与 --bare ----

    @Test
    fun `bare keeps only the add-dir skills and full keeps everything`() = run {
        fs.files["/root/.claude/skills/deploy/SKILL.md"] = "---\ndescription: home\n---\nx"
        fs.files["/extra/lib/.claude/skills/tool/SKILL.md"] = "---\ndescription: tool\n---\ny"
        val inputs = SettingsInputs(addDirs = listOf(GuestPath("/extra/lib")))
        val bare = SkillSnapshots.load(fs, GuestPath("/workspace"), GuestPath("/root"), null, inputs, addDirsOnly = true)!!
        assertEquals(listOf("tool"), bare.entries.map { it.name })
        assertEquals("add-dir", bare.entries.single().source)
        val full = SkillSnapshots.load(fs, GuestPath("/workspace"), GuestPath("/root"), null, inputs)!!
        assertTrue(full.entries.map { it.name }.containsAll(listOf("deploy", "tool")))
    }
}

/** 只够读配置与嵌套 skill 用的内存文件系统：目录隐式存在，工作区是 /workspace */
private class ShellMapFs : WorkspaceFs {
    val files: MutableMap<String, String> = ConcurrentHashMap()

    override suspend fun stat(path: GuestPath): FileStat? = when {
        path.value in files -> FileStat(FileKind.File, files.getValue(path.value).length.toLong(), 0)
        path.value == "/" || files.keys.any { it.startsWith(path.value + "/") } -> FileStat(FileKind.Directory, 0, 0)
        else -> null
    }

    override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray {
        val bytes = files.getValue(path.value).toByteArray(Charsets.UTF_8)
        return bytes.copyOf(minOf(bytes.size, limit))
    }

    override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) = error("测试不写文件")

    override suspend fun list(dir: GuestPath): List<DirEntry> {
        val prefix = dir.value.trimEnd('/') + "/"
        return files.keys.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
            .map { DirEntry(it, if (prefix + it in files) FileKind.File else FileKind.Directory) }
    }

    override fun scopeOf(path: GuestPath): PathScope =
        if (path.value == "/workspace" || path.value.startsWith("/workspace/")) PathScope.Workspace else PathScope.Rootfs
}
