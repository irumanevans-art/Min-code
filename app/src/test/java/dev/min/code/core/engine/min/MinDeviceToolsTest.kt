package dev.min.code.core.engine.min

import dev.min.agent.policy.PermissionRule
import dev.min.agent.policy.PolicyContext
import dev.min.agent.policy.PolicyEngine
import dev.min.agent.policy.RuleKind
import dev.min.agent.policy.Verdict
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.args
import dev.min.agent.testkit.text
import dev.min.code.core.device.DEVICE_TOOLS
import dev.min.code.core.device.DeviceActions
import dev.min.code.core.device.DeviceController
import dev.min.code.core.device.DeviceToolKind
import dev.min.code.core.device.DeviceToolNames
import dev.min.code.core.device.deviceToolDefinition
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.UserInput
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** 只记录调用的设备。每个动作的返回值可替换，默认都成功 */
private class FakeDevice : DeviceActions {
    val calls = mutableListOf<String>()
    var uiTree = DeviceController.Result.success("[0] Button \"OK\"（用 device_tap 点它）")
    var shot = DeviceController.ImageResult.success("当前屏幕。请回到 device_ui_tree。", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
    var tapResult = DeviceController.Result.success("已点击 [2]")
    var throwOnBack = false

    override fun uiTree() = uiTree.also { calls += "uiTree" }
    override fun screenshot() = shot.also { calls += "screenshot" }
    override fun tap(index: Int) = tapResult.also { calls += "tap($index)" }
    override fun input(index: Int, text: String) = DeviceController.Result.success("已填入").also { calls += "input($index,$text)" }
    override fun swipe(direction: String, index: Int?) = DeviceController.Result.success("已滑动").also { calls += "swipe($direction,$index)" }
    override fun openApp(packageName: String) = DeviceController.Result.success("已打开").also { calls += "open($packageName)" }
    override fun back(): DeviceController.Result {
        calls += "back"
        if (throwOnBack) throw SecurityException("no permission")
        return DeviceController.Result.success("已按返回键")
    }
}

class MinDeviceToolsTest {
    private val device = FakeDevice()
    private val tools = deviceTools(device)
    private var kernel: TestKernel? = null

    @After fun tearDown() { kernel?.close() }

    private fun tool(kind: DeviceToolKind): Tool<*> = tools.single { it.name == kind.nativeName }

    @Suppress("UNCHECKED_CAST")
    private fun Tool<*>.parseRaw(vararg pairs: Pair<String, Any?>) = (this as Tool<Any>).parse(args(*pairs))

    @Suppress("UNCHECKED_CAST")
    private fun Tool<*>.call(ctx: ToolContext, vararg pairs: Pair<String, Any?>): ToolResult = runBlocking {
        val parsed = parseRaw(*pairs)
        assertTrue("parse 应通过：$parsed", parsed is ToolInput.Valid<*>)
        val last = (this@call as Tool<Any>).run((parsed as ToolInput.Valid<Any>).value, ctx).last()
        (last as ToolProgress.Done).result
    }

    // ---- 定义与 MCP 那份一致 ----

    @Test fun sevenToolsWithTheDeviceNamesInDeclarationOrder() {
        assertEquals(
            listOf("DeviceUiTree", "DeviceTap", "DeviceType", "DeviceSwipe", "DeviceOpenApp", "DeviceScreenshot", "DeviceBack"),
            tools.map { it.name },
        )
        assertEquals(DeviceToolKind.entries.map { it.nativeName }, tools.map { it.name })
        assertEquals("每个原生名只对应一个 MCP 名", 7, DeviceToolKind.entries.map { it.mcpName }.toSet().size)
    }

    @Test fun everyMcpToolHasANativeCounterpartAndViceVersa() {
        assertEquals(DEVICE_TOOLS.map { it.name }.toSet(), DeviceToolKind.entries.map { it.mcpName }.toSet())
    }

    @Test fun schemaIsTheMcpSchemaWithToolNamesRenamed() {
        for (kind in DeviceToolKind.entries) {
            val mcp = deviceToolDefinition(kind)
            val native = tool(kind)
            // 入参字段名、类型、必填、枚举逐项相同——只有 description 里提到的工具名换了
            assertEquals(kind.name, DeviceToolNames.toNative(mcp.inputSchema), native.inputSchema)
            assertEquals(kind.name, strip(mcp.inputSchema), strip(native.inputSchema))
            assertEquals(kind.name, DeviceToolNames.toNative(mcp.description), native.description(PROFILE))
        }
    }

