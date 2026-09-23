package dev.min.code.core.claudecode

import dev.min.code.core.service.LocalServiceStatus
import dev.min.code.core.service.LocalServiceStopReason
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限路径上的托管：deny 要等服务出结论才回，结论怎么告诉模型，
 * 以及等的那几秒里用户按了停止、或者换了档时，迟到的 deny 不能再发。
 *
 * 那条 can_use_tool 是手搓的（形状照 bash_with_permission 录到的那条），
 * 进程表是 [FakeServiceHost]，服务什么时候出结论由测试说了算。
 */
class ClaudeCodeManagerHostingTest {

    private val requestId = "perm-host-1"
    private val toolUseId = "toolu_host_1"

    private fun canUseTool(command: String): String = buildJsonObject {
        put("type", "control_request")
        put("request_id", requestId)
        put(
            "request",
            buildJsonObject {
                put("subtype", "can_use_tool")
                put("tool_name", "Bash")
                put("display_name", "Bash")
                put(
                    "input",
                    buildJsonObject {
                        put("command", command)
                        put("description", "Start the server")
                        put("run_in_background", true)
                    },
                )
                put("description", "Start the server")
                put("tool_use_id", toolUseId)
            },
        )
    }.toString()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    /** Min 回给这条权限请求的所有应答（写给 [process] 的） */
    private fun answers(process: FakeCliProcess): List<JsonObject> = process.written
        .filter { it.str("type") == "control_response" }
        .map { it["response"]!!.jsonObject }
        .filter { it.str("request_id") == requestId }

    private fun denyMessage(answer: JsonObject): String {
        val body = answer["response"]!!.jsonObject
        assertEquals("deny", body.str("behavior"))
        return body.str("message")!!
    }

    private fun notes(h: ManagerHarness) = h.manager.state.value.items
        .filterIsInstance<ChatItem.Note>().map { it.text }

    /** 起会话、发一条消息让这一轮挂着（不吐任何帧），再让 CLI 发来托管那条权限请求 */
    private suspend fun ManagerHarness.askToHost(command: String = "python3 -m http.server 8799") {
        startAndHandshake()
        process.holdTurns = true
        manager.send("start the server")
        awaitState("busy") { it.busy }
        process.emit(canUseTool(command))
        awaitCondition("交给进程表") { services.started.isNotEmpty() }
    }

    @Test
    fun `the deny waits for the service and says it is hosted once it is up`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.askToHost()
            // 还在就绪窗口里：CLI 什么都没收到，也没弹权限面板
            delay(200)
            assertTrue(answers(h.process).isEmpty())
            assertEquals(null, h.manager.state.value.pendingPermission)

            h.services.settle("svc1", LocalServiceStatus.Running)
            h.awaitCondition("deny 发出") { answers(h.process).isNotEmpty() }
            val msg = denyMessage(answers(h.process).single())
            assertTrue(msg, msg.startsWith("Min hosted this as a background service in the process table · preview http://127.0.0.1:8799."))
            h.awaitState("已托管提示") { s -> s.items.any { it is ChatItem.Note && it.text.startsWith("已托管到进程表") } }
        }
    }

    @Test
    fun `a service that dies at once hands the model its exit code and last output`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.askToHost()
            h.services.settle(
                "svc1",
                LocalServiceStatus.Failed,
                exitCode = 127,
                stopReason = LocalServiceStopReason.Crash,
                logTail = "bash: line 1: python3: command not found\n",
            )
            h.awaitCondition("deny 发出") { answers(h.process).isNotEmpty() }
            val msg = denyMessage(answers(h.process).single())
            assertTrue(msg, "exited right away (exit 127: command not found). The service is NOT running." in msg)
            assertTrue(msg, "python3: command not found" in msg)
            assertFalse(msg, "hosted this" in msg)
            h.awaitState("死因提示") { s -> s.items.any { it is ChatItem.Note && "已把原因告诉模型" in it.text } }
            assertFalse(notes(h).any { it.startsWith("已托管到进程表") })
        }
    }

    @Test
    fun `a service that cannot start is reported to the model with the reason`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.services.failWith = "port 8799 busy"
            h.askToHost()
            h.awaitCondition("deny 发出") { answers(h.process).isNotEmpty() }
            val msg = denyMessage(answers(h.process).single())
            assertTrue(msg, msg.startsWith("Min could not start this as a background service: port 8799 busy."))
            h.awaitState("失败提示") { s ->
                s.items.any { it is ChatItem.Note && it.text == "托管到进程表失败：port 8799 busy。已把原因告诉模型。" }
            }
        }
    }

    @Test
    fun `stopping the turn while waiting drops the late deny but still tells the user`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.askToHost()
            h.manager.interrupt()
            h.awaitCondition("interrupt 发出") {
                h.process.written.any { it.str("type") == "control_request" && it["request"]!!.jsonObject.str("subtype") == "interrupt" }
            }
            h.services.settle("svc1", LocalServiceStatus.Failed, exitCode = 127, stopReason = LocalServiceStopReason.Crash)
            h.awaitState("死因提示") { s -> s.items.any { it is ChatItem.Note && "模型还不知道它没起来" in it.text } }
            // 给写队列一点时间：真要写，这会儿早该到了
            delay(300)
            assertTrue(answers(h.process).isEmpty())
        }
    }

    @Test
    fun `relaunching while waiting never sends the old request's deny to the new CLI`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.askToHost()
            val first = h.process
            // 这一轮还挂着（轮次没变），挡住迟到 deny 的只能是「进程换了」
            h.manager.applyEffort("high")
            h.awaitState("重启完成") { h.launches.size == 2 && it.status == SessionStatus.Running && !it.applyingEffort }
            h.services.settle("svc1", LocalServiceStatus.Running)
            delay(500)
            assertTrue(answers(first).isEmpty())
            assertTrue(answers(h.process).isEmpty())
        }
    }
}
