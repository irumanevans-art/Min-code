package dev.min.code.core.claudecode

import dev.min.code.core.service.LocalServiceStatus
import dev.min.code.core.session.ChatItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLI 撤回它发来的 control_request（`control_cancel_request`）之后：卡要撤、话要说、迟到的应答不能再写。
 *
 * 帧形状取自 CLI 2.1.283 的 schema：`{type:"control_cancel_request", request_id}`，不带原因、不要应答。
 * 按停止那一路（CLI 顺手撤掉挂着的请求）由特征测试 interrupt_during_permission 覆盖。
 */
class ClaudeCodeCliWithdrawalTest {

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun canUseTool(requestId: String, toolName: String, input: JsonObject, toolUseId: String): String =
        buildJsonObject {
            put("type", "control_request")
            put("request_id", requestId)
            put(
                "request",
                buildJsonObject {
                    put("subtype", "can_use_tool")
                    put("tool_name", toolName)
                    put("display_name", toolName)
                    put("input", input)
                    put("tool_use_id", toolUseId)
                },
            )
        }.toString()

    private fun dangerousRm(requestId: String = "perm-1") = canUseTool(
        requestId = requestId,
        toolName = "Bash",
        input = buildJsonObject {
            put("command", "rm -rf \"\$(pwd)\"")
            put("description", "Remove the working directory")
        },
        toolUseId = "toolu_rm",
    )

    private fun cancel(requestId: String) =
        """{"type":"control_cancel_request","request_id":"$requestId"}"""

    private fun responses(process: FakeCliProcess): List<JsonObject> = process.written
        .filter { it.str("type") == "control_response" }
        .map { it["response"]!!.jsonObject }

    private fun notes(h: ManagerHarness) = h.manager.state.value.items
        .filterIsInstance<ChatItem.Note>().map { it.text }

    /**
     * 2.1.281 起 bypass / auto 下 `rm -rf "$(pwd)"` 这类命令也会发 can_use_tool。
     * Min 在 bypass 下从来没收到过权限请求，这里钉住：卡照样弹，不按模式短路。
     */
    @Test
    fun `bypass mode still shows the card for a command the CLI asks about`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake(ClaudeCodeManager.SessionOptions(skipPermissions = true))
            assertEquals(ClaudeCodePermissionMode.BYPASS, h.manager.state.value.permissionMode)
            h.process.emit(dangerousRm())
            val s = h.awaitState("权限卡") { it.pendingPermission != null }
            assertEquals("perm-1", s.pendingPermission!!.requestId)
            // 没托管、没替用户做主：CLI 还在等人答
            assertTrue(responses(h.process).isEmpty())
            assertTrue(h.services.started.isEmpty())

