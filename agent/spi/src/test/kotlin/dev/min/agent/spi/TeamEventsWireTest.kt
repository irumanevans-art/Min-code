package dev.min.agent.spi

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.SessionId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** 团队事件的线形状：字段名、判别值固定；默认值不落盘（旧版本读得出、新版本不会因为多一个默认字段而改格式） */
class TeamEventsWireTest {
    private fun assertEventWire(event: SessionEvent, expectedJson: String) {
        val text = AgentJson.encodeToString(SessionEvent.serializer(), event)
        assertEquals(AgentJson.parseToJsonElement(expectedJson), AgentJson.parseToJsonElement(text))
        assertEquals(event, AgentJson.decodeFromString(SessionEvent.serializer(), text))
    }

    @Test
    fun `team events keep their wire shape and omit defaults`() {
        assertEventWire(
            TeamCreated("t1", SessionId("s1"), listOf(TeamMemberSpec("alice", prompt = "写后端"), TeamMemberSpec("bob", "Explore", "读代码"))),
            """{"type":"team_created","teamId":"t1","leader":"s1","members":[{"name":"alice","prompt":"写后端"},{"name":"bob","agentType":"Explore","prompt":"读代码"}]}""",
        )
        assertEventWire(
            TeamCreated("t1", SessionId("s1"), listOf(TeamMemberSpec("carol", prompt = "审阅", model = "claude-haiku-5-5"))),
            """{"type":"team_created","teamId":"t1","leader":"s1","members":[{"name":"carol","prompt":"审阅","model":"claude-haiku-5-5"}]}""",
        )
        assertEventWire(
            TeamMemberJoined("t1", "alice", SessionId("s2")),
            """{"type":"team_member_joined","teamId":"t1","name":"alice","session":"s2"}""",
        )
        assertEventWire(
            TeamMessage("t1", "m1", "alice", "bob", "接口定好了"),
            """{"type":"team_message","teamId":"t1","messageId":"m1","from":"alice","to":"bob","text":"接口定好了"}""",
        )
        assertEventWire(
            TeamMessage("t1", "m2", "lead", "bob", "大家注意", broadcast = true),
            """{"type":"team_message","teamId":"t1","messageId":"m2","from":"lead","to":"bob","text":"大家注意","broadcast":true}""",
        )
        assertEventWire(
            TeamTaskCreated("t1", "1", "接口设计", by = "lead"),
            """{"type":"team_task_created","teamId":"t1","taskId":"1","subject":"接口设计","by":"lead"}""",
        )
        assertEventWire(
            TeamTaskCreated("t1", "2", "补测试", "覆盖团队清单", "正在补测试", JsonObject(mapOf("p" to JsonPrimitive(1))), "bob"),
            """{"type":"team_task_created","teamId":"t1","taskId":"2","subject":"补测试","description":"覆盖团队清单","activeForm":"正在补测试","metadata":{"p":1},"by":"bob"}""",
        )
        assertEventWire(
            TeamTaskUpdated("t1", "1", TaskPatch(status = TaskStatus.InProgress, owner = "alice"), "alice"),
            """{"type":"team_task_updated","teamId":"t1","taskId":"1","patch":{"status":"in_progress","owner":"alice"},"by":"alice"}""",
        )
        assertEventWire(
            TeamTaskDeleted("t1", "1", "lead"),
            """{"type":"team_task_deleted","teamId":"t1","taskId":"1","by":"lead"}""",
        )
        assertEventWire(
            TeamDisbanded("t1", TeamDisbandReason.UserEnded),
            """{"type":"team_disbanded","teamId":"t1","reason":"user_ended"}""",
        )
    }
}
