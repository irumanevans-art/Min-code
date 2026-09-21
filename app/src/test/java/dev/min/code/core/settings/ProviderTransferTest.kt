package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入 / 导出。
 *
 * 这一组里有两条是**安全断言**而不是功能断言：redacted 的整份文本里不能出现 token 子串，
 * 以及认不得的 schema 必须整份拒掉。前者一旦回归，用户以为自己发出去的是一份干净的
 * 配置，其实是一串 key；后者一旦回归，会出现「导了一半」这种没法收拾的状态。
 */
class ProviderTransferTest {

    private val token = "sk-ant-verysecrettoken-0001"
    private val codexKey = "sk-codex-verysecretkey-0002"

    private val settings = AppSettings(
        profiles = listOf(
            ApiProfile(
                id = "one",
                label = "Kimi",
                token = token,
                baseUrl = "https://api.moonshot.cn/anthropic",
                env = mapOf("ANTHROPIC_SMALL_FAST_MODEL" to "kimi-k2-turbo-preview"),
                presetId = "moonshot",
            ),
            ApiProfile(id = "two", label = "自建", token = "", baseUrl = "http://192.168.1.9:3000"),
        ),
        activeProfileId = "one",
        codexProfiles = listOf(
            CodexProfile(
                id = "cx",
                label = "中转",
                baseUrl = "https://relay.example.com/v1",
                apiKey = codexKey,
                authMode = CodexAuthMode.RELAY,
                wireApi = "chat",
            ),
        ),
        activeCodexProfileId = "cx",
    )

    // -----------------------------------------------------------------------
    // 导出：redacted 是这套里唯一一处「默认不泄漏」的保证，必须逐字检查
    // -----------------------------------------------------------------------

    @Test
    fun a_redacted_export_contains_no_secret_anywhere() {
        val text = buildTransferFile(settings, includeSecrets = false, now = "2026-09-21T10:00:00+08:00")

        // 不是检查某个字段，是检查整份文本 —— 将来有人加了个字段顺手带上 token，这条会挂
        assertFalse(text.contains(token))
        assertFalse(text.contains(codexKey))
        // 键本身也不该出现：写成 "token": "" 会让这条配置导进去之后变成一个看不出问题的坏条目
        assertFalse(text.contains("\"token\""))
        assertFalse(text.contains("\"apiKey\""))
        // 该带走的照常带走
        assertTrue(text.contains("api.moonshot.cn"))
        assertTrue(text.contains("ANTHROPIC_SMALL_FAST_MODEL"))
        assertTrue(text.contains(SECRETS_REDACTED))
    }

    @Test
    fun b_plain_export_carries_the_secrets() {
        val text = buildTransferFile(settings, includeSecrets = true, now = "now")
        assertTrue(text.contains(token))
        assertTrue(text.contains(codexKey))
        assertTrue(text.contains(SECRETS_PLAIN))
    }

    @Test
    fun c_export_never_carries_local_ids() {
        val text = buildTransferFile(settings, includeSecrets = true, now = "now")
        // 本机 UUID 跨设备没有意义，导过去还原成同一个 id 只会让两台设备上的条目纠缠不清
        assertFalse(text.contains("\"one\""))
        assertFalse(text.contains("\"cx\""))
    }

    @Test
    fun d_round_trip_keeps_everything_but_the_key() {
        val text = buildTransferFile(settings, includeSecrets = false, now = "now")
        val file = (parseTransferFile(text) as TransferParse.Ok).file
        val back = file.claude.first().toProfile()

        assertEquals("Kimi", back.label)
        assertEquals("https://api.moonshot.cn/anthropic", back.baseUrl)
        assertEquals(mapOf("ANTHROPIC_SMALL_FAST_MODEL" to "kimi-k2-turbo-preview"), back.env)
        assertEquals("moonshot", back.presetId)
        // 键不存在 → token 是空串 → 列表上明确标「待填 API Key」，而不是一条看着能用的坏配置
        assertTrue(back.missingToken)
    }

    @Test
    fun e_file_name_says_which_one_is_hot() {
        assertEquals("min-providers-20260921.json", transferFileName("20260921", false))
        assertEquals("min-providers-20260921-with-keys.json", transferFileName("20260921", true))
    }

    // -----------------------------------------------------------------------
    // 解析：认不得的东西一律整份拒，不做部分导入
    // -----------------------------------------------------------------------

    @Test
    fun f_unknown_schema_is_refused_whole() {
        val other = """{"schema":"cc-switch/v2","claude":[{"label":"x","baseUrl":"https://a.b"}]}"""
        val err = parseTransferFile(other) as TransferParse.Err
        assertEquals(TransferParse.Reason.UNKNOWN_SCHEMA, err.reason)
        assertEquals("cc-switch/v2", err.detail)
    }

