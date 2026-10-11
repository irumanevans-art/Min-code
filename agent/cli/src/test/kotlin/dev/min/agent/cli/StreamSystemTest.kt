package dev.min.agent.cli

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.MessageId
import dev.min.agent.spi.CompactionFailed
import dev.min.agent.spi.CompactionStarted
import dev.min.agent.spi.CompactionTrigger
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelFallback
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * stream-json 里 SDKMessage 的其它 `system` 子类型：`api_retry`、`status`、`compact_boundary`、`hook_started` /
 * `hook_response`、`permission_denied`、`informational`、`task_started` / `task_updated` / `task_notification` /
 * `background_tasks_changed`。字段照 cc-sdk-typescript.md（SDKStatusMessage、SDKCompactBoundaryMessage、SDKHook*、
 * SDKPermissionDeniedMessage、SDKInformationalMessage、SDKTask*、SDKBackgroundTasksChangedMessage）与 headless 页的 api_retry 表。
 * Min 没有对应事件的（plugin_install、tool_progress、thinking_tokens、files_persisted、rate_limit_event、prompt_suggestion、
 * hook_progress ……）不发；原文只列了名字没给字段的（session_state_changed、notification、memory_recall、
 * elicitation_complete、mirror_error）不编。
 */
class StreamSystemTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private fun <T> runT(block: suspend () -> T): T = runBlocking { withTimeout(300_000) { block() } }

    private val turn = TurnId("t1")
    private val request = RequestId("r1")
    private fun rows(event: dev.min.agent.spi.SessionEvent, includeHookEvents: Boolean = false): List<JsonObject> =
        systemRows(event, "sess-1", includeHookEvents) { "uuid-fixed" }

    // ---- 事件 → 行（纯函数）----

    @Test fun `a retried model failure becomes api_retry with the headless table fields`() {
        val event = ModelRequestFailed(turn, request, attempt = 2, kind = ModelFailureKind.Overloaded, message = "busy", httpStatus = 529, willRetry = true, retryDelayMs = 4000, maxRetries = 4)
        val row = rows(event).single()
        assertEquals(setOf("type", "subtype", "attempt", "max_retries", "retry_delay_ms", "error_status", "error", "uuid", "session_id"), row.keys)
        assertEquals("system", row.str("type"))
        assertEquals("api_retry", row.str("subtype"))
        assertEquals(JsonPrimitive(2), row["attempt"])
        assertEquals(JsonPrimitive(4), row["max_retries"])
        assertEquals(JsonPrimitive(4000), row["retry_delay_ms"])
        assertEquals(JsonPrimitive(529), row["error_status"])
        assertEquals("overloaded", row.str("error"))
        assertEquals("sess-1", row.str("session_id"))
    }

    @Test fun `api_retry error_status is null without an http response and the error category follows the table`() {
        fun row(kind: ModelFailureKind, status: Int? = null) =
            rows(ModelRequestFailed(turn, request, 1, kind, "m", status, true, retryDelayMs = 1000, maxRetries = 4)).single()
        assertEquals(JsonNull, row(ModelFailureKind.Network)["error_status"])
        assertEquals("unknown", row(ModelFailureKind.Network).str("error"))
        assertEquals("rate_limit", row(ModelFailureKind.RateLimited, 429).str("error"))
        assertEquals("authentication_failed", row(ModelFailureKind.Auth, 401).str("error"))
        assertEquals("server_error", row(ModelFailureKind.Server, 500).str("error"))
        assertEquals("invalid_request", row(ModelFailureKind.BadRequest, 400).str("error"))
        assertEquals("model_not_found", row(ModelFailureKind.ModelUnavailable, 404).str("error"))
    }

    @Test fun `a failure that will not be retried is not an api_retry`() {
        assertTrue(rows(ModelRequestFailed(turn, request, 5, ModelFailureKind.Overloaded, "busy", 529, willRetry = false)).isEmpty())
    }

    @Test fun `compaction gives status compacting then status null and compact_boundary`() {
        val started = rows(CompactionStarted(turn, "c1", CompactionTrigger.Auto)).single()
        assertEquals("status", started.str("subtype"))
        assertEquals("compacting", started.str("status"))
        val done = rows(ContextCompacted(turn, "c1", CompactionTrigger.Manual, "摘要", MessageId("m1"), tokensBefore = 1234))
        assertEquals(listOf("status", "compact_boundary"), done.map { it.str("subtype") })
        assertEquals(JsonNull, done[0]["status"])
        // SDKCompactBoundaryMessage：compact_metadata { trigger: manual|auto, pre_tokens }
        val boundary = done[1]
        assertEquals(setOf("type", "subtype", "compact_metadata", "uuid", "session_id"), boundary.keys)
        assertEquals("manual", boundary["compact_metadata"]!!.jsonObject.str("trigger"))
        assertEquals(JsonPrimitive(1234), boundary["compact_metadata"]!!.jsonObject["pre_tokens"])
        // 撞墙后的压缩（overflow）在 SDK 里也是 auto
        assertEquals("auto", rows(ContextCompacted(turn, "c2", CompactionTrigger.Overflow, "s", MessageId("m"))).last()["compact_metadata"]!!.jsonObject.str("trigger"))
    }

    @Test fun `a failed compaction clears the status and says why`() {
        val out = rows(CompactionFailed(turn, "c1", "摘要请求失败"))
        assertEquals(listOf("status", "informational"), out.map { it.str("subtype") })
        assertEquals(JsonNull, out[0]["status"])
        assertEquals("warning", out[1].str("level"))
        assertTrue(out[1].str("content")!!.contains("摘要请求失败"))
    }

    @Test fun `notes and fallbacks become informational banners`() {
        val note = rows(Note(NoteLevel.Info, "一条说明")).single()
        assertEquals(setOf("type", "subtype", "content", "level", "uuid", "session_id"), note.keys)
        assertEquals("informational", note.str("subtype"))
        assertEquals("一条说明", note.str("content"))
        assertEquals("info", note.str("level"))
        assertEquals("warning", rows(Note(NoteLevel.Warning, "w")).single().str("level"))
        // SDK 的 level 没有 error：错误级的说明按 warning 给，不丢
        assertEquals("warning", rows(Note(NoteLevel.Error, "e")).single().str("level"))
        val fallback = rows(ModelFallback(turn, request, ModelRef("p", "a"), ModelRef("p", "b"), ModelFailureKind.Overloaded, "busy")).single()
        assertEquals("notice", fallback.str("level"))
        assertTrue(fallback.str("content")!!.contains("a") && fallback.str("content")!!.contains("b"))
    }

    @Test fun `hooks ran gives a hook_started and hook_response pair, other events only with include-hook-events`() {
        val start = HooksRan("SessionStart", handlers = 1, decision = "neutral", durationMs = 12)
        val pair = rows(start)
        assertEquals(listOf("hook_started", "hook_response"), pair.map { it.str("subtype") })
        val started = pair[0]
        assertEquals(setOf("type", "subtype", "hook_id", "hook_name", "hook_event", "uuid", "session_id"), started.keys)
        assertEquals("SessionStart", started.str("hook_event"))
        val response = pair[1]
        assertEquals(setOf("type", "subtype", "hook_id", "hook_name", "hook_event", "output", "stdout", "stderr", "outcome", "uuid", "session_id"), response.keys)
        assertEquals(started.str("hook_id"), response.str("hook_id"))
        assertEquals("success", response.str("outcome"))
        // SessionStart 与 Setup 一直发；别的事件要 --include-hook-events
        assertTrue(rows(HooksRan("PreToolUse", 1, "allow", 5)).isEmpty())
        assertEquals(2, rows(HooksRan("PreToolUse", 1, "allow", 5), includeHookEvents = true).size)
        val failed = rows(HooksRan("Setup", 1, "neutral", 5, problems = listOf("超时")))
        assertEquals("error", failed.last().str("outcome"))
        assertTrue(failed.last().str("stderr")!!.contains("超时"))
    }

    @Test fun `events with no SDK counterpart produce nothing`() {
        assertTrue(rows(dev.min.agent.spi.SessionRenamed("x")).isEmpty())
    }

    // ---- 端到端 ----

    private val stream = listOf("--output-format", "stream-json")

    private fun runOnce(name: String, extra: List<String>, route: (String) -> String?, statusFor: (Int) -> Int? = { null }): StreamOut = runT {
        ModelServer(route).also { it.statusFor = statusFor }.use { server ->
            val live = startLive(tmp.root.toPath(), scope(), name, server, extra)
            live.finish()
        }
    }

    @Test fun `a retried request shows up as api_retry before the answer`() {
        val out = runOnce("retry", listOf("-p", "T_RETRY") + stream, { textResponse("RETRY_OK") }, { index -> if (index == 0) 529 else null })
        assertEquals(0, out.exit)
        val retry = out.system("api_retry").single()
        assertEquals(JsonPrimitive(1), retry["attempt"])
        assertEquals(JsonPrimitive(4), retry["max_retries"])
        assertEquals(JsonPrimitive(529), retry["error_status"])
        assertEquals("overloaded", retry.str("error"))
        assertTrue(retry["retry_delay_ms"]!!.jsonPrimitive.content.toLong() >= 0)
        assertTrue(out.lines.indexOf(retry) < out.lines.indexOfFirst { it.str("type") == "assistant" })
        assertEquals("RETRY_OK", out.results.single().str("result"))
    }

    @Test fun `a request that fails for good has no api_retry`() {
        val out = runOnce("no-retry", listOf("-p", "T_FAIL") + stream, { null })
        assertEquals(1, out.exit)
        assertTrue(out.system("api_retry").isEmpty())
    }

    private fun guest(p: java.nio.file.Path): String = p.toString().let { if (it.length > 2 && it[1] == ':') "/" + it[0].lowercaseChar() + it.substring(2).replace('\\', '/') else it }

    @Test fun `a denial the CLI decided without asking anyone is a permission_denied message`() {
        val target = tmp.newFolder("pd").toPath().resolve("no.txt")
        val out = runOnce("denied", listOf("-p", "T_WRITE", "--mode", "default") + stream, { last ->
            if (last.contains("T_WRITE")) toolResponse("call_pd", "Write", buildJsonObject { put("file_path", guest(target)); put("content", "x") }) else textResponse("PD_DONE")
        })
        assertFalse(Files.exists(target))
        val denied = out.system("permission_denied").single()
        // SDKPermissionDeniedMessage：tool_name / tool_use_id / message（给模型的拒绝说明）；decision_reason* 是 CC 判定组件的名字，Min 不编
        assertEquals(setOf("type", "subtype", "tool_name", "tool_use_id", "message", "uuid", "session_id"), denied.keys)
        assertEquals("Write", denied.str("tool_name"))
        assertEquals("call_pd", denied.str("tool_use_id"))
        assertTrue(denied.str("message")!!.contains("非交互"))
        // 它是 result.permission_denials 之外的实时提示：两处对得上
        assertEquals("call_pd", out.results.single()["permission_denials"]!!.jsonArray.single().jsonObject.str("tool_use_id"))
    }

    @Test fun `a foreground subagent gives task_started and task_updated around its tool result`() {
        val out = runOnce("task-fg", listOf("-p", "T_DELEGATE") + stream, { last ->
            when {
                last.contains("T_DELEGATE") -> toolResponse("a1", "Agent", buildJsonObject {
                    put("description", "去看一眼"); put("prompt", "去看看"); put("subagent_type", "general-purpose"); put("run_in_background", false)
                })
                last.contains("去看看") -> textResponse("CHILD_DONE")
                else -> textResponse("PARENT_DONE")
            }
        })
        assertEquals(0, out.exit)
        val started = out.system("task_started").single()
        // SDKTaskStartedMessage
        assertEquals(setOf("type", "subtype", "task_id", "tool_use_id", "description", "task_type", "is_backgrounded", "spawn_depth", "uuid", "session_id"), started.keys)
        assertEquals("a1", started.str("tool_use_id"))
        assertEquals("去看一眼", started.str("description"))
        assertEquals("local_agent", started.str("task_type"))
        assertEquals(JsonPrimitive(false), started["is_backgrounded"])
        assertEquals(JsonPrimitive(1), started["spawn_depth"])
        val updated = out.system("task_updated").single()
        assertEquals(setOf("type", "subtype", "task_id", "patch", "uuid", "session_id"), updated.keys)
        assertEquals(started.str("task_id"), updated.str("task_id"))
        val patch = updated["patch"]!!.jsonObject
        assertEquals("completed", patch.str("status"))
        assertTrue(patch["end_time"]!!.jsonPrimitive.content.toLong() > 0)
        val order = out.lines.map { it.str("subtype") ?: it.str("type") }
        assertTrue(order.toString(), order.indexOf("task_started") < order.indexOf("task_updated"))
        // 前台子 agent 不是后台任务：没有 task_notification，也不进 background_tasks_changed
        assertTrue(out.system("task_notification").isEmpty())
    }

    @Test fun `a background command gives task_started, background_tasks_changed, and on exit task_updated plus task_notification`() {
        // -p 单轮不等后台任务：用 stdin 输入把进程撑到通知到达（真实用法里 SDK 的输入也是开着的）
        val out = runT {
            ModelServer { last ->
                if (last.contains("T_BG")) toolResponse("bg1", "Bash", buildJsonObject {
                    put("command", "sleep 1; echo bg-done"); put("description", "后台回声"); put("run_in_background", true)
                }) else textResponse("BG_ACK")
            }.use { server ->
                val live = startLive(tmp.root.toPath(), scope(), "task-bg", server, listOf("-p", "--input-format", "stream-json") + stream)
                live.send("""{"type":"user","message":{"role":"user","content":"T_BG"}}""")
                live.awaitLine(60_000) { it.str("subtype") == "task_notification" }
                live.awaitLine { it.str("subtype") == "background_tasks_changed" && it["tasks"]!!.jsonArray.isEmpty() }
                live.finish()
            }
        }
        assertEquals(0, out.exit)
        val started = out.system("task_started").single()
        assertEquals("local_bash", started.str("task_type"))
        assertEquals(JsonPrimitive(true), started["is_backgrounded"])
        assertEquals("后台回声", started.str("description"))
        val taskId = started.str("task_id")!!
        // 全量替换式的在跑集合：先含它，最后是空的
        val changed = out.system("background_tasks_changed")
        assertTrue(changed.isNotEmpty())
        val first = changed.first()["tasks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(taskId), first.map { it.str("task_id") })
        assertEquals("local_bash", first.single().str("task_type"))
        assertEquals(JsonArray(emptyList()), changed.last()["tasks"])
        val updated = out.system("task_updated").last { it.str("task_id") == taskId }
        assertEquals("completed", updated["patch"]!!.jsonObject.str("status"))
        val notice = out.system("task_notification").single()
        assertEquals(setOf("type", "subtype", "task_id", "status", "output_file", "summary", "uuid", "session_id"), notice.keys - "tool_use_id")
        assertEquals(taskId, notice.str("task_id"))
        assertEquals("completed", notice.str("status"))
        assertTrue(notice.str("output_file")!!.isNotBlank())
        assertTrue(notice.str("summary")!!.contains("后台回声"))
    }

    @Test fun `no extra system rows without the events behind them`() {
        val out = runOnce("quiet", listOf("-p", "T_QUIET") + stream, { textResponse("QUIET_OK") })
        val subtypes = out.ofType("system").map { it.str("subtype") }
        assertEquals(listOf("init"), subtypes)
    }

    // ---- thinking_tokens / commands_changed / tool_progress / task_progress ----

    @Test fun `a thinking block gives thinking_tokens estimates and the result carries modelUsage with the catalog limits`() {
        val out = runOnce("think", listOf("--provider", "deepseek-openai", "--model", "deepseek-flash", "-p", "T_THINK") + stream, { _ ->
            response("stop", buildJsonObject {
                put("role", "assistant"); put("reasoning_content", "先想想这个问题，再回答"); put("content", "THINK_DONE")
            })
        })
        assertEquals(out.stdout + out.stderr, 0, out.exit)
        val rows = out.system("thinking_tokens")
        assertTrue("有思考块就有估算行", rows.isNotEmpty())
        rows.forEach {
            // SDKThinkingTokensMessage：estimated_tokens（本块累计）/ estimated_tokens_delta（这一帧）
            assertEquals(setOf("type", "subtype", "estimated_tokens", "estimated_tokens_delta", "uuid", "session_id"), it.keys)
            assertTrue(it["estimated_tokens_delta"]!!.jsonPrimitive.content.toInt() >= 1)
        }
        assertEquals(rows.sumOf { it["estimated_tokens_delta"]!!.jsonPrimitive.content.toInt() }, rows.last()["estimated_tokens"]!!.jsonPrimitive.content.toInt())
        // 目录里的模型：contextWindow / maxOutputTokens 取自目录，provider 带上
        val usage = out.results.single()["modelUsage"]!!.jsonObject["deepseek-flash"]!!.jsonObject
        assertEquals(JsonPrimitive(1048576), usage["contextWindow"])
        assertEquals("deepseek-openai", usage.str("provider"))
        assertTrue(out.results.single()["duration_api_ms"]!!.jsonPrimitive.content.toLong() >= 0)
    }

    @Test fun `no thinking block means no thinking_tokens`() {
        val out = runOnce("nothink", listOf("-p", "T_PLAIN") + stream, { textResponse("PLAIN") })
        assertTrue(out.system("thinking_tokens").isEmpty())
    }

    @Test fun `a skill found in a subdirectory gives commands_changed with the full updated list`() {
        // Read 的路径要写进剧本，而工作目录在 prepare 里才知道：路由闭包读它
        var notePath = ""
        val out = runT {
            ModelServer { last ->
                if (last.contains("T_READ")) toolResponse("rd1", "Read", buildJsonObject { put("file_path", notePath) }) else textResponse("READ_DONE")
            }.use { server ->
                startLive(tmp.root.toPath(), scope(), "nested", server, listOf("-p", "T_READ") + stream) { work ->
                    val note = work.resolve("sub/note.txt")
                    Files.createDirectories(note.parent)
                    Files.writeString(note, "hello")
                    val skill = work.resolve("sub/.claude/skills/nested-skill/SKILL.md")
                    Files.createDirectories(skill.parent)
                    Files.writeString(skill, "---\nname: nested-skill\ndescription: from a subdirectory\n---\nDo it.")
                    notePath = guest(note)
                }.finish()
            }
        }
        assertEquals(out.stdout + out.stderr, 0, out.exit)
        val changed = out.system("commands_changed")
        assertTrue(out.stdout, changed.isNotEmpty())
        val commands = changed.last()["commands"]!!.jsonArray.map { it.jsonObject }
        val nested = commands.single { it.str("name") == "nested-skill" }
        // SlashCommand：name / description / argumentHint
        assertEquals("from a subdirectory", nested.str("description"))
        assertTrue("argumentHint" in nested.keys)
    }

    @Test fun `a running tool gets heartbeat tool_progress rows`() {
        val saved = streamHeartbeatMs
        streamHeartbeatMs = 200
        try {
            val out = runOnce("heartbeat", listOf("-p", "T_SLEEP") + stream, { last ->
                if (last.contains("T_SLEEP")) toolResponse("sl1", "Bash", buildJsonObject { put("command", "sleep 2") }) else textResponse("SLEPT")
            })
            assertEquals(0, out.exit)
            val beats = out.ofType("tool_progress")
            assertTrue(out.stdout, beats.isNotEmpty())
            val beat = beats.first()
            // SDKToolProgressMessage：type 是顶层 tool_progress，不是 system
            assertEquals(setOf("type", "tool_use_id", "tool_name", "parent_tool_use_id", "elapsed_time_seconds", "heartbeat", "uuid", "session_id"), beat.keys)
            assertEquals("sl1", beat.str("tool_use_id"))
            assertEquals("Bash", beat.str("tool_name"))
            assertEquals(JsonNull, beat["parent_tool_use_id"])
            assertEquals(JsonPrimitive(true), beat["heartbeat"])
            assertTrue(beat["elapsed_time_seconds"]!!.jsonPrimitive.content.toDouble() >= 0.0)
            // 工具结束之后不再有心跳
            val lastBeat = out.lines.indexOfLast { it.str("type") == "tool_progress" }
            val toolResult = out.lines.indexOfFirst { it.str("type") == "user" }
            assertTrue("心跳都在工具结果之前（$lastBeat / $toolResult）", lastBeat < toolResult)
        } finally {
            streamHeartbeatMs = saved
        }
    }

    @Test fun `a subagent that uses tools reports task_progress with usage and the last tool`() {
        val out = runOnce("progress", listOf("-p", "T_DELEGATE") + stream, { last ->
            when {
                last.contains("T_DELEGATE") -> toolResponse("a1", "Agent", buildJsonObject {
                    put("description", "去看一眼"); put("prompt", "去看看"); put("subagent_type", "general-purpose"); put("run_in_background", false)
                })
                last.contains("去看看") -> toolResponse("cb1", "Bash", buildJsonObject { put("command", "echo child") })
                last.contains("child") -> textResponse("CHILD_DONE")
                else -> textResponse("PARENT_DONE")
            }
        })
        assertEquals(out.stdout + out.stderr, 0, out.exit)
        val started = out.system("task_started").single()
        val progress = out.system("task_progress")
        assertTrue(out.stdout, progress.isNotEmpty())
        val row = progress.last()
        // SDKTaskProgressMessage：task_id / tool_use_id / description / subagent_type / usage / last_tool_name；summary 要 agentProgressSummaries，Min 没有
        assertEquals(setOf("type", "subtype", "task_id", "tool_use_id", "description", "subagent_type", "usage", "last_tool_name", "uuid", "session_id"), row.keys)
        assertEquals(started.str("task_id"), row.str("task_id"))
        assertEquals("a1", row.str("tool_use_id"))
        assertEquals("general-purpose", row.str("subagent_type"))
        assertEquals("Bash", row.str("last_tool_name"))
        val usage = row["usage"]!!.jsonObject
        assertEquals(setOf("total_tokens", "tool_uses", "duration_ms"), usage.keys)
        assertEquals(JsonPrimitive(1), usage["tool_uses"])
        val order = out.lines.map { it.str("subtype") ?: it.str("type") }
        assertTrue(order.toString(), order.indexOf("task_progress") < order.indexOf("task_updated"))
    }
}
