package dev.min.code.core.claudecode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「替 CLI 开浏览器」那座桥只放行 Anthropic 的授权页。URL 来自 rootfs 里的一个文件，
 * 而 rootfs 是 agent 能写的地方。
 */
class ClaudeSubscriptionTest {

    @Test
    fun `the url the cli hands to BROWSER is accepted`() {
        // 2.1.280 实测交给 $BROWSER 的形状（state / challenge 换成占位）
        assertTrue(
            isClaudeAuthorizeUrl(
                "https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e" +
                    "&response_type=code&redirect_uri=http%3A%2F%2Flocalhost%3A41253%2Fcallback" +
                    "&scope=user%3Ainference&code_challenge=abc&code_challenge_method=S256&state=xyz",
            ),
        )
        assertTrue(isClaudeAuthorizeUrl("https://claude.ai/oauth/authorize?x=1"))
    }

    @Test
    fun `anything else is refused`() {
        listOf(
            "http://claude.com/cai/oauth/authorize",
            "https://evil.example/oauth/authorize",
            "https://claude.com.evil.example/oauth/authorize",
            "https://claude.com@evil.example/oauth/authorize",
            "https://claude.com/login",
            "file:///root/.claude/.credentials.json",
            "javascript:alert(1)",
            "not a url",
            "",
        ).forEach { assertFalse("should refuse: $it", isClaudeAuthorizeUrl(it)) }
    }

    @Test
    fun `logout only counts as done on exit code zero`() {
        assertTrue(cliLogoutSucceeded(0))
        // 1 = CLI 报错；-1 = 超时被强杀；null = rootfs 里没有能跑的 CLI
        listOf(1, 127, -1, null).forEach { assertFalse("exit $it", cliLogoutSucceeded(it)) }
    }
}
