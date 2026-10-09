package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

internal data class WorkflowAnswer(val text: String, val isError: Boolean = false)

/** 节点结果先同步落盘才交回 JS，否则进程可能已经消费结果而日志里仍没有它。 */
internal class WorkflowRun(
    private val run: WorkflowRunStarted,
    previous: List<SessionEvent>,
    private val append: suspend (SessionEvent) -> Unit,
    private val invoke: suspend (Int, String, JsonObject) -> WorkflowAnswer,
) : WorkflowHost {
    private val gate = Semaphore(run.concurrency)
    private val starts = linkedMapOf<Int, WorkflowNodeStarted>()
    private val completed = linkedMapOf<Int, WorkflowNodeCompleted>()
    init {
        // 一次重跑已经写了 started 却被杀时，不能拿更早那次的 completed 冒充本次结果。
        previous.forEach { event -> when (event) {
            is WorkflowNodeStarted -> if (event.runId == run.runId) { starts[event.index] = event; completed.remove(event.index) }
            is WorkflowNodeCompleted -> if (event.runId == run.runId) completed[event.index] = event
            else -> Unit
        } }
    }
    private var replayBefore = completed.values.filter { it.isError }.minOfOrNull { it.index } ?: Int.MAX_VALUE
    private val next = MutableStateFlow(0)
    @Volatile private var appendFailure: Exception? = null
    private val progressLock = Mutex()
    private var progressCount = previous.filterIsInstance<WorkflowProgress>().count { it.runId == run.runId }
    private var progressBytes = previous.filterIsInstance<WorkflowProgress>().filter { it.runId == run.runId }.sumOf { it.message.toByteArray(Charsets.UTF_8).size.toLong() }
    private var progressDropped = previous.filterIsInstance<WorkflowProgress>().any { it.runId == run.runId && it.message == PROGRESS_DROPPED }
    private suspend fun persist(event: SessionEvent) {
        try { append(event) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // 写盘失败后释放所有按序等待的节点；它们必须看到同一失败，不能永远等一个序号。
            appendFailure = e; next.value = Int.MAX_VALUE; throw e
        }
    }

    override suspend fun agent(index: Int, prompt: String, options: JsonObject): JsonElement {
        require(index in 0 until 1000 && prompt.isNotBlank())
        require(options.keys.all { it in setOf("schema", "label", "model") }) { "Unknown agent option." }
        options["schema"]?.let { require(it is JsonObject) }
        for (key in listOf("model", "label")) options[key]?.let { require(it is JsonPrimitive && it.isString) }
        // JS 分配序号是同步的；协程启动顺序可能不同，日志判定仍按原启动顺序做。
        next.first { it >= index }
        appendFailure?.let { throw it }
        check(next.value == index) { "Duplicate workflow node." }
        val old = starts[index]
        if (old != null && (old.prompt != prompt || old.options != options)) replayBefore = minOf(replayBefore, index)
        val cached = completed[index]?.takeIf { index < replayBefore && !it.isError && old?.prompt == prompt && old.options == options }
        if (cached == null) persist(WorkflowNodeStarted(run.runId, index, prompt, options))
        else persist(WorkflowNodeReplayed(run.runId, index))
        next.value = index + 1
        if (cached != null) return cached.result
        return gate.withPermit {
            var failed = false
            val result = try {
                val schema = options["schema"] as? JsonObject
                var value: JsonElement = JsonNull
                var feedback = ""
                for (attempt in 1..if (schema == null) 1 else run.attempts) {
                    val answer = invoke(index, prompt + (schema?.let { "\n\nReturn only JSON matching this schema: $it" } ?: "") + feedback, options)
                    if (answer.isError) { failed = true; value = workflowError("agent_failed", answer.text); break }
                    if (schema == null) { value = JsonPrimitive(answer.text); break }
                    val decoded = try { Json.parseToJsonElement(answer.text.trim()) } catch (_: IllegalArgumentException) { null }
                    val errors = decoded?.let { JsonSchema.validate(schema, it) } ?: listOf("Output must be JSON.")
                    if (errors.isEmpty()) { value = requireNotNull(decoded); break }
                    if (attempt == run.attempts) { failed = true; value = workflowError("schema_retries_exhausted", errors.joinToString("\n")); break }
                    feedback = "\n\nReturn only JSON matching this schema: $schema\nPrevious output was invalid: ${errors.joinToString("; ")}"
                }
                value
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed = true; workflowError("agent_failed", e.message ?: "Agent failed.") }
            persist(WorkflowNodeCompleted(run.runId, index, result, failed))
            result
        }
    }

    override suspend fun progress(kind: String, message: String) {
        require(kind in listOf("phase", "log") && message.length <= 30_000)
        progressLock.withLock {
            val bytes = message.toByteArray(Charsets.UTF_8).size
            if (progressCount >= 4096 || progressBytes + bytes > 1024 * 1024) {
                if (!progressDropped) { progressDropped = true; persist(WorkflowProgress(run.runId, "log", PROGRESS_DROPPED)) }
            } else { persist(WorkflowProgress(run.runId, kind, message)); progressCount++; progressBytes += bytes }
        }
    }
    companion object { const val PROGRESS_DROPPED = "Workflow progress limit reached; further messages dropped." }
}

