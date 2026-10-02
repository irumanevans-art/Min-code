package dev.min.agent.tools

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionEffect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.min.agent.testkit.*

class PlanModeToolsTest {
    private val profile = HarnessProfile(ModelFamily.Claude, EditStyle.StrReplace)
    private val plan = "## 计划\n1. 改 a.kt\n2. 跑测试"

    @Test
    fun `entering plan mode declares the switch and tells the model what it may do`() = runBlocking {
        val ctx = TestToolContext().apply { mode = PermissionMode.AcceptEdits }
        val result = EnterPlanModeTool.call(ctx, "unexpected" to "ignored")
        assertFalse(result.isError)
        assertEquals(listOf(SessionEffect.ModeChange(PermissionMode.Plan)), result.effects)
        assertTrue(result.text, result.text.contains("ExitPlanMode") && result.text.contains("只能读"))
    }

    @Test
    fun `entering again while already planning changes nothing`() = runBlocking {
        val ctx = TestToolContext().apply { mode = PermissionMode.Plan }
        val result = EnterPlanModeTool.call(ctx)
        assertTrue(result.effects.isEmpty())
        assertEquals(EnterPlanModeTool.ALREADY_MESSAGE, result.text)
    }

    @Test
    fun `exiting needs a plan`() {
        assertTrue(ExitPlanModeTool.reject().contains("`plan`"))
        assertTrue(ExitPlanModeTool.reject("plan" to "  \n").contains("不能为空"))
        assertEquals(plan, ExitPlanModeTool.parsed("plan" to "\n$plan\n").plan)
    }

    @Test
    fun `the approval card carries the whole plan`() {
        val ask = ExitPlanModeTool.permissionAsk(ExitPlanModeInput(plan), TestToolContext())
        assertEquals("ExitPlanMode", ask.tool)
        assertEquals(plan, ask.summary)
    }

    @Test
    fun `an approved plan goes back to the mode before planning`() = runBlocking {
        val ctx = TestToolContext().apply {
            mode = PermissionMode.Plan
            modeBeforePlan = PermissionMode.AcceptEdits
        }
        val result = ExitPlanModeTool.call(ctx, "plan" to plan)
        assertFalse(result.isError)
        assertEquals(listOf(SessionEffect.ModeChange(PermissionMode.AcceptEdits)), result.effects)
        assertTrue(result.text, result.text.contains("acceptEdits") && result.text.contains("可以开始实施"))
    }

    @Test
    fun `a session that started in plan mode goes back to default`() = runBlocking {
        val ctx = TestToolContext().apply { mode = PermissionMode.Plan }
        assertEquals(listOf(SessionEffect.ModeChange(PermissionMode.Default)), ExitPlanModeTool.call(ctx, "plan" to plan).effects)
    }

    @Test
    fun `outside plan mode an approved plan leaves the mode alone`() = runBlocking {
        val ctx = TestToolContext().apply {
            mode = PermissionMode.BypassPermissions
            modeBeforePlan = PermissionMode.Default
        }
        val result = ExitPlanModeTool.call(ctx, "plan" to plan)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.text, result.text.contains("bypassPermissions"))
    }

    @Test
    fun `traits and schemas`() {
        with(EnterPlanModeTool.traits) {
            assertTrue(readOnly)
            assertFalse(alwaysAsk)
            assertEquals(ResumePolicy.Replay, resumePolicy)
        }
        with(ExitPlanModeTool.traits) {
            assertTrue("总是要人批准", alwaysAsk)
            assertTrue(readOnly)
            assertFalse(concurrencySafe)
            assertEquals(ResumePolicy.Replay, resumePolicy)
        }
        val empty = EnterPlanModeTool.inputSchema
        assertEquals(JsonPrimitive("object"), empty["type"])
        assertEquals(JsonObject(emptyMap()), empty["properties"])
        assertEquals(JsonArray(emptyList()), empty["required"])
        val exit = ExitPlanModeTool.inputSchema
        assertEquals(JsonArray(listOf(JsonPrimitive("plan"))), exit["required"])
        assertTrue(((exit["properties"] as JsonObject)["plan"] as JsonObject).containsKey("description"))
        listOf(EnterPlanModeTool.description(profile), ExitPlanModeTool.description(profile)).forEach { assertTrue(it.length > 20) }
    }
}
