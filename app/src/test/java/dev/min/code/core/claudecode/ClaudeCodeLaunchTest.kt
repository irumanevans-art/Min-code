package dev.min.code.core.claudecode

import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.AuthHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCodeLaunchTest {

    private val entry = listOf("/usr/bin/node", "/opt/claude/cli.js")
    private val profile = ApiProfile(id = "p", token = "sk-test", baseUrl = "https://relay.example.com")

    private fun args(options: ClaudeCodeManager.SessionOptions, sessionId: String = "sid") =
        claudeLaunchArgs(entry, options, sessionId)

    /** `--flag value` 里 flag 后面那个值；flag 不在就是 null */
    private fun List<String>.valueOf(flag: String): String? =
        indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    @Test
    fun `argv starts with the entry and speaks stream-json both ways`() {
        val argv = args(ClaudeCodeManager.SessionOptions())
        assertEquals(entry, argv.take(2))
        assertEquals("stream-json", argv.valueOf("--output-format"))
        assertEquals("stream-json", argv.valueOf("--input-format"))
        // 没有它就没有权限询问面，需审批的工具会被直接拒绝
        assertEquals("stdio", argv.valueOf("--permission-prompt-tool"))
    }

    @Test
    fun `a new session names its id, a resumed one resumes it, never both`() {
        val fresh = args(ClaudeCodeManager.SessionOptions(newSessionId = "n"), sessionId = "n")
        assertEquals("n", fresh.valueOf("--session-id"))
        assertFalse("--resume" in fresh)

        val resumed = args(ClaudeCodeManager.SessionOptions(resumeSessionId = "old"), sessionId = "old")
        assertEquals("old", resumed.valueOf("--resume"))
        assertFalse("--session-id" in resumed)
    }

    @Test
    fun `an effort outside the ladder is dropped instead of silently ignored by the CLI`() {
        assertEquals("high", args(ClaudeCodeManager.SessionOptions(effort = "high")).valueOf("--effort"))
        assertNull(args(ClaudeCodeManager.SessionOptions(effort = "turbo")).valueOf("--effort"))
    }

    @Test
    fun `ultracode takes the effort slot and the ladder level is not passed too`() {
        val argv = args(ClaudeCodeManager.SessionOptions(ultracode = true, effort = "low"))
        assertEquals(listOf(ClaudeCodeManager.EFFORT_ULTRACODE), argv.filterIndexed { i, _ -> i > 0 && argv[i - 1] == "--effort" })
    }

    @Test
    fun `permission mode is always explicit and skip means bypass`() {
        val plan = args(ClaudeCodeManager.SessionOptions(permissionMode = ClaudeCodePermissionMode.PLAN))
        assertEquals("plan", plan.valueOf("--permission-mode"))
        // 不带它的话运行时切到 bypassPermissions 会被 CLI 拒绝
        assertTrue("--allow-dangerously-skip-permissions" in plan)

        val skip = args(ClaudeCodeManager.SessionOptions(skipPermissions = true, permissionMode = ClaudeCodePermissionMode.PLAN))
        assertEquals(ClaudeCodePermissionMode.BYPASS.wire, skip.valueOf("--permission-mode"))
    }

    @Test
    fun `blank model is not passed`() {
        assertFalse("--model" in args(ClaudeCodeManager.SessionOptions(model = "  ")))
        assertEquals("opus", args(ClaudeCodeManager.SessionOptions(model = "opus")).valueOf("--model"))
    }

    private fun env(
        profile: ApiProfile = this.profile,
        options: ClaudeCodeManager.SessionOptions = ClaudeCodeManager.SessionOptions(),
        relayBaseUrl: String? = null,
    ) = claudeSessionEnv(options, profile, relayBaseUrl, netSnap = null)

    @Test
    fun `token lands in the key the profile chose, and only there`() {
        val bearer = env()
        assertEquals("sk-test", bearer["ANTHROPIC_AUTH_TOKEN"])
        assertNull(bearer["ANTHROPIC_API_KEY"])

        val apiKey = env(profile.copy(authHeader = AuthHeader.API_KEY))
        assertEquals("sk-test", apiKey["ANTHROPIC_API_KEY"])
        assertNull(apiKey["ANTHROPIC_AUTH_TOKEN"])
    }

    @Test
    fun `the local relay address beats the profile address, and blank falls back to the default`() {
        assertEquals("https://relay.example.com", env()["ANTHROPIC_BASE_URL"])
        assertEquals("http://127.0.0.1:9", env(relayBaseUrl = "http://127.0.0.1:9")["ANTHROPIC_BASE_URL"])
        assertEquals(
            ClaudeCodeManager.DEFAULT_BASE_URL,
            env(profile.copy(baseUrl = ""))["ANTHROPIC_BASE_URL"],
        )
    }

    @Test
    fun `profile env may override app preferences but never the address or the token`() {
        val custom = profile.copy(
            env = mapOf(
                "CLAUDE_CODE_MAX_CONTEXT_TOKENS" to "128000",
                "ANTHROPIC_BASE_URL" to "https://evil.example.com",
                "ANTHROPIC_AUTH_TOKEN" to "stolen",
            ),
        )
        val map = env(custom)
        assertEquals("128000", map["CLAUDE_CODE_MAX_CONTEXT_TOKENS"])
        assertEquals("https://relay.example.com", map["ANTHROPIC_BASE_URL"])
        assertEquals("sk-test", map["ANTHROPIC_AUTH_TOKEN"])
    }

    @Test
    fun `prompt cache ttl goes to both the main and the subagent switch, and only when valid`() {
        val oneHour = env(options = ClaudeCodeManager.SessionOptions(promptCacheTtl = "1h"))
        assertEquals("1h", oneHour["CLAUDE_CODE_PROMPT_CACHE_TTL"])
        assertEquals("1h", oneHour["CLAUDE_CODE_SUBAGENT_PROMPT_CACHE_TTL"])

        val bogus = env(options = ClaudeCodeManager.SessionOptions(promptCacheTtl = "3d"))
        assertNull(bogus["CLAUDE_CODE_PROMPT_CACHE_TTL"])
        assertNull(bogus["CLAUDE_CODE_SUBAGENT_PROMPT_CACHE_TTL"])
    }

    @Test
    fun `the sandbox flag and the autoupdater switch are always set`() {
        val map = env()
        // proot 以 --root-id 运行，不声明沙箱的话 bypassPermissions 会直接 exit(1)
        assertEquals("1", map["IS_SANDBOX"])
        assertEquals("1", map["DISABLE_AUTOUPDATER"])
    }
}
