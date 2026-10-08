package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.CRON_MAX_ACTIVE
import dev.min.agent.spi.CronBoard
import dev.min.agent.spi.CronStatus
import dev.min.agent.spi.CronTask
import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolResult
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.call
import dev.min.agent.testkit.reject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** 定时任务三件套：入参校验、结果文字、声明的效果。落事件 / 触发 / 花费上限在内核的 CronSessionTest */
class CronToolsTest {
    /** 2026-10-08 08:00 UTC */
    private val t0 = 1_791_446_400_000L
    private val minute = 60_000L
    private val profile = HarnessProfile(ModelFamily.Claude, EditStyle.StrReplace)

    private fun ctx(board: CronBoard): ToolContext {
        val base = TestToolContext()
        return object : ToolContext by base {
            override val crons: CronBoard = board
        }
    }

    private fun board(tasks: List<CronTask> = emptyList(), refusal: String? = null) =
        CronBoard(tasks, nextId = "c${tasks.size + 1}", nowMs = t0, zone = ZoneOffset.UTC, refusal = refusal)

    private fun ToolResult.text() = content.filterIsInstance<TextPart>().joinToString("") { it.text }

    private fun task(id: String, status: CronStatus = CronStatus.Active, recurring: Boolean = true, interval: Long? = 30 * minute, cron: String? = null, ended: String? = null, fires: Int = 0) =
        CronTask(id, "检查构建 $id", recurring, 0.05, t0, cron = cron, intervalMs = interval.takeIf { cron == null }, expiresAtMs = (t0 + 7 * 24 * 60 * minute).takeIf { recurring }, fires = fires, status = status, endedBecause = ended)

    // ---- CronCreate ----

    @Test
    fun `creating a recurring interval task declares the effect and previews the next fire`() = runBlocking {
        val result = CronCreateTool.call(ctx(board()), "every" to "30m", "prompt" to "检查构建")
        assertFalse(result.text(), result.isError)
        assertEquals(
            listOf(SessionEffect.CronCreate("检查构建", recurring = true, maxBudgetUsd = 0.05, cron = null, intervalMs = 30 * minute)),
            result.effects,
        )
        val text = result.text()
        assertTrue(text, text.startsWith("已创建定时任务 c1：每 30 分钟（循环，创建 7 天后自动过期）"))
        assertTrue(text, text.contains("下次触发约 2026-10-08 08:30 UTC"))
        assertTrue(text, text.contains("花费上限 \$0.05"))
        assertTrue("把手机上的现实写给模型", text.contains("App 被系统杀掉时不跑"))
    }

    @Test
    fun `a one shot cron task takes the budget and the recurring flag`() = runBlocking {
        val result = CronCreateTool.call(ctx(board()), "cron" to "0 9 * * 1-5", "prompt" to "晨报", "recurring" to false, "max_budget_usd" to 0.2)
        assertEquals(
            listOf(SessionEffect.CronCreate("晨报", recurring = false, maxBudgetUsd = 0.2, cron = "0 9 * * 1-5", intervalMs = null)),
            result.effects,
        )
        assertTrue(result.text(), result.text().contains("一次性，触发一次就结束") && result.text().contains("2026-10-08 09:00 UTC") && result.text().contains("\$0.20"))
    }

    @Test
    fun `the new task gets the number the kernel will assign`() = runBlocking {
        val result = CronCreateTool.call(ctx(board(listOf(task("c1"), task("c2")))), "every" to "1h", "prompt" to "x")
        assertTrue(result.text(), result.text().startsWith("已创建定时任务 c3："))
    }

    @Test
    fun `creation is refused outside the root session, past fifty tasks and for schedules that never match`() = runBlocking {
        val refused = CronCreateTool.call(ctx(board(refusal = "定时任务只能在根会话里建")), "every" to "30m", "prompt" to "x")
        assertTrue(refused.isError)
        assertTrue(refused.effects.isEmpty())
        assertEquals("定时任务只能在根会话里建", refused.text())

        val full = (1..CRON_MAX_ACTIVE).map { task("c$it") }
        val tooMany = CronCreateTool.call(ctx(board(full)), "every" to "30m", "prompt" to "x")
        assertTrue(tooMany.isError && tooMany.text().contains("上限") && tooMany.effects.isEmpty())

        val withEnded = full.dropLast(1) + task("c50", CronStatus.Cancelled, ended = "已取消")
        assertFalse("已结束的不占名额", CronCreateTool.call(ctx(board(withEnded)), "every" to "30m", "prompt" to "x").isError)

        val never = CronCreateTool.call(ctx(board()), "cron" to "0 0 30 2 *", "prompt" to "x")
        assertTrue(never.isError && never.text().contains("不会匹配任何时刻") && never.effects.isEmpty())
    }

