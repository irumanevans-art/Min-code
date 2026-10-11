package dev.min.code.core.engine.min

import dev.min.agent.tools.FeedbackBundle
import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.CodexProfile
import dev.min.code.core.settings.SearchBackendKind
import dev.min.code.core.settings.UnifiedProfile
import dev.min.code.core.settings.WebSearchConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断包的已知秘密名单：每一类凭证各有一条用例——哪一类被漏掉，对应那条就红。
 * 值都是「没有任何形状」的假串，只有逐字替换才能把它们从导出的包里去掉。
 */
class FeedbackKnownSecretsTest {
    private val provider = "relay-token-plain-7a1b2c3d"
    private val codexKey = "codex-key-plain-8e2f3a4b"
    private val unified = "unified-token-plain-9c3d4e5f"
    private val envSecret = "env-secret-plain-1d4e5f6a"
    private val brave = "brave-key-plain-2e5f6a7b"
    private val tavily = "tavily-key-plain-3f6a7b8c"
    private val otlp = "otlp-token-plain-4a7b8c9d"
    private val mcpAccess = "mcp-access-plain-5b8c9d0e"
    private val mcpRefresh = "mcp-refresh-plain-6c9d0e1f"
    private val mcpClient = "mcp-client-secret-7d0e1f2a"

    private val settings = AppSettings(
        profiles = listOf(ApiProfile(id = "a", token = provider, env = mapOf("MY_SERVICE_SECRET" to envSecret, "API_TIMEOUT_MS" to "600000"))),
        codexProfiles = listOf(CodexProfile(id = "c", apiKey = codexKey)),
        unifiedProfiles = listOf(UnifiedProfile(id = "u", token = unified)),
        webSearch = WebSearchConfig(backend = SearchBackendKind.BRAVE, braveKey = brave, tavilyKey = tavily),
    )

    private fun all() = FeedbackKnownSecrets.collect(settings, "Authorization=Bearer $otlp,x-team=acme", listOf(mcpAccess, mcpRefresh, mcpClient))

    @Test fun `provider keys of the Claude Codex and unified entries are known`() {
        val s = all()
        assertTrue(provider in s)
        assertTrue(codexKey in s)
        assertTrue(unified in s)
    }

    @Test fun `credential-looking env values of a provider are known, other env values are not`() {
        val s = all()
        assertTrue(envSecret in s)
        assertFalse("600000" in s)
    }

    @Test fun `the Brave and Tavily search keys are known`() {
        val s = all()
        assertTrue(brave in s)
        assertTrue(tavily in s)
    }

    @Test fun `the OTLP header token is known, both as the whole value and bare`() {
        val s = all()
        assertTrue("Bearer $otlp" in s)
        assertTrue(otlp in s)
        // 不是凭证的头部值也会进名单（头部整串都是加密存的），但逐字替换只会影响出现它的地方
        assertEquals(listOf("Bearer $otlp", otlp, "acme"), FeedbackKnownSecrets.headerSecrets("Authorization=Bearer $otlp,x-team=acme"))
    }

    @Test fun `the MCP OAuth tokens are known`() {
        val s = all()
        assertTrue(mcpAccess in s && mcpRefresh in s && mcpClient in s)
    }

    @Test fun `blank values never enter the list`() {
        val s = FeedbackKnownSecrets.collect(AppSettings(profiles = listOf(ApiProfile(id = "a", token = " "))), "", listOf(""))
        assertTrue(s.isEmpty())
    }

    @Test fun `with the whole list an exported bundle holds none of the stored secrets`() {
        val every = listOf(provider, codexKey, unified, envSecret, brave, tavily, otlp, mcpAccess, mcpRefresh, mcpClient)
        val body = "我把这些都贴进来了：" + every.joinToString(" ")
        val log = every.joinToString("\n") { "{\"text\":\"$it\"}" }
        val bytes = FeedbackBundle.build(dev.min.agent.spi.FeedbackDraft("t", body), log, "v", emptyMap(), emptyMap(), knownSecrets = all())
        val text = java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            buildString { while (true) { val e = z.nextEntry ?: break; append(z.readBytes().decodeToString()) } }
        }
        for (s in every) assertFalse(s + " in bundle", text.contains(s))
        // 没传名单时（手机端原来的状态）这些没有形状的值全都漏出去：证明名单是这里唯一挡住它们的一层
        val bare = FeedbackBundle.build(dev.min.agent.spi.FeedbackDraft("t", body), log, "v", emptyMap(), emptyMap())
        val leaked = java.util.zip.ZipInputStream(bare.inputStream()).use { z ->
            buildString { while (true) { val e = z.nextEntry ?: break; append(z.readBytes().decodeToString()) } }
        }
        assertTrue(every.count { leaked.contains(it) } >= 8)
    }
}
