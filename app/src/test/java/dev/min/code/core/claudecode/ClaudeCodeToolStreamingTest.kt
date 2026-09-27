package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用的增量路径：`content_block_start` 就插卡，`input_json_delta` 一片一片补入参，
 * 整块 tool_use 到了只补齐、不再插第二张。喂的是真 CLI 录下来的帧。
 */
class ClaudeCodeToolStreamingTest {

    private fun ClaudeCodeManager.SessionState.toolCards() = items.filterIsInstance<ChatItem.ToolCall>()

    @Test
    fun `tool card appears on block start and fills from input deltas`() = runBlocking<Unit> {
        ManagerHarness("bash_with_permission").use { h ->
            h.startAndHandshake()
            h.manager.send("run it")
            // 录制里 content_block_start 之后就是一串 input_json_delta：卡片在整块 tool_use 之前已经在了，
            // 而且摘要要读的 command 字段已经从片段里补出来
            val early = h.awaitState("入参流到一半") { s ->
                s.toolCards().singleOrNull()?.input?.get("command") != null
            }
            val card = early.toolCards().single()
            assertEquals("Bash", card.name)
            assertEquals("toolu_01UfwD4k1txn6u1ht5Y5qdJz", card.toolUseId)
            assertEquals(ChatItem.ToolCall.Status.Running, card.status)

            val asking = h.awaitState("权限请求") { it.pendingPermission != null }
            // 整块 tool_use 到了：还是那一张卡（id 不变，列表不会当成新条目），入参是完整的
            val filled = asking.toolCards().single()
            assertEquals(card.id, filled.id)
            assertEquals(
                "mkdir -p /tmp/minfx && echo hello-from-min | tee /tmp/minfx/out.txt",
                filled.input["command"]!!.jsonPrimitive.content,
            )

            h.manager.answerPermission(allow = true)
            val s = h.awaitTurnEnd()
            assertEquals(1, s.toolCards().size)
            assertEquals(ChatItem.ToolCall.Status.Done, s.toolCards().single().status)
        }
    }

    @Test
    fun `tool card whose input never finished is dropped when the turn ends`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.holdTurns = true
            h.manager.send("hi")
            h.awaitState("发出") { it.busy }
            // 入参流到一半，这条消息就结束了：整块 tool_use 永远不会来，CLI 也没跑这个工具
            h.process.emit("""{"type":"stream_event","event":{"type":"message_start","message":{}},"parent_tool_use_id":null}""")
            h.process.emit("""{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_cut","name":"Read","input":{}}},"parent_tool_use_id":null}""")
            h.process.emit("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"file_path\":\"/tmp/a"}},"parent_tool_use_id":null}""")
            val cut = h.awaitState("半截卡") { s ->
                s.toolCards().any { it.toolUseId == "toolu_cut" && it.input["file_path"] != null }
            }
            assertEquals("/tmp/a", cut.toolCards().single().input["file_path"]!!.jsonPrimitive.content)

            // 下一条消息开始：上一条里没等到整块 tool_use 的卡撤掉，不能留着一直转圈
            h.process.emit("""{"type":"stream_event","event":{"type":"message_start","message":{}},"parent_tool_use_id":null}""")
            val after = h.awaitState("撤卡") { s -> s.toolCards().none { it.toolUseId == "toolu_cut" } }
            assertTrue(after.toolCards().isEmpty())
            h.process.releaseTurn()
            h.awaitTurnEnd()
        }
    }
}
