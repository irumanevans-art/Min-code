package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑面板的「JSON 形态」。
 *
 * 它是一个手写文本框，所以两件事必须成立：往返不丢东西（否则在两个 tab 之间切一下
 * 配置就变了），以及出错时说得出是哪一行——只回一句「JSON 无效」等于让用户自己去数括号。
 */
class ProviderJsonTest {

    private val profile = ApiProfile(
        id = "a",
        label = "Kimi",
        token = "sk-secret-token",
        baseUrl = "https://api.moonshot.cn/anthropic",
        env = mapOf("ANTHROPIC_SMALL_FAST_MODEL" to "kimi-k2-turbo-preview"),
    )

    // -----------------------------------------------------------------------
    // 往返
    // -----------------------------------------------------------------------

    @Test
    fun a_revealed_round_trip_changes_nothing() {
        val parsed = parseProfileJson(profileToJsonText(profile, revealToken = true))
        val ok = parsed as ProfileJsonParse.Ok

        assertEquals(profile.baseUrl, ok.baseUrl)
        assertEquals(profile.token, ok.token)
        assertEquals(profile.env, ok.env)
        assertEquals(profile, profile.withJsonParse(ok))
    }

    @Test
    fun the_shape_is_stable_even_when_there_are_no_extra_variables() {
        // 形状稳定，两次编辑之间的差异才是用户自己改的那些
        val text = profileToJsonText(profile.copy(env = emptyMap()), revealToken = true)
        val ok = parseProfileJson(text) as ProfileJsonParse.Ok

        assertTrue(text.contains("ANTHROPIC_BASE_URL"))
        assertTrue(text.contains("ANTHROPIC_AUTH_TOKEN"))
        assertEquals(emptyMap<String, String>(), ok.env)
    }

    // -----------------------------------------------------------------------
    // 掩码
    //
    // 「看一眼这条配置长什么样」不该先要求把明文 key 摊在屏幕上，
    // 而看完存回去也不能把 key 抹成那串点。
    // -----------------------------------------------------------------------

    @Test
    fun a_masked_render_does_not_contain_the_token() {
        val text = profileToJsonText(profile, revealToken = false)
        assertFalse(text.contains("sk-secret-token"))
        assertTrue(text.contains(TOKEN_PLACEHOLDER))
    }

    @Test
    fun saving_back_a_masked_text_keeps_the_original_token() {
        val text = profileToJsonText(profile, revealToken = false)
        val ok = parseProfileJson(text) as ProfileJsonParse.Ok

        // null = 「这一栏没动过」。落回配置上时原样保留
        assertNull(ok.token)
        assertEquals("sk-secret-token", profile.withJsonParse(ok).token)
    }

    @Test
    fun replacing_the_placeholder_really_replaces_the_token() {
        val text = profileToJsonText(profile, revealToken = false)
            .replace(TOKEN_PLACEHOLDER, "sk-new")
        val ok = parseProfileJson(text) as ProfileJsonParse.Ok

        assertEquals("sk-new", ok.token)
        assertEquals("sk-new", profile.withJsonParse(ok).token)
    }

    // -----------------------------------------------------------------------
    // 拒绝
    // -----------------------------------------------------------------------

    @Test
    fun malformed_input_is_rejected_with_a_reason() {
        assertEquals(
            ProfileJsonParse.Reason.NOT_JSON,
            (parseProfileJson("{not json") as ProfileJsonParse.Err).reason,
        )
        assertEquals(
            ProfileJsonParse.Reason.NOT_AN_OBJECT,
            (parseProfileJson("""["a"]""") as ProfileJsonParse.Err).reason,
        )
        assertEquals(
            ProfileJsonParse.Reason.ENV_NOT_AN_OBJECT,
            (parseProfileJson("""{"env":"nope"}""") as ProfileJsonParse.Err).reason,
        )
    }

