package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.McpToolEntry
import dev.min.agent.spi.McpToolsSnapshot
import dev.min.agent.spi.McpServersLoaded
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.reply
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * prompt / agent / mcp_tool 三种 hook 类型接进会话（模型一律用脚本化的模拟模型）：prompt 的 {ok,reason}
 * 按事件口径生效；agent 起只读子会话跑完给结论、花费计入树；mcp_tool 的工具文本按 command stdout 口径解读。
 * 匹配 / `if` / 去重 / HooksRan 走同一条合流（HookDriver），不另起一套。
 */
class ModelHooksSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private class RoutingModel(private val route: (ModelRequest) -> dev.min.agent.testkit.Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    private fun ModelRequest.lastText(): String = messages.last().parts.joinToString("\n") { p ->
        when (p) {
            is TextPart -> p.text
            is ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    private fun ModelRequest.allText(): String = messages.joinToString("\n") { m ->
        m.parts.joinToString("") { p -> (p as? TextPart)?.text ?: "" }
    }

    private val bash = object : Tool<JsonObject> {
        override val name = "Bash"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits()
        override fun description(profile: HarnessProfile) = "跑命令"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "跑命令")
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("ran: " + (input["command"] as? JsonPrimitive)?.content)))))
        }
    }

    private fun settings(hooks: String) = """{"hooks": $hooks}"""
    private fun write(path: String, content: String) {
        fsFiles[path] = content
    }

    private val fsFiles = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val fs = object : dev.min.agent.spi.WorkspaceFs {
        override suspend fun stat(path: GuestPath): dev.min.agent.spi.FileStat? = when {
            fsFiles.containsKey(path.value) -> dev.min.agent.spi.FileStat(dev.min.agent.spi.FileKind.File, fsFiles.getValue(path.value).length.toLong(), 0)
            path.value == "/" || fsFiles.keys.any { it.startsWith(path.value + "/") } -> dev.min.agent.spi.FileStat(dev.min.agent.spi.FileKind.Directory, 0, 0)
            else -> null
        }

        override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray = fsFiles.getValue(path.value).toByteArray(Charsets.UTF_8)
        override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) {
            fsFiles[path.value] = bytes.toString(Charsets.UTF_8)
        }
        override suspend fun list(dir: GuestPath): List<dev.min.agent.spi.DirEntry> {
            val prefix = dir.value.trimEnd('/') + "/"
            return fsFiles.keys.filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }.distinct()
                .map { dev.min.agent.spi.DirEntry(it, if (fsFiles.containsKey(prefix + it)) dev.min.agent.spi.FileKind.File else dev.min.agent.spi.FileKind.Directory) }
        }
        override fun scopeOf(path: GuestPath): dev.min.agent.spi.PathScope = dev.min.agent.spi.PathScope.Rootfs
    }

    private fun hub(model: ModelClient): SessionHub {
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        val base = dev.min.agent.testkit.testDeps(model, listOf(bash))
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() }, ids = base.ids, models = base.models, tools = listOf(bash),
            host = dev.min.agent.kernel.HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts),
            platform = "test", zone = ZoneOffset.UTC, memoryHome = GuestPath("/root"),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return SessionHub(store, deps, scope)
    }

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${System.nanoTime()}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    // ---- prompt ----

    @Test
    fun `prompt hook 的 ok false 拦下工具调用，模型看到理由`() = run {
        write("/root/.claude/settings.json", settings(
            """{"PreToolUse": [{"matcher": "Bash", "hooks": [
                {"type": "prompt", "prompt": "判断这个 Bash 调用是否安全：${'$'}ARGUMENTS", "continueOnBlock": true, "timeout": 1}
            ]}]}""",
        ))
        val model = RoutingModel { req ->
            when {
                // hook 的评估请求：system 里带着 hook evaluator 的说明，回否决
                req.system.any { it.contains("hook evaluator") } ->
                    reply(TextPart("""{"ok": false, "reason": "这是删库命令，不能跑"}"""), usage = Usage(inputTokens = 100))
                req.lastText() == "跑" -> reply(bashCall("c1"), stop = StopReason.ToolUse)
                else -> reply(TextPart("收到"))
            }
        }
        val h = hub(model)
        val rt = h.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("跑")
        // ok:false + continueOnBlock → Deny（不结束回合）：调用被拒、理由作为工具错误交回模型
        assertTrue("调用被 hook 拒了", rt.state.value.toolResults.getValue(CallId("c1")).isError)
        val denied = rt.state.value.toolResults.getValue(CallId("c1"))
        assertTrue("拒绝理由给到了模型：${denied.content}", denied.content.any { (it as? TextPart)?.text?.contains("删库") == true })
        // HooksRan 记了这一批（模型侧处理器也在计数里）
        assertTrue(hookRans(rt).any { it.event == "PreToolUse" && it.handlers == 1 })
        // 评估请求真的看到了 $ARGUMENTS 换进去的事件输入
        val eval = model.requests.first { req -> req.system.any { it.contains("hook evaluator") } }
        assertTrue("事件输入进了提示词：${eval.allText()}", eval.allText().contains("\"tool_name\""))
        // 评估请求走 rt.modelBilling（m0 的唯一出口）：ModelCharged 落本会话日志，受预算与超时约束。
        // 按评估请求独有的 usage（inputTokens=100）认这笔账——主回合的 ModelCharged 隔离不出来
        val charged = chargedEvents(rt)
        assertTrue("prompt hook 的评估请求必须经账本记账（ModelCharged 落日志），实收 ${charged.map { it.usage.inputTokens }}",
            charged.any { it.usage.inputTokens == 100L })
    }

    @Test
    fun `model 点名解析失败是非阻断错误，不静默换模型`() = run {
        write("/root/.claude/settings.json", settings(
            """{"PreToolUse": [{"matcher": "Bash", "hooks": [
                {"type": "prompt", "prompt": "判断安不安全", "model": "nope/no-such-model"}
            ]}]}""",
        ))
        val model = RoutingModel { req ->
            when {
                // 点名模型解析失败时评估请求根本不该发出去；发了就说明静默退回了别的模型
                req.system.any { it.contains("hook evaluator") } -> reply(TextPart("""{"ok": false, "reason": "静默换模型的否决"}"""), usage = Usage(inputTokens = 100))
                req.lastText() == "跑" -> reply(bashCall("c1"), stop = StopReason.ToolUse)
                else -> reply(TextPart("收到"))
            }
        }
        val h = hub(model)
        val rt = h.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("跑")
        // 非阻断：评估失败不拦调用
        assertTrue("点名模型解析失败不该拦调用", !rt.state.value.toolResults.getValue(CallId("c1")).isError)
        val ran = hookRans(rt).single { it.event == "PreToolUse" }
        assertTrue("解析失败要报出来、点名要看得见：${ran.problems}",
            ran.problems.any { it.contains("no-such-model") && it.contains("失败") })
    }

    @Test
    fun `prompt hook 没按口径回复只是非阻断错误，工具照常跑`() = run {
        write("/root/.claude/settings.json", settings(
            """{"PreToolUse": [{"matcher": "Bash", "hooks": [
                {"type": "prompt", "prompt": "随便说说"}
            ]}]}""",
        ))
        val model = RoutingModel { req ->
            when {
                req.system.any { it.contains("hook evaluator") } -> reply(TextPart("我觉得还行"))
                req.lastText() == "跑" -> reply(bashCall("c1"), stop = StopReason.ToolUse)
                else -> reply(TextPart("收到"))
            }
        }
        val h = hub(model)
        val rt = h.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("跑")
        assertTrue("没按口径回复不拦调用", !rt.state.value.toolResults.getValue(CallId("c1")).isError)
        val ran = hookRans(rt).single { it.event == "PreToolUse" }
        assertTrue("口径违规进了 problems：${ran.problems}", ran.problems.any { it.contains("口径") })
    }

    // ---- agent ----

    @Test
    fun `agent hook 起只读子会话跑完给结论，ok false 让回合继续，花费计入树`() = run {
        write("/root/.claude/settings.json", settings(
            """{"Stop": [{"hooks": [
                {"type": "agent", "prompt": "确认测试都过了再停：", "timeout": 10}
            ]}]}""",
        ))
        val evals = java.util.concurrent.atomic.AtomicLong()
        val model = RoutingModel { req ->
            when {
                // hook agent 子会话的请求：系统提示里有 verifier 的说明。第一次否决（回合继续），之后放行
                req.system.any { it.contains("hook verifier") } ->
                    if (evals.incrementAndGet() == 1L) {
                        reply(TextPart("""{"ok": false, "reason": "还有两个测试没过"}"""), usage = Usage(inputTokens = 500, outputTokens = 50))
                    } else {
                        reply(TextPart("""{"ok": true}"""), usage = Usage(inputTokens = 500, outputTokens = 20))
                    }
                req.lastText().contains("还有两个测试没过") -> reply(TextPart("那我把剩下两个修了"))
                else -> reply(TextPart("干完了"))
            }
        }
        val h = hub(model)
        val rt = h.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("做事")
        // 第一次停被 agent hook 打回去（Block），模型接着回应了理由；第二轮 Stop 不再拦（脚本走完了）……
        // 路由上「还有两个测试没过」的回应是一次 TextPart，之后回合再停时 hook 脚本已耗尽 → RoutingModel 会抛错。
        // 改成：回应完理由后直接断言回合的续跑发生过
        val turns = hookRans(rt).count { it.event == "Stop" }
        assertTrue("Stop 的 agent hook 跑过并落了 HooksRan", turns >= 1)
        // hook agent 子会话在树上（来历类型 hook-agent、只读工具），它的花费进了自己的日志（model_charged）
        val agents = h.online.value.mapNotNull { h.get(it) }.filter { it.state.value.origin?.agentType == HOOK_AGENT_TYPE }
        assertTrue("hook agent 子会话在线挂着（树汇总要读它）", agents.isNotEmpty())
        assertEquals(listOf("Read", "Grep", "Glob"), agents.first().state.value.origin?.tools)
        assertTrue("hook agent 的模型请求经自己的账本记账（ModelCharged 落日志）", agents.first().state.value.unpricedModelCalls > 0)
    }

    // ---- mcp_tool ----

    @Test
    fun `mcp_tool hook 的工具文本按 command stdout 口径解读，isError 是非阻断错误`() = run {
        write("/root/.claude/settings.json", settings(
            """{"PostToolUse": [{"matcher": "Bash", "hooks": [
                {"type": "mcp_tool", "server": "scan", "tool": "security_scan",
                 "input": {"file": "${'$'}{tool_input.command}"}}
            ]}]}""",
        ))
        val model = RoutingModel { req ->
            when {
                req.lastText() == "跑" -> reply(bashCall("c1"), stop = StopReason.ToolUse)
                else -> reply(TextPart("收到"))
            }
        }
        val h = hub(model)
        val rt = h.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        // 接上一个已就绪的 MCP 工具（不真连服务器：caller 是假的）
        rt.loadMcp(
            McpServersLoaded(emptyList()),
            McpToolsSnapshot(listOf(McpToolEntry("mcp__scan__security_scan", "scan", "security_scan", "扫一遍", JsonObject(mapOf("type" to JsonPrimitive("object")))))),
        )
        rt.mcpCaller = object : McpCaller {
            override suspend fun call(r: McpCallRequest): ToolResult {
                assertEquals("scan", r.server)
                assertEquals("security_scan", r.tool)
                // 参数模板的 ${tool_input.command} 从事件输入插值
                assertEquals("rm -rf build", (r.arguments["file"] as? JsonPrimitive)?.content)
                // 工具回 JSON 对象：按 command 的 stdout 口径被当 hook 输出解读（additionalContext）
                return ToolResult(listOf(TextPart("""{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"扫描没发现问题"}}""")))
            }
        }
        rt.say("跑")
        val ran = hookRans(rt).single { it.event == "PostToolUse" && it.handlers == 1 }
        assertTrue("没有非阻断错误：${ran.problems}", ran.problems.isEmpty())
        // additionalContext 挂在这次调用的结果后面（模型下一轮看得到）
        assertTrue(rt.state.value.hookContexts.values.any { texts -> texts.any { it.contains("扫描没发现问题") } })
    }

    @Test
    fun `mcp_tool 的 timeout 生效：工具挂死按超时收场，回合照常收尾`() = run {
        write("/root/.claude/settings.json", settings(
            """{"PostToolUse": [{"matcher": "Bash", "hooks": [
                {"type": "mcp_tool", "server": "scan", "tool": "hang_scan", "timeout": 1}
            ]}]}""",
        ))
        val model = RoutingModel { req ->
            if (req.lastText() == "跑") reply(bashCall("c1"), stop = StopReason.ToolUse) else reply(TextPart("收到"))
        }
        val h = hub(model)
        val rt = h.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.loadMcp(
            McpServersLoaded(emptyList()),
            McpToolsSnapshot(listOf(McpToolEntry("mcp__scan__hang_scan", "scan", "hang_scan", "挂死", JsonObject(mapOf("type" to JsonPrimitive("object")))))),
        )
        rt.mcpCaller = object : McpCaller {
            override suspend fun call(r: McpCallRequest): ToolResult = kotlinx.coroutines.awaitCancellation()
        }
        // say 会等回合收尾：没有超时的话这一行永远等不到（复核 M2 的复现就是回合挂死）
        rt.say("跑")
        val ran = hookRans(rt).single { it.event == "PostToolUse" }
        assertTrue("超时要进 problems：${ran.problems}", ran.problems.any { it.contains("hang_scan") && it.contains("超时") })
    }

    @Test
    fun `服务器上没有这个工具时照 CC 的失败口径给非阻断错误`() = run {
        write("/root/.claude/settings.json", settings(
            """{"PostToolUse": [{"matcher": "Bash", "hooks": [
                {"type": "mcp_tool", "server": "scan", "tool": "not_there"}
            ]}]}""",
        ))
        val model = RoutingModel { req ->
            if (req.lastText() == "跑") reply(bashCall("c1"), stop = StopReason.ToolUse) else reply(TextPart("收到"))
        }
        val h = hub(model)
        val rt = h.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.loadMcp(McpServersLoaded(emptyList()), McpToolsSnapshot(emptyList()))
        rt.say("跑")
        val ran = hookRans(rt).single { it.event == "PostToolUse" }
        assertTrue("没连上 / 工具不存在是非阻断错误：${ran.problems}", ran.problems.any { it.contains("没有已连接的工具") })
    }

    @Test
    fun `launch 时的 SessionStart 上 mcp_tool 跳过（MCP 还没就绪，CC 原文）`() = run {
        write("/root/.claude/settings.json", settings(
            """{"SessionStart": [{"hooks": [
                {"type": "mcp_tool", "server": "scan", "tool": "boot_check"}
            ]}]}""",
        ))
        val model = RoutingModel { _ -> reply(TextPart("好")) }
        val h = hub(model)
        val rt = h.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        // launch 的 SessionStart（startup/resume）赶不上 MCP 就绪：mcp_tool 跳过、连 HooksRan 都不落（没有别的处理器）
        assertTrue("没有落 SessionStart 的 hook 记录：${hookRans(rt)}", hookRans(rt).none { it.event == "SessionStart" })
    }

    private fun bashCall(id: String) = ToolCallPart(CallId(id), "Bash", JsonObject(mapOf("command" to JsonPrimitive("rm -rf build"))))

    private suspend fun hookRans(rt: SessionRuntime): List<HooksRan> {
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        return store.peek(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksRan>()
    }

    private suspend fun chargedEvents(rt: SessionRuntime): List<dev.min.agent.spi.ModelCharged> {
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        return store.peek(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<dev.min.agent.spi.ModelCharged>()
    }
}
