package dev.min.agent.kernel

import dev.min.agent.llm.ModelRequest
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeExec
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * security-guidance 在会话里的三层（CC 文档 security-guidance.md）：每次编辑后的模式提醒、回合末的独立模型审查、
 * 模型自己提交 / 推送后的审查。审查者是 hook agent 子会话（脚本模型按顺序回话）：主模型与审查者的请求在同一条脚本里按发生顺序排。
 */
class SecurityGuidanceSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val files = ConcurrentHashMap<String, String>()
    private val exec = FakeExec()
    private var counter = 0
    private val now = AtomicLong(1_000)

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(40_000) { block() } }

    private fun enable() {
        files["/workspace/.claude/settings.json"] = """{"enabledPlugins":{"security-guidance@claude-plugins-official":true}}"""
    }

    private val fs = object : WorkspaceFs {
        override suspend fun stat(path: GuestPath): FileStat? = when {
            files.containsKey(path.value) -> FileStat(FileKind.File, files.getValue(path.value).length.toLong(), 0)
            path.value == "/" || files.keys.any { it.startsWith(path.value + "/") } -> FileStat(FileKind.Directory, 0, 0)
            else -> null
        }

        override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray = files.getValue(path.value).toByteArray(Charsets.UTF_8)
        override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) {
            files[path.value] = bytes.toString(Charsets.UTF_8)
        }

        override suspend fun list(dir: GuestPath): List<DirEntry> {
            val prefix = dir.value.trimEnd('/') + "/"
            return files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
                .map { DirEntry(it, if (files.containsKey(prefix + it)) FileKind.File else FileKind.Directory) }
        }

        override fun scopeOf(path: GuestPath): PathScope = if (path.value.startsWith("/workspace")) PathScope.Workspace else PathScope.Rootfs
    }

    /** 写文件的替身：只声明路径与「会写」，真正的写没有意义 */
    private val write = object : Tool<JsonObject> {
        override val name = "Write"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = false)
        override fun description(profile: HarnessProfile) = "写文件"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext): PermissionAsk {
            val path = (input["file_path"] as? JsonPrimitive)?.content ?: "/dev/null"
            return PermissionAsk(name, "写文件", paths = listOf(PathAccess(GuestPath(path), AccessKind.Write, PathScope.Workspace)))
        }
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("wrote")))))
        }
    }

    private val bash = object : Tool<JsonObject> {
        override val name = "Bash"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = false)
        override fun description(profile: HarnessProfile) = "命令"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "命令")
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("committed")))))
        }
    }

    private fun hubFor(model: ScriptedModel, env: Map<String, String> = emptyMap(), trusted: Boolean = true, customizations: Customizations = Customizations.Full): SessionHub {
        val tools = listOf<Tool<JsonObject>>(write, bash)
        val base = testDeps(model, tools, idTag = "t${++counter}-")
        val deps = KernelDeps(
            clock = { now.addAndGet(10) }, ids = base.ids, models = base.models, tools = tools,
            host = HostPorts(fs, base.host.search, exec, base.host.artifacts),
            platform = "test", zone = ZoneOffset.UTC, memoryHome = GuestPath("/root"),
            env = { name -> env[name] ?: if (name == "CLAUDE_CODE_DISABLE_AUTO_MEMORY") "1" else null },
            trustProjectHooks = { trusted }, customizations = customizations,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return SessionHub(FileSessionStore(tmp.root.toPath(), { 0L }), deps, scope)
    }

    private suspend fun session(model: ScriptedModel, env: Map<String, String> = emptyMap(), trusted: Boolean = true, customizations: Customizations = Customizations.Full): SessionRuntime =
        hubFor(model, env, trusted, customizations).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)

    /** 重开：同一个日志目录、换一个新的内核实例（新的内存状态、新的脚本模型） */
    private suspend fun reopen(id: dev.min.agent.protocol.SessionId, model: ScriptedModel): SessionRuntime = hubFor(model).open(id)!!

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private fun writeCall(path: String, content: String) =
        ToolCallPart(CallId("w${++counter}"), "Write", args("file_path" to path, "content" to content))

    private fun commitCall(command: String = "git commit -m change") = ToolCallPart(CallId("b${++counter}"), "Bash", args("command" to command))

    private fun ModelRequest.reminders() =
        messages.flatMap { m -> m.parts.filterIsInstance<TextPart>().map { it.text } }.filter { it.startsWith("<system-reminder>") }

    private val bad = """{"ok": false, "reason": "a.py:1 eval of user input"}"""
    private val ok = """{"ok": true}"""

    // ---- 每次编辑后的模式提醒 ----

    @Test
    fun `an edit that matches a built-in pattern adds a reminder once per pattern and file`() = run {
        enable()
        val model = ScriptedModel(
            reply(writeCall("/workspace/a.py", "x = eval(s)"), stop = StopReason.ToolUse),
            reply(writeCall("/workspace/a.py", "y = eval(t)"), stop = StopReason.ToolUse),
            reply(writeCall("/workspace/b.py", "z = eval(u)"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
            reply(TextPart(ok)),
        )
        val rt = session(model)
        rt.say("write code")
        val second = model.requests[1].reminders()
        assertEquals(second.toString(), 1, second.count { "dynamic_code_execution" in it && "/workspace/a.py" in it })
        // 同一个文件同一个模式：不再提醒
        assertEquals(1, model.requests[2].reminders().count { "/workspace/a.py" in it })
        // 换个文件：提醒
        assertEquals(1, model.requests[3].reminders().count { "/workspace/b.py" in it })
        // 重放一致
        val reopened = SessionRuntime.open(FileSessionStore(tmp.root.toPath(), { 0L }), rt.sessionId, rt.deps, CoroutineScope(SupervisorJob()).also { scopes += it })!!
        assertEquals(rt.state.value.modelMessages(), reopened.state.value.modelMessages())
    }

    @Test
    fun `what the edit does not write is not flagged and an edit without a pattern adds nothing`() = run {
        enable()
        val model = ScriptedModel(
            reply(
                ToolCallPart(CallId("e1"), "Write", args("file_path" to "/workspace/a.py", "content" to "safe()", "old_string" to "eval(x)")),
                stop = StopReason.ToolUse,
            ),
            reply(TextPart("done")),
            reply(TextPart(ok)),
        )
        val rt = session(model)
        rt.say("edit")
        assertTrue(model.requests[1].reminders().isEmpty())
    }

    @Test
    fun `it stays off unless the plugin is enabled, and each switch turns its layer off`() = run {
        // 没启用
        val plain = ScriptedModel(reply(writeCall("/workspace/a.py", "x = eval(s)"), stop = StopReason.ToolUse), reply(TextPart("done")))
        session(plain).say("write")
        assertTrue(plain.requests[1].reminders().isEmpty())
        assertEquals(2, plain.requests.size)

        enable()
        for (env in listOf("SECURITY_GUIDANCE_DISABLE" to "1", "ENABLE_PATTERN_RULES" to "0")) {
            val m = ScriptedModel(reply(writeCall("/workspace/a.py", "x = eval(s)"), stop = StopReason.ToolUse), reply(TextPart("done")), reply(TextPart(ok)))
            session(m, mapOf(env)).say("write")
            assertTrue("$env", m.requests[1].reminders().isEmpty())
        }
    }

    @Test
    fun `a project that is not trusted cannot switch it on`() = run {
        enable()
        val m = ScriptedModel(reply(writeCall("/workspace/a.py", "x = eval(s)"), stop = StopReason.ToolUse), reply(TextPart("done")))
        session(m, trusted = false).say("write")
        assertTrue(m.requests[1].reminders().isEmpty())
        // 没有多出审查者的请求：主模型两次，没有第三次
        assertEquals(2, m.requests.size)
    }

    @Test
    fun `disableAllHooks and the bare or safe launch modes switch it off like they do for a plugin`() = run {
        files["/workspace/.claude/settings.json"] =
            """{"disableAllHooks":true,"enabledPlugins":{"security-guidance@claude-plugins-official":true}}"""
        val off = ScriptedModel(reply(writeCall("/workspace/a.py", "x = eval(s)"), stop = StopReason.ToolUse), reply(TextPart("done")))
        session(off).say("write")
        assertTrue(off.requests[1].reminders().isEmpty())
        assertEquals(2, off.requests.size)

        enable()
        for (mode in listOf(Customizations.Bare, Customizations.Safe)) {
            val m = ScriptedModel(reply(writeCall("/workspace/a.py", "x = eval(s)"), stop = StopReason.ToolUse), reply(TextPart("done")))
            session(m, customizations = mode).say("write")
            assertTrue("$mode", m.requests[1].reminders().isEmpty())
            assertEquals(2, m.requests.size)
        }
    }

    @Test
    fun `custom patterns from security-patterns json are checked too`() = run {
        enable()
        files["/workspace/.claude/security-patterns.json"] =
            """{"patterns":[{"rule_name":"internal_key","substrings":["AKIA"],"reminder":"Hardcoded key."}]}"""
        val model = ScriptedModel(reply(writeCall("/workspace/a.py", "k='AKIAXXXX'"), stop = StopReason.ToolUse), reply(TextPart("done")), reply(TextPart(ok)))
        session(model).say("write")
        assertTrue(model.requests[1].reminders().any { "internal_key" in it && "Hardcoded key." in it })
    }

    @Test
    fun `reminders and findings already given are not given again after the session is reopened`() = run {
        enable()
        exec.script = { cmd, _ -> if (cmd.script.startsWith("git show")) ExecResult.Exited(0, "diff", false, 1) else ExecResult.Exited(0, "", false, 1) }
        val first = ScriptedModel(
            reply(writeCall("/workspace/a.py", "x = eval(s)"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
            reply(TextPart(bad)),
            reply(TextPart("fixed")),
        )
        val rt = session(first)
        rt.say("write")
        assertTrue(rt.state.value.securityWarned.isNotEmpty() && rt.state.value.securityReported.isNotEmpty())

        val second = ScriptedModel(
            reply(writeCall("/workspace/a.py", "y = eval(t)"), stop = StopReason.ToolUse),
            reply(commitCall(), stop = StopReason.ToolUse),
            reply(TextPart(bad)),
            reply(TextPart("done")),
            reply(TextPart(ok)),
        )
        val reopened = reopen(rt.sessionId, second)
        // 日志里的去重表在新实例里原样恢复
        assertEquals(rt.state.value.securityWarned, reopened.state.value.securityWarned)
        reopened.say("again")
        // 同一个模式同一个文件：重开后不再提醒（历史里只有重开前那一条）
        assertEquals(1, second.requests[1].reminders().count { "dynamic_code_execution" in it })
        // 回合末审查已经报过的同一条：提交审查不重复提醒
        assertTrue(second.requests[3].reminders().none { "published" in it })
    }

    // ---- 回合末的独立审查 ----

    @Test
    fun `a turn that changed files is reviewed and the findings re-prompt the model`() = run {
        enable()
        files["/workspace/.claude/claude-security-guidance.md"] = "Never log customer_id."
        val model = ScriptedModel(
            reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
            reply(TextPart(bad)),
            reply(TextPart("fixed it")),
        )
        val rt = session(model)
        rt.say("write")
        assertEquals(4, model.requests.size)
        // 审查者拿到改动的文件与项目指引
        val reviewPrompt = model.requests[2].messages.flatMap { it.parts.filterIsInstance<TextPart>() }.joinToString("\n") { it.text }
        assertTrue(reviewPrompt.contains("/workspace/a.py") && reviewPrompt.contains("print(1)") && reviewPrompt.contains("Never log customer_id."))
        // 主模型被带着发现重新提示
        val feedback = model.requests[3].messages.flatMap { it.parts.filterIsInstance<TextPart>() }.joinToString("\n") { it.text }
        assertTrue(feedback, feedback.contains("a.py:1 eval of user input"))
        assertTrue(rt.state.value.turn == null)
    }

    @Test
    fun `a clean review or a turn without edits adds no re-prompt`() = run {
        enable()
        val model = ScriptedModel(
            reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
            reply(TextPart(ok)),
            reply(TextPart("no edits this turn")),
        )
        val rt = session(model)
        rt.say("write")
        assertEquals(3, model.requests.size)
        rt.say("just talk")
        // 这一回合没改文件：不审查
        assertEquals(4, model.requests.size)
    }

    @Test
    fun `the review fires at most three times in a row and then yields`() = run {
        enable()
        val steps = mutableListOf<dev.min.agent.testkit.Step>()
        repeat(3) {
            steps += reply(writeCall("/workspace/a.py", "print($it)"), stop = StopReason.ToolUse)
            steps += reply(TextPart("done"))
            steps += reply(TextPart(bad))
        }
        steps += reply(writeCall("/workspace/a.py", "print(9)"), stop = StopReason.ToolUse)
        steps += reply(TextPart("done"))
        val model = ScriptedModel(*steps.toTypedArray())
        session(model).say("write")
        // 3 次发现之后第 4 次回合末不再审查：主模型 8 次 + 审查者 3 次
        assertEquals(11, model.requests.size)
    }

    @Test
    fun `the end of turn review can be switched off by its own variable`() = run {
        enable()
        val model = ScriptedModel(reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse), reply(TextPart("done")))
        session(model, mapOf("ENABLE_STOP_REVIEW" to "0")).say("write")
        assertEquals(2, model.requests.size)
        val model2 = ScriptedModel(reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse), reply(TextPart("done")))
        session(model2, mapOf("ENABLE_CODE_SECURITY_REVIEW" to "0")).say("write")
        assertEquals(2, model2.requests.size)
    }

    // ---- 提交 / 推送后的审查 ----

    @Test
    fun `a commit the model makes is reviewed and the findings land on the tool result`() = run {
        enable()
        exec.script = { cmd, _ -> if (cmd.script.startsWith("git show")) ExecResult.Exited(0, "diff --git a/a.py b/a.py\n+eval(x)", false, 1) else ExecResult.Exited(0, "", false, 1) }
        val model = ScriptedModel(
            reply(commitCall(), stop = StopReason.ToolUse),
            reply(TextPart(bad)),
            reply(TextPart("done")),
        )
        session(model).say("commit it")
        val review = model.requests[1].messages.flatMap { it.parts.filterIsInstance<TextPart>() }.joinToString("\n") { it.text }
        assertTrue(review.contains("git commit -m change") && review.contains("+eval(x)"))
        assertTrue(model.requests[2].reminders().any { "a.py:1 eval of user input" in it })
    }

    @Test
    fun `a commit review finding the turn end review already reported is not repeated`() = run {
        enable()
        exec.script = { cmd, _ -> if (cmd.script.startsWith("git show")) ExecResult.Exited(0, "diff", false, 1) else ExecResult.Exited(0, "", false, 1) }
        val model = ScriptedModel(
            reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
            reply(TextPart(bad)),
            reply(commitCall(), stop = StopReason.ToolUse),
            reply(TextPart(bad)),
            reply(TextPart("committed")),
        )
        session(model).say("write and commit")
        assertEquals(6, model.requests.size)
        assertTrue(model.requests[5].reminders().none { "published" in it })
    }

    @Test
    fun `commands that are not a commit or a push are not reviewed, and a clean commit adds nothing`() = run {
        enable()
        exec.script = { cmd, _ -> if (cmd.script.startsWith("git show")) ExecResult.Exited(0, "diff", false, 1) else ExecResult.Exited(0, "", false, 1) }
        val model = ScriptedModel(
            reply(commitCall("git status"), stop = StopReason.ToolUse),
            reply(commitCall("git push origin main"), stop = StopReason.ToolUse),
            reply(TextPart(ok)),
            reply(TextPart("done")),
        )
        session(model).say("go")
        assertTrue(model.requests[3].reminders().isEmpty())
        assertEquals(4, model.requests.size)
        // git status 不审查，git push 审查一次（取最近一次提交的 diff）
        assertEquals(1, exec.runs.count { it.first.script.startsWith("git show") })
    }

    @Test
    fun `commit reviews are capped at twenty per rolling hour`() = run {
        enable()
        exec.script = { cmd, _ -> if (cmd.script.startsWith("git show")) ExecResult.Exited(0, "diff", false, 1) else ExecResult.Exited(0, "", false, 1) }
        val steps = mutableListOf<dev.min.agent.testkit.Step>()
        repeat(22) {
            steps += reply(commitCall(), stop = StopReason.ToolUse)
            if (it < 20) steps += reply(TextPart(ok))
        }
        steps += reply(TextPart("done"))
        val model = ScriptedModel(*steps.toTypedArray())
        session(model).say("commit a lot")
        // 22 次提交里只有前 20 次叫了审查者：主模型 23 次 + 审查者 20 次
        assertEquals(43, model.requests.size)
        assertFalse(model.requests.isEmpty())
    }
}
