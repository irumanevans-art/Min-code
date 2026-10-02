package dev.min.agent.ext.hooks

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具类与回合类事件的输入键名（其余组在 HookInputsWireTest）。
 */
class HookInputsToolTurnWireTest {
    private val toolInput = buildJsonObject { put("command", "ls") }
    private val mcpServer = McpServerInfo(name = "srv", source = "plugin")

    @Test
    fun `PreToolUse input keys`() {
        assertInputKeys(
            HookEvent.PreToolUse,
            PreToolUseInput(toolName = "Bash", toolInput = toolInput, toolUseId = "call_1", mcpServer = mcpServer),
            setOf("tool_name", "tool_input", "tool_use_id", "mcp_server"),
        )
        // mcp_server 是嵌套对象 {name, source}（v2.1.274+）
        val json = hookInputJson(hookCommon(HookEvent.PreToolUse), PreToolUseInput(mcpServer = mcpServer))
        assertEquals(setOf("name", "source"), json.getValue("mcp_server").jsonObject.keys)
    }

    @Test
    fun `PermissionRequest input keys`() {
        assertInputKeys(
            HookEvent.PermissionRequest,
            PermissionRequestInput(
                toolName = "Bash",
                toolInput = toolInput,
                mcpServer = mcpServer,
                permissionSuggestions = listOf(PermissionSuggestion(type = "addRules", destination = "session")),
            ),
            setOf("tool_name", "tool_input", "mcp_server", "permission_suggestions"),
        )
        // permission_suggestions 条目只转录 type/destination（负载键文档未写）
        val json = hookInputJson(
            hookCommon(HookEvent.PermissionRequest),
            PermissionRequestInput(permissionSuggestions = listOf(PermissionSuggestion("setMode", "session"))),
        )
        assertEquals(
            setOf("type", "destination"),
            json.getValue("permission_suggestions").jsonArray[0].jsonObject.keys,
        )
    }

    @Test
    fun `PostToolUse input keys`() = assertInputKeys(
        HookEvent.PostToolUse,
        PostToolUseInput(
            toolInput = toolInput,
            toolResponse = buildJsonObject { put("stdout", "ok") },
            durationMs = 12,
        ),
        setOf("tool_input", "tool_response", "duration_ms"),
    )

    @Test
    fun `PostToolUseFailure input keys`() = assertInputKeys(
        HookEvent.PostToolUseFailure,
        PostToolUseFailureInput(
            toolName = "Bash", toolInput = toolInput, error = "boom", isInterrupt = false,
            durationMs = 5, mcpServer = mcpServer,
        ),
        setOf("tool_name", "tool_input", "error", "is_interrupt", "duration_ms", "mcp_server"),
    )

    @Test
    fun `PostToolBatch input keys`() = assertInputKeys(
        HookEvent.PostToolBatch,
        PostToolBatchInput(toolCalls = listOf(toolInput)),
        setOf("tool_calls"),
    )

    @Test
    fun `PermissionDenied input keys`() = assertInputKeys(
        HookEvent.PermissionDenied,
        PermissionDeniedInput(
            toolName = "Bash", toolInput = toolInput, toolUseId = "call_1",
            reason = "[deny.rw]", mcpServer = mcpServer,
        ),
        setOf("tool_name", "tool_input", "tool_use_id", "reason", "mcp_server"),
    )

    @Test
    fun `Stop input keys`() {
        assertInputKeys(
            HookEvent.Stop,
            StopInput(
                stopHookActive = false,
                lastAssistantMessage = "done",
                backgroundTasks = listOf(
                    BackgroundTask(
                        id = "b1", type = "shell", status = "running", description = "dev server",
                        command = "npm run dev", agentType = "tester", server = "srv", tool = "Bash", name = "n",
                    ),
                ),
                sessionCrons = listOf(SessionCron(id = "c1", schedule = "0 9 * * *", recurring = false, prompt = "run")),
            ),
            setOf("stop_hook_active", "last_assistant_message", "background_tasks", "session_crons"),
        )
        // 嵌套条目的键名也要与文档一致
        val json = hookInputJson(
            hookCommon(HookEvent.Stop),
            StopInput(
                backgroundTasks = listOf(
                    BackgroundTask(
                        id = "b1", type = "subagent", status = "running", description = "d",
                        command = "c", agentType = "a", server = "s", tool = "t", name = "n",
                    ),
                ),
                sessionCrons = listOf(SessionCron(id = "c1", schedule = "s", recurring = true, prompt = "p")),
            ),
        )
        assertEquals(
            setOf("id", "type", "status", "description", "command", "agent_type", "server", "tool", "name"),
            json.getValue("background_tasks").jsonArray[0].jsonObject.keys,
        )
        assertEquals(
            setOf("id", "schedule", "recurring", "prompt"),
            json.getValue("session_crons").jsonArray[0].jsonObject.keys,
        )
    }

    @Test
    fun `StopFailure input keys`() = assertInputKeys(
        HookEvent.StopFailure,
        StopFailureInput(
            error = "rate_limit",
            errorDetails = buildJsonObject { put("retry_after", 30) },
            lastAssistantMessage = "API Error: Rate limit reached",
        ),
        setOf("error", "error_details", "last_assistant_message"),
    )

    @Test
    fun `SubagentStart input has no specific fields`() {
        // agent_id/agent_type 都是 R1a.0 通用字段：stdin 就是通用字段本身
        assertInputKeys(HookEvent.SubagentStart, SubagentStartInput(), emptySet())
    }

    @Test
    fun `SubagentStop input keys`() = assertInputKeys(
        HookEvent.SubagentStop,
        SubagentStopInput(
            stopHookActive = false, agentTranscriptPath = "/tmp/a.jsonl", lastAssistantMessage = "done",
        ),
        setOf("stop_hook_active", "agent_transcript_path", "last_assistant_message"),
    )

    @Test
    fun `TaskCreated input keys`() = assertInputKeys(
        HookEvent.TaskCreated,
        TaskCreatedInput(
            taskId = "t1", taskSubject = "do", taskDescription = "d", teammateName = "tm", teamName = "team",
        ),
        setOf("task_id", "task_subject", "task_description", "teammate_name", "team_name"),
    )

    @Test
    fun `TaskCompleted input keys`() = assertInputKeys(
        HookEvent.TaskCompleted,
        TaskCompletedInput(
            taskId = "t1", taskSubject = "do", taskDescription = "d", teammateName = "tm", teamName = "team",
        ),
        setOf("task_id", "task_subject", "task_description", "teammate_name", "team_name"),
    )

    @Test
    fun `TeammateIdle input keys`() = assertInputKeys(
        HookEvent.TeammateIdle,
        TeammateIdleInput(teammateName = "tm", teamName = "team"),
        setOf("teammate_name", "team_name"),
    )
}
