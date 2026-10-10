package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.host.CasWorkspaceSnapshots
import dev.min.agent.host.JvmHttpHookClient
import dev.min.agent.host.JvmSearch
import dev.min.agent.kernel.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import dev.min.agent.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/** 所有 git 副作用只发生在独立临时仓库，测试的开发 worktree 永不作为功能 worktree。 */
class WorktreeIntegrationTest {
    private val temp = Files.createTempDirectory("M5 中文 space ' ")
    private val repo = Files.createDirectories(temp.resolve("repo"))
    private val home = Files.createDirectories(temp.resolve("home"))
    private val paths = DesktopPaths(repo, temp.resolve("scratch"))
    private val cwd = paths.guestOfHost(repo)
    private val exec = LocalExec(paths::hostOf, temp.resolve("logs"), paths.guestOfHost(temp.resolve("logs")),
        listOf(if (System.getProperty("os.name").startsWith("Windows")) "C:/Program Files/Git/bin/bash.exe" else "bash", "-lc"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = FileSessionStore(temp.resolve("sessions"), { System.currentTimeMillis() })
    private val ids = AtomicLong()
    private val servers = mutableListOf<HttpServer>()
    private val tools = listOf(ReadTool, WriteTool, BashTool(), EnterWorktreeTool, ExitWorktreeTool, AgentTool)

    @After fun cleanup() {
        servers.forEach { it.stop(0) }
        scope.cancel()
        temp.toFile().deleteRecursively()
    }

    private fun runTest(block: suspend () -> Unit) = runBlocking { withTimeout(120_000) { block() } }
    private suspend fun shell(script: String, dir: GuestPath = cwd): String {
        val result = exec.run(ShellCommand(script, dir), ExecOptions(30_000)) as ExecResult.Exited
        assertEquals(result.output, 0, result.exitCode)
        return result.output.trim()
    }
    private fun q(text: String) = "'" + text.replace("'", "'\\''") + "'"
    private suspend fun initRepo() {
        shell("git init -b main && git config user.email test@example.invalid && git config user.name Test && git config core.autocrlf false")
        Files.writeString(repo.resolve("tracked.txt"), "base\n")
        shell("git add tracked.txt && git commit -m initial")
    }
    private fun deps(model: ScriptedModel = ScriptedModel(reply(TextPart("done")))) = KernelDeps(
        clock = Clock { System.currentTimeMillis() }, ids = IdGenerator { "${it}_${ids.incrementAndGet()}" },
        models = { ResolvedModel(TEST_PROFILE, model) }, tools = tools,
        host = HostPorts(paths.fs, JvmSearch(paths.fs), object : Exec {
            override suspend fun kill(handle: ExecHandle) = exec.kill(handle)
            override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult {
                assertTrue("Git 必须兼容拒绝 env 的 Android Exec", command.env.isEmpty())
                return exec.run(command, options)
            }
        }, { FakeArtifacts() }, httpHooks = JvmHttpHookClient(),
            snapshots = CasWorkspaceSnapshots(temp.resolve("snapshots"), { paths.fs }, paths.guestOfHost(home))),
        platform = "test", zone = ZoneOffset.UTC, memoryHome = paths.guestOfHost(home),
        hookShellPath = { paths.guestOfHost(paths.hostOf(it)).value }, gitPath = { paths.hostOf(it).toString() }, canonicalPath = { desktopCanonicalCredentialPath(paths, it) },
        trustProjectHooks = { true }, env = { if (it == "CLAUDE_CODE_DISABLE_AUTO_MEMORY") "1" else null },
    )
    private suspend fun runtime(model: ScriptedModel = ScriptedModel(reply(TextPart("done"))), agents: List<AgentDefinition> = BuiltinAgents.all): Pair<SessionHub, SessionRuntime> {
        val hub = SessionHub(store, deps(model), scope, agents = agents)
        return hub to hub.create(cwd, TEST_MODEL, PermissionMode.BypassPermissions)
    }
    private suspend fun truth(rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals("日志即真相", fold(log.header, log.read().toList()), rt.state.value)
    }
    private fun text(result: ToolResult) = result.content.filterIsInstance<TextPart>().joinToString("") { it.text }
    private suspend fun ok(result: ToolResult) { assertFalse(text(result), result.isError) }
    private suspend fun denied(result: ToolResult) { assertTrue(text(result), result.isError) }
    private suspend fun entered(rt: SessionRuntime, name: String = "feature"): WorktreeBinding {
        ok(rt.enterWorktree(name)); return rt.state.value.worktree!!
    }

    @Test fun `create enters CC path branch and ignores tree then removes clean`() = runTest {
        initRepo(); val (_, rt) = runtime(); val w = entered(rt)
        assertEquals("${cwd.value}/.claude/worktrees/feature", w.path.value)
        assertEquals("worktree-feature", w.branch)
        assertEquals(w.path, rt.state.value.settings.cwd)
        assertTrue(Files.exists(paths.hostOf(w.path).resolve("tracked.txt")))
        assertEquals("", shell("git status --porcelain"))
        assertEquals(".claude/worktrees/feature", shell("git check-ignore .claude/worktrees/feature"))
        assertEquals(shell("git rev-parse HEAD"), w.baseCommit)
        ok(rt.exitWorktree(remove = true))
        assertEquals(cwd, rt.state.value.settings.cwd)
        assertNull(rt.state.value.worktree)
        assertFalse(Files.exists(paths.hostOf(w.path)))
        assertEquals("", shell("git branch --list worktree-feature"))
        assertTrue(rt.state.value.finishedWorktrees.last().removed); truth(rt)
    }

    @Test fun `keep preserves tree and branch existing path can be entered`() = runTest {
        initRepo(); val (_, rt) = runtime(); val w = entered(rt)
        Files.writeString(paths.hostOf(w.path).resolve("new.txt"), "work")
        ok(rt.exitWorktree())
        assertTrue(Files.exists(paths.hostOf(w.path))); assertFalse(rt.state.value.finishedWorktrees.last().removed)
        assertEquals("worktree-feature", shell("git -C ${q(paths.hostOf(w.path).toString())} branch --show-current"))
        ok(rt.enterWorktree(path = w.path.value))
        assertEquals(cwd, rt.state.value.worktree!!.originalCwd)
        assertEquals(w.path, rt.state.value.settings.cwd)
        truth(rt)
    }

    @Test fun `dirty tracked untracked and committed work each refuse remove`() = runTest {
        initRepo(); val (_, rt) = runtime(); val w = entered(rt)
        suspend fun refuseDirty() {
            val result = rt.exitWorktree(true); denied(result)
            assertTrue(text(result), text(result).contains("uncommitted, untracked, submodule or committed work"))
        }
        val tracked = paths.hostOf(w.path).resolve("tracked.txt")
        Files.writeString(tracked, "changed\n"); refuseDirty()
        assertEquals(w.path, rt.state.value.settings.cwd); assertEquals("changed\n", Files.readString(tracked))
        shell("git checkout -- tracked.txt", w.path)
        Files.writeString(paths.hostOf(w.path).resolve("new.txt"), "new"); refuseDirty()
        shell("git add new.txt && git commit -m child", w.path); refuseDirty()
        assertTrue(Files.exists(paths.hostOf(w.path))); assertEquals(w, rt.state.value.worktree)
        assertEquals("worktree-feature", shell("git branch --show-current", w.path)); truth(rt)
    }

    @Test fun `no git fails without event and hooks may replace git`() = runTest {
        val (_, rt) = runtime(); denied(rt.enterWorktree("outside"))
        assertEquals(cwd, rt.state.value.settings.cwd); assertNull(rt.state.value.worktree)
        assertTrue(text(rt.enterWorktree("outside")).contains("not a git repository"))
        val args = parseArgs(arrayOf("--provider", "wt-test", "--model", "scripted", "--wire", "openai_chat", "--base-url", "http://127.0.0.1:1", "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER", "--worktree", "outside"), repo)
        try { DesktopApp(args, scope, temp.resolve("desktop"), claudeHome = home).openSession(); fail("非 Git 启动必须失败") }
        catch (e: UsageException) { assertEquals(1, e.exitCode); assertTrue(e.message!!.contains("not a git repository")) }
        truth(rt)
    }

    @Test fun `command create takes final ANSI stripped path and remove nonzero after delete succeeds`() = runTest {
        val custom = Files.createDirectories(repo.resolve("custom"))
        val command = "printf 'banner\\n\\033[32mcustom\\033[0m\\n'"
        settings("""{"WorktreeCreate":[{"hooks":[{"type":"command","command":${JsonPrimitive(command)}}]}],"WorktreeRemove":[{"hooks":[{"type":"command","command":${JsonPrimitive("rm -rf -- " + q(paths.guestOfHost(custom).value) + "; exit 1")}}]}]}""")
        val (_, rt) = runtime(); val w = entered(rt)
        assertTrue(w.hookCreated); assertEquals(paths.guestOfHost(custom), w.path)
        assertNull(w.branch); ok(rt.exitWorktree(true)); assertFalse(Files.exists(custom))
        assertEquals(cwd, rt.state.value.settings.cwd)
        val events = store.open(rt.sessionId)!!.read().toList().map { it.event }
        assertTrue(events.filterIsInstance<HooksRan>().any { it.event == "WorktreeCreate" })
        assertTrue(events.filterIsInstance<HooksRan>().any { it.event == "WorktreeRemove" }); truth(rt)
    }

    @Test fun `create command failure missing path absolute dot refuse`() = runTest {
        val custom = Files.createDirectories(repo.resolve("custom"))
        for (command in listOf("echo custom; exit 1", "true", "echo " + q(cwd.value + "/./custom"), "echo missing")) {
            settings("""{"WorktreeCreate":[{"hooks":[{"type":"command","command":${JsonPrimitive(command)}}]}]}""")
            val (_, rt) = runtime(); denied(rt.enterWorktree("bad")); assertEquals(cwd, rt.state.value.settings.cwd); truth(rt)
        }
        assertTrue(Files.isDirectory(custom))
    }

    @Test fun `remove hook failure retains active binding and never falls back to git`() = runTest {
        initRepo()
        settings("""{"WorktreeRemove":[{"hooks":[{"type":"command","command":"exit 1"}]}]}""")
        val (_, rt) = runtime(); val w = entered(rt)
        denied(rt.exitWorktree(true)); assertTrue(Files.exists(paths.hostOf(w.path)))
        assertEquals(w, rt.state.value.worktree); assertEquals(w.path, rt.state.value.settings.cwd)
        assertTrue(shell("git worktree list --porcelain").contains("worktree-feature")); truth(rt)
    }

    @Test fun `HTTP create uses hookSpecificOutput and remove discards JSON control`() = runTest {
        val custom = Files.createDirectories(repo.resolve("http-tree")); val requests = mutableListOf<JsonObject>()
        val server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        servers += server
        server.createContext("/hook") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            synchronized(requests) { requests += request }
            val event = request["hook_event_name"]!!.jsonPrimitive.content
            val body = if (event == "WorktreeCreate") """{"continue":false,"hookSpecificOutput":{"hookEventName":"WorktreeCreate","worktreePath":${JsonPrimitive(paths.guestOfHost(custom).value)}}}"""
                else { custom.toFile().deleteRecursively(); """{"continue":false,"systemMessage":"discarded"}""" }
            val bytes = body.encodeToByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start(); val url = "http://127.0.0.1:${server.address.port}/hook"
        settings("""{"WorktreeCreate":[{"hooks":[{"type":"http","url":"$url"}]}],"WorktreeRemove":[{"hooks":[{"type":"http","url":"$url"}]}]}""")
        val (_, rt) = runtime(); val w = entered(rt)
        assertEquals(paths.guestOfHost(custom), w.path); assertTrue(w.hookCreated)
        assertEquals("feature", requests.first()["name"]!!.jsonPrimitive.content)
        ok(rt.exitWorktree(true)); assertFalse(Files.exists(custom))
        assertEquals(w.path.value, requests.last()["worktree_path"]!!.jsonPrimitive.content)
        assertFalse(rt.state.value.presentation.any { (it.record.event as? Note)?.text == "discarded" }); truth(rt)
    }

    @Test fun `successful remove hook owns cleanup even for builtin git tree`() = runTest {
        initRepo()
        settings("""{"WorktreeRemove":[{"hooks":[{"type":"command","command":"echo '{\"continue\":false,\"systemMessage\":\"discarded\"}'; exit 0"}]}]}""")
        val (_, rt) = runtime(); val w = entered(rt)
        ok(rt.exitWorktree(true))
        assertNull(rt.state.value.worktree); assertEquals(cwd, rt.state.value.settings.cwd)
        assertTrue(rt.state.value.finishedWorktrees.last().removed)
        assertTrue("exit 0 即成功，内核不能替 hook 删除留下的目录", Files.isDirectory(paths.hostOf(w.path)))
        assertTrue(shell("git worktree list --porcelain").contains("worktree-feature"))
        assertFalse(rt.state.value.presentation.any { (it.record.event as? Note)?.text == "discarded" }); truth(rt)
    }

    @Test fun `cwd change updates checkpoint memory hook and survives reopening with stable prefix`() = runTest {
        initRepo()
        Files.writeString(repo.resolve("CLAUDE.md"), "main instructions")
        shell("git add CLAUDE.md && git commit -m memory")
        val hookOutput = """{"continue":false,"systemMessage":"cwd notification","watchPaths":[${JsonPrimitive(cwd.value + "/.claude/worktrees/other/watch.txt") }]}"""
        settings("""{"CwdChanged":[{"hooks":[{"type":"command","command":${JsonPrimitive("cat > " + q(paths.guestOfHost(temp.resolve("cwd-input.json")).value) + "; printf '%s\\n' " + q(hookOutput))}}]}]}""")
        val model = ScriptedModel(reply(TextPart("first")), reply(TextPart("second")))
        val (hub, rt) = runtime(model); rt.send(ClientMsgId("first"), listOf(TextPart("first"))); rt.awaitIdle()
        val firstRoot = rt.state.value.checkpoints.last().event.root
        val w = entered(rt); Files.writeString(paths.hostOf(w.path).resolve("CLAUDE.md"), "new instructions")
        // 再进入另一 worktree 的 path，测试新目录的记忆发现而不改稳定前缀。
        shell("git worktree add -b other ${q(repo.resolve(".claude/worktrees/other").toString())} HEAD")
        val other = GuestPath(cwd.value + "/.claude/worktrees/other")
        Files.writeString(paths.hostOf(other).resolve("CLAUDE.md"), "other instructions")
        ok(rt.enterWorktree(path = other.value))
        rt.send(ClientMsgId("second"), listOf(TextPart("second"))); rt.awaitIdle()
        assertEquals(other, rt.state.value.checkpoints.last().event.root); assertEquals(cwd, firstRoot)
        val oldPoint = rt.state.value.checkpoints.first().event.messageId
        assertTrue(rt.rewind(oldPoint, RewindScope.Code) is RewindOutcome.Refused)
        Files.writeString(paths.hostOf(other).resolve("tracked.txt"), "after")
        val currentPoint = rt.state.value.checkpoints.last().event.messageId
        assertTrue(rt.rewind(currentPoint, RewindScope.Code) is RewindOutcome.Done)
        assertEquals("base\n", Files.readString(paths.hostOf(other).resolve("tracked.txt")))
        assertEquals(model.requests.first().system, model.requests.last().system)
        val requestText = model.requests.last().messages.toString()
        assertTrue(requestText.contains("Working directory changed")); assertTrue(requestText.contains("other instructions"))
        assertTrue("切目录必须执行 CwdChanged hook", Files.exists(temp.resolve("cwd-input.json")))
        val input = Json.parseToJsonElement(Files.readString(temp.resolve("cwd-input.json"))).jsonObject
        assertEquals(w.path.value, input["old_cwd"]!!.jsonPrimitive.content); assertEquals(other.value, input["new_cwd"]!!.jsonPrimitive.content)
        assertEquals(other.value, input["cwd"]!!.jsonPrimitive.content)
        assertEquals(listOf(GuestPath(other.value + "/watch.txt")), rt.state.value.cwdWatchPaths)
        assertTrue(rt.state.value.presentation.any { (it.record.event as? Note)?.text == "cwd notification" })
        truth(rt); hub.close(rt.sessionId); val reopened = hub.open(rt.sessionId)!!
        assertEquals(other, reopened.state.value.settings.cwd)
        assertNotNull("重开必须保留日志中的 worktree binding", reopened.state.value.worktree)
        assertEquals(cwd, reopened.state.value.worktree!!.originalCwd); truth(reopened)
    }

    @Test fun `isolated child retains edits reports branch inherits memory and cannot exit`() = runTest {
        initRepo(); Files.writeString(repo.resolve("CLAUDE.md"), "parent memory")
        val model = ScriptedModel(
            reply(ToolCallPart(CallId("agent"), "Agent", args("prompt" to "work", "subagent_type" to "isolated", "description" to "isolated edits", "run_in_background" to false)), stop = StopReason.ToolUse),
            reply(ToolCallPart(CallId("write"), "Bash", args("command" to "printf child > child.txt")), stop = StopReason.ToolUse),
            reply(TextPart("child done")), reply(TextPart("parent done")),
        )
        Files.writeString(Files.createDirectories(repo.resolve(".claude/agents")).resolve("isolated.md"), "---\nname: isolated\ndescription: isolated\nisolation: worktree\n---\nwork")
        val catalog = AgentCatalog.of(dev.min.agent.ext.config.ClaudeConfigLoader.load(paths.fs, cwd, paths.guestOfHost(home)).agents)
        assertTrue(catalog.definitions.single { it.name == "isolated" }.isolationWorktree)
        assertFalse(catalog.notes.any { it.contains("isolation") })
        val (hub, rt) = runtime(model, catalog.definitions)
        shell("git worktree add -b parent " + q(repo.resolve(".claude/worktrees/parent").toString()) + " HEAD")
        Files.writeString(repo.resolve(".claude/worktrees/parent/CLAUDE.md"), "directory parent memory")
        ok(rt.enterWorktree(path = cwd.value + "/.claude/worktrees/parent"))
        rt.send(ClientMsgId("spawn"), listOf(TextPart("spawn")));
        rt.awaitIdle()
        val child = hub.get(rt.state.value.children.keys.single())!!; val w = child.state.value.worktree!!
        assertTrue(w.pinned); assertTrue(w.owned); assertNotEquals(cwd, w.path)
        assertTrue(Files.exists(paths.hostOf(w.path).resolve("child.txt"))); assertFalse(Files.exists(repo.resolve("child.txt")))
        assertTrue(child.state.value.memory.any { it.content.contains("parent memory") })
        assertTrue(child.state.value.memory.any { it.content == "directory parent memory" })
        assertFalse(model.requests[1].tools.any { it.name == "ExitWorktree" })
        denied(child.exitWorktree()); assertEquals(w.path, child.state.value.settings.cwd)
        assertFalse(child.state.value.finishedWorktrees.last().removed)
        val report = text(rt.state.value.toolResults[CallId("agent")]!!)
        assertTrue(report.contains(w.path.value)); assertTrue(report.contains(w.branch!!)); assertTrue(report.contains("retained"))
        truth(child); truth(rt)
    }

    @Test fun `clean isolated child is removed and main writes and git redirects are rejected even bypass`() = runTest {
        initRepo()
        val model = ScriptedModel(
            reply(ToolCallPart(CallId("agent"), "Agent", args("prompt" to "work", "subagent_type" to "isolated", "description" to "isolation", "run_in_background" to false)), stop = StopReason.ToolUse),
            reply(ToolCallPart(CallId("escape"), "Bash", args("command" to "git -C ${q(cwd.value)} status")), stop = StopReason.ToolUse),
            reply(TextPart("child done")), reply(TextPart("parent done")),
        )
        val (hub, rt) = runtime(model, BuiltinAgents.all + AgentDefinition("isolated", "isolated", "work", isolationWorktree = true))
        rt.send(ClientMsgId("spawn"), listOf(TextPart("spawn")));
        rt.awaitIdle()
        val child = hub.get(rt.state.value.children.keys.single())!!; val w = child.state.value.worktree!!
        assertTrue(text(child.state.value.toolResults[CallId("escape")]!!).contains("Worktree isolation"))
        assertTrue(child.state.value.toolResults[CallId("escape")]!!.isError)
        assertTrue(child.state.value.finishedWorktrees.last().removed); assertFalse(Files.exists(paths.hostOf(w.path)))
        assertEquals("", shell("git branch --list ${q(w.branch!!)}"))
        assertTrue(text(rt.state.value.toolResults[CallId("agent")]!!).contains("removed (no changes)")); truth(child); truth(rt)
    }

    @Test fun `CLI worktree optional name aliases and tool permissions are nonreadonly`() {
        val base = arrayOf("--model", "test")
        for (flag in listOf("--worktree", "-w")) {
            val unnamed = parseArgs(base + flag + arrayOf("-p", "hello"), repo)
            assertTrue(unnamed.worktree); assertNull(unnamed.worktreeName); assertEquals("hello", unnamed.prompt)
            assertEquals("feature", parseArgs(base + arrayOf(flag, "feature"), repo).worktreeName)
        }
        assertFalse(EnterWorktreeTool.traits.readOnly); assertFalse(ExitWorktreeTool.traits.readOnly)
        assertFalse(EnterWorktreeTool.traits.concurrencySafe)
        assertEquals(ResumePolicy.Unknown, EnterWorktreeTool.traits.resumePolicy)
    }

    @Test fun `existing external same repo accepts but foreign main and absolute dots refuse`() = runTest {
        initRepo()
        val external = temp.resolve("external")
        shell("git worktree add -b external " + q(external.toString()) + " HEAD")
        val (hub, rt) = runtime()
        denied(rt.enterWorktree(path = cwd.value))
        denied(rt.enterWorktree(path = cwd.value + "/./external"))
        ok(rt.enterWorktree(path = paths.guestOfHost(external).value))
        assertFalse(rt.state.value.worktree!!.owned)
        ok(rt.exitWorktree())
        val foreign = Files.createDirectories(temp.resolve("foreign")); val f = paths.guestOfHost(foreign)
        shell("git init -b main && git config user.email t@example.invalid && git config user.name T && git commit --allow-empty -m initial", f)
        val other = temp.resolve("foreign-linked")
        shell("git worktree add -b foreign " + q(other.toString()) + " HEAD", f)
        denied(rt.enterWorktree(path = paths.guestOfHost(other).value))
        val missing = entered(rt, "missing")
        shell("git worktree remove -- " + q(paths.hostOf(missing.path).toString()))
        truth(rt); hub.close(rt.sessionId); val reopened = hub.open(rt.sessionId)!!
        assertNull(reopened.state.value.worktree); assertEquals(cwd, reopened.state.value.settings.cwd); truth(reopened)
    }

    @Test fun `real symlink or junction below repository cannot masquerade as hook tree`() = runTest {
        val target = Files.createDirectories(temp.resolve("target")); val link = repo.resolve("link")
        if (System.getProperty("os.name").startsWith("Windows")) {
            fun ps(p: Path) = "'" + p.toString().replace("'", "''") + "'"
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "New-Item -ItemType Junction -Path " + ps(link) + " -Target " + ps(target) + " | Out-Null").redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().decodeToString()
            assertEquals(output, 0, process.waitFor())
        } else Files.createSymbolicLink(link, target)
        try {
            settings("""{"WorktreeCreate":[{"hooks":[{"type":"command","command":"echo link"}]}]}""")
            val (_, rt) = runtime(); denied(rt.enterWorktree("linked"))
            assertEquals(cwd, rt.state.value.settings.cwd); assertNull(rt.state.value.worktree); assertTrue(Files.exists(target)); truth(rt)
        } finally { Files.delete(link) }
    }

    @Test fun `desktop CLI launch resume headless keep named prompt and unnamed clean cleanup`() = runTest {
        initRepo()
        val base = parseArgs(arrayOf("--provider", "wt-test", "--model", "scripted", "--wire", "openai_chat", "--base-url", "http://127.0.0.1:1", "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER", "--worktree", "cli"), repo)
        val app = DesktopApp(base, scope, temp.resolve("desktop"), claudeHome = home)
        val rt = app.openSession(); assertNotNull("CLI 请求隔离时，启动必须公布 binding", rt.state.value.worktree); val w = rt.state.value.worktree!!
        assertEquals("worktree-cli", w.branch); assertTrue(Files.exists(paths.hostOf(w.path)))
        app.exitSession(rt).join()
        val resumed = DesktopApp(base.copy(resume = rt.sessionId.value), scope, temp.resolve("desktop"), claudeHome = home)
        val reopened = resumed.openSession(); assertEquals(w, reopened.state.value.worktree)
        val out = java.io.ByteArrayOutputStream(); val ps = java.io.PrintStream(out)
        val headless = DesktopApp(base.copy(prompt = "hello"), scope, temp.resolve("desktop"), claudeHome = home)
        headless.cleanupWorktree(reopened, java.io.BufferedReader(java.io.StringReader("remove")), ps)
        assertEquals(w, reopened.state.value.worktree); assertEquals("", out.toString())
        reopened.rename("named")
        resumed.cleanupWorktree(reopened, java.io.BufferedReader(java.io.StringReader("")), ps)
        assertNull(reopened.state.value.worktree); assertTrue(Files.exists(paths.hostOf(w.path))); assertTrue(out.toString().contains("输入 remove"))
        ok(reopened.enterWorktree(path = w.path.value)); ok(reopened.exitWorktree(true))
        assertFalse(Files.exists(paths.hostOf(w.path)))
        val autoApp = DesktopApp(base.copy(worktreeName = "auto"), scope, temp.resolve("desktop"), claudeHome = home)
        val automatic = autoApp.openSession(); val automaticPath = automatic.state.value.worktree!!.path
        out.reset(); autoApp.cleanupWorktree(automatic, java.io.BufferedReader(java.io.StringReader("")), ps)
        assertFalse(Files.exists(paths.hostOf(automaticPath))); assertNull(automatic.state.value.worktree); assertEquals("", out.toString())
        autoApp.exitSession(automatic).join()
        resumed.exitSession(reopened).join()
        val records = resumed.records(reopened.sessionId, 0)
        assertEquals(fold(recordsHeader(temp.resolve("desktop"), reopened.sessionId), records), reopened.state.value)
    }

    private suspend fun recordsHeader(dir: Path, id: SessionId) = FileSessionStore(dir.resolve("sessions"), { 0L }).open(id)!!.header

    @Test fun `checkout suppresses repository hooks and filter drivers`() = runTest {
        initRepo()
        Files.writeString(repo.resolve(".gitattributes"), "tracked.txt filter=trap\n")
        shell("git add .gitattributes && git commit -m attributes")
        val marker = paths.guestOfHost(temp.resolve("executed")).value
        val bad = "touch " + q(marker) + "; cat"
        shell("git config filter.trap.smudge " + q(bad) + " && git config filter.trap.required true")
        Files.writeString(repo.resolve(".git/hooks/post-checkout"), "#!/bin/sh\ntouch " + q(marker) + "\n")
        shell("chmod +x .git/hooks/post-checkout")
        val (_, rt) = runtime(); val w = entered(rt, "filters")
        assertFalse(Files.exists(temp.resolve("executed"))); assertEquals("base\n", Files.readString(paths.hostOf(w.path).resolve("tracked.txt")))
        ok(rt.exitWorktree(true)); truth(rt)
    }

    @Test fun `HTTP creation fails on transport missing path and wrong event without switching`() = runTest {
        val custom = Files.createDirectories(repo.resolve("custom"))
        val server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0); servers += server
        var status = 500; var response = "failure"
        server.createContext("/hook") { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = response.encodeToByteArray(); exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }; server.start()
        val url = "http://127.0.0.1:${server.address.port}/hook"
        settings("""{"WorktreeCreate":[{"hooks":[{"type":"http","url":"$url"}]}]}""")
        val (_, rt) = runtime()
        denied(rt.enterWorktree("bad")); assertNull(rt.state.value.worktree)
        status = 200; response = """{"hookSpecificOutput":{"hookEventName":"WorktreeCreate"}}"""
        denied(rt.enterWorktree("bad")); assertNull(rt.state.value.worktree)
        response = """{"hookSpecificOutput":{"hookEventName":"CwdChanged","worktreePath":${JsonPrimitive(paths.guestOfHost(custom).value)}}}"""
        denied(rt.enterWorktree("bad")); assertEquals(cwd, rt.state.value.settings.cwd); truth(rt)
    }

