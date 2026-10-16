package dev.min.agent.ext.skills

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SkillDef
import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文里的 `` !`命令` ``：识别规则、占位与回填的顺序、参数不会借位执行、失败整次中止、fork 经端口跑。
 * 只测文本与顺序；谁能跑、信任、权限在内核的 KernelSkillRunPort（SkillShellRunTest）。
 */
class SkillRunTest {
    private val skillDir = "/workspace/.claude/skills/ship"

    private fun skill(
        name: String,
        body: String,
        source: ConfigSource = ConfigSource.Project,
        context: String? = null,
        allowed: List<String> = emptyList(),
        agent: String? = null,
        userInvocable: Boolean = true,
    ) = SkillDef(
        name = name, description = "d", path = GuestPath("$skillDir/SKILL.md"), body = body, source = source,
        context = context, allowedTools = allowed, agent = agent, userInvocable = userInvocable,
    )

    /** 记录每一步的假端口：命令结果由 [outputs] 决定，没写的命令回显 `out:<命令>` */
    private class FakePort(
        val outputs: Map<String, RunResult> = emptyMap(),
        val forkResult: RunResult = RunResult.Ok("report"),
    ) : SkillRunPort {
        val log = mutableListOf<String>()
        val ran = mutableListOf<String>()
        val forks = mutableListOf<String>()

        override suspend fun begin(owner: SkillRunOwner) {
            log += "begin:${owner.name}"
        }

        override suspend fun shell(owner: SkillRunOwner, command: String): RunResult {
            log += "shell:$command"
            ran += command
            return outputs[command] ?: RunResult.Ok("out:$command")
        }

        override suspend fun fork(owner: SkillRunOwner, prompt: String): RunResult {
            log += "fork:${owner.name}"
            forks += plain(prompt)
            return forkResult
        }
    }

    private fun owner(name: String = "ship", fork: Boolean = false) =
        SkillRunOwner(name = name, source = "project", allowedTools = emptyList(), fork = fork, agent = null, background = true, baseDir = skillDir)

    /** 正文前面的 `Base directory for this skill:` 行是渲染的固定部分，比对正文时去掉它 */
    private companion object {
        fun plain(s: String): String = s.replace(Regex("Base directory for this skill: [^\n]*\n\n"), "")
    }

    private fun run(body: String, args: String? = null, port: SkillRunPort, fork: Boolean = false): RunResult = runBlocking {
        SkillRun.run(owner(fork = fork), body, args, emptyList(), SkillVars(projectDir = "/workspace"), port).let { if (it is RunResult.Ok) RunResult.Ok(plain(it.text)) else it }
    }

    // ---- 识别 ----

    @Test
    fun `inline markers at a line start or after whitespace are commands, a marker glued to a word is literal`() {
        val t = SkillShell.tokenize("A !`git status` B\nKEY=!`nope` x", skillDir, "/workspace")
        assertEquals(listOf("git status"), t.commands)
        assertTrue(t.text.contains("KEY=!`nope`"))
        assertFalse(t.text.contains("!`git status`"))
    }

    @Test
    fun `a fenced block is one multi-line command and the markers inside it are not separate commands`() {
        val body = "before\n```!\necho one\necho `two` !`x`\n```\nafter"
        val t = SkillShell.tokenize(body, skillDir, "/workspace")
        assertEquals(listOf("echo one\necho `two` !`x`"), t.commands)
        assertEquals("before\n\u0000" + "0" + "\u0000\nafter", t.text)
    }

    @Test
    fun `skill and project dir variables are substituted in the command text before it runs`() {
        val t = SkillShell.tokenize("!`\${CLAUDE_SKILL_DIR}/run.sh \${CLAUDE_PROJECT_DIR}`", skillDir, "/workspace")
        assertEquals(listOf("$skillDir/run.sh /workspace"), t.commands)
        // 命令（没有目录的 command）里的 ${CLAUDE_SKILL_DIR} 原样留着，不猜值
        assertEquals(listOf("\${CLAUDE_SKILL_DIR}/x"), SkillShell.tokenize("!`\${CLAUDE_SKILL_DIR}/x`", null, null).commands)
    }

    @Test
    fun `a NUL in the body cannot forge a placeholder`() {
        val t = SkillShell.tokenize("a\u0000" + "0" + "\u0000 !`echo hi`", skillDir, null)
        assertEquals(listOf("echo hi"), t.commands)
        assertEquals("splice keeps the forged text out of the output", "a�0� " + "out", SkillShell.splice(t.text, listOf("out")))
    }

    // ---- 顺序与回填 ----

    @Test
    fun `commands run in order and their output replaces the marker`() {
        val port = FakePort()
        val result = run("x !`echo a` y !`echo b` z", port = port)
        assertEquals(RunResult.Ok("x out:echo a y out:echo b z"), result)
        assertEquals(listOf("echo a", "echo b"), port.ran)
    }

    @Test
    fun `the begin hook runs before any command`() {
        val port = FakePort()
        run("!`echo a`", port = port)
        assertEquals(listOf("begin:ship", "shell:echo a"), port.log)
    }