    @Test
    fun g_garbage_and_empty_are_told_apart() {
        assertEquals(
            TransferParse.Reason.NOT_JSON,
            (parseTransferFile("{ not json") as TransferParse.Err).reason,
        )
        assertEquals(
            TransferParse.Reason.EMPTY,
            (parseTransferFile("""{"schema":"$TRANSFER_SCHEMA"}""") as TransferParse.Err).reason,
        )
    }

    @Test
    fun h_unknown_fields_do_not_break_a_good_file() {
        val text = """
            {"schema":"$TRANSFER_SCHEMA","somethingNew":42,
             "claude":[{"label":"A","baseUrl":"https://a.example.com","futureField":"x"}]}
        """.trimIndent()
        val ok = parseTransferFile(text) as TransferParse.Ok
        assertEquals(1, ok.file.claude.size)
    }

    // -----------------------------------------------------------------------
    // 合并：四条规则各一条，外加「不动已有条目」的逐字段断言
    // -----------------------------------------------------------------------

    private val existing = listOf(
        ApiProfile(id = "x", label = "A", token = "k1", baseUrl = "https://a.example.com"),
        ApiProfile(id = "y", label = "B", token = "k2", baseUrl = "https://b.example.com"),
    )

    @Test
    fun i_same_address_and_key_is_skipped() {
        val incoming = listOf(ApiProfile(id = "", label = "A", token = "k1", baseUrl = "https://a.example.com/"))
        val out = mergeClaudeImports(existing, incoming)
        assertEquals(0, out.added.size)
        assertEquals(1, out.skipped)
    }

    @Test
    fun j_redacted_entry_never_duplicates_a_local_one() {
        // 「把自己刚导出的 redacted 文件又导回来」。照「地址同 key 不同 → 加」办的话，
        // 一次误操作就能把整张表翻倍，且新增的那一半全是没 key 的空壳
        val incoming = listOf(
            ApiProfile(id = "", label = "A", token = "", baseUrl = "https://a.example.com"),
            ApiProfile(id = "", label = "B", token = "", baseUrl = "https://b.example.com"),
        )
        val out = mergeClaudeImports(existing, incoming)
        assertEquals(0, out.added.size)
        assertEquals(2, out.skipped)
    }

    @Test
    fun k_same_address_different_key_is_a_new_entry() {
        val incoming = listOf(ApiProfile(id = "", label = "A2", token = "k9", baseUrl = "https://a.example.com"))
        val out = mergeClaudeImports(existing, incoming)
        assertEquals(1, out.added.size)
        assertEquals("k9", out.added.first().token)
        assertEquals(0, out.skipped)
    }

    @Test
    fun l_label_clash_gets_a_suffix() {
        val incoming = listOf(
            ApiProfile(id = "", label = "A", token = "k9", baseUrl = "https://c.example.com"),
            ApiProfile(id = "", label = "A", token = "k8", baseUrl = "https://d.example.com"),
        )
        val out = mergeClaudeImports(existing, incoming)
        assertEquals(listOf("A (2)", "A (3)"), out.added.map { it.label })
        assertEquals(2, out.renamed)
    }

    @Test
    fun m_blank_label_is_left_blank() {
        // 空名靠地址显示，给它加个 "(2)" 等于凭空造出一个名字
        val incoming = listOf(ApiProfile(id = "", label = "", token = "k9", baseUrl = "https://e.example.com"))
        val out = mergeClaudeImports(existing, incoming)
        assertEquals("", out.added.single().label)
        assertEquals(0, out.renamed)
    }

    @Test
    fun n_existing_entries_are_never_touched() {
        val incoming = listOf(ApiProfile(id = "", label = "A", token = "k9", baseUrl = "https://a.example.com"))
        val before = existing.toList()
        mergeClaudeImports(existing, incoming)
        // 合并是纯函数，既有的那张表原样在那儿；active 压根不在它的参数表里，改不了
        assertEquals(before, existing)
    }

    @Test
    fun o_codex_tells_the_auth_modes_apart() {
        // 官方登录和官方 API key 两条的 baseUrl 一模一样，只看地址的话第二条永远导不进来
        val mine = listOf(
            CodexProfile(id = "a", label = "官方", baseUrl = "https://api.openai.com/v1", authMode = CodexAuthMode.CLI),
        )
        val incoming = listOf(
            CodexProfile(
                id = "",
                label = "官方 key",
                baseUrl = "https://api.openai.com/v1",
                apiKey = "sk-1",
                authMode = CodexAuthMode.OPENAI_API_KEY,
            ),
        )
        assertEquals(1, mergeCodexImports(mine, incoming).added.size)
    }

