package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接配置表的纯逻辑。加密（Android Keystore）和落盘（DataStore）都要设备，测不到；
 * 但「哪条生效」「表坏了怎么办」「token 露多少」这三件容易出事的都是纯函数，必须有网。
 */
class ApiProfileTest {

    private fun profile(id: String, baseUrl: String = "https://api.anthropic.com") =
        ApiProfile(id = id, token = "sk-$id", baseUrl = baseUrl)

    // -----------------------------------------------------------------------
    // 哪条生效
    // -----------------------------------------------------------------------

    @Test
    fun active_is_the_one_pointed_at() {
        val list = listOf(profile("a"), profile("b"))
        assertEquals("b", resolveActiveId(list, "b"))
    }

    @Test
    fun active_falls_back_to_first_when_id_is_stale() {
        // 删掉当前生效那条之后就是这个局面：存着的 id 已经不在表里
        val list = listOf(profile("a"), profile("b"))
        assertEquals("a", resolveActiveId(list, "deleted"))
        assertEquals("a", resolveActiveId(list, ""))
    }

    @Test
    fun active_is_empty_when_table_is_empty() {
        assertEquals("", resolveActiveId(emptyList(), "whatever"))
    }

    @Test
    fun settings_token_and_base_url_follow_the_active_profile() {
        val settings = AppSettings(
            profiles = listOf(
                ApiProfile(id = "a", token = "sk-a", baseUrl = "https://a.example"),
                ApiProfile(id = "b", token = "sk-b", baseUrl = "https://b.example"),
            ),
            activeProfileId = "b",
        )
        assertEquals("sk-b", settings.token)
        assertEquals("https://b.example", settings.baseUrl)
    }

    @Test
    fun settings_are_blank_but_not_crashing_when_nothing_configured() {
        val settings = AppSettings()
        assertNull(settings.activeProfile)
        assertEquals("", settings.token)
        // 没配过也要给一个能用的默认地址，不能是空串
        assertEquals(AppSettings.DEFAULT_BASE_URL, settings.baseUrl)
    }

    @Test
    fun stale_active_id_still_yields_a_usable_token() {
        // 这条是回归：active 兜底要在 token / baseUrl 上也成立，
        // 否则删掉当前项之后会话会拿着空 token 去启动
        val settings = AppSettings(profiles = listOf(profile("a")), activeProfileId = "gone")
        assertEquals("sk-a", settings.token)
    }

    // -----------------------------------------------------------------------
    // 明文地址的警示
    // -----------------------------------------------------------------------

    @Test
    fun insecure_base_url_is_flagged() {
        assertTrue(AppSettings(listOf(profile("a", "http://1.2.3.4:8080")), "a").insecureBaseUrl)
        assertFalse(AppSettings(listOf(profile("a", "https://api.anthropic.com")), "a").insecureBaseUrl)
    }

    // -----------------------------------------------------------------------
    // 序列化
    // -----------------------------------------------------------------------

    @Test
    fun json_round_trip() {
        val list = listOf(
            ApiProfile(id = "a", label = "主力", token = "sk-aaa", baseUrl = "https://a.example"),
            ApiProfile(id = "b", label = "", token = "sk-bbb", baseUrl = "https://b.example"),
        )
        assertEquals(list, decodeProfilesJson(encodeProfilesJson(list)))
    }

    @Test
    fun broken_json_decodes_to_empty_instead_of_throwing() {
        // 设置页炸在解析上 = 用户再也进不去设置，比丢一条配置严重得多
        assertEquals(emptyList<ApiProfile>(), decodeProfilesJson("{not json"))
        assertEquals(emptyList<ApiProfile>(), decodeProfilesJson(""))
        assertEquals(emptyList<ApiProfile>(), decodeProfilesJson("[{\"nope\":1}]"))
    }

    @Test
    fun unknown_fields_survive_a_downgrade() {
        // 新版本加了字段、用户又装回旧版本时，旧版本不能整表读不出来
        val json = """[{"id":"a","label":"x","token":"sk-a","baseUrl":"https://a.example","futureField":42}]"""
        assertEquals(listOf(ApiProfile("a", "x", "sk-a", "https://a.example")), decodeProfilesJson(json))
    }

    // -----------------------------------------------------------------------
    // 列表上怎么显示
    // -----------------------------------------------------------------------

    @Test
    fun display_name_prefers_label_then_host() {
        assertEquals("主力", ApiProfile("a", "主力", "sk", "https://api.x.com").displayName())
        assertEquals("api.x.com", ApiProfile("a", "", "sk", "https://api.x.com").displayName())
        assertEquals("1.2.3.4:8080", ApiProfile("a", "", "sk", "http://1.2.3.4:8080").displayName())
        // 路径不该混进标题里
        assertEquals("api.x.com", ApiProfile("a", "", "sk", "https://api.x.com/v1").displayName())
        assertEquals("未命名", ApiProfile("a", "", "sk", "").displayName())
    }

    @Test
    fun masked_token_shows_head_and_tail_only() {
        assertEquals("sk-abc…wxyz", ApiProfile("a", "", "sk-abcdefghijklmnopqrstuvwxyz", "").maskedToken())
        assertEquals("", ApiProfile("a", "", "", "").maskedToken())
    }

    @Test
    fun short_token_is_fully_masked() {
        // 短 token 全打码：露头尾就等于露了大半
        val masked = ApiProfile("a", "", "sk-123456789", "").maskedToken()
        assertEquals("••••••••••••", masked)
        assertFalse(masked.contains("sk"))
    }

    // -----------------------------------------------------------------------
    // 地址规整
    // -----------------------------------------------------------------------

    @Test
    fun base_url_is_normalized() {
        assertEquals("https://api.x.com", normalizeBaseUrl("  https://api.x.com/  "))
        assertEquals("https://api.x.com", normalizeBaseUrl("https://api.x.com"))
        // 空着就用官方地址，不能存一个空串进去
        assertEquals(AppSettings.DEFAULT_BASE_URL, normalizeBaseUrl("   "))
    }
}
