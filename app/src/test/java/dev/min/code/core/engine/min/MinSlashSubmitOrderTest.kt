package dev.min.code.core.engine.min

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.ShellCommand
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.UserInput
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * App 斜杠入口（MinCommands.expandSlash）的 UserPromptSubmit 先后：拦下时正文里的 `` !`命令` `` 不能跑、模型收不到请求（攻击用例）；
 * 放行时 hook 看到的是用户敲的原文、预执行照跑、同一条 prompt 只跑一次。
 */
class MinSlashSubmitOrderTest {
    private var kernel: TestKernel? = null
    private val ids = AtomicInteger()
    private fun cmd() = "cmd-${ids.incrementAndGet()}"

    @After fun tearDown() { kernel?.close() }

    private class RecordingExec : Exec {
        val ran = CopyOnWriteArrayList<String>()
        override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult {
            ran += command.script
            return ExecResult.Exited(0, "pre-exec-ok", false, 1)
        }
        override suspend fun kill(handle: ExecHandle) = Unit
    }

    private class RecordingHook(val reply: (HttpHookRequest) -> HttpHookResult) : HttpHookClient {
        val sent = CopyOnWriteArrayList<HttpHookRequest>()
        override suspend fun send(request: HttpHookRequest): HttpHookResult {
            sent += request
            return reply(request)
        }
    }

    private fun promptOf(request: HttpHookRequest) = Json.parseToJsonElement(request.body).jsonObject["prompt"]?.jsonPrimitive?.content

