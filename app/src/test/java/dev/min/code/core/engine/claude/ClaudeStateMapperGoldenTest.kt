package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.ManagerHarness
import dev.min.code.core.claudecode.ClaudeStateSnapshot
import dev.min.code.core.claudecode.FakeCliProcess
import dev.min.code.core.claudecode.SnapshotStore
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.session.ChatItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 黄金测试（设计 §9.2 B4）：B0 的 10 份 CLI 夹具回放之后，`ClaudeStateMapper.map(state)` 映射出的每个字段，
 * 都等于 B0 快照文件里「会话页读到的值」。
 *
 * 场景与阶段名与 `ClaudeStateSnapshotTest` 一一对应（那边是 private 的，只能照抄脚本）。比对的基准是
 * **提交进仓库的快照文件**，不是现场再渲染一遍：快照文件不改，映射写错了就是红。
 *
 * 比对规则：
 * 1. 映射结果渲染出的每个键，值必须与快照一致（派生量、agentId 的换形见 [AgentStateSnapshotRender.derivedBaseline]）；
 * 2. 快照里没被渲染的键必须落在 [UNCOVERED] 里，每一类都写明原因 —— 通用类型表达不了 / 页面空闲时不读 / 属于 CC 内部。
 *    快照以后多出新键，这里会报警，逼着人决定它该不该映射。
 */
class ClaudeStateMapperGoldenTest {