    /** 去掉所有 description，只剩结构：字段名、type、enum、required */
    private fun strip(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterKeys { it != "description" }.mapValues { strip(it.value) })
        is JsonArray -> JsonArray(element.map(::strip))
        else -> element
    }

    @Test fun noMcpSnakeCaseNameLeaksIntoWhatTheModelSees() {
        val mcpNames = DeviceToolKind.entries.map { it.mcpName }
        for (tool in tools) {
            val seen = tool.description(PROFILE) + tool.inputSchema
            for (name in mcpNames) assertFalse("${tool.name} 里还有 $name", seen.contains(name))
        }
    }

    @Test fun indexFreshnessRuleSurvivesInTheIndexedTools() {
        for (kind in listOf(DeviceToolKind.Tap, DeviceToolKind.Input, DeviceToolKind.Swipe)) {
            val description = tool(kind).description(PROFILE)
            assertTrue("$kind", description.contains("most recent DeviceUiTree output"))
        }
    }

    @Test fun resultTextMentioningMcpNamesIsRenamedToo() {
        val result = tool(DeviceToolKind.UiTree).call(TestToolContext())
        assertEquals("[0] Button \"OK\"（用 DeviceTap 点它）", result.text)
        assertFalse(result.isError)
    }

    // ---- 入参 ----

    @Test fun badArgumentsAreRejectedWithAMessageForTheModel() {
        fun invalid(kind: DeviceToolKind, vararg pairs: Pair<String, Any?>): String =
            (tool(kind).parseRaw(*pairs) as ToolInput.Invalid).messageForModel
        assertTrue(invalid(DeviceToolKind.Tap).contains("index"))
        assertTrue(invalid(DeviceToolKind.Tap, "index" to -1).contains("index"))
        assertTrue(invalid(DeviceToolKind.Tap, "index" to "abc").contains("index"))
        assertTrue(invalid(DeviceToolKind.Input, "index" to 1).contains("text"))
        assertTrue(invalid(DeviceToolKind.Swipe, "direction" to "sideways").contains("direction"))
        assertTrue(invalid(DeviceToolKind.Swipe).contains("direction"))
        assertTrue(invalid(DeviceToolKind.OpenApp, "package_name" to "  ").contains("package_name"))
    }

    @Test fun lenientAboutNumbersWrittenAsStrings() {
        val result = tool(DeviceToolKind.Tap).call(TestToolContext(), "index" to "2")
        assertEquals(listOf("tap(2)"), device.calls)
        assertEquals("已点击 [2]", result.text)
    }

    @Test fun eachToolCallsTheMatchingDeviceAction() {
        val ctx = TestToolContext()
        tool(DeviceToolKind.UiTree).call(ctx)
        tool(DeviceToolKind.Tap).call(ctx, "index" to 3)
        tool(DeviceToolKind.Input).call(ctx, "index" to 1, "text" to "hello")
        tool(DeviceToolKind.Swipe).call(ctx, "direction" to "Down", "index" to 4)
        tool(DeviceToolKind.Swipe).call(ctx, "direction" to "up")
        tool(DeviceToolKind.OpenApp).call(ctx, "package_name" to "com.android.settings")
        tool(DeviceToolKind.Back).call(ctx)
        assertEquals(
            listOf("uiTree", "tap(3)", "input(1,hello)", "swipe(down,4)", "swipe(up,null)", "open(com.android.settings)", "back"),
            device.calls,
        )
    }

    // ---- 权限口径 ----

    private fun verdict(tool: Tool<*>, mode: PermissionMode, rules: List<PermissionRule> = emptyList()): Verdict {
        @Suppress("UNCHECKED_CAST")
        val typed = tool as Tool<Any>
        val sample = when (tool.name) {
            "DeviceTap" -> arrayOf("index" to 1)
            "DeviceType" -> arrayOf("index" to 1, "text" to "x")
            "DeviceSwipe" -> arrayOf("direction" to "down")
            "DeviceOpenApp" -> arrayOf("package_name" to "a.b")
            else -> emptyArray()
        }
        val input = (typed.parse(args(*sample)) as ToolInput.Valid<Any>).value
        val ask = typed.permissionAsk(input, TestToolContext())
        assertEquals(tool.name, ask.tool)
        assertTrue("没有路径 / 命令 / 域名，规则只能按工具名匹配", ask.paths.isEmpty() && ask.commands.isEmpty() && ask.domains.isEmpty())
        return PolicyEngine.evaluate(ask, typed.traits.readOnly, PolicyContext(mode = mode, rules = rules))
    }

    private fun allow(rule: String) = PermissionRule.parse(RuleKind.Allow, rule, "test")!!
    private fun deny(rule: String) = PermissionRule.parse(RuleKind.Deny, rule, "test")!!

