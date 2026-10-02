package dev.min.agent.spi

import dev.min.agent.protocol.AgentJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** skills_loaded 的线形状。期望 JSON 按源码里的 @SerialName 与属性名手写，谁改了字段名、判别值或默认值立刻红 */
class SkillEventsWireTest {

    private fun assertEventWire(event: SessionEvent, expectedJson: String) {
        val text = AgentJson.encodeToString(SessionEvent.serializer(), event)
        assertEquals("往返不相等：$text", event, AgentJson.decodeFromString(SessionEvent.serializer(), text))
        assertEquals(AgentJson.parseToJsonElement(expectedJson), AgentJson.parseToJsonElement(text))
    }

    @Test
    fun `skills loaded keeps its wire shape`() {
        assertEventWire(
            SkillsLoaded(
                entries = listOf(
                    SkillEntry(
                        name = "deploy", description = "部署", path = GuestPath("/workspace/.claude/skills/deploy/SKILL.md"),
                        whenToUse = "上线时", source = "project", argumentHint = "[env]", arguments = listOf("env"),
                        disableModelInvocation = true, userInvocable = false,
                    ),
                    SkillEntry(name = "fe:button", description = "d", path = GuestPath("/root/.claude/commands/fe/button.md"), kind = SkillKind.Command),
                ),
                listingBudgetFraction = 0.02,
                listingMaxDescChars = 500,
                notes = listOf("跳过"),
            ),
            """{"type":"skills_loaded","entries":[
                {"name":"deploy","description":"部署","path":"/workspace/.claude/skills/deploy/SKILL.md","whenToUse":"上线时",
                 "source":"project","argumentHint":"[env]","arguments":["env"],"disableModelInvocation":true,"userInvocable":false},
                {"name":"fe:button","description":"d","path":"/root/.claude/commands/fe/button.md","kind":"command"}],
              "listingBudgetFraction":0.02,"listingMaxDescChars":500,"notes":["跳过"]}""",
        )
    }

    @Test
    fun `defaults are not written`() {
        assertEventWire(
            SkillsLoaded(entries = listOf(SkillEntry("a", "d", GuestPath("/p/SKILL.md")))),
            """{"type":"skills_loaded","entries":[{"name":"a","description":"d","path":"/p/SKILL.md"}]}""",
        )
    }
}
