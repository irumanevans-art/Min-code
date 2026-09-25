package dev.min.code.core.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 供应商配置往外投影的那几条规则。
 *
 * 这是整个切换功能里最容易悄悄出事的一层：写错了不会崩、不会报错，只会让请求发去
 * 上一家、或者让一个早该删掉的环境变量继续生效。三组分别钉住
 * 「谁说了算」「切换之后旧的清不清得干净」「key 会不会落盘」。
 */
class ProviderEnvTest {

    private fun profile(
        baseUrl: String = "https://relay.example",
        token: String = "sk-live",
        env: Map<String, String> = emptyMap(),
    ) = ApiProfile(id = "a", label = "中转", token = token, baseUrl = baseUrl, env = env)

    // -----------------------------------------------------------------------
    // 事实来源赢过 env 表
    //
    // 地址和 token 各有专有字段。要是 env 表里也能写同名键并且生效，就会出现
    // 「设置页上白纸黑字是一个地址、请求却发去了另一个」——用户自己查不出来。
    // -----------------------------------------------------------------------

    @Test
    fun reserved_keys_written_into_the_env_table_are_ignored() {
        val sanitized = sanitizedProfileEnv(
            profile(
                env = mapOf(
                    "ANTHROPIC_BASE_URL" to "https://evil.example",
                    "ANTHROPIC_AUTH_TOKEN" to "sk-stolen",
                    // 另一种放法也占住：只占一个的话，选了 Bearer 的人还能从自定义 env 塞第二把钥匙
                    "ANTHROPIC_API_KEY" to "sk-also-stolen",
                    "PATH" to "/tmp",
                    "HOME" to "/tmp",
                    "IS_SANDBOX" to "0",
                    "ANTHROPIC_SMALL_FAST_MODEL" to "claude-haiku-4-5",
                ),
            ),
        )
        assertEquals(mapOf("ANTHROPIC_SMALL_FAST_MODEL" to "claude-haiku-4-5"), sanitized)
    }

    @Test
    fun keys_are_trimmed_and_blank_ones_dropped_but_values_are_left_alone() {
        val sanitized = sanitizedProfileEnv(
            profile(env = mapOf("  API_TIMEOUT_MS  " to "600000", "   " to "x", "H" to " v ")),
        )
        // 值不 trim：有的自定义头部末尾那个空格是有意义的，替用户擦掉是越界
        assertEquals(mapOf("API_TIMEOUT_MS" to "600000", "H" to " v "), sanitized)
    }

    // -----------------------------------------------------------------------
    // 托管区的增量
    //
    // settings.json 同时被 CLI 自己写、也可能被用户手写，所以只能动我们记过账的键。
    // 这组里每一条都对应一个真实会出的岔子。
    // -----------------------------------------------------------------------

