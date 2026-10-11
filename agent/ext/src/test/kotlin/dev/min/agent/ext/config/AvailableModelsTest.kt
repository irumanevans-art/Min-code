package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailableModelsTest {
    private val fs = InMemoryFs()
    private val managed = "/etc/claude-code/managed-settings.json"
    private fun load() = runBlocking { SettingsLoader.load(fs, GuestPath("/workspace"), GuestPath("/root")) }.merged

    @Test
    fun `a managed availableModels alone applies and lower layers cannot extend it`() {
        fs.file(managed, """{"availableModels":["sonnet"]}""")
        fs.file("/root/.claude/settings.json", """{"availableModels":["opus","haiku"]}""")
        fs.file("/workspace/.claude/settings.json", """{"availableModels":["fable"]}""")
        fs.file("/workspace/.claude/settings.local.json", """{"availableModels":["claude-opus-5-5"]}""")
        // 托管层定义了就只认它：低层一个都不并入，也不跨托管源合并
        assertEquals(AvailableModelsSetting(listOf("sonnet"), managed = true), load().availableModelsSetting())
    }

    @Test
    fun `an empty managed list blocks everything instead of falling back to user layers`() {
        fs.file(managed, """{"availableModels":[]}""")
        fs.file("/root/.claude/settings.json", """{"availableModels":["sonnet"]}""")
        assertEquals(AvailableModelsSetting(emptyList(), managed = true), load().availableModelsSetting())
    }

    @Test
    fun `without managed policy the user project and local lists are concatenated and deduplicated`() {
        fs.file("/root/.claude/settings.json", """{"availableModels":["sonnet"]}""")
        fs.file("/workspace/.claude/settings.json", """{"availableModels":["haiku","sonnet"]}""")
        assertEquals(AvailableModelsSetting(listOf("sonnet", "haiku"), managed = false), load().availableModelsSetting())
    }

    @Test
    fun `no layer defining it means no restriction, and a wrong typed layer is ignored`() {
        assertNull(load().availableModelsSetting())
        fs.file("/root/.claude/settings.json", """{"availableModels":"sonnet"}""")
        assertNull(load().availableModelsSetting())
        fs.file("/workspace/.claude/settings.json", """{"availableModels":["sonnet",3,"  ",""]}""")
        assertEquals(AvailableModelsSetting(listOf("sonnet"), managed = false), load().availableModelsSetting())
    }

    @Test
    fun `a family alias allows every version of that family`() {
        val entries = listOf("sonnet")
        assertTrue(AvailableModels.allows("claude-sonnet-5-5", entries))
        assertTrue(AvailableModels.allows("claude-sonnet-4-5-20250929", entries))
        assertFalse(AvailableModels.allows("claude-opus-5-5", entries))
        assertFalse(AvailableModels.allows("deepseek-flash", entries))
    }

    @Test
    fun `a version prefix matches its dated ids only at a segment boundary`() {
        val entries = listOf("claude-sonnet-4-5")
        assertTrue(AvailableModels.allows("claude-sonnet-4-5-20250929", entries))
        assertTrue(AvailableModels.allows("claude-sonnet-4-5", entries))
        assertFalse(AvailableModels.allows("claude-sonnet-4-50", entries))
        assertFalse(AvailableModels.allows("claude-sonnet-5-5", entries))
    }

    @Test
    fun `a specific version in a family turns that family's wildcard off`() {
        val entries = listOf("sonnet", "claude-sonnet-4-5")
        assertTrue(AvailableModels.allows("claude-sonnet-4-5-20250929", entries))
        assertFalse(AvailableModels.allows("claude-sonnet-5-5", entries))
        // 别的家族不受影响
        assertTrue(AvailableModels.allows("claude-haiku-5-5", listOf("sonnet", "haiku", "claude-sonnet-4-5")))
    }

    @Test
    fun `the 1m suffix is ignored when matching`() {
        assertTrue(AvailableModels.allows("claude-opus-4-6[1m]", listOf("opus")))
        assertTrue(AvailableModels.allows("claude-opus-4-6", listOf("claude-opus-4-6[1m]")))
    }

    @Test
    fun `an alias that the list blocks is replaced by the newest permitted version of its family`() {
        // CC：`["sonnet","claude-opus-4-6"]` 下 opus 选到 4.6；目录里靠前的先被选中（目录顺序即新旧顺序）
        assertEquals("claude-opus-4-6", AvailableModels.pick("opus", listOf("sonnet", "claude-opus-4-6"), listOf("claude-opus-5-5")))
        assertEquals("claude-opus-5-5", AvailableModels.pick("opus", listOf("opus"), listOf("claude-opus-5-5")))
        assertNull(AvailableModels.pick("opus", listOf("sonnet"), listOf("claude-opus-5-5")))
    }
}
