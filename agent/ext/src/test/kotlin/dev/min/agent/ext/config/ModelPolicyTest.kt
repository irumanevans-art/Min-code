package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `deniedModels`、`availableModelsMatch`、`enforceAvailableModels`（CC 模型配置文档「Block specific models or versions」「Enforce the allowlist for the default model」） */
class ModelPolicyTest {
    private val fs = InMemoryFs()
    private val managed = "/etc/claude-code/managed-settings.json"
    private fun policy() = runBlocking { SettingsLoader.load(fs, GuestPath("/workspace"), GuestPath("/root")) }.merged.modelPolicy()

    @Test
    fun `no keys at all means every model and the default are allowed`() {
        val p = policy()
        assertTrue(p.allows("claude-opus-5-5"))
        assertTrue(p.allowsDefault("claude-opus-5-5"))
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun `a denied model is blocked in every spelling even without an allowlist`() {
        fs.file(managed, """{"deniedModels":["claude-opus-5-5"]}""")
        val p = policy()
        assertFalse(p.allows("claude-opus-5-5"))
        assertFalse("带日期的同一版本也挡", p.allows("claude-opus-5-5-20260901"))
        assertFalse(p.allows("claude-opus-5-5[1m]"))
        assertTrue("别的版本不受影响", p.allows("claude-opus-5"))
        assertTrue(p.allows("claude-sonnet-5-5"))
        assertFalse("默认选项也照 deniedModels", p.allowsDefault("claude-opus-5-5"))
    }

    @Test
    fun `a denied family alias blocks the whole family, even where the allowlist permits it`() {
        fs.file(managed, """{"availableModels":["opus","sonnet"],"deniedModels":["opus"]}""")
        val p = policy()
        assertFalse(p.allows("claude-opus-4-6"))
        assertTrue(p.allows("claude-sonnet-5-5"))
    }

    @Test
    fun `an exact list permits only the versions it names`() {
        fs.file(managed, """{"availableModels":["claude-opus-5"],"availableModelsMatch":"exact"}""")
        val p = policy()
        assertTrue(p.allows("claude-opus-5"))
        assertFalse("默认的前缀匹配会放过 5.5，exact 不放", p.allows("claude-opus-5-5"))
        assertFalse("exact 列表非空时默认选项也只认名单", p.allowsDefault("claude-opus-5-5"))
    }

    @Test
    fun `without enforceAvailableModels the default option is not remapped by a prefix allowlist`() {
        fs.file(managed, """{"availableModels":["sonnet"]}""")
        val p = policy()
        assertFalse(p.allows("claude-opus-5-5"))
        assertTrue("CC：availableModels 单独设不动 Default", p.allowsDefault("claude-opus-5-5"))
    }

    @Test
    fun `enforceAvailableModels extends a non-empty allowlist to the default option`() {
        fs.file(managed, """{"availableModels":["sonnet"],"enforceAvailableModels":true}""")
        assertFalse(policy().allowsDefault("claude-opus-5-5"))
        assertTrue(policy().allowsDefault("claude-sonnet-5-5"))
    }

    @Test
    fun `an empty allowlist blocks named selections and enforce has no effect on the default`() {
        fs.file(managed, """{"availableModels":[],"enforceAvailableModels":true}""")
        val p = policy()
        assertFalse(p.allows("claude-sonnet-5-5"))
        assertTrue(p.allowsDefault("claude-opus-5-5"))
    }

    @Test
    fun `deniedModels and availableModelsMatch are read from managed settings only, with a warning elsewhere`() {
        fs.file("/root/.claude/settings.json", """{"deniedModels":["claude-opus-5-5"],"availableModelsMatch":"exact"}""")
        fs.file("/workspace/.claude/settings.json", """{"deniedModels":["sonnet"],"availableModels":["claude-opus-5"]}""")
        val p = policy()
        // 低层的 deniedModels 与 exact 都不生效：opus 5.5 照前缀匹配被项目名单放行，也不被用户层的 deniedModels 挡
        assertTrue(p.allows("claude-opus-5-5"))
        assertEquals(3, p.warnings.size)
        assertTrue(p.warnings.joinToString(), p.warnings.all { "managed" in it || "托管" in it })
    }

    @Test
    fun `a deniedModels in a lower layer blocks nothing even without an allowlist`() {
        fs.file("/workspace/.claude/settings.json", """{"deniedModels":["sonnet"]}""")
        assertTrue(policy().allows("claude-sonnet-5-5"))
        assertTrue(policy().allowsDefault("claude-sonnet-5-5"))
    }

    @Test
    fun `enforceAvailableModels comes from managed settings alone once any managed settings exist`() {
        fs.file(managed, """{"availableModels":["sonnet"]}""")
        fs.file("/root/.claude/settings.json", """{"enforceAvailableModels":true}""")
        assertTrue("有托管设置时只读托管源里的 enforce", policy().allowsDefault("claude-opus-5-5"))
    }

    @Test
    fun `without managed settings enforceAvailableModels may come from a user file`() {
        fs.file("/root/.claude/settings.json", """{"availableModels":["sonnet"],"enforceAvailableModels":true}""")
        assertFalse(policy().allowsDefault("claude-opus-5-5"))
    }

    @Test
    fun `a wrongly typed managed deniedModels is ignored with a warning`() {
        fs.file(managed, """{"deniedModels":"opus"}""")
        val p = policy()
        assertTrue("读不出的值当没写：不能凭空挡掉模型", p.allows("claude-opus-5-5"))
        assertTrue(p.warnings.joinToString(), p.warnings.any { "deniedModels" in it })
    }
}
