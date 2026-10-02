package dev.min.agent.spi

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 任务事件与任务效果的线形状：字段名、判别值、默认值不落盘；旧日志（没有任务）不受影响 */
class TaskEventsWireTest {
    private fun assertEventWire(event: SessionEvent, expectedJson: String) {
        val text = AgentJson.encodeToString(SessionEvent.serializer(), event)
        assertEquals(AgentJson.parseToJsonElement(expectedJson), AgentJson.parseToJsonElement(text))
        assertEquals(event, AgentJson.decodeFromString(SessionEvent.serializer(), text))
    }

    @Test
    fun `task events keep their wire shape and omit defaults`() {
        assertEventWire(
            TaskCreated("1", "写测试", "给折叠补测试"),
            """{"type":"task_created","taskId":"1","subject":"写测试","description":"给折叠补测试"}""",
        )
        assertEventWire(
            TaskCreated("2", "跑测试", "全量", "正在跑测试", JsonObject(mapOf("k" to JsonPrimitive("v")))),
            """{"type":"task_created","taskId":"2","subject":"跑测试","description":"全量","activeForm":"正在跑测试","metadata":{"k":"v"}}""",
        )
        assertEventWire(
            TaskUpdated("1", TaskPatch(status = TaskStatus.InProgress, addBlocks = listOf("2"))),
            """{"type":"task_updated","taskId":"1","patch":{"status":"in_progress","addBlocks":["2"]}}""",
        )
        assertEventWire(TaskDeleted("3"), """{"type":"task_deleted","taskId":"3"}""")
    }

    @Test
    fun `a metadata patch keeps null values because they mean delete this key`() {
        assertEventWire(
            TaskUpdated("1", TaskPatch(metadata = JsonObject(mapOf("gone" to JsonNull, "kept" to JsonPrimitive(1))))),
            """{"type":"task_updated","taskId":"1","patch":{"metadata":{"gone":null,"kept":1}}}""",
        )
    }

    @Test
    fun `task effects round trip inside a ToolOutcome`() {
        val outcome: SessionEvent = ToolOutcome(
            TurnId("t1"), CallId("c1"),
            ToolResult(
                listOf(TextPart("好")),
                effects = listOf(
                    SessionEffect.TaskCreate("写测试", "详情"),
                    SessionEffect.TaskUpdate("1", TaskPatch(status = TaskStatus.Completed)),
                    SessionEffect.TaskDelete("2"),
                ),
            ),
        )
        val expected = """{"type":"tool_outcome","turnId":"t1","callId":"c1","result":{"content":[{"type":"text","text":"好"}],"effects":[
            {"type":"task_create","subject":"写测试","description":"详情"},
            {"type":"task_update","taskId":"1","patch":{"status":"completed"}},
            {"type":"task_delete","taskId":"2"}]}}"""
        assertEventWire(outcome, expected)
    }

    @Test
    fun `an empty patch is empty and any field makes it not`() {
        assertTrue(TaskPatch().isEmpty)
        assertEquals(false, TaskPatch(owner = "").isEmpty)
        assertEquals(false, TaskPatch(addBlockedBy = listOf("1")).isEmpty)
        assertEquals(false, TaskPatch(metadata = JsonObject(mapOf("k" to JsonNull))).isEmpty)
    }
}