    @Test
    fun `output is inserted as plain text and is not scanned again for markers`() {
        val port = FakePort(outputs = mapOf("echo a" to RunResult.Ok("!`echo pwned`")))
        val result = run("!`echo a`", port = port)
        assertEquals(RunResult.Ok("!`echo pwned`"), result)
        assertEquals(listOf("echo a"), port.ran)
    }

    @Test
    fun `a marker that arrives through arguments is never executed`() {
        val port = FakePort()
        val result = run("Do: \$ARGUMENTS", args = "!`rm -rf build`", port = port)
        assertEquals(RunResult.Ok("Do: !`rm -rf build`"), result)
        assertTrue(port.ran.isEmpty())
    }

    @Test
    fun `dollar arguments inside a command are not substituted into it`() {
        val port = FakePort()
        run("!`echo \$ARGUMENTS`", args = "x", port = port)
        assertEquals(listOf("echo \$ARGUMENTS"), port.ran)
    }

    @Test
    fun `one refused command aborts the whole invocation and the rest do not run`() {
        val port = FakePort(outputs = mapOf("b" to RunResult.Refused("no")))
        val result = run("!`a` !`b` !`c`", port = port)
        assertEquals(RunResult.Refused("no"), result)
        assertEquals(listOf("a", "b"), port.ran)
    }

    @Test
    fun `fork runs the rendered text with the outputs in place`() {
        val port = FakePort(forkResult = RunResult.Ok("the report"))
        val result = run("status: !`echo a`", port = port, fork = true)
        assertEquals(RunResult.Ok("the report"), result)
        assertEquals(listOf("status: out:echo a"), port.forks)
    }

    @Test
    fun `a fork refusal comes back as refused`() {
        val port = FakePort(forkResult = RunResult.Refused("no agent"))
        assertEquals(RunResult.Refused("no agent"), run("x", port = port, fork = true))
    }

    // ---- 斜杠入口 ----

    private fun expandRun(input: String, skills: List<SkillDef>, port: SkillRunPort, commands: List<dev.min.agent.ext.config.CommandDef> = emptyList()) =
        runBlocking { SlashCommands.expandRun(input, skills, commands, SkillVars(projectDir = "/workspace"), port) }.let { if (it is SlashResult.Expanded) SlashResult.Expanded(plain(it.text), it.names) else it }

    @Test
    fun `the pure expansion leaves markers in place so only the port ever runs a command`() {
        val s = skill("ship", "go !`git push`")
        val r = SlashCommands.expand("/ship", listOf(s), emptyList()) as SlashResult.Expanded
        assertEquals("go !`git push`", plain(r.text))
    }

    @Test
    fun `expandRun substitutes the outputs and keeps the stacked order`() {
        val a = skill("a", "A !`echo 1` \$ARGUMENTS")
        val b = skill("b", "B \$ARGUMENTS")
        val port = FakePort()
        val r = expandRun("/a /b hi", listOf(a, b), port)
        assertEquals(SlashResult.Expanded("A out:echo 1 hi\n\nB hi", listOf("a", "b")), r)
    }

    @Test
    fun `a refused command in a slash invocation comes back as refused and nothing is sent`() {
        val s = skill("ship", "!`rm -rf build`")
        val port = FakePort(outputs = mapOf("rm -rf build" to RunResult.Refused("Shell command permission check failed")))
        assertEquals(SlashResult.Refused("Shell command permission check failed"), expandRun("/ship", listOf(s), port))
    }

    @Test
    fun `a fork skill typed as a slash command is forked, not expanded inline`() {
        val s = skill("review", "look at !`git diff`", context = "fork", agent = "Explore")
        val port = FakePort(forkResult = RunResult.Ok("done"))
        val r = expandRun("/review", listOf(s), port)
        assertEquals(SlashResult.Forked("review", "done"), r)
        assertEquals(listOf("look at out:git diff"), port.forks)
    }

    @Test
    fun `a path that is not a skill name is not a command even with a slash inside`() {
        val port = FakePort()
        assertEquals(SlashResult.NotACommand, expandRun("/etc/hosts", emptyList(), port))
    }

    @Test
    fun `a nested skill is called by its qualified name`() {
        val nested = skill("apps/web:deploy", "deploy web")
        val r = expandRun("/apps/web:deploy", listOf(nested), FakePort())
        assertEquals(SlashResult.Expanded("deploy web", listOf("apps/web:deploy")), r)
    }


    @Test
    fun `a blocking expansion gate stops the invocation before any command or fork runs`() {
        val s = skill("ship", "go !`git push`")
        val port = FakePort()
        val r = runBlocking {
            SlashCommands.expandRun("/ship", listOf(s), emptyList(), SkillVars(projectDir = "/workspace"), port, gate = SlashGate { "blocked by hook" })
        }
        assertEquals(SlashResult.Refused("blocked by hook"), r)
        assertTrue("nothing may run before the gate: ${port.log}", port.log.isEmpty())
    }