    private fun hookConfig() = JsonObject(
        mapOf(
            "UserPromptSubmit" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "hooks" to JsonArray(
                                listOf(JsonObject(mapOf("type" to JsonPrimitive("http"), "url" to JsonPrimitive("https://hooks.example/submit")))),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun files() = mutableMapOf(
        // 用户层 skill：项目层的预执行还要过工作区信任，这里测的是 hook 先后，不掺信任闸
        "/root/.claude/skills/danger/SKILL.md" to
            "---\ndescription: dangerous\nallowed-tools: [Bash]\n---\nPRE-EXEC OUTPUT: !`touch marker` ARGS=\$ARGUMENTS",
    )

    private fun session(hook: RecordingHook, exec: RecordingExec, files: MutableMap<String, String>): MinAgentSession {
        val fs = object : dev.min.agent.spi.WorkspaceFs {
            override suspend fun stat(path: dev.min.agent.spi.GuestPath): dev.min.agent.spi.FileStat? =
                files[path.value]?.let { dev.min.agent.spi.FileStat(dev.min.agent.spi.FileKind.File, it.toByteArray().size.toLong(), 0) }
                    ?: if (files.keys.any { it.startsWith(path.value.trimEnd('/') + "/") }) dev.min.agent.spi.FileStat(dev.min.agent.spi.FileKind.Directory, 0, 0) else null
            override suspend fun read(path: dev.min.agent.spi.GuestPath, offset: Long, limit: Int): ByteArray =
                files.getValue(path.value).toByteArray().drop(offset.toInt()).take(limit).toByteArray()
            override suspend fun writeAtomic(path: dev.min.agent.spi.GuestPath, bytes: ByteArray) { files[path.value] = bytes.decodeToString() }
            override suspend fun list(dir: dev.min.agent.spi.GuestPath): List<dev.min.agent.spi.DirEntry> = files.keys
                .filter { it.startsWith(dir.value.trimEnd('/') + "/") }
                .map { it.removePrefix(dir.value.trimEnd('/') + "/").substringBefore('/') }.distinct()
                .map { dev.min.agent.spi.DirEntry(it, stat(dev.min.agent.spi.GuestPath(dir.value.trimEnd('/') + "/" + it))!!.kind) }
            override fun scopeOf(path: dev.min.agent.spi.GuestPath) = dev.min.agent.spi.PathScope.Workspace
        }
        val model = ScriptedModel(answer("ok"), answer("ok2"))
        val k = TestKernel(model, hostPorts = hostPortsWith(fs, exec, hook), memoryHome = dev.min.agent.spi.GuestPath("/root")).also { kernel = it }
        val root = runBlocking {
            k.hub.create(
                dev.min.agent.spi.GuestPath("/workspace"), T_MODEL, dev.min.agent.protocol.PermissionMode.BypassPermissions,
                initial = listOf(HooksLoaded(listOf(HookSourceData("user", hookConfig())))),
            )
        }
        return MinAgentSession(root, FakeHost(k), k.scope)
    }

    @Test fun `blocking UserPromptSubmit on a slash line stops the embedded shell and never reaches the model`() {
        val hook = RecordingHook {
            HttpHookResult.Responded(200, """{"decision":"block","reason":"blocked by policy"}""")
        }
        val exec = RecordingExec()
        val s = session(hook, exec, files())
        val result = runBlocking { s.submit(SessionCommand.Send(cmd(), UserInput("/danger BLOCKME"))) }
        assertTrue("拦下应 Rejected：$result", result is CommandResult.Rejected)
        assertTrue((result as CommandResult.Rejected).message.contains("blocked by policy"))
        assertTrue("预执行不能跑：${exec.ran}", exec.ran.isEmpty())
        val events = runBlocking { kernel!!.store.open(s.runtime.sessionId)!!.read().toList().map { it.event } }
        assertTrue("模型没收到请求：输入不能进收件箱", events.none { it is InputReceived })
        val prompts = hook.sent.mapNotNull(::promptOf)
        assertEquals("同一条 prompt 只跑一次", 1, prompts.size)
        assertEquals("/danger BLOCKME", prompts.single())
        assertTrue("hooks_ran 照记", events.filterIsInstance<HooksRan>().any { it.event == "UserPromptSubmit" })
    }

    @Test fun `passing UserPromptSubmit sees the typed line once, then pre-exec runs and the model gets the expanded text`() {
        val hook = RecordingHook {
            HttpHookResult.Responded(200, """{"hookSpecificOutput":{"hookEventName":"UserPromptSubmit","additionalContext":"extra context from hook"}}""")
        }
        val exec = RecordingExec()
        val s = session(hook, exec, files())
        assertEquals(CommandResult.Accepted, runBlocking { s.submit(SessionCommand.Send(cmd(), UserInput("/danger fine"))) })
        runBlocking { s.runtime.awaitIdle() }
        assertEquals(listOf("touch marker"), exec.ran.toList())
        val received = runBlocking { kernel!!.store.open(s.runtime.sessionId)!!.read().toList().map { it.event } }
            .filterIsInstance<InputReceived>().single()
        val text = (received.parts.single() as TextPart).text
        assertTrue("模型收到展开后的文字：$text", text.contains("PRE-EXEC OUTPUT: pre-exec-ok") && text.contains("ARGS=fine"))
        assertEquals(listOf("extra context from hook"), received.submitScreened)
        val prompts = hook.sent.mapNotNull(::promptOf)
        assertEquals("同一条 prompt 只跑一次", 1, prompts.size)
        assertEquals("/danger fine", prompts.single())
        assertTrue("hook 看到的不是展开后的正文", !prompts.single().contains("PRE-EXEC"))
    }

    @Test fun `a plain message still goes through UserPromptSubmit exactly once inside the kernel`() {
        val hook = RecordingHook {
            HttpHookResult.Responded(200, """{"hookSpecificOutput":{"hookEventName":"UserPromptSubmit","additionalContext":"ctx"}}""")
        }
        val exec = RecordingExec()
        val s = session(hook, exec, files())
        assertEquals(CommandResult.Accepted, runBlocking { s.submit(SessionCommand.Send(cmd(), UserInput("hello there"))) })
        runBlocking { s.runtime.awaitIdle() }
        assertTrue("普通消息没有预执行", exec.ran.isEmpty())
        val prompts = hook.sent.mapNotNull(::promptOf)
        assertEquals(1, prompts.size)
        assertEquals("hello there", prompts.single())
        val received = runBlocking { kernel!!.store.open(s.runtime.sessionId)!!.read().toList().map { it.event } }
            .filterIsInstance<InputReceived>().single()
        assertEquals(null, received.submitScreened)
    }
}
