package dev.min.agent.testkit

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ArtifactSink
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStamp
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GlobQuery
import dev.min.agent.spi.GrepHit
import dev.min.agent.spi.GrepQuery
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.QuestionOutcome
import dev.min.agent.spi.Search
import dev.min.agent.spi.TaskBoard
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.UserQuestion
import dev.min.agent.spi.WorkspaceFs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.fail

/*
 * 工具测试共用的假端口（Edit 等后续工具也用这一套）：内存文件系统、脚本化的搜索与进程、可预置 lastSeen 的上下文。
 * 都只记录调用、不碰真实磁盘与进程。
 */

/** 内存文件系统。mtime 由内部时钟给：每次写入 +1，测试里想造「读后被改」就再 [put] 一次或 [touch] */
class FakeFs : WorkspaceFs {
    private class Entry(var bytes: ByteArray, var mtime: Long)

    private val files = linkedMapOf<String, Entry>()
    private val dirs = mutableSetOf("/")
    private var clock = 1_000L

    /** [scopeOf] 的前缀覆盖：最长前缀优先，都不匹配是 Workspace */
    val scopes = mutableMapOf<String, PathScope>()

    /** writeAtomic 收到的写入，按顺序 */
    val writes = mutableListOf<Pair<GuestPath, ByteArray>>()

    fun put(path: String, content: String, mtime: Long? = null) = put(path, content.encodeToByteArray(), mtime)

    fun put(path: String, bytes: ByteArray, mtime: Long? = null) {
        files[path] = Entry(bytes, mtime ?: ++clock)
        registerParents(path)
    }

    fun mkdir(path: String) {
        dirs += path
        registerParents(path)
    }

    /** 只改 mtime，模拟文件被外部动过 */
    fun touch(path: String, mtime: Long = ++clock) {
        files.getValue(path).mtime = mtime
    }

    fun text(path: String): String? = files[path]?.bytes?.decodeToString()

    /** 删掉一个文件（宿主端口里没有删除，回滚的替身要用） */
    fun delete(path: String) {
        files.remove(path)
    }

    /** 现有的全部文件路径 */
    fun paths(): Set<String> = files.keys.toSet()

    fun bytes(path: String): ByteArray? = files[path]?.bytes

    fun stamp(path: String): FileStamp {
        val e = files.getValue(path)
        return FileStamp(GuestPath(path), e.bytes.size.toLong(), e.mtime)
    }

    private fun registerParents(path: String) {
        var p = path.substringBeforeLast('/', "")
        while (p.isNotEmpty()) {
            dirs += p
            p = p.substringBeforeLast('/', "")
        }
    }

    override suspend fun stat(path: GuestPath): FileStat? {
        val e = files[path.value]
        if (e != null) return FileStat(FileKind.File, e.bytes.size.toLong(), e.mtime)
        if (path.value in dirs) return FileStat(FileKind.Directory, 0, 0)
        return null
    }

    override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray {
        val e = files[path.value] ?: throw java.io.FileNotFoundException(path.value)
        val from = offset.toInt().coerceAtMost(e.bytes.size)
        val to = if (limit >= e.bytes.size - from) e.bytes.size else from + limit
        return e.bytes.copyOfRange(from, to)
    }

    override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) {
        writes += path to bytes
        put(path.value, bytes)
    }

    override suspend fun deleteFile(path: GuestPath) { delete(path.value) }

    override suspend fun list(dir: GuestPath): List<DirEntry> {
        val prefix = dir.value.trimEnd('/') + "/"
        val names = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        return names.filter { '/' !in it }.map { DirEntry(it, FileKind.File) } +
            names.filter { '/' in it }.map { it.substringBefore('/') }.distinct().map { DirEntry(it, FileKind.Directory) }
    }

    override fun scopeOf(path: GuestPath): PathScope =
        scopes.entries.filter { path.value.startsWith(it.key) }.maxByOrNull { it.key.length }?.value ?: PathScope.Workspace
}

/** 脚本化的搜索：命中与文件列表由测试预置，查询被记下来。遵守 maxHits / maxResults，和真宿主一样会截 */
class FakeSearch : Search {
    var hits: List<GrepHit> = emptyList()
    var globbed: List<GuestPath> = emptyList()
    val grepQueries = mutableListOf<GrepQuery>()
    val globQueries = mutableListOf<GlobQuery>()

    override fun grep(query: GrepQuery): Flow<GrepHit> {
        grepQueries += query
        var matches = 0
        return hits.asFlow().takeWhile { hit ->
            if (!hit.isContext) matches++
            matches <= query.maxHits
        }
    }

    override fun glob(query: GlobQuery): Flow<GuestPath> {
        globQueries += query
        return globbed.asFlow().take(query.maxResults)
    }
}

/** 脚本化的进程：[script] 决定每条命令的结果；命令与选项被记下来 */
class FakeExec(var script: (ShellCommand, ExecOptions) -> ExecResult = { _, _ -> ExecResult.Exited(0, "", false, 1) }) : Exec {
    val runs = mutableListOf<Pair<ShellCommand, ExecOptions>>()
    val killed = mutableListOf<ExecHandle>()