    @Test
    fun `create rejects bad input with a message the model can act on`() {
        assertTrue(CronCreateTool.reject("prompt" to "x").contains("恰好给一个"))
        assertTrue(CronCreateTool.reject("prompt" to "x", "cron" to "* * * * *", "every" to "5m").contains("恰好给一个"))
        assertTrue(CronCreateTool.reject("cron" to "* * * * *").contains("prompt"))
        assertTrue(CronCreateTool.reject("prompt" to "x", "cron" to "61 * * * *").contains("分钟"))
        assertTrue(CronCreateTool.reject("prompt" to "x", "every" to "30s").contains("30m / 2h / 1d"))
        assertTrue(CronCreateTool.reject("prompt" to "x", "every" to "5m", "recurring" to "yes").contains("recurring"))
        assertTrue(CronCreateTool.reject("prompt" to "x", "every" to "5m", "max_budget_usd" to 0).contains("max_budget_usd"))
        assertTrue(CronCreateTool.reject("prompt" to "x", "every" to "5m", "max_budget_usd" to -1.5).contains("max_budget_usd"))
        assertTrue(CronCreateTool.reject("prompt" to "x", "every" to "5m", "max_budget_usd" to "0.1").contains("max_budget_usd"))
        assertTrue(CronCreateTool.reject("prompt" to " ", "every" to "5m").contains("prompt"))
    }

    // ---- CronDelete ----

    @Test
    fun `deleting declares the cancel effect, and unknown or finished tasks are explained`() = runBlocking {
        val ctx = ctx(board(listOf(task("c1"), task("c2", CronStatus.Expired, ended = "花费上限"))))
        val ok = CronDeleteTool.call(ctx, "id" to "c1")
        assertEquals(listOf<SessionEffect>(SessionEffect.CronDelete("c1")), ok.effects)
        assertEquals("已取消定时任务 c1（每 30 分钟）。", ok.text())

        val missing = CronDeleteTool.call(ctx, "id" to "c9")
        assertTrue(missing.isError && missing.effects.isEmpty())
        assertTrue(missing.text(), missing.text().contains("没有定时任务 c9") && missing.text().contains("进行中的：c1"))

        val ended = CronDeleteTool.call(ctx, "id" to "c2")
        assertTrue(ended.isError && ended.text().contains("花费上限"))
        assertTrue(CronDeleteTool.reject().contains("id"))
    }

    // ---- CronList ----

    @Test
    fun `listing shows qualifiers, budget, fires, next time and the prompt, and why finished tasks ended`() = runBlocking {
        val tasks = listOf(
            task("c1", fires = 3),
            task("c2", recurring = false, interval = null, cron = "0 9 * * 1-5"),
            task("c3", CronStatus.Expired, ended = "花费上限（这次运行花了 \$0.0600，到了单次上限 \$0.0500）"),
        )
        val text = CronListTool.call(ctx(board(tasks))).text()
        val lines = text.lines()
        assertTrue(text, lines[0].startsWith("- c1｜循环｜每 30 分钟｜单次上限 \$0.05｜已触发 3 次｜下次 2026-10-08 08:30 UTC，2026-10-15 08:00 UTC 过期"))
        assertEquals("  提示词：检查构建 c1", lines[1])
        assertEquals("- c2｜一次性｜cron 0 9 * * 1-5｜单次上限 \$0.05｜已触发 0 次｜下次 2026-10-08 09:00 UTC", lines[2])
        assertTrue(text, text.contains("c3｜循环｜每 30 分钟｜单次上限 \$0.05｜已触发 0 次｜已结束：花费上限"))
        assertEquals("本会话没有定时任务。", CronListTool.call(ctx(board())).text())
    }

    @Test
    fun `the tools carry the contract the model must know`() {
        val create = CronCreateTool.description(profile)
        assertTrue(create.contains("7 天") && create.contains("错过的不补跑") && create.contains("花费上限") && create.contains("被系统杀掉"))
        assertTrue(create.contains("预设任务") && create.contains("不是用户发的话"))
        for (tool in listOf(CronCreateTool, CronDeleteTool, CronListTool)) {
            assertTrue("${tool.name} 只改会话自己的任务表，不需要审批", tool.traits.readOnly)
            val schema = tool.inputSchema.toString()
            assertFalse(tool.name, schema.contains("oneOf") || schema.contains("\$ref") || schema.contains("anyOf"))
        }
        assertTrue(CronListTool.traits.concurrencySafe)
        assertFalse("编号一个一个分配", CronCreateTool.traits.concurrencySafe)
        assertEquals(JsonPrimitive("object"), CronListTool.inputSchema["type"])
    }
}

