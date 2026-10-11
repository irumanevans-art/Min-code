package dev.min.agent.kernel

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamSettingsTest {
    private fun read(raw: kotlinx.serialization.json.JsonElement?): Pair<Int, List<String>> {
        val notes = mutableListOf<String>()
        return teamMaxConcurrentFrom(raw, notes::add) to notes
    }

    @Test
    fun `unset means three and values up to eight are taken as they are`() {
        assertEquals(3 to emptyList<String>(), read(null))
        assertEquals(1 to emptyList<String>(), read(JsonPrimitive(1)))
        assertEquals(8 to emptyList<String>(), read(JsonPrimitive(8)))
    }

    @Test
    fun `above the hard cap of eight it is clamped with a note`() {
        val (value, notes) = read(JsonPrimitive(12))
        assertEquals(8, value)
        assertTrue(notes.single().contains("硬上限 8"))
    }

    @Test
    fun `bad values fall back to the default with a note`() {
        for (bad in listOf(JsonPrimitive(0), JsonPrimitive(-2), JsonPrimitive("3"), JsonPrimitive(2.5), JsonPrimitive(true))) {
            val (value, notes) = read(bad)
            assertEquals("$bad", 3, value)
            assertEquals("$bad", 1, notes.size)
        }
    }
}
