package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.DirectoryAdded
import dev.min.agent.spi.Durability
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.Note
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.PermissionRulesLoaded
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import kotlinx.coroutines.CompletableDeferred
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

/**
 * `/add-dir` 与 DirectoryAdded hook（官方 hooks 页「DirectoryAdded」、permissions 页「Working directories」、commands 页 `/add-dir`）。
 * 权限口径是安全相关的：加进来的目录只授予文件访问（读不再问、写按权限模式），手机文件底线不被覆盖，
 * 不存在 / 不是目录 / 网络路径 / 已在工作目录里的不加；模型自己没有入口（只有宿主调 [addDirectory]，没有对应的工具）。
 */
class DirectoryAddedTest {
    @get:Rule val tmp = TemporaryFolder()
    private val rigs = mutableListOf<HookTestRig>()
    @After fun tearDown() = rigs.forEach { it.close() }

    private fun rig(
        model: ModelClient = ScriptedModel(reply(TextPart("好")), reply(TextPart("好"))),
        tools: List<Tool<*>> = emptyList(),
        http: FakeHttp = FakeHttp(),
        block: suspend HookTestRig.() -> Unit,
    ) = runBlocking {
        val fs = FakeFs().apply {
            mkdir("/workspace"); mkdir("/other"); mkdir("/other/sub"); put("/other/notes.txt", "hi"); mkdir("/third"); mkdir("/sdcard/Pictures")
            scopes["/sdcard"] = PathScope.Device
        }
        val rig = HookTestRig(tmp.newFolder().toPath(), fs = fs, http = http, model = model, tools = tools).also { rigs += it }
        withTimeout(30_000) { rig.block() }
    }

