package dev.min.agent.ext.hooks

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class HookHttpTest {
    @Test fun `http 字段解析与缺 url 跳过`() {
        val set = HookSet.parse(listOf(source("""{"PreToolUse":[{"matcher":"Bash","hooks":[
            {"type":"http","url":"http://a/hook","timeout":30,
             "headers":{"Authorization":"Bearer ${'$'}MY_TOKEN","X-Trial":"t-${'$'}{TRIAL}"},
             "allowedEnvVars":["MY_TOKEN","TRIAL"]},
            {"type":"http"},
            {"type":"http","url":"   "},
            {"type":"http","url":"http://b/hook","headers":{"X-Bad":5}},
            {"type":"http","url":"http://c/hook","allowedEnvVars":["A",1]}
        ]}]}""")))
        assertEquals(listOf("user PreToolUse 第 1 组 第 2 项：http 处理器缺少 url，跳过",
            "user PreToolUse 第 1 组 第 3 项：http 处理器缺少 url，跳过",
            "user PreToolUse 第 1 组 第 4 项：headers.X-Bad 的值必须是字符串，跳过",
            "user PreToolUse 第 1 组 第 5 项：allowedEnvVars 必须是字符串数组，跳过"), set.notes)
        val h = set.handlers.single()
        assertEquals("http://a/hook", h.url)
        assertEquals(listOf("MY_TOKEN", "TRIAL"), h.allowedEnvVars)
        assertEquals("Bearer ${'$'}MY_TOKEN", (h.headers?.get("Authorization") as JsonPrimitive).content)
        assertEquals(30, h.timeout)
    }

    @Test fun `同事件两个不同 url 都保留 而 command 与 http 各走各的匹配`() {
        val set = HookSet.parse(listOf(source("""{"PreToolUse":[{"hooks":[
            {"type":"http","url":"http://a/one"},{"type":"http","url":"http://a/two"},{"type":"command","command":"sh x.sh"}
        ]}]}""")))
        val http = set.matchingHttp(HookEvent.PreToolUse, "Bash")
        assertEquals(listOf("http://a/one", "http://a/two"), http.handlers.map { it.url })
        assertTrue(http.notes.isEmpty())
        // matchingDetailed 的既有行为不变：http 仍按「本期不执行此 hook 类型」跳过（每个处理器一条 note）
        val command = set.matchingDetailed(HookEvent.PreToolUse, "Bash")
        assertEquals(listOf("sh x.sh"), command.handlers.map { it.command })
        assertEquals(2, command.notes.size)
        assertTrue(command.notes.all { it.contains("本期不执行此 hook 类型") })
        // 事件绑定照旧：别的事件一个都挑不出来
        assertTrue(set.matchingHttp(HookEvent.Stop, null).handlers.isEmpty())
    }

    @Test fun `异步 http 与 if 条件在 http 匹配里同样生效`() {
        val set = HookSet.parse(listOf(source("""{"PreToolUse":[{"matcher":"Bash","hooks":[
            {"type":"http","url":"http://a/one","async":true},
            {"type":"http","url":"http://a/two","if":"Bash(git *)"}
        ]}]}""")))
        // 没注入判定时 if 处理器同样跳过；async 无论如何都不跑
        val bare = set.matchingHttp(HookEvent.PreToolUse, "Bash")
        assertTrue(bare.handlers.isEmpty())
        assertTrue(bare.notes.any { it.contains("async 形态本期不执行") })
        assertTrue(bare.notes.any { it.contains("未注入 if 判定") })
        val judge: (String) -> Boolean = { it == "Bash(git *)" }
        assertEquals(listOf("http://a/two"), set.matchingHttp(HookEvent.PreToolUse, "Bash", judge).handlers.map { it.url })
        val wrong: (String) -> Boolean = { _ -> false }
        assertTrue(set.matchingHttp(HookEvent.PreToolUse, "Bash", wrong).handlers.isEmpty())
        // 跨层去重仍取高来源
        val layered = HookSet.parse(listOf(
            source("""{"PreToolUse":[{"hooks":[{"type":"http","url":"http://a/one"}]}]}""", ConfigSource.User),
            source("""{"PreToolUse":[{"hooks":[{"type":"http","url":"http://a/one"}]}]}""", ConfigSource.Project)))
        assertEquals(ConfigSource.Project, layered.matchingHttp(HookEvent.PreToolUse, null).handlers.single().source)
    }

    @Test fun `URL 闸与环境变量闸按清单整串匹配且跨层合并`() {
        val open = HttpHookPolicy(null, null)
        assertTrue(open.allowsUrl("http://any/hook")); assertTrue(open.allowsEnvVar("ANY"))
        val closed = HttpHookPolicy(listOf("http://a/hook"), listOf("MY_TOKEN"))
        assertFalse(closed.allowsUrl("http://a/hook/x")); assertTrue(closed.allowsUrl("http://a/hook"))
        assertFalse(closed.allowsEnvVar("OTHER"))
        val merged = HttpHookPolicy.merge(listOf(
            HttpHookPolicy(listOf("http://a/1"), listOf("A")),
            HttpHookPolicy(null, listOf("B")),
            HttpHookPolicy(null, null)))
        assertEquals(listOf("http://a/1"), merged.allowedUrls)
        assertEquals(listOf("A", "B"), merged.allowedEnvVars)
        assertTrue(merged.allowsEnvVar("A")); assertTrue(merged.allowsEnvVar("B")); assertFalse(merged.allowsEnvVar("C"))
        assertEquals(listOf("http://a/1", "http://a/2"), HttpHookPolicy.merge(listOf(
            HttpHookPolicy(listOf("http://a/1"), null), HttpHookPolicy(listOf("http://a/2"), null))).allowedUrls)
        assertNull(HttpHookPolicy.merge(listOf(HttpHookPolicy(null, null), HttpHookPolicy(null, null))).allowedUrls)
    }

    @Test fun `URL 模式里星号是通配 主机名大小写不敏感 空清单一个都不放`() {
        val policy = HttpHookPolicy(listOf("https://hooks.example.com/*", "http://localhost:*"), null)
        assertTrue(policy.allowsUrl("https://hooks.example.com/pre-tool?x=1"))
        assertTrue(policy.allowsUrl("https://HOOKS.Example.COM/a/b"))
        assertTrue("FQDN 末尾的点与没有等价", policy.allowsUrl("https://hooks.example.com./a"))
        assertTrue(policy.allowsUrl("http://localhost:8080/x"))
        assertFalse("协议不同", policy.allowsUrl("http://hooks.example.com/a"))
        assertFalse("别的主机", policy.allowsUrl("https://hooks.example.com.evil.net/a"))
        assertFalse("userinfo 骗主机", policy.allowsUrl("https://hooks.example.com@evil.net/a"))
        assertFalse(policy.allowsUrl("https://evil.net/https://hooks.example.com/a"))
        assertFalse("路径区分大小写的那一半仍然精确", HttpHookPolicy(listOf("https://a.example/Hook"), null).allowsUrl("https://a.example/hook"))
        assertFalse(HttpHookPolicy(emptyList(), null).allowsUrl("https://hooks.example.com/a"))
        assertFalse(HttpHookPolicy(null, emptyList()).allowsEnvVar("TOKEN"))
    }

    @Test fun `头值插值只认两道闸都放行的变量`() {
        val gate = HttpHookPolicy(null, listOf("MY_TOKEN"))
        val headers = objectOf("""{"Authorization":"Bearer ${'$'}MY_TOKEN","X-Trial":"t-${'$'}{TRIAL}","X-Plain":"v"}""")
        val out = HookHttpRunner.interpolateHeaders(headers, listOf("MY_TOKEN"), gate,
            mapOf("MY_TOKEN" to "tok", "TRIAL" to "t"))
        assertEquals(mapOf("Authorization" to "Bearer tok", "X-Trial" to "t-", "X-Plain" to "v"), out)
        // 处理器没写 allowedEnvVars：任何引用都换成空串（CC：Required for any env var interpolation to work）
        assertEquals("Bearer ", HookHttpRunner.interpolateHeaders(headers, null, gate, mapOf("MY_TOKEN" to "tok"))
            .getValue("Authorization"))
        // settings 闸没放行的变量也换空串；环境里没有的同样换空串
        assertEquals("Bearer ", HookHttpRunner.interpolateHeaders(headers, listOf("MY_TOKEN"),
            HttpHookPolicy(null, listOf("OTHER")), mapOf("MY_TOKEN" to "tok")).getValue("Authorization"))
        assertEquals("Bearer ", HookHttpRunner.interpolateHeaders(headers, listOf("MY_TOKEN"), gate, emptyMap())
            .getValue("Authorization"))
        assertTrue(HookHttpRunner.interpolateHeaders(null, listOf("A"), gate, mapOf("A" to "1")).isEmpty())
    }

    @Test fun `HTTP 事实按 CC 响应处理表翻译成退出码与通道`() {
        val r = HookHttpRunner(fake())
        fun runOf(result: HttpHookResult) = r.toRun(httpHandler(), result)
        // 2xx 空体 → exit 0 无输出；2xx JSON 对象体 → exit 0 stdout=体（原文带回车）
        assertEquals(0, runOf(HttpHookResult.Responded(204, "")).exitCode)
        assertEquals("", runOf(HttpHookResult.Responded(204, "")).stdout)
        val json = runOf(HttpHookResult.Responded(200, """{"continue":false}""" + "\n"))
        assertEquals(0, json.exitCode); assertEquals("""{"continue":false}""" + "\n", json.stdout)
        // 2xx 非 JSON 对象体 → 非阻断错误材料，文字不冒充 stdout
        val plain = runOf(HttpHookResult.Responded(200, "not json\nsecond"))
        assertEquals(1, plain.exitCode); assertEquals("响应体不是 JSON 对象：not json", plain.stderr)
        // 非 2xx → HTTP 状态加正文首行；按 CC 归为非阻断错误，不能冒充成功
        val non2xx = runOf(HttpHookResult.Responded(503, "busy\nmore"))
        assertEquals(1, non2xx.exitCode); assertEquals("HTTP 503：busy", non2xx.stderr)
        assertEquals("HTTP 500", runOf(HttpHookResult.Responded(500, "")).stderr)
        // 连不上与超时
        assertEquals("HTTP 请求失败：refused", runOf(HttpHookResult.Failed("refused")).stderr)
        val timedOut = runOf(HttpHookResult.TimedOut)
        assertEquals(124, timedOut.exitCode); assertTrue(timedOut.timedOut)
    }

    @Test fun `解读复用 command 通道 2xx JSON 决定生效 纯文本体不进上下文`() {
        val deny = interpret(HookEvent.PreToolUse, HookHttpRunner(fake()).toRun(httpHandler(),
            HttpHookResult.Responded(200, specific(HookEvent.PreToolUse,
                """"permissionDecision":"deny","permissionDecisionReason":"no rm""""))))
        assertEquals(HookDecision.Deny, deny.decision); assertEquals("no rm", deny.reason?.text)
        assertNull(deny.nonBlockingError)
        // CC：2xx 非 JSON 体与非 2xx 同样按非阻断错误处理，不把文字加进上下文
        val plain = interpret(HookEvent.UserPromptSubmit, HookHttpRunner(fake()).toRun(
            httpHandler(HookEvent.UserPromptSubmit), HttpHookResult.Responded(200, "部署提醒")))
        assertEquals(HookDecision.Neutral, plain.decision)
        assertNotNull(plain.nonBlockingError); assertTrue(plain.additionalContext.isEmpty())
        // 校验失败体同样是非阻断错误
        assertNotNull(interpret(HookEvent.PreToolUse, HookHttpRunner(fake()).toRun(httpHandler(),
            HttpHookResult.Responded(200, """{"decision":"wrong"}"""))).nonBlockingError)
        // 超时沿用设计 §5：PreToolUse 升 ask，其余中性
        assertEquals(HookDecision.Ask, interpret(HookEvent.PreToolUse, HookHttpRunner(fake()).toRun(
            httpHandler(), HttpHookResult.TimedOut)).decision)
        assertEquals(HookDecision.Neutral, interpret(HookEvent.Stop, HookHttpRunner(fake()).toRun(
            httpHandler(HookEvent.Stop), HttpHookResult.TimedOut)).decision)
        // 报错署名用 url，不再是裸的 http
        val failed = interpret(HookEvent.PreToolUse, HookHttpRunner(fake()).toRun(httpHandler(),
            HttpHookResult.Failed("refused")))
        assertEquals("http://a/hook hook error：HTTP 请求失败：refused", failed.nonBlockingError)
    }

    @Test fun `执行器并行发出请求 体原样送达 闸门拦下的不产生运行结果`() = runBlocking {
        val requests = mutableListOf<HttpHookRequest>()
        val client = HttpHookClient { request ->
            requests += request
            when (request.url) {
                "http://a/allow" -> HttpHookResult.Responded(200, specific(HookEvent.PreToolUse,
                    """"permissionDecision":"allow""""))
                "http://a/dead" -> HttpHookResult.Failed("refused")
                else -> HttpHookResult.TimedOut
            }
        }
        val stdin = objectOf("""{"tool_name":"Bash","command":"ls"}""")
        val batch = HookHttpRunner(client).run(
            listOf(httpHandler(url = "http://a/allow"), httpHandler(url = "http://a/dead"),
                httpHandler(url = "http://a/blocked")),
            stdin, mapOf("MY_TOKEN" to "tok"), HttpHookPolicy(listOf("http://a/allow", "http://a/dead"), null)) { 30 }
        assertEquals(listOf("http://a/blocked：不在 allowedHttpHookUrls，未执行"), batch.skipped)
        assertEquals(2, batch.runs.size)
        assertEquals(2, requests.size)
        // 事件 JSON 原文就是请求体；超时预算按 timeoutFor 换算成毫秒
        assertTrue(requests.all { it.body == stdin.toString() })
        assertTrue(requests.all { it.timeoutMs == 30_000L })
        val combined = combine(HookEvent.PreToolUse, batch.runs.map { interpret(HookEvent.PreToolUse, it) })
        assertEquals(HookDecision.Allow, combined.decision)
        assertEquals(listOf("http://a/dead hook error：HTTP 请求失败：refused"), combined.nonBlockingErrors)
    }

    private fun fake(): HttpHookClient = HttpHookClient { HttpHookResult.Responded(200, "") }

    private fun httpHandler(event: HookEvent = HookEvent.PreToolUse, url: String = "http://a/hook"): HookHandler =
        HookHandler(event, null, "http", null, null, null, null, ConfigSource.User,
            JsonObject(mapOf("type" to JsonPrimitive("http"), "url" to JsonPrimitive(url))), url)
}
