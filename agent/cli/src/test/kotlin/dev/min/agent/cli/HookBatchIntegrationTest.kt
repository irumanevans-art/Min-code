package dev.min.agent.cli

import dev.min.agent.host.BindMount
import dev.min.agent.host.RootfsFileSystem
import dev.min.agent.spi.Clock
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.IdGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * CLI 的 kernel -> ext 是 implementation 依赖，ext 只在运行时可见。用反射驱动真实引擎，
 * 避免为了测试修改本任务范围之外的 CLI 构建文件；不是在测试里重写脚本生成器。
 */
class HookBatchIntegrationTest {
    @Test fun `真实 shell 三处理器并行 stdin 原样送达 stdout stderr 分离 超时隔离且清理`() = runBlocking {
        val root = Files.createTempDirectory("hooks 中文 ' ")
        try {
            val work = Files.createDirectories(root.resolve("work"))
            val paths = DesktopPaths(work, root.resolve("scratch"))
            val cwd = paths.guestOfHost(work)
            val fs = RootfsFileSystem(root.resolve("rootfs"), listOf(BindMount(cwd, work)))
            val shellPath: (GuestPath) -> String = { paths.guestOfHost(fs.hostPath(it)).value }
            val exec = LocalExec({ fs.hostPath(it) }, root.resolve("logs"), GuestPath("/logs"))
            val quoted = "中文 it's \$(touch injected)"
            val commands = (0..2).map { i ->
                val receive = "cat > ${q(paths.guestOfHost(work.resolve("receipt.$i")).value)}; "
                receive + when (i) {
                    0 -> "sleep 3; printf '%s' '{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"allow\"}}'; printf '%s' \"\$HOOK_VALUE\" >&2"
                    1 -> "sleep 3; printf '%s' \"\$HOOK_VALUE\" >&2; exit 2"
                    else -> "printf 'discarded'; printf 'timeout stderr' >&2; sleep 30"
                }
            }
            val handlers = parseHandlers(commands.map { command ->
                jsonObject(mapOf("type" to "command", "command" to command))
            })
            val stdin = jsonObject(mapOf("prompt" to "中文 \"quote\" 'single'", "hook_event_name" to "PreToolUse"))
            val runner = newRunner(exec, fs, shellPath)
            val method = runner.javaClass.methods.single { it.name.startsWith("run-") }
            val started = System.nanoTime()
            val runs = invokeSuspend(method, runner, arrayOf(handlers, stdin, mapOf("HOOK_VALUE" to quoted), cwd.value,
                { h: Any -> if (get(h, "Command") == commands[2]) 2 else 15 })) as List<*>
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue("并行一批应少于串行的 8 秒，实际 $elapsed ms", elapsed < 7_000)
            assertEquals(3, runs.size)
            assertEquals(runs.toString(), 0, get(runs[0]!!, "ExitCode"))
            (0..2).forEach { i -> assertEquals(stdin.toString(), Files.readString(work.resolve("receipt.$i"))) }
            assertEquals(0, get(runs[0]!!, "ExitCode")); assertEquals(2, get(runs[1]!!, "ExitCode"))
            assertEquals(124, get(runs[2]!!, "ExitCode")); assertEquals(true, get(runs[2]!!, "TimedOut"))
            assertEquals(false, get(runs[0]!!, "FailedToStart"))
            assertTrue((get(runs[0]!!, "Stdout") as String).startsWith("{\"hookSpecificOutput\""))
            assertEquals(quoted, get(runs[0]!!, "Stderr")); assertEquals(quoted, get(runs[1]!!, "Stderr"))
            assertEquals("", get(runs[1]!!, "Stdout"))
            assertEquals("discarded", get(runs[2]!!, "Stdout")); assertEquals("timeout stderr", get(runs[2]!!, "Stderr"))
            assertFalse(Files.exists(work.resolve("injected")))
            assertFalse(Files.exists(root.resolve("rootfs/root/.min/hooks/integration")))
            assertTrue(runs.all { (get(it!!, "DurationMs") as Long) > 0 })
            assertTrue(runs.all { (get(it!!, "Notes") as List<*>).isEmpty() })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `exec args 中空格引号中文与 shell 元字符按字面传入`() = runBlocking {
        val root = Files.createTempDirectory("hooks-argv")
        try {
            val work = Files.createDirectories(root.resolve("work"))
            val paths = DesktopPaths(work, root.resolve("scratch")); val cwd = paths.guestOfHost(work)
            val fs = RootfsFileSystem(root.resolve("rootfs"), listOf(BindMount(cwd, work)))
            val exec = LocalExec({ fs.hostPath(it) }, root.resolve("logs"), GuestPath("/logs"))
            val args = listOf("sh", "-c", "printf '%s\\n' \"\$@\"; cat", "argv0", "a b", "it's 中文", "\$(touch injected)", "semi;colon")
            val handler = JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("command"),
                "args" to kotlinx.serialization.json.JsonArray(args.map { kotlinx.serialization.json.JsonPrimitive(it) })))
            val handlers = parseHandlers(listOf(handler))
            val runner = newRunner(exec, fs) { paths.guestOfHost(fs.hostPath(it)).value }
            val runs = invokeSuspend(runner.javaClass.methods.single { it.name.startsWith("run-") }, runner,
                arrayOf(handlers, jsonObject(mapOf("text" to "中文")), emptyMap<String, String>(), cwd.value,
                    { _: Any -> 10 })) as List<*>
            assertEquals(runs.toString(), 0, get(runs.single()!!, "ExitCode"))
            assertEquals("a b\nit's 中文\n\$(touch injected)\nsemi;colon\n{\"text\":\"中文\"}", get(runs.single()!!, "Stdout"))
            assertEquals("", get(runs.single()!!, "Stderr")); assertFalse(Files.exists(work.resolve("injected")))
        } finally { root.toFile().deleteRecursively() }
    }

