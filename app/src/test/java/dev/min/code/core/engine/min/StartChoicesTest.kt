package dev.min.code.core.engine.min

import dev.min.code.core.engine.Choice
import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 开始面板的选项表：哪些档能选、默认选哪个、默认模型取哪个。 */
class StartChoicesTest {
    private val a = ApiProfile("a", label = "Alpha", token = "ka", baseUrl = "https://a.test")
    private val b = ApiProfile("b", label = "Beta", token = "kb", baseUrl = "https://b.test")
    private val noKey = ApiProfile("c", label = "Gamma", token = "", baseUrl = "https://c.test")
    private val modes = listOf(Choice("default", "Default"), Choice("plan", "Plan"))

    private fun choices(
        settings: AppSettings,
        lastUsed: String? = null,
        lastModel: Map<String, String> = emptyMap(),
        models: Map<String, List<String>> = emptyMap(),
        default: Map<String, String> = emptyMap(),
    ) = startChoicesOf(
        settings, lastUsed, lastModel::get, { models[it.id].orEmpty() }, { default[it.id] }, modes, "default",
    )

    @Test fun `only profiles with a key are offered`() {
        val c = choices(AppSettings(profiles = listOf(a, noKey, b)))
        assertEquals(listOf("a", "b"), c.profiles.map { it.id })
        assertEquals(listOf("Alpha", "Beta"), c.profiles.map { it.label })
    }

    @Test fun `the default profile is the last used one, then the global active one, then the first usable`() {
        val settings = AppSettings(profiles = listOf(a, b), activeProfileId = "a")
        assertEquals("b", choices(settings, lastUsed = "b").defaultProfileId)
        assertEquals("a", choices(settings, lastUsed = null).defaultProfileId)
        // 最近用的那个档已经没 key / 被删了：落到全局当前档
        assertEquals("a", choices(settings, lastUsed = "c").defaultProfileId)
        assertEquals("a", choices(AppSettings(profiles = listOf(a, b), activeProfileId = "")).defaultProfileId)
    }

    @Test fun `the default model is the one last picked on that profile, else the profile's own default`() {
        val c = choices(
            AppSettings(profiles = listOf(a, b)),
            lastModel = mapOf("a" to "alpha-2"),
            models = mapOf("a" to listOf("alpha-1", "alpha-2"), "b" to listOf("beta-1")),
            default = mapOf("a" to "alpha-1", "b" to "beta-1"),
        )
        assertEquals("alpha-2", c.profiles.first { it.id == "a" }.defaultModelId)
        assertEquals("beta-1", c.profiles.first { it.id == "b" }.defaultModelId)
        assertEquals(listOf("alpha-1", "alpha-2"), c.profiles.first { it.id == "a" }.models.map { it.id })
        assertTrue(c.profiles.first { it.id == "a" }.models.all { it.profileId == "a" })
    }

    @Test fun `a profile with no known models has none and no default, so the panel asks for a typed id`() {
        val c = choices(AppSettings(profiles = listOf(a)))
        assertEquals(emptyList<Choice>(), c.profiles.single().models)
        assertNull(c.profiles.single().defaultModelId)
    }

    @Test fun `no usable profile gives an empty table and no default`() {
        val c = choices(AppSettings(profiles = listOf(noKey)))
        assertTrue(c.profiles.isEmpty())
        assertNull(c.defaultProfileId)
        assertEquals(modes, c.modes)
        assertEquals("default", c.defaultModeId)
    }
}
