package dev.min.agent.cli

import dev.min.agent.ext.config.RuleSpecKind
import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.RuleEntry
import dev.min.agent.spi.RuleKindData
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolInput
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.TestToolContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking

/**
 * Claude Code 权限规则兼容样本集（resources/permission-compat/）：每个样本一份真实用户会写的 settings.json
 * （进 /workspace/.claude/settings.json）与 case.json（当前模式 + 一串工具调用），期望按 CC 权限文档原文写出
 * （放行 / 问人 / 拒绝，每条 call 带 basis）。
 *
 * 测试照内核 ToolExecutor.policyContext() 的做法组判定上下文：SettingsLoader 读规则（project 层 `/x` 锚在 cwd），
 * 在 desktopTools() 里按名字找工具、parse 入参、用 TestToolContext 调 permissionAsk，再走 PolicyEngine.evaluate，
 * 把 Verdict 映射成 allow / ask / deny 与期望逐项比对；失败信息带样本名、第几个 call、期望与实际。
 * 工具表里没有的名字（mcp__…）直接造 PermissionAsk、按非只读处理。
 *
 * policy 在 CLI 里只有运行期可见（kernel 对它是 implementation 依赖），因此照 HookBatchIntegrationTest /
 * HooksCompatTest 的方式用反射驱动真实引擎：PermissionRule.parse、PolicyContext 构造、PolicyEngine.evaluate。
 *
 * 期望与实现对不上的样本在 case.json 里标 pendingFix：差异单独列出、不算失败，逐条复核后再定。
 * 撞上 Min 安全底线（PolicyEngine.floorReason）的样本按 CC 原文写期望并标 pendingFix，说明里写明撞线。
 */
class PermissionCompatTest {
    private val cwd = GuestPath("/workspace")
    private val home = GuestPath("/root")

    @Test fun `permission 兼容样本的判定与期望逐项一致`() {
        val samplesRoot = Paths.get(URI(javaClass.classLoader.getResource("permission-compat")!!.toString()))
        val samples = Files.list(samplesRoot).use { it.filter(Files::isDirectory).sorted().toList() }
        assertTrue("样本应至少 30 个，实际 ${samples.size}", samples.size >= 30)
        val failures = mutableListOf<String>()
        val pending = mutableListOf<String>()
        runBlocking { samples.forEach { sample -> runSample(sample, failures, pending) } }
        if (pending.isNotEmpty()) {
            println("---- pendingFix 样本的差异（不算失败，待逐条复核）----\n${pending.joinToString("\n")}")
        }
        val pendingNote = if (pending.isEmpty()) "" else
            "\n---- pendingFix 样本的差异（${pending.size} 行，不算失败）----\n${pending.joinToString("\n")}"
        assertTrue("共 ${samples.size} 个样本，${failures.size} 处不一致：\n" +
            failures.joinToString("\n") + pendingNote, failures.isEmpty())
    }