    private fun parseHandlers(hooks: List<JsonObject>): List<*> {
        val config = JsonObject(mapOf("PreToolUse" to kotlinx.serialization.json.JsonArray(listOf(
            JsonObject(mapOf("hooks" to kotlinx.serialization.json.JsonArray(hooks)))))))
        val configSource = Class.forName("dev.min.agent.ext.config.ConfigSource").enumConstants.first { it.toString() == "User" }
        val source = Class.forName("dev.min.agent.ext.config.HookSource").constructors.single().newInstance(configSource, config)
        val setClass = Class.forName("dev.min.agent.ext.hooks.HookSet")
        val companion = setClass.getField("Companion").get(null)
        val set = companion.javaClass.getMethod("parse", List::class.java).invoke(companion, listOf(source))
        return get(set, "Handlers") as List<*>
    }

    private fun newRunner(exec: LocalExec, fs: RootfsFileSystem, shellPath: (GuestPath) -> String): Any =
        Class.forName("dev.min.agent.ext.hooks.HookBatchRunner").constructors.single { it.parameterCount == 5 }
            .newInstance(exec, fs, IdGenerator { "integration" }, shellPath, Clock { System.nanoTime() / 1_000_000 })

    private fun get(instance: Any, property: String): Any? = instance.javaClass.getMethod("get$property").invoke(instance)
    private fun q(s: String) = "'" + s.replace("'", "'\"'\"'") + "'"
    private fun jsonObject(fields: Map<String, String>) = Json.parseToJsonElement(
        JsonObject(fields.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString()) as JsonObject

    private suspend fun invokeSuspend(method: java.lang.reflect.Method, receiver: Any, args: Array<Any>): Any? =
        suspendCoroutine { continuation: Continuation<Any?> ->
            try {
                val result = method.invoke(receiver, *args, continuation)
                if (result !== COROUTINE_SUSPENDED) continuation.resume(result)
            } catch (e: InvocationTargetException) { continuation.resumeWithException(e.targetException) }
        }
}