            assertTrue(h.manager.answerPermission(allow = false))
            h.awaitCondition("应答写出") { responses(h.process).isNotEmpty() }
            assertEquals("perm-1", responses(h.process).single().str("request_id"))
        }
    }

    @Test
    fun `a withdrawn request takes its card down and a late tap is not sent`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake(ClaudeCodeManager.SessionOptions(skipPermissions = true))
            h.process.emit(dangerousRm())
            h.awaitState("权限卡") { it.pendingPermission != null }

            h.process.emit(cancel("perm-1"))
            val s = h.awaitState("卡撤掉") { it.pendingPermission == null }
            assertNull(s.pendingPermission)
            h.awaitState("说明") { st -> st.items.any { it is ChatItem.Note && "CLI 撤回了「Bash」的权限请求" in it.text } }

            // 撤回之后才按下去的（sheet 的动画还没收完、或者从通知点的）：一个字都不写
            assertFalse(h.manager.answerPermission(allow = true))
            assertFalse(h.manager.answerPermission(allow = true, expectedRequestId = "perm-1"))
            delay(100)
            assertTrue(responses(h.process).isEmpty())
            // 不是红字：这不是故障
            assertFalse(h.manager.state.value.items.any { it is ChatItem.Note && it.isError })
        }
    }

    @Test
    fun `a withdrawn question is not answered`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            val input = buildJsonObject {
                put(
                    "questions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("question", "Which one?")
                                put("header", "Pick")
                                put("multiSelect", false)
                                put(
                                    "options",
                                    buildJsonArray {
                                        add(buildJsonObject { put("label", "A") })
                                        add(buildJsonObject { put("label", "B") })
                                    },
                                )
                            },
                        )
                    },
                )
            }
            h.process.emit(canUseTool("ask-1", ASK_USER_QUESTION_TOOL, input, "toolu_ask"))
            h.awaitState("提问卡") { it.pendingPermission != null }
            h.process.emit(cancel("ask-1"))
            h.awaitState("卡撤掉") { it.pendingPermission == null }
            assertTrue(notes(h).any { it.startsWith("CLI 撤回了这次提问") })

            h.manager.answerQuestions(mapOf("Which one?" to "A"))
            delay(100)
            assertTrue(responses(h.process).isEmpty())
            // 答案也不回显进对话流 —— 它没送出去
            assertFalse(notes(h).any { "→ A" in it })
        }
    }

    @Test
    fun `a cancel for some other request leaves the card alone`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.emit(dangerousRm("perm-1"))
            h.awaitState("权限卡") { it.pendingPermission != null }
            h.process.emit(cancel("someone-else"))
            delay(200)
            assertEquals("perm-1", h.manager.state.value.pendingPermission?.requestId)
            assertFalse(notes(h).any { "撤回" in it })

            assertTrue(h.manager.answerPermission(allow = true))
            h.awaitCondition("应答写出") { responses(h.process).isNotEmpty() }
        }
    }

    /** 托管 Bash 等服务出结论的那几秒里 CLI 撤回了请求：服务照样留着，迟到的 deny 不写 */
    @Test
    fun `a hosted Bash withdrawn while the service starts gets no late deny`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.emit(
                canUseTool(
                    requestId = "perm-host",
                    toolName = "Bash",
                    input = buildJsonObject {
                        put("command", "python3 -m http.server 8799")
                        put("description", "Start the server")
                        put("run_in_background", true)
                    },
                    toolUseId = "toolu_host",
                ),
            )
            h.awaitCondition("交给进程表") { h.services.started.isNotEmpty() }
            h.process.emit(cancel("perm-host"))
            // 撤回帧先被读循环处理完，再让服务出结论
            delay(200)
            h.services.settle("svc1", LocalServiceStatus.Running)
            h.awaitState("托管结局") { s -> s.items.any { it is ChatItem.Note && it.text.startsWith("已托管到进程表") } }
            delay(100)
            assertTrue(responses(h.process).isEmpty())
        }
    }

    // ---------------------------------------------------------------- 纯逻辑

    private val bash = ClaudeCodeEvent.PermissionRequest(
        requestId = "r",
        toolName = "Bash",
        input = JsonObject(emptyMap()),
    )

    @Test
    fun `the note stays quiet when the user pressed stop`() {
        assertNull(cliWithdrawalNote(bash, byOurInterrupt = true))
    }

    @Test
    fun `the note names the tool and does not claim a timeout`() {
        val note = cliWithdrawalNote(bash, byOurInterrupt = false)!!
        assertTrue(note, note.startsWith("CLI 撤回了「Bash」的权限请求"))
        assertFalse(note, "超时" in note)
        val question = cliWithdrawalNote(bash.copy(toolName = ASK_USER_QUESTION_TOOL), byOurInterrupt = false)!!
        assertTrue(question, question.startsWith("CLI 撤回了这次提问"))
    }

    @Test
    fun `withdrawn ids are remembered up to the capacity`() {
        val ids = WithdrawnCliRequests()
        repeat(WithdrawnCliRequests.CAPACITY + 1) { ids.add("r$it") }
        assertFalse("r0" in ids)
        assertTrue("r1" in ids)
        assertTrue("r${WithdrawnCliRequests.CAPACITY}" in ids)
    }
}
