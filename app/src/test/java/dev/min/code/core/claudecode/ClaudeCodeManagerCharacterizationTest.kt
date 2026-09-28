package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClaudeCodeManager 的特征测试：**把现在的行为钉住**，给拆分（进程与 IO、control 通道）兜底。
 *
 * 喂的是真 CLI（2.1.280）录下来的帧（`src/test/resources/claudecode/frames/`，
 * 用 `tools/record_cli_frames.py` 录），断言的是 Manager 吐出来的 [ClaudeCodeManager.SessionState]
 * 和它写进 stdin 的帧。时长、token、标题这些数都来自录制，重录之后要跟着改。
 *
 * 标着「当前行为」的断言不代表那样是对的，只是拆分时不许顺手改掉 —— 要改请单独提交、连测试一起改。
 */
class ClaudeCodeManagerCharacterizationTest {

    private val claudeMdNote =
        "note: /workspace 里还没有 CLAUDE.md。它是 Claude Code 每轮都会读的项目上下文：" +
            "发送 /init 让它扫描目录生成一份，或在 /memory 里手写（命令、规范、坑，200 行以内）。"
    private val initNote = "note: 会话已建立 · claude-opus-5-5[1m] · 23 个工具"

    private fun ClaudeCodeManager.SessionState.lines() = items.map { it.summary() }

    private fun FakeCliProcess.subtypes(): List<String> = written
        .filter { it.str("type") == "control_request" }
        .map { it["request"]!!.jsonObject.str("subtype")!! }

    private fun FakeCliProcess.userTexts(): List<String> = written
        .filter { it.str("type") == "user" }
        .map { frame ->
            frame["message"]!!.jsonObject["content"]!!.let { it as kotlinx.serialization.json.JsonArray }
                .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
        }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    // ---------------------------------------------------------------- 握手

    @Test
    fun `handshake fills the model catalog, slash commands, applied settings and usage`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            val s = h.awaitState("用量读回") { it.contextTokens != null }

