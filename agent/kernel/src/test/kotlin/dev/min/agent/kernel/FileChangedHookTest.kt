package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.CwdWatchPathsChanged
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.Note
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * FileChanged（官方 hooks 页「FileChanged」）：被监视的文件在磁盘上变了就触发，不管是谁改的。
 * 监视清单 = matcher 里点名的工作目录文件名（按 `|` 切）+ hooks 返回的 watchPaths（替换动态清单）；matcher 对被改文件的 basename 选组；
 * 输入 `file_path`（绝对）与 `event`（change / add / unlink）；无决策控制；没有人点名文件就不起 watcher。
 */
class FileChangedHookTest {
    @get:Rule val tmp = TemporaryFolder()
    private val rigs = mutableListOf<HookTestRig>()
    @After fun tearDown() = rigs.forEach { it.close() }

    private fun rig(
        http: FakeHttp = FakeHttp(),
        model: dev.min.agent.llm.ModelClient = ScriptedModel(reply(TextPart("好")), reply(TextPart("好")), reply(TextPart("好"))),
        tools: List<dev.min.agent.spi.Tool<*>> = emptyList(),
        hookShellPath: (GuestPath) -> String = { it.value },
        fs: FakeFs = FakeFs().apply { mkdir("/workspace") },
        block: suspend HookTestRig.() -> Unit,
    ) = runBlocking {
        val rig = HookTestRig(tmp.newFolder().toPath(), fs = fs, http = http, model = model, tools = tools, hookShellPath = hookShellPath).also { rigs += it }
        withTimeout(30_000) { rig.block() }
    }

    private fun HookTestRig.fileChanged() = http.bodies("FileChanged")

    @Test fun `a named file that is created, changed and deleted fires add, change and unlink with absolute file_path`() = rig {
        val rt = session(httpHooks("FileChanged" to ".envrc|data.csv"))
        fs.put("/workspace/.envrc", "export A=1")
        rt.fileWatches.pollNow()
        fs.put("/workspace/.envrc", "export A=2")
        rt.fileWatches.pollNow()
        fs.delete("/workspace/.envrc")
        rt.fileWatches.pollNow()
        val bodies = fileChanged()
        assertEquals(bodies.toString(), 3, bodies.size)
        listOf("add", "change", "unlink").forEachIndexed { i, event ->
            assertTrue(bodies[i], bodies[i].contains("\"file_path\":\"/workspace/.envrc\""))
            assertTrue(bodies[i], bodies[i].contains("\"event\":\"$event\""))
            assertTrue(bodies[i], bodies[i].contains("\"hook_event_name\":\"FileChanged\""))
        }
        assertEquals("每一次都记了一批 hook", 3, hooksRan(rt, "FileChanged").size)
    }

    @Test fun `files that did not change fire nothing, and the baseline is taken when the session starts`() = rig(
        fs = FakeFs().apply { mkdir("/workspace"); put("/workspace/.envrc", "x") },
    ) {
        val rt = session(httpHooks("FileChanged" to ".envrc"))
        rt.fileWatches.arm()
        rt.fileWatches.pollNow()
        rt.fileWatches.pollNow()
        assertTrue("没变就不触发", http.sent.isEmpty())
        // 外部进程在会话建好之后改了它（没经过任何工具）：照样触发——原文「no matter what changed the file」
        fs.put("/workspace/.envrc", "y")
        rt.fileWatches.pollNow()
        assertEquals(1, fileChanged().size)
    }

    @Test fun `only a file named by a matcher or a watchPaths entry is watched`() = rig {
        val rt = session(httpHooks("FileChanged" to ".envrc"))
        fs.put("/workspace/other.txt", "x")
        rt.fileWatches.pollNow()
        fs.put("/workspace/other.txt", "y")
        rt.fileWatches.pollNow()
        assertTrue("没点名的文件不监视", http.sent.isEmpty())
    }

    @Test fun `no FileChanged hook and no watchPaths means nothing is watched at all`() = rig {
        val rt = session(httpHooks("Stop" to null))
        fs.put("/workspace/.envrc", "x")
        rt.fileWatches.pollNow()
        fs.put("/workspace/.envrc", "y")
        rt.fileWatches.pollNow()
        assertTrue(http.sent.isEmpty())
        assertTrue(hooksRan(rt, "FileChanged").isEmpty())
    }

