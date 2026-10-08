package dev.min.agent.ext.hooks

import org.junit.Assert.*
import org.junit.Test

class HookInterpretTest {
    @Test fun `每个退出码都解析 JSON 且合法 JSON 抑制普通错误`() {
        listOf(0, 1, 2, 3, 126).forEach { code ->
            val v = interpreted(HookEvent.PreToolUse, specific(HookEvent.PreToolUse,
                """"permissionDecision":"allow","additionalContext":"context""""), code)
            assertEquals(if (code == 2) HookDecision.Block else HookDecision.Allow, v.decision)
            assertNull(v.nonBlockingError); assertEquals("context", v.additionalContext.single().text)
        }
    }

    @Test fun `退出码二采用 JSON 阻断理由否则 stderr 不采用 allow 理由`() {
        val e = HookEvent.PreToolUse
        assertEquals("json deny", interpreted(e, specific(e,
            """"permissionDecision":"deny","permissionDecisionReason":"json deny""""), 2).reason?.text)
        assertEquals("stderr", interpreted(e, specific(e,
            """"permissionDecision":"allow","permissionDecisionReason":"allow reason""""), 2).reason?.text)
        val broken = interpreted(e, "{broken}", 2)
        assertEquals(HookDecision.Block, broken.decision); assertEquals("stderr", broken.reason?.text)
        // CC：exit 2 + 校验失败仍阻断，失败只进调试日志（notes），不报非阻断错误
        assertNull(broken.nonBlockingError)
        assertTrue(broken.notes.any { it.contains("解析或校验失败") })
    }

    @Test fun `多行各自合法 JSON 且无输出字段按纯文本处理`() {
        // CC §Exit code 0：两行及以上、每行各自是合法 JSON、没有一行设置字段 → 整体按纯文本
        val plain = interpreted(HookEvent.UserPromptSubmit, "{\"a\":1}\n[2]\n{\"b\":3}\n")
        assertNull(plain.nonBlockingError)
        assertEquals(listOf("{\"a\":1}\n[2]\n{\"b\":3}\n"), plain.additionalContext.map { it.text })
        // PreToolUse 上同款输出只进调试日志，不是错误也不入上下文
        val debugOnly = interpreted(HookEvent.PreToolUse, "{\"a\":1}\n{\"b\":3}")
        assertNull(debugOnly.nonBlockingError); assertTrue(debugOnly.additionalContext.isEmpty())
        // 任何一行设置了输出字段，整体判为解析失败
        val fieldLine = interpreted(HookEvent.UserPromptSubmit, "{\"a\":1}\n{\"decision\":\"block\"}")
        assertNotNull(fieldLine.nonBlockingError); assertTrue(fieldLine.additionalContext.isEmpty())
        // 有一行不是合法 JSON（整体也不是一个对象）时按解析失败处理
        val brokenLine = interpreted(HookEvent.UserPromptSubmit, "{\"a\":1}\n{broken}")
        assertNotNull(brokenLine.nonBlockingError)
    }

    @Test fun `exit 二的阻断能力逐事件覆盖`() {
        // PostToolUse / PostToolUseFailure 不在里面：CC 逐事件表「Can block? No | Shows stderr to Claude」，stderr 作为上下文
        val blocks = setOf(HookEvent.UserPromptSubmit, HookEvent.UserPromptExpansion, HookEvent.PreToolUse,
            HookEvent.PostToolBatch, HookEvent.SubagentStop,
            HookEvent.TaskCreated, HookEvent.TaskCompleted, HookEvent.Stop, HookEvent.TeammateIdle,
            HookEvent.ConfigChange, HookEvent.PreCompact,
            HookEvent.PreModelSwitch, HookEvent.Elicitation, HookEvent.ElicitationResult)
        HookEvent.entries.forEach { event ->
            assertEquals(event.name, if (event in blocks) HookDecision.Block else HookDecision.Neutral,
                interpreted(event, "{}", 2).decision)
        }
    }

    @Test fun `工具跑完之后的事件 exit 二把 stderr 作为上下文交给模型而不是阻断`() {
        listOf(HookEvent.PostToolUse, HookEvent.PostToolUseFailure).forEach { event ->
            val v = interpret(event, HandlerRun(handler(event), 2, "", "lint failed\n"))
            assertEquals(event.name, HookDecision.Neutral, v.decision)
            assertEquals(event.name, listOf("lint failed"), v.additionalContext.map { it.text })
            assertNull(v.nonBlockingError)
        }
        // JSON 的 decision:block 仍是阻断（反馈给模型）：PostToolUse 的这条路不变
        assertEquals(HookDecision.Block, interpreted(HookEvent.PostToolUse, """{"decision":"block","reason":"r"}""").decision)
    }

    @Test fun `六种忽略 exit 二的事件不把 stderr 报为普通错误`() {
        // PermissionDenied 的 CC 原文：Exit code and stderr are ignored because the denial already occurred
        listOf(HookEvent.PermissionRequest, HookEvent.StopFailure, HookEvent.Notification,
            HookEvent.Setup, HookEvent.InstructionsLoaded, HookEvent.PermissionDenied).forEach { event ->
            val v = interpreted(event, "text", 2)
            assertEquals(HookDecision.Neutral, v.decision); assertNull(v.reason); assertNull(v.nonBlockingError)
        }
        // 忽略的只是 exit 2：PermissionDenied 上其他非零退出码仍是非阻断错误，合法 JSON 也照常生效
        assertNotNull(interpreted(HookEvent.PermissionDenied, "text", 1).nonBlockingError)
        assertNull(interpreted(HookEvent.PermissionDenied, "{}", 1).nonBlockingError)
    }

    @Test fun `PermissionRequest 只能由 behavior 决定且 deny interrupt 停止`() {
        val e = HookEvent.PermissionRequest
        val allow = interpreted(e, specific(e, """"decision":{"behavior":"allow","updatedInput":{"x":1},"message":"ok"}"""), 2)
        assertEquals(HookDecision.Allow, allow.decision); assertEquals(objectOf("""{"x":1}"""), allow.updatedInput)
        val deny = interpreted(e, specific(e, """"decision":{"behavior":"deny","updatedInput":{"x":2},"message":"stop","interrupt":true}"""))
        assertEquals(HookDecision.Deny, deny.decision); assertFalse(deny.shouldContinue)
        assertEquals("stop", deny.stopReason?.text); assertNull(deny.updatedInput)
    }

    @Test fun `PreToolUse 新旧格式及 defer 中性`() {
        val e = HookEvent.PreToolUse
        assertEquals(HookDecision.Allow, interpreted(e, """{"decision":"approve","reason":"old"}""").decision)
        assertEquals(HookDecision.Deny, interpreted(e, """{"decision":"block","reason":"old"}""").decision)
        assertEquals(HookDecision.Ask, interpreted(e, specific(e, """"permissionDecision":"ask"""")).decision)
        assertEquals(HookDecision.Neutral, interpreted(e, specific(e, """"permissionDecision":"defer"""")).decision)
        val v = interpreted(e, specific(e, """"permissionDecision":"allow","updatedInput":{"command":"new"}"""))
        assertEquals(objectOf("""{"command":"new"}"""), v.updatedInput)
    }

    @Test fun `PreModelSwitch 校验决定且顶层 block 不被嵌套 allow 推翻`() {
        val e = HookEvent.PreModelSwitch
        val v = interpreted(e, """{"decision":"block","reason":"top","hookSpecificOutput":{"hookEventName":"PreModelSwitch","permissionDecision":"allow"}}""")
        assertEquals(HookDecision.Block, v.decision); assertEquals("top", v.reason?.text)
        assertEquals(HookDecision.Ask, interpreted(e, specific(e, """"permissionDecision":"ask"""")).decision)
        assertNotNull(interpreted(e, specific(e, """"permissionDecision":"defer"""")).nonBlockingError)
    }

    @Test fun `只有四个事件成功纯文本 stdout 注入上下文其余保留调试`() {
        val contexts = setOf(HookEvent.UserPromptSubmit, HookEvent.UserPromptExpansion, HookEvent.SessionStart, HookEvent.PostModelSwitch)
        HookEvent.entries.forEach { event ->
            val v = interpreted(event, " 中文\n")
            assertEquals(event.name, if (event in contexts) listOf(" 中文\n") else emptyList<String>(), v.additionalContext.map { it.text })
            assertTrue(interpreted(event, "text", 3).additionalContext.isEmpty())
        }
    }

    @Test fun `其他退出码非 JSON 错误只显示 stderr 第一行`() {
        val v = interpreted(HookEvent.PreToolUse, "debug", 5, "first\nsecond")
        assertEquals(HookDecision.Neutral, v.decision)
        assertEquals("hook hook error：first", v.nonBlockingError)
        assertNull(interpreted(HookEvent.PreToolUse, "debug").nonBlockingError)
    }

    @Test fun `JSON 只在花括号包住整个 stdout 时识别`() {
        val event = HookEvent.PreToolUse
        assertEquals(HookDecision.Allow, interpreted(event, " \n{\"decision\":\"approve\"}\t").decision)
        listOf("log\n{\"decision\":\"approve\"}", "[]", "{\"decision\":\"approve\"}\nlog").forEach {
            assertEquals(HookDecision.Neutral, interpreted(event, it).decision)
        }
    }

    @Test fun `类型错误事件名不符缺名未知决定均非阻断错误`() {
        val e = HookEvent.PreToolUse
        listOf("{broken}", """{"continue":"false"}""", """{"systemMessage":4}""",
            """{"hookSpecificOutput":[]}""", """{"hookSpecificOutput":{"permissionDecision":"deny"}}""",
            specific(HookEvent.Stop, """"additionalContext":"x""""),
            specific(e, """"permissionDecision":"wrong""""), specific(e, """"permissionDecision":4"""),
            specific(e, """"updatedInput":[]"""), """{"decision":"wrong"}""").forEach {
            val v = interpreted(e, it)
            assertEquals(it, HookDecision.Neutral, v.decision); assertNotNull(it, v.nonBlockingError)
            assertEquals(it, HookDecision.Block, interpreted(e, it, 2).decision)
        }
        assertNull(interpreted(e, """{"unknown":1}""").nonBlockingError)
    }

    @Test fun `上下文专属输出逐形状解码`() {
        listOf(HookEvent.SessionStart, HookEvent.UserPromptSubmit, HookEvent.UserPromptExpansion,
            HookEvent.PreToolUse, HookEvent.PostToolUse, HookEvent.PostToolUseFailure, HookEvent.PostToolBatch,
            HookEvent.SubagentStart, HookEvent.SubagentStop, HookEvent.Stop, HookEvent.PostModelSwitch).forEach { e ->
            assertEquals(e.name, "ctx", interpreted(e, specific(e, """"additionalContext":"ctx"""")).additionalContext.single().text)
        }
    }

    @Test fun `continue false 与 stopReason 优先且仍保留 systemMessage`() {
        // 丢弃 continue 的事件（HOOK_OUTPUT_DISCARDS 里有 Continue 的）另测，见 HookOutputDiscardsTest。
        HookEvent.entries.filter { DiscardedField.Continue !in HOOK_OUTPUT_DISCARDS[it].orEmpty() }.forEach { e ->
            val v = interpreted(e, """{"continue":false,"stopReason":"stop","systemMessage":"warning"}""")
            assertFalse(e.name, v.shouldContinue); assertEquals("stop", v.stopReason?.text)
            assertEquals("warning", v.systemMessage?.text)
        }
    }

    @Test fun `cwd 只通知人 worktree 丢弃控制和通知但保留原始路径输出`() {
        for (event in listOf(HookEvent.CwdChanged, HookEvent.WorktreeCreate, HookEvent.WorktreeRemove)) {
            for (exit in listOf(0, 1, 2)) {
                val verdict = interpreted(event, """{"continue":false,"stopReason":"stop","systemMessage":"warning"}""", exit)
                assertTrue(verdict.shouldContinue); assertNull(verdict.stopReason)
                assertEquals(HookDecision.Neutral, verdict.decision)
                assertEquals(if (event == HookEvent.CwdChanged) "warning" else null, verdict.systemMessage?.text)
                assertTrue(verdict.additionalContext.isEmpty())
            }
        }
        val create = interpreted(HookEvent.WorktreeCreate, specific(HookEvent.WorktreeCreate, """"worktreePath":"/path""""))
        assertEquals(kotlinx.serialization.json.JsonPrimitive("/path"), create.specificOutput?.get("worktreePath"))
    }

    @Test fun `Notification 丢弃 continue 与 systemMessage，SessionEnd 丢弃全部输出字段`() {
        listOf(HookEvent.Notification, HookEvent.SessionEnd).forEach { e ->
            val v = interpreted(e, """{"continue":false,"stopReason":"stop","systemMessage":"warning"}""")
            assertTrue(e.name, v.shouldContinue); assertNull(v.stopReason); assertNull(v.systemMessage)
        }
        val end = interpreted(HookEvent.SessionEnd, """{"decision":"block","reason":"r","hookSpecificOutput":{"hookEventName":"SessionEnd","additionalContext":"c"}}""")
        assertEquals(HookDecision.Neutral, end.decision); assertNull(end.reason); assertTrue(end.additionalContext.isEmpty())
    }

    @Test fun `超时丢弃输出 PreToolUse ask 其余中性`() {
        HookEvent.entries.forEach { e ->
            val v = interpret(e, HandlerRun(handler(e), 124, """{"continue":false}""", "bad", timedOut = true))
            assertEquals(e.name, if (e == HookEvent.PreToolUse) HookDecision.Ask else HookDecision.Neutral, v.decision)
            assertTrue(v.shouldContinue); assertTrue(v.additionalContext.isEmpty()); assertTrue(v.timedOut)
        }
    }

    @Test fun `127 和缺文件属于没跑起来即使 stdout 合法也不接受决定`() {
        listOf(127, null).forEach { code ->
            val v = interpret(HookEvent.PreToolUse, HandlerRun(handler(), code, """{"decision":"approve"}""", "err"))
            assertTrue(v.failedToStart); assertNotNull(v.nonBlockingError); assertTrue(v.notes.single().contains("没跑起来"))
            assertEquals(HookDecision.Neutral, v.decision)
        }
        assertTrue(interpret(HookEvent.PreToolUse, HandlerRun(handler(), null, "", "", timedOut = true,
            failedToStart = true)).timedOut)
    }

    @Test fun `一万字符边界与两千预览 原文仍供内核落附件`() {
        assertFalse(HookText("x".repeat(10_000)).needsAttachment)
        val long = "中".repeat(10_001)
        val v = interpreted(HookEvent.PreToolUse, specific(HookEvent.PreToolUse,
            """"additionalContext":"$long","updatedInput":{"nested":["$long"]}"""))
        assertEquals(long, v.additionalContext.single().text)
        assertEquals(2_000, v.additionalContext.single().preview.length)
        assertEquals(2, v.attachmentRequests.size)
        assertTrue(v.attachmentRequests.any { it.path == listOf("hookSpecificOutput", "updatedInput", "nested", "0") })
        val plain = interpreted(HookEvent.SessionStart, long)
        assertEquals(1, plain.attachmentRequests.size)
        assertEquals(1, interpreted(HookEvent.PreToolUse, "", 2, long).attachmentRequests.size)
        val common = interpreted(HookEvent.Stop, """{"continue":false,"stopReason":"$long","systemMessage":"$long"}""")
        assertEquals(2, common.attachmentRequests.size); assertEquals(2_000, common.stopReason!!.preview.length)
        assertEquals(2_000, common.systemMessage!!.preview.length)
        val error = interpreted(HookEvent.Stop, "debug", 3, long)
        assertEquals(2_000, error.nonBlockingError!!.length); assertEquals(1, error.attachmentRequests.size)
    }
}
