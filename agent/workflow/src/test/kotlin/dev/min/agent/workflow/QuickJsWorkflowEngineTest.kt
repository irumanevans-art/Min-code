package dev.min.agent.workflow

import dev.min.agent.spi.WorkflowHost
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class QuickJsWorkflowEngineTest {
    private val engine = QuickJsWorkflowEngine()
    private val calls = CopyOnWriteArrayList<String>()
    private val progress = CopyOnWriteArrayList<String>()
    private val host = object : WorkflowHost {
        override suspend fun agent(index: Int, prompt: String, options: JsonObject): JsonElement {
            calls += "$index:$prompt"; return JsonPrimitive(prompt)
        }
        override suspend fun progress(kind: String, message: String) { progress += "$kind:$message" }
    }
    private fun script(body: String) = "export const meta = {name: 'test', description: 'Test', phases: ['Review']};\n$body"
    private fun run(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(15_000, block) }
    private suspend fun fails(body: String) {
        try { engine.evaluate(script(body), null, host); fail("Sandbox should reject: $body") }
        catch (_: com.dokar.quickjs.QuickJsException) { }
    }

    @Test fun deterministicApisCannotBeRecoveredThroughConstructors() = run {
        for (body in listOf("Date.now()", "Math.random()", "new Date()", "Date()", "new (new Date(0).constructor)()", "Object.getPrototypeOf(new Date(0)).constructor.now()", "Date = function() {}; Date.now()", "Math.random = () => 0; Math.random()")) fails(body)
        assertEquals(JsonPrimitive(0), engine.evaluate(script("return new Date(0).getTime();"), null, host))
    }
    @Test fun sandboxHasNoImportsFilesShellOrInput() = run {
        for (body in listOf("await import('std')", "import * as fs from 'fs';", "require('fs')", "std.open('/tmp/x')", "os.exec(['sh'])", "fetch('https://example.com')", "prompt('answer')")) fails(body)
        assertEquals(JsonPrimitive("undefined,undefined,undefined,undefined,undefined"), engine.evaluate(script(
            "return [typeof __workflowAgent, typeof __workflowResult, typeof call, typeof finish, typeof node].join(',');"), null, host))
    }
    @Test fun primitivesAndArgsRunInActualQuickJs() = run {
        val result = engine.evaluate(script("""
            await phase('Review'); await log(args);
            return await pipeline(['a', 'b'], item => agent(item + args, {label: item}));
        """.trimIndent()), JsonPrimitive("!"), host)
        assertEquals(JsonArray(listOf(JsonPrimitive("a!"), JsonPrimitive("b!"))), result)
        assertEquals(setOf("0:a!", "1:b!"), calls.toSet())
        assertEquals(listOf("phase:Review", "log:!"), progress)
        assertEquals(JsonPrimitive("undefined"), engine.evaluate(script("return typeof args;"), null, host))
    }
    @Test fun limitsAndPhaseNamesAreEnforced() = run {
        fails("await phase('Other')")
        fails("await pipeline(Array(4097), x => agent('x'))")
        fails("await parallel(Array(4097))")
        fails("await pipeline(Array(1001).fill('x'), x => agent(x))")
    }
    @Test fun cancellationInterruptsBusyNativeCode() = run {
        val busy = async { engine.evaluate(script("while (true) {}"), null, host) }
        delay(100)
        busy.cancelAndJoin()
        assertTrue(busy.isCancelled)
    }
    @Test fun unawaitedNodesFinishBeforeTheRunCompletes() = run {
        val delayedHost = object : WorkflowHost {
            override suspend fun agent(index: Int, prompt: String, options: JsonObject): JsonElement {
                delay(if (index == 0) 50 else 5)
                return JsonPrimitive(prompt)
            }
            override suspend fun progress(kind: String, message: String) = Unit
        }
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
            engine.evaluate(script("agent('a'); agent('b');"), null, delayedHost))
    }
}
