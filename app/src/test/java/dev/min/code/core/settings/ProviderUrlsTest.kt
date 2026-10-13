package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 供应商地址的纯规整 / 拼接 / 明文判定。这几个函数被存盘、探活、取模型、机内路由
 * 四处共用，一处错了表现都像「这家中转坏了」，所以每条规则都有一条钉子。
 */
class ProviderUrlsTest {

    // -----------------------------------------------------------------------
    // 明文地址的警示
    // -----------------------------------------------------------------------

    @Test
    fun plaintext_http_over_the_network_is_insecure() {
        // 公网 IP —— 这正是「token 在公网裸奔」的那种地址
        assertTrue(isInsecureBaseUrl("http://1.2.3.4:8080"))
        assertTrue(isInsecureBaseUrl("http://relay.example.com/v1"))
        // 局域网也算：同一个 Wi-Fi 下的任何人都在路上
        assertTrue(isInsecureBaseUrl("http://192.168.1.10:3000"))
        assertTrue(isInsecureBaseUrl("http://10.0.0.8"))
        // 大小写、前后空格不该成为绕过判定的办法
        assertTrue(isInsecureBaseUrl("  HTTP://1.2.3.4  "))
        // 127 只在第一段才算回环：128.x / 1.127.x 都是普通公网地址
        assertTrue(isInsecureBaseUrl("http://128.0.0.1"))
        assertTrue(isInsecureBaseUrl("http://1.127.0.1"))
        assertTrue(isInsecureBaseUrl("http://127.0.0.1.example.com"))
        // 用户名混淆：真正的主机是 @ 后面那个
        assertTrue(isInsecureBaseUrl("http://127.0.0.1@evil.example.com/v1"))
    }

    @Test
    fun loopback_over_http_is_not_insecure() {
        // 回环走 http 全程不出这台设备，报警只是噪音
        assertTrue(listOf(
            "http://localhost:8080",
            "http://LocalHost/v1",
            "http://api.localhost:1234",
            "http://127.0.0.1:4000",
            "http://127.1.2.3",
            "http://[::1]:8080/v1",
            "http://0:0:0:0:0:0:0:1",
            // 模拟器映射到宿主机的回环别名
            "http://10.0.2.2:3000",
        ).none { isInsecureBaseUrl(it) })
    }

    @Test
    fun https_and_non_http_are_never_insecure() {
        assertFalse(isInsecureBaseUrl("https://api.anthropic.com"))
        assertFalse(isInsecureBaseUrl("https://1.2.3.4:8443"))
        // 没有 scheme 的串根本发不出去，别拿警告去吓人
        assertFalse(isInsecureBaseUrl("1.2.3.4:8080"))
        assertFalse(isInsecureBaseUrl(""))
        assertFalse(isInsecureBaseUrl("http://"))
    }

    /** 主机是在剥掉 path / query / fragment 之后才找 `@` 的：它们里面的 `@` 骗不了判定 */
    @Test
    fun host_is_taken_before_query_and_fragment_so_an_at_sign_there_cannot_fool_it() {
        // 真主机是 evil.com，query 里那个回环只是参数
        assertTrue(isInsecureBaseUrl("http://evil.com?x=@127.0.0.1"))
        assertTrue(isInsecureBaseUrl("http://evil.com/path@127.0.0.1"))
        // 反过来：fragment 里的 @evil 不改变真主机是回环这件事
        assertFalse(isInsecureBaseUrl("http://127.0.0.1#@evil.com"))
    }

    /** 回环别名只认精确值：前缀 / 后缀相像的域名与 IP 都是外面的机器 */
    @Test
    fun lookalike_loopback_hosts_are_still_insecure() {
        assertTrue(isInsecureBaseUrl("http://localhost.evil.com"))
        assertTrue(isInsecureBaseUrl("http://notlocalhost"))
        assertTrue(isInsecureBaseUrl("http://10.0.2.20"))
        // 回环正则要求四段；两段式简写 127.1 按不安全报，宁可多警告一次也不放行
        assertTrue(isInsecureBaseUrl("http://127.1"))
    }