    @Test fun traitsAreWriteNonConcurrentAndNotReplayed() {
        for (tool in tools) {
            assertFalse("${tool.name} readOnly", tool.traits.readOnly)
            assertFalse("${tool.name} concurrencySafe", tool.traits.concurrencySafe)
            assertEquals("${tool.name} resume", dev.min.agent.spi.ResumePolicy.Unknown, tool.traits.resumePolicy)
            assertFalse("${tool.name} alwaysAsk", tool.traits.alwaysAsk)
        }
    }

    @Test fun defaultModeAsksForAllSeven() {
        for (tool in tools) assertEquals(tool.name, Verdict.Ask, verdict(tool, PermissionMode.Default))
    }

    @Test fun acceptEditsAndPlanStillAsk() {
        // 屏幕操作不是「工作区里的编辑」，acceptEdits 不该顺带放行
        for (tool in tools) {
            assertEquals(tool.name, Verdict.Ask, verdict(tool, PermissionMode.AcceptEdits))
            assertEquals(tool.name, Verdict.Ask, verdict(tool, PermissionMode.Plan))
        }
    }

    @Test fun bypassAllowsAndDontAskDenies() {
        for (tool in tools) {
            assertTrue(tool.name, verdict(tool, PermissionMode.BypassPermissions) is Verdict.Allow)
            assertTrue(tool.name, verdict(tool, PermissionMode.DontAsk) is Verdict.Deny)
        }
    }

    @Test fun allowRuleByToolNameLetsThatToolThroughAndOnlyThat() {
        val rules = listOf(allow("DeviceUiTree"))
        assertTrue(verdict(tool(DeviceToolKind.UiTree), PermissionMode.Default, rules) is Verdict.Allow)
        assertEquals(Verdict.Ask, verdict(tool(DeviceToolKind.Tap), PermissionMode.Default, rules))
        assertEquals(Verdict.Ask, verdict(tool(DeviceToolKind.Screenshot), PermissionMode.Default, rules))
    }

    @Test fun denyRuleWinsEvenInBypass() {
        val rules = listOf(deny("DeviceScreenshot"))
        assertTrue(verdict(tool(DeviceToolKind.Screenshot), PermissionMode.BypassPermissions, rules) is Verdict.Deny)
        assertTrue(verdict(tool(DeviceToolKind.Tap), PermissionMode.BypassPermissions, rules) is Verdict.Allow)
    }

    @Test fun suggestedAlwaysAllowRuleIsTheBareToolName() {
        for (tool in tools) {
            @Suppress("UNCHECKED_CAST")
            val typed = tool as Tool<Any>
            val input = when (tool.name) {
                "DeviceTap" -> 1
                "DeviceType" -> typed.parseRaw("index" to 1, "text" to "x").let { (it as ToolInput.Valid<*>).value!! }
                "DeviceSwipe" -> typed.parseRaw("direction" to "up").let { (it as ToolInput.Valid<*>).value!! }
                "DeviceOpenApp" -> "a.b"
                else -> Unit
            }
            val ask = typed.permissionAsk(input, TestToolContext())
            assertEquals(tool.name, PolicyEngine.suggestRule(ask, PolicyContext(PermissionMode.Default)))
        }
    }

    // ---- 失败与图片 ----

    @Test fun accessibilityOffIsAnErrorResultWithTheStepsNotAnException() {
        device.uiTree = DeviceController.Result.failure("设备操控还没打开。请到 Min 的「设置 → 设备 → 设备操控」开启，那里会告诉你怎么在系统设置里授予无障碍权限。")
        val result = tool(DeviceToolKind.UiTree).call(TestToolContext())
        assertTrue(result.isError)
        assertTrue(result.text.contains("设置 → 设备 → 设备操控"))
    }

    @Test fun anExceptionFromTheDeviceBecomesAnErrorResult() {
        device.throwOnBack = true
        val result = tool(DeviceToolKind.Back).call(TestToolContext())
        assertTrue(result.isError)
        assertTrue(result.text.contains("SecurityException"))
    }

