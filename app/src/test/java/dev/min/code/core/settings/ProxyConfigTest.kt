package dev.min.code.core.settings

import dev.min.agent.host.UpstreamProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyConfigTest {
    @Test fun offOrIncompleteMeansDirect() {
        assertNull(ProxyConfig().upstream())
        assertNull(ProxyConfig(ProxyKind.OFF, "127.0.0.1", 7890).upstream())
        assertNull(ProxyConfig(ProxyKind.SOCKS5, "", 1080).upstream())
        assertNull(ProxyConfig(ProxyKind.SOCKS5, "127.0.0.1", 0).upstream())
        assertNull(ProxyConfig(ProxyKind.HTTP, "bad host", 80).upstream())
        assertEquals(UpstreamProxy(UpstreamProxy.Type.HTTP, "127.0.0.1", 7890), ProxyConfig(ProxyKind.HTTP, "127.0.0.1", 7890).upstream())
    }

    @Test fun blankPasswordKeepsSavedOneUnlessClearedOrUserChanged() {
        val saved = ProxyConfig(ProxyKind.SOCKS5, "p.example", 1080, "alice", "pw")
        assertEquals("pw", saved.applying(ProxyEdit(ProxyKind.SOCKS5, "p.example", 1080, "alice")).password)
        assertEquals("", saved.applying(ProxyEdit(ProxyKind.SOCKS5, "p.example", 1080, "alice", clearPassword = true)).password)
        assertEquals("", saved.applying(ProxyEdit(ProxyKind.SOCKS5, "p.example", 1080, "bob")).password)
        assertEquals("new", saved.applying(ProxyEdit(ProxyKind.SOCKS5, "p.example", 1080, "bob", "new", clearPassword = true)).password)
        assertEquals("xn--fiqs8s.example", saved.applying(ProxyEdit(ProxyKind.SOCKS5, " 中国.example ", 1080, "alice")).host)
        // 关掉代理不丢已填的东西
        assertEquals(saved.copy(kind = ProxyKind.OFF), saved.applying(ProxyEdit(ProxyKind.OFF, "p.example", 1080, "alice")))
    }

    @Test fun parsesCommonProxyLinks() {
        assertEquals(ProxyConfig(ProxyKind.SOCKS5, "1.2.3.4", 1080, "u", "p@ss"), parseProxyLink("socks5://u:p%40ss@1.2.3.4:1080"))
        assertEquals(ProxyConfig(ProxyKind.SOCKS5, "proxy.example", 7891), parseProxyLink(" socks://proxy.example:7891 "))
        assertEquals(ProxyConfig(ProxyKind.HTTP, "127.0.0.1", 7890), parseProxyLink("http://127.0.0.1:7890"))
        assertEquals(ProxyConfig(ProxyKind.HTTP, "::1", 8080), parseProxyLink("http://[::1]:8080"))
        assertEquals(ProxyConfig(ProxyKind.SOCKS5, "s.example", 443, "me", "x y"), parseProxyLink("tg://socks?server=s.example&port=443&user=me&pass=x%20y"))
        assertEquals(ProxyConfig(ProxyKind.SOCKS5, "s.example", 1080), parseProxyLink("https://t.me/socks?server=s.example&port=1080"))
        // Telegram 自己认的其它写法（ProxySettings.fromUri）：不带 // 的 tg:、telegram.me / telegram.dog、host= 别名
        assertEquals(ProxyConfig(ProxyKind.SOCKS5, "s.example", 1080), parseProxyLink("tg:socks?server=s.example&port=1080"))
        assertEquals(ProxyConfig(ProxyKind.SOCKS5, "s.example", 1080), parseProxyLink("https://telegram.me/socks?host=s.example&port=1080"))
        assertEquals(ProxyConfig(ProxyKind.SOCKS5, "s.example", 1080), parseProxyLink("https://telegram.dog/socks?server=s.example&port=1080"))
        // 国际化域名转 punycode
        assertEquals(ProxyConfig(ProxyKind.SOCKS5, "xn--fiqs8s.example", 1080), parseProxyLink("tg://socks?server=%E4%B8%AD%E5%9B%BD.example&port=1080"))
        // MTProto 不是通用协议；普通文字、半截地址都不算链接
        for (raw in listOf("tg://proxy?server=s.example&port=443&secret=ab", "127.0.0.1", "socks5://", "ftp://h:21", "tg://socks?server=s.example",
                "https://evil.example/socks?server=s.example&port=1080", "https://h.example:8443")) {
            assertNull(raw, parseProxyLink(raw))
        }
    }

    @Test fun portInput() {
        assertEquals(7890, parseProxyPort(" 7890 "))
        for (raw in listOf("", "0", "65536", "-1", "abc")) assertNull(raw, parseProxyPort(raw))
    }

    @Test fun jsonRoundTripAndBadDataFallsBackToDefault() {
        val config = ProxyConfig(ProxyKind.HTTP, "h", 1, "u", "p")
        assertEquals(config, decodeProxyJson(encodeProxyJson(config)))
        assertEquals(ProxyConfig(), decodeProxyJson("{\"kind\":\"QUIC\"}"))
        assertEquals(ProxyConfig(), decodeProxyJson("not json"))
        assertEquals(ProxyConfig(), decodeProxyJson(""))
    }

    @Test fun toStringHidesSecrets() {
        val text = ProxyConfig(ProxyKind.SOCKS5, "secret-host.example", 1080, "alice", "hunter2").toString()
        for (secret in listOf("secret-host", "alice", "hunter2")) assertFalse(text, text.contains(secret))
    }
}
