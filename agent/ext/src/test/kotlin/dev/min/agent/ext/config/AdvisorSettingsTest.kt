package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import dev.min.agent.protocol.ModelRef
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AdvisorSettingsTest {
    private fun merge(layers: List<SettingsLayer>) = SettingsMerger.merge(layers, mutableListOf())
    private fun layer(source: ConfigSource, value: JsonElement) = SettingsLayer(source, GuestPath("/work/${source.label}.json"), GuestPath("/work"), JsonObject(mapOf("advisorModel" to value)))
    @Test fun `advisor follows scalar layer priority and explicit null disables inherited value`() {
        val layers = listOf(layer(ConfigSource.User, JsonPrimitive("p/a")), layer(ConfigSource.Project, JsonPrimitive("q/b")), layer(ConfigSource.Local, JsonPrimitive("r/c")), layer(ConfigSource.Cli, JsonPrimitive("s/d")), layer(ConfigSource.Managed, JsonPrimitive("t/e")))
        assertEquals(ModelRef("t", "e"), merge(layers).advisorSettings("main", true, emptySet()).model)
        assertNull(merge(layers + layer(ConfigSource.Managed, JsonNull)).advisorSettings("main", true, emptySet()).model)
        assertEquals(ModelRef("p", "a"), merge(layers.take(3)).advisorSettings("main", false, emptySet()).model)
        assertEquals(ModelRef("r", "c"), merge(layers.take(3)).advisorSettings("main", false, setOf(GuestPath("/work/local.json"))).model)
    }
    @Test fun `advisor accepts any provider full model id aliases and off`() {
        assertEquals(ModelRef("gemini", "gemini-custom"), parseAdvisorRef("gemini/gemini-custom", "anthropic"))
        assertEquals(ModelRef("main", "custom"), parseAdvisorRef("custom", "main"))
        assertEquals(ModelRef("anthropic", "claude-opus-5-5"), parseAdvisorRef("opus", "anthropic"))
        assertNull(parseAdvisorRef("off", "main"))
        for (value in listOf("", "/x", "p/", "p/a b")) assertThrows(IllegalArgumentException::class.java) { parseAdvisorRef(value, "main") }
    }
}
