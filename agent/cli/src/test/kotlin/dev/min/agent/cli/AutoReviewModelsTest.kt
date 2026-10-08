package dev.min.agent.cli

import dev.min.agent.ext.config.parseAutoModeReviewer
import dev.min.agent.kernel.defaultAutoReviewer
import dev.min.agent.llm.*
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class AutoReviewModelsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val catalog = Catalog.bundled()
    private fun session(provider: String = "anthropic", model: String = "claude-sonnet-5") =
        resolveModel(parseArgs(arrayOf("--provider", provider, "--model", model), Path.of(".")), catalog)

    @Test fun `auto CLI and independent reviewer budget timeout parse`() {
        val args = parseArgs(arrayOf("--model", "large", "--mode", "auto", "--auto-mode-reviewer", "deepseek/deepseek-flash",
            "--max-budget-usd", "1.5", "--model-timeout-ms", "250"), Path.of("."))
        assertEquals(dev.min.agent.protocol.PermissionMode.Auto, args.mode)
        assertEquals("deepseek/deepseek-flash", args.autoModeReviewer)
        assertEquals(1.5, args.maxBudgetUsd!!, 0.0)
        assertEquals(250L, args.modelTimeoutMs)
        for (value in listOf("0", "-1", "NaN", "Infinity", "bad")) {
            assertThrows(UsageException::class.java) { parseArgs(arrayOf("--model", "m", "--max-budget-usd", value), Path.of(".")) }
        }
    }

    @Test fun `anthropic default uses requested haiku id without borrowing another price`() {
        val main = session()
        val selected = resolveReviewerSelection(AutoModeReviewer(defaultAutoReviewer(main.profile.ref) { null }), catalog, main)
        assertEquals(ModelRef("anthropic", "claude-haiku-5-5"), selected.profile.ref)
        assertNull(selected.profile.pricing)
        assertEquals(main.provider, selected.provider)
    }

    @Test fun `provider fast model is backed by directory and no fast falls back`() {
        for (provider in catalog.providers) {
            provider.fastModel?.let { assertNotNull(catalog.model(ModelRef(provider.id, it))) }
        }
        val main = session("deepseek", "deepseek-pro")
        val ref = defaultAutoReviewer(main.profile.ref) { id -> catalog.provider(id)?.fastModel?.let { ModelRef(id, it) } }
        assertEquals(ModelRef("deepseek", "deepseek-flash"), ref)
        assertEquals(ref, resolveReviewerSelection(AutoModeReviewer(ref), catalog, main).profile.ref)
        assertEquals(ModelRef("zhipu", "glm-5.1"), defaultAutoReviewer(ModelRef("zhipu", "glm-5.1")) { null })
    }

    @Test fun `cross provider review keeps its profile endpoint and key on session switch`() {
        val setting = AutoModeReviewer(ModelRef("deepseek-openai", "deepseek-flash"))
        val first = resolveReviewerSelection(setting, catalog, session())
        val second = resolveReviewerSelection(setting, catalog, session("minimax", "MiniMax-M3"))
        assertEquals(first, second)
        assertEquals(Wire.OpenAIChat, first.wire)
        assertEquals("DEEPSEEK-OPENAI_API_KEY", first.keyEnv)
        assertEquals(catalog.provider("deepseek-openai")!!.baseUrl, first.provider.baseUrl)
    }

    @Test fun `uncatalogued review requires user declared capabilities`() {
        val main = session()
        assertThrows(UsageException::class.java) { resolveReviewerSelection(AutoModeReviewer(ModelRef("anthropic", "custom")), catalog, main) }
        val declared = parseAutoModeReviewer(Json.parseToJsonElement("""{"provider":"anthropic","model":"custom","contextWindow":9000,"maxOutputTokens":500}"""))
        val selected = resolveReviewerSelection(declared, catalog, main)
        assertEquals(9000, selected.profile.contextWindow)
        assertEquals(500, selected.profile.maxOutputTokens)
        assertEquals(ModelRef("anthropic", "custom"), selected.profile.ref)
    }

    @Test fun `user setting write preserves existing keys and reset removes override`() {
        val file = tmp.root.toPath().resolve(".claude/settings.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"model":"large","autoMode":{"hard_deny":["Bash(rm *)"]}}""")
        saveAutoReviewer(file, JsonPrimitive("deepseek/deepseek-flash"))
        val raw = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("large", raw.getValue("model").jsonPrimitive.content)
        assertTrue("autoMode" in raw)
        assertEquals("deepseek/deepseek-flash", raw.getValue("autoModeReviewer").jsonPrimitive.content)
        saveAutoReviewer(file, null)
        assertFalse("autoModeReviewer" in Json.parseToJsonElement(Files.readString(file)).jsonObject)
    }
}
