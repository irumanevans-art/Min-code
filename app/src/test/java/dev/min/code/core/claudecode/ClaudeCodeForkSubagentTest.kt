package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem
import dev.min.code.ui.session.permissionOriginOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fork 子 agent（`Agent{subagent_type:"fork"}`，CLAUDE_CODE_FORK_SUBAGENT=1 才开）的整条链路，
 * 喂的是真 CLI 2.1.286 录下来的帧（fork_background_permission.txt）。
 *
 * 实录的顺序是这个测试的要点：
 * 1. 主线程 `task_started{subagent_type:"fork", is_backgrounded:true}`，Agent 结果立刻是 `async_launched`；
 * 2. 主线程说 LAUNCHED、**result 收尾**；
 * 3. 收尾之后 fork 才干到要权限的那一步，发 `can_use_tool{agent_id=<fork 的 task_id>}`；
 * 4. 用户允许后它跑完：`task_updated` → `task_notification`，CLI 自己再开一轮向主线程交代结果。
 */
class ClaudeCodeForkSubagentTest {

    private val forkTaskId = "a0bf19a8052ec9945"

    private fun fakeCli(h: ManagerHarness) = FakeCliProcess(
        h.fixture,
        // 夹具只录到 initialize / set_max_thinking_tokens 的应答：get_settings / get_session_cost 给最小应答
        overrides = mapOf(
            "get_settings" to { _ ->
                buildJsonObject {
                    put("subtype", "success")
                    put("response", buildJsonObject {
                        put("applied", buildJsonObject {
                            put("model", "claude-haiku-4-5-20251001")
                            put("effort", "low")
                            put("ultracode", false)
                        })
                    })
                }
            },
            "get_session_cost" to { _ ->
                buildJsonObject {
                    put("subtype", "success")
                    put("response", buildJsonObject { put("text", "Total cost: $0.02") })
                }
            },
        ),
    )

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    private fun responses(process: FakeCliProcess): List<JsonObject> = process.written
        .filter { it.str("type") == "control_response" }
        .map { it["response"]!!.jsonObject }

    @Test
    fun `a fork's permission request arrives after the main turn and is shown, answered and followed to the end`() = runBlocking<Unit> {
        ManagerHarness("fork_background_permission").use { h ->
            h.nextProcess = { fakeCli(h) }
            h.startAndHandshake()
            h.manager.send("launch a fork")

            // 主线程 result 之后，fork 的权限请求才到：卡必须挂出来
            val asked = h.awaitState("fork 的权限卡") { it.pendingPermission?.agentId == forkTaskId }
            assertFalse("主回合已经收尾", asked.busy && asked.lastTurnFinishedAt == null)
            val pending = asked.pendingPermission!!
            assertEquals("Bash", pending.toolName)

            // 任务表：类型 fork、后台、在跑；描述与任务原文来自 task_started
            val task = asked.tasks.single { it.id == forkTaskId }
            assertEquals(FORK_SUBAGENT_TYPE, task.subagentType)
            assertTrue(task.backgrounded)
            assertTrue(task.isRunning)

            // 线程：归属到发起它的那张 Agent 卡，不是别的
            val thread = subagentThreads(asked.items, asked.tasks).single()
            assertEquals(forkTaskId, thread.agentId)
            assertEquals(FORK_SUBAGENT_TYPE, thread.agentType)
            assertEquals("fork probe", thread.description)
            assertEquals("Run the Bash command `touch fork_marker.txt` and then reply FORK_DONE.", thread.prompt)
            assertEquals(SubagentThread.Phase.Running, thread.phase)
            assertTrue(thread.tracked)
            // 它干的活挂在卡上（Bash 调用）
            assertTrue(thread.items.any { it is ChatItem.ToolCall && it.name == "Bash" })

            // 权限卡上说清是谁要的：后台的分叉子 agent，不是主线程
            val origin = permissionOriginOf(pending, asked.tasks, asked.permissionQueue.size)
            assertEquals("fork probe", origin.agent)
            assertTrue(origin.fork)
            assertTrue(origin.background)

            assertTrue(h.manager.answerPermission(allow = true))
            h.awaitCondition("应答写出") { responses(h.process).any { it.str("request_id") == pending.requestId } }

            // 它跑完 → 任务终局、CLI 自开的一轮收尾
            val end = h.awaitState("fork 跑完且主线程交代完") { st ->
                !st.busy && st.items.any { it is ChatItem.AssistantText && "Fork completed" in it.text }
            }
            assertNull(end.pendingPermission)
            val done = subagentThreads(end.items, end.tasks).single()
            assertEquals(SubagentThread.Phase.Done, done.phase)
            val bash = done.items.filterIsInstance<ChatItem.ToolCall>().single { it.name == "Bash" }
            assertEquals(ChatItem.ToolCall.Status.Done, bash.status)
            assertTrue(done.items.any { it is ChatItem.AssistantText && it.text == "FORK_DONE" })
            // 完成通知点名：哪个分身、结果是什么
            val notes = end.items.filterIsInstance<ChatItem.Note>().map { it.text }
            assertTrue(notes.toString(), "分身「fork probe」完成：FORK_DONE" in notes)
            assertTrue(end.tasks.none { it.isRunning })
        }
    }
}
