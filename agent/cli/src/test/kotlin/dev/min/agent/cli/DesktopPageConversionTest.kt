package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.host.CachedWebPort
import dev.min.agent.host.UrlConnectionTransport
import dev.min.agent.spi.*
import dev.min.agent.tools.web.HtmlPageConverter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI

class DesktopPageConversionTest {
    @Test fun `desktop converter preserves title and structured body through real HTTP transport and cache`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var downloads = 0
        server.createContext("/guide/page") { ex ->
            downloads++
            val html = """<html><head><title>Guide &amp; examples</title></head><body>
                <nav>Navigation</nav><main><h1>Guide</h1><p>Hello <strong>world</strong> &amp; 中 😀.</p>
                <p><a href="../next">Next</a></p><script>PRIVATE SCRIPT</script></main><footer>Footer</footer>
                </body></html>""".toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            ex.sendResponseHeaders(200, html.size.toLong())
            ex.responseBody.use { it.write(html) }
        }
        server.start()
        try {
            // Only redirect the socket to loopback; the converter receives the public page URL.
            val transport = object : HttpTransport {
                private val http = UrlConnectionTransport()
                override suspend fun send(request: HttpRequest): HttpResponse = http.send(HttpRequest(
                    request.method, "http://127.0.0.1:${server.address.port}${URI(request.url).rawPath}",
                    request.headers, request.body, request.timeoutMs,
                ))
                override fun stream(request: HttpRequest): Flow<SseEvent> = error("unused")
            }
            val port = CachedWebPort(HtmlPageConverter, transport)
            val url = "https://example.com/guide/page"
            val page = port.fetch(url) as WebResponse.Page
            assertEquals(url, page.url)
            assertEquals(PageContent("Guide & examples",
                "# Guide\n\nHello **world** & 中 😀.\n\n[Next](https://example.com/next)\n"), page.content)
            assertEquals(page, port.fetch(url))
            assertEquals(1, downloads)
        } finally {
            server.stop(0)
        }
    }
}