    /** 跑单个样本：内存 FS 放 settings → SettingsLoader 读规则 → 逐个 call 判定 → 与 expected 比对。 */
    private suspend fun runSample(sample: Path, failures: MutableList<String>, pending: MutableList<String>) {
        val name = sample.fileName.toString()
        val diffs = mutableListOf<String>()
        try {
            val case = Json.parseToJsonElement(
                Files.readString(sample.resolve("case.json"), Charsets.UTF_8)).jsonObject
            val pendingFix = case["pendingFix"]?.jsonPrimitive?.contentOrNull
            val mode = permissionMode(case.getValue("mode").jsonPrimitive.content)
            val settings = Files.readString(sample.resolve("settings.json"), Charsets.UTF_8)

            // scope 前缀照 PolicyEngine.defaultScope 的三档：工作区之外一律 rootfs，样本用到的路径都在前缀里
            val fs = FakeFs()
            fs.scopes["/workspace"] = PathScope.Workspace
            fs.scopes["/sdcard"] = PathScope.Device
            fs.scopes["/storage"] = PathScope.Device
            for (prefix in listOf("/root", "/etc", "/tmp", "/data", "/home", "/usr", "/var")) {
                fs.scopes[prefix] = PathScope.Rootfs
            }
            fs.put("/workspace/.claude/settings.json", settings)
            val snapshot = SettingsLoader.load(fs, cwd, home)
            val rules = snapshot.merged.ruleSpecs().mapNotNull { spec ->
                val kind = when (spec.kind) {
                    RuleSpecKind.Allow -> RuleKindData.Allow
                    RuleSpecKind.Ask -> RuleKindData.Ask
                    RuleSpecKind.Deny -> RuleKindData.Deny
                }
                // 与内核 policyContext() 同一入口：RuleEntry → PermissionRule.from，认不出的写法返回 null
                ruleFrom(RuleEntry(kind, spec.text, spec.source, spec.baseDir))
            }
            val context = policyContext(mode, rules, fs, snapshot.merged.fileAccessDirectories(cwd, home))

            val tools = desktopTools()
            val ctx = TestToolContext(fs = fs, cwd = cwd)
            case.getValue("calls").jsonArray.forEachIndexed { i, callEl ->
                val call = callEl.jsonObject
                val toolName = call.getValue("tool").jsonPrimitive.content
                val label = "$name 第${i + 1}个 call（$toolName）"
                val input = call["input"] as? JsonObject ?: JsonObject(emptyMap())
                val expected = call.getValue("expected").jsonPrimitive.content
                val ask: PermissionAsk
                val readOnly: Boolean
                val tool = tools.firstOrNull { it.name == toolName }
                if (tool != null) {
                    @Suppress("UNCHECKED_CAST")
                    val typed = tool as Tool<Any>
                    val parsed = typed.parse(input)
                    if (parsed is ToolInput.Invalid) {
                        diffs += "$label：入参解析失败（${parsed.messageForModel}）"
                        return@forEachIndexed
                    }
                    ask = typed.permissionAsk((parsed as ToolInput.Valid<Any>).value, ctx)
                    readOnly = tool.traits.readOnly
                } else {
                    // MCP 工具等不在工具表里的名字：按非只读的裸调用处理（任务单约定）
                    ask = PermissionAsk(toolName, "调用 $toolName")
                    readOnly = false
                }
                val actual = evaluate(ask, readOnly, context)
                if (actual != expected) diffs += "$label：期望=$expected 实际=$actual"
            }
            when {
                diffs.isEmpty() -> Unit
                pendingFix != null -> pending += "[$name] pendingFix：$pendingFix\n" +
                    diffs.joinToString("\n") { "    $it" }
                else -> failures += diffs
            }
        } catch (e: Throwable) {
            failures += "$name 执行异常：${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun permissionMode(value: String): PermissionMode = when (value) {
        "default" -> PermissionMode.Default
        "acceptEdits" -> PermissionMode.AcceptEdits
        "plan" -> PermissionMode.Plan
        "dontAsk" -> PermissionMode.DontAsk
        "bypassPermissions" -> PermissionMode.BypassPermissions
        else -> error("未知 mode：$value（case.json 的 mode 应为 default/acceptEdits/plan/dontAsk/bypassPermissions）")
    }

    // ---------- policy 的反射驱动（policy 在 CLI 里只有运行期可见） ----------
    //
    // GuestPath 是 value class：JVM 描述符里它的参数位置是 String、名字带 mangle，所以不直接找
    // PermissionRule.parse / PolicyContext 主构造的原签名——规则从可见的 RuleEntry 走 from()，上下文走主构造。

    private val policyContextClass = Class.forName("dev.min.agent.policy.PolicyContext")

    /** PermissionRule.from(entry)：与内核同一入口，认不出的写法返回 null */
    private fun ruleFrom(entry: RuleEntry): Any? {
        val companion = Class.forName("dev.min.agent.policy.PermissionRule").getField("Companion").get(null)
        val from = companion.javaClass.methods.single { it.name == "from" && it.parameterCount == 1 }
        return from.invoke(companion, entry)
    }

    /** 照内核 policyContext() 组上下文：模式、规则、cwd、home 与宿主按路径分级的函数 */
    private fun policyContext(mode: PermissionMode, rules: List<Any?>, fs: FakeFs, directories: List<GuestPath>): Any {
        val scopeOf: (GuestPath) -> PathScope = { p -> fs.scopeOf(p) }
        // value class 参数仍用 String；新增的目录列表走正式设置入口，不能靠 FakeFs 伪装成工作区。
        // 新增宿主凭证边界；普通权限样本没有宿主凭证，用空集与恒等解析保持原来的测试语义。
        val canonicalPath: (GuestPath) -> GuestPath = { it }
        // 普通兼容样本不开启全 shell 分类；新增字段必须显式传入，避免把反射签名失配当成规则差异。
        // 普通桌面兼容样本没有 Android guest 策略，显式传 null，确保仍进入原来的规则判定。
        // 15 个字段（含 shellCwd）加最后的 DefaultConstructorMarker；不能把构造签名失配当成权限行为差异。
        val ctor = policyContextClass.constructors.single { it.parameterCount == 16 }
        return ctor.newInstance(mode, rules, cwd.value, home.value, scopeOf, null, directories, emptySet<GuestPath>(), canonicalPath, null, emptyList<String>(), false, false, null, null, null)
    }

    /** PolicyEngine.evaluate(ask, readOnly, context)；Verdict 子类名映射成 allow / ask / deny */
    private fun evaluate(ask: PermissionAsk, readOnly: Boolean, context: Any): String {
        val engine = Class.forName("dev.min.agent.policy.PolicyEngine").getField("INSTANCE").get(null)
        val evaluate = engine.javaClass.methods.single {
            it.name == "evaluate" && it.parameterCount == 3 && it.parameterTypes[2] == policyContextClass
        }
        return when (evaluate.invoke(engine, ask, readOnly, context).javaClass.simpleName) {
            "Allow" -> "allow"
            "Ask" -> "ask"
            "Deny" -> "deny"
            else -> error("未知 Verdict：${evaluate.invoke(engine, ask, readOnly, context).javaClass}")
        }
    }
}