    @Test fun screenshotGivesAnImagePartAndSavesAnAttachment() {
        val ctx = TestToolContext()
        val result = tool(DeviceToolKind.Screenshot).call(ctx)
        assertFalse(result.isError)
        val image = result.content.filterIsInstance<ImagePart>().single()
        assertEquals("image/png", image.mediaType)
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), (image.source as ImageSource.Base64).data)
        assertEquals(listOf("device/c1-screenshot.png"), ctx.artifacts.files.keys.toList())
        assertEquals(listOf<Byte>(1, 2, 3), ctx.artifacts.files.getValue("device/c1-screenshot.png").toList())
        assertTrue("附件路径写进了文字，模型以后能引用", result.text.contains("artifacts/device/c1-screenshot.png"))
        assertTrue("提示里的工具名是原生名", result.text.contains("DeviceUiTree") && !result.text.contains("device_ui_tree"))
    }

    @Test fun screenshotFailureIsATextOnlyError() {
        device.shot = DeviceController.ImageResult.failure("截不到屏。可能这个界面标了 FLAG_SECURE")
        val result = tool(DeviceToolKind.Screenshot).call(TestToolContext())
        assertTrue(result.isError)
        assertTrue(result.content.none { it is ImagePart })
        assertTrue(result.text.contains("FLAG_SECURE"))
    }

    @Test fun screenshotStillReturnsTheImageWhenTheAttachmentCannotBeSaved() {
        val ctx = object : ToolContext by TestToolContext() {
            override val artifacts = object : dev.min.agent.spi.ArtifactSink {
                override suspend fun put(name: String, bytes: ByteArray): String = error("disk full")
            }
        }
        val result = tool(DeviceToolKind.Screenshot).call(ctx)
        assertFalse(result.isError)
        assertEquals(1, result.content.count { it is ImagePart })
    }

    // ---- 开关 ----

    @Test fun gatedToolsFollowTheSwitchOnEveryRead() {
        var on = false
        val base = minTools()
        val list = minToolsWithDevice(device, { on })
        assertEquals(base.map { it.name }, list.map { it.name })
        on = true
        assertEquals(base.map { it.name } + DeviceToolKind.entries.map { it.nativeName }, list.map { it.name })
        assertEquals("设备工具排在内置工具之后", base.size, list.indexOfFirst { it.name == "DeviceUiTree" })
        assertEquals(base.size + 7, list.size)
        on = false
        assertEquals(base.size, list.size)
        assertTrue(list.none { it.name.startsWith("Device") })
    }

    @Test fun withoutADeviceTheToolListIsJustTheBuiltIns() {
        assertEquals(minTools().map { it.name }, minToolsWithDevice(null, { true }).map { it.name })
    }

    // ---- 对着真内核：审批卡、执行、结果 ----

    private fun tapCall() = replyParts(
        listOf(ToolCallPart(CallId("c1"), "DeviceTap", JsonObject(mapOf("index" to JsonPrimitive(2))))),
        StopReason.ToolUse,
    )

    private fun run(mode: PermissionMode, vararg steps: ModelStep): Pair<TestKernel, MinAgentSession> {
        val k = TestKernel(ScriptedModel(*steps), tools).also { kernel = it }
        val session = MinAgentSession(runBlocking { k.newRoot(mode = mode) }, FakeHost(k), k.scope)
        runBlocking { session.submit(SessionCommand.Send("send-1", UserInput("go"))) }
        return k to session
    }

    @Test fun inDefaultModeTheCardAppearsAndAllowingRunsTheTap() {
        val (_, session) = run(PermissionMode.Default, tapCall(), answer("done"))
        val card = awaitValue { session.state.value.interactions.firstOrNull() as? PendingInteraction.Approval }
        assertTrue("执行前设备没被碰", device.calls.isEmpty())
        runBlocking { session.submit(SessionCommand.Answer("ans-1", card.id, InteractionAnswer.Choose("allow"))) }
        runBlocking { session.runtime.awaitIdle() }
        assertEquals(listOf("tap(2)"), device.calls)
        assertEquals("已点击 [2]", (session.runtime.state.value.toolResults.getValue(CallId("c1")).content.single() as TextPart).text)
    }

    @Test fun denyingNeverTouchesTheDevice() {
        val (_, session) = run(PermissionMode.Default, tapCall(), answer("ok"))
        val card = awaitValue { session.state.value.interactions.firstOrNull() as? PendingInteraction.Approval }
        runBlocking { session.submit(SessionCommand.Answer("ans-1", card.id, InteractionAnswer.Choose("deny"))) }
        runBlocking { session.runtime.awaitIdle() }
        assertTrue(device.calls.isEmpty())
    }

    @Test fun bypassModeRunsWithoutACard() {
        val (_, session) = run(PermissionMode.BypassPermissions, tapCall(), answer("done"))
        runBlocking { session.runtime.awaitIdle() }
        assertEquals(listOf("tap(2)"), device.calls)
        assertTrue(session.state.value.interactions.isEmpty())
    }

    private companion object {
        val PROFILE = dev.min.agent.spi.HarnessProfile(dev.min.agent.spi.ModelFamily.Claude, dev.min.agent.spi.EditStyle.StrReplace)
    }
}
