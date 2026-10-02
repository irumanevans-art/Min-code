package dev.min.agent.ext.hooks

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * 逐事件输入的线上键名（会话/上下文/提示/模型切换/worktree/elicitation 组；工具与回合组在
 * HookInputsToolTurnWireTest）。专属字段全部填上，断言合成后的 stdin JSON 键集合与调研文档原名逐一相符。
 */
class HookInputsWireTest {
    @Test
    fun `SessionStart input keys`() = assertInputKeys(
        HookEvent.SessionStart,
        SessionStartInput(
            source = "startup", model = "m", sessionTitle = "t", secondsSinceLastResponse = 1.5,
            contextTokens = 1_000, promptCacheLikelyExpired = false, estimatedCacheWriteUsd = 0.01,
        ),
        setOf("source", "model", "session_title", "seconds_since_last_response", "context_tokens",
            "prompt_cache_likely_expired", "estimated_cache_write_usd"),
    )

    @Test
    fun `Setup input keys`() = assertInputKeys(HookEvent.Setup, SetupInput(trigger = "init"), setOf("trigger"))

    @Test
    fun `InstructionsLoaded input keys`() = assertInputKeys(
        HookEvent.InstructionsLoaded,
        InstructionsLoadedInput(
            filePath = "/m/CLAUDE.md", memoryType = "Project", loadReason = "session_start",
            globs = listOf("*.md"), triggerFilePath = "/m/x.md", parentFilePath = "/m/p.md",
        ),
        setOf("file_path", "memory_type", "load_reason", "globs", "trigger_file_path", "parent_file_path"),
    )

    @Test
    fun `SessionEnd input keys`() =
        assertInputKeys(HookEvent.SessionEnd, SessionEndInput(reason = "clear"), setOf("reason"))

    @Test
    fun `ConfigChange input keys`() = assertInputKeys(
        HookEvent.ConfigChange,
        ConfigChangeInput(source = "user_settings", filePath = "/s.json"),
        setOf("source", "file_path"),
    )

    @Test
    fun `CwdChanged input keys`() = assertInputKeys(
        HookEvent.CwdChanged,
        CwdChangedInput(oldCwd = "/a", newCwd = "/b"),
        setOf("old_cwd", "new_cwd"),
    )

    @Test
    fun `DirectoryAdded input keys`() = assertInputKeys(
        HookEvent.DirectoryAdded,
        DirectoryAddedInput(directory = "/d", source = "slash_command"),
        setOf("directory", "source"),
    )

    @Test
    fun `FileChanged input keys`() = assertInputKeys(
        HookEvent.FileChanged,
        FileChangedInput(filePath = "/f", event = "change"),
        setOf("file_path", "event"),
    )

    @Test
    fun `PreCompact input keys`() = assertInputKeys(
        HookEvent.PreCompact,
        PreCompactInput(trigger = "manual", customInstructions = "keep short"),
        setOf("trigger", "custom_instructions"),
    )

    @Test
    fun `PostCompact input keys`() = assertInputKeys(
        HookEvent.PostCompact,
        PostCompactInput(trigger = "auto", compactSummary = "summary"),
        setOf("trigger", "compact_summary"),
    )

    @Test
    fun `UserPromptSubmit input keys`() =
        assertInputKeys(HookEvent.UserPromptSubmit, UserPromptSubmitInput(prompt = "hi"), setOf("prompt"))

    @Test
    fun `UserPromptExpansion input keys`() = assertInputKeys(
        HookEvent.UserPromptExpansion,
        UserPromptExpansionInput(
            expansionType = "slash_command", commandName = "/review", commandArgs = "all",
            commandSource = "user", prompt = "hi",
        ),
        setOf("expansion_type", "command_name", "command_args", "command_source", "prompt"),
    )

    @Test
    fun `MessageDisplay input keys`() = assertInputKeys(
        HookEvent.MessageDisplay,
        MessageDisplayInput(turnId = "turn_1", messageId = "msg_1", index = 0, final = true, delta = "hello"),
        setOf("turn_id", "message_id", "index", "final", "delta"),
    )

    @Test
    fun `Notification input keys`() = assertInputKeys(
        HookEvent.Notification,
        NotificationInput(message = "need input", title = "Min", notificationType = "permission_prompt"),
        setOf("message", "title", "notification_type"),
    )

    @Test
    fun `PreModelSwitch input keys`() = assertInputKeys(
        HookEvent.PreModelSwitch,
        PreModelSwitchInput(
            fromModel = "m1", toModel = "m2", requestedModel = "opus", source = "picker",
            contextTokens = 1_000, promptCacheWarm = true, cacheTtl = "5m",
            estimatedCacheWriteUsd = 0.01, pricing = "catalog",
        ),
        setOf("from_model", "to_model", "requested_model", "source", "context_tokens",
            "prompt_cache_warm", "cache_ttl", "estimated_cache_write_usd", "pricing"),
    )

    @Test
    fun `PostModelSwitch input keys`() = assertInputKeys(
        HookEvent.PostModelSwitch,
        PostModelSwitchInput(
            fromModel = "m1", toModel = "m2", requestedModel = "opus", source = "auto",
            contextTokens = 1_000, promptCacheWarm = true, cacheTtl = "5m",
            estimatedCacheWriteUsd = 0.01, pricing = "catalog",
        ),
        setOf("from_model", "to_model", "requested_model", "source", "context_tokens",
            "prompt_cache_warm", "cache_ttl", "estimated_cache_write_usd", "pricing"),
    )

    @Test
    fun `WorktreeCreate input keys`() =
        assertInputKeys(HookEvent.WorktreeCreate, WorktreeCreateInput(name = "bold-oak-a3f2"), setOf("name"))

    @Test
    fun `WorktreeRemove input keys`() =
        assertInputKeys(HookEvent.WorktreeRemove, WorktreeRemoveInput(worktreePath = "/wt"), setOf("worktree_path"))

    @Test
    fun `Elicitation input keys`() = assertInputKeys(
        HookEvent.Elicitation,
        ElicitationInput(
            mcpServerName = "srv", message = "fill", mode = "form", url = "https://x",
            elicitationId = "e1", requestedSchema = buildJsonObject { put("type", "object") },
        ),
        setOf("mcp_server_name", "message", "mode", "url", "elicitation_id", "requested_schema"),
    )

    @Test
    fun `ElicitationResult input keys`() = assertInputKeys(
        HookEvent.ElicitationResult,
        ElicitationResultInput(
            mcpServerName = "srv", action = "accept", mode = "form", elicitationId = "e1",
            content = buildJsonObject { put("q", "a") },
        ),
        setOf("mcp_server_name", "action", "mode", "elicitation_id", "content"),
    )
}
