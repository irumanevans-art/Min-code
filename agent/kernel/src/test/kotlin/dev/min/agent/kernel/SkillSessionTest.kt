package dev.min.agent.kernel

import dev.min.agent.context.Environment
import dev.min.agent.context.SystemPrompt
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SkillKind
import dev.min.agent.spi.SkillsLoaded
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.*
import dev.min.agent.tools.SkillTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * skills 在会话里的样子：创建时快照进日志、系统提示只从快照拼、重新打开不重读、Skill 工具按快照找 skill 并渲染正文、
 * 子 agent 继承父会话的快照。真日志 + 内存文件系统。
 */
class SkillSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = SkillMapFs()
    private val header = LogHeader(LOG_FORMAT_VERSION, SessionId("s1"), createdAtMs = 0)
    private val created = SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
    private var counter = 0
    private fun msg() = ClientMsgId("u${++counter}")
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }
    private fun text(t: String) = listOf(TextPart(t))

    private fun skillFile(description: String, body: String, extra: String = "") = "---\ndescription: $description\n$extra---\n$body"

    private fun seedSkills() {
        fs.files["/root/.claude/skills/deploy/SKILL.md"] = skillFile("Ship the app", "Deploy to \$ARGUMENTS.", "when_to_use: when asked to release\n")
        fs.files["/workspace/.claude/skills/fix/SKILL.md"] = skillFile("Fix an issue", "Fix issue \$issue on \$branch.", "arguments: [issue, branch]\n")
        fs.files["/workspace/.claude/skills/manual/SKILL.md"] = skillFile("Only the user runs this", "Dangerous steps.", "disable-model-invocation: true\n")
        fs.files["/workspace/.claude/commands/notes.md"] = "---\ndescription: Take notes\n---\nNote: \$ARGUMENTS"
    }

    private fun deps(model: ModelClient, tools: List<Tool<*>>, home: GuestPath? = GuestPath("/root"), idPrefix: String = ""): KernelDeps {
        val base = testDeps(model, tools)
        return KernelDeps(
            clock = base.clock,
            ids = IdGenerator { prefix -> idPrefix + base.ids.next(prefix) },
            models = base.models,
            tools = tools,
            host = HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts),
            platform = base.platform,
            zone = base.zone,
            retry = base.retry,
            memoryHome = home,
            // 本测试只管记忆文件与 skill 清单：把默认开启的自动记忆关掉（它有自己的 AutoMemorySessionTest）
            env = { name -> if (name == "CLAUDE_CODE_DISABLE_AUTO_MEMORY") "1" else null },
        )
    }

    private suspend fun create(model: ModelClient, tools: List<Tool<*>> = listOf(SkillTool), home: GuestPath? = GuestPath("/root")) =
        SessionRuntime.create(store, header, created, deps(model, tools, home), newScope())

    private suspend fun events(id: String = "s1"): List<SessionEvent> = store.peek(SessionId(id))!!.read().toList().map { it.event }

    private fun call(id: String, vararg pairs: Pair<String, Any?>) = ToolCallPart(CallId(id), "Skill", args(*pairs))

    /** 模型调一次 Skill 工具，然后收尾；返回那次调用的结果 */
    private suspend fun invoke(rt: SessionRuntime, @Suppress("UNUSED_PARAMETER") model: ScriptedModel): ToolResult {
        rt.send(msg(), text("用 skill"))
        rt.awaitIdle()
        return rt.state.value.toolResults.values.last()
    }

    private fun env() = Environment("test", "/workspace", "1970-01-01")

    // ---- 快照 ----

    @Test
    fun `creating a session logs the skill and command snapshot and the prompt lists the callable ones`() = run {
        seedSkills()
        val model = ScriptedModel(reply(TextPart("好")))
        val rt = create(model)

        val loaded = events()[1] as SkillsLoaded
        // 内置 skill（loop / code-review / simplify / batch）排在磁盘 skill 之后、命令之前
        assertEquals(listOf("deploy", "fix", "manual", "loop", "code-review", "simplify", "batch", "notes"), loaded.entries.map { it.name })
        assertEquals(List(7) { SkillKind.Skill } + SkillKind.Command, loaded.entries.map { it.kind })
        assertEquals(listOf("user", "project", "project", "内置", "内置", "内置", "内置", "project"), loaded.entries.map { it.source })
        assertEquals(listOf("issue", "branch"), loaded.entries.single { it.name == "fix" }.arguments)
        assertEquals(loaded, rt.state.value.skills)

        rt.send(msg(), text("在吗"))
        rt.awaitIdle()
        val system = model.requests.single().system
        assertEquals(SystemPrompt.build(TEST_PROFILE, env(), skills = loaded), system)
        assertEquals(3, system.size)
        // 内置 skill 的说明来自随包的 SKILL.md，这里只对名字；batch 禁止模型调用，不列
        val lines = system[2].lines()
        assertEquals("The following skills are available for use with the Skill tool:", lines[0])
        assertEquals("", lines[1])
        assertEquals("- deploy: Ship the app - when asked to release", lines[2])
        assertEquals(listOf("deploy", "fix", "loop", "code-review", "simplify", "notes"), lines.drop(2).map { it.removePrefix("- ").substringBefore(":") })
        // 禁止模型调用的不列
        assertFalse(system.any { "manual" in it })
    }

    @Test
    fun `settings can raise or lower the listing caps and bad values are ignored with a note`() = run {
        seedSkills()
        fs.files["/workspace/.claude/settings.json"] = """{"skillListingBudgetFraction": 0.5, "skillListingMaxDescChars": 4}"""
        val loaded = create(ScriptedModel()).state.value.skills!!
        assertEquals(0.5, loaded.listingBudgetFraction!!, 0.0)
        assertEquals(4, loaded.listingMaxDescChars)

        fs.files["/workspace/.claude/settings.json"] = """{"skillListingBudgetFraction": 7, "skillListingMaxDescChars": "x"}"""
        val bad = SessionRuntime.create(
            store, LogHeader(LOG_FORMAT_VERSION, SessionId("s2"), 0), created, deps(ScriptedModel(), listOf(SkillTool)), newScope(),
        ).state.value.skills!!
        assertEquals(null, bad.listingBudgetFraction)
        assertEquals(null, bad.listingMaxDescChars)
        assertEquals(2, bad.notes.count { it.startsWith("忽略") })
    }

    @Test
    fun `without a Skill tool the listing is not shown`() = run {
        seedSkills()
        val model = ScriptedModel(reply(TextPart("好")))
        val rt = create(model, tools = emptyList())
        assertNotNull(rt.state.value.skills)
        rt.send(msg(), text("在吗"))
        rt.awaitIdle()
        assertEquals(2, model.requests.single().system.size)
    }

    @Test
    fun `no skills and no home both mean no event`() = run {
        fs.files["/workspace/README.md"] = "x"
        create(ScriptedModel())
        // 只有内置 skill 时仍然写清单事件（它们进清单）
        assertTrue(events().filterIsInstance<SkillsLoaded>().single().entries.all { it.source == "内置" })

        seedSkills()
        SessionRuntime.create(
            store, LogHeader(LOG_FORMAT_VERSION, SessionId("s9"), 0), created, deps(ScriptedModel(), listOf(SkillTool), home = null), newScope(),
        )
        assertFalse(events("s9").any { it is SkillsLoaded })
    }

    @Test
    fun `the snapshot is frozen when the disk changes and when the session is reopened`() = run {
        seedSkills()
        val before = ScriptedModel(reply(TextPart("一")), reply(TextPart("二")))
        val rt = create(before)
        rt.send(msg(), text("第一句"))
        rt.awaitIdle()
        rt.send(msg(), text("第二句"))
        rt.awaitIdle()
        // 会话在跑时磁盘变了：加一个、改一个说明
        fs.files["/workspace/.claude/skills/newcomer/SKILL.md"] = skillFile("Brand new", "x")
        fs.files["/workspace/.claude/skills/fix/SKILL.md"] = skillFile("CHANGED", "y")
        rt.send(msg(), text("第三句"))
        rt.awaitIdle()
        assertEquals("会话内三次请求系统提示相同", 1, before.requests.map { it.system }.distinct().size)
        assertFalse(before.requests.last().system.any { "newcomer" in it || "CHANGED" in it })

        val after = ScriptedModel(reply(TextPart("四")))
        val reopened = SessionRuntime.open(store, SessionId("s1"), deps(after, listOf(SkillTool), idPrefix = "r"), newScope())!!
        reopened.send(msg(), text("第四句"))
        reopened.awaitIdle()
        assertEquals(before.requests.first().system, after.requests.single().system)
        assertEquals(1, events().count { it is SkillsLoaded })
        val prints = events().filterIsInstance<ModelRequestStarted>().map { it.prefixFingerprint }
        assertEquals(1, prints.toSet().size)
    }

    // ---- Skill 工具 ----

    @Test
    fun `the Skill tool returns the rendered body with named arguments and a base directory`() = run {
        seedSkills()
        val model = ScriptedModel(
            reply(call("c1", "skill" to "fix", "args" to "42 main"), stop = StopReason.ToolUse),
            reply(TextPart("好")),
        )
        val rt = create(model)
        val result = invoke(rt, model)
        assertFalse(result.isError)
        assertEquals(
            "Base directory for this skill: /workspace/.claude/skills/fix\n\nFix issue 42 on main.",
            result.content.filterIsInstance<TextPart>().single().text,
        )
        // 结果在日志里：请求能从日志重建
        assertEquals(result, rt.state.value.toolResults.getValue(CallId("c1")))
    }

    @Test
    fun `a command is callable by the model too and gets no base directory`() = run {
        seedSkills()
        val model = ScriptedModel(reply(call("c1", "skill" to "/notes", "args" to "buy milk"), stop = StopReason.ToolUse), reply(TextPart("好")))
        val rt = create(model)
        val result = invoke(rt, model)
        assertEquals("Note: buy milk", result.content.filterIsInstance<TextPart>().single().text)
    }

    @Test
    fun `no args and no placeholders returns the plain body`() = run {
        seedSkills()
        val model = ScriptedModel(reply(call("c1", "skill" to "deploy"), stop = StopReason.ToolUse), reply(TextPart("好")))
        val rt = create(model)
        val body = (invoke(rt, model).content.single() as TextPart).text
        // 模型没给 args：占位符原样留着，不替换也不追加
        assertEquals("Base directory for this skill: /root/.claude/skills/deploy\n\nDeploy to \$ARGUMENTS.", body)
    }

    @Test
    fun `a skill hidden from the model is refused with an explanation`() = run {
        seedSkills()
        val model = ScriptedModel(reply(call("c1", "skill" to "manual"), stop = StopReason.ToolUse), reply(TextPart("好")))
        val rt = create(model)
        val result = invoke(rt, model)
        assertTrue(result.isError)
        val message = (result.content.single() as TextPart).text
        assertTrue(message, "disable-model-invocation" in message && "/manual" in message)
        assertFalse("Dangerous steps." in message)
    }

    @Test
    fun `an unknown skill lists the callable names and a skill added after the snapshot is unknown`() = run {
        seedSkills()
        val model = ScriptedModel(reply(call("c1", "skill" to "newcomer"), stop = StopReason.ToolUse), reply(TextPart("好")))
        val rt = create(model)
        fs.files["/workspace/.claude/skills/newcomer/SKILL.md"] = skillFile("Brand new", "x")
        val result = invoke(rt, model)
        assertTrue(result.isError)
        val message = (result.content.single() as TextPart).text
        assertTrue(message, "没有名为「newcomer」" in message && "deploy、fix、loop、code-review、simplify、notes" in message)
        assertFalse("manual" in message)
    }

    @Test
    fun `a skill file deleted after the snapshot is reported as unreadable`() = run {
        seedSkills()
        val model = ScriptedModel(reply(call("c1", "skill" to "fix"), stop = StopReason.ToolUse), reply(TextPart("好")))
        val rt = create(model)
        fs.files.remove("/workspace/.claude/skills/fix/SKILL.md")
        val result = invoke(rt, model)
        assertTrue(result.isError)
        assertTrue((result.content.single() as TextPart).text.contains("读不了"))
    }

    @Test
    fun `a long skill body goes through the result budget`() = run {
        fs.files["/workspace/.claude/skills/big/SKILL.md"] = skillFile("Big one", "L".repeat(50_000))
        val model = ScriptedModel(reply(call("c1", "skill" to "big"), stop = StopReason.ToolUse), reply(TextPart("好")))
        val rt = create(model)
        val result = invoke(rt, model)
        assertNotNull("超预算要落成附件", result.artifact)
        assertTrue(result.fullChars!! > 50_000)
        assertTrue((result.content.single() as TextPart).text.length < 32_000)
    }

    // ---- 子 agent ----

    private class Routing(private val route: (ModelRequest) -> Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    private val agentTool = object : Tool<JsonObject> {
        override val name = "Agent"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "测试用 Agent"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, name)
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            val r = ctx.agents.delegate(
                DelegateRequest(
                    "任务", (input["prompt"] as JsonPrimitive).content, (input["subagent_type"] as? JsonPrimitive)?.content, null,
                    background = false, name = null,
                ),
            )
            val text = when (r) {
                is DelegateResult.Finished -> r.report
                is DelegateResult.Started -> "started"
                is DelegateResult.Rejected -> r.messageForModel
            }
            emit(ToolProgress.Done(ToolResult(listOf(TextPart(text)))))
        }
    }

    private fun ModelRequest.lastText() = messages.last().parts.joinToString("\n") { p ->
        when (p) {
            is TextPart -> p.text
            is dev.min.agent.protocol.ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    private fun delegating(type: String): Routing = Routing { req ->
        val last = req.lastText()
        when {
            // 子 agent 的第一个请求：分身的最后一条是分身须知，新开的最后一条就是委派的任务
            "You are a fork" in last || last == "do the task" -> reply(call("k1", "skill" to "deploy", "args" to "prod"), stop = StopReason.ToolUse)
            last == "开始" -> reply(
                ToolCallPart(CallId("a1"), "Agent", args("subagent_type" to type, "prompt" to "do the task")),
                stop = StopReason.ToolUse,
            )
            else -> reply(TextPart("完成"))
        }
    }

    @Test
    fun `a fork and a fresh subagent inherit the parent snapshot without reading the disk again`() = run {
        for (type in listOf("fork", "general-purpose")) {
            seedSkills()
            val model = delegating(type)
            val hub = SessionHub(store, deps(model, listOf(SkillTool, agentTool), idPrefix = type), newScope(), HubLimits())
            val parent = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
            // 父会话建好后磁盘上的 skill 变了：子 agent 仍应沿用父会话冻结的那份
            fs.files["/root/.claude/skills/deploy/SKILL.md"] = skillFile("Ship the app", "NEW BODY \$ARGUMENTS", "when_to_use: when asked to release\n")
            fs.files["/workspace/.claude/skills/extra/SKILL.md"] = skillFile("Added later", "x")
            val readsBefore = fs.reads.size
            parent.send(msg(), text("开始"))
            parent.state.first { it.turn == null && it.children.isNotEmpty() && it.lastTurn != null }
            val childId = parent.state.value.children.keys.single()
            val child = hub.get(childId)!!
            child.state.first { it.lastTurn != null }

            assertEquals(type, parent.state.value.skills, child.state.value.skills)
            assertEquals(1, events(childId.value).count { it is SkillsLoaded })
            val childPrompt = model.requests.first { it.lastText().let { t -> "You are a fork" in t || "do the task" in t } }.system
            assertTrue("$type 子 agent 的系统提示里有清单", childPrompt.any { "- deploy: Ship the app" in it })
            assertFalse(type, childPrompt.any { "extra" in it })
            if (type == "fork") assertEquals("分身前缀与父会话逐字相同", model.requests.first().system, childPrompt.take(model.requests.first().system.size))
            // 子 agent 调 Skill：用的是继承来的清单，正文读的是此刻的文件
            val result = child.state.value.toolResults.getValue(CallId("k1"))
            assertTrue(type, (result.content.single() as TextPart).text.contains("NEW BODY prod"))
            assertTrue("子会话创建不重读清单目录：$type", fs.reads.drop(readsBefore).none { it.endsWith("/extra/SKILL.md") })
            fs.files.clear()
            fs.reads.clear()
        }
    }
}

/** 只够读配置用的内存文件系统：目录隐式存在，没有链接；工作区是 /workspace */
private class SkillMapFs : WorkspaceFs {
    val files: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    val reads = java.util.Collections.synchronizedList(mutableListOf<String>())

    override suspend fun stat(path: GuestPath): FileStat? = when {
        path.value in files -> FileStat(FileKind.File, files.getValue(path.value).length.toLong(), 0)
        path.value == "/" || files.keys.any { it.startsWith(path.value + "/") } -> FileStat(FileKind.Directory, 0, 0)
        else -> null
    }

    override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray {
        reads += path.value
        val bytes = files.getValue(path.value).toByteArray(Charsets.UTF_8)
        return bytes.copyOf(minOf(bytes.size, limit))
    }

    override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) = error("加载配置不该写文件")

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
