package dev.min.code.core.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CodexProfile 的序列化。profile 存在 Keystore 加密的 JSON 里，这里钉两条：
 * 老格式（没有 model/effort）要能读回来，新字段不丢。
 */
class CodexProfileTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `profiles written before model and effort existed decode with blanks`() {
        val legacy = """{"id":"default","baseUrl":"https://api.openai.com/v1",
           "apiKey":"sk-test","authMode":"OPENAI_API_KEY"}""".replace("\n", "")

        val profile = json.decodeFromString(CodexProfile.serializer(), legacy)

        assertEquals("default", profile.id)
        assertEquals("", profile.model)
        assertEquals("", profile.effort)
    }

    @Test
    fun `model and effort round trip`() {
        val profile = CodexProfile(
            id = "default",
            authMode = CodexAuthMode.RELAY,
            model = "gpt-5.1-codex-max",
            effort = "xhigh",
        )
        val encoded = json.encodeToString(CodexProfile.serializer(), profile)
        val decoded = json.decodeFromString(CodexProfile.serializer(), encoded)

        assertEquals(profile, decoded)
    }

    // -----------------------------------------------------------------------
    // 多配置：以前存储层是一张表、界面只编辑 id="default" 那一条，
    // 于是「删掉当前生效的那条之后该轮到谁」这类规则从来没被走到过。
    // 现在两侧共用同一条规则，这一组就是钉它。
    // -----------------------------------------------------------------------

    private fun codex(id: String) = CodexProfile(id = id, label = id)

    @Test
    fun `active falls back exactly like the claude side`() {
        val list = listOf(codex("a"), codex("b"))
        assertEquals("b", resolveActiveIdBy(list, "b") { it.id })
        // 删掉当前项之后：存着的 id 已经不在表里，退回第一条
        assertEquals("a", resolveActiveIdBy(list, "deleted") { it.id })
        assertEquals("a", resolveActiveIdBy(list, "") { it.id })
        assertEquals("", resolveActiveIdBy(emptyList<CodexProfile>(), "whatever") { it.id })
    }

    @Test
    fun `settings hand back the profile the active id points at`() {
        val settings = AppSettings(
            codexProfiles = listOf(codex("a"), codex("b")),
            activeCodexProfileId = "b",
        )
        assertEquals("b", settings.activeCodexProfile?.id)
        // 指不到时不能给 null —— 那会让 Codex 页突然变成「还没配过」
        assertEquals("a", settings.copy(activeCodexProfileId = "gone").activeCodexProfile?.id)
    }

    // -----------------------------------------------------------------------
    // wire_api
    //
    // 以前写死 responses。只提供 /chat/completions 的中转在那种写法下永远连不上，
    // 而界面上完全看不出是为什么 —— 所以合法值要收在一处，非法值要有明确的回落。
    // -----------------------------------------------------------------------

    @Test
    fun `wire api falls back instead of writing something the cli cannot read`() {
        assertEquals("responses", CodexProfile(id = "a").effectiveWireApi)
        assertEquals("chat", CodexProfile(id = "a", wireApi = "chat").effectiveWireApi)
        assertEquals("responses", CodexProfile(id = "a", wireApi = "completions").effectiveWireApi)
        assertEquals("responses", CodexProfile(id = "a", wireApi = "").effectiveWireApi)
    }

    @Test
    fun `profiles written before wire api existed default to responses`() {
        val legacy = """{"id":"default","baseUrl":"https://relay.example/v1","authMode":"RELAY"}"""
        val profile = json.decodeFromString(CodexProfile.serializer(), legacy)

        assertEquals("responses", profile.wireApi)
        assertEquals(emptyMap<String, String>(), profile.env)
        assertFalse(profile.insecureAck)
    }

    // -----------------------------------------------------------------------
    // 明文中转：和 Claude 侧对称。只有中转模式才谈得上这件事 ——
    // 另两种模式的地址不是用户填的，为它们报警只是噪音。
    // -----------------------------------------------------------------------

    @Test
    fun `only a relay address can be insecure`() {
        val relay = CodexProfile(id = "a", authMode = CodexAuthMode.RELAY, baseUrl = "http://1.2.3.4:8080")
        assertTrue(relay.needsInsecureConfirm)
        assertFalse(relay.copy(insecureAck = true).needsInsecureConfirm)
        // 同一个地址，换成 API key 直连官方就不该问
        assertFalse(relay.copy(authMode = CodexAuthMode.OPENAI_API_KEY).needsInsecureConfirm)
        assertFalse(relay.copy(baseUrl = "http://127.0.0.1:8080").needsInsecureConfirm)
    }
}
