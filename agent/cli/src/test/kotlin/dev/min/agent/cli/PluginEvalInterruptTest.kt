package dev.min.agent.cli

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class PluginEvalInterruptTest {
    @Test fun `中断保留已完成结果并退出二且不吞外部取消`(): Unit = runBlocking {
        val controller = EvalInterrupt()
        val grader = buildJsonObject { put("name", "ok"); put("type", "regex"); put("pattern", "ok") }
        val first = EvalCase("first", Path.of("."), JsonObject(emptyMap()), JsonObject(emptyMap()), "test", listOf(grader))
        val suite = EvalSuite(Path.of("."), Path.of("."), listOf(first, first.copy(name = "second")), emptyList())
        val result = runPluginEval(suite, EvalOptions(runs = 1, ablation = false, interrupt = controller), EvalKernel { case, _, _ ->
            if (case.name == "second") controller.interrupt()
            EvalEvidence("ok", "", emptyList(), emptyMap(), costUsd = 0.0)
        })
        assertEquals(2, result.exitCode)
        assertEquals("interrupted", result.json.string("partialReason"))
        assertEquals(1, result.json["cases"]!!.jsonArray.first().jsonObject["arms"]!!.jsonObject["with"]!!.jsonArray.size)
        var cancelled = false
        try { runPluginEval(suite.copy(cases = listOf(first)), EvalOptions(runs = 1, ablation = false), EvalKernel { _, _, _ -> throw CancellationException("external") }) }
        catch(e: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
    @Test fun `SIGINT handler 可恢复并把信号转换为局部中断`(): Unit = runBlocking {
        val controller = EvalInterrupt()
        controller.install().use {
            sun.misc.Signal.raise(sun.misc.Signal("INT"))
            withTimeout(5000) { while (!controller.interrupted) yield() }
        }
        assertTrue(controller.interrupted)
    }
}