    /** 像 Write：写一个文件，路径的范围由宿主给（引擎再按 additionalDirectories 重新分级） */
    private val write = object : Tool<JsonObject> {
        override val name = "Write"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits()
        override fun description(profile: HarnessProfile) = "写文件"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext): PermissionAsk {
            val path = GuestPath((input["file_path"] as JsonPrimitive).content)
            return PermissionAsk(name, "写 $path", paths = listOf(PathAccess(path, AccessKind.Write, ctx.fs.scopeOf(path))))
        }
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("wrote")))))
        }
    }

    private fun writeCall(path: String) = ScriptedModel(
        reply(ToolCallPart(CallId("w1"), "Write", args("file_path" to path)), stop = StopReason.ToolUse), reply(TextPart("done")),
    )

    @Test fun `adding a directory fires DirectoryAdded with the documented input and matcher`() = rig {
        val rt = session(httpHooks("DirectoryAdded" to "slash_command"))
        val added = rt.addDirectory(GuestPath("/other"), DIRECTORY_SOURCE_SLASH_COMMAND) as AddDirectoryResult.Added
        added.hooks!!.join()
        val body = http.bodies("DirectoryAdded").single()
        assertTrue(body, body.contains("\"directory\":\"/other\""))
        assertTrue(body, body.contains("\"source\":\"slash_command\""))
        assertTrue(body, body.contains("\"session_id\":\"${rt.sessionId.value}\""))
        assertEquals(listOf(GuestPath("/other")), rt.state.value.additionalDirectories)
        assertTrue(events(rt).any { it is DirectoryAdded && it.directory == GuestPath("/other") && it.source == "slash_command" })
        assertEquals("日志里记着这一批 hook", 1, hooksRan(rt, "DirectoryAdded").size)
    }

    @Test fun `the matcher filters on how the directory was added`() = rig {
        val rt = session(httpHooks("DirectoryAdded" to "register_repo_root"))
        (rt.addDirectory(GuestPath("/other"), DIRECTORY_SOURCE_SLASH_COMMAND) as AddDirectoryResult.Added).hooks?.join()
        assertTrue("matcher 是 register_repo_root，slash_command 不匹配", http.sent.isEmpty())
        (rt.addDirectory(GuestPath("/third"), DIRECTORY_SOURCE_REGISTER_REPO_ROOT) as AddDirectoryResult.Added).hooks!!.join()
        assertTrue(http.bodies("DirectoryAdded").single().contains("\"source\":\"register_repo_root\""))
    }

    @Test fun `the add completes without waiting for the hook`() {
        val gate = CompletableDeferred<Unit>()
        rig(http = FakeHttp { gate.await(); HttpHookResult.Responded(200, "{}") }) {
            val rt = session(httpHooks("DirectoryAdded" to null))
            val result = rt.addDirectory(GuestPath("/other")) as AddDirectoryResult.Added
            assertTrue("hook 还卡着，加目录已经完成、权限范围已刷新", rt.state.value.additionalDirectories.contains(GuestPath("/other")))
            assertFalse(result.hooks!!.isCompleted)
            gate.complete(Unit)
            result.hooks!!.join()
        }
    }

    @Test fun `a slash command hook's systemMessage goes to the model as context, not to the user`() = rig(
        http = FakeHttp { HttpHookResult.Responded(200, """{"systemMessage":"deps installed for /other"}""") },
    ) {
        val rt = session(httpHooks("DirectoryAdded" to null))
        (rt.addDirectory(GuestPath("/other")) as AddDirectoryResult.Added).hooks!!.join()
        val context = events(rt).filterIsInstance<HookContextAdded>().single()
        assertEquals("DirectoryAdded", context.event)
        assertEquals("deps installed for /other", context.text)
        assertTrue("原文：不显示给人", events(rt).filterIsInstance<Note>().none { it.text.contains("deps installed") })
    }

    @Test fun `a register_repo_root hook's systemMessage only reaches the debug record`() = rig(
        http = FakeHttp { HttpHookResult.Responded(200, """{"systemMessage":"quiet"}""") },
    ) {
        val rt = session(httpHooks("DirectoryAdded" to null))
        (rt.addDirectory(GuestPath("/other"), DIRECTORY_SOURCE_REGISTER_REPO_ROOT) as AddDirectoryResult.Added).hooks!!.join()
        assertTrue(events(rt).none { it is HookContextAdded })
        assertTrue(events(rt).filterIsInstance<Note>().none { it.text.contains("quiet") })
        assertEquals(1, hooksRan(rt, "DirectoryAdded").size)
    }

    @Test fun `a hook that fails cannot undo the add`() = rig(http = FakeHttp { HttpHookResult.Responded(500, "boom") }) {
        val rt = session(httpHooks("DirectoryAdded" to null))
        (rt.addDirectory(GuestPath("/other")) as AddDirectoryResult.Added).hooks!!.join()
        assertTrue("原文：目录已经加上，hook 拦不了", rt.state.value.additionalDirectories.contains(GuestPath("/other")))
    }

    @Test fun `a directory that is already a working directory or inside one adds nothing and fires nothing`() = rig {
        val rt = session(httpHooks("DirectoryAdded" to null))
        assertTrue(rt.addDirectory(GuestPath("/workspace")) is AddDirectoryResult.AlreadyIncluded)
        fs.mkdir("/workspace/sub")
        assertTrue("工作目录里面的子目录", rt.addDirectory(GuestPath("/workspace/sub")) is AddDirectoryResult.AlreadyIncluded)
        (rt.addDirectory(GuestPath("/other")) as AddDirectoryResult.Added).hooks!!.join()
        assertTrue("已加过的目录里面", rt.addDirectory(GuestPath("/other/sub")) is AddDirectoryResult.AlreadyIncluded)
        assertTrue("同一个目录再加一次", rt.addDirectory(GuestPath("/other/")) is AddDirectoryResult.AlreadyIncluded)
        assertEquals("只有 /other 那一次触发过", 1, http.bodies("DirectoryAdded").size)
        assertEquals(1, events(rt).count { it is DirectoryAdded })
    }

    @Test fun `a missing path, a file, a network path and a phone-file path are refused before anything is written`() = rig {
        val rt = session(httpHooks("DirectoryAdded" to null))
        assertTrue(rt.addDirectory(GuestPath("/nope")) is AddDirectoryResult.Refused)
        assertTrue("文件不是目录", rt.addDirectory(GuestPath("/other/notes.txt")) is AddDirectoryResult.Refused)
        assertTrue("手机文件底线不被附加目录覆盖：直接拒绝，不让人以为加上了", rt.addDirectory(GuestPath("/sdcard/Pictures")) is AddDirectoryResult.Refused)
        assertTrue(isNetworkPathText("\\\\server\\share"))
        assertTrue(isNetworkPathText("//server/share"))
        assertFalse(isNetworkPathText("/local/path"))
        assertFalse(isNetworkPathText("C:\\Users"))
        assertTrue(rt.state.value.additionalDirectories.isEmpty())
        assertTrue(events(rt).none { it is DirectoryAdded })
        assertTrue(http.sent.isEmpty())
    }

    @Test fun `source must be one of the two documented values`() = rig {
        val rt = session()
        try {
            rt.addDirectory(GuestPath("/other"), "model_tool")
            throw AssertionError("应当拒绝未知来源")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(rt.state.value.additionalDirectories.isEmpty())
    }

    // ---- 权限口径（安全相关，变异过） ----

    @Test fun `before adding, writing outside the working directory asks even in acceptEdits mode`() = rig(model = writeCall("/other/a.txt"), tools = listOf(write)) {
        val rt = session(mode = PermissionMode.AcceptEdits)
        rt.send(ClientMsgId("c1"), listOf(TextPart("写")))
        withTimeout(10_000) { rt.state.first { s -> s.turn?.calls?.values?.any { it.phase is CallPhase.AwaitingApproval } == true } }
        rt.interrupt()
    }

    @Test fun `after adding, a write into the directory follows the permission mode instead of asking`() = rig(model = writeCall("/other/a.txt"), tools = listOf(write)) {
        val rt = session(mode = PermissionMode.AcceptEdits)
        assertTrue(rt.addDirectory(GuestPath("/other")) is AddDirectoryResult.Added)
        say(rt, "写")
        assertTrue("加了之后不用问", events(rt).none { it is ApprovalRequested })
        assertEquals(dev.min.agent.spi.TurnOutcome.Completed, rt.state.value.lastTurn!!.outcome)
    }

    @Test fun `an added directory does not make a write free in default mode`() = rig(model = writeCall("/other/a.txt"), tools = listOf(write)) {
        val rt = session(mode = PermissionMode.Default)
        rt.addDirectory(GuestPath("/other"))
        rt.send(ClientMsgId("c2"), listOf(TextPart("写")))
        withTimeout(10_000) { rt.state.first { s -> s.turn?.calls?.values?.any { it.phase is CallPhase.AwaitingApproval } == true } }
        rt.interrupt()
    }

    @Test fun `a session reopened from its log keeps the added directory, and a settings reload cannot drop it`() = rig {
        val rt = session()
        rt.addDirectory(GuestPath("/other"))
        assertTrue(hub.close(rt.sessionId))
        val reopened = hub.open(rt.sessionId)!!
        assertEquals(listOf(GuestPath("/other")), reopened.state.value.additionalDirectories)
        assertEquals(listOf(GuestPath("/other")), reopened.state.value.addedDirectories)
        // 设置整份重读（ConfigChange 生效）替换目录列表时，中途加的不属于设置，不能被冲掉
        reopened.commit(listOf(PermissionRulesLoaded(emptyList(), additionalDirectories = listOf(GuestPath("/from-settings")))), Durability.Sync)
        assertEquals(setOf(GuestPath("/from-settings"), GuestPath("/other")), reopened.state.value.additionalDirectories.toSet())
    }

    @Test fun `nothing is written to any settings file`() = rig {
        val rt = session()
        rt.addDirectory(GuestPath("/other"))
        assertTrue("只在会话里有效：不写 settings", fs.writes.none { it.first.value.endsWith("settings.json") || it.first.value.endsWith("settings.local.json") })
    }
}