    // -----------------------------------------------------------------------
    // 从 Rootfs 现成的 settings.json 导
    // -----------------------------------------------------------------------

    @Test
    fun p_reads_the_env_block_only() {
        val json = """
            {"permissions":{"allow":["Bash"]},
             "env":{"ANTHROPIC_BASE_URL":"https://relay.example.com/",
                    "ANTHROPIC_AUTH_TOKEN":"sk-from-file",
                    "API_TIMEOUT_MS":"600000"}}
        """.trimIndent()
        val profile = parseClaudeSettingsImport(json, label = "Rootfs")!!

        assertEquals("https://relay.example.com", profile.baseUrl)
        assertEquals("sk-from-file", profile.token)
        // 两个专有键有自己的字段，不该同时躺在 env 里 —— 两处都能写就会「界面显示 A、进程用 B」
        assertEquals(mapOf("API_TIMEOUT_MS" to "600000"), profile.env)
        // 用户本来就在用的地址，不为它补一道明文确认
        assertTrue(profile.insecureAck)
    }

    @Test
    fun q_config_without_anything_usable_is_not_guessed_at() {
        assertNull(parseClaudeSettingsImport("""{"model":"opus"}"""))
        assertNull(parseClaudeSettingsImport("""{"env":{"FOO":"bar"}}"""))
        assertNull(parseClaudeSettingsImport("not json at all"))
    }

    // -----------------------------------------------------------------------
    // 备份文件名与轮转
    // -----------------------------------------------------------------------

    @Test
    fun r_backup_names_sort_by_time_and_keep_the_tag() {
        val a = backupFileName(BACKUP_TAG_CLAUDE_SETTINGS, 1_700_000_000_000L)
        val b = backupFileName(BACKUP_TAG_CLAUDE_SETTINGS, 1_800_000_000_000L)
        assertEquals(BACKUP_TAG_CLAUDE_SETTINGS, backupTag(a))
        // 时间戳定宽，所以按名字排序就是按时间排序 —— 轮转不必去读 lastModified
        assertTrue(a < b)
    }

    @Test
    fun s_rotation_keeps_the_newest_per_tag() {
        val names = (1..13).map { backupFileName(BACKUP_TAG_CLAUDE_SETTINGS, it * 100_000_000_000L) } +
            listOf(backupFileName("other", 1L))
        val expired = expiredBackups(names, keep = 10)

        assertEquals(3, expired.size)
        // 删的是最旧的三份，留下的是最新的十份
        assertTrue(expired.all { it in names.take(3) })
        assertTrue(expired.none { backupTag(it) == "other" })
    }

    @Test
    fun t_new_fields_round_trip() {
        val rich = settings.copy(
            profiles = listOf(
                ApiProfile(
                    id = "one",
                    label = "Kimi",
                    token = token,
                    baseUrl = "https://api.moonshot.cn/anthropic",
                    note = "公司号",
                    apiFormat = ApiFormat.OPENAI_CHAT,
                    authHeader = AuthHeader.API_KEY,
                    fullUrlEndpoint = true,
                ),
            ),
            codexProfiles = listOf(
                CodexProfile(
                    id = "cx",
                    label = "中转",
                    baseUrl = "https://relay.example.com/v1",
                    apiKey = codexKey,
                    authMode = CodexAuthMode.RELAY,
                    note = "备用",
                ),
            ),
        )
        val text = buildTransferFile(rich, includeSecrets = true, now = "now")
        val file = (parseTransferFile(text) as TransferParse.Ok).file
        val back = file.claude.first().toProfile()
        assertEquals("公司号", back.note)
        assertEquals(ApiFormat.OPENAI_CHAT, back.apiFormat)
        assertEquals(AuthHeader.API_KEY, back.authHeader)
        assertTrue(back.fullUrlEndpoint)
        assertEquals("备用", file.codex.first().toProfile().note)
    }

    @Test
    fun u_old_files_without_new_fields_still_load() {
        val text = """
            {"schema":"$TRANSFER_SCHEMA",
             "claude":[{"label":"A","baseUrl":"https://a.example.com","token":"sk-1"}]}
        """.trimIndent()
        val back = (parseTransferFile(text) as TransferParse.Ok).file.claude.first().toProfile()
        assertEquals(ApiFormat.ANTHROPIC_MESSAGES, back.apiFormat)
        assertEquals(AuthHeader.AUTH_TOKEN, back.authHeader)
        assertFalse(back.fullUrlEndpoint)
        assertEquals("", back.note)
    }
}
