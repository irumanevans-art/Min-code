package dev.min.agent.cli

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronSettingsTest {
    private fun read(raw: kotlinx.serialization.json.JsonElement?): Pair<Double?, List<String>> {
        val notes = mutableListOf<String>()
        return cronMaxBudgetUsdFrom(raw, notes::add) to notes
    }

    @Test
    fun `unset means no ceiling, a positive number is taken as it is`() {
        assertEquals(null to emptyList<String>(), read(null))
        assertEquals(0.5 to emptyList<String>(), read(JsonPrimitive(0.5)))
        assertEquals(2.0 to emptyList<String>(), read(JsonPrimitive(2)))
    }

    @Test
    fun `bad values mean no ceiling with a note instead of a zero cap that would freeze every task`() {
        for (bad in listOf(JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive("1"), JsonPrimitive(true))) {
            val (value, notes) = read(bad)
            assertNull("$bad", value)
            assertEquals("$bad", 1, notes.size)
        }
    }
}

class CronDisableTest {
    @Test
    fun `CLAUDE_CODE_DISABLE_CRON switches scheduled tasks off like Claude Code does`() {
        for (on in listOf("1", "true", "yes", " TRUE ")) assertEquals(on, true, cronDisabledBy { if (it == "CLAUDE_CODE_DISABLE_CRON") on else null })
        for (off in listOf(null, "", "0", "false", "no")) assertEquals("$off", false, cronDisabledBy { if (it == "CLAUDE_CODE_DISABLE_CRON") off else null })
    }

    @Test
    fun `with scheduled tasks disabled the four cron tools are not offered at all`() {
        val cron = listOf("CronCreate", "CronDelete", "CronList", "ScheduleWakeup")
        val on = desktopTools().map { it.name }
        cron.forEach { assertTrue(it in on) }
        val off = desktopTools(cronEnabled = false).map { it.name }
        cron.forEach { assertFalse("$it 不该出现", it in off) }
        assertEquals("别的工具原样，顺序不变", on.filterNot { it in cron }, off)
    }
}