    @Test
    fun ipv6_loopback_is_recognised_with_and_without_brackets() {
        assertFalse(isInsecureBaseUrl("http://::1"))
        assertFalse(isInsecureBaseUrl("http://[::1]"))
        assertFalse(isInsecureBaseUrl("HTTP://[::1]:3000"))
        // 带端口的普通 IPv6 不能被当成回环
        assertTrue(isInsecureBaseUrl("http://[2001:db8::1]:8080"))
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

    /**
     * 用户从 OpenAI 兼容中转抄地址时经常带着 `/v1`。Claude / ANTHROPIC_BASE_URL
     * 的约定是不带版本段——不剥就会在探活、取模型、机内路由上拼出 `/v1/v1/…`，
     * 表现完全像「这家中转坏了」，而同一家在 cc-switch 里好好的。
     */
    @Test
    fun claude_base_url_strips_trailing_v1() {
        assertEquals("https://one.oxapi.bond", normalizeBaseUrl("https://one.oxapi.bond/v1"))
        assertEquals("https://one.oxapi.bond", normalizeBaseUrl("https://one.oxapi.bond/v1/"))
        assertEquals("https://one.oxapi.bond", normalizeBaseUrl("  https://one.oxapi.bond/V1  "))
        // 中间的 /v1 是路径的一部分，不能动
        assertEquals("https://host.example/v1/gateway", normalizeBaseUrl("https://host.example/v1/gateway"))
        assertEquals("https://host.example/v1/gateway", normalizeBaseUrl("https://host.example/v1/gateway/v1"))
    }

    /** 只剥一层，只认完整的 `/v1` 段：`/v10`、`/api.v1` 是别人的路径 */
    @Test
    fun claude_base_url_strips_exactly_one_whole_v1_segment() {
        assertEquals("https://x.example/v1", normalizeBaseUrl("https://x.example/v1/v1"))
        assertEquals("https://x.example/v10", normalizeBaseUrl("https://x.example/v10"))
        assertEquals("https://x.example/api.v1", normalizeBaseUrl("https://x.example/api.v1"))
        // 剥掉之后再收一次尾斜杠，不留 `https://x.example/`
        assertEquals("https://x.example", normalizeBaseUrl("https://x.example//v1//"))
    }

    /** 整串就是 `/v1` 或一堆斜杠，剥完是空的，同样落回官方地址而不是存空串 */
    @Test
    fun claude_base_url_that_normalizes_to_nothing_falls_back_to_the_official_one() {
        assertEquals(AppSettings.DEFAULT_BASE_URL, normalizeBaseUrl("/v1"))
        assertEquals(AppSettings.DEFAULT_BASE_URL, normalizeBaseUrl("///"))
    }

    /** Codex / OpenAI 正好相反：官方地址本身以 /v1 结尾，剥掉就对不上了 */
    @Test
    fun codex_base_url_keeps_trailing_v1() {
        assertEquals("https://api.openai.com/v1", normalizeCodexBaseUrl("https://api.openai.com/v1"))
        assertEquals("https://api.openai.com/v1", normalizeCodexBaseUrl("https://api.openai.com/v1/"))
        assertEquals("https://aierxin.cc/v1", normalizeCodexBaseUrl("  https://aierxin.cc/v1/  "))
        assertEquals("https://api.openai.com/v1", normalizeCodexBaseUrl("   "))
        assertEquals("https://api.openai.com/v1", normalizeCodexBaseUrl("/"))
    }

    @Test
    fun join_claude_api_never_doubles_v1() {
        assertEquals(
            "https://one.oxapi.bond/v1/models",
            joinClaudeApi("https://one.oxapi.bond/v1", "/v1/models"),
        )
        assertEquals(
            "https://one.oxapi.bond/v1/models",
            joinClaudeApi("https://one.oxapi.bond", "/v1/models"),
        )
        assertEquals(
            "https://one.oxapi.bond/v1/chat/completions",
            joinClaudeApi("https://one.oxapi.bond/v1/", "v1/chat/completions"),
        )
    }

    @Test
    fun join_claude_api_on_an_empty_base_uses_the_official_host() {
        assertEquals("https://api.anthropic.com/v1/models", joinClaudeApi("", "/v1/models"))
    }

    @Test
    fun join_openai_api_accepts_base_with_or_without_v1() {
        assertEquals(
            "https://aierxin.cc/v1/chat/completions",
            joinOpenAiApi("https://aierxin.cc/v1", "/chat/completions"),
        )
        assertEquals(
            "https://aierxin.cc/v1/chat/completions",
            joinOpenAiApi("https://aierxin.cc", "/chat/completions"),
        )
        assertEquals(
            "https://api.openai.com/v1/responses",
            joinOpenAiApi("https://api.openai.com/v1/", "responses"),
        )
    }

    /** 判 `/v1` 不分大小写，拼的时候保留用户写的原样，不替他改成小写 */
    @Test
    fun join_openai_api_recognises_an_uppercase_v1_and_keeps_it() {
        assertEquals("https://x.example/V1/models", joinOpenAiApi("https://x.example/V1", "/models"))
    }

    @Test
    fun join_openai_api_on_an_empty_base_uses_the_official_host() {
        assertEquals("https://api.openai.com/v1/models", joinOpenAiApi("", "models"))
    }
}