    @Test fun `the matcher filters which group runs by the changed file's basename, a group without matcher runs for every watched file`() = rig {
        val rt = session(httpHookGroups(
            Triple("FileChanged", ".envrc", "https://hooks.example/envrc"),
            Triple("FileChanged", "data.csv", "https://hooks.example/csv"),
            Triple("FileChanged", null, "https://hooks.example/all"),
        ))
        fs.put("/workspace/.envrc", "1")
        rt.fileWatches.pollNow()
        fs.put("/workspace/data.csv", "1")
        rt.fileWatches.pollNow()
        val byUrl = http.sent.groupBy({ it.url }, { it.body })
        assertEquals(1, byUrl.getValue("https://hooks.example/envrc").size)
        assertEquals(1, byUrl.getValue("https://hooks.example/csv").size)
        assertEquals("没写 matcher 的组每个被监视的文件都跑", 2, byUrl.getValue("https://hooks.example/all").size)
        assertTrue(byUrl.getValue("https://hooks.example/envrc").single().contains(".envrc"))
        assertTrue(byUrl.getValue("https://hooks.example/csv").single().contains("data.csv"))
    }

    @Test fun `watchPaths from the hook replaces the dynamic list and is itself watched, an empty array clears it`() {
        var next = """{"watchPaths":["/workspace/sub/dyn.txt"]}"""
        rig(http = FakeHttp { HttpHookResult.Responded(200, next) }, fs = FakeFs().apply { mkdir("/workspace"); mkdir("/workspace/sub") }) {
            val rt = session(httpHooks("FileChanged" to ".envrc"))
            fs.put("/workspace/.envrc", "1")
            rt.fileWatches.pollNow()
            assertEquals(listOf(GuestPath("/workspace/sub/dyn.txt")), rt.state.value.cwdWatchPaths)
            assertTrue(events(rt).any { it is CwdWatchPathsChanged })
            // 动态清单里的文件被改：触发（matcher 省略的组或 matcher 匹配其 basename 才跑——这里 matcher 是 .envrc，不匹配，所以不跑）
            fs.put("/workspace/sub/dyn.txt", "1")
            rt.fileWatches.pollNow()
            assertEquals("dyn.txt 不匹配 .envrc 这个 matcher：只监视、不跑这一组", 1, fileChanged().size)
            next = """{"watchPaths":[]}"""
            fs.put("/workspace/.envrc", "2")
            rt.fileWatches.pollNow()
            assertTrue("空数组清空动态清单", rt.state.value.cwdWatchPaths.isEmpty())
        }
    }

    @Test fun `a relative watchPaths entry is rejected as a whole and the old list stays`() = rig(
        http = FakeHttp { HttpHookResult.Responded(200, """{"watchPaths":["/workspace/ok.txt","relative.txt"]}""") },
    ) {
        val rt = session(httpHooks("FileChanged" to ".envrc"))
        fs.put("/workspace/.envrc", "1")
        rt.fileWatches.pollNow()
        assertTrue(rt.state.value.cwdWatchPaths.isEmpty())
        assertTrue(events(rt).filterIsInstance<Note>().any { it.text.contains("watchPaths 无效") })
    }

    @Test fun `a watchPaths entry from a SessionStart hook seeds the list`() = rig(
        http = FakeHttp { HttpHookResult.Responded(200, """{"hookSpecificOutput":{"hookEventName":"SessionStart","watchPaths":["/workspace/seed.env"]}}""") },
    ) {
        val rt = session(httpHooks("SessionStart" to null, "FileChanged" to null))
        assertEquals(listOf(GuestPath("/workspace/seed.env")), rt.state.value.cwdWatchPaths)
        fs.put("/workspace/seed.env", "1")
        rt.fileWatches.pollNow()
        assertEquals(1, fileChanged().size)
        assertTrue(fileChanged().single().contains("\"file_path\":\"/workspace/seed.env\""))
    }

