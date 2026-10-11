package dev.min.agent.kernel

import dev.min.agent.ext.config.SettingsInputs
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SkillInvocation
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import dev.min.agent.tools.SkillTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
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
import java.util.concurrent.ConcurrentHashMap

/**
 * skills 收尾几条：嵌套目录里新发现的 skill 以 system-reminder 追加（不动冻结的系统提示）、`--bare` 不给清单但仍可按名字调、
 * `--add-dir` 目录里的 skill 中途变化在新回合前热发现。真日志 + 内存文件系统。
 */
class SkillFinishSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = FinMapFs()
    private var counter = 0
    private var sessions = 0
    private fun msg() = ClientMsgId("u${++counter}")
    private fun text(t: String) = listOf(TextPart(t))
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }
    private fun skillFile(description: String, body: String = "body") = "---\ndescription: $description\n---\n$body"

    /** 读文件的替身工具：只为让内核看到「碰到了这个路径」 */
    private val read = object : Tool<JsonObject> {
        override val name = "Read"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true)
        override fun description(profile: HarnessProfile) = "读文件"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext): PermissionAsk {
            val path = (input["file_path"] as? JsonPrimitive)?.content ?: "/dev/null"
            return PermissionAsk(name, "读文件", paths = listOf(PathAccess(GuestPath(path), AccessKind.Read, PathScope.Workspace)))
        }
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("read ok")))))
        }
    }

    private fun deps(model: ModelClient, customizations: Customizations = Customizations.Full, addDirs: List<GuestPath> = emptyList()): KernelDeps {
        val base = testDeps(model, listOf(read, SkillTool))
        return KernelDeps(
            clock = base.clock, ids = IdGenerator { p -> base.ids.next(p) }, models = base.models,
            tools = listOf(read, SkillTool),
            host = HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts),
            platform = base.platform, zone = base.zone, retry = base.retry,
            memoryHome = GuestPath("/root"),
            env = { name -> if (name == "CLAUDE_CODE_DISABLE_AUTO_MEMORY") "1" else null },
            customizations = customizations,
            settingsInputs = SettingsInputs(addDirs = addDirs),
        )
    }

    private suspend fun create(model: ModelClient, customizations: Customizations = Customizations.Full, addDirs: List<GuestPath> = emptyList()): SessionRuntime {
        val n = ++sessions
        return SessionRuntime.create(
            store, LogHeader(LOG_FORMAT_VERSION, SessionId("fin-$n"), 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default), deps(model, customizations, addDirs), newScope(),
        )
    }

    private suspend fun SessionRuntime.say(t: String) {
        send(msg(), text(t))
        awaitIdle()
    }

    private fun readCall(path: String) = ToolCallPart(CallId("c${++counter}"), "Read", args("file_path" to path))

    private fun ModelRequest.reminders(): List<String> =
        messages.flatMap { m -> m.parts.filterIsInstance<TextPart>().map { it.text } }.filter { it.startsWith("<system-reminder>") }

    private suspend fun invoke(rt: SessionRuntime, name: String): SkillInvocation =
        KernelSkillPort(rt, fs, TurnId("t"), CallId("skill-call-${++counter}")).invoke(name, null)

    private suspend fun eventCount(rt: SessionRuntime): Int = store.peek(rt.sessionId)!!.read().toList().size

    // ---- 嵌套目录：以系统提醒追加，系统提示前缀不动 ----

    @Test
    fun `a nested skill is announced as a system reminder after the batch and the system prompt stays byte-identical`() = run {
        fs.files["/workspace/.claude/skills/root/SKILL.md"] = skillFile("root skill")
        fs.files["/workspace/apps/web/.claude/skills/deploy/SKILL.md"] = skillFile("web deploy")
        fs.files["/workspace/apps/web/src/a.ts"] = "x"
        val model = ScriptedModel(
            reply(readCall("/workspace/apps/web/src/a.ts"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
        )
        val rt = create(model)
        rt.say("look at the web app")
        val (first, second) = model.requests
        // 冻结的系统提示一字不动：新 skill 没有挤进它
        assertEquals(first.system, second.system)
        assertFalse(second.system.any { "deploy" in it })
        assertTrue(first.reminders().none { "web deploy" in it })
        val reminders = second.reminders()
        assertEquals(reminders.toString(), 1, reminders.count { "web deploy" in it })
        assertTrue(reminders.single { "web deploy" in it }.contains("- deploy: web deploy"))
        // 重放一致：重开会话，同一段日志渲染出同一份对话
        val reopened = SessionRuntime.open(store, rt.sessionId, rt.deps, newScope())!!
        assertEquals(rt.state.value.modelMessages(), reopened.state.value.modelMessages())
        assertTrue(invoke(reopened, "deploy") is SkillInvocation.Loaded)
    }

    @Test
    fun `after compaction the mid-session skills are restated on the summary`() = run {
        fs.files["/workspace/apps/web/.claude/skills/deploy/SKILL.md"] = skillFile("web deploy")
        fs.files["/workspace/apps/web/src/a.ts"] = "x"
        val model = ScriptedModel(reply(readCall("/workspace/apps/web/src/a.ts"), stop = StopReason.ToolUse), reply(TextPart("done")))
        val rt = create(model)
        rt.say("look")
        // 压缩之后摘要取代了带提醒的那条工具结果；用折叠出的投影验证重述
        val s = rt.state.value
        val compacted = s.copy(compaction = CompactionView("k1", "summary", s.history.last().messageId, null))
        val summary = compacted.modelMessages().first().parts.filterIsInstance<TextPart>().map { it.text }
        assertTrue(summary.toString(), summary.any { it.startsWith("<system-reminder>") && "web deploy" in it })
        assertTrue(compacted.modelMessages(withReminders = false).first().parts.filterIsInstance<TextPart>().none { "web deploy" in it.text })
    }

    // ---- bare：不给清单，但 --add-dir 的 skill 仍可按名字调 ----

    @Test
    fun `bare does not list the skills of an add-dir directory but they stay callable`() = run {
        fs.files["/extra/lib/.claude/skills/tool/SKILL.md"] = skillFile("a tool", "tool body")
        val model = ScriptedModel(reply(TextPart("ok")))
        val rt = create(model, Customizations.Bare, listOf(GuestPath("/extra/lib")))
        assertEquals(listOf("tool"), rt.state.value.skills!!.entries.map { it.name })
        rt.say("hi")
        val system = model.requests.single().system
        assertTrue(system.toString(), system.none { "following skills" in it || "- tool" in it })
        val loaded = invoke(rt, "tool")
        assertTrue("got $loaded", loaded is SkillInvocation.Loaded && loaded.content.endsWith("tool body"))
    }

    @Test
    fun `full mode does list the add-dir skills in the system prompt`() = run {
        fs.files["/extra/lib/.claude/skills/tool/SKILL.md"] = skillFile("a tool")
        val model = ScriptedModel(reply(TextPart("ok")))
        val rt = create(model, Customizations.Full, listOf(GuestPath("/extra/lib")))
        rt.say("hi")
        assertTrue(model.requests.single().system.any { "- tool: a tool" in it })
    }

    // ---- --add-dir 的热发现 ----

    @Test
    fun `an add-dir skill added, edited and removed mid-session is picked up before the next turn`() = run {
        fs.files["/extra/lib/.claude/skills/tool/SKILL.md"] = skillFile("a tool", "v1")
        val model = ScriptedModel(reply(TextPart("1")), reply(TextPart("2")), reply(TextPart("3")), reply(TextPart("4")))
        val rt = create(model, Customizations.Full, listOf(GuestPath("/extra/lib")))
        rt.say("first")
        assertTrue(model.requests[0].reminders().none { "fresh" in it })

        // 新增 + 改一个说明
        fs.files["/extra/lib/.claude/skills/fresh/SKILL.md"] = skillFile("brand new", "fresh body")
        fs.files["/extra/lib/.claude/skills/tool/SKILL.md"] = skillFile("a tool v2", "v2")
        rt.say("second")
        val announced = model.requests[1].reminders()
        assertTrue(announced.toString(), announced.any { "- fresh: brand new" in it && "- tool: a tool v2" in it })
        // 冻结的系统提示没变
        assertEquals(model.requests[0].system, model.requests[1].system)
        val fresh = invoke(rt, "fresh")
        assertTrue("got $fresh", fresh is SkillInvocation.Loaded && fresh.content.endsWith("fresh body"))
        assertEquals("a tool v2", rt.state.value.skillEntries().single { it.name == "tool" }.description)

        // 没变化：不重复通知，也不多写 skills_rescanned
        val rescansBefore = rescanEvents(rt)
        rt.say("third")
        assertEquals(rescansBefore, rescanEvents(rt))
        assertEquals(1, model.requests[2].reminders().count { "brand new" in it })

        // 删掉一个
        fs.files.remove("/extra/lib/.claude/skills/fresh/SKILL.md")
        rt.say("fourth")
        assertTrue(model.requests[3].reminders().any { "no longer available: fresh" in it })
        assertTrue(invoke(rt, "fresh") is SkillInvocation.Rejected)

        // 重放一致
        val reopened = SessionRuntime.open(store, rt.sessionId, rt.deps, newScope())!!
        assertEquals(rt.state.value.modelMessages(), reopened.state.value.modelMessages())
        assertEquals(rt.state.value.skillEntries().map { it.name }, reopened.state.value.skillEntries().map { it.name })
        assertTrue(eventCount(rt) > 0)
    }

    private suspend fun rescanEvents(rt: SessionRuntime): Int =
        store.peek(rt.sessionId)!!.read().toList().count { it.event is dev.min.agent.spi.SkillsRescanned }

    @Test
    fun `bare does not watch the add-dir skills`() = run {
        fs.files["/extra/lib/.claude/skills/tool/SKILL.md"] = skillFile("a tool")
        val model = ScriptedModel(reply(TextPart("1")), reply(TextPart("2")))
        val rt = create(model, Customizations.Bare, listOf(GuestPath("/extra/lib")))
        rt.say("first")
        fs.files["/extra/lib/.claude/skills/fresh/SKILL.md"] = skillFile("brand new")
        rt.say("second")
        assertTrue(rt.state.value.skillEntries().none { it.name == "fresh" })
    }

    @Test
    fun `a same-named add-dir skill never displaces a project skill on rescan`() = run {
        fs.files["/workspace/.claude/skills/tool/SKILL.md"] = skillFile("project tool", "project body")
        fs.files["/extra/lib/.claude/skills/tool/SKILL.md"] = skillFile("add-dir tool", "add-dir body")
        val model = ScriptedModel(reply(TextPart("1")), reply(TextPart("2")))
        val rt = create(model, Customizations.Full, listOf(GuestPath("/extra/lib")))
        rt.say("first")
        fs.files["/extra/lib/.claude/skills/tool/SKILL.md"] = skillFile("add-dir tool edited", "edited body")
        rt.say("second")
        val tool = rt.state.value.skillEntries().single { it.name == "tool" }
        assertEquals("project tool", tool.description)
        val loaded = invoke(rt, "tool")
        assertTrue("got $loaded", loaded is SkillInvocation.Loaded && loaded.content.endsWith("project body"))
    }
}

/** 内存文件系统：目录隐式存在 */
private class FinMapFs : WorkspaceFs {
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
