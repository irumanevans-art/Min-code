package dev.min.agent.kernel

import dev.min.agent.ext.workflow.WorkflowScript
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

internal data class WorkflowInput(val name: String, val source: String, val args: JsonElement?, val resume: String? = null) {
    val hash: String get() = WorkflowScript.hash(source)
}

internal class WorkflowTool(private val rt: SessionRuntime) : Tool<WorkflowInput> {
    override val name = "Workflow"
    override val traits = ToolTraits(delegates = true)
    override val inputSchema = buildJsonObject {
        put("type", "object"); put("required", JsonArray(listOf(JsonPrimitive("source"))))
        put("properties", buildJsonObject {
            put("source", buildJsonObject { put("type", "string") })
            put("args", buildJsonObject {})
            put("resume_run", buildJsonObject { put("type", "string") })
        })
    }
    override fun description(profile: HarnessProfile) = "Run an approved deterministic JavaScript workflow. First statement: export const meta = {name, description}. APIs: agent, pipeline, parallel, phase, log, args. No imports, file or shell access. Resume with the original source, args and resume_run ID."
    override fun parse(raw: JsonObject): ToolInput<WorkflowInput> = try {
        val source = raw["source"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("source must be a string")
        val script = WorkflowScript.parse(source)
        val resume = raw["resume_run"]?.jsonPrimitive?.takeIf { it.isString }?.content
        ToolInput.Valid(WorkflowInput(script.name, source, raw["args"], resume))
    } catch (e: CancellationException) { throw e }
    catch (e: IllegalArgumentException) { ToolInput.Invalid(e.message ?: "Invalid workflow.") }
    catch (e: IllegalStateException) { ToolInput.Invalid(e.message ?: "Invalid workflow.") }
    override fun permissionAsk(input: WorkflowInput, ctx: ToolContext) = PermissionAsk(name,
        "${input.name}\nSHA-256: ${input.hash}\n\n${input.source}", specifiers = listOf(input.name))
    override fun run(input: WorkflowInput, ctx: ToolContext) = flow {
        if (rt.state.value.settings.mode == dev.min.agent.protocol.PermissionMode.Auto && !rt.workflowApproved(input.hash)) {
            rt.commit(listOf(WorkflowApproved(rt.state.value.settings.cwd, input.hash)), Durability.Sync)
            rt.deps.workflowApprovals.consentAuto()
        }
        emit(ToolProgress.Done(rt.executeWorkflow(requireNotNull(rt.state.value.turn).turnId, ctx.callId, input)))
    }
}

internal fun SessionRuntime.workflowApproved(hash: String): Boolean =
    deps.workflowApprovals.contains(state.value.settings.cwd, hash) ||
        state.value.settings.mode == dev.min.agent.protocol.PermissionMode.Auto &&
        (deps.workflowApprovals.autoConsented() || deps.workflowApprovals === WorkflowApprovals.SessionOnly && state.value.workflowEvents.any { it is WorkflowApproved })

suspend fun SessionRuntime.revokeWorkflowApproval(source: String) {
    deps.workflowApprovals.revoke(state.value.settings.cwd, WorkflowScript.hash(source))
}
