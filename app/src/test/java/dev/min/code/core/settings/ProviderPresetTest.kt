package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 预设表。
 *
 * 第一组**直接读随包发出去的那份真文件**——这是它唯一能被测到的机会。预设表是纯数据，
 * 编译器一个字都不检查：某次手改漏了个逗号，表现是「从预设添加」里空空如也，而单元测试
 * 和构建全绿。
 *
 * 地址对不对测不了（那要联网），但「id 唯一」「地址长得像个地址」「枚举值在表里」
 * 这三类错全都测得到，而且它们正是手改一张 JSON 时最容易犯的。
 */
class ProviderPresetTest {

    private val bundled: ProviderPresets by lazy {
        parseProviderPresets(bundledPresetFile().readText())
    }

    /** 单测的工作目录随 Gradle 版本变过，所以往上找而不是钉死一条相对路径 */
    private fun bundledPresetFile(): File {
        val candidates = listOf(
            "src/main/assets/provider_presets.json",
            "app/src/main/assets/provider_presets.json",
            "../app/src/main/assets/provider_presets.json",
        )
        return candidates.map(::File).firstOrNull { it.isFile }
            ?: error("找不到 provider_presets.json，工作目录是 ${File(".").absolutePath}")
    }

    // -----------------------------------------------------------------------
    // 随包那份表本身
    // -----------------------------------------------------------------------

    @Test
    fun the_bundled_table_parses_and_is_not_empty() {
        assertTrue("表解析不出来或版本过低", bundled.version >= MIN_SUPPORTED_PRESET_VERSION)
        assertFalse("Claude 侧一条预设都没有", bundled.claude.isEmpty())
        assertFalse("Codex 侧一条预设都没有", bundled.codex.isEmpty())
    }

