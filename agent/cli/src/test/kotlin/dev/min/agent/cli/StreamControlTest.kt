package dev.min.agent.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `-p --input-format stream-json --output-format stream-json` 的 stdio 控制协议：
 * `control_request`（initialize / interrupt / set_permission_mode / set_model）与 `can_use_tool` 权限询问
 * （`--permission-prompt-tool stdio`），`control_response` 的信封 `{type, response:{subtype, request_id, response|error}}`。
 * CC 出处：cc-sdk-typescript.md 的 Query 方法表、SDKControlInitializeResponse（含 `pending_permission_requests`）、
 * SDKControlInterruptResponse（`still_queued` / `cancelled`、`cancel_queued`）、CanUseTool / PermissionResult；
 * headless 页的 `--permission-prompts none`。
 *
 * 权限询问是安全判定：未应答 = 不放行；回话只能认 request_id 对得上的那一次询问、只认一次；格式不对一律当拒绝；
 * 改写入参（updatedInput 与原入参不同）Min 没有「按改写后的入参执行」的路径，不能悄悄按原入参执行，按拒绝处理。
 */
class StreamControlTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private fun run(block: suspend () -> Unit) { runBlocking { withTimeout(300_000) { block() } } }

    private val base = listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json")
    private val hosted = base + listOf("--mode", "default", "--permission-prompt-tool", "stdio")

    private fun user(text: String, uuid: String? = null) =
        """{"type":"user",${uuid?.let { """"uuid":"$it",""" } ?: ""}"message":{"role":"user","content":${JsonPrimitive(text)}}}"""

    private fun control(id: String, request: String) = """{"type":"control_request","request_id":"$id","request":$request}"""

    private fun answer(id: String, body: String) =
        """{"type":"control_response","response":{"subtype":"success","request_id":"$id","response":$body}}"""

    private fun isControl(sub: String) = { l: JsonObject ->
        l.str("type") == "control_request" && l["request"]?.jsonObject?.str("subtype") == sub
    }

    private fun isResponseTo(id: String) = { l: JsonObject ->
        l.str("type") == "control_response" && l["response"]?.jsonObject?.str("request_id") == id
    }

    private fun guest(p: Path): String = p.toString().let { if (it.length > 2 && it[1] == ':') "/" + it[0].lowercaseChar() + it.substring(2).replace('\\', '/') else it }

    private fun writeCall(id: String, file: Path) =
        Triple(id, "Write", buildJsonObject { put("file_path", guest(file)); put("content", "x") })

    // ---- 控制请求 ----

    @Test fun `initialize answers with the SDK initialize response inside the control_response envelope`() = run {
        ModelServer { textResponse("X") }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "init", server, base)
            live.send(control("i1", """{"subtype":"initialize"}"""))
            val line = live.awaitLine(pred = isResponseTo("i1"))
            val response = line["response"]!!.jsonObject
            assertEquals("success", response.str("subtype"))
            val payload = response["response"]!!.jsonObject
            // SDKControlInitializeResponse 里 Min 有数据的键；models / account 不属于 Min 的数据，省略不编
            assertTrue(payload["commands"] is JsonArray)
            assertTrue(payload["agents"] is JsonArray)
            assertEquals("default", payload.str("output_style"))
            assertTrue(payload["available_output_styles"] is JsonArray)
            // 信封上的 pending_permission_requests：成功的 initialize 总是带，没有未决询问就是空数组
            assertEquals(JsonArray(emptyList()), response["pending_permission_requests"])
            val out = live.finish()
            assertEquals(0, out.exit)
            assertEquals(0, out.requests.size)
        }
    }

    @Test fun `the init line advertises the interrupt capabilities when stdin carries the control protocol`() = run {
        ModelServer { textResponse("X") }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "caps", server, base)
            val init = live.awaitLine { it.str("subtype") == "init" }
            val caps = init["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(caps.toString(), "interrupt_receipt_v1" in caps && "interrupt_cancel_queued_v1" in caps)
            live.finish()
        }
    }

    @Test fun `an unknown control subtype gets an error response and the run goes on`() = run {
        ModelServer { textResponse("STILL_FINE") }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "unknown", server, base)
            live.send(control("m1", """{"subtype":"mystery"}"""))
            val response = live.awaitLine(pred = isResponseTo("m1"))["response"]!!.jsonObject
            assertEquals("error", response.str("subtype"))
            assertTrue(response.str("error")!!.contains("mystery"))
            live.send(user("T_AFTER"))
            val out = live.finish()
            assertEquals(0, out.exit)
            assertEquals("STILL_FINE", out.results.single().str("result"))
        }
    }

    @Test fun `control requests need stream-json output, with text output they are refused loudly`() = run {
        ModelServer { textResponse("X") }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "textout", server, listOf("-p", "--input-format", "stream-json"))
            live.send(control("c1", """{"subtype":"interrupt"}"""))
            val out = live.finish()
            assertEquals(1, out.exit)
            assertTrue(out.stderr, out.stderr.contains("control_request"))
            assertFalse("stdout 是给文本用的，不能混进控制消息", out.stdout.contains("control_response"))
        }
    }

    @Test fun `set_permission_mode takes effect for the next tool call and rejects bad modes`() = run {
        val first = tmp.newFolder("sp-first").toPath().resolve("denied.txt")
        val second = tmp.newFolder("sp-second").toPath().resolve("allowed.txt")
        ModelServer { last ->
            when {
                last.contains("T_W1") -> toolResponse("w1", "Write", buildJsonObject { put("file_path", guest(first)); put("content", "x") })
                last.contains("T_W2") -> toolResponse("w2", "Write", buildJsonObject { put("file_path", guest(second)); put("content", "x") })
                else -> textResponse("WRITE_DONE")
            }
        }.use { server ->
            // default 模式、没有权限询问宿主：要审批的写被拒（--approve 默认 no）
            val live = startLive(tmp.root.toPath(), scope(), "mode", server, base + listOf("--mode", "default"))
            live.send(user("T_W1"))
            live.awaitLine { it.str("type") == "result" }
            assertFalse("default 模式下未获批准的写不该落盘", Files.exists(first))
            live.send(control("b1", """{"subtype":"set_permission_mode","mode":"nope"}"""))
            assertEquals("error", live.awaitLine(pred = isResponseTo("b1"))["response"]!!.jsonObject.str("subtype"))
            live.send(control("b2", """{"subtype":"set_permission_mode","mode":"bypassPermissions"}"""))
            assertEquals("success", live.awaitLine(pred = isResponseTo("b2"))["response"]!!.jsonObject.str("subtype"))
            live.send(user("T_W2"))
            val out = live.finish()
            assertTrue("切到 bypassPermissions 之后的写应当直接放行", Files.exists(second))
            assertEquals(0, out.exit)
        }
    }

    @Test fun `register_repo_root adds a working directory mid-session, bad paths get an error response`() = run {
        val shared = tmp.newFolder("rr-shared").toPath()
        val before = shared.resolve("before.txt")
        val after = shared.resolve("after.txt")
        ModelServer { last ->
            when {
                last.contains("T_W1") -> toolResponse("w1", "Write", buildJsonObject { put("file_path", guest(before)); put("content", "x") })
                last.contains("T_W2") -> toolResponse("w2", "Write", buildJsonObject { put("file_path", guest(after)); put("content", "x") })
                else -> textResponse("WRITE_DONE")
            }
        }.use { server ->
            // acceptEdits：工作目录外的写要审批，没有宿主批准 = 拒绝
            val live = startLive(tmp.root.toPath(), scope(), "rr", server, base + listOf("--mode", "acceptEdits"))
            live.send(user("T_W1"))
            live.awaitLine { it.str("type") == "result" }
            assertFalse("没加之前，工作目录外的写不该落盘", Files.exists(before))
            live.send(control("r0", """{"subtype":"register_repo_root"}"""))
            assertEquals("缺 path", "error", live.awaitLine(pred = isResponseTo("r0"))["response"]!!.jsonObject.str("subtype"))
            live.send(control("r1", """{"subtype":"register_repo_root","path":${JsonPrimitive(shared.resolve("nope").toString())}}"""))
            assertEquals("不存在", "error", live.awaitLine(pred = isResponseTo("r1"))["response"]!!.jsonObject.str("subtype"))
            live.send(control("r2", """{"subtype":"register_repo_root","path":${JsonPrimitive("\\\\server\\share")}}"""))
            assertTrue("网络路径", live.awaitLine(pred = isResponseTo("r2"))["response"]!!.jsonObject.str("error")!!.contains("网络路径"))
            live.send(control("r3", """{"subtype":"register_repo_root","path":${JsonPrimitive(shared.toString())}}"""))
            val ok = live.awaitLine(pred = isResponseTo("r3"))["response"]!!.jsonObject
            assertEquals("success", ok.str("subtype"))
            assertEquals(JsonPrimitive(true), ok["response"]!!.jsonObject["added"])
            live.send(user("T_W2"))
            val out = live.finish()
            assertTrue("加了之后，写进这个目录按权限模式（acceptEdits）直接放行", Files.exists(after))
            assertFalse(Files.exists(before))
            assertEquals(0, out.exit)
        }
    }

    @Test fun `set_model switches among the models the CLI was started with and refuses the rest`() = run {
        ModelServer { textResponse("MODEL_OK") }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "model", server, base + listOf("--fast-model", "scripted-fast"))
            live.send(control("s1", """{"subtype":"set_model","model":"scripted-fast"}"""))
            assertEquals("success", live.awaitLine(pred = isResponseTo("s1"))["response"]!!.jsonObject.str("subtype"))
            live.send(user("T_ONE"))
            live.awaitLine { it.str("type") == "result" }
            assertEquals("scripted-fast", server.requests.last().str("model"))
            // 没配置过的模型：Min 的桌面 CLI 没有它的凭证与客户端，明说不行，不悄悄留在旧模型上还报成功
            live.send(control("s2", """{"subtype":"set_model","model":"never-configured"}"""))
            assertEquals("error", live.awaitLine(pred = isResponseTo("s2"))["response"]!!.jsonObject.str("subtype"))
            // 不给 model 或 "default"：回到启动时的主模型
            live.send(control("s3", """{"subtype":"set_model"}"""))
            assertEquals("success", live.awaitLine(pred = isResponseTo("s3"))["response"]!!.jsonObject.str("subtype"))
            live.send(user("T_TWO"))
            val out = live.finish()
            assertEquals("scripted", out.requests.last().str("model"))
            assertEquals(0, out.exit)
        }
    }

    // ---- interrupt ----

    private fun slowRoute(release: CountDownLatch): (String) -> String? = { last ->
        when {
            // 被中断的 T_SLOW 还留在历史里，所以先认后一条的标记
            last.contains("T_QUEUED") -> textResponse("FAST_DONE")
            last.contains("T_SLOW") -> { release.await(30, TimeUnit.SECONDS); textResponse("SLOW_DONE") }
            else -> textResponse("FAST_DONE")
        }
    }

    @Test fun `interrupt stops the running turn, receipts the queued messages, and the next message still runs`() = run {
        val release = CountDownLatch(1)
        ModelServer(slowRoute(release)).use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "interrupt", server, base)
            live.send(user("T_SLOW", "u1"))
            live.awaitRequests(1)
            live.send(user("T_QUEUED", "u2"))
            live.send(control("i1", """{"subtype":"interrupt"}"""))
            val receipt = live.awaitLine(pred = isResponseTo("i1"))["response"]!!.jsonObject
            assertEquals("success", receipt.str("subtype"))
            // SDKControlInterruptResponse：中断那一刻还在排队的消息的 uuid
            assertEquals(listOf("u2"), receipt["response"]!!.jsonObject["still_queued"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertNull(receipt["response"]!!.jsonObject["cancelled"])
            release.countDown()
            val out = live.finish()
            assertEquals(2, out.results.size)
            val interrupted = out.results[0]
            assertEquals("error_during_execution", interrupted.str("subtype"))
            assertEquals(JsonPrimitive(true), interrupted["is_error"])
            assertTrue(interrupted["errors"].toString().contains("被用户中断"))
            assertEquals("FAST_DONE", out.results[1].str("result"))
            // 干净的中断：回执先于被中断回合的 result
            val receiptAt = out.lines.indexOfFirst(isResponseTo("i1"))
            val resultAt = out.lines.indexOfFirst { it.str("type") == "result" }
            assertTrue("回执要在被中断回合的 result 之前（$receiptAt / $resultAt）", receiptAt in 0 until resultAt)
            assertEquals(0, out.exit)
        }
    }

    @Test fun `interrupt with cancel_queued drops the queued messages and lists them as cancelled`() = run {
        val release = CountDownLatch(1)
        ModelServer(slowRoute(release)).use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "cancel-queued", server, base)
            live.send(user("T_SLOW", "u1"))
            live.awaitRequests(1)
            live.send(user("T_QUEUED", "u2"))
            live.send(control("i1", """{"subtype":"interrupt","cancel_queued":true}"""))
            val body = live.awaitLine(pred = isResponseTo("i1"))["response"]!!.jsonObject["response"]!!.jsonObject
            assertEquals(JsonArray(emptyList()), body["still_queued"])
            assertEquals(listOf("u2"), body["cancelled"]!!.jsonArray.map { it.jsonPrimitive.content })
            release.countDown()
            val out = live.finish()
            assertEquals("被取消的消息不会运行", 1, out.results.size)
            assertEquals("只有被中断的那一轮发过请求", 1, out.requests.size)
        }
    }

    @Test fun `interrupt while idle succeeds with an empty receipt`() = run {
        ModelServer { textResponse("X") }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "idle-interrupt", server, base)
            live.send(control("i1", """{"subtype":"interrupt"}"""))
            val body = live.awaitLine(pred = isResponseTo("i1"))["response"]!!.jsonObject
            assertEquals("success", body.str("subtype"))
            assertEquals(JsonArray(emptyList()), body["response"]!!.jsonObject["still_queued"])
            assertEquals(0, live.finish().exit)
        }
    }

    // ---- can_use_tool ----

    @Test fun `a permission ask goes out as can_use_tool and an allow with the same input runs the tool`() = run {
        val target = tmp.newFolder("allow").toPath().resolve("ok.txt")
        ModelServer { last ->
            if (last.contains("T_WRITE")) toolResponse("call_w", "Write", buildJsonObject { put("file_path", guest(target)); put("content", "x") })
            else textResponse("ALLOWED_DONE")
        }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "allow", server, hosted)
            live.send(user("T_WRITE"))
            val ask = live.awaitLine(pred = isControl("can_use_tool"))
            val request = ask["request"]!!.jsonObject
            assertTrue(ask.str("request_id")!!.isNotBlank())
            assertEquals("Write", request.str("tool_name"))
            assertEquals("call_w", request.str("tool_use_id"))
            assertEquals(guest(target), request["input"]!!.jsonObject.str("file_path"))
            assertFalse("还没答复，工具不能先执行", Files.exists(target))
            live.send(answer(ask.str("request_id")!!, """{"behavior":"allow","updatedInput":${request["input"]}}"""))
            val out = live.finish()
            assertTrue(Files.exists(target))
            assertEquals(0, out.exit)
            assertEquals("ALLOWED_DONE", out.results.single().str("result"))
        }
    }

    @Test fun `a deny keeps the tool from running, tells the model why and lands in permission_denials`() = run {
        val target = tmp.newFolder("deny").toPath().resolve("no.txt")
        ModelServer { last ->
            if (last.contains("T_WRITE")) toolResponse("call_d", "Write", buildJsonObject { put("file_path", guest(target)); put("content", "x") })
            else textResponse("DENIED_DONE")
        }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "deny", server, hosted)
            live.send(user("T_WRITE"))
            val ask = live.awaitLine(pred = isControl("can_use_tool"))
            live.send(answer(ask.str("request_id")!!, """{"behavior":"deny","message":"no thanks"}"""))
            val out = live.finish()
            assertFalse(Files.exists(target))
            val toolResult = out.ofType("user").single()["message"]!!.jsonObject["content"]!!.jsonArray.first().jsonObject
            assertEquals(JsonPrimitive(true), toolResult["is_error"])
            assertTrue(out.requests.last().toString().contains("no thanks"))
            val denial = out.results.single()["permission_denials"]!!.jsonArray.single().jsonObject
            assertEquals("Write", denial.str("tool_name"))
            assertEquals("call_d", denial.str("tool_use_id"))
            // 宿主自己拒的，不算「没问宿主就拒了」：没有 permission_denied 系统消息
            assertTrue(out.system("permission_denied").isEmpty())
        }
    }

    @Test fun `a deny with interrupt true also ends the turn`() = run {
        val target = tmp.newFolder("deny-interrupt").toPath().resolve("no.txt")
        ModelServer { toolResponse("call_i", "Write", buildJsonObject { put("file_path", guest(target)); put("content", "x") }) }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "deny-interrupt", server, hosted)
            live.send(user("T_WRITE"))
            val ask = live.awaitLine(pred = isControl("can_use_tool"))
            live.send(answer(ask.str("request_id")!!, """{"behavior":"deny","message":"stop","interrupt":true}"""))
            val out = live.finish()
            assertFalse(Files.exists(target))
            assertTrue(out.results.single()["errors"].toString().contains("被用户中断"))
            assertEquals("中断之后不再发第二个请求", 1, out.requests.size)
        }
    }

    @Test fun `permission-prompts none never consults the host and denies`() = run {
        val target = tmp.newFolder("none").toPath().resolve("no.txt")
        ModelServer { last ->
            if (last.contains("T_WRITE")) toolResponse("call_n", "Write", buildJsonObject { put("file_path", guest(target)); put("content", "x") })
            else textResponse("NONE_DONE")
        }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "none", server, hosted + listOf("--permission-prompts", "none"))
            live.send(user("T_WRITE"))
            val out = live.finish()
            assertFalse(Files.exists(target))
            assertTrue("没有咨询宿主", out.lines.none { it.str("type") == "control_request" })
            assertEquals("NONE_DONE", out.results.single().str("result"))
        }
    }

    // ---- 权限询问的安全判定（变异对象）----

    @Test fun `an ask nobody answered is denied when stdin closes`() = run {
        val target = tmp.newFolder("unanswered").toPath().resolve("no.txt")
        ModelServer { last ->
            if (last.contains("T_WRITE")) toolResponse("call_u", "Write", buildJsonObject { put("file_path", guest(target)); put("content", "x") })
            else textResponse("UNANSWERED_DONE")
        }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "unanswered", server, hosted)
            live.send(user("T_WRITE"))
            live.awaitLine(pred = isControl("can_use_tool"))
            // 没答复就关了输入：宿主走了，没人批准过，工具不能执行
            val out = live.finish()
            assertFalse("未应答 = 不放行", Files.exists(target))
            val toolResult = out.ofType("user").single()["message"]!!.jsonObject["content"]!!.jsonArray.first().jsonObject
            assertEquals(JsonPrimitive(true), toolResult["is_error"])
        }
    }

    @Test fun `an ask that comes after stdin was closed is denied`() = run {
        val target = tmp.newFolder("late").toPath().resolve("no.txt")
        ModelServer { last ->
            if (last.contains("T_WRITE")) toolResponse("call_l", "Write", buildJsonObject { put("file_path", guest(target)); put("content", "x") }) else textResponse("LATE_DONE")
        }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "late", server, hosted)
            // 消息和 EOF 一起到：回合起来的时候宿主早就走了，没人能答复——不批准
            live.send(user("T_WRITE"))
            val out = live.finish()
            assertFalse("宿主已断开，工具不能执行", Files.exists(target))
            assertEquals(1, out.ofType("user").size)
        }
    }

    @Test fun `a forged request id cannot approve anything and the real ask stays pending`() = run {
        val target = tmp.newFolder("forged").toPath().resolve("no.txt")
        ModelServer { last ->
            if (last.contains("T_WRITE")) toolResponse("call_f", "Write", buildJsonObject { put("file_path", guest(target)); put("content", "x") })
            else textResponse("FORGED_DONE")
        }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "forged", server, hosted)
            live.send(user("T_WRITE"))
            val ask = live.awaitLine(pred = isControl("can_use_tool"))
            live.send(answer("not-a-real-id", """{"behavior":"allow","updatedInput":${ask["request"]!!.jsonObject["input"]}}"""))
            // 伪造的 id 不对应任何询问：忽略；真的那次仍在等，工具没执行、没有工具结果
            delay(500)
            assertFalse(Files.exists(target))
            assertTrue(live.linesNow().none { it.str("type") == "user" })
            live.send(answer(ask.str("request_id")!!, """{"behavior":"deny","message":"real answer"}"""))
            val out = live.finish()
            assertFalse(Files.exists(target))
            assertTrue(out.requests.last().toString().contains("real answer"))
        }
    }

    @Test fun `one response only decides its own ask and only once`() = run {
        val a = tmp.newFolder("pair-a").toPath().resolve("a.txt")
        val b = tmp.newFolder("pair-b").toPath().resolve("b.txt")
        ModelServer { last ->
            if (last.contains("T_PAIR")) toolsResponse(writeCall("call_a", a), writeCall("call_b", b)) else textResponse("PAIR_DONE")
        }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "pair", server, hosted)
            live.send(user("T_PAIR"))
            // 内核一次只挂一个待审批的调用：A 答了才轮到 B
            val askA = live.awaitLine { it.str("type") == "control_request" && it["request"]!!.jsonObject.str("tool_use_id") == "call_a" }
            val idA = askA.str("request_id")!!
            live.send(answer(idA, """{"behavior":"allow","updatedInput":${askA["request"]!!.jsonObject["input"]}}"""))
            val askB = live.awaitLine { it.str("type") == "control_request" && it["request"]!!.jsonObject.str("tool_use_id") == "call_b" }
            // A 已经批准过了；用 A 的 id 再回一次（想顺手批准 B）不起作用——A 的未决项取走即删，B 的 id 另有其人
            live.send(answer(idA, """{"behavior":"allow","updatedInput":${askB["request"]!!.jsonObject["input"]}}"""))
            delay(500)
            assertTrue(Files.exists(a))
            assertFalse("B 还在等它自己的答复", Files.exists(b))
            live.send(answer(askB.str("request_id")!!, """{"behavior":"deny","message":"b no"}"""))
            val out = live.finish()
            assertTrue(Files.exists(a))
            assertFalse(Files.exists(b))
            assertEquals(0, out.exit)
        }
    }

    @Test fun `a second response cannot flip an ask that was already denied`() = run {
        val target = tmp.newFolder("flip").toPath().resolve("no.txt")
        ModelServer { last ->
            if (last.contains("T_WRITE")) toolResponse("call_x", "Write", buildJsonObject { put("file_path", guest(target)); put("content", "x") }) else textResponse("FLIP_DONE")
        }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "flip", server, hosted)
            live.send(user("T_WRITE"))
            val ask = live.awaitLine(pred = isControl("can_use_tool"))
            val id = ask.str("request_id")!!
            live.send(answer(id, """{"behavior":"deny","message":"first"}"""))
            live.send(answer(id, """{"behavior":"allow","updatedInput":${ask["request"]!!.jsonObject["input"]}}"""))
            val out = live.finish()
            assertFalse(Files.exists(target))
            assertEquals(0, out.exit)
        }
    }

    @Test fun `an allow that rewrites the input is refused instead of running the original input`() = run {
        val target = tmp.newFolder("rewrite").toPath().resolve("orig.txt")
        val other = tmp.newFolder("rewrite-other").toPath().resolve("other.txt")
        ModelServer { last ->
            if (last.contains("T_WRITE")) toolResponse("call_r", "Write", buildJsonObject { put("file_path", guest(target)); put("content", "x") }) else textResponse("REWRITE_DONE")
        }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "rewrite", server, hosted)
            live.send(user("T_WRITE"))
            val ask = live.awaitLine(pred = isControl("can_use_tool"))
            live.send(answer(ask.str("request_id")!!, """{"behavior":"allow","updatedInput":{"file_path":"${guest(other)}","content":"x"}}"""))
            val out = live.finish()
            // Min 没有「按改写后的入参执行」的路径：既不能按原入参执行（宿主要的是改写后的），也不能写到改写的地方
            assertFalse(Files.exists(target))
            assertFalse(Files.exists(other))
            assertTrue(out.requests.last().toString().contains("updatedInput"))
        }
    }

    @Test fun `malformed or error responses are denials, never approvals`() = run {
        val files = (1..4).map { tmp.newFolder("bad-$it").toPath().resolve("f.txt") }
        ModelServer { last ->
            if (last.contains("T_FOUR")) toolsResponse(*files.mapIndexed { i, f -> writeCall("call_$i", f) }.toTypedArray()) else textResponse("BAD_DONE")
        }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "malformed", server, hosted)
            live.send(user("T_FOUR"))
            fun askOf(i: Int) = { l: JsonObject -> l.str("type") == "control_request" && l["request"]!!.jsonObject.str("tool_use_id") == "call_$i" }
            // 0：没有 behavior；1：未知的 behavior；2：response 不是对象；3：subtype=error（内核一次挂一个审批，逐个等）
            live.send(answer(live.awaitLine(pred = askOf(0)).str("request_id")!!, """{"updatedInput":{}}"""))
            live.send(answer(live.awaitLine(pred = askOf(1)).str("request_id")!!, """{"behavior":"maybe"}"""))
            live.send(answer(live.awaitLine(pred = askOf(2)).str("request_id")!!, "\"allow\""))
            live.send("""{"type":"control_response","response":{"subtype":"error","request_id":"${live.awaitLine(pred = askOf(3)).str("request_id")}","error":"boom"}}""")
            val out = live.finish()
            files.forEach { assertFalse("$it 不该被写出来", Files.exists(it)) }
            assertEquals(4, out.ofType("user").size)
        }
    }

    // ---- 参数面 ----

    private fun parse(vararg extra: String) = parseArgs(arrayOf("--model", "m") + extra, tmp.root.toPath())

    @Test fun `permission-prompt-tool stdio and permission-prompts are validated at startup`() {
        val args = parse(*hosted.toTypedArray())
        assertEquals("stdio", args.permissionPromptTool)
        assertEquals(PermissionPrompts.Host, args.permissionPrompts)
        assertEquals(PermissionPrompts.None, parse(*hosted.toTypedArray(), "--permission-prompts", "none").permissionPrompts)
        // 宿主走 stdio：两端都得是 stream-json
        assertThrows(UsageException::class.java) { parse("-p", "hi", "--permission-prompt-tool", "stdio") }
        assertThrows(UsageException::class.java) { parse("-p", "--input-format", "stream-json", "--permission-prompt-tool", "stdio") }
        // 原文的写法（MCP 工具名）也认，见 McpPromptToolTest；不是 stdio 也不是 mcp__服务器__工具 的名字不认
        assertEquals("mcp__perm__approve", parse(*base.toTypedArray(), "--permission-prompt-tool", "mcp__perm__approve").permissionPromptTool)
        assertThrows(UsageException::class.java) { parse(*base.toTypedArray(), "--permission-prompt-tool", "approve") }
        assertThrows(UsageException::class.java) { parse("-p", "hi", "--permission-prompts", "bogus") }
        // --permission-prompts 是 print 模式的参数
        assertThrows(UsageException::class.java) { parse("--permission-prompts", "none") }
    }

    @Test fun `include-hook-events needs stream-json output`() {
        assertTrue(parse("-p", "hi", "--output-format", "stream-json", "--include-hook-events").includeHookEvents)
        assertThrows(UsageException::class.java) { parse("-p", "hi", "--include-hook-events") }
        assertThrows(UsageException::class.java) { parse("--include-hook-events") }
    }

    // ---- 裁决的纯函数 ----

    private fun decide(response: String, original: String = """{"file_path":"/x","content":"y"}""", rewritable: Boolean = false) =
        StdioControl.decide(kotlinx.serialization.json.Json.parseToJsonElement(response).jsonObject, kotlinx.serialization.json.Json.parseToJsonElement(original).jsonObject, rewritable)

    private fun ok(response: String, original: String = """{"file_path":"/x","content":"y"}""") =
        decide(response, original).choice == dev.min.agent.spi.ApprovalChoice.Once

    @Test fun `only a well formed allow with the original input is an approval`() {
        assertTrue(ok("""{"subtype":"success","request_id":"r","response":{"behavior":"allow"}}"""))
        assertTrue(ok("""{"subtype":"success","request_id":"r","response":{"behavior":"allow","updatedInput":{"content":"y","file_path":"/x"}}}"""))
        // 不是批准的：改写了入参、没有 behavior、未知 behavior、response 不是对象、没有 response、subtype=error、deny
        assertFalse(ok("""{"subtype":"success","request_id":"r","response":{"behavior":"allow","updatedInput":{"file_path":"/other","content":"y"}}}"""))
        assertFalse(ok("""{"subtype":"success","request_id":"r","response":{"updatedInput":{}}}"""))
        assertFalse(ok("""{"subtype":"success","request_id":"r","response":{"behavior":"ALLOW"}}"""))
        assertFalse(ok("""{"subtype":"success","request_id":"r","response":"allow"}"""))
        assertFalse(ok("""{"subtype":"success","request_id":"r"}"""))
        assertFalse(ok("""{"subtype":"error","request_id":"r","error":"x","response":{"behavior":"allow"}}"""))
        assertFalse(ok("""{"subtype":"success","request_id":"r","response":{"behavior":"deny","message":"m"}}"""))
    }

    @Test fun `a deny keeps the host's words in the tool result and can ask for an interrupt`() {
        val plain = decide("""{"subtype":"success","request_id":"r","response":{"behavior":"deny","message":"no thanks"}}""")
        val deny = plain.choice as dev.min.agent.spi.ApprovalChoice.Deny
        assertEquals("no thanks", deny.note)
        // 宿主说的话不冒充「用户说的」独立文本块：留在 tool_result 里（与 CLI 自己生成的拒绝说明同一口径）
        assertTrue(deny.fromHost)
        assertFalse(plain.interrupt)
        assertTrue(decide("""{"subtype":"success","request_id":"r","response":{"behavior":"deny","message":"m","interrupt":true}}""").interrupt)
    }

    @Test fun `updatedPermissions are never adopted`() {
        // 宿主一句话不能给本机写下持久的放行规则：allow 带 updatedPermissions 也只是这一次的 Once
        val d = decide("""{"subtype":"success","request_id":"r","response":{"behavior":"allow","updatedPermissions":[{"type":"addRules","rules":[{"toolName":"Write"}],"behavior":"allow","destination":"localSettings"}]}}""")
        assertEquals(dev.min.agent.spi.ApprovalChoice.Once, d.choice)
    }

    @Test fun `question answers map labels to selections and everything else to free text`() {
        val q = listOf(
            dev.min.agent.spi.UserQuestion("选哪个？", "选择", listOf(dev.min.agent.spi.QuestionOption("甲", "d"), dev.min.agent.spi.QuestionOption("乙", "d"))),
            dev.min.agent.spi.UserQuestion("哪些？", "多选", listOf(dev.min.agent.spi.QuestionOption("A", "d"), dev.min.agent.spi.QuestionOption("B", "d")), multiSelect = true),
        )
        fun answers(json: String) = hostQuestionAnswers(q, kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject)
        val ok = answers("""{"questions":[],"answers":{"选哪个？":"乙","哪些？":"A, B"}}""")!!
        assertEquals(listOf("乙"), ok[0].selected)
        assertEquals(listOf("A", "B"), ok[1].selected)
        assertEquals(listOf("A", "B"), answers("""{"answers":{"选哪个？":"甲","哪些？":["A","B"]}}""")!![1].selected)
        // 不是选项 label：自由文本（单选题里给多个 label 也按自由文本，不替宿主拆）
        val free = answers("""{"answers":{"选哪个？":"都不要，我想要丙","哪些？":"A"}}""")!!
        assertEquals("都不要，我想要丙", free[0].other)
        assertTrue(free[0].selected.isEmpty())
        // 缺一题 / 没有 answers / 类型不对：按没有回答，不猜
        assertNull(answers("""{"answers":{"选哪个？":"甲"}}"""))
        assertNull(answers("""{"questions":[]}"""))
        assertNull(answers("""{"answers":{"选哪个？":1,"哪些？":"A"}}"""))
    }

    @Test fun `AskUserQuestion goes through can_use_tool and the answers reach the model`() = run {
        val questions = """{"questions":[{"question":"选哪个？","header":"选择","options":[{"label":"甲","description":"d1"},{"label":"乙","description":"d2"}],"multiSelect":false}]}"""
        ModelServer { last ->
            if (last.contains("T_ASK")) toolResponse("ask1", "AskUserQuestion", kotlinx.serialization.json.Json.parseToJsonElement(questions).jsonObject)
            else textResponse("ASK_DONE")
        }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "ask", server, hosted)
            live.send(user("T_ASK"))
            val ask = live.awaitLine(pred = isControl("can_use_tool"))
            val request = ask["request"]!!.jsonObject
            assertEquals("AskUserQuestion", request.str("tool_name"))
            assertEquals("ask1", request.str("tool_use_id"))
            assertEquals("选哪个？", request["input"]!!.jsonObject["questions"]!!.jsonArray.single().jsonObject.str("question"))
            live.send(answer(ask.str("request_id")!!, """{"behavior":"allow","updatedInput":{"questions":[],"answers":{"选哪个？":"乙"}}}"""))
            val out = live.finish()
            assertTrue(out.requests.last().toString().contains("乙"))
            assertEquals("ASK_DONE", out.results.single().str("result"))
        }
    }

    @Test fun `a denied AskUserQuestion means the user did not answer`() = run {
        val questions = """{"questions":[{"question":"选哪个？","header":"选择","options":[{"label":"甲","description":"d1"},{"label":"乙","description":"d2"}],"multiSelect":false}]}"""
        ModelServer { last ->
            if (last.contains("T_ASK")) toolResponse("ask1", "AskUserQuestion", kotlinx.serialization.json.Json.parseToJsonElement(questions).jsonObject)
            else textResponse("ASK_DONE")
        }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), "ask-deny", server, hosted)
            live.send(user("T_ASK"))
            val ask = live.awaitLine(pred = isControl("can_use_tool"))
            live.send(answer(ask.str("request_id")!!, """{"behavior":"deny","message":"skip"}"""))
            val out = live.finish()
            assertEquals(0, out.exit)
            assertFalse(out.requests.last().toString().contains("\"乙\""))
        }
    }
}
