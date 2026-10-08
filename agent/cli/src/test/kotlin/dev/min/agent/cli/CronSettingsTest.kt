package dev.min.agent.cli

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
