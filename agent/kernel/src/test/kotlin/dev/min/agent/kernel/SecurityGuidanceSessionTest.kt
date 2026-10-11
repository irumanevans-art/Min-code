package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
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
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.protocol.ModelRef
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
import kotlinx.coroutines.flow.emitAll
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

    /** 工具与 git 命令实际发生的先后（测基线是不是在第一次编辑之前拍的） */
    private val order = java.util.Collections.synchronizedList(mutableListOf<String>())
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
            order += "write"
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
            order += "bash"
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("committed")))))
        }
    }

    private fun hubFor(
        model: ModelClient, env: Map<String, String> = emptyMap(), trusted: Boolean = true, customizations: Customizations = Customizations.Full,
        reviewer: ScriptedModel? = null,
    ): SessionHub {
        val tools = listOf<Tool<JsonObject>>(write, bash)
        val base = testDeps(model, tools, idTag = "t${++counter}-")
        val deps = KernelDeps(
            clock = { now.addAndGet(10) }, ids = base.ids, tools = tools,
            // 点名了别的审查模型时，除会话模型之外的型号都交给 reviewer 这条脚本
            models = if (reviewer == null) base.models else ModelResolver { ref -> if (ref == TEST_MODEL) base.models.resolve(ref) else ResolvedModel(TEST_PROFILE.copy(ref = ref), reviewer) },
            host = HostPorts(fs, base.host.search, exec, base.host.artifacts),
            platform = "test", zone = ZoneOffset.UTC, memoryHome = GuestPath("/root"),
            env = { name -> env[name] ?: if (name == "CLAUDE_CODE_DISABLE_AUTO_MEMORY") "1" else null },
            trustProjectHooks = { trusted }, customizations = customizations,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return SessionHub(FileSessionStore(tmp.root.toPath(), { 0L }), deps, scope)
    }

    private suspend fun session(
        model: ModelClient, env: Map<String, String> = emptyMap(), trusted: Boolean = true, customizations: Customizations = Customizations.Full,
        reviewer: ScriptedModel? = null, headless: Boolean = false,
    ): SessionRuntime =
        hubFor(model, env, trusted, customizations, reviewer).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions, headless = headless)

    /** 重开：同一个日志目录、换一个新的内核实例（新的内存状态、新的脚本模型） */
    private suspend fun reopen(id: dev.min.agent.protocol.SessionId, model: ModelClient): SessionRuntime = hubFor(model).open(id)!!

    /**
     * 说一句话并等到会话完全安静：回合收尾后审查在后台跑，发现再作为 hook 来源的输入把会话叫起来跟进，
     * 所以光等「这个回合结束」不够，要等后台审查全出结论、排进来的跟进回合也跑完。
     */
    private suspend fun SessionRuntime.say(text: String, settle: Boolean = true) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
        if (settle) settled()
    }

    private suspend fun SessionRuntime.settled() {
        while (true) {
            security.awaitReviews()
            state.first { it.turn == null }
            if (security.pendingReviews() == 0 && isQuiescent()) return
            kotlinx.coroutines.delay(5)
        }
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

    // ---- 审查模型（SECURITY_REVIEW_MODEL / SG_AGENTIC_MODEL） ----

    @Test
    fun `the review model variables pick the model of each model-backed review`() = run {
        enable()
        exec.script = { cmd, _ -> if (cmd.script.startsWith("git show")) ExecResult.Exited(0, "diff", false, 1) else ExecResult.Exited(0, "", false, 1) }
        val main = ScriptedModel(
            reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse),
            reply(commitCall(), stop = StopReason.ToolUse),
            reply(TextPart("done")),
            reply(TextPart("fixed")),
        )
        // 审查者的回话按发生顺序：提交审查先、回合末审查后
        val reviewer = ScriptedModel(reply(TextPart(ok)), reply(TextPart(bad)))
        session(main, mapOf("SECURITY_REVIEW_MODEL" to "turn-model", "SG_AGENTIC_MODEL" to "other/commit-model"), reviewer = reviewer).say("go")
        assertEquals(
            listOf(ModelRef("test", "commit-model").copy(providerId = "other"), ModelRef("test", "turn-model")),
            reviewer.requests.map { it.profile.ref },
        )
        // 主模型自己只有 4 次请求：写、提交、结束、被审查发现重新提示后的收尾
        assertEquals(4, main.requests.size)
    }

    @Test
    fun `without the variables the reviews run on the session model`() = run {
        enable()
        val main = ScriptedModel(reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse), reply(TextPart("done")), reply(TextPart(ok)))
        val reviewer = ScriptedModel()
        session(main, reviewer = reviewer).say("go")
        assertEquals(3, main.requests.size)
        assertTrue(reviewer.requests.isEmpty())
    }

    @Test
    fun `a review model outside availableModels is refused and the session model reviews instead`() = run {
        enable()
        files["/etc/claude-code/managed-settings.json"] = """{"availableModels":["scripted"]}"""
        val main = ScriptedModel(
            reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
            reply(TextPart(ok)),
        )
        val reviewer = ScriptedModel(reply(TextPart(ok)))
        val rt = session(main, mapOf("SECURITY_REVIEW_MODEL" to "turn-model"), reviewer = reviewer)
        rt.say("go")
        // 名单外的型号一次都没有被调用；审查落回会话模型
        assertTrue(reviewer.requests.isEmpty())
        assertEquals(3, main.requests.size)
        assertTrue(rt.state.value.presentation.map { it.record.event }.filterIsInstance<dev.min.agent.spi.Note>().any { "availableModels" in it.text && "turn-model" in it.text })
    }

    @Test
    fun `a deniedModels entry also stops a named review model`() = run {
        enable()
        files["/etc/claude-code/managed-settings.json"] = """{"deniedModels":["commit-model"]}"""
        exec.script = { cmd, _ -> if (cmd.script.startsWith("git show")) ExecResult.Exited(0, "diff", false, 1) else ExecResult.Exited(0, "", false, 1) }
        val main = ScriptedModel(reply(commitCall(), stop = StopReason.ToolUse), reply(TextPart(ok)), reply(TextPart("done")))
        val reviewer = ScriptedModel(reply(TextPart(ok)))
        session(main, mapOf("SG_AGENTIC_MODEL" to "commit-model"), reviewer = reviewer).say("commit")
        assertTrue(reviewer.requests.isEmpty())
        assertEquals(3, main.requests.size)
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
        session(model, headless = true).say("commit it")
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
        session(model, headless = true).say("write and commit")
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
        session(model, headless = true).say("go")
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
        session(model, headless = true).say("commit a lot")
        // 22 次提交里只有前 20 次叫了审查者：主模型 23 次 + 审查者 20 次
        assertEquals(43, model.requests.size)
        assertFalse(model.requests.isEmpty())
    }

    // ---- 改动清单取 git 工作区的差异（CC：回合开头记基线、回合末 diff） ----

    private val t1 = "1".repeat(40)
    private val t2 = "2".repeat(40)

    /** 假仓库：前几次快照依次给 [trees]（最后一个一直给下去）；diff 命令给 [diff]；别的命令空输出成功 */
    private fun gitRepo(trees: List<String>, diff: String = "", extra: (String) -> ExecResult? = { null }) {
        val queue = ArrayDeque(trees)
        exec.script = { cmd, _ ->
            val script = cmd.script
            extra(script) ?: when {
                "write-tree" in script -> synchronized(queue) {
                    // 第一次快照要拍一会儿：写工具若不等基线，就会赶在它前面落笔（记录放在睡醒之后）
                    if (queue.size == trees.size) Thread.sleep(150)
                    order += "snapshot"
                    ExecResult.Exited(0, (if (queue.size > 1) queue.removeFirst() else queue.first()) + "\n", false, 1)
                }
                "--name-only" in script -> ExecResult.Exited(0, diff, false, 1)
                else -> ExecResult.Exited(0, "", false, 1)
            }
        }
    }

    private fun diffOf(vararg files: Pair<String, String>, top: String = "/workspace") =
        "TOP=$top\n---MIN-SG-NAMES---\n" + files.joinToString("") { it.first + "\n" } + "---MIN-SG-PATCH---\n" +
            files.joinToString("") { (name, body) -> "diff --git a/$name b/$name\n$body\n" }

    private fun reviewText(req: ModelRequest) = req.messages.flatMap { it.parts.filterIsInstance<TextPart>() }.joinToString("\n") { it.text }

    @Test
    fun `a change a shell command made, which no file tool saw, is reviewed from the git diff`() = run {
        enable()
        gitRepo(listOf(t1, t2), diffOf("x.py" to "+++ b/x.py\n+eval(b)"))
        val model = ScriptedModel(
            reply(commitCall("sed -i 's/a/eval(b)/' x.py"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
            reply(TextPart(bad)),
            reply(TextPart("fixed")),
        )
        session(model).say("patch it with sed")
        val review = reviewText(model.requests[2])
        assertTrue(review, review.contains("=== /workspace/x.py ===") && review.contains("+eval(b)"))
        assertEquals(4, model.requests.size)
    }

    @Test
    fun `the baseline is taken before the first edit and the closing snapshot after the last`() = run {
        enable()
        gitRepo(listOf(t1, t2), diffOf("a.py" to "+x"))
        val model = ScriptedModel(reply(writeCall("/workspace/a.py", "x"), stop = StopReason.ToolUse), reply(TextPart("done")), reply(TextPart(ok)))
        session(model).say("write")
        assertEquals(listOf("snapshot", "write", "snapshot"), order.toList())
    }

    @Test
    fun `an unchanged work tree means no review even if commands ran`() = run {
        enable()
        // 两个 tree 相同：即便 diff 命令还能吐出东西也不该采信（真 git 里相同的 tree 没有差异，这里故意给一份来证明没去问它）
        gitRepo(listOf(t1, t1), diffOf("x.py" to "+eval(1)"))
        val model = ScriptedModel(reply(commitCall("ls"), stop = StopReason.ToolUse), reply(TextPart("done")))
        session(model).say("look around")
        assertEquals(2, model.requests.size)
    }

    @Test
    fun `files the file tools wrote that git does not list are still reviewed, and a file in both is shown once with its diff`() = run {
        enable()
        gitRepo(listOf(t1, t2), diffOf("x.py" to "+++ b/x.py\n+GITPATCH"))
        val model = ScriptedModel(
            reply(writeCall("/workspace/x.py", "TOOLTEXT"), stop = StopReason.ToolUse),
            reply(writeCall("/workspace/ignored.log", "OUTSIDE"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
            reply(TextPart(ok)),
        )
        session(model).say("write")
        val review = reviewText(model.requests[3])
        assertEquals(1, Regex("=== /workspace/x.py ===").findAll(review).count())
        assertTrue(review.contains("GITPATCH") && !review.contains("TOOLTEXT"))
        // git 的清单里没有（被忽略的、仓库之外的）：并上文件工具的摘录
        assertTrue(review.contains("=== /workspace/ignored.log ===") && review.contains("OUTSIDE"))
    }

    @Test
    fun `outside a git repository it falls back to what the file tools wrote`() = run {
        enable()
        exec.script = { cmd, _ -> if ("write-tree" in cmd.script) ExecResult.Exited(3, "", false, 1) else ExecResult.Exited(0, "", false, 1) }
        val model = ScriptedModel(
            reply(commitCall("sed -i s/a/b/ x.py"), stop = StopReason.ToolUse),
            reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
            reply(TextPart(ok)),
        )
        session(model).say("go")
        val review = reviewText(model.requests[3])
        assertTrue(review.contains("=== /workspace/a.py ===") && review.contains("print(1)"))
        // 只有 Bash 改的在仓库之外看不到：不审
        val bashOnly = ScriptedModel(reply(commitCall("sed -i s/a/b/ x.py"), stop = StopReason.ToolUse), reply(TextPart("done")))
        session(bashOnly).say("go")
        assertEquals(2, bashOnly.requests.size)
    }

    @Test
    fun `no more than thirty files are shown and the prompt says how many changed`() = run {
        enable()
        gitRepo(listOf(t1, t2), diffOf(*(1..40).map { "f$it.py" to "+x$it" }.toTypedArray()))
        val model = ScriptedModel(reply(commitCall("make"), stop = StopReason.ToolUse), reply(TextPart("done")), reply(TextPart(ok)))
        session(model).say("generate")
        val review = reviewText(model.requests[2])
        assertTrue(review.contains("=== /workspace/f30.py ===") && !review.contains("=== /workspace/f31.py ==="))
        assertTrue(review, review.contains("40 files changed"))
    }

    // ---- git push 推出多个提交 ----

    private val c0 = "a".repeat(40)
    private val c2 = "b".repeat(40)

    /** 假仓库里的一次推送：前后两次远端引用 [before] / [after]，`git log` 给 [log]（第一行 COUNT=…）；`git show HEAD` 给 SHOWHEAD */
    private fun pushRepo(before: String, after: String, log: String) {
        val refs = ArrayDeque(listOf(before, after))
        exec.script = { cmd, _ ->
            val s = cmd.script
            when {
                "for-each-ref" in s -> {
                    order += "refs"
                    ExecResult.Exited(0, synchronized(refs) { if (refs.size > 1) refs.removeFirst() else refs.first() }, false, 1)
                }
                "rev-list --count" in s -> {
                    order += "log"
                    ExecResult.Exited(0, log, false, 1)
                }
                s.startsWith("git show") -> ExecResult.Exited(0, "SHOWHEAD diff --git a/h b/h\n+head", false, 1)
                else -> ExecResult.Exited(0, "", false, 1)
            }
        }
    }

    @Test
    fun `a push is reviewed over every commit it delivered, not just the latest`() = run {
        enable()
        pushRepo(
            "$c0 refs/remotes/origin/main\n", "$c2 refs/remotes/origin/main\n",
            "COUNT=2\ncommit $c0\nfirst\n\ndiff --git a/a b/a\n+FIRSTCOMMIT\ncommit $c2\nsecond\n\ndiff --git a/b b/b\n+SECONDCOMMIT\n",
        )
        val model = ScriptedModel(
            reply(commitCall("git push origin main"), stop = StopReason.ToolUse),
            reply(TextPart(bad)),
            reply(TextPart("done")),
        )
        session(model, headless = true).say("push")
        val review = reviewText(model.requests[1])
        assertTrue(review, review.contains("FIRSTCOMMIT") && review.contains("SECONDCOMMIT") && review.contains("2 commits"))
        assertFalse(review.contains("SHOWHEAD"))
        assertTrue(model.requests[2].reminders().any { "a.py:1 eval of user input" in it })
        // 推送前的远端引用在工具跑之前取、推送后的在它之后取
        assertEquals(listOf("refs", "bash", "refs", "log"), order.toList())
    }

    @Test
    fun `a push that moved no remote ref falls back to the latest commit`() = run {
        enable()
        pushRepo("$c0 refs/remotes/origin/main\n", "$c0 refs/remotes/origin/main\n", "COUNT=0\n")
        val model = ScriptedModel(reply(commitCall("git push https://example.invalid/r.git main"), stop = StopReason.ToolUse), reply(TextPart(ok)), reply(TextPart("done")))
        session(model, headless = true).say("push")
        assertTrue(reviewText(model.requests[1]).contains("SHOWHEAD"))
    }

    @Test
    fun `a plain commit still reviews just that commit and takes no remote snapshot`() = run {
        enable()
        pushRepo("$c0 refs/remotes/origin/main\n", "$c2 refs/remotes/origin/main\n", "COUNT=2\nx\n")
        val model = ScriptedModel(reply(commitCall("git commit -m x"), stop = StopReason.ToolUse), reply(TextPart(ok)), reply(TextPart("done")))
        session(model, headless = true).say("commit")
        assertTrue(reviewText(model.requests[1]).contains("SHOWHEAD"))
        assertEquals(listOf("bash"), order.toList())
    }

    // ---- 回合末审查在后台跑 ----

    /** 按请求内容分流的模型：[kinds] 记下每个请求是谁发的（main 主模型 / goal 评估器 / review 审查者），按发生顺序 */
    private inner class RoutingModel(private val route: (ModelRequest, String) -> dev.min.agent.testkit.Step) : ModelClient {
        val requests = java.util.concurrent.CopyOnWriteArrayList<ModelRequest>()
        val kinds: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun stream(request: ModelRequest): Flow<dev.min.agent.llm.ModelStreamEvent> = flow {
            val kind = when {
                request.system.any { "hook evaluator" in it } -> "goal"
                reviewText(request).contains("independent security reviewer") -> "review"
                else -> "main"
            }
            requests += request
            kinds += kind
            emitAll(route(request, kind)(request))
        }
    }

    @Test
    fun `an interactive session ends the turn without waiting for the review, and the finding comes back as a follow-up`() = run {
        enable()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val mains = java.util.concurrent.atomic.AtomicInteger()
        val model = RoutingModel { _, kind ->
            when (kind) {
                "review" -> { req -> flow { gate.await(); emitAll(reply(TextPart(bad))(req)) } }
                else -> when (mains.incrementAndGet()) {
                    1 -> reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse)
                    2 -> reply(TextPart("done"))
                    else -> reply(TextPart("fixed"))
                }
            }
        }
        val rt = session(model)
        rt.say("write", settle = false)
        // 回复已经交出、回合已经结束，审查还卡在那里
        assertTrue(rt.state.value.turn == null)
        assertEquals(1, rt.security.pendingReviews())
        assertEquals(2, model.requests.count { it.system.none { s -> "hook evaluator" in s } && !reviewText(it).contains("independent security reviewer") })
        assertTrue(rt.state.value.history.filterIsInstance<HistoryEntry.User>().none { it.origin is dev.min.agent.spi.InputOrigin.Hook })

        gate.complete(Unit)
        rt.settled()
        assertEquals(listOf("main", "main", "review", "main"), model.kinds.toList())
        // 发现作为 hook 来源的输入把会话叫起来：主模型最后一次请求里带着它
        val last = reviewText(model.requests.last())
        assertTrue(last, last.contains("a.py:1 eval of user input"))
        val origin = rt.state.value.history.filterIsInstance<HistoryEntry.User>().last().origin
        assertEquals(dev.min.agent.spi.InputOrigin.Hook("security-guidance"), origin)
        assertTrue(rt.state.value.turn == null)
    }

    @Test
    fun `the background review starts only when the turn really ends, after the goal evaluation`() = run {
        enable()
        val mains = java.util.concurrent.atomic.AtomicInteger()
        val goals = java.util.concurrent.atomic.AtomicInteger()
        val model = RoutingModel { _, kind ->
            when (kind) {
                "goal" -> reply(TextPart(if (goals.incrementAndGet() == 1) """{"ok": false, "reason": "keep going"}""" else """{"ok": true, "reason": "done"}"""))
                "review" -> reply(TextPart(ok))
                else -> when (mains.incrementAndGet()) {
                    1 -> reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse)
                    2 -> reply(TextPart("first answer"))
                    else -> reply(TextPart("more"))
                }
            }
        }
        val rt = session(model)
        rt.commit(listOf(dev.min.agent.spi.GoalSet("all green")), dev.min.agent.spi.Durability.Sync)
        rt.say("go")
        // 目标没达成：回合续下去，审查不起；目标达成、回合真的要收尾，才起（并且只起一次，审的是整个回合攒下的改动）
        assertEquals(listOf("main", "main", "goal", "main", "goal", "review"), model.kinds.toList())
        assertTrue(reviewText(model.requests.last()).contains("=== /workspace/a.py ==="))
    }

    @Test
    fun `a headless session still reviews inline so the process does not exit before the verdict`() = run {
        enable()
        val model = ScriptedModel(
            reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
            reply(TextPart(bad)),
            reply(TextPart("fixed")),
        )
        val rt = session(model, headless = true)
        rt.say("write", settle = false)
        // 回合一结束，审查与随后的跟进都已经在这一回合里做完了
        assertEquals(0, rt.security.pendingReviews())
        assertEquals(4, model.requests.size)
        assertTrue(rt.state.value.turn == null)
    }

    @Test
    fun `closing the session stops a review that is still running`() = run {
        enable()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val mains = java.util.concurrent.atomic.AtomicInteger()
        val model = RoutingModel { _, kind ->
            if (kind == "review") { req -> flow { gate.await(); emitAll(reply(TextPart(bad))(req)) } }
            else if (mains.incrementAndGet() == 1) reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse)
            else reply(TextPart("done"))
        }
        val hub = hubFor(model)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("write", settle = false)
        assertEquals(1, rt.security.pendingReviews())
        hub.close(rt.sessionId)
        assertEquals(0, rt.security.pendingReviews())
        // 放开闸门也不会有任何后续：没有新的主模型请求
        gate.complete(Unit)
        kotlinx.coroutines.delay(100)
        assertEquals(2, model.requests.count { it.system.none { s -> "hook evaluator" in s } && !reviewText(it).contains("independent security reviewer") })
    }

    // ---- 提交 / 推送审查在后台跑（交互会话） ----

    private fun isCommitReview(req: ModelRequest) = reviewText(req).contains("A coding agent just ran")

    private fun ModelRequest.isMain() = system.none { "hook evaluator" in it } && !reviewText(this).contains("independent security reviewer")

    private fun SessionRuntime.hookInputs() =
        state.value.history.filterIsInstance<HistoryEntry.User>().filter { it.origin == dev.min.agent.spi.InputOrigin.Hook("security-guidance") }

    @Test
    fun `an interactive commit review runs in the background and its finding comes back as a hook input`() = run {
        enable()
        exec.script = { cmd, _ -> if (cmd.script.startsWith("git show")) ExecResult.Exited(0, "diff --git a/a.py b/a.py\n+eval(x)", false, 1) else ExecResult.Exited(0, "", false, 1) }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val mains = java.util.concurrent.atomic.AtomicInteger()
        val model = RoutingModel { _, kind ->
            if (kind == "review") { req -> flow { gate.await(); emitAll(reply(TextPart(bad))(req)) } }
            else when (mains.incrementAndGet()) {
                1 -> reply(commitCall(), stop = StopReason.ToolUse)
                2 -> reply(TextPart("done"))
                else -> reply(TextPart("fixed"))
            }
        }
        val rt = session(model)
        rt.say("commit it", settle = false)
        // Bash 的结果立刻交回、回合照常收尾，审查者还卡着
        assertEquals(2, model.requests.count { it.isMain() })
        assertEquals(1, rt.security.pendingReviews())
        assertTrue(model.requests[1].reminders().none { "a.py:1" in it })
        assertTrue(rt.hookInputs().isEmpty())

        gate.complete(Unit)
        rt.settled()
        val review = reviewText(model.requests.first { it.system.none { s -> "hook evaluator" in s } && isCommitReview(it) })
        assertTrue(review.contains("git commit -m change") && review.contains("+eval(x)"))
        // 发现是 hook 来源的输入（把空闲的会话叫起来跟进），不是挂在工具结果后的提醒
        assertEquals(1, rt.hookInputs().size)
        assertTrue(reviewText(model.requests.last()).contains("a.py:1 eval of user input"))
        assertTrue(model.requests.flatMap { it.reminders() }.none { "a.py:1" in it })
    }

    @Test
    fun `a finding that arrives while a turn is running is an interjection into that turn, not a new turn`() = run {
        enable()
        exec.script = { cmd, _ -> if (cmd.script.startsWith("git show")) ExecResult.Exited(0, "diff", false, 1) else ExecResult.Exited(0, "", false, 1) }
        val holdMain = kotlinx.coroutines.CompletableDeferred<Unit>()
        val mains = java.util.concurrent.atomic.AtomicInteger()
        val model = RoutingModel { _, kind ->
            if (kind == "review") reply(TextPart(bad))
            else when (mains.incrementAndGet()) {
                1 -> reply(commitCall(), stop = StopReason.ToolUse)
                // 第二个请求卡住，直到审查出了结论、发现已经排进收件箱：发现落在回合进行中
                2 -> { r -> flow { holdMain.await(); emitAll(reply(TextPart("done"))(r)) } }
                else -> reply(TextPart("fixed"))
            }
        }
        val rt = session(model)
        rt.send(ClientMsgId("u${++counter}"), listOf(TextPart("commit it")))
        val firstTurn = rt.state.first { it.turn != null }.turn!!.turnId
        while (model.requests.count { it.isMain() } < 2 || rt.security.pendingReviews() > 0) kotlinx.coroutines.delay(5)
        assertTrue(rt.state.value.turn != null)
        holdMain.complete(Unit)
        rt.settled()
        assertEquals(1, rt.hookInputs().size)
        assertEquals(3, model.requests.count { it.isMain() })
        // 同一个回合里接着处理：没有另开新回合
        assertEquals(firstTurn, rt.state.value.lastTurn?.turnId)
    }

    @Test
    fun `a background commit review does not loosen the hourly cap`() = run {
        enable()
        exec.script = { cmd, _ -> if (cmd.script.startsWith("git show")) ExecResult.Exited(0, "diff", false, 1) else ExecResult.Exited(0, "", false, 1) }
        val mains = java.util.concurrent.atomic.AtomicInteger()
        val model = RoutingModel { _, kind ->
            if (kind == "review") reply(TextPart(ok))
            else if (mains.incrementAndGet() <= 22) reply(commitCall(), stop = StopReason.ToolUse)
            else reply(TextPart("done"))
        }
        val rt = session(model)
        rt.say("commit a lot")
        // 22 次提交里只有前 20 次起了审查者
        assertEquals(20, model.requests.count { isCommitReview(it) })
        assertEquals(23, model.requests.count { it.isMain() })
    }

    @Test
    fun `a background commit review does not repeat a finding already reported`() = run {
        enable()
        exec.script = { cmd, _ -> if (cmd.script.startsWith("git show")) ExecResult.Exited(0, "diff", false, 1) else ExecResult.Exited(0, "", false, 1) }
        val mains = java.util.concurrent.atomic.AtomicInteger()
        val model = RoutingModel { _, kind ->
            if (kind == "review") reply(TextPart(bad))
            else when (mains.incrementAndGet()) {
                1 -> reply(writeCall("/workspace/a.py", "print(1)"), stop = StopReason.ToolUse)
                2 -> reply(TextPart("done"))
                3 -> reply(commitCall(), stop = StopReason.ToolUse)
                else -> reply(TextPart("committed"))
            }
        }
        val rt = session(model)
        rt.say("write and commit")
        // 回合末审查报过同一条：提交审查（也报了同一条）不再作为输入送来，只有回合末那一次
        assertEquals(1, model.requests.count { isCommitReview(it) })
        assertEquals(1, rt.hookInputs().size)
        assertEquals(1, rt.state.value.securityReported.size)
    }

    @Test
    fun `a push reviewed in the background still takes its remote snapshots before and after the tool`() = run {
        enable()
        pushRepo(
            "$c0 refs/remotes/origin/main\n", "$c2 refs/remotes/origin/main\n",
            "COUNT=2\ncommit $c0\nfirst\n\ndiff --git a/a b/a\n+FIRSTCOMMIT\ncommit $c2\nsecond\n\ndiff --git a/b b/b\n+SECONDCOMMIT\n",
        )
        val mains = java.util.concurrent.atomic.AtomicInteger()
        val model = RoutingModel { _, kind ->
            if (kind == "review") reply(TextPart(ok))
            else if (mains.incrementAndGet() == 1) reply(commitCall("git push origin main"), stop = StopReason.ToolUse)
            else reply(TextPart("done"))
        }
        session(model).say("push")
        val review = reviewText(model.requests.first { isCommitReview(it) })
        assertTrue(review.contains("FIRSTCOMMIT") && review.contains("SECONDCOMMIT") && review.contains("2 commits"))
        assertEquals(listOf("refs", "bash", "refs", "log"), order.toList())
    }
}