            assertEquals(SessionStatus.Running, s.status)
            assertFalse(s.busy)
            assertEquals(ClaudeCodePermissionMode.DEFAULT, s.permissionMode)
            assertEquals(
                listOf("default", "opus[1m]", "sonnet", "sonnet[1m]", "fable", "haiku"),
                s.availableModels.map { it.value },
            )
            assertTrue(s.slashCommands.any { it.name == "code-review" })
            assertEquals("claude-opus-5-5[1m]", s.appliedModel)
            assertEquals("low", s.appliedEffort)
            assertFalse(s.appliedUltracode)
            assertNotNull(s.contextLimit)
            assertTrue(s.costText!!.startsWith("Total cost:"))
            assertEquals("profile-under-test", s.launchedProfileId)
            // 没发过话的会话：只有那条 /init 提示，「会话已建立」要等 CLI 第一轮的 system/init
            assertEquals(listOf(claudeMdNote), s.lines())
            // 握手的前三步是串行的，顺序就是这样
            assertEquals(
                listOf("initialize", "set_max_thinking_tokens", "get_settings"),
                h.process.subtypes().take(3),
            )
            val thinking = h.process.written.first { it.str("type") == "control_request" &&
                it["request"]!!.jsonObject.str("subtype") == "set_max_thinking_tokens" }
            assertEquals("summarized", thinking["request"]!!.jsonObject.str("thinking_display"))
            assertTrue(h.process.subtypes().containsAll(listOf("get_context_usage", "get_session_cost")))
        }
    }

    // ---------------------------------------------------------------- 一轮

    @Test
    fun `plain reply streams into one assistant item stamped with duration and tokens`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            val prompt = "Reply with exactly the two letters OK and nothing else."
            h.manager.send(prompt)
            val s = h.awaitTurnEnd()

            assertEquals(
                listOf(claudeMdNote, "user: $prompt", initNote, "assistant: OK [2294ms, 4 tok]"),
                s.lines(),
            )
            assertEquals(SessionStatus.Running, s.status)
            assertFalse(s.turnProduced)
            assertEquals("", s.streamingText)
            assertEquals("", s.streamingThinking)
            assertNull(s.statusPhase)
            assertNull(s.statusDetail)
            assertNull(s.retryNotice)
            assertNull(s.pendingPermission)
            assertEquals(2294L, s.lastTurnDurationMs)
            assertEquals(4, s.turnOutputTokens)
            // system/init 带来的是 CLI 自己的会话 id 和模型
            assertEquals("423d9de2-34af-4eb3-b663-6e5153550a56", s.sessionId)
            assertEquals("claude-opus-5-5[1m]", s.model)
            assertEquals(23, s.toolCount)
            assertEquals(listOf(prompt), h.process.userTexts())
            val user = h.process.written.first { it.str("type") == "user" }
            assertEquals("human", user["origin"]!!.jsonObject.str("kind"))

            // 收尾之后：读用量 + 首轮拟名（persist=true，描述就是那句话）
            val titled = h.awaitState("拟名") { it.liveTitle != null }
            assertEquals("OK reply test", titled.liveTitle)
            val title = h.process.written.last { it.str("type") == "control_request" &&
                it["request"]!!.jsonObject.str("subtype") == "generate_session_title" }["request"]!!.jsonObject
            assertEquals(prompt, title.str("description"))
            assertEquals("true", title.str("persist"))
        }
    }

    @Test
    fun `permission request waits for an answer and allow lets the tool run`() = runBlocking<Unit> {
        ManagerHarness("bash_with_permission").use { h ->
            h.startAndHandshake()
            h.manager.send("run it")
            val asking = h.awaitState("权限请求") { it.pendingPermission != null }
            val request = asking.pendingPermission!!
            assertTrue(asking.busy)
            assertEquals("Bash", request.toolName)
            assertEquals(
                "mkdir -p /tmp/minfx && echo hello-from-min | tee /tmp/minfx/out.txt",
                request.input["command"]!!.jsonPrimitive.content,
            )
            assertEquals("toolu_01UfwD4k1txn6u1ht5Y5qdJz", request.toolUseId)
            assertEquals(5, request.suggestions.size)
            // tool_use 先到，卡片已经在转了
            assertEquals(listOf(claudeMdNote, "user: run it", initNote, "tool Bash [Running]:"), asking.lines())

            assertTrue(h.manager.answerPermission(allow = true))
            val s = h.awaitTurnEnd()
            assertEquals(
                listOf(
                    claudeMdNote, "user: run it", initNote,
                    "tool Bash [Done]: hello-from-min",
                    "assistant: done [4293ms, 116 tok]",
                ),
                s.lines(),
            )
            val answer = h.process.written.single { it.str("type") == "control_response" }["response"]!!.jsonObject
            assertEquals("success", answer.str("subtype"))
            assertEquals(request.requestId, answer.str("request_id"))
            // 只批准这一次：不带 updatedPermissions，也不回显 updatedInput
            assertEquals(buildJsonObject { put("behavior", "allow") }, answer["response"])
            // 同一条不能应答两次
            assertFalse(h.manager.answerPermission(allow = true))
        }
    }

    /**
     * 点「拒绝」= 停下来等人（桌面端的 No）：应答带 interrupt，CLI 以 error_during_execution 收尾
     * （bash_denied_interrupt 的后半段是真 CLI 2.1.283 录的）。这一轮不能报成「任务失败」，
     * permission_denials 里那条也不能说成「被权限规则拦截」—— 是人拒的。
     */
    @Test
    fun `denying a permission stops the turn quietly instead of letting the model retry`() = runBlocking<Unit> {
        ManagerHarness("bash_denied_interrupt").use { h ->
            h.startAndHandshake()
            h.manager.send("run it")
            val request = h.awaitState("权限请求") { it.pendingPermission != null }.pendingPermission!!

            assertTrue(h.manager.answerPermission(allow = false))
            val s = h.awaitTurnEnd()
            assertFalse(s.busy)
            val answer = h.process.written.single { it.str("type") == "control_response" }["response"]!!.jsonObject
            assertEquals(request.requestId, answer.str("request_id"))
            assertEquals(
                buildJsonObject {
                    put("behavior", "deny")
                    put("message", "User denied")
                    put("interrupt", true)
                },
                answer["response"],
            )
            val notes = s.items.filterIsInstance<ChatItem.Note>().map { it.text }
            assertTrue(notes.toString(), notes.none { "任务失败" in it || "权限规则" in it })
        }
    }

    @Test
    fun `answering a different request id than the pending one is refused`() = runBlocking<Unit> {
        ManagerHarness("bash_with_permission").use { h ->
            h.startAndHandshake()
            h.manager.send("run it")
            h.awaitState("权限请求") { it.pendingPermission != null }
            assertFalse(h.manager.answerPermission(allow = true, expectedRequestId = "someone-else"))
            assertNotNull(h.manager.state.value.pendingPermission)
            assertTrue(h.process.written.none { it.str("type") == "control_response" })
            h.manager.answerPermission(allow = false, denyMessage = "no")
            h.awaitTurnEnd()
        }
    }

    @Test
    fun `subagent run lands on the Agent card and its task entry is cleared at the end`() = runBlocking<Unit> {
        ManagerHarness("subagent").use { h ->
            h.startAndHandshake()
            h.manager.send("go")
            val s = h.awaitTurnEnd()
            val lines = s.lines()
            assertEquals(6, lines.size)
            assertEquals(listOf(claudeMdNote, "user: go", initNote), lines.take(3))
            // 2.1.280 不再逐帧转发子 agent 的回复，只转发它收到的提示（不进子条目）；
            // 结果在 tool_result 里整段交回
            val agent = s.items[3] as ChatItem.ToolCall
            assertEquals("Agent", agent.name)
            assertEquals(ChatItem.ToolCall.Status.Done, agent.status)
            assertTrue(agent.result!!.contains("The report follows:\n  4\n"))
            assertTrue(agent.subItems.isEmpty())
            assertEquals("note: 子任务完成：4", lines[4])
            assertEquals("assistant: The subagent returned \"4\". [6192ms, 153 tok]", lines[5])
            // 成功收尾的子任务不占位
            assertTrue(s.tasks.isEmpty())
        }
    }

    /**
     * parallel_subagents.ndjson（真机 2.1.283，tools/probe_frames.py 录制）钉下来的行为：
     * 主回合的 result 落了以后，两个后台 agent 相继完成，CLI **自己**又开了两轮 ——
     * 每轮以 result 收尾，中间没有任何用户消息。这不同于「交棒的消息被 CLI 当成新的一问」，
     * 是 CLI 纯自发发起的；对用户来说它们就是正在进行的对话，busy 必须亮着，
     * 否则输入坞显示成空闲、停止键也点不到。
     *
     * 夹具用 `# turn` / `# pause` 观察点停在关键位置（机制见 ClaudeCodeManagerHarness），
     * 测试逐点放行，把「第一条 result 之后」每个位置的 busy 采下来再统一断言期望行为。
     */
    @Test
    fun `CLI-initiated turns after background agents complete light busy up and land their text`() = runBlocking<Unit> {
        ManagerHarness("background_agents_followup").use { h ->
            // 探针只录到 initialize / set_max_thinking_tokens 的应答；get_settings / get_session_cost
            // 给最小应答，凑齐 startAndHandshake 的等待条件（applied.model / cost 文本）
            h.nextProcess = {
                FakeCliProcess(
                    h.fixture,
                    overrides = mapOf(
                        // override 顶掉的是整个 response 信封层：subtype 要自己带，
                        // 载荷放内层 response（ControlOk 读的就是 response.response）
                        "get_settings" to { _ ->
                            buildJsonObject {
                                put("subtype", "success")
                                put("response", buildJsonObject {
                                    put("applied", buildJsonObject {
                                        put("model", "claude-haiku-4-5-20251001")
                                        put("effort", "low")
                                        put("ultracode", false)
                                    })
                                })
                            }
                        },
                        "get_session_cost" to { _ ->
                            buildJsonObject {
                                put("subtype", "success")
                                put("response", buildJsonObject { put("text", "Total cost: $0.06") })
                            }
                        },
                    ),
                )
            }
            h.startAndHandshake()
            h.manager.send(
                "In ONE assistant message, make TWO parallel Agent tool calls (the subagent launcher, " +
                    "formerly called Task) with subagent_type 'general-purpose': the first subagent reads " +
                    "only file1.txt and summarizes it in one sentence; the second reads only file2.txt and " +
                    "summarizes it in one sentence. Wait for both, then reply DONE.",
            )
            val firstEnd = h.awaitTurnEnd()
            val firstFinishedAt = firstEnd.lastTurnFinishedAt

            // # pause t2：result #1 之后、自开轮第一帧之前
            val afterResult1 = h.manager.state.value.busy
            h.process.releasePause("t2")
            h.awaitState("自开轮 init+status") { it.statusPhase != null }
            val afterInit2 = h.manager.state.value.busy
            h.process.releasePause("t2init")
            // 自开轮的正文已进 items、result #2 还压在 # pause mid2 后面 —— 流式输出期间
            h.awaitState("第二条正文") { st ->
                st.items.any { it is ChatItem.AssistantText && "First agent completed" in it.text }
            }
            val duringTurn2 = h.manager.state.value.busy

            h.process.releasePause("mid2")
            // 自开轮的 init 会把 lastTurnFinishedAt 清成 null，只看「变了」会在 result 之前就放行
            val secondFinishedAt = h.awaitState("result #2") {
                !it.busy && it.lastTurnFinishedAt != null && it.lastTurnFinishedAt != firstFinishedAt
            }.lastTurnFinishedAt
            val afterResult2 = h.manager.state.value.busy

            h.process.releasePause("t3")
            h.awaitState("自开轮 2 init+status") { it.statusPhase != null }
            val afterInit3 = h.manager.state.value.busy
            h.process.releasePause("t3init")
            h.awaitState("第三条正文") { st ->
                st.items.any { it is ChatItem.AssistantText && "Both agents completed" in it.text }
            }
            val duringTurn3 = h.manager.state.value.busy

            h.process.releasePause("mid3")
            val s = h.awaitState("result #3") {
                !it.busy && it.lastTurnFinishedAt != null && it.lastTurnFinishedAt != secondFinishedAt
            }

            // —— 失败信息里带全部采样点 ——
            val busyTrace = "afterResult1=$afterResult1 afterInit2=$afterInit2 duringTurn2=$duringTurn2 " +
                "afterResult2=$afterResult2 afterInit3=$afterInit3 duringTurn3=$duringTurn3"
            // a. CLI 自开的回合流式输出期间 busy 必须亮着
            assertTrue("CLI 自开回合流式输出期间 busy 应为 true（$busyTrace）", duringTurn2 && duringTurn3)
            // init 一到就亮（CLI 开轮的标志），两轮之间照常落下
            assertTrue("自开轮 init 之后 busy 应为 true（$busyTrace）", afterInit2 && afterInit3)
            assertFalse("两轮之间 busy 应为 false（$busyTrace）", afterResult1 || afterResult2)
            // b. 最后一条 result 之后 busy 落下
            assertFalse("最后一条 result 之后 busy 应为 false（$busyTrace）", s.busy)
            // c. 两个后续回合的助手正文都进了 items
            assertTrue(s.items.any { it is ChatItem.AssistantText && "First agent completed" in it.text })
            assertTrue(s.items.any { it is ChatItem.AssistantText && "Both agents completed" in it.text })
        }
    }

    @Test
    fun `unknown model - the CLI explains it in a synthetic reply and Min marks the turn failed once`() = runBlocking<Unit> {        ManagerHarness("model_not_found").use { h ->
            h.startAndHandshake()
            h.manager.send("Say hi.")
            val s = h.awaitTurnEnd()
            val explanation = "There's an issue with the selected model (claude-nonexistent-9). " +
                "It may not exist or you may not have access to it. Run --model to pick a different model."
            // 原因只在 CLI 的合成回复里说一遍，红字不再重复那句话
            assertEquals(
                listOf(
                    claudeMdNote, "user: Say hi.",
                    "note: 会话已建立 · claude-nonexistent-9 · 23 个工具",
                    "assistant: $explanation [781ms, null tok]",
                    "error: 任务失败（原因见上）",
                ),
                s.items.filterNot { it is ChatItem.ProcessOutput }.map { it.summary() },
            )
            // stderr 那一行进对话流，但不算错误（不点亮红字）
            val stderr = h.awaitState("stderr 入流") { st -> st.items.any { it is ChatItem.ProcessOutput } }
                .items.filterIsInstance<ChatItem.ProcessOutput>().single()
            assertEquals(listOf("[claude-code:unrecognized_model] {\"model\":\"claude-nonexistent-9\",\"query_source\":\"sdk\"}"), stderr.lines)
            assertNull(h.manager.state.value.errorMessage)
            assertEquals("claude-nonexistent-9", s.model)
        }
    }

    // ---------------------------------------------------------------- 停止键（Esc）

    @Test
    fun `interrupt mid-turn keeps what was streamed and ends the turn`() = runBlocking<Unit> {
        ManagerHarness("interrupt_midturn").use { h ->
            h.startAndHandshake()
            h.manager.send("count")
            h.awaitState("开始流") { it.streamingText.isNotEmpty() && it.turnProduced }
            h.manager.interrupt()
            val s = h.awaitTurnEnd()
            // 打断之后 CLI 回的是 is_error 的 result（error_during_execution）；是用户自己按的，不标红
            assertEquals(
                listOf(claudeMdNote, "user: count", initNote, "assistant: 1\n2\n3\n4 [2468ms, null tok]",
                    "note: 已中断"),
                s.lines(),
            )
            val interrupt = h.process.written.single { it.str("type") == "control_request" &&
                it["request"]!!.jsonObject.str("subtype") == "interrupt" }["request"]!!.jsonObject
            assertEquals("true", interrupt.str("cancel_queued"))
        }
    }

    @Test
    fun `interrupt while a permission is pending lets the CLI cancel it and end the turn`() = runBlocking<Unit> {
        ManagerHarness("interrupt_during_permission").use { h ->
            h.startAndHandshake()
            h.manager.send("Run this exact Bash command and nothing else: ls / . Then reply with one word: done.")
            h.awaitState("权限请求") { it.pendingPermission != null }
            h.manager.interrupt()
            val s = h.awaitTurnEnd()
            // CLI 先发 control_cancel_request 撤销这条请求、自己把工具判成被拒，再发 result；
            // 面板在撤回帧到时就收掉了。是用户自己按的停止，不补「CLI 撤回了……」那句（「已中断」已经说了）。
            // 托管等结论期间按停止，走的也是这一路
            assertNull(s.pendingPermission)
            assertEquals(
                listOf(
                    "tool Bash [Error, error]: The user doesn't want to proceed with this tool use. " +
                        "The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). " +
                        "STOP what you are doing and wait for the user to tell you how to proceed.",
                    "note: 已中断",
                    "note: 本轮有 1 次工具调用被权限规则拦截：Bash",
                ),
                s.lines().takeLast(3),
            )
            // 权限请求本身 Min 一个字都没回
            assertTrue(h.process.written.none { it.str("type") == "control_response" })
        }
    }

    @Test
    fun `interrupt before any output withdraws the message back to the composer`() = runBlocking<Unit> {
        ManagerHarness("interrupt_before_output").use { h ->
            h.startAndHandshake()
            // UNDISPATCHED：first() 当场挂上订阅，不然退还会走落盘那一支
            val withdrawn = async(start = CoroutineStart.UNDISPATCHED) { h.manager.withdrawnMessages.first() }
            h.manager.send("Write a haiku about the sea.")
            h.awaitState("busy") { it.busy }
            h.manager.interrupt()
            assertEquals("Write a haiku about the sea.", withdrawn.await().text)
            val s = h.awaitTurnEnd()
            // 用户那条被摘掉了，随后那个 is_error 的 result 也不留痕迹
            assertEquals(listOf(claudeMdNote, initNote), s.lines())
        }
    }

    @Test
    fun `a withdrawn message with nobody listening is saved as the session draft`() = runBlocking<Unit> {
        ManagerHarness("interrupt_before_output").use { h ->
            h.startAndHandshake()
            val sessionId = h.manager.state.value.sessionId!!
            h.manager.send("Write a haiku about the sea.")
            h.awaitState("busy") { it.busy }
            h.manager.interrupt()
            h.awaitTurnEnd()
            assertEquals("Write a haiku about the sea.", h.drafts.load(sessionId).text)
        }
    }

    // ---------------------------------------------------------------- 排队

    @Test
    fun `a message typed while starting is held and sent after the handshake`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            val gate = CompletableDeferred<Unit>()
            h.launchGate = gate
            h.manager.startSession()
            h.awaitState("Starting") { it.status == SessionStatus.Starting }
            h.manager.send("hello early")
            assertEquals(listOf("user(queued): hello early"), h.manager.state.value.lines())
            gate.complete(Unit)
            val s = h.awaitTurnEnd()
            // 已有用户消息，所以不提 /init
            assertEquals(listOf("user: hello early", initNote, "assistant: OK [2294ms, 4 tok]"), s.lines())
            assertEquals(listOf("hello early"), h.process.userTexts())
            // 握手（含 set_max_thinking_tokens / get_settings）先走完，消息才发出去
            val firstUser = h.process.written.indexOfFirst { it.str("type") == "user" }
            val getSettings = h.process.written.indexOfFirst { it.str("type") == "control_request" &&
                it["request"]!!.jsonObject.str("subtype") == "get_settings" }
            assertTrue(getSettings in 0 until firstUser)
        }
    }

    @Test
    fun `a message sent while busy is handed off at once and confirmed by the next requesting status`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.holdTurns = true
            h.manager.send("first")
            h.awaitState("busy") { it.busy }
            h.manager.send("second")
            assertEquals(listOf(claudeMdNote, "user: first", "user(queued): second"), h.manager.state.value.lines())
            // 追加的那条不等本轮结束，立刻写进 stdin
            h.awaitCondition("交棒") { h.process.userTexts() == listOf("first", "second") }
            h.process.releaseTurn()
            val s = h.awaitTurnEnd()
            assertEquals(
                listOf(claudeMdNote, "user: first", "user: second", initNote, "assistant: OK [2294ms, 4 tok]"),
                s.lines(),
            )
        }
    }

    // ---------------------------------------------------------------- 进程生命周期

    @Test
    fun `stopping an idle session closes stdin, lets the CLI exit and reports no error`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.manager.stopSession()
            assertTrue(h.manager.state.value.stopping)
            val s = h.awaitState("关闭") { it.status == SessionStatus.Closed && !it.stopping }
            assertNull(s.errorMessage)
            assertFalse(s.busy)
            assertTrue(h.process.hasExited)
            // 是关 stdin 让它自己退的，不是杀的
            assertEquals(0, h.process.exitValueOrNull)
        }
    }

    @Test
    fun `stopping right after sending closes the session without an error`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.holdTurns = true
            h.manager.send("hello")
            h.awaitState("busy") { it.busy }
            h.manager.stopSession()
            val s = h.awaitState("关闭") { it.status == SessionStatus.Closed && !it.stopping }
            assertNull(s.errorMessage)
            assertFalse(s.busy)
            assertNull(s.pendingPermission)
            // 已经写出去的消息留在对话流里，不退回输入框
            assertEquals(listOf(claudeMdNote, "user: hello"), s.lines())
            assertTrue(h.process.hasExited)
        }
    }

    @Test
    fun `stopping after an interrupted turn does not report the CLI's exit code 1`() = runBlocking<Unit> {
        // 录制里看到的：打断过的会话，关 stdin 之后 CLI 以 1 退出
        ManagerHarness("interrupt_midturn").use { h ->
            h.startAndHandshake()
            h.manager.send("count")
            h.awaitState("开始流") { it.streamingText.isNotEmpty() }
            h.manager.interrupt()
            h.awaitTurnEnd()
            h.manager.stopSession()
            val s = h.awaitState("关闭") { it.status == SessionStatus.Closed && !it.stopping }
            assertEquals(1, h.process.exitValueOrNull)
            assertNull(s.errorMessage)
        }
    }

    @Test
    fun `stdout breaking while the session is being stopped is not reported as an interruption`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.stdoutFailsOnExit = true
            h.manager.stopSession()
            val s = h.awaitState("关闭") { it.status == SessionStatus.Closed && !it.stopping }
            assertNull(s.errorMessage)
            assertTrue(h.process.hasExited)
        }
    }

    @Test
    fun `the CLI exiting on its own with a non-zero code closes the session with the exit code`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.crash(1)
            val s = h.awaitState("关闭") { it.status == SessionStatus.Closed }
            assertEquals("claude 进程退出 (exit 1)", s.errorMessage)
            assertFalse(s.busy)
        }
    }

    @Test
    fun `a broken stdout stream fails the session`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.failStdout("boom")
            val s = h.awaitState("失败") { it.status == SessionStatus.Failed }
            assertEquals("会话中断: boom", s.errorMessage)
            assertFalse(s.busy)
        }
    }

    @Test
    fun `a failed write to stdin fails the session`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.breakStdin()
            h.manager.send("hello")
            val s = h.awaitState("失败") { it.status == SessionStatus.Failed }
            assertEquals("写入会话失败: Broken pipe", s.errorMessage)
            assertFalse(s.busy)
            // 管道已断，关 stdin 让它退不掉 —— 直接杀，免得 close() 干等 shutdown 的宽限期
            h.process.destroy()
        }
    }

    @Test
    fun `changing effort relaunches the same session and keeps the conversation`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.manager.send("Reply with exactly the two letters OK and nothing else.")
            val before = h.awaitTurnEnd()
            val first = h.process
            h.manager.applyEffort("high")
            val s = h.awaitState("重启完成") {
                h.launches.size == 2 && it.status == SessionStatus.Running && !it.applyingEffort
            }
            h.awaitCondition("新进程握手") { "get_settings" in h.process.subtypes() }
            // 旧进程是关 stdin 退的
            assertTrue(first.hasExited)
            assertEquals(0, first.exitValueOrNull)
            val relaunch = h.launches[1]
            assertEquals("high", relaunch.options.effort)
            // transcript 还没落盘（测试里就没有）→ 不能 --resume，用同一个 id 新建
            assertNull(relaunch.options.resumeSessionId)
            assertEquals(before.sessionId, relaunch.options.newSessionId)
            assertEquals(before.sessionId, relaunch.sessionId)
            // 对话、模型目录、init 标记都不因为换档而清空
            assertEquals(before.lines(), s.lines())
            assertTrue(s.announcedInit)
            assertEquals(before.availableModels, s.availableModels)
            assertEquals(before.sessionId, s.sessionId)
            assertNull(s.errorMessage)
        }
    }

    // ---------------------------------------------------------------- control 通道

    @Test
    fun `an unknown control request from the CLI is answered with an error instead of left hanging`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.process.emit("""{"type":"control_request","request_id":"cli-1","request":{"subtype":"mystery"}}""")
            h.awaitCondition("应答") { h.process.written.any { it.str("type") == "control_response" } }
            val answer = h.process.written.single { it.str("type") == "control_response" }["response"]!!.jsonObject
            assertEquals("error", answer.str("subtype"))
            assertEquals("cli-1", answer.str("request_id"))
            assertEquals("Min does not support control request 'mystery'", answer.str("error"))
        }
    }

    /**
     * 认得的 error_code（2.1.283+）先说人话，CLI 原句括在后面；认不得的只显示原句。
     * 2.1.283 以前的 CLI 没有 code，同一句 bypass 拒绝靠原句认出来（兜底，见 [ControlErrorCode]）。
     */
    @Test
    fun `a control error is shown with the CLI's own words`() = runBlocking<Unit> {
        val refusal = "Cannot set permission mode to bypassPermissions because the session was not " +
            "launched with --dangerously-skip-permissions"
        val hint = "这个会话的 CLI 启动时没开放「跳过权限确认」，中途切不过去。重开这个会话后再切"
        for ((code, error, expected) in listOf(
            Triple("bypass_not_launched", refusal, "$hint（$refusal）"),
            Triple(null, refusal, "$hint（$refusal）"),
            Triple("bypass_disabled", "Cannot set permission mode to bypassPermissions because it is disabled " +
                "by settings or configuration", null),
        )) {
            ManagerHarness("plain_reply").use { h ->
                h.nextProcess = {
                    FakeCliProcess(h.fixture, overrides = mapOf("set_permission_mode" to { _ ->
                        buildJsonObject {
                            put("subtype", "error")
                            put("error", error)
                            code?.let { put("error_code", it) }
                        }
                    }))
                }
                h.startAndHandshake()
                h.manager.setPermissionMode(ClaudeCodePermissionMode.BYPASS)
                val s = h.awaitState("报错") { st -> st.items.any { it is ChatItem.Note && it.isError } && !st.applyingSettings }
                assertEquals("error: 切换到 Bypass permissions 失败：${expected ?: error}", s.lines().last())
                assertEquals(ClaudeCodePermissionMode.DEFAULT, s.permissionMode)
            }
        }
    }

    /*
     * 进程死了，还在等应答的控制请求不能干等满超时（默认 8 秒，用量 20、拟名 30）：
     * 那段时间「应用中」一直转着，最后还报成「CLI 未应答」，而真实原因是进程没了。
     * 下面两条的 3 秒上限比 [ClaudeCodeControlChannel.TIMEOUT_MS] 短得多，等满超时就过不了。
     */

    @Test
    fun `a control request still waiting when the CLI dies fails at once instead of timing out`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.nextProcess = { FakeCliProcess(h.fixture, unanswered = setOf("set_permission_mode")) }
            h.startAndHandshake()
            h.manager.setPermissionMode(ClaudeCodePermissionMode.ACCEPT_EDITS)
            h.awaitCondition("请求已发出") { "set_permission_mode" in h.process.subtypes() }
            h.process.crash(1)
            val s = h.awaitState("立刻失败", timeoutMs = 3_000) { st ->
                !st.applyingSettings && st.items.any { it is ChatItem.Note && it.isError }
            }
            assertEquals("error: 切换到 Accept edits 失败：claude 进程已退出", s.lines().last())
            assertEquals(ClaudeCodePermissionMode.DEFAULT, s.permissionMode)
        }
    }

    @Test
    fun `the CLI dying mid-handshake hands held messages back at once and says why`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.nextProcess = { FakeCliProcess(h.fixture, unanswered = setOf("initialize")) }
            val gate = CompletableDeferred<Unit>()
            h.launchGate = gate
            val withdrawn = async(start = CoroutineStart.UNDISPATCHED) { h.manager.withdrawnMessages.first() }
            h.manager.startSession()
            h.awaitState("Starting") { it.status == SessionStatus.Starting }
            h.manager.send("hello early")
            gate.complete(Unit)
            h.awaitCondition("握手已发出") { h.launches.isNotEmpty() && "initialize" in h.process.subtypes() }
            h.process.crash(1)
            val s = h.awaitState("退回", timeoutMs = 3_000) { st -> st.items.none { it is ChatItem.UserText } }
            assertEquals(
                listOf("error: CLI 握手失败（claude 进程已退出），启动前排队的消息已退回输入框"),
                s.lines(),
            )
            assertEquals("hello early", withTimeout(3_000) { withdrawn.await() }.text)
        }
    }

    @Test
    fun `a successful permission mode switch updates the mode and the options`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.manager.setPermissionMode(ClaudeCodePermissionMode.ACCEPT_EDITS)
            val s = h.awaitState("切换") { it.permissionMode == ClaudeCodePermissionMode.ACCEPT_EDITS && !it.applyingSettings }
            assertEquals(ClaudeCodePermissionMode.ACCEPT_EDITS, s.options.permissionMode)
            assertFalse(s.options.skipPermissions)
            val sent = h.process.written.last { it.str("type") == "control_request" }["request"]!!.jsonObject
            assertEquals("set_permission_mode", sent.str("subtype"))
            assertEquals("acceptEdits", sent.str("mode"))
        }
    }
}
