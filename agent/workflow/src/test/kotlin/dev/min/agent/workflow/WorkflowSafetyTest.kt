package dev.min.agent.workflow

import com.dokar.quickjs.QuickJsException
import dev.min.agent.spi.WorkflowHost
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowSafetyTest {
    private val host = object : WorkflowHost {
        override suspend fun agent(index: Int, prompt: String, options: JsonObject): JsonElement = JsonPrimitive(prompt)
        override suspend fun progress(kind: String, message: String) = Unit
    }
    private fun source(body: String) = "export const meta = {name:'safety', description:'Safety'};\n$body"
    private fun rejects(body: String) = runBlocking {
        try { QuickJsWorkflowEngine().evaluate(source(body), null, host); fail("Expected resource exception") }
        catch (e: QuickJsException) { assertTrue(e.message.orEmpty().isNotBlank()) }
    }
    @Test fun syncRecursion() = rejects("function f(){return f()+1} return f();")
    @Test fun asyncRecursion() = rejects("async function f(){return (await f())+1} return await f();")
    @Test fun jsonParseRecursion() = rejects("return JSON.parse('['.repeat(100000)+']'.repeat(100000));")
    @Test fun toStringRecursion() = rejects("const o={toString(){return String(this)}}; return String(o);")
    @Test fun proxyRecursion() = rejects("const p=new Proxy({}, {get(t,k,r){return r[k]}}); return p.x;")
    @Test fun memoryLimitIsEnforced() = rejects("const a=[]; while(true) a.push('x'.repeat(100000));")
    @Test fun idleWatchdogInterruptsNativeLoop() = runBlocking {
        val started = System.nanoTime()
        try { QuickJsWorkflowEngine(150).evaluate(source("while(true){}"), null, host); fail("Expected interrupt") }
        catch (_: com.dokar.quickjs.QuickJsInterruptedException) { }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
    }
}