    @Test
    fun `the gate sees the first target's name, args, source and the typed line`() {
        val s = skill("ship", "x")
        var seen: SlashGateInfo? = null
        runBlocking {
            SlashCommands.expandRun("/ship  now  please", listOf(s), emptyList(), SkillVars(), FakePort(), gate = SlashGate { seen = it; null })
        }
        assertEquals(SlashGateInfo("ship", "now  please", "project", "/ship  now  please"), seen)
    }

    // ---- UserPromptSubmit 先于展开（CC 2.1.299） ----

    @Test
    fun `a blocking submit gate stops the invocation before the expansion gate, any command or fork`() {
        val s = skill("ship", "go !`touch marker`")
        val port = FakePort()
        val calls = mutableListOf<String>()
        val r = runBlocking {
            SlashCommands.expandRun(
                "/ship now", listOf(s), emptyList(), SkillVars(projectDir = "/workspace"), port,
                gate = SlashGate { calls += "expansion"; null },
                submitGate = SlashSubmitGate { calls += "submit:$it"; PromptSubmitVerdict.Blocked("no secrets") },
            )
        }
        assertEquals(SlashResult.Refused("no secrets"), r)
        assertEquals("the expansion gate must not even be asked", listOf("submit:/ship now"), calls)
        assertTrue("nothing may run before the hook says yes: ${port.log}", port.log.isEmpty())
    }

    @Test
    fun `a passing submit gate runs first with the raw line, then the expansion gate, then the commands`() {
        val s = skill("ship", "go !`make`")
        val order = mutableListOf<String>()
        val port = object : SkillRunPort {
            override suspend fun shell(owner: SkillRunOwner, command: String): RunResult { order += "shell"; return RunResult.Ok("done") }
            override suspend fun fork(owner: SkillRunOwner, prompt: String) = RunResult.Ok("")
        }
        val r = runBlocking {
            SlashCommands.expandRun(
                "  /ship  now  ", listOf(s), emptyList(), SkillVars(projectDir = "/workspace"), port,
                gate = SlashGate { order += "expansion:${it.prompt}"; null },
                submitGate = SlashSubmitGate { order += "submit:$it"; PromptSubmitVerdict.Pass(listOf("ctx")) },
            )
        } as SlashResult.Expanded
        assertEquals(listOf("submit:/ship  now", "expansion:/ship  now", "shell"), order)
        assertEquals("the contexts ride along so the kernel does not run the hook again", listOf("ctx"), r.submitContexts)
    }

    @Test
    fun `without a submit gate nothing is marked as screened, and plain input never reaches the gate`() {
        val s = skill("ship", "x")
        assertNull((expandRunRaw("/ship", s) as SlashResult.Expanded).submitContexts)
        var asked = 0
        val gate = SlashSubmitGate { asked++; PromptSubmitVerdict.Pass(emptyList()) }
        runBlocking {
            SlashCommands.expandRun("hello", listOf(s), emptyList(), SkillVars(), FakePort(), submitGate = gate)
            SlashCommands.expandRun("/etc/hosts", listOf(s), emptyList(), SkillVars(), FakePort(), submitGate = gate)
            SlashCommands.expandRun("/nope", listOf(s), emptyList(), SkillVars(), FakePort(), submitGate = gate)
        }
        assertEquals("not a command / unknown: the kernel screens plain text itself", 0, asked)
    }

    @Test
    fun `an MCP prompt is screened on the typed line too, and a block refuses it`() {
        val p = dev.min.agent.ext.mcp.McpPromptCommand("mcp__s__p", "s", "p", listOf(dev.min.agent.ext.mcp.McpPromptArgument("arg", true)))
        val passed = runBlocking {
            SlashCommands.expandRun("/mcp__s__p hi", emptyList(), emptyList(), SkillVars(), FakePort(), listOf(p), submitGate = SlashSubmitGate { PromptSubmitVerdict.Pass(listOf("c")) })
        }
        assertEquals(listOf("c"), (passed as SlashResult.McpPrompt).submitContexts)
        val blocked = runBlocking {
            SlashCommands.expandRun("/mcp__s__p hi", emptyList(), emptyList(), SkillVars(), FakePort(), listOf(p), submitGate = SlashSubmitGate { PromptSubmitVerdict.Blocked("nope") })
        }
        assertEquals(SlashResult.Refused("nope"), blocked)
    }

    private fun expandRunRaw(input: String, s: SkillDef) =
        runBlocking { SlashCommands.expandRun(input, listOf(s), emptyList(), SkillVars(projectDir = "/workspace"), FakePort()) }
    @Test
    fun `the allowed tools reach the port with the owner`() {
        val s = skill("ship", "x", allowed = listOf("Bash(make build)"))
        var seen: SkillRunOwner? = null
        val port = object : SkillRunPort {
            override suspend fun begin(owner: SkillRunOwner) { seen = owner }
            override suspend fun shell(owner: SkillRunOwner, command: String) = RunResult.Ok("")
            override suspend fun fork(owner: SkillRunOwner, prompt: String) = RunResult.Ok("")
        }
        expandRun("/ship", listOf(s), port)
        assertEquals(listOf("Bash(make build)"), seen!!.allowedTools)
        assertEquals("project", seen!!.source)
        assertNull(seen!!.agent)
        assertFalse(seen!!.fork)
    }
}