    @Test fun `model tools switch cwd before following call and remind the immediate next request`() = runTest {
        initRepo()
        val model = ScriptedModel(
            reply(ToolCallPart(CallId("enter"), "EnterWorktree", args("name" to "tools")), ToolCallPart(CallId("write"), "Bash", args("command" to "printf inside > inside.txt")), stop = StopReason.ToolUse),
            reply(ToolCallPart(CallId("exit"), "ExitWorktree", args("action" to "keep")), stop = StopReason.ToolUse),
            reply(TextPart("done")),
        )
        val (_, rt) = runtime(model); rt.send(ClientMsgId("tools"), listOf(TextPart("isolate"))); rt.awaitIdle()
        assertFalse(Files.exists(repo.resolve("inside.txt"))); assertEquals("inside", Files.readString(repo.resolve(".claude/worktrees/tools/inside.txt")))
        assertEquals(cwd, rt.state.value.settings.cwd); assertNull(rt.state.value.worktree)
        assertEquals(model.requests[0].system, model.requests[2].system)
        assertTrue(model.requests[1].messages.toString().contains("Working directory changed"))
        assertTrue(model.requests[1].messages.toString().contains(".claude/worktrees/tools"))
        assertTrue(model.requests[2].messages.toString().contains("Working directory changed from"))
        for (call in listOf("enter", "write", "exit")) assertFalse(text(rt.state.value.toolResults[CallId(call)]!!), rt.state.value.toolResults[CallId(call)]!!.isError)
        truth(rt)
    }

    private fun settings(hooks: String) {
        Files.writeString(Files.createDirectories(home.resolve(".claude")).resolve("settings.json"), """{"hooks":$hooks,"autoMemoryEnabled":false}""")
    }
}
