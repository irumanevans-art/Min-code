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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun deps(model: ModelClient, trust: Boolean, tools: List<dev.min.agent.spi.Tool<*>> = emptyList()): KernelDeps {
        val base = testDeps(model, tools)
        return KernelDeps(
            clock = base.clock,
            ids = base.ids,
            models = base.models,
            tools = tools,
            host = HostPorts(fs, base.host.search, exec, base.host.artifacts),
            platform = base.platform,
            zone = base.zone,
            retry = base.retry,
            memoryHome = GuestPath("/root"),
            env = { name -> if (name == "CLAUDE_CODE_DISABLE_AUTO_MEMORY") "1" else null },
            trustProjectHooks = { trust },
        )
    }

    private suspend fun session(
        trust: Boolean = true,
        model: ModelClient = ScriptedModel(),
        mode: PermissionMode = PermissionMode.Default,
        tools: List<dev.min.agent.spi.Tool<*>> = emptyList(),
    ): SessionRuntime {
        val n = ++sessions
        return SessionRuntime.create(
            store, LogHeader(LOG_FORMAT_VERSION, SessionId("skill-run-$n"), 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, mode), deps(model, trust, tools), newScope(),
        )
    }

    /** 带 Hub 的根会话：分身的 skill 要经它派生子 agent */
    private suspend fun hubSession(model: ModelClient, mode: PermissionMode = PermissionMode.Default, tools: List<dev.min.agent.spi.Tool<*>> = emptyList()): SessionRuntime =
        SessionHub(store, deps(model, true, tools), newScope(), HubLimits()).create(GuestPath("/workspace"), TEST_MODEL, mode)

    private fun project(name: String, body: String, allowed: List<String> = emptyList(), context: String? = null, agent: String? = null, background: Boolean = true) = SkillDef(
        name = name, description = "d", path = GuestPath("/workspace/.claude/skills/$name/SKILL.md"), body = body,
        source = ConfigSource.Project, allowedTools = allowed, context = context, agent = agent, background = background,
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

    // ---- 起点目录：session shell 的当前目录（skills 页「How injected commands run」） ----

    @Test
    fun `a pre-run command starts in the shell directory Claude cd'd into, not the session directory`() = run {
        setProjectSettings("""{"permissions":{"allow":["Bash(make build)"]}}""")
        fs.files["/workspace/sub/keep"] = "k"
        val rt = session()
        rt.commit(listOf(dev.min.agent.spi.ShellCwdChanged(GuestPath("/workspace/sub"))), dev.min.agent.spi.Durability.Sync)
        assertTrue(typed(rt, project("ship", "!`make build`")) is SlashResult.Expanded)
        assertEquals("/workspace/sub", exec.runs.single().first.cwd.value)
        assertEquals("预执行不改 shell 目录，也不把自己的 cd 带回去", GuestPath("/workspace/sub"), rt.state.value.shellCwd)
    }

    @Test
    fun `without a cd the pre-run command starts in the session directory as before`() = run {
        setProjectSettings("""{"permissions":{"allow":["Bash(make build)"]}}""")
        val rt = session()
        assertTrue(typed(rt, project("ship", "!`make build`")) is SlashResult.Expanded)
        assertEquals("/workspace", exec.runs.single().first.cwd.value)
    }

    @Test
    fun `a carried directory that is gone is reset before the decision and the command runs from the session directory`() = run {
        setProjectSettings("""{"permissions":{"allow":["Bash(make build)"]}}""")
        val rt = session()
        // /workspace/gone 不存在：权限判定若按它解析、命令却在别处跑，就成了没分析过的命令——先重置，判定与起点同为会话目录
        rt.commit(listOf(dev.min.agent.spi.ShellCwdChanged(GuestPath("/workspace/gone"))), dev.min.agent.spi.Durability.Sync)
        assertTrue(typed(rt, project("ship", "!`make build`")) is SlashResult.Expanded)
        assertEquals("/workspace", exec.runs.single().first.cwd.value)
        assertNull(rt.state.value.shellCwd)
    }

    @Test
    fun `a carried directory outside the project is not used either`() = run {
        setProjectSettings("""{"permissions":{"allow":["Bash(make build)"]}}""")
        fs.files["/elsewhere/x"] = "x"
        val rt = session()
        rt.commit(listOf(dev.min.agent.spi.ShellCwdChanged(GuestPath("/elsewhere"))), dev.min.agent.spi.Durability.Sync)
        assertTrue(typed(rt, project("ship", "!`make build`")) is SlashResult.Expanded)
        assertEquals("/workspace", exec.runs.single().first.cwd.value)
        assertNull(rt.state.value.shellCwd)
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

    // ---- 自动模式：注入命令需要批准时，不中止也不自己跑，交给模型自己调 Bash（CC skills 文档） ----

    private val bash = listOf<dev.min.agent.spi.Tool<*>>(dev.min.agent.tools.BashTool())

    @Test
    fun `in auto mode a command that would need approval is left for the model to run itself`() = run {
        val rt = session(mode = PermissionMode.Auto, tools = bash)
        val r = typed(rt, project("build", "before !`make build` after"))
        assertTrue("got $r", r is SlashResult.Expanded)
        val text = (r as SlashResult.Expanded).text
        // 命令没有被内核替人跑（模型自己的 Bash 调用才走自动模式的常规判定），正文里留一句让模型先跑它的话
        assertTrue(exec.runs.isEmpty())
        assertTrue(text, text.contains("make build") && text.contains("Bash") && text.contains("before [Not pre-run") && text.endsWith("] after"))
        assertTrue(text, !text.contains("!`make build`"))
    }

    @Test
    fun `in auto mode an allowed command still runs and a denied one still aborts`() = run {
        setProjectSettings("""{"permissions":{"allow":["Bash(make build)"],"deny":["Bash(rm *)"]}}""")
        val rt = session(mode = PermissionMode.Auto, tools = bash)
        assertTrue(typed(rt, project("ok", "!`make build`")) is SlashResult.Expanded)
        assertEquals(listOf("make build"), exec.runs.map { it.first.script })
        val denied = typed(rt, project("bad", "!`rm -rf x`"))
        assertTrue("got $denied", denied is SlashResult.Refused)
        assertEquals(1, exec.runs.size)
    }

    @Test
    fun `in auto mode the deferral still aborts without a shell tool or in a forked skill that sets an agent`() = run {
        // 没有 Bash 工具：模型没法照着「先自己跑」去做，照文档中止
        val noShell = session(mode = PermissionMode.Auto)
        assertTrue(typed(noShell, project("build", "!`make build`")) is SlashResult.Refused)
        // 指定了 agent 的分身 skill：照文档中止（命令要在子 agent 里跑，那边没有这条交接）
        val rt = hubSession(ScriptedModel(), PermissionMode.Auto, bash)
        val forked = typed(rt, project("build", "!`make build`", context = "fork", agent = "Explore"))
        assertTrue("got $forked", forked is SlashResult.Refused)
        assertTrue(exec.runs.isEmpty())
    }

    // ---- 分身带上父 skill 的 allowed-tools ----

    @Test
    fun `a forked skill hands its allowed-tools to the sub-agent it spawns`() = run {
        val rt = hubSession(ScriptedModel(reply(TextPart("child done"))))
        // 父会话里别的 skill 已放行的工具不跟着漏给它：子 agent 只拿这个 skill 自己声明的
        rt.grantSkillTools(listOf("Bash(make build)"), null, "other")
        val r = typed(rt, project("pr", "summarize", allowed = listOf("Bash(gh pr view *)"), context = "fork", agent = "Explore", background = false))
        assertTrue("got $r", r is SlashResult.Forked)
        val childId = rt.state.value.children.keys.single()
        val child = rt.hub!!.get(childId)!!
        assertEquals(listOf("Bash(gh pr view *)"), child.state.value.skillGrants)
    }

    @Test
    fun `a sub-agent spawned without a skill gets no allowed-tools grant`() = run {
        val rt = hubSession(ScriptedModel(reply(TextPart("child done"))))
        // 父会话里别的 skill 已经放行了一批工具：它们不能漏给一个与它无关的子 agent
        rt.grantSkillTools(listOf("Bash(make build)"), null, "other")
        val hub = rt.hub!!
        val res = hub.spawn(rt, dev.min.agent.protocol.TurnId("t1"), dev.min.agent.protocol.CallId("c1"),
            dev.min.agent.spi.DelegateRequest(description = "d", prompt = "p", agentType = "Explore", background = false))
        assertTrue("got $res", res is dev.min.agent.spi.DelegateResult.Finished)
        val child = hub.get((res as dev.min.agent.spi.DelegateResult.Finished).child)!!
        assertTrue(child.state.value.skillGrants.isEmpty())
    }

    // ---- allowManagedPermissionRulesOnly：托管层设了，项目 / 个人 / add-dir 来源的 allowed-tools 不生效 ----

    private fun managedOnly(permissions: String = "") {
        fs.files["/etc/claude-code/managed-settings.json"] =
            """{"allowManagedPermissionRulesOnly": true${if (permissions.isEmpty()) "" else ", \"permissions\": $permissions"}}"""
    }

    private fun from(source: ConfigSource, label: String = source.label, allowed: List<String> = listOf("Bash(make build)"), body: String = "!`make build`") = SkillDef(
        name = "s-${source.name.lowercase()}", description = "d", path = GuestPath("/workspace/.claude/skills/s/SKILL.md"), body = body,
        source = source, allowedTools = allowed, sourceLabel = label,
    )

    @Test
    fun `managed-only ignores the allowed-tools of project, personal and add-dir skills`() = run {
        managedOnly()
        // 最后一个是认不出来的来源标签：失败关闭，当作受影响
        for (skill in listOf(from(ConfigSource.Project), from(ConfigSource.User), from(ConfigSource.AddDir), from(ConfigSource.Cli, label = "mystery"))) {
            val rt = session()
            val r = typed(rt, skill)
            // allowed-tools 不生效：命令回到「需要批准」，非自动模式下整次中止，一条都没跑，也没有放行落日志
            assertTrue("${skill.sourceLabel} got $r", r is SlashResult.Refused)
            assertTrue(rt.state.value.skillGrants.isEmpty())
        }
        assertTrue(exec.runs.isEmpty())
    }

    @Test
    fun `managed-only still lets the managed rules allow the command the ignored skill lists`() = run {
        managedOnly(permissions = """{"allow": ["Bash(make build)"]}""")
        val rt = session()
        // 走托管规则：命令被托管层放行，所以能跑；但这不是 skill 的放行
        assertTrue(typed(rt, from(ConfigSource.Project)) is SlashResult.Expanded)
        assertEquals(1, exec.runs.size)
        assertTrue(rt.state.value.skillGrants.isEmpty())
    }

    @Test
    fun `managed-only keeps the allowed-tools of managed, bundled and plugin skills`() = run {
        managedOnly()
        for (skill in listOf(from(ConfigSource.Managed), from(ConfigSource.Bundled), from(ConfigSource.Plugin, label = "plugin:demo"))) {
            val rt = session()
            assertTrue("${skill.sourceLabel}", typed(rt, skill) is SlashResult.Expanded)
            assertEquals(listOf("Bash(make build)"), rt.state.value.skillGrants)
        }
    }

    @Test
    fun `managed-only is only about the managed layer, a project setting of it changes nothing`() = run {
        setProjectSettings("""{"allowManagedPermissionRulesOnly": true}""")
        val rt = session()
        assertTrue(typed(rt, from(ConfigSource.Project)) is SlashResult.Expanded)
    }

    @Test
    fun `managed-only keeps a forked project skill from handing its allowed-tools to the sub-agent`() = run {
        managedOnly()
        val rt = hubSession(ScriptedModel(reply(TextPart("child done"))))
        val r = typed(rt, project("pr", "summarize", allowed = listOf("Bash(gh pr view *)"), context = "fork", agent = "Explore", background = false))
        assertTrue("got $r", r is SlashResult.Forked)
        val child = rt.hub!!.get(rt.state.value.children.keys.single())!!
        assertTrue(child.state.value.skillGrants.isEmpty())
    }

    @Test
    fun `managed-only says which skill had its allowed-tools ignored, once`() = run {
        managedOnly()
        val rt = session()
        typed(rt, from(ConfigSource.Project))
        typed(rt, from(ConfigSource.Project))
        assertEquals(listOf("s-project"), rt.ignoredSkillTools())
        val notes = store.peek(rt.sessionId)!!.read().toList().mapNotNull { it.event as? dev.min.agent.spi.Note }
        assertEquals(1, notes.count { "s-project" in it.text && "allowManagedPermissionRulesOnly" in it.text })
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