    @Test fun `a hook cannot block anything, continue and decision are discarded`() = rig(
        http = FakeHttp { HttpHookResult.Responded(200, """{"continue":false,"decision":"block","reason":"no","systemMessage":"watch out"}""") },
    ) {
        val rt = session(httpHooks("FileChanged" to ".envrc"))
        fs.put("/workspace/.envrc", "1")
        rt.fileWatches.pollNow()
        // 没有任何东西被停：文件监视照常往下走（又改一次又触发一次）
        fs.put("/workspace/.envrc", "2")
        rt.fileWatches.pollNow()
        assertEquals(2, fileChanged().size)
        assertEquals("systemMessage 作为给人看的一条提示", 2, events(rt).filterIsInstance<Note>().count { it.text == "watch out" })
    }

    @Test fun `file_path is the path as the hook's shell sees it`() = rig(hookShellPath = { "/shell" + it.value }) {
        val rt = session(httpHooks("FileChanged" to ".envrc"))
        fs.put("/workspace/.envrc", "1")
        rt.fileWatches.pollNow()
        assertTrue(fileChanged().single(), fileChanged().single().contains("\"file_path\":\"/shell/workspace/.envrc\""))
    }

    // ---- 真回合里的时机 ----

    @Test fun `a file a tool changed fires its hook before the next model request`() {
        val fs = FakeFs().apply { mkdir("/workspace") }
        val model = ScriptedModel(
            reply(ToolCallPart(CallId("t1"), "Touch", args()), stop = StopReason.ToolUse),
            reply(TextPart("done")),
        )
        var requestsWhenHookRan = -1
        val http = FakeHttp { requestsWhenHookRan = model.requests.size; HttpHookResult.Responded(200, "{}") }
        rig(http = http, model = model, tools = listOf(actionTool("Touch") { fs.put("/workspace/.envrc", "from tool") }), fs = fs) {
            val rt = session(httpHooks("FileChanged" to ".envrc"))
            say(rt, "go")
            assertEquals(1, fileChanged().size)
            assertEquals("hook 在第二个模型请求之前就跑完了（一批工具之后立刻看）", 1, requestsWhenHookRan)
        }
    }

    @Test fun `while a turn runs the poll loop reports a change before the tool returns`() {
        val fs = FakeFs().apply { mkdir("/workspace") }
        val release = CompletableDeferred<Unit>()
        val model = ScriptedModel(reply(ToolCallPart(CallId("t1"), "Wait", args()), stop = StopReason.ToolUse), reply(TextPart("done")))
        val http = FakeHttp()
        rig(http = http, model = model, tools = listOf(actionTool("Wait") { release.await() }), fs = fs) {
            val rt = session(httpHooks("FileChanged" to ".envrc"))
            rt.fileWatches.pollMs = 20
            val before = rt.state.value.lastTurn?.turnId
            rt.send(dev.min.agent.protocol.ClientMsgId("m1"), listOf(TextPart("go")))
            // 工具卡着、回合在跑：此时外部改文件，轮询协程应当报出来
            rt.state.first { s -> s.turn?.calls?.values?.any { it.tool == "Wait" } == true }
            fs.put("/workspace/.envrc", "outside change")
            val deadline = System.currentTimeMillis() + 10_000
            while (fileChanged().isEmpty() && System.currentTimeMillis() < deadline) delay(20)
            assertEquals("工具还没返回，回合里的轮询已经触发了 hook", 1, fileChanged().size)
            assertTrue(rt.state.value.turn != null)
            release.complete(Unit)
            rt.awaitIdle()
            assertTrue(rt.state.value.lastTurn?.turnId != before)
        }
    }

    @Test fun `a change made while the session is idle is reported before the next turn starts, and an idle session is not polled`() = rig {
        val rt = session(httpHooks("FileChanged" to ".envrc"))
        rt.fileWatches.pollMs = 20
        fs.put("/workspace/.envrc", "1")
        delay(300)
        assertTrue("空闲时不轮询、不唤醒任何东西（原文的 watcher 在这里让位给省电）", http.sent.isEmpty())
        say(rt, "go")
        assertEquals("下一个回合开始前补报", 1, fileChanged().size)
    }

}