    private fun settings(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> =
        linkedMapOf(*pairs)

    private fun envOf(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test
    fun a_key_the_previous_provider_needed_is_removed_when_the_new_one_does_not() {
        // A 家要 SMALL_FAST_MODEL，B 家不要。不删的话它会留在文件里继续生效
        val existing = settings(
            "env" to envOf(
                "ANTHROPIC_BASE_URL" to "https://a.example",
                "ANTHROPIC_SMALL_FAST_MODEL" to "a-haiku",
            ),
        )
        val (next, written) = applyManagedEnv(
            existing = existing,
            managed = mapOf("ANTHROPIC_BASE_URL" to "https://b.example"),
            previousKeys = setOf("ANTHROPIC_BASE_URL", "ANTHROPIC_SMALL_FAST_MODEL"),
        )
        val env = next["env"] as JsonObject
        assertNull(env["ANTHROPIC_SMALL_FAST_MODEL"])
        assertEquals(JsonPrimitive("https://b.example"), env["ANTHROPIC_BASE_URL"])
        assertEquals(setOf("ANTHROPIC_BASE_URL"), written)
    }

    @Test
    fun keys_we_never_wrote_are_never_touched() {
        val existing = settings(
            "env" to envOf("MY_OWN_VAR" to "keep-me", "ANTHROPIC_BASE_URL" to "https://a.example"),
        )
        val (next, _) = applyManagedEnv(
            existing = existing,
            managed = mapOf("ANTHROPIC_BASE_URL" to "https://b.example"),
            previousKeys = setOf("ANTHROPIC_BASE_URL"),
        )
        assertEquals(JsonPrimitive("keep-me"), (next["env"] as JsonObject)["MY_OWN_VAR"])
    }

    @Test
    fun everything_outside_the_env_block_survives_untouched() {
        // 这个文件里还住着 CLI 自己的状态。碰坏一个键，轻则重新走引导，重则登录态丢失
        val permissions = JsonObject(mapOf("allow" to buildJsonArray { add(JsonPrimitive("Bash")) }))
        val existing = settings(
            "permissions" to permissions,
            "model" to JsonPrimitive("claude-opus-5"),
            "bypassPermissionsModeAccepted" to JsonPrimitive(true),
        )
        val (next, _) = applyManagedEnv(existing, mapOf("ANTHROPIC_BASE_URL" to "https://b.example"), emptySet())

        assertEquals(permissions, next["permissions"])
        assertEquals(JsonPrimitive("claude-opus-5"), next["model"])
        assertEquals(JsonPrimitive(true), next["bypassPermissionsModeAccepted"])
    }

    @Test
    fun an_emptied_env_block_disappears_instead_of_becoming_an_empty_object() {
        // 留一个 "env": {} 在那儿，下次有人看这个文件会以为托管还开着
        val existing = settings("env" to envOf("ANTHROPIC_BASE_URL" to "https://a.example"))
        val (next, written) = applyManagedEnv(existing, emptyMap(), setOf("ANTHROPIC_BASE_URL"))

        assertFalse(next.containsKey("env"))
        assertTrue(written.isEmpty())
    }

    @Test
    fun turning_management_off_clears_exactly_what_we_wrote() {
        val existing = settings(
            "env" to envOf(
                "ANTHROPIC_BASE_URL" to "https://a.example",
                "ANTHROPIC_AUTH_TOKEN" to "sk-live",
                "MY_OWN_VAR" to "keep-me",
            ),
            "model" to JsonPrimitive("claude-opus-5"),
        )
        // 托管关掉 = 目标状态是「一个托管键都没有」
        val (next, written) = applyManagedEnv(
            existing = existing,
            managed = emptyMap(),
            previousKeys = setOf("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN"),
        )
        assertEquals(envOf("MY_OWN_VAR" to "keep-me"), next["env"])
        assertEquals(JsonPrimitive("claude-opus-5"), next["model"])
        assertTrue(written.isEmpty())
    }

    // -----------------------------------------------------------------------
    // key 落不落盘
    //
    // 托管默认不写 token。写空串和不写这个键是两件事：空串会让终端里的 claude
    // 拿着一个空 key 去请求，换回来一个莫名其妙的 401。
    // -----------------------------------------------------------------------

    @Test
    fun the_token_key_is_absent_rather_than_blank_when_secrets_stay_off() {
        val env = managedSettingsEnv(profile(), includeSecrets = false)
        assertFalse(env.containsKey("ANTHROPIC_AUTH_TOKEN"))
        assertEquals("https://relay.example", env["ANTHROPIC_BASE_URL"])
    }

    @Test
    fun the_token_is_written_only_when_explicitly_allowed() {
        val env = managedSettingsEnv(profile(), includeSecrets = true)
        assertEquals("sk-live", env["ANTHROPIC_AUTH_TOKEN"])
        // 没有 token 的条目即使开了开关也不写一个空键
        assertFalse(managedSettingsEnv(profile(token = ""), includeSecrets = true)
            .containsKey("ANTHROPIC_AUTH_TOKEN"))
    }

    // -----------------------------------------------------------------------
    // 终端的凭据
    //
    // 在这之前终端里一个凭据都没有：同一个 App 里，会话页能用的 token，
    // 换个页签敲 claude 就用不了。这组钉住那个缺口被堵上，并且没堵歪。
    // -----------------------------------------------------------------------

    private fun settingsWith(
        claude: ApiProfile? = profile(),
        codex: CodexProfile? = null,
        inject: Boolean = true,
    ) = AppSettings(
        profiles = listOfNotNull(claude),
        activeProfileId = claude?.id.orEmpty(),
        codexProfiles = listOfNotNull(codex),
        activeCodexProfileId = codex?.id.orEmpty(),
        injectCredentialsIntoShells = inject,
    )

    @Test
    fun the_switch_really_switches_it_off() {
        assertEquals(emptyMap<String, String>(), shellCredentialEnv(settingsWith(inject = false)))
    }

    @Test
    fun both_engines_get_their_keys_because_it_is_the_same_person_at_the_prompt() {
        val env = shellCredentialEnv(
            settingsWith(
                claude = profile(env = mapOf("API_TIMEOUT_MS" to "600000")),
                codex = CodexProfile(
                    id = "c",
                    authMode = CodexAuthMode.RELAY,
                    baseUrl = "https://codex.example/v1/",
                    apiKey = "sk-codex",
                ),
            ),
        )
        assertEquals("https://relay.example", env["ANTHROPIC_BASE_URL"])
        assertEquals("sk-live", env["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("600000", env["API_TIMEOUT_MS"])
        // 末尾那个斜杠要去掉，否则拼出来的是 //v1/responses
        assertEquals("https://codex.example/v1", env["OPENAI_BASE_URL"])
        assertEquals("sk-codex", env["OPENAI_API_KEY"])
    }

    @Test
    fun the_official_codex_login_is_never_handed_someone_elses_key() {
        // CLI 模式读的是它自己那份 auth.json。塞一个环境变量进去，
        // 「官方登录」就会安静地走别人的 key
        val env = shellCredentialEnv(
            settingsWith(codex = CodexProfile(id = "c", authMode = CodexAuthMode.CLI, apiKey = "sk-codex")),
        )
        assertFalse(env.containsKey("OPENAI_API_KEY"))
        assertFalse(env.containsKey("OPENAI_BASE_URL"))
    }

    @Test
    fun a_profile_without_a_token_does_not_export_a_blank_one() {
        val env = shellCredentialEnv(settingsWith(claude = profile(token = "")))
        assertFalse(env.containsKey("ANTHROPIC_AUTH_TOKEN"))
        assertEquals("https://relay.example", env["ANTHROPIC_BASE_URL"])
    }

    // -----------------------------------------------------------------------
    // Claude 订阅：凭证是 CLI 自己的，Min 一个认证变量都不给
    //
    // 非空的 API_KEY / AUTH_TOKEN 会压过订阅登录；BASE_URL 指着中转时 CLI 会把订阅的
    // OAuth bearer 发过去（实测）。所以是「不注入」，不是「注入空串」。
    // -----------------------------------------------------------------------

    private val claudeAuthKeys = listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN")

    @Test
    fun a_subscription_shell_gets_no_claude_credentials_at_all() {
        val env = shellCredentialEnv(
            settingsWith(claude = profile(env = mapOf("API_TIMEOUT_MS" to "600000")))
                .copy(claudeAuth = ClaudeAuthMode.SUBSCRIPTION),
        )
        claudeAuthKeys.forEach { assertFalse("$it leaked into a subscription shell", env.containsKey(it)) }
        // 供应商自带的 env 也属于那家供应商，订阅时一样不给
        assertFalse(env.containsKey("API_TIMEOUT_MS"))
    }

    @Test
    fun a_subscription_keeps_the_provider_table_but_does_not_use_it() {
        val settings = settingsWith().copy(claudeAuth = ClaudeAuthMode.SUBSCRIPTION)
        // 两条路互不覆盖：供应商那条还在，切回来原样可用
        assertEquals("a", settings.activeProfile?.id)
        assertNull(settings.claudeProfile)
        assertTrue(settings.claudeConnected)
        assertEquals("a", settings.copy(claudeAuth = ClaudeAuthMode.PROVIDER).claudeProfile?.id)
    }

    @Test
    fun provider_mode_without_a_token_is_not_connected() {
        assertFalse(settingsWith(claude = profile(token = "")).claudeConnected)
        assertFalse(settingsWith(claude = null).claudeConnected)
        assertTrue(settingsWith(claude = null).copy(claudeAuth = ClaudeAuthMode.SUBSCRIPTION).claudeConnected)
    }

    @Test
    fun a_subscription_token_cannot_be_smuggled_in_through_the_env_table() {
        // 把 setup-token 印出来的东西塞进供应商的自定义 env = Min 代持订阅凭证
        val sanitized = sanitizedProfileEnv(profile(env = mapOf("CLAUDE_CODE_OAUTH_TOKEN" to "sk-ant-oat01-x")))
        assertFalse(sanitized.containsKey("CLAUDE_CODE_OAUTH_TOKEN"))
    }
}
