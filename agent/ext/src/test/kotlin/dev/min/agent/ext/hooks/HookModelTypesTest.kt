package dev.min.agent.ext.hooks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** prompt / agent / mcp_tool 三种模型侧类型的纯函数面：结论解析、按事件的口径翻译、参数模板插值 */
class HookModelTypesTest {
    private fun promptHandler(event: HookEvent, continueOnBlock: Boolean = false) = HookHandler(
        event = event, matcher = null, type = "prompt", command = null, args = null, timeout = null, condition = null,
        source = dev.min.agent.ext.config.ConfigSource.User, raw = buildJsonObject { },
        prompt = "Evaluate the hook input and answer with JSON.", continueOnBlock = continueOnBlock,
    )


    private fun agentHandler(event: HookEvent) = HookHandler(
        event = event, matcher = null, type = "agent", command = null, args = null, timeout = null, condition = null,
        source = dev.min.agent.ext.config.ConfigSource.User, raw = buildJsonObject { },
        prompt = "Verify tests pass and answer with JSON.",
    )

    // ---- parsePromptDecision ----

    @Test fun `模型结论的严格与宽松解析`() {
        assertEquals(PromptDecision(true, null, false), parsePromptDecision("""{"ok": true}"""))
        assertEquals(PromptDecision(false, "还有事没做完", false), parsePromptDecision("""{"ok": false, "reason": "还有事没做完"}"""))
        assertEquals(PromptDecision(false, "x", true), parsePromptDecision("""{"ok": false, "reason": "x", "impossible": true}"""))
        // 外围带话的回复：退回首尾平衡的对象片段
        assertEquals(PromptDecision(false, "r", false), parsePromptDecision("""好的，结论如下：{"ok": false, "reason": "r"} 以上。"""))
        assertNull(parsePromptDecision("""{"reason": "缺 ok"}"""))
        assertNull(parsePromptDecision("完全没有 JSON 的回复"))
    }

    // ---- interpretPrompt：ok:false 按事件翻译（CC 的 Response schema 表） ----

    @Test fun `Stop 的 ok false 把理由交回模型继续，impossible 才放行`() {
        val deny = interpretPrompt(HookEvent.Stop, promptHandler(HookEvent.Stop), PromptHookOutcome.Done("""{"ok": false, "reason": "还没做完"}"""))
        assertEquals(HookDecision.Block, deny.decision)
        assertEquals("还没做完", deny.reason?.text)
        val impossible = interpretPrompt(HookEvent.Stop, promptHandler(HookEvent.Stop), PromptHookOutcome.Done("""{"ok": false, "reason": "条件永远满足不了", "impossible": true}"""))
        assertEquals(HookDecision.Neutral, impossible.decision)
    }

    @Test fun `PreToolUse 默认拒绝并结束回合，continueOnBlock 换把理由作为工具错误继续`() {
        val deny = interpretPrompt(HookEvent.PreToolUse, promptHandler(HookEvent.PreToolUse), PromptHookOutcome.Done("""{"ok": false, "reason": "别动"}"""))
        assertEquals(HookDecision.Deny, deny.decision)
        assertFalse(deny.shouldContinue)
        assertEquals("别动", deny.stopReason?.text)
        val soft = interpretPrompt(HookEvent.PreToolUse, promptHandler(HookEvent.PreToolUse, continueOnBlock = true), PromptHookOutcome.Done("""{"ok": false, "reason": "换个方式"}"""))
        assertEquals(HookDecision.Deny, soft.decision)
        assertTrue(soft.shouldContinue)
        assertEquals("换个方式", soft.reason?.text)
        // agent 恒按 continueOnBlock:true（CC：Agent-based hooks）
        val agent = interpretPrompt(HookEvent.PreToolUse, agentHandler(HookEvent.PreToolUse), PromptHookOutcome.Done("""{"ok": false, "reason": "agent 说不"}"""))
        assertEquals(HookDecision.Deny, agent.decision)
        assertTrue(agent.shouldContinue)
        // ok:true 一律 Neutral（M1）：CC 的表只定义 ok:false 的行为，ok:true 从不等于 permissionDecision:allow——
        // 评估器是廉价小模型、输入里有模型可控的 tool_input，翻成放行就绕过审批；只有 command / http 的
        // permissionDecision:allow 能免审
        for (event in listOf(HookEvent.PreToolUse, HookEvent.Stop, HookEvent.PostToolUse, HookEvent.UserPromptSubmit)) {
            assertEquals("ok:true 在 $event 上必须是中性", HookDecision.Neutral,
                interpretPrompt(event, promptHandler(event), PromptHookOutcome.Done("""{"ok": true}""")).decision)
        }
    }

    @Test fun `UserPromptSubmit 与 PostToolUseFailure 的口径`() {
        // UserPromptSubmit：结束回合 + 警告（continue 救不了）
        val end = interpretPrompt(HookEvent.UserPromptSubmit, promptHandler(HookEvent.UserPromptSubmit), PromptHookOutcome.Done("""{"ok": false, "reason": "这个提示词不合规"}"""))
        assertFalse(end.shouldContinue)
        assertEquals("这个提示词不合规", end.stopReason?.text)
        // PostToolUseFailure：理由作为工具错误交回模型、回合继续
        val feedback = interpretPrompt(HookEvent.PostToolUseFailure, promptHandler(HookEvent.PostToolUseFailure), PromptHookOutcome.Done("""{"ok": false, "reason": "修一下再试"}"""))
        assertEquals(HookDecision.Block, feedback.decision)
        assertTrue(feedback.shouldContinue)
    }