    @Test
    fun a_non_string_value_names_the_key_that_broke() {
        // 悄悄把 30000 转成 "30000" 会让用户以为自己写对了；
        // 而只说「有个值类型不对」又等于让他自己去找
        val err = parseProfileJson("""{"env":{"API_TIMEOUT_MS":30000}}""") as ProfileJsonParse.Err
        assertEquals(ProfileJsonParse.Reason.ENV_VALUE_NOT_A_STRING, err.reason)
        assertEquals("API_TIMEOUT_MS", err.detail)

        val nested = parseProfileJson("""{"env":{"H":{"a":"b"}}}""") as ProfileJsonParse.Err
        assertEquals("H", nested.detail)
    }

    @Test
    fun top_level_extras_are_reported_rather_than_swallowed() {
        // 从桌面版 cc-switch 导出的文件里会带这些。存不下就得说出来，
        // 不然用户会以为它们被保存了
        val ok = parseProfileJson(
            """{"env":{"ANTHROPIC_BASE_URL":"https://a.example"},"permissions":{},"model":"x"}""",
        ) as ProfileJsonParse.Ok

        assertEquals(listOf("permissions", "model"), ok.ignoredKeys)
        assertEquals("https://a.example", ok.baseUrl)
    }

    @Test
    fun an_empty_object_is_a_blank_config_not_an_error() {
        // 从头写的时候会路过这个状态，不该在每一次击键上报错
        val ok = parseProfileJson("{}") as ProfileJsonParse.Ok
        assertEquals(AppSettings.DEFAULT_BASE_URL, ok.baseUrl)
        assertEquals("", ok.token)
    }

    @Test
    fun reserved_keys_typed_into_the_json_do_not_sneak_into_the_env_table() {
        // 和 ProviderEnvTest 里那条是同一条规矩，这里钉住另一个入口
        val ok = parseProfileJson(
            """{"env":{"ANTHROPIC_BASE_URL":"https://a.example","PATH":"/tmp","X":"1"}}""",
        ) as ProfileJsonParse.Ok

        assertEquals(mapOf("X" to "1"), ok.env)
    }

    /** 键名跟着认证方式走：JSON 形态和表单是同一份事实的两个视图，能力不该少一块 */
    @Test
    fun auth_header_is_encoded_as_the_env_key_name() {
        val apiKey = profile.copy(authHeader = AuthHeader.API_KEY)
        val text = profileToJsonText(apiKey, revealToken = true)
        assertTrue(text.contains("ANTHROPIC_API_KEY"))
        assertFalse(text.contains("ANTHROPIC_AUTH_TOKEN"))

        val ok = parseProfileJson(text) as ProfileJsonParse.Ok
        assertEquals(AuthHeader.API_KEY, ok.authHeader)
        assertEquals(AuthHeader.API_KEY, apiKey.withJsonParse(ok).authHeader)
    }

    @Test
    fun reading_the_api_key_key_switches_the_header() {
        val ok = parseProfileJson(
            """{"env":{"ANTHROPIC_BASE_URL":"https://a.example","ANTHROPIC_API_KEY":"sk-x"}}""",
        ) as ProfileJsonParse.Ok
        assertEquals(AuthHeader.API_KEY, ok.authHeader)
        assertEquals("sk-x", ok.token)
    }

    @Test
    fun both_keys_prefer_bearer_and_drop_the_other() {
        // 同时握着两把钥匙比用错一把更难查，所以 Bearer 那个赢，另一个丢掉
        val ok = parseProfileJson(
            """{"env":{"ANTHROPIC_AUTH_TOKEN":"sk-b","ANTHROPIC_API_KEY":"sk-a"}}""",
        ) as ProfileJsonParse.Ok
        assertEquals(AuthHeader.AUTH_TOKEN, ok.authHeader)
        assertEquals("sk-b", ok.token)
        assertFalse(ok.env.containsKey("ANTHROPIC_API_KEY"))
    }
}
