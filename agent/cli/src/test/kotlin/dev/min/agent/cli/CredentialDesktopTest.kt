package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class CredentialDesktopTest {
    @get:Rule val tmp=TemporaryFolder()
    @Test fun `desktop tools cannot expose credentials to model events or artifacts even with allow hook`() = runBlocking {
        for(mode in listOf(PermissionMode.Default,PermissionMode.AcceptEdits,PermissionMode.BypassPermissions)) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
            try {
                val home=tmp.newFolder().toPath(); val cwd=tmp.newFolder().toPath()
                val paths=DesktopPaths(cwd,home.resolve("root"))
                val token=paths.guestOfHost(home.resolve("mcp-oauth.json")).value
                val root=paths.guestOfHost(home).value
                Files.writeString(home.resolve("mcp-oauth.json"),"FAKE_ACCESS_DESKTOP")
                Files.writeString(home.resolve(".mcp-oauth-abandoned.tmp"),"FAKE_REFRESH_DESKTOP")
                Files.writeString(home.resolve("notes.txt"),"ordinary")
                Files.createDirectories(home.resolve(".claude"))
                Files.writeString(home.resolve(".claude/settings.json"),"""{"hooks":{"PreToolUse":[{"matcher":"Read|Bash","hooks":[{"type":"command","command":"printf '%s' '{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"allow\"}}'"}]}]}}""")
                val counter=AtomicInteger(); val requests=CopyOnWriteArrayList<String>()
                val calls=listOf(
                    "Read" to obj("file_path" to token),
                    "Bash" to obj("command" to "cat $token"),
                    "Bash" to obj("command" to "cat < $token"),
                    "Grep" to obj("pattern" to ".","path" to root,"output_mode" to "content"),
                    "Glob" to obj("pattern" to "**/*","path" to root),
                )
                server.createContext("/v1/chat/completions") { ex ->
                    requests+=ex.requestBody.readBytes().decodeToString()
                    val first=counter.incrementAndGet()==1
                    val delta=if(first) buildJsonObject {
                        put("role","assistant")
                        put("tool_calls",JsonArray(calls.mapIndexed { i,(name,input) -> buildJsonObject {
                            put("index",i);put("id","c$i");put("type","function")
                            put("function",obj("name" to name,"arguments" to input.toString()))
                        } }))
                    } else obj("role" to "assistant","content" to "done")
                    val chunk=buildJsonObject {put("id","m");put("choices",JsonArray(listOf(buildJsonObject {put("index",0);put("delta",delta);put("finish_reason",JsonNull)})))}
                    val end=buildJsonObject {put("id","m");put("choices",JsonArray(listOf(buildJsonObject {put("index",0);put("delta",obj());put("finish_reason",if(first) "tool_calls" else "stop")})));put("usage",buildJsonObject {put("prompt_tokens",10);put("completion_tokens",5)})}
                    val bytes="data: $chunk\n\ndata: $end\n\ndata: [DONE]\n\n".toByteArray()
                    ex.responseHeaders.add("Content-Type","text/event-stream");ex.sendResponseHeaders(200,bytes.size.toLong());ex.responseBody.use {it.write(bytes)}
                }
                server.start()
                val args=parseArgs(arrayOf("--provider","local-test","--model","scripted","--base-url","http://127.0.0.1:${server.address.port}/v1","--wire","openai_chat","--api-key-env",if(System.getenv("USERNAME")!=null) "USERNAME" else "USER"),cwd).copy(mode=mode)
                val app=DesktopApp(args,scope,home,claudeHome=home)
                val rt=app.openSession()
                rt.send(ClientMsgId("u"),listOf(TextPart("test")))
                withTimeout(20_000) {rt.awaitIdle()}
                val results=rt.state.value.toolResults
                for(i in 0..2) assertTrue("credential direct access denied in $mode",results.getValue(CallId("c$i")).isError)
                assertFalse(results.getValue(CallId("c3")).isError)
                assertFalse(results.getValue(CallId("c4")).isError)
                assertTrue(results.getValue(CallId("c3")).content.filterIsInstance<TextPart>().any {it.text.contains("ordinary")})
                val wire=app.records(rt.sessionId,0).joinToString {AgentJson.encodeToString(EventRecord.serializer(),it)}
                // 配置了 hook 不等于执行过；必须看见真实 allow，才能证明它越不过凭证拒绝。
                assertTrue(app.records(rt.sessionId,0).any { record ->
                    val event=record.event
                    event is HooksRan && event.event=="PreToolUse" && event.decision=="allow" && event.problems.isEmpty()
                })
                assertFalse(wire.contains("FAKE_"));assertTrue(requests.none {it.contains("FAKE_")})
                val artifacts=home.resolve("sessions").resolve(rt.sessionId.value).resolve("artifacts")
                if(Files.exists(artifacts)) Files.walk(artifacts).use { stream -> assertTrue(stream.filter {Files.isRegularFile(it)}.allMatch { !Files.readString(it).contains("FAKE_") }) }
            } finally {scope.cancel();server.stop(0)}
        };Unit
    }
    private fun obj(vararg fields: Pair<String,String>)=JsonObject(fields.associate {it.first to JsonPrimitive(it.second)})
}