    @Test fun `PermissionRequest 与 PermissionDenied 上输出没有效果`() {
        for (event in listOf(HookEvent.PermissionRequest, HookEvent.PermissionDenied)) {
            val verdict = interpretPrompt(event, promptHandler(event), PromptHookOutcome.Done("""{"ok": false, "reason": "x"}"""))
            assertEquals(HookDecision.Neutral, verdict.decision)
            assertTrue(verdict.shouldContinue)
        }
    }

    @Test fun `没按口径回、执行失败、超时都是非阻断错误`() {
        val malformed = interpretPrompt(HookEvent.Stop, promptHandler(HookEvent.Stop), PromptHookOutcome.Done("我觉得可以了"))
        assertNotNull(malformed.nonBlockingError)
        val failed = interpretPrompt(HookEvent.Stop, promptHandler(HookEvent.Stop), PromptHookOutcome.Failed("HTTP 429"))
        assertTrue(failed.nonBlockingError!!.contains("HTTP 429"))
        val timedOut = interpretPrompt(HookEvent.Stop, promptHandler(HookEvent.Stop), PromptHookOutcome.TimedOut)
        assertTrue(timedOut.timedOut)
        // ok:false 不带 reason：口径违规，同样非阻断
        val noReason = interpretPrompt(HookEvent.Stop, promptHandler(HookEvent.Stop), PromptHookOutcome.Done("""{"ok": false}"""))
        assertNotNull(noReason.nonBlockingError)
    }

    // ---- 事件矩阵（matchingModel）与解析 ----

    @Test fun `matchingModel 按类型过事件矩阵`() {
        val set = HookSet.parse(
            listOf(
                dev.min.agent.ext.config.HookSource(
                    dev.min.agent.ext.config.ConfigSource.User,
                    buildJsonObject {
                        put("Stop", JsonArray(listOf(buildJsonObject { put("hooks", JsonArray(listOf(buildJsonObject { put("type", "prompt"); put("prompt", "x") }))) })))
                        put("PermissionRequest", JsonArray(listOf(buildJsonObject { put("hooks", JsonArray(listOf(buildJsonObject { put("type", "agent"); put("prompt", "x") }))) })))
                        put("SessionEnd", JsonArray(listOf(buildJsonObject { put("hooks", JsonArray(listOf(buildJsonObject { put("type", "prompt"); put("prompt", "x") }))) })))
                    },
                ),
            ),
        )
        assertEquals(1, set.matchingModel(HookEvent.Stop, null).handlers.size)
        // PermissionRequest 不收 agent（CC 原文）：跳过并记说明
        val onPermission = set.matchingModel(HookEvent.PermissionRequest, null)
        assertTrue(onPermission.handlers.isEmpty())
        assertTrue(onPermission.notes.single().contains("不支持"))
        // SessionEnd 不在 prompt 的事件集里
        assertTrue(set.matchingModel(HookEvent.SessionEnd, null).handlers.isEmpty())
    }

    @Test fun `解析校验：prompt 必填、mcp 要 server 和 tool、agent 没有 continueOnBlock`() {
        fun hooksOf(vararg entries: Pair<String, String>): HookSet = HookSet.parse(
            listOf(
                dev.min.agent.ext.config.HookSource(
                    dev.min.agent.ext.config.ConfigSource.User,
                    buildJsonObject { put("Stop", JsonArray(listOf(buildJsonObject { put("hooks", JsonArray(listOf(buildJsonObject { entries.forEach { (k, v) -> put(k, v) } }))) }))) },
                ),
            ),
        )
        assertEquals(1, hooksOf("type" to "prompt", "prompt" to "判断一下").matchingModel(HookEvent.Stop, null).handlers.size)
        assertTrue("缺 prompt 的整组跳过", hooksOf("type" to "prompt").notes.single().contains("prompt"))
        assertTrue("缺 server", hooksOf("type" to "mcp_tool", "tool" to "t").notes.single().contains("server"))
        assertTrue("缺 tool", hooksOf("type" to "mcp_tool", "server" to "s").notes.single().contains("tool"))
        assertTrue(
            "agent 没有 continueOnBlock",
            hooksOf("type" to "agent", "prompt" to "p", "continueOnBlock" to "true").notes.single().contains("continueOnBlock"),
        )
        // input 模板照解析成对象
        val withInput = HookSet.parse(
            listOf(
                dev.min.agent.ext.config.HookSource(
                    dev.min.agent.ext.config.ConfigSource.User,
                    buildJsonObject {
                        put("Stop", JsonArray(listOf(buildJsonObject {
                            put("hooks", JsonArray(listOf(buildJsonObject {
                                put("type", "mcp_tool"); put("server", "s"); put("tool", "t")
                                put("input", buildJsonObject { put("a", "b") })
                            })))
                        })))
                    },
                ),
            ),
        )
        assertEquals(buildJsonObject { put("a", "b") }, withInput.matchingModel(HookEvent.Stop, null).handlers.single().mcpInput)
    }

    // ---- interpolateMcpInput ----

    @Test fun `mcp 参数模板的 dollar 路径插值`() {
        val template = buildJsonObject {
            put("file_path", "\${tool_input.file_path}")
            put("plain", "原样")
            put("missing", "\${tool_input.nope}")
        }
        val input = buildJsonObject {
            put("tool_input", buildJsonObject { put("file_path", "/w/a.txt") })
        }
        val interpolated = interpolateMcpInput(template, input)
        assertEquals("/w/a.txt", (interpolated["file_path"] as JsonPrimitive).content)
        assertEquals("原样", (interpolated["plain"] as JsonPrimitive).content)
        assertEquals("取不到换空串", "", (interpolated["missing"] as JsonPrimitive).content)
    }
}