    /** 夹具里没录到 get_settings / get_session_cost 的应答时给最小应答（同 ClaudeStateSnapshotTest） */
    private fun minimalSettingsOverrides(cost: String): Map<String, (JsonObject) -> JsonObject> = mapOf(
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
                put("response", buildJsonObject { put("text", cost) })
            }
        },
    )

    private class Checker(private val h: ManagerHarness, fixture: String) {
        val baselines = AgentStateSnapshotRender.parseFile(SnapshotStore.file(fixture).readText(Charsets.UTF_8))
        val checked = ArrayList<String>()
        val uncoveredSeen = LinkedHashSet<String>()

        private fun live(): String = ClaudeStateSnapshot.render(h.manager.state.value, h.dir, h.launches.map { it.sessionId })

        /** 等现场渲染静默（同 B0 的 Recorder），再把映射结果与快照文件里同名阶段逐键比 */
        suspend fun stage(name: String) {
            var last = live()
            var same = 0
            val deadline = System.currentTimeMillis() + 15_000
            while (same < 4) {
                if (System.currentTimeMillis() > deadline) fail("阶段「$name」一直没静默：\n$last")
                delay(100)
                val now = live()
                if (now == last) same++ else { same = 0; last = now }
            }
            val state = h.manager.state.value
            val ref = SessionRef(dev.min.code.core.engine.EngineKind.ClaudeCode, state.sessionId ?: "none")
            val mapped = ClaudeStateMapper.map(ref, state)
            val rendered = AgentStateSnapshotRender.render(
                mapped, ClaudeStateMapper.mapLive(state), h.dir, h.launches.map { it.sessionId },
            )
            val baseline = AgentStateSnapshotRender.derivedBaseline(
                baselines[name] ?: error("快照文件里没有阶段「$name」，有：${baselines.keys}"),
            )
            val diffs = ArrayList<String>()
            for ((key, value) in rendered) {
                val expected = baseline[key]
                if (expected == null) diffs += "映射渲染出了快照里没有的键 $key = $value"
                else if (expected != value) diffs += "$key\n    快照: $expected\n    映射: $value"
            }
            // 快照里没被渲染的键：必须在不覆盖清单里
            val rawKeys = baselines.getValue(name).keys
            for (key in rawKeys) {
                if (key in rendered) continue
                val norm = key.replace(Regex("""\[\d+]"""), "[]")
                if (UNCOVERED.none { it.matches(norm) }) diffs += "快照键 $key 既没被映射渲染、也不在不覆盖清单里"
                else uncoveredSeen += norm
            }
            if (diffs.isNotEmpty()) fail("阶段「$name」映射与快照不一致：\n" + diffs.joinToString("\n"))
            checked += "$name:${rendered.size}"
        }
    }

    private fun scenario(
        fixture: String,
        overrides: Map<String, (JsonObject) -> JsonObject>? = null,
        body: suspend ManagerHarness.(Checker) -> Unit,
    ) = runBlocking<Unit> {
        ManagerHarness(fixture).use { h ->
            if (overrides != null) h.nextProcess = { FakeCliProcess(h.fixture, overrides = overrides) }
            val checker = Checker(h, fixture)
            h.body(checker)
            // 快照里的阶段一个都不能漏测
            assertEquals("夹具 $fixture 的阶段没测全", checker.baselines.keys.toList(), checker.checked.map { it.substringBefore(':') })
        }
    }

    private suspend fun ManagerHarness.handshakeWithUsage() {
        startAndHandshake()
        awaitState("用量读回") { it.contextTokens != null }
    }

    @Test
    fun `plain_reply`() = scenario("plain_reply") { c ->
        handshakeWithUsage()
        c.stage("handshaken")
        manager.send("Reply with exactly the two letters OK and nothing else.")
        awaitTurnEnd()
        awaitState("拟名") { it.liveTitle != null }
        c.stage("turn-done")
    }

    @Test
    fun `bash_with_permission`() = scenario("bash_with_permission") { c ->
        handshakeWithUsage()
        manager.send("run it")
        awaitState("权限请求") { it.pendingPermission != null }
        c.stage("asking")
        assertTrue(manager.answerPermission(allow = true))
        awaitTurnEnd()
        c.stage("turn-done")
    }

    @Test
    fun `bash_denied_interrupt`() = scenario("bash_denied_interrupt") { c ->
        handshakeWithUsage()
        manager.send("run it")
        awaitState("权限请求") { it.pendingPermission != null }
        c.stage("asking")
        assertTrue(manager.answerPermission(allow = false))
        awaitTurnEnd()
        c.stage("denied")
    }

    @Test
    fun `interrupt_before_output`() = scenario("interrupt_before_output") { c ->
        handshakeWithUsage()
        manager.send("Write a haiku about the sea.")
        awaitState("busy") { it.busy }
        c.stage("busy-before-output")
        manager.interrupt()
        awaitTurnEnd()
        c.stage("withdrawn")
    }

    @Test
    fun `interrupt_midturn`() = scenario("interrupt_midturn") { c ->
        handshakeWithUsage()
        manager.send("count")
        awaitState("开始流") { it.streamingText.isNotEmpty() && it.turnProduced }
        manager.interrupt()
        awaitTurnEnd()
        c.stage("interrupted")
    }

    @Test
    fun `interrupt_during_permission`() = scenario("interrupt_during_permission") { c ->
        handshakeWithUsage()
        manager.send("Run this exact Bash command and nothing else: ls / . Then reply with one word: done.")
        awaitState("权限请求") { it.pendingPermission != null }
        c.stage("asking")
        manager.interrupt()
        awaitTurnEnd()
        c.stage("interrupted")
    }

    @Test
    fun `model_not_found`() = scenario("model_not_found") { c ->
        handshakeWithUsage()
        manager.send("Say hi.")
        awaitTurnEnd()
        awaitState("stderr 入流") { st -> st.items.any { it is ChatItem.ProcessOutput } }
        c.stage("failed")
    }

    @Test
    fun `subagent`() = scenario("subagent") { c ->
        handshakeWithUsage()
        manager.send("go")
        awaitTurnEnd()
        c.stage("turn-done")
    }

    @Test
    fun `background_agents_followup`() = scenario(
        "background_agents_followup",
        minimalSettingsOverrides("Total cost: \$0.06"),
    ) { c ->
        startAndHandshake()
        manager.send("go")
        awaitTurnEnd()
        c.stage("main-turn-ended")
        listOf("t2", "t2init", "mid2", "t3", "t3init", "mid3").forEach(process::releasePause)
        awaitState("第三轮收尾") { st ->
            !st.busy && st.items.any { it is ChatItem.AssistantText && "Both agents completed" in it.text }
        }
        c.stage("followups-done")
    }

    @Test
    fun `fork_background_permission`() = scenario(
        "fork_background_permission",
        minimalSettingsOverrides("Total cost: \$0.02"),
    ) { c ->
        startAndHandshake()
        manager.send("launch a fork")
        awaitState("fork 的权限卡") { it.pendingPermission?.agentId != null }
        c.stage("fork-asking")
        assertTrue(manager.answerPermission(allow = true))
        awaitState("fork 跑完且主线程交代完") { st ->
            !st.busy && st.items.any { it is ChatItem.AssistantText && "Fork completed" in it.text }
        }
        c.stage("fork-done")
    }

    private companion object {
        /**
         * 快照里没被映射渲染的键，按「去掉下标」的形状列出。每一类的原因：
         * - 通用类型表达不了 / 不该表达：CC 内部或启动参数（sessionId 由 ref 承载）；
         * - 页面空闲时不读：turnStartedAt / outputTokens*（manager 回合结束后不清零，快照里是残留值，
         *   通用状态里空闲 = turn 为 null）；
         * - 已由派生键覆盖：applied*、availableModels / relayModels 的细节（`derived.*`）。
         */
        val UNCOVERED: List<Regex> = listOf(
            "sessionId", // 由 SessionRef 承载，不在状态里
            "model", // init 报的模型，页面只在芯片里当兜底用，已由 derived.modelChip 覆盖
            "toolCount", // 顶栏副标题的「N 个工具」，CC 内部计数，通用类型里没有
            "turnStartedAt", "outputTokensSettled", "outputTokensCurrent", // 空闲时的残留值，见上
            "options\\.skipPermissions", "options\\.permissionMode", "options\\.cwd", // 启动参数；权限模式与 cwd 另有字段
            "options\\.newSessionId", "options\\.resumeSessionId", // 启动器的随机 id
            "appliedModel", "appliedEffort", "appliedUltracode", // 并入 derived.modelChip / effortChip
            "applyingSettings", "applyingEffort", // 并入 derived.applying
            "availableModels\\.size", "availableModels\\[]", // 并入 derived.models（模型表）
            "relayModelsChecked", "relayModelsError", "relayModels\\.size", "relayModels\\[]", // 同上；error 通用类型没有
            "tasks\\.size", "runningTasks\\.size", "tasks\\[]", "tasks\\[]\\..+", // 任务表分流进 threads / BackgroundTask，没有一一对应
        ).map(::Regex)
    }
}
