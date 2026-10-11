package dev.min.agent.cli

import dev.min.agent.ext.config.AvailableModelsSetting
import dev.min.agent.ext.config.ModelPolicy
import dev.min.agent.llm.catalog.Catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 启动模型的优先级与模型限制下的替换（CC 模型配置文档「Setting your model」「Restrict model selection」） */
class StartupModelTest {
    private val catalog = Catalog.bundled()
    private fun choose(flag: String? = null, env: String? = null, setting: String? = null, policy: ModelPolicy = ModelPolicy()) =
        chooseStartupModel(flag, env, setting, "anthropic", catalog, policy)
    private fun managed(vararg entries: String, enforce: Boolean = false, denied: List<String> = emptyList()) =
        ModelPolicy(AvailableModelsSetting(entries.toList(), managed = true), denied = denied, enforceDefault = enforce)

    @Test
    fun `--model beats ANTHROPIC_MODEL beats the model setting beats the default`() {
        assertEquals("sonnet", choose(flag = "sonnet", env = "haiku", setting = "opus").model)
        assertEquals("haiku", choose(env = "haiku", setting = "opus").model)
        assertEquals("opus", choose(setting = "opus").model)
        assertEquals("没点名就落到这家的默认选项（opus 档）", "claude-opus-5-5", choose().model)
        assertTrue(choose(flag = "sonnet").warnings.isEmpty())
    }

    @Test
    fun `a blocked named model is replaced by the default with a warning naming both`() {
        val r = choose(flag = "claude-opus-5-5", policy = managed("sonnet", enforce = true))
        assertEquals("claude-sonnet-5-5", r.model)
        assertTrue(r.warnings.joinToString(), r.warnings.any { "claude-opus-5-5" in it && "claude-sonnet-5-5" in it })
    }

    @Test
    fun `a blocked family alias lands on the newest permitted version of its family`() {
        val r = choose(setting = "opus", policy = managed("sonnet", "claude-opus-4-6"))
        assertEquals("claude-opus-4-6", r.model)
        assertTrue(r.warnings.joinToString(), r.warnings.single().contains("claude-opus-4-6"))
    }

    @Test
    fun `opusplan stays an alias so the session can switch tiers`() {
        assertEquals("opusplan", choose(flag = "opusplan").model)
    }

    @Test
    fun `a default the policy blocks with nothing permitted refuses to start and names the keys`() {
        val e = assertThrows(UsageException::class.java) { choose(policy = ModelPolicy(denied = listOf("opus", "sonnet", "haiku"))) }
        assertTrue(e.message!!, e.message!!.contains("deniedModels") && e.message!!.contains("availableModels"))
    }

    @Test
    fun `a blocked default steps down and says so`() {
        val r = choose(policy = ModelPolicy(denied = listOf("claude-opus-5-5")))
        assertEquals("claude-sonnet-5-5", r.model)
        assertTrue(r.warnings.joinToString(), r.warnings.single().contains("claude-opus-5-5"))
    }

    @Test
    fun `--model is optional now`() {
        assertEquals(null, parseArgs(arrayOf("-p", "hi"), java.nio.file.Path.of(".")).model)
    }
}
