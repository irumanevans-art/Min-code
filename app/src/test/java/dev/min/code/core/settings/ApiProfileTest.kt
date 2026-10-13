package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun insecure_base_url_follows_the_active_profile() {
        assertTrue(AppSettings(listOf(profile("a", "http://1.2.3.4:8080")), "a").insecureBaseUrl)
        assertFalse(AppSettings(listOf(profile("a", "https://api.anthropic.com")), "a").insecureBaseUrl)
        // 没配过时用的是官方 https 地址，不该报警
        assertFalse(AppSettings().insecureBaseUrl)
    }

    // -----------------------------------------------------------------------
    // 明文风险的一次确认
    // -----------------------------------------------------------------------

    @Test
    fun only_an_unacknowledged_plaintext_address_asks() {
        val fresh = ApiProfile("a", "", "sk", "http://1.2.3.4:8080")
        assertTrue(fresh.needsInsecureConfirm)
        assertFalse(fresh.copy(insecureAck = true).needsInsecureConfirm)
        // https 和回环从来不问
        assertFalse(ApiProfile("a", "", "sk", "https://api.x.com").needsInsecureConfirm)
        assertFalse(ApiProfile("a", "", "sk", "http://127.0.0.1:8080").needsInsecureConfirm)
    }

    @Test
    fun existing_profiles_are_never_asked_after_an_upgrade() {
        // 升级上来的既有配置一律视为已确认：人家正在用的地址不能因为装了新版本就被拦一道
        val stored = listOf(
            ApiProfile("a", "", "sk-a", "http://1.2.3.4:8080"),
            ApiProfile("b", "", "sk-b", "https://api.x.com"),
        )
        val migrated = ackExistingProfiles(stored)
        assertTrue(migrated.none { it.needsInsecureConfirm })
        // 除了这一位，其余字段一个都不许动
        assertEquals(stored.map { it.copy(insecureAck = true) }, migrated)
    }

    @Test
    fun acknowledgement_survives_a_round_trip() {
        // 确认过就得记住，不然每次开设置页都要再点一遍
        val list = listOf(ApiProfile("a", "中转", "sk-a", "http://1.2.3.4:8080", insecureAck = true))
        assertEquals(list, decodeProfilesJson(encodeProfilesJson(list)))
        // 旧版本存的 JSON 没有这个字段，解出来是「未确认」——由迁移统一放行，见上一条
        assertFalse(
            decodeProfilesJson("""[{"id":"a","token":"sk","baseUrl":"http://1.2.3.4"}]""")
                .single().insecureAck,
        )
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
    // 老 JSON 读得回来
    //
    // 供应商切换那一批新字段（presetId / websiteUrl / env）全部加在 ApiProfile 上。
    // 这一组是整个改动的地基：解不回老表 = 用户升级一次配置全没了，比任何功能都严重。
    // -----------------------------------------------------------------------

    @Test
    fun profiles_written_before_the_provider_fields_existed_decode_with_defaults() {
        val legacy = """[{"id":"a","label":"主力","token":"sk-a","baseUrl":"https://a.example"}]"""
        val profile = decodeProfilesJson(legacy).single()

        assertEquals("sk-a", profile.token)
        assertEquals("", profile.presetId)
        assertEquals("", profile.websiteUrl)
        assertEquals(emptyMap<String, String>(), profile.env)
    }

    @Test
    fun provider_fields_survive_a_round_trip() {
        val list = listOf(
            ApiProfile(
                id = "a",
                label = "Kimi",
                token = "sk-a",
                baseUrl = "https://api.moonshot.cn/anthropic",
                presetId = "kimi",
                websiteUrl = "https://platform.moonshot.cn",
                env = mapOf("ANTHROPIC_SMALL_FAST_MODEL" to "kimi-k2-turbo-preview"),
            ),
        )
        assertEquals(list, decodeProfilesJson(encodeProfilesJson(list)))
    }

    @Test
    fun a_missing_token_is_a_state_not_a_crash() {
        // 导入一份 redacted 的备份之后就是这样：地址和 env 都在，只差一个 key。
        // 界面靠它把条目标出来，而不是让用户切过去之后撞上「未配置 token」
        assertTrue(ApiProfile("a", "Kimi", "", "https://a.example").missingToken)
        assertFalse(ApiProfile("a", "Kimi", "sk-a", "https://a.example").missingToken)
    }

    // -----------------------------------------------------------------------
    // 排序
    //
    // 拖拽重排每动一格就写一次盘，所以「没动」必须原样返回同一张表，
    // 否则一次拖动会变成十几次无意义的落盘。
    // -----------------------------------------------------------------------

    @Test
    fun moving_an_item_swaps_it_with_its_neighbour() {
        val list = listOf(profile("a"), profile("b"), profile("c"))
        assertEquals(listOf("b", "a", "c"), moveInList(list, -1) { it.id == "b" }.map { it.id })
        assertEquals(listOf("a", "c", "b"), moveInList(list, +1) { it.id == "b" }.map { it.id })
    }

    @Test
    fun moving_past_either_end_changes_nothing() {
        val list = listOf(profile("a"), profile("b"))
        assertSame(list, moveInList(list, -1) { it.id == "a" })
        assertSame(list, moveInList(list, +1) { it.id == "b" })
        // 单条、挪 0 格、找不到 —— 三种都不该产生一张新表
        assertSame(list, moveInList(list, 0) { it.id == "a" })
        assertSame(list, moveInList(list, +1) { it.id == "gone" })
        val single = listOf(profile("a"))
        assertSame(single, moveInList(single, +1) { true })
    }

    // -----------------------------------------------------------------------
    // 「密文打不开」和「没配过」是两回事
    //
    // 分不开的代价很实在：读成空表 → 界面显示「还没配过」→ 用户随手加一条 →
    // 整表覆盖把原密文抹掉。密钥（改锁屏、换机恢复备份）也许还回得来，抹掉就回不来了。
    // -----------------------------------------------------------------------

    /** 解不开 = null，这才是要挡住写入的那种情况 */
    @Test
    fun `ciphertext that will not decrypt is unreadable`() {
        assertTrue(isUnreadableCipher("v1:aXY=:Ym9keQ==") { null })
    }

    @Test
    fun `ciphertext that decrypts is readable`() {
        assertFalse(isUnreadableCipher("v1:aXY=:Ym9keQ==") { "[]" })
    }

    /** 没存过就是没存过，不能当成打不开去挡住写入 —— 那样新用户一条都加不进来 */
    @Test
    fun `absent or blank value is not unreadable`() {
        assertFalse(isUnreadableCipher(null) { null })
        assertFalse(isUnreadableCipher("") { null })
        assertFalse(isUnreadableCipher("   ") { null })
    }

    /**
     * 1.1.9 之前存的是明文 JSON，没有 v1: 前缀。它压根不用解密，
     * 不该因为 decrypt 给了 null 就被当成打不开。
     */
    @Test
    fun `legacy plaintext is not unreadable`() {
        assertFalse(isUnreadableCipher("[{\"id\":\"a\"}]") { null })
    }

    // -----------------------------------------------------------------------
    // 解开了但读不懂：同样不能让写入把它整表覆盖
    // -----------------------------------------------------------------------

    /** 读侧把它当空表，可写侧照空表覆盖 —— 原来那串就没了。这里要和「解不开」一样挡住 */
    @Test
    fun `ciphertext that decrypts but does not parse is unreadable`() {
        assertTrue(isUnreadableCipher("v1:aXY=:Ym9keQ==", parses = { false }) { "{broken" })
    }

    @Test
    fun `ciphertext that decrypts and parses is readable`() {
        assertFalse(isUnreadableCipher("v1:aXY=:Ym9keQ==", parses = { true }) { "[]" })
    }

    /** 解出来是空串不是坏数据，别拿它去喂解析器 */
    @Test
    fun `blank plaintext is not unreadable even if the parser would reject it`() {
        assertFalse(isUnreadableCipher("v1:aXY=:Ym9keQ==", parses = { false }) { "" })
    }

    /** 明文（1.1.9 之前、没有 v1: 前缀）读不懂也一样：decrypt 对它原样返回，不是 null */
    @Test
    fun `legacy plaintext that does not parse is unreadable`() {
        assertTrue(isUnreadableCipher("{broken", parses = { false }) { it })
    }

    /** 三张表各自的解析器：用真实形状钉死，别在改 schema 时静默放行或误挡 */
    @Test
    fun `every cipher table accepts a well formed list and rejects garbage`() {
        for (table in CIPHER_TABLES.values) {
            assertTrue(table.parses("[]"))
            assertTrue(table.parses("""[{"id":"a"}]"""))
            assertFalse(table.parses("{broken"))
            assertFalse(table.parses("""{"id":"a"}"""))
        }
    }

    /** 新版本写进来一个旧版不认识的枚举值，降级后读到的就是这种数据 */
    @Test
    fun `a downgrade that meets an unknown enum value is unreadable not empty`() {
        val codex = CIPHER_TABLES.values.count { !it.parses("""[{"id":"a","authMode":"FROM_THE_FUTURE"}]""") }
        assertTrue("至少 Codex 表（authMode）该把未知枚举判成读不懂", codex >= 1)
    }

    @Test
    fun `claude table accepts a profile written by this version`() {
        val json = encodeProfilesJson(listOf(profile("a"), profile("b")))
        assertTrue(CIPHER_TABLES.values.any { it.parses(json) })
        assertEquals(2, decodeProfilesJson(json).size)
    }
}
