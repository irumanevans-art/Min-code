package dev.min.code.core.claudecode

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.session.ChatItem
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/*
 * ClaudeCodeManager 特征测试的脚手架。
 *
 * 夹具是真 CLI 录下来的（tools/record_cli_frames.py），格式见那个脚本的头注释。
 * [FakeCliProcess] 扮演 CLI：Min 发来的控制请求按录到的应答回（request_id 换成 Min 的），
 * Min 发来一条用户消息就把录到的那一轮原样吐出去；轮中的权限请求 / 打断要等 Min 真的应答了才往下走。
 */

/** 一段录制拆成的样子：控制请求的应答表 + 按用户消息切开的若干轮 */
internal class CliFixture(
    /** subtype → 录到的应答信封（`response` 那一层），按出现顺序；用完了就一直用最后一个 */
    val responses: Map<String, List<JsonObject>>,
    val turns: List<List<Step>>,
    /** 关掉 stdin 之后 CLI 的退出码 */
    val exitCode: Int,
) {
    sealed interface Step {
        data class Stdout(val line: String) : Step
        data class Stderr(val line: String) : Step
        /** CLI 发了 can_use_tool，等 Min 回这个 request_id */
        data class AwaitPermission(val requestId: String) : Step
        /** 录制时这里发了 interrupt，等 Min 也发一个 */
        data object AwaitInterrupt : Step
    }

    companion object {
        fun load(name: String): CliFixture {
            val text = CliFixture::class.java.getResource("/claudecode/frames/$name.txt")
                ?.readText() ?: error("缺夹具 $name")
            val subtypeOf = HashMap<String, String>()
            val responses = LinkedHashMap<String, MutableList<JsonObject>>()
            val turns = mutableListOf<MutableList<Step>>()
            var turn: MutableList<Step>? = null
            var exitCode = 0
            for (raw in text.lines()) {
                if (raw.startsWith("# exit ")) exitCode = raw.removePrefix("# exit ").trim().toInt()
                if (raw.length < 2 || raw[1] != ' ') continue
                val body = raw.substring(2)
                when (raw[0]) {
                    '>' -> {
                        val obj = Json.parseToJsonElement(body).jsonObject
                        when (obj.str("type")) {
                            "user" -> turn = mutableListOf<Step>().also(turns::add)
                            "control_request" -> {
                                val subtype = obj["request"]!!.jsonObject.str("subtype")!!
                                subtypeOf[obj.str("request_id")!!] = subtype
                                if (subtype == "interrupt") turn?.add(Step.AwaitInterrupt)
                            }
                        }
                    }

                    '<' -> {
                        val obj = Json.parseToJsonElement(body).jsonObject
                        val type = obj.str("type")
                        if (type == "control_response") {
                            val envelope = obj["response"]!!.jsonObject
                            subtypeOf[envelope.str("request_id")]?.let { subtype ->
                                responses.getOrPut(subtype) { mutableListOf() } += envelope
                            }
                            continue
                        }
                        val current = turn ?: continue
                        current += Step.Stdout(body)
                        if (type == "control_request") {
                            current += Step.AwaitPermission(obj.str("request_id")!!)
                        }
                        if (type == "result") turn = null
                    }

                    '!' -> turn?.add(Step.Stderr(body))
                }
            }
            return CliFixture(responses, turns, exitCode)
        }
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * 按 [CliFixture] 回放的假 `claude` 进程。
 *
 * 关 stdin = 优雅退出（和真 CLI 一样，退出码取录制里的那个）；[destroy] 给 143。
 */
internal class FakeCliProcess(
    private val fixture: CliFixture,
    /** 测试自己塞的控制应答，优先于录制（没录过的 subtype、想造错误时用） */
    private val overrides: Map<String, (JsonObject) -> JsonObject> = emptyMap(),
) : Process() {
    private val stdout = LineSource()
    private val stderr = LineSource()
    private val stdin = LineSink(::onMinLine, ::onStdinClosed)
    private val exited = CountDownLatch(1)

    @Volatile
    private var exitCode: Int? = null
    private val turns = ArrayDeque(fixture.turns)
    private val used = ConcurrentHashMap<String, Int>()
    private val permissionAnswers = ConcurrentHashMap<String, CountDownLatch>()
    private val interrupts = LinkedBlockingQueue<Unit>()

    /** Min 写进 stdin 的每一行 */
    val written = CopyOnWriteArrayList<JsonObject>()

    /** 为 true 时收到用户消息先不吐帧，等 [releaseTurn] —— 用来卡在「刚发出、还没输出」 */
    @Volatile
    var holdTurns = false
    private val turnGate = LinkedBlockingQueue<Unit>()

    fun releaseTurn() = turnGate.put(Unit)

    /** 从此 stdin 写入都抛 IOException，模拟管道断掉 */
    fun breakStdin() {
        stdin.broken = true
    }

    /** 读 stdout 的一方收到 IOException，模拟流读坏了（进程仍在） */
    fun failStdout(message: String) = stdout.fail(IOException(message))

    /** 进程自己退出（不是 Min 关的） */
    fun crash(code: Int) = exit(code)

    fun emit(line: String) = stdout.push(line)

    private fun onMinLine(line: String) {
        val obj = Json.parseToJsonElement(line).jsonObject
        written += obj
        when (obj.str("type")) {
            "control_request" -> {
                val requestId = obj.str("request_id")!!
                val request = obj["request"]!!.jsonObject
                val subtype = request.str("subtype")!!
                stdout.push(controlResponse(requestId, subtype, request))
                if (subtype == "interrupt") interrupts.put(Unit)
            }

            "control_response" -> {
                val requestId = obj["response"]!!.jsonObject.str("request_id")
                permissionAnswers[requestId]?.countDown()
            }

            "user" -> {
                val steps = synchronized(turns) { turns.removeFirstOrNull() } ?: return
                Thread({ play(steps) }, "fake-cli-turn").apply { isDaemon = true }.start()
            }
        }
    }

    private fun controlResponse(requestId: String, subtype: String, request: JsonObject): String {
        val envelope = overrides[subtype]?.invoke(request) ?: run {
            val recorded = fixture.responses[subtype]
            if (recorded.isNullOrEmpty()) {
                buildJsonObject { put("subtype", "success") }
            } else {
                val i = used.merge(subtype, 1, Int::plus)!! - 1
                recorded[minOf(i, recorded.lastIndex)]
            }
        }
        val withId = JsonObject(envelope + ("request_id" to kotlinx.serialization.json.JsonPrimitive(requestId)))
        return JsonObject(
            mapOf(
                "type" to kotlinx.serialization.json.JsonPrimitive("control_response"),
                "response" to withId,
            )
        ).toString()
    }

    private fun play(steps: List<CliFixture.Step>) {
        if (holdTurns) turnGate.poll(WAIT_S, TimeUnit.SECONDS)
        for (step in steps) {
            if (exitCode != null) return
            when (step) {
                is CliFixture.Step.Stdout -> {
                    if (step.line.contains("\"can_use_tool\"")) {
                        val id = Json.parseToJsonElement(step.line).jsonObject.str("request_id")!!
                        permissionAnswers.putIfAbsent(id, CountDownLatch(1))
                    }
                    stdout.push(step.line)
                }

                is CliFixture.Step.Stderr -> stderr.push(step.line)
                is CliFixture.Step.AwaitPermission ->
                    permissionAnswers.getOrPut(step.requestId) { CountDownLatch(1) }.await(WAIT_S, TimeUnit.SECONDS)

                CliFixture.Step.AwaitInterrupt -> interrupts.poll(WAIT_S, TimeUnit.SECONDS)
            }
        }
    }

    private fun onStdinClosed() = exit(fixture.exitCode)

    @Synchronized
    private fun exit(code: Int) {
        if (exitCode != null) return
        exitCode = code
        stdout.close()
        stderr.close()
        exited.countDown()
    }

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = stderr
    override fun waitFor(): Int {
        exited.await()
        return exitCode!!
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
    override fun exitValue(): Int = exitCode ?: throw IllegalThreadStateException("still running")
    override fun destroy() = exit(143)
    override fun isAlive(): Boolean = exitCode == null
    val hasExited: Boolean get() = exitCode != null
    val exitValueOrNull: Int? get() = exitCode

    private companion object {
        const val WAIT_S = 20L
    }
}

/** 一行一行往里塞的 InputStream；[close] 之后读到 EOF，[fail] 之后读到异常 */
private class LineSource : InputStream() {
    private val queue = LinkedBlockingQueue<Any>()
    private var current: ByteArray = ByteArray(0)
    private var pos = 0
    private var ended: Any? = null

    fun push(line: String) = queue.put((line + "\n").toByteArray(Charsets.UTF_8))
    override fun close() = queue.put(EOF)
    fun fail(e: IOException) = queue.put(e)

    override fun read(): Int {
        while (pos >= current.size) {
            ended?.let { if (it is IOException) throw it else return -1 }
            when (val next = queue.take()) {
                is ByteArray -> {
                    current = next
                    pos = 0
                }

                else -> ended = next
            }
        }
        return current[pos++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val first = read()
        if (first < 0) return -1
        b[off] = first.toByte()
        var n = 1
        while (n < len && pos < current.size) b[off + n++] = current[pos++]
        return n
    }

    private companion object {
        val EOF = Any()
    }
}

/** 按行回调的 OutputStream */
private class LineSink(
    private val onLine: (String) -> Unit,
    private val onClose: () -> Unit,
) : OutputStream() {
    private val buffer = ByteArrayOutputStream()

    @Volatile
    var broken = false

    @Volatile
    private var closed = false

    @Synchronized
    override fun write(b: Int) {
        if (broken || closed) throw IOException("Broken pipe")
        if (b == '\n'.code) {
            val line = buffer.toString(Charsets.UTF_8.name())
            buffer.reset()
            if (line.isNotBlank()) onLine(line)
        } else {
            buffer.write(b)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}

/** 纯内存的 SharedPreferences，够 SessionPrefs / CostLedger / Installer 用 */
private class MemoryPrefs : SharedPreferences {
    private val map = ConcurrentHashMap<String, Any>()
    override fun getAll(): Map<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        map[key] as? Set<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = HashMap<String, Any>()
        private val removes = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { if (value == null) removes += key else puts[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { if (values == null) removes += key else puts[key] = values }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { removes += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) map.clear()
            removes.forEach(map::remove)
            map.putAll(puts)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

/** 只实现 Manager 这一路真会碰到的几样：文件目录和 SharedPreferences */
private class FakeContext(private val root: File) : ContextWrapper(null) {
    private val prefs = ConcurrentHashMap<String, MemoryPrefs>()
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { MemoryPrefs() }
}

/** 装好一个 Manager：真的 SessionPrefs / 草稿盘 / 工作区目录，假的 CLI 进程 */
internal class ManagerHarness(fixtureName: String) : AutoCloseable {
    val dir: File = Files.createTempDirectory("ccm-test").toFile()
    private val context = FakeContext(dir)
    val fixture = CliFixture.load(fixtureName)
    private val workspaceRepository = WorkspaceManager(File(context.filesDir, "workspaces")).let {
        WorkspaceRepository(it, RootfsInstaller(it))
    }
    val drafts = ComposerDraftStore(File(dir, "drafts"))

    class Launch(val options: ClaudeCodeManager.SessionOptions, val sessionId: String, val process: FakeCliProcess)

    val launches = CopyOnWriteArrayList<Launch>()

    /** 下一次启动用哪个进程；默认按夹具新造一个 */
    var nextProcess: () -> FakeCliProcess = { FakeCliProcess(fixture) }

    /** 非空时启动要等它放行，用来卡在 Starting */
    @Volatile
    var launchGate: CompletableDeferred<Unit>? = null

    val manager = ClaudeCodeManager(
        context = context,
        workspaceRepository = workspaceRepository,
        settingsStore = SettingsStore(context),
        installer = ClaudeCodeInstaller(context, workspaceRepository),
        costLedger = ClaudeCodeCostLedger(context),
        drafts = drafts,
        launcher = { options, sessionId ->
            launchGate?.await()
            val process = nextProcess()
            launches += Launch(options, sessionId, process)
            LaunchedCli(process, workspaceRepository.linuxDir(), "profile-under-test")
        },
    )

    val process: FakeCliProcess get() = launches.last().process

    suspend fun awaitState(
        what: String,
        timeoutMs: Long = 10_000,
        predicate: (ClaudeCodeManager.SessionState) -> Boolean,
    ): ClaudeCodeManager.SessionState = try {
        withTimeout(timeoutMs) { manager.state.first(predicate) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("等不到「$what」，最后的状态：\n${manager.state.value.describe()}", e)
    }

    /** 等一个和状态无关的条件（比如假进程收到了什么）。StateFlow 不变时 [awaitState] 不会重判，只能轮询 */
    suspend fun awaitCondition(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("等不到「$what」，最后的状态：\n${manager.state.value.describe()}")
            }
            kotlinx.coroutines.delay(20)
        }
    }

    /** 启动并等握手收尾（它的最后一步是 CLAUDE.md 提示，那之后才会发攥着的消息） */
    suspend fun startAndHandshake(options: ClaudeCodeManager.SessionOptions = ClaudeCodeManager.SessionOptions()) {
        manager.startSession(options)
        awaitState("握手完成") { s ->
            s.appliedModel != null && s.costText != null && s.items.any { it is ChatItem.Note && "CLAUDE.md" in it.text }
        }
    }

    /** 等一轮收尾：busy 落下且 result 的时间戳到了 */
    suspend fun awaitTurnEnd(): ClaudeCodeManager.SessionState =
        awaitState("一轮收尾") { !it.busy && it.lastTurnFinishedAt != null }

    override fun close() {
        manager.dispose()
        // dispose 是异步的：等最后一个进程被关掉再删目录
        launches.lastOrNull()?.process?.waitFor(5, TimeUnit.SECONDS)
        dir.deleteRecursively()
    }
}

/** 条目压成一行字，断言和失败信息都看这个 */
internal fun ChatItem.summary(): String = when (this) {
    is ChatItem.UserText -> "user${if (queued) "(queued)" else ""}: $text"
    is ChatItem.AssistantText -> "assistant: $text" +
        (if (durationMs != null || outputTokens != null) " [${durationMs}ms, ${outputTokens} tok]" else "")
    is ChatItem.Thinking -> "thinking: $text"
    is ChatItem.Note -> "${if (isError) "error" else "note"}: $text"
    is ChatItem.ProcessOutput -> "stderr: ${lines.joinToString(" | ")}"
    is ChatItem.ToolCall -> ("tool $name [$status${if (isError) ", error" else ""}]: ${result.orEmpty().trim()}" +
        (if (subItems.isNotEmpty()) " {${subItems.joinToString("; ") { it.summary() }}}" else "")).trimEnd()
}

internal fun ClaudeCodeManager.SessionState.describe(): String = buildString {
    appendLine("status=$status busy=$busy stopping=$stopping error=$errorMessage")
    appendLine("sessionId=$sessionId model=$model appliedModel=$appliedModel pending=${pendingPermission?.toolName}")
    items.forEach { appendLine("  " + it.summary()) }
}
