package dev.min.agent.ext.hooks

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.HookSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun objectOf(text: String): JsonObject = HookJson.parseToJsonElement(text) as JsonObject

internal fun handler(event: HookEvent = HookEvent.PreToolUse, command: String = "hook"): HookHandler =
    HookHandler(event, null, "command", command, null, null, null, ConfigSource.User,
        JsonObject(mapOf("type" to JsonPrimitive("command"), "command" to JsonPrimitive(command))))

internal fun source(text: String, layer: ConfigSource = ConfigSource.User) = HookSource(layer, objectOf(text))

internal fun interpreted(event: HookEvent, stdout: String, code: Int = 0, stderr: String = "stderr") =
    interpret(event, HandlerRun(handler(event), code, stdout, stderr))

internal fun specific(event: HookEvent, fields: String): String =
    """{"hookSpecificOutput":{"hookEventName":"$event",$fields}}"""
