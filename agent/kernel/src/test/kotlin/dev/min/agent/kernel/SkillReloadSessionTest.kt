package dev.min.agent.kernel

import dev.min.agent.ext.config.SettingsInputs
import dev.min.agent.ext.skills.BundledSkills
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
import dev.min.agent.spi.SkillKind
import dev.min.agent.spi.SkillsRescanned
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
 * 用户 / 项目 / `--add-dir` 层 skills 的热更新（skills 文档「Edit a skill during a session」）与 `/reload-skills`：
 * 每次开新回合前重扫、有差异才写 `skills_rescanned`，冻结的系统提示不动；手动重扫还含命令目录。真日志 + 内存文件系统。
 */
class SkillReloadSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = ReloadMapFs()
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

    private fun deps(model: ModelClient, customizations: Customizations, addDirs: List<GuestPath>): KernelDeps {
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
            store, LogHeader(LOG_FORMAT_VERSION, SessionId("reload-$n"), 0),
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

    private suspend fun rescanEvents(rt: SessionRuntime): Int =
        store.peek(rt.sessionId)!!.read().toList().count { it.event is SkillsRescanned }

    private fun SessionRuntime.entry(name: String) = state.value.skillEntries().single { it.name == name }

    // ---- 用户层 / 项目层：新回合前热发现 ----

    @Test
    fun `a user skill added mid-session is announced before the next turn and the system prompt stays byte-identical`() = run {
        fs.files["/root/.claude/skills/old/SKILL.md"] = skillFile("an old one")
        val model = ScriptedModel(reply(TextPart("1")), reply(TextPart("2")))
        val rt = create(model)
        rt.say("first")
        fs.files["/root/.claude/skills/fresh/SKILL.md"] = skillFile("brand new", "fresh body")
        rt.say("second")
        assertTrue(model.requests[1].reminders().toString(), model.requests[1].reminders().any { "- fresh: brand new" in it })
        assertEquals(model.requests[0].system, model.requests[1].system)
        val loaded = invoke(rt, "fresh")
        assertTrue("got $loaded", loaded is SkillInvocation.Loaded && loaded.content.endsWith("fresh body"))
        // 冻结清单里的老 skill 不受影响
        assertTrue(invoke(rt, "old") is SkillInvocation.Loaded)
    }

    @Test
    fun `a project skill edited then removed mid-session follows the disk, old and new`() = run {
        fs.files["/workspace/.claude/skills/tool/SKILL.md"] = skillFile("v1", "b1")
        val model = ScriptedModel(reply(TextPart("1")), reply(TextPart("2")), reply(TextPart("3")))
        val rt = create(model)
        rt.say("first")
        fs.files["/workspace/.claude/skills/tool/SKILL.md"] = skillFile("v2", "b2")
        rt.say("second")
        assertEquals("v2", rt.entry("tool").description)
        assertTrue(model.requests[1].reminders().any { "- tool: v2" in it })
        fs.files.remove("/workspace/.claude/skills/tool/SKILL.md")
        rt.say("third")
        assertTrue(model.requests[2].reminders().any { "no longer available: tool" in it })
        assertTrue(invoke(rt, "tool") is SkillInvocation.Rejected)
        // 重放一致
        val reopened = SessionRuntime.open(store, rt.sessionId, rt.deps, newScope())!!
        assertEquals(rt.state.value.modelMessages(), reopened.state.value.modelMessages())
        assertEquals(rt.state.value.skillEntries().map { it.name }, reopened.state.value.skillEntries().map { it.name })
    }

    @Test
    fun `a body-only edit needs no rescan event and the next invocation reads the new text`() = run {
        fs.files["/workspace/.claude/skills/tool/SKILL.md"] = skillFile("same", "b1")
        val model = ScriptedModel(reply(TextPart("1")), reply(TextPart("2")))
        val rt = create(model)
        rt.say("first")
        fs.files["/workspace/.claude/skills/tool/SKILL.md"] = skillFile("same", "b2")
        rt.say("second")
        assertEquals(0, rescanEvents(rt))
        val loaded = invoke(rt, "tool")
        assertTrue("got $loaded", loaded is SkillInvocation.Loaded && loaded.content.endsWith("b2"))
    }

    @Test
    fun `an unchanged disk writes nothing and a nested skill is not mistaken for a removed one`() = run {
        fs.files["/workspace/apps/web/.claude/skills/deploy/SKILL.md"] = skillFile("web deploy")
        fs.files["/workspace/apps/web/src/a.ts"] = "x"
        val model = ScriptedModel(reply(readCall("/workspace/apps/web/src/a.ts"), stop = StopReason.ToolUse), reply(TextPart("done")), reply(TextPart("3")))
        val rt = create(model)
        rt.say("first")
        assertTrue(rt.state.value.skillEntries().any { it.name == "deploy" })
        rt.say("second")
        assertEquals(0, rescanEvents(rt))
        assertTrue(rt.state.value.skillEntries().any { it.name == "deploy" })
    }

    @Test
    fun `a nested skill is in the current list only after a file in its directory was touched`() = run {
        fs.files["/workspace/apps/web/.claude/skills/deploy/SKILL.md"] = skillFile("web deploy")
        fs.files["/workspace/apps/web/src/a.ts"] = "x"
        val model = ScriptedModel(reply(readCall("/workspace/apps/web/src/a.ts"), stop = StopReason.ToolUse), reply(TextPart("done")))
        val rt = create(model)
        // 原文：「Until then they don't appear in the / menu and you can't invoke them by name」
        assertTrue(rt.state.value.currentSkillEntries().none { it.name == "deploy" })
        assertTrue(rt.slashDefs("/deploy").skills.isEmpty())
        rt.say("look at the web app")
        assertTrue(rt.state.value.currentSkillEntries().any { it.name == "deploy" })
        assertEquals(listOf("deploy"), rt.slashDefs("/deploy now").skills.map { it.name })
    }

    @Test
    fun `bare and safe do not watch the user and project skills`() = run {
        for (mode in listOf(Customizations.Bare, Customizations.Safe)) {
            val model = ScriptedModel(reply(TextPart("1")), reply(TextPart("2")))
            val rt = create(model, mode)
            rt.say("first")
            fs.files["/root/.claude/skills/fresh-$mode/SKILL.md"] = skillFile("brand new")
            rt.say("second")
            assertTrue(rt.state.value.skillEntries().none { it.name == "fresh-$mode" })
        }
    }

    @Test
    fun `a project skill that was shadowing a bundled one gives it back when it is deleted`() = run {
        val model = ScriptedModel(reply(TextPart("1")), reply(TextPart("2")), reply(TextPart("3")))
        val rt = create(model)
        rt.say("first")
        assertTrue(BundledSkills.isBundled(rt.entry("loop").path.value))
        fs.files["/workspace/.claude/skills/loop/SKILL.md"] = skillFile("my loop")
        rt.say("second")
        assertEquals("my loop", rt.entry("loop").description)
        fs.files.remove("/workspace/.claude/skills/loop/SKILL.md")
        rt.say("third")
        assertTrue(BundledSkills.isBundled(rt.entry("loop").path.value))
    }

    @Test
    fun `a sub-agent inherits the hot-discovered skills and does not get back one that was deleted`() = run {
        fs.files["/workspace/.claude/skills/gone/SKILL.md"] = skillFile("short lived")
        val model = ScriptedModel(reply(TextPart("1")), reply(TextPart("child done")))
        val hub = SessionHub(store, deps(model, Customizations.Full, emptyList()), newScope(), HubLimits())
        val parent = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        fs.files.remove("/workspace/.claude/skills/gone/SKILL.md")
        fs.files["/workspace/.claude/skills/fresh/SKILL.md"] = skillFile("brand new")
        parent.say("first")
        assertTrue(parent.state.value.skillEntries().none { it.name == "gone" })
        val res = hub.spawn(parent, TurnId("t1"), CallId("c1"),
            dev.min.agent.spi.DelegateRequest(description = "d", prompt = "p", agentType = "Explore", background = false))
        val child = hub.get((res as dev.min.agent.spi.DelegateResult.Finished).child)!!
        assertTrue(child.state.value.skillEntries().any { it.name == "fresh" })
        assertTrue("删掉的不能在子 agent 里复活", child.state.value.skillEntries().none { it.name == "gone" })
    }

    // ---- /reload-skills：手动重扫，含命令 ----

    @Test
    fun `reloadSkills picks up new skills and commands now and reports the counts`() = run {
        fs.files["/root/.claude/skills/old/SKILL.md"] = skillFile("an old one")
        val rt = create(ScriptedModel(reply(TextPart("1"))))
        val before = rt.state.value.skillEntries().size
        fs.files["/root/.claude/skills/fresh/SKILL.md"] = skillFile("brand new")
        fs.files["/workspace/.claude/commands/ship.md"] = skillFile("ship it")
        fs.files.remove("/root/.claude/skills/old/SKILL.md")
        val r = rt.reloadSkills()
        assertTrue(r.available)
        assertEquals(listOf("fresh", "ship"), r.added.sorted())
        assertEquals(listOf("old"), r.removed)
        assertEquals(before + 1, r.total)
        assertEquals(r.total, rt.state.value.skillEntries().size)
        assertTrue(invoke(rt, "fresh") is SkillInvocation.Loaded)
        assertEquals(SkillKind.Command, rt.entry("ship").kind)
        // 再来一次：没有变化，不再写事件
        val events = rescanEvents(rt)
        val again = rt.reloadSkills()
        assertTrue(again.added.isEmpty() && again.removed.isEmpty() && again.changed.isEmpty())
        assertEquals(events, rescanEvents(rt))
    }

    @Test
    fun `the automatic rescan before a turn leaves commands alone, only reloadSkills reads them`() = run {
        val model = ScriptedModel(reply(TextPart("1")), reply(TextPart("2")))
        val rt = create(model)
        rt.say("first")
        fs.files["/workspace/.claude/commands/ship.md"] = skillFile("ship it")
        rt.say("second")
        assertTrue(rt.state.value.skillEntries().none { it.name == "ship" })
        rt.reloadSkills()
        assertTrue(rt.state.value.skillEntries().any { it.name == "ship" })
    }

    @Test
    fun `reloadSkills tells the model on its next message and does not touch the frozen prompt`() = run {
        val model = ScriptedModel(reply(TextPart("1")), reply(TextPart("2")))
        val rt = create(model)
        rt.say("first")
        fs.files["/root/.claude/skills/fresh/SKILL.md"] = skillFile("brand new")
        rt.reloadSkills()
        rt.say("second")
        assertTrue(model.requests[1].reminders().any { "- fresh: brand new" in it })
        assertEquals(model.requests[0].system, model.requests[1].system)
        // 手动重扫已经登记了，回合前的自动重扫不再重复写
        assertEquals(1, rescanEvents(rt))
    }

    @Test
    fun `reloadSkills in safe mode does nothing and says so, in bare mode it reads only the add-dir skills`() = run {
        fs.files["/extra/lib/.claude/skills/tool/SKILL.md"] = skillFile("a tool")
        val safe = create(ScriptedModel(), Customizations.Safe, listOf(GuestPath("/extra/lib")))
        fs.files["/extra/lib/.claude/skills/fresh/SKILL.md"] = skillFile("brand new")
        assertFalse(safe.reloadSkills().available)
        assertTrue(safe.state.value.skillEntries().isEmpty())

        val bare = create(ScriptedModel(), Customizations.Bare, listOf(GuestPath("/extra/lib")))
        fs.files["/extra/lib/.claude/skills/later/SKILL.md"] = skillFile("added after start")
        fs.files["/root/.claude/skills/user/SKILL.md"] = skillFile("user one")
        val r = bare.reloadSkills()
        assertTrue(r.available)
        assertEquals(listOf("later"), r.added)
        assertTrue(bare.state.value.skillEntries().none { it.name == "user" })
    }
}

/** 内存文件系统：目录隐式存在 */
private class ReloadMapFs : WorkspaceFs {
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