/** ScheduleWakeup：同一个机制的另一个入口 */
class ScheduleWakeupToolTest {
    private val t0 = 1_791_446_400_000L
    private val profile = HarnessProfile(ModelFamily.Claude, EditStyle.StrReplace)

    private fun ctx(tasks: List<CronTask> = emptyList(), refusal: String? = null): ToolContext {
        val base = TestToolContext()
        val board = CronBoard(tasks, nextId = "c${tasks.size + 1}", nowMs = t0, zone = ZoneOffset.UTC, refusal = refusal)
        return object : ToolContext by base {
            override val crons: CronBoard = board
        }
    }

    private fun wakeup(id: String) = CronTask(id, "继续", false, 0.05, t0, intervalMs = 120_000, source = dev.min.agent.spi.CRON_SOURCE_WAKEUP)

    private fun ToolResult.text() = content.filterIsInstance<TextPart>().joinToString("") { it.text }

    @Test
    fun `scheduling declares a one shot interval task marked as a wakeup`() = runBlocking {
        val result = ScheduleWakeupTool.call(ctx(), "delaySeconds" to 270, "prompt" to "继续看构建", "reason" to "等 CI")
        assertEquals(
            listOf<SessionEffect>(SessionEffect.CronCreate("继续看构建", recurring = false, maxBudgetUsd = 0.05, intervalMs = 270_000, source = "wakeup")),
            result.effects,
        )
        assertTrue(result.text(), result.text().contains("270 秒后唤醒") && result.text().contains("2026-10-08 08:04 UTC") && result.text().contains("等 CI"))
    }

    @Test
    fun `a new wakeup replaces the pending one and stop cancels it`() = runBlocking {
        val pending = listOf(wakeup("c1"), CronTask("c2", "别的", true, 0.05, t0, intervalMs = 600_000))
        val replaced = ScheduleWakeupTool.call(ctx(pending), "delaySeconds" to 60, "prompt" to "再来")
        assertEquals(SessionEffect.CronDelete("c1"), replaced.effects.first())
        assertTrue(replaced.effects.last() is SessionEffect.CronCreate)
        assertEquals("只替自己的，不碰 CronCreate 建的任务", 2, replaced.effects.size)
        assertTrue(replaced.text().contains("替掉了还没触发的 c1"))

        val stopped = ScheduleWakeupTool.call(ctx(pending), "stop" to true)
        assertEquals(listOf<SessionEffect>(SessionEffect.CronDelete("c1")), stopped.effects)
        assertEquals("没有安排中的唤醒。", ScheduleWakeupTool.call(ctx(), "stop" to true).text())
    }

    @Test
    fun `delay must be a whole number of seconds between a minute and an hour`() {
        assertTrue(ScheduleWakeupTool.reject("prompt" to "x").contains("delaySeconds"))
        assertTrue(ScheduleWakeupTool.reject("delaySeconds" to 59, "prompt" to "x").contains("60 到 3600"))
        assertTrue(ScheduleWakeupTool.reject("delaySeconds" to 3601, "prompt" to "x").contains("60 到 3600"))
        assertTrue(ScheduleWakeupTool.reject("delaySeconds" to 90.5, "prompt" to "x").contains("整数秒"))
        assertTrue(ScheduleWakeupTool.reject("delaySeconds" to 60).contains("prompt"))
        assertTrue(ScheduleWakeupTool.reject("stop" to "yes").contains("stop"))
    }

    @Test
    fun `it is refused where cron is refused and describes the shared rules`() = runBlocking {
        val refused = ScheduleWakeupTool.call(ctx(refusal = "只能在根会话里建"), "delaySeconds" to 60, "prompt" to "x")
        assertTrue(refused.isError && refused.effects.isEmpty())
        val d = ScheduleWakeupTool.description(profile)
        assertTrue(d.contains("同一时刻最多一个") && d.contains("花费上限") && d.contains("被系统杀掉"))
        assertTrue(ScheduleWakeupTool.traits.readOnly)
    }
}