internal fun workflowError(code: String, message: String): JsonObject = buildJsonObject {
    put("error", code); put("message", message)
}

internal suspend fun SessionRuntime.executeWorkflow(turnId: TurnId, callId: CallId, input: WorkflowInput): ToolResult {
    val engine = deps.workflowEngine ?: return ToolResult(listOf(TextPart("Workflow engine is unavailable.")), true)
    val previous = state.value.workflowEvents
    val settingsEnv = deps.memoryHome?.let { dev.min.agent.ext.config.SettingsLoader.load(deps.host.fs, state.value.settings.cwd, it).merged.env }.orEmpty()
    fun setting(name: String): String? = deps.env(name) ?: settingsEnv[name]
    val run = input.resume?.let { id -> previous.filterIsInstance<WorkflowRunStarted>().lastOrNull { it.runId == id } ?: error("Unknown workflow run.") }
        ?: WorkflowRunStarted(deps.ids.next("workflow"), input.name, input.source, input.hash, input.args,
            setting("CLAUDE_CODE_WORKFLOW_MAX_CONCURRENT_AGENTS")?.toIntOrNull()?.takeIf { it in 1..256 } ?: deps.workflowConcurrency,
            setting("MAX_STRUCTURED_OUTPUT_RETRIES")?.toIntOrNull()?.takeIf { it > 0 } ?: 5, turnId = turnId)
    require(run.args == input.args) { "Resume requires the original arguments." }
    if (input.resume == null) commit(listOf(run), Durability.Sync)
    val hub = requireNotNull(hub) { "Workflow requires SessionHub." }
    val children = ConcurrentHashMap<Int, dev.min.agent.protocol.SessionId>()
    val host = WorkflowRun(run, previous, { event -> commit(listOf(event), Durability.Sync); Unit }) { index, prompt, options ->
        val result = if (children[index] == null) hub.portFor(this, turnId, CallId("${callId.value}:$index")).delegate(
            DelegateRequest(options["label"]?.jsonPrimitive?.content ?: "${input.name} #$index", prompt,
                model = options["model"]?.jsonPrimitive?.content, background = false, workflow = true)
        ) else hub.retryWorkflow(this, requireNotNull(children[index]), prompt)
        when (result) {
            is DelegateResult.Finished -> {
                children[index] = result.child
                val child = requireNotNull(hub.get(result.child))
                val answer = child.state.value.history.filterIsInstance<HistoryEntry.Assistant>().lastOrNull()
                    ?.parts?.filterIsInstance<TextPart>()?.joinToString("") { it.text }.orEmpty()
                WorkflowAnswer(if (result.isError) result.report else answer, result.isError)
            }
            is DelegateResult.Rejected -> WorkflowAnswer(result.messageForModel, true)
            is DelegateResult.Started -> WorkflowAnswer("Workflow node unexpectedly ran in background.", true)
        }
    }
    try {
        val result = engine.evaluate(input.source, run.args, host)
        commit(listOf(WorkflowRunCompleted(run.runId, result)), Durability.Sync)
        return ToolResult(listOf(TextPart(result.toString())))
    } catch (e: CancellationException) {
        // 取消也落终态，恢复只能显式针对本回合，不能被后来的无关 ▶ 捡走。
        withContext(NonCancellable) { commit(listOf(WorkflowRunCompleted(run.runId, JsonNull, "cancelled")), Durability.Sync) }
        throw e
    } catch (e: Exception) {
        try { commit(listOf(WorkflowRunCompleted(run.runId, workflowError("run_failed", e.message ?: "Workflow failed."), "failed")), Durability.Sync) }
        catch (inner: CancellationException) { throw inner }
        catch (inner: Exception) { e.addSuppressed(inner) }
        throw e
    }
}
