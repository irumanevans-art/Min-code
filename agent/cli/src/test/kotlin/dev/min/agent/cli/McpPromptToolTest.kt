package dev.min.agent.cli

import dev.min.agent.spi.ApprovalChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * `--permission-prompt-tool mcp__服务器__工具`（CLI reference：「Specify an MCP tool to handle permission prompts in non-interactive
 * mode」）：要审批的调用经这个 MCP 工具问，结果是 PermissionResult（`{"behavior":"allow"|"deny",…}`，放在文本内容块里）。
 * 原文还规定了：工具不在已连接的 MCP 工具里 → 报 `Error: MCP tool <名字> (passed via --permission-prompt-tool) not found.
 * Available MCP tools: …`、退出码 1（errors 页）；标了 `requiresUserInteraction` 的 MCP 工具，提示工具回 allow 也转成拒绝，
 * 文案 `MCP tool requires user interaction; not supported via --permission-prompt-tool`（MCP 页）；`--permission-prompts none` 不问它。
 *
 * 结论的解析是安全判定：工具报错 / 不是 JSON / 缺 behavior / 未知 behavior / 改写入参 / 找不到工具，一律不放行（变异过）。
 * 端到端用真的 node 进程当 MCP 服务器（resources/mcp/perm-server.cjs），没有 node 就跳过。
 */
class McpPromptToolTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private fun run(block: suspend () -> Unit) { runBlocking { withTimeout(300_000) { block() } } }

    private fun guest(p: Path): String = p.toString().let { if (it.length > 2 && it[1] == ':') "/" + it[0].lowercaseChar() + it.substring(2).replace('\\', '/') else it }

    // ---- 结论解析（纯函数）----

    private val original = Json.parseToJsonElement("""{"file_path":"/x","content":"y"}""").jsonObject

    private fun verdict(text: String, isError: Boolean = false) = decideMcpResult(isError, text, original).choice

    @Test fun `only a well formed allow in a successful tool result is an approval`() {
        assertEquals(ApprovalChoice.Once, verdict("""{"behavior":"allow"}"""))
        assertEquals(ApprovalChoice.Once, verdict("""{"behavior":"allow","updatedInput":{"file_path":"/x","content":"y"}}"""))
        // 不放行：工具自己报错（即便文本看着像批准）、不是 JSON、不是对象、缺 behavior、未知 behavior、改写入参、deny
        assertFalse(verdict("""{"behavior":"allow"}""", isError = true) == ApprovalChoice.Once)
        assertFalse(verdict("definitely not json") == ApprovalChoice.Once)
        assertFalse(verdict("""["allow"]""") == ApprovalChoice.Once)
        assertFalse(verdict("""{"updatedInput":{}}""") == ApprovalChoice.Once)
        assertFalse(verdict("""{"behavior":"yes"}""") == ApprovalChoice.Once)
        assertFalse(verdict("""{"behavior":"allow","updatedInput":{"file_path":"/other","content":"y"}}""") == ApprovalChoice.Once)
        assertFalse(verdict("") == ApprovalChoice.Once)
        val deny = verdict("""{"behavior":"deny","message":"no"}""") as ApprovalChoice.Deny
        assertEquals("no", deny.note)
        assertTrue(deny.fromHost)
    }

    // ---- 参数 ----

    private fun parse(vararg extra: String) = parseArgs(arrayOf("--model", "m") + extra, tmp.root.toPath())

    @Test fun `an MCP tool name is accepted in print mode and a malformed one is not`() {
        assertEquals("mcp__perm__approve", parse("-p", "hi", "--permission-prompt-tool", "mcp__perm__approve").permissionPromptTool)
        assertThrows(UsageException::class.java) { parse("-p", "hi", "--permission-prompt-tool", "approve") }
        assertThrows(UsageException::class.java) { parse("-p", "hi", "--permission-prompt-tool", "mcp__onlyserver") }
        assertThrows(UsageException::class.java) { parse("--permission-prompt-tool", "mcp__perm__approve") }
    }

    // ---- 端到端（真 node 进程）----

    private class Setup(val work: Path, val calls: Path, val consentMark: Path)

    private fun prepareFor(name: String, setup: (Setup) -> Unit = {}): (Path) -> Unit = { work ->
        val script = work.resolve("perm-server.cjs")
        Files.write(script, javaClass.getResourceAsStream("/mcp/perm-server.cjs")!!.use { it.readBytes() })
        val calls = work.resolve("calls.log")
        val mark = work.resolve("consent.mark")
        fun fwd(p: Path) = p.toString().replace('\\', '/')
        Files.writeString(
            work.resolve(".mcp.json"),
            """{"mcpServers":{"perm":{"command":"node","args":["${fwd(script)}"],"env":{"CALLS_LOG":"${fwd(calls)}","CONSENT_MARK":"${fwd(mark)}"}}}}""",
        )
        // 项目 .mcp.json 的服务器要先批准（enabledMcpjsonServers）才会启动
        Files.createDirectories(work.resolve(".claude"))
        Files.writeString(work.resolve(".claude/settings.local.json"), """{"enabledMcpjsonServers":["perm"]}""")
        setup(Setup(work, calls, mark))
    }

    private val prompt = listOf("--mode", "default", "--trust", "--permission-prompt-tool", "mcp__perm__approve")

    private fun nodeAvailable() {
        val ok = try { ProcessBuilder("node", "--version").start().waitFor() == 0 } catch (_: java.io.IOException) { false }
        assumeTrue("本机没有 node，跳过 MCP 权限提示工具的真进程验收。", ok)
    }

    private fun writeTo(file: Path, id: String = "w") = buildJsonObject { put("file_path", guest(file)); put("content", "x") }

    private fun runWith(name: String, extra: List<String>, route: (String) -> String?, prepare: (Path) -> Unit = prepareFor(name)): StreamOut = runBlocking {
        withTimeout(300_000) {
            ModelServer(route).use { server ->
                startLive(tmp.root.toPath(), scope(), name, server, extra, prepare).finish()
            }
        }
    }

    @Test fun `an approval asked through the MCP tool runs the tool when the tool allows`() {
        nodeAvailable()
        val target = tmp.newFolder("allow").toPath().resolve("allowme.txt")
        val out = runWith("allow", listOf("-p", "T_WRITE") + prompt, { last ->
            if (last.contains("T_WRITE")) toolResponse("call_m", "Write", writeTo(target)) else textResponse("MCP_ALLOWED")
        })
        assertEquals(out.stdout + out.stderr, 0, out.exit)
        assertTrue("批准之后工具应当执行", Files.exists(target))
        // 提示工具收到的是 {tool_name, input, tool_use_id}
        val calls = Files.readAllLines(tmp.root.toPath().resolve("allow-work/calls.log")).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(1, calls.size)
        assertEquals("Write", calls[0].str("tool_name"))
        assertEquals("call_m", calls[0].str("tool_use_id"))
        assertEquals(guest(target), calls[0]["input"]!!.jsonObject.str("file_path"))
    }

    @Test fun `the MCP tool denying keeps the tool from running and its message reaches the model`() {
        nodeAvailable()
        val target = tmp.newFolder("deny").toPath().resolve("plain.txt")
        val server = ModelServer { last ->
            if (last.contains("T_WRITE")) toolResponse("call_d", "Write", writeTo(target)) else textResponse("MCP_DENIED")
        }
        server.use {
            runBlocking {
                withTimeout(300_000) {
                    val out = startLive(tmp.root.toPath(), scope(), "deny", it, listOf("-p", "T_WRITE") + prompt, prepareFor("deny")).finish()
                    assertFalse(Files.exists(target))
                    assertTrue(it.requests.last().getValue("messages").toString().takeLast(1200) + out.stderr, it.requests.last().toString().contains("nope from mcp"))
                    assertEquals(0, out.exit)
                }
            }
        }
    }

    @Test fun `a broken or erroring prompt tool answer is a denial, never an approval`() {
        nodeAvailable()
        val names = listOf("garbage", "nobehavior", "erroring", "rewrite")
        val files = names.map { tmp.newFolder("bad-$it").toPath().resolve("$it.txt") }
        val out = runWith("broken", listOf("-p", "T_FOUR") + prompt, { last ->
            if (last.contains("T_FOUR")) toolsResponse(*files.mapIndexed { i, f -> Triple("call_$i", "Write", writeTo(f)) }.toTypedArray()) else textResponse("BROKEN_DONE")
        })
        files.forEach { assertFalse("$it 不该被写出来", Files.exists(it)) }
        assertEquals(0, out.exit)
    }

    @Test fun `a missing prompt tool ends the run with the documented error and exit code 1`() {
        nodeAvailable()
        val target = tmp.newFolder("missing").toPath().resolve("allowme.txt")
        val out = runWith("missing", listOf("-p", "T_WRITE", "--mode", "default", "--trust", "--permission-prompt-tool", "mcp__perm__nothere"), { last ->
            if (last.contains("T_WRITE")) toolResponse("call_x", "Write", writeTo(target)) else textResponse("SHOULD_NOT_REACH")
        })
        assertEquals(1, out.exit)
        assertTrue(out.stderr, out.stderr.contains("Error: MCP tool mcp__perm__nothere (passed via --permission-prompt-tool) not found. Available MCP tools: "))
        assertTrue("列出已连接的 MCP 工具", out.stderr.contains("mcp__perm__approve"))
        assertFalse("找不到提示工具不能退化成放行", Files.exists(target))
        assertEquals("出错退出，不再发第二个请求", 1, out.requests.size)
    }

    @Test fun `an allow for a tool that requires user interaction is converted to a denial`() {
        nodeAvailable()
        val out = runWith("interaction", listOf("-p", "T_CONSENT") + prompt, { last ->
            if (last.contains("T_CONSENT")) toolResponse("call_c", "mcp__perm__consent", buildJsonObject {}) else textResponse("CONSENT_DONE")
        })
        // 提示工具对 consent 回了 allow，但它标了 requiresUserInteraction：不能经 --permission-prompt-tool 批准（MCP 页原文）
        assertFalse("consent 工具不该被真的调用", Files.exists(tmp.root.toPath().resolve("interaction-work/consent.mark")))
        assertTrue(out.requests.last().toString().contains("MCP tool requires user interaction; not supported via --permission-prompt-tool"))
        assertEquals(0, out.exit)
    }

    @Test fun `permission-prompts none never consults the MCP tool`() {
        nodeAvailable()
        val target = tmp.newFolder("none").toPath().resolve("allowme.txt")
        val out = runWith("none", listOf("-p", "T_WRITE", "--permission-prompts", "none") + prompt, { last ->
            if (last.contains("T_WRITE")) toolResponse("call_n", "Write", writeTo(target)) else textResponse("NONE_DONE")
        })
        assertFalse(Files.exists(target))
        assertFalse("没有咨询提示工具", Files.exists(tmp.root.toPath().resolve("none-work/calls.log")))
        assertEquals(0, out.exit)
    }
}
