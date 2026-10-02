package dev.min.agent.tools

import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SkillInvocation
import dev.min.agent.spi.SkillPort
import dev.min.agent.spi.ToolContext
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.call
import dev.min.agent.testkit.parsed
import dev.min.agent.testkit.reject
import dev.min.agent.testkit.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Skill 工具：入参与 Claude Code 同名（skill 必填、args 可选），结果就是端口给的正文，被拒是写给模型的错误 */
class SkillToolTest {
    private class FakeSkills(val answer: (String, String?) -> SkillInvocation) : SkillPort {
        val calls = mutableListOf<Pair<String, String?>>()
        override suspend fun invoke(name: String, args: String?): SkillInvocation {
            calls += name to args
            return answer(name, args)
        }
    }

    private fun ctx(skills: SkillPort): ToolContext {
        val base = TestToolContext()
        return object : ToolContext by base {
            override val skills = skills
        }
    }

    @Test
    fun `schema has a required skill string and an optional args string`() {
        val s = SkillTool.inputSchema
        assertEquals(JsonArray(listOf(JsonPrimitive("skill"))), s["required"])
        val props = s["properties"] as JsonObject
        assertEquals(setOf("skill", "args"), props.keys)
        props.values.forEach { assertEquals(JsonPrimitive("string"), (it as JsonObject)["type"]) }
        assertEquals("Skill", SkillTool.name)
    }

    @Test
    fun `parse needs a skill name and strips a leading slash`() {
        assertTrue(SkillTool.reject().contains("`skill`"))
        assertTrue(SkillTool.reject("skill" to "  ").contains("不能为空"))
        assertTrue(SkillTool.reject("skill" to "/").contains("不能为空"))
        assertTrue(SkillTool.reject("skill" to 3).contains("字符串"))
        assertEquals(SkillInput("pdf", null), SkillTool.parsed("skill" to "pdf"))
        assertEquals(SkillInput("pdf", "a.pdf out"), SkillTool.parsed("skill" to "/pdf", "args" to "a.pdf out"))
    }

    @Test
    fun `the result is the rendered body from the port and args are passed through verbatim`() = runBlocking {
        val port = FakeSkills { name, args -> SkillInvocation.Loaded(name, "body for $name with $args") }
        val result = SkillTool.call(ctx(port), "skill" to "fix", "args" to "\"a b\" c")
        assertFalse(result.isError)
        assertEquals("body for fix with \"a b\" c", result.text)
        assertEquals(listOf("fix" to "\"a b\" c"), port.calls)
    }

    @Test
    fun `a rejected skill is an error result carrying the reason for the model`() = runBlocking {
        val port = FakeSkills { _, _ -> SkillInvocation.Rejected("只能由用户用 /deploy 触发") }
        val result = SkillTool.call(ctx(port), "skill" to "deploy")
        assertTrue(result.isError)
        assertEquals("只能由用户用 /deploy 触发", result.text)
    }

    @Test
    fun `without a skills port the tool fails politely`() = runBlocking {
        val result = SkillTool.call(TestToolContext(), "skill" to "x")
        assertTrue(result.isError)
        assertTrue(result.text.contains("skills"))
    }

    @Test
    fun `the permission ask carries the skill name as specifier and the tool is read only`() {
        val ask = SkillTool.permissionAsk(SkillInput("deploy", "x"), TestToolContext())
        assertEquals("Skill", ask.tool)
        assertEquals(listOf("deploy"), ask.specifiers)
        assertTrue(ask.paths.isEmpty() && ask.commands.isEmpty())
        assertTrue(SkillTool.traits.readOnly && SkillTool.traits.concurrencySafe)
        assertEquals(ResumePolicy.Replay, SkillTool.traits.resumePolicy)
        assertFalse(SkillTool.traits.alwaysAsk)
    }

    @Test
    fun `the description is constant across model families`() {
        val families = ModelFamily.entries.map { SkillTool.description(HarnessProfile(it, EditStyle.StrReplace)) }.toSet()
        assertEquals(1, families.size)
    }
}
