package dev.min.code.core.claudecode

import dev.min.code.core.service.LocalServiceStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 本机预览位的来源：托管的服务起来了、bypass 下跟着托管、工具输出里认出 loopback 地址。
 * 三条路都只往 `localPreviewUrls` 里发一个归一过的 loopback URL，由页面决定填不填预览位。
 */
class ClaudeCodeManagerPreviewTest {

    private fun bashToolUse(id: String, command: String) = buildJsonObject {
        put("type", "assistant")
        put("message", buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", id)
                    put("name", "Bash")
                    put("input", buildJsonObject {
                        put("command", command)
                        put("run_in_background", true)
                    })
                })
            })
        })
    }.toString()

    private fun toolResult(id: String, text: String) = buildJsonObject {
        put("type", "user")
        put("message", buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", id)
                    put("content", text)
                })
            })
        })
    }.toString()

    private suspend fun ManagerHarness.collectPreviews(): CopyOnWriteArrayList<String> {
        val urls = CopyOnWriteArrayList<String>()
        // SharedFlow 没有 replay：订阅要先于事件，UNDISPATCHED 保证 launch 返回时已经在收了
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            manager.localPreviewUrls.collect { urls += it }
        }
        return urls
    }

    @Test
    fun `a hosted service that came up offers its preview`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            val urls = h.collectPreviews()
            h.process.holdTurns = true
            h.manager.send("serve")
            h.awaitState("busy") { it.busy }
            h.process.emit(
                """{"type":"control_request","request_id":"p1","request":{"subtype":"can_use_tool","tool_name":"Bash","display_name":"Bash","input":{"command":"python3 -m http.server 8799","run_in_background":true},"description":"serve","tool_use_id":"toolu_p1"}}"""
            )
            h.awaitCondition("交给进程表") { h.services.started.isNotEmpty() }
            h.services.settle("svc1", LocalServiceStatus.Running)
            h.awaitCondition("预览位地址") { urls.isNotEmpty() }
            assertEquals(listOf("http://127.0.0.1:8799/"), urls.toList())
        }
    }

    /** bypass 下没有权限闸门：tool_use 一到就托管；进程表那边成不成，预览位都先填上（CLI 可能已占了端口） */
    @Test
    fun `hosting without a permission gate offers the preview for the guessed port`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake(ClaudeCodeManager.SessionOptions(skipPermissions = true))
            val urls = h.collectPreviews()
            h.process.holdTurns = true
            h.manager.send("serve")
            h.awaitState("busy") { it.busy }
            h.process.emit(bashToolUse("toolu_b1", "python3 -m http.server 8801 &"))
            h.awaitCondition("托管了") { h.services.started.isNotEmpty() }
            h.awaitCondition("预览位地址") { urls.isNotEmpty() }
            assertEquals(listOf("http://127.0.0.1:8801/"), urls.toList())
        }
    }

    @Test
    fun `a loopback address in a tool result is offered, anything else is not`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            val urls = h.collectPreviews()
            h.process.holdTurns = true
            h.manager.send("serve")
            h.awaitState("busy") { it.busy }
            h.process.emit(bashToolUse("toolu_r0", "ls"))
            h.process.emit(toolResult("toolu_r0", "see https://example.com/docs for details"))
            h.process.emit(bashToolUse("toolu_r1", "npm run dev"))
            h.process.emit(toolResult("toolu_r1", "  Local:   http://localhost:5173/"))
            h.awaitCondition("预览位地址") { urls.isNotEmpty() }
            delay(200)
            assertEquals(listOf("http://127.0.0.1:5173/"), urls.toList())
            assertTrue(h.services.started.isEmpty())
        }
    }
}
