package dev.min.code.core.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
}
