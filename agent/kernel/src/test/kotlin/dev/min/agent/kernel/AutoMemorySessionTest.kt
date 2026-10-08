package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.MemoryLoaded
import dev.min.agent.spi.SessionCreated
import dev.min.agent.tools.WriteTool
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolResult
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

/**
 * 自动记忆在会话里的端到端样子（docs/agent/detail/记忆按需加载与自动记忆设计.md §2）：
 * 索引进快照与提示、开关与目录的取舍、以及写记忆目录的权限（Default 问、acceptEdits 放行、plan 只读）。
 */
class AutoMemorySessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = FakeFs()
    private val header = LogHeader(LOG_FORMAT_VERSION, SessionId("s1"), createdAtMs = 0)
    private val memoryDir = "/root/.claude/projects/-workspace/memory"
    private var counter = 0

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun msg() = ClientMsgId("u${++counter}")

    private fun writeCall(id: String, path: String, content: String = "笔记") =
        ToolCallPart(CallId(id), "Write", args("file_path" to path, "content" to content))

    /** [env] 与 [mode] 是本测试真正在动的两样 */
    private fun deps(
        model: ScriptedModel,
        env: Map<String, String> = emptyMap(),
    ): KernelDeps {
        // /root 不是工作区：权限判定按真实宿主的三档来，而不是 FakeFs 默认的「全是工作区」
        fs.scopes["/root"] = dev.min.agent.spi.PathScope.Rootfs
        val base = testDeps(model, listOf(WriteTool))
        return KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = base.tools,
            host = HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts),
            platform = base.platform, zone = base.zone, retry = base.retry,
            memoryHome = GuestPath("/root"),
            env = { env[it] },
        )
    }

    private suspend fun session(mode: PermissionMode = PermissionMode.Default, env: Map<String, String> = emptyMap()) =
        SessionRuntime.create(
            store, header, SessionCreated(GuestPath("/workspace"), TEST_MODEL, mode), deps(scripted, env), scope,
        )

    private lateinit var scripted: ScriptedModel

    private suspend fun events(): List<SessionEvent> = store.open(SessionId("s1"))!!.read().toList().map { it.event }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    @Test
    fun `the index is frozen into the snapshot and the prompt carries the section`() = run {
        fs.put("$memoryDir/MEMORY.md", "user.md: 喜欢简洁\nproject.md: 架构决定\n")
        scripted = ScriptedModel(reply(TextPart("好")))
        val rt = session()
        rt.send(msg(), listOf(TextPart("在吗")))
        rt.awaitIdle()

        val loaded = events().filterIsInstance<MemoryLoaded>().single()
        assertEquals(dev.min.agent.spi.AutoMemoryInfo(GuestPath(memoryDir), totalLines = 2, loadedLines = 2), loaded.autoMemory)
        assertEquals("$memoryDir/MEMORY.md", loaded.files.single().path.value)
        assertEquals(dev.min.agent.spi.MemorySource.Auto, loaded.files.single().source)

        val system = scripted.requests.single().system
        assertEquals(3, system.size)
        val auto = system[2]
        assertTrue(auto, auto.startsWith("Auto memory:\nYour notes for this project live in $memoryDir."))
        assertTrue(auto, auto.contains("user.md: 喜欢简洁"))
        assertTrue(auto, auto.contains("MEMORY.md: 2/200 lines."))
        // 项目说明段不存在（没有 CLAUDE.md），索引不属于「项目要求」
        assertTrue(auto, system.none { it.startsWith("Project instructions:") })
        assertLogIsTruth(rt)
    }

    @Test
    fun `a worktree session keys its memory back to the main repository`() = run {
        // /wt 是 /repo 的链接工作树：键是主仓库 /repo（设计 §2.1）
        fs.put("/repo/.git/HEAD", "ref: refs/heads/main")
        fs.put("/repo/.git/worktrees/wt/commondir", "../..")
        fs.put("/wt/.git", "gitdir: /repo/.git/worktrees/wt\n")
        fs.put("/root/.claude/projects/-repo/memory/MEMORY.md", "project.md: 主仓库的笔记\n")
        scripted = ScriptedModel(reply(TextPart("好")))
        val rt = SessionRuntime.create(
            store, header, SessionCreated(GuestPath("/wt"), TEST_MODEL, PermissionMode.Default), deps(scripted), scope,
        )
        rt.send(msg(), listOf(TextPart("在吗")))
        rt.awaitIdle()

        val loaded = events().filterIsInstance<MemoryLoaded>().single()
        assertEquals(dev.min.agent.spi.AutoMemoryInfo(GuestPath("/root/.claude/projects/-repo/memory"), 1, 1), loaded.autoMemory)
        assertLogIsTruth(rt)
    }

    @Test
    fun `turning it off by settings removes the section the index and the event`() = run {
        fs.put("$memoryDir/MEMORY.md", "user.md: x\n")
        fs.put("/root/.claude/settings.json", """{"autoMemoryEnabled": false}""")
        scripted = ScriptedModel(reply(TextPart("好")))
        val rt = session()
        rt.send(msg(), listOf(TextPart("在吗")))
        rt.awaitIdle()

        assertFalse(events().any { it is MemoryLoaded })
        assertEquals(2, scripted.requests.single().system.size)
        assertLogIsTruth(rt)
    }

    @Test
    fun `the off switch reads the usual truthy spellings`() {
        listOf("1", "true", "TRUE", " yes ").forEach { assertTrue(it, isTruthyEnv(it)) }
        listOf(null, "", "0", "false", "no", "2").forEach { assertFalse(it.toString(), isTruthyEnv(it)) }
    }

    @Test
    fun `the environment variable forces it off`() = run {
        fs.put("$memoryDir/MEMORY.md", "user.md: x\n")
        scripted = ScriptedModel(reply(TextPart("好")))
        val rt = session(env = mapOf("CLAUDE_CODE_DISABLE_AUTO_MEMORY" to "1"))
        rt.send(msg(), listOf(TextPart("在吗")))
        rt.awaitIdle()

        assertFalse(events().any { it is MemoryLoaded })
        assertEquals(2, scripted.requests.single().system.size)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a user-level override moves the directory`() = run {
        fs.put("/root/notes/MEMORY.md", "user.md: 换了地方\n")
        fs.put("/root/.claude/settings.json", """{"autoMemoryDirectory": "~/notes"}""")
        scripted = ScriptedModel(reply(TextPart("好")))
        val rt = session()
        rt.send(msg(), listOf(TextPart("在吗")))
        rt.awaitIdle()

        val loaded = events().filterIsInstance<MemoryLoaded>().single()
        assertEquals(dev.min.agent.spi.AutoMemoryInfo(GuestPath("/root/notes"), 1, 1), loaded.autoMemory)
        assertEquals("/root/notes/MEMORY.md", loaded.files.single().path.value)
        assertLogIsTruth(rt)
    }

    @Test
    fun `an invalid override falls back to the default location with a note`() = run {
        fs.put("$memoryDir/MEMORY.md", "user.md: 默认位置\n")
        fs.put("/root/.claude/settings.json", """{"autoMemoryDirectory": "notes"}""")
        scripted = ScriptedModel(reply(TextPart("好")))
        val rt = session()
        rt.send(msg(), listOf(TextPart("在吗")))
        rt.awaitIdle()

        val loaded = events().filterIsInstance<MemoryLoaded>().single()
        assertEquals(dev.min.agent.spi.AutoMemoryInfo(GuestPath(memoryDir), 1, 1), loaded.autoMemory)
        assertTrue(loaded.notes.single().contains("autoMemoryDirectory"))
        assertLogIsTruth(rt)
    }

    @Test
    fun `a missing index still announces the directory with zero usage`() = run {
        scripted = ScriptedModel(reply(TextPart("好")))
        val rt = session()
        rt.send(msg(), listOf(TextPart("在吗")))
        rt.awaitIdle()

        val loaded = events().filterIsInstance<MemoryLoaded>().single()
        assertEquals(dev.min.agent.spi.AutoMemoryInfo(GuestPath(memoryDir)), loaded.autoMemory)
        assertTrue(loaded.files.isEmpty())
        assertTrue(scripted.requests.single().system[2].contains("MEMORY.md: 0/200 lines."))
        assertLogIsTruth(rt)
    }

    @Test
    fun `acceptEdits writes to the memory directory without asking`() = run {
        fs.put("$memoryDir/MEMORY.md", "user.md: x\n")
        scripted = ScriptedModel(
            reply(writeCall("c1", "$memoryDir/feedback.md", "别用 Tab"), stop = StopReason.ToolUse),
            reply(TextPart("记下了")),
        )
        val rt = session(mode = PermissionMode.AcceptEdits)
        rt.send(msg(), listOf(TextPart("记一条")))
        rt.awaitIdle()

        // 没有审批，写入发生了
        assertFalse(events().any { it is ApprovalRequested })
        assertEquals("$memoryDir/feedback.md", fs.writes.single().first.value)
        assertLogIsTruth(rt)
    }

    @Test
    fun `default mode asks and suggests a rule for the memory directory`() = run {
        fs.put("$memoryDir/MEMORY.md", "user.md: x\n")
        scripted = ScriptedModel(
            reply(writeCall("c1", "$memoryDir/feedback.md", "别用 Tab"), stop = StopReason.ToolUse),
            reply(TextPart("记下了")),
        )
        val rt = session()
        rt.send(msg(), listOf(TextPart("记一条")))
        val approval = rt.state.first { s ->
            s.turn?.calls?.values?.any { it.phase is dev.min.agent.kernel.CallPhase.AwaitingApproval } == true
        }
        val requested = events().filterIsInstance<ApprovalRequested>().single()
        // 规则里 `//` 是绝对路径锚（CC 约定）：这条建议就是「Edit(记忆目录/**)」，点一次「总是允许」即成 CC 的行为
        assertEquals("Edit(/$memoryDir/**)", requested.suggestedRule)
        assertNull(requested.floorReason)

        rt.answerApproval(requested.approvalId, ApprovalChoice.Once)
        rt.awaitIdle()
        assertEquals("$memoryDir/feedback.md", fs.writes.single().first.value)
        assertLogIsTruth(rt)
    }

    @Test
    fun `plan mode memory writes ask and a user denial keeps them read-only`() = run {
        fs.put("$memoryDir/MEMORY.md", "user.md: x\n")
        scripted = ScriptedModel(
            reply(writeCall("c1", "$memoryDir/feedback.md", "别用 Tab"), stop = StopReason.ToolUse),
            reply(TextPart("知道了")),
        )
        val rt = session(mode = PermissionMode.Plan)
        rt.send(msg(), listOf(TextPart("记一条")))
        val waiting = rt.state.first { it.phaseOf(CallId("c1")) is CallPhase.AwaitingApproval }
        assertTrue(fs.writes.isEmpty())
        rt.answerApproval((waiting.phaseOf(CallId("c1")) as CallPhase.AwaitingApproval).approvalId, ApprovalChoice.Deny("计划模式先别改"))
        rt.awaitIdle()

        assertEquals(1, events().count { it is ApprovalRequested })
        assertTrue(fs.writes.isEmpty())
        val result = events().filterIsInstance<dev.min.agent.spi.ToolOutcome>().single { it.callId == CallId("c1") }.result
        assertTrue(result.text, result.isError && "计划模式" in result.userText.orEmpty() && "计划模式" !in result.text)
        assertLogIsTruth(rt)
    }

    @Test
    fun `writing memory keeps the prompt frozen within the session`() = run {
        // 系统提示只由创建时的快照拼：会话里写完笔记，用量行也不变（缓存不破，§2.4）
        fs.put("$memoryDir/MEMORY.md", "user.md: x\n")
        scripted = ScriptedModel(
            reply(writeCall("c1", "$memoryDir/feedback.md", "别用 Tab"), stop = StopReason.ToolUse),
            reply(TextPart("记下了")),
        )
        val rt = session(mode = PermissionMode.AcceptEdits)
        rt.send(msg(), listOf(TextPart("记一条")))
        rt.awaitIdle()

        // 写的是新文件：索引本身不变，两次请求的系统提示逐字相同
        assertEquals("user.md: x\n", fs.text("$memoryDir/MEMORY.md"))
        assertEquals(2, scripted.requests.size)
        assertEquals(scripted.requests[0].system, scripted.requests[1].system)
        assertTrue(scripted.requests[1].system[2].contains("MEMORY.md: 1/200 lines."))
        assertLogIsTruth(rt)
    }
}