    override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult {
        runs += command to options
        return script(command, options)
    }

    override suspend fun kill(handle: ExecHandle) {
        killed += handle
    }
}

/** 与真宿主一样校验名字：哪个工具或内核路径产出了不合法的附件名，用到这个替身的测试就会红 */
class FakeArtifacts : ArtifactSink {
    val files = mutableMapOf<String, ByteArray>()
    override suspend fun put(name: String, bytes: ByteArray): String {
        files[dev.min.agent.spi.ArtifactNames.validate(name)] = bytes
        return "artifacts/$name"
    }
}

/** 工具上下文。[seen] 就是内核从日志里推出来的 lastSeen；[see] 把文件当前状态记成「刚读过」 */
class TestToolContext(
    override val fs: FakeFs = FakeFs(),
    override val search: FakeSearch = FakeSearch(),
    override val exec: FakeExec = FakeExec(),
    override val cwd: GuestPath = GuestPath("/work"),
    /** 会话目录默认真实存在；想测「cwd 不存在」时传 false */
    createCwd: Boolean = true,
) : ToolContext {
    override val sessionId = SessionId("s1")
    override val callId = CallId("c1")
    override val artifacts = FakeArtifacts()
    val seen = mutableMapOf<GuestPath, FileStamp>()

    init {
        if (createCwd) fs.mkdir(cwd.value)
    }

    override fun lastSeen(path: GuestPath): FileStamp? = seen[path]

    fun see(path: String) {
        seen[GuestPath(path)] = fs.stamp(path)
    }

    override var mode: PermissionMode = PermissionMode.Default
    override var modeBeforePlan: PermissionMode? = null
    override var web: dev.min.agent.spi.WebPort = dev.min.agent.spi.WebPort.Unavailable
    override var webSearch: dev.min.agent.spi.WebSearchPort = dev.min.agent.spi.WebSearchPort.Unavailable
    override var background: dev.min.agent.spi.BackgroundPort = dev.min.agent.spi.BackgroundPort.Unavailable
    val extractions = mutableListOf<Pair<String, String>>()
    var extraction: suspend (String, String) -> ToolResult = { _, _ -> ToolResult(listOf(TextPart("提取端口未配置")), isError = true) }
    override suspend fun extract(content: String, prompt: String): ToolResult {
        extractions += content to prompt
        return extraction(content, prompt)
    }

    /** 任务清单视图：想测任务工具就预置一份 */
    override var tasks: TaskBoard = TaskBoard()

    /** 收到的提问，按顺序 */
    val asked = mutableListOf<List<UserQuestion>>()

    /** 用户怎么答。默认没人答：不该提问的工具提了问就是测试失败 */
    var answer: (List<UserQuestion>) -> QuestionOutcome = { fail("这个测试不该向用户提问：$it"); error("unreachable") }

    override suspend fun ask(questions: List<UserQuestion>): QuestionOutcome {
        asked += questions
        return answer(questions)
    }
}

fun args(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.filter { it.second != null }.associate { (k, v) ->
        k to when (v) {
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is JsonElement -> v
            else -> error("args 不支持 ${v!!::class}")
        }
    },
)

/** parse 必须通过（否则是测试写错了）后执行，返回最后的 Done 结果 */
suspend fun <I : Any> Tool<I>.call(ctx: ToolContext, vararg pairs: Pair<String, Any?>): ToolResult {
    val last = run(parsed(*pairs), ctx).last()
    if (last !is ToolProgress.Done) fail("最后一条进度应是 Done，实际：$last")
    return (last as ToolProgress.Done).result
}

/** parse 必须拒绝，返回给模型的话 */
fun <I : Any> Tool<I>.reject(vararg pairs: Pair<String, Any?>): String {
    val parsed = parse(args(*pairs))
    if (parsed !is ToolInput.Invalid) fail("parse 应拒绝，实际：$parsed")
    return (parsed as ToolInput.Invalid).messageForModel
}

fun <I : Any> Tool<I>.parsed(vararg pairs: Pair<String, Any?>): I {
    val parsed = parse(args(*pairs))
    if (parsed !is ToolInput.Valid<*>) fail("parse 应通过，实际：$parsed")
    @Suppress("UNCHECKED_CAST")
    return (parsed as ToolInput.Valid<I>).value
}

/** 结果里所有文本拼起来 */
val ToolResult.text: String get() = content.filterIsInstance<TextPart>().joinToString("\n") { it.text }

val ToolResult.images: List<ImagePart> get() = content.filterIsInstance<ImagePart>()

/** 只记录调用、按给定函数出结果的工具。权限请求不带路径与命令 */
class FakeTool(
    override val name: String,
    override val traits: ToolTraits,
    private val body: suspend (JsonObject) -> ToolResult,
) : Tool<JsonObject> {
    override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
    override fun description(profile: HarnessProfile) = "测试工具 $name"
    override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
    override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "测试工具 $name")
    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow { emit(ToolProgress.Done(body(input))) }
}

val READ_ONLY = ToolTraits(readOnly = true, concurrencySafe = true)
val WRITES = ToolTraits()