    @Test
    fun every_preset_id_is_unique_and_usable_as_a_reference() {
        // id 是 ApiProfile.presetId 的取值来源。撞号的话，「这条是从哪来的」就指错了
        val ids = bundled.claude.map { it.id } + bundled.codex.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    fun every_claude_preset_has_a_name_and_an_address_that_survives_normalisation() {
        bundled.claude.forEach { preset ->
            assertTrue("${preset.id} 没有名字", preset.displayName(zh = false).isNotBlank())
            assertTrue("${preset.id} 没有中文名", preset.displayName(zh = true).isNotBlank())
            val normalized = normalizeBaseUrl(preset.baseUrl)
            assertTrue(
                "${preset.id} 的地址不是 http(s)：$normalized",
                normalized.startsWith("http://") || normalized.startsWith("https://"),
            )
            assertTrue("${preset.id} 的分类不认识：${preset.category}", preset.category in PRESET_CATEGORIES)
        }
    }

    @Test
    fun every_codex_preset_declares_a_mode_and_protocol_the_cli_understands() {
        bundled.codex.forEach { preset ->
            // authMode 写错会静默退回 CLI —— 一个本该带 key 的预设变成「官方登录」
            assertEquals(
                "${preset.id} 的 authMode 不认识：${preset.authMode}",
                preset.authMode,
                preset.mode.name,
            )
            assertTrue(
                "${preset.id} 的 wire_api 不认识：${preset.wireApi}",
                preset.wireApi in CODEX_WIRE_APIS,
            )
            // 带 key 的两种模式必须有地址，否则应用之后是一条连不上的配置
            if (preset.mode != CodexAuthMode.CLI) {
                assertTrue("${preset.id} 缺地址", preset.baseUrl.isNotBlank())
            }
        }
    }

    @Test
    fun no_preset_ships_a_credential() {
        // 一条都不许有。预设是公开数据，带 key 出去就是把谁的额度送人
        val blob = bundledPresetFile().readText()
        assertFalse(blob.contains("sk-ant-api"))
        assertTrue(bundled.claude.none { it.env.keys.any { k -> k in RESERVED_ENV_KEYS } })
    }

    // -----------------------------------------------------------------------
    // 解析的降级
    //
    // 表坏了最多是「没有预设可选」，不该炸掉整个页面 —— 手填一条配置的路还在。
    // -----------------------------------------------------------------------

    @Test
    fun a_broken_table_degrades_to_empty_instead_of_throwing() {
        assertEquals(ProviderPresets.EMPTY, parseProviderPresets("{not json"))
        assertEquals(ProviderPresets.EMPTY, parseProviderPresets(""))
        assertEquals(ProviderPresets.EMPTY, parseProviderPresets("[]"))
    }

    @Test
    fun a_table_from_the_future_is_refused_whole_rather_than_half_read() {
        // 解出一堆半截数据的下场是界面上一排没有地址的供应商，比「没有预设」难懂得多
        val tooOld = """{"version":0,"claude":[{"id":"a","baseUrl":"https://a.example"}]}"""
        assertEquals(ProviderPresets.EMPTY, parseProviderPresets(tooOld))
    }

    @Test
    fun unknown_fields_and_id_less_entries_do_not_break_the_rest() {
        val json = """
            {"version":1,"futureKey":42,
             "claude":[{"id":"a","name":"A","baseUrl":"https://a.example","futureField":"x"},
                       {"name":"没有 id 的那条","baseUrl":"https://b.example"}]}
        """.trimIndent()
        val parsed = parseProviderPresets(json)

        // 没有 id 的条目没法被 presetId 引用，滤掉；其余照常
        assertEquals(1, parsed.claude.size)
        assertEquals("a", parsed.claude.single().id)
    }

    // -----------------------------------------------------------------------
    // 预设 → 条目
    // -----------------------------------------------------------------------

    @Test
    fun applying_a_preset_copies_its_content_and_remembers_where_it_came_from() {
        val preset = ClaudePreset(
            id = "kimi",
            name = "Moonshot",
            nameZh = "月之暗面",
            baseUrl = "https://api.moonshot.cn/anthropic/",
            websiteUrl = "https://platform.moonshot.cn",
            env = mapOf("API_TIMEOUT_MS" to "600000"),
        )
        val profile = preset.toProfile(token = "  sk-a  ", zh = true)

        assertEquals("月之暗面", profile.label)
        assertEquals("sk-a", profile.token)
        // 地址在这一步就规整掉，免得两条同一家的配置因为一个尾斜杠被当成不同的
        assertEquals("https://api.moonshot.cn/anthropic", profile.baseUrl)
        assertEquals("kimi", profile.presetId)
        assertEquals("https://platform.moonshot.cn", profile.websiteUrl)
        assertEquals(mapOf("API_TIMEOUT_MS" to "600000"), profile.env)
        // 英文界面拿英文名
        assertEquals("Moonshot", preset.toProfile(token = "", zh = false).label)
    }

    @Test
    fun a_preset_without_a_chinese_name_falls_back_instead_of_showing_nothing() {
        val preset = ClaudePreset(id = "x", name = "Relay X", baseUrl = "https://x.example")
        assertEquals("Relay X", preset.displayName(zh = true))
    }

    @Test
    fun a_preset_cannot_smuggle_in_a_reserved_variable() {
        // 预设表是数据文件，将来可能不是我们自己写的那份（filesDir 覆盖）。
        // 地址与 token 的位置不能被一张表夺走
        val preset = ClaudePreset(
            id = "x",
            baseUrl = "https://x.example",
            env = mapOf("ANTHROPIC_AUTH_TOKEN" to "sk-planted", "OK" to "1"),
        )
        assertEquals(mapOf("OK" to "1"), preset.toProfile(token = "sk-mine", zh = false).env)
    }

    @Test
    fun deepseek_preset_ships_official_model_mapping() {
        val deepseek = bundled.claude.single { it.id == "deepseek" }
        assertEquals("deepseek-flash[1m]", deepseek.env["ANTHROPIC_MODEL"])
        assertEquals("deepseek-flash[1m]", deepseek.env["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("deepseek-flash[1m]", deepseek.env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("deepseek-flash", deepseek.env["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals("deepseek-flash", deepseek.env["CLAUDE_CODE_SUBAGENT_MODEL"])
        assertEquals("max", deepseek.env["CLAUDE_CODE_EFFORT_LEVEL"])
        assertEquals("786432", deepseek.env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"])
    }

    @Test
    fun kimi_code_preset_uses_api_key_header_and_context_window() {
        val kimi = bundled.claude.single { it.id == "kimi_code" }
        assertEquals(AuthHeader.API_KEY, kimi.resolvedAuthHeader)
        assertEquals(AuthHeader.API_KEY, kimi.toProfile(token = "sk", zh = false).authHeader)
        assertEquals("kimi-for-coding", kimi.env["ANTHROPIC_MODEL"])
        assertEquals("1048576", kimi.env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"])
        assertEquals("1048576", kimi.env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"])
    }

    @Test
    fun minimax_and_zhipu_presets_ship_documented_mappings() {
        val cn = bundled.claude.single { it.id == "minimax_cn" }
        assertEquals("https://api.minimax.cn/anthropic", normalizeBaseUrl(cn.baseUrl))
        assertEquals("MiniMax-M3[1m]", cn.env["ANTHROPIC_MODEL"])
        assertEquals("1000000", cn.env["CLAUDE_CODE_AUTO_COMPACT_WINDOW"])

        val zhipu = bundled.claude.single { it.id == "zhipu_cn" }
        assertEquals("glm-4.7", zhipu.env["ANTHROPIC_MODEL"])
        assertEquals("3000000", zhipu.env["API_TIMEOUT_MS"])
    }
}
