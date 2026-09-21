package dev.min.code.core.codex

import dev.min.code.core.settings.CodexAuthMode
import dev.min.code.core.settings.CodexProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * config.toml 的渲染与回读。
 *
 * 这段以前内联在 `CodexRuntime.prepare()` 里——一个要 proot、要文件系统的 suspend
 * 函数中间的字符串拼接。转义写错、wire_api 写了个 CLI 不认识的值，都只能靠装包跑一遍
 * 才发现，而表现是「任务毫无动静地失败」。抽出来就为了这一组。
 */
class CodexConfigTomlTest {

    private fun relay(baseUrl: String = "https://codex.example/v1", wireApi: String = "responses") =
        CodexProfile(
            id = "a",
            authMode = CodexAuthMode.RELAY,
            baseUrl = baseUrl,
            apiKey = "sk-codex",
            wireApi = wireApi,
        )

    // -----------------------------------------------------------------------
    // 渲染
    // -----------------------------------------------------------------------

    @Test
    fun a_relay_becomes_a_custom_provider_pointing_at_its_own_address() {
        val toml = renderCodexConfigToml(relay())!!

        assertTrue(toml.contains("""model_provider = "min_openai""""))
        assertTrue(toml.contains("""[model_providers.min_openai]"""))
        assertTrue(toml.contains("""base_url = "https://codex.example/v1""""))
        assertTrue(toml.contains("""wire_api = "responses""""))
        // key 只活在进程环境里，文件里只留一个指针
        assertTrue(toml.contains("""env_key = "OPENAI_API_KEY""""))
        assertFalse(toml.contains("sk-codex"))
    }

    @Test
    fun the_official_api_key_mode_always_points_at_the_official_address() {
        // 用户在中转模式下填过的地址，不该在切回官方之后还生效
        val toml = renderCodexConfigToml(
            relay(baseUrl = "https://leftover.example").copy(authMode = CodexAuthMode.OPENAI_API_KEY),
        )!!
        assertTrue(toml.contains("""base_url = "https://api.openai.com/v1""""))
        assertFalse(toml.contains("leftover"))
    }

    @Test
    fun the_official_login_gets_no_file_at_all() {
        // 留着上一次选的中转，「官方登录」就会偷偷走别人的地址
        assertNull(renderCodexConfigToml(CodexProfile(id = "a", authMode = CodexAuthMode.CLI)))
        assertNull(renderCodexConfigToml(null))
    }

    @Test
    fun a_trailing_slash_never_survives_into_the_file() {
        val toml = renderCodexConfigToml(relay(baseUrl = "https://codex.example/v1/"))!!
        assertTrue(toml.contains("""base_url = "https://codex.example/v1""""))
    }

    @Test
    fun quotes_and_backslashes_in_an_address_are_escaped() {
        // 反斜杠必须排在引号前面：反过来的话，引号转义自己引进来的那个反斜杠
        // 会被第二轮再转一次，写出来的是坏 TOML，codex 直接起不来
        val toml = renderCodexConfigToml(relay(baseUrl = """https://x.example/a"b\c"""))!!
        assertTrue(toml.contains("""base_url = "https://x.example/a\"b\\c""""))
    }

    @Test
    fun an_unknown_wire_api_falls_back_instead_of_reaching_the_file() {
        assertTrue(renderCodexConfigToml(relay(wireApi = "completions"))!!.contains("""wire_api = "responses""""))
        assertTrue(renderCodexConfigToml(relay(wireApi = "chat"))!!.contains("""wire_api = "chat""""))
    }

    // -----------------------------------------------------------------------
    // 回读（导入现有配置）
    //
    // 这里不是一个 TOML 解析器。认不出就返回 null，不猜 ——
    // 把用户的地址导成一个错的，比「这次导不进来」严重得多。
    // -----------------------------------------------------------------------

    @Test
    fun what_we_wrote_is_what_we_can_read_back() {
        val original = relay(baseUrl = "https://codex.example/v1", wireApi = "chat")
        val parsed = parseCodexConfigImport(renderCodexConfigToml(original)!!, apiKey = "sk-codex")!!

        assertEquals(CodexAuthMode.RELAY, parsed.authMode)
        assertEquals("https://codex.example/v1", parsed.baseUrl)
        assertEquals("chat", parsed.wireApi)
        assertEquals("sk-codex", parsed.apiKey)
        // 导进来的地址是用户本来就在用的，不为它补一道确认框
        assertTrue(parsed.insecureAck)
    }

    @Test
    fun the_official_address_comes_back_as_the_official_mode() {
        val toml = renderCodexConfigToml(relay().copy(authMode = CodexAuthMode.OPENAI_API_KEY))!!
        assertEquals(CodexAuthMode.OPENAI_API_KEY, parseCodexConfigImport(toml)!!.authMode)
    }

    @Test
    fun a_shape_we_do_not_recognise_yields_nothing() {
        assertNull(parseCodexConfigImport(""))
        assertNull(parseCodexConfigImport("model = \"gpt-5\"\n"))
        // 指到一个根本不存在的 provider 块
        assertNull(parseCodexConfigImport("model_provider = \"other\"\n"))
        // 有块但没有地址
        assertNull(parseCodexConfigImport("model_provider = \"x\"\n[model_providers.x]\nname = \"X\"\n"))
    }

    @Test
    fun a_second_provider_block_does_not_leak_into_the_first() {
        val toml = """
            model_provider = "mine"

            [model_providers.mine]
            name = "Mine"
            base_url = "https://mine.example/v1"
            wire_api = "chat"

            [model_providers.other]
            base_url = "https://other.example/v1"
        """.trimIndent()

        val parsed = parseCodexConfigImport(toml)!!
        assertEquals("https://mine.example/v1", parsed.baseUrl)
        assertEquals("Mine", parsed.label)
    }
}
