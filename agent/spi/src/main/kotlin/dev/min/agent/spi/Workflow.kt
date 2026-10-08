package dev.min.agent.spi

import dev.min.agent.protocol.ClientMsgId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 原生脚本引擎属于宿主；内核只给子会话和日志端口，不向 JS 暴露宿主对象。 */
fun interface WorkflowEngine {
    suspend fun evaluate(source: String, args: JsonElement?, host: WorkflowHost): JsonElement
}

interface WorkflowHost {
    suspend fun agent(index: Int, prompt: String, options: JsonObject): JsonElement
    suspend fun progress(kind: String, message: String)
}

interface WorkflowApprovals {
    fun contains(cwd: GuestPath, hash: String): Boolean
    suspend fun approve(cwd: GuestPath, hash: String)
    object SessionOnly : WorkflowApprovals {
        override fun contains(cwd: GuestPath, hash: String) = false
        override suspend fun approve(cwd: GuestPath, hash: String) { }
    }
}

@Serializable
@SerialName("workflow_requested")
data class WorkflowRequested(val input: ClientMsgId, val name: String, val source: String, val args: JsonElement? = null) : SessionEvent

@Serializable
@SerialName("workflow_call_issued")
data class WorkflowCallIssued(val input: ClientMsgId, val turnId: dev.min.agent.protocol.TurnId,
    val messageId: dev.min.agent.protocol.MessageId, val call: dev.min.agent.protocol.ToolCallPart) : SessionEvent

@Serializable
@SerialName("workflow_approved")
data class WorkflowApproved(val cwd: GuestPath, val hash: String) : SessionEvent

@Serializable
@SerialName("workflow_run_started")
data class WorkflowRunStarted(
    val runId: String, val name: String, val source: String, val hash: String,
    val args: JsonElement? = null, val concurrency: Int = 16, val attempts: Int = 5,
) : SessionEvent

@Serializable
@SerialName("workflow_node_started")
data class WorkflowNodeStarted(val runId: String, val index: Int, val prompt: String, val options: JsonObject) : SessionEvent

@Serializable
@SerialName("workflow_node_completed")
data class WorkflowNodeCompleted(val runId: String, val index: Int, val result: JsonElement, val isError: Boolean = false) : SessionEvent

@Serializable
@SerialName("workflow_node_replayed")
data class WorkflowNodeReplayed(val runId: String, val index: Int) : SessionEvent

@Serializable
@SerialName("workflow_progress")
data class WorkflowProgress(val runId: String, val kind: String, val message: String) : SessionEvent

@Serializable
@SerialName("workflow_run_completed")
data class WorkflowRunCompleted(val runId: String, val result: JsonElement) : SessionEvent
