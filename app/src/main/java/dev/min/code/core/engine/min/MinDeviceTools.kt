package dev.min.code.core.engine.min

import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ArtifactNames
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.catchingNonCancellation
import dev.min.code.core.device.DeviceActions
import dev.min.code.core.device.DeviceController
import dev.min.code.core.device.DeviceToolKind
import dev.min.code.core.device.DeviceToolNames
import dev.min.code.core.device.deviceToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/*
 * 设备工具的 Min Agent 原生版（设计 02 §总体架构、04 §手机原生）：内核直接调 [DeviceActions]，不经 MCP-over-HTTP；
 * [dev.min.code.core.device.DeviceMcpServer] 原样留给 Claude Code 引擎。
 *
 * - 描述与入参 schema 取自 `DeviceTools.kt` 的同一份定义（名字换成大驼峰），不在这里再写一遍；
 * - 七个都 `readOnly = false`：屏幕内容是用户的隐私，动作落在真手机上，默认模式要问人；
 *   用户可以用 `allow: ["DeviceUiTree"]` 这类规则放行，bypass 模式照常放行；
 * - 不能并发（两个手势叠在一起没有意义），进程被杀后不重放（点过的按钮再点一次可能是另一个操作）。
 *   重放策略 [dev.min.agent.spi.ResumePolicy] 取默认的 Unknown：恢复时合成「执行被中断，结果未知」。
 */

/** 七个设备工具，按 [DeviceToolKind] 的声明顺序（工具说明是稳定前缀的一部分，换序会让提示缓存失效） */
fun deviceTools(actions: DeviceActions): List<Tool<*>> = listOf(
    UiTreeTool(actions), TapTool(actions), TypeTool(actions), SwipeTool(actions),
    OpenAppTool(actions), ScreenshotTool(actions), BackTool(actions),
)

/**
 * 工具表里设备工具随设置开关进出：开关关着就不进表（模型看不到，也就不会去试）。
 *
 * 内核的 [dev.min.agent.kernel.KernelDeps.tools] 一个进程只建一次，而用户随时可能在设置里改开关，
 * 所以这个表每次被读都现取开关——下一个回合起生效（含已经开着的会话；工具表是稳定前缀的一部分，
 * 这一回合的缓存会因此断一次）。每次读只取一份快照，迭代中途开关翻转不会让长度对不上。
 */
internal class DeviceGatedTools(
    private val base: List<Tool<*>>,
    private val device: List<Tool<*>>,
    private val enabled: () -> Boolean,
) : AbstractList<Tool<*>>() {
    private fun current(): List<Tool<*>> = if (enabled()) base + device else base

    override val size: Int get() = current().size
    override fun get(index: Int): Tool<*> = current()[index]
    override fun iterator(): Iterator<Tool<*>> = current().iterator()
    override fun listIterator(): ListIterator<Tool<*>> = current().listIterator()
    override fun listIterator(index: Int): ListIterator<Tool<*>> = current().listIterator(index)
}

/** 内置工具之后接设备工具；没有设备（桌面、测试）就只有内置工具 */
fun minToolsWithDevice(devices: DeviceActions?, enabled: () -> Boolean, base: List<Tool<*>> = minTools()): List<Tool<*>> =
    if (devices == null) base else DeviceGatedTools(base, deviceTools(devices), enabled)

// ---- 共用骨架 ----

private abstract class DeviceTool<I : Any>(private val kind: DeviceToolKind) : Tool<I> {
    private val definition = deviceToolDefinition(kind)
    private val text = DeviceToolNames.toNative(definition.description)

    override val name: String = kind.nativeName
    override val inputSchema: JsonObject = DeviceToolNames.toNative(definition.inputSchema) as JsonObject
    override val traits = ToolTraits(readOnly = false, concurrencySafe = false)

    override fun description(profile: HarnessProfile): String = text

    /** 执行本体。阻塞调用已由 [run] 放到 IO 线程；这里可以直接调 [DeviceActions] */
    protected abstract suspend fun execute(input: I, ctx: ToolContext): ToolResult

    final override fun run(input: I, ctx: ToolContext): Flow<ToolProgress> = flow {
        // 工具结果要么成功要么错误结果，不抛：无障碍回调、截屏权限在真机上都出过没接住的异常（见 DeviceMcpServer 的注释）。
        // 取消照常往外传
        val result = try {
            withContext(Dispatchers.IO) { execute(input, ctx) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure("设备操作出错了（${e.javaClass.simpleName}）：${e.message.orEmpty().take(200)}。可以重新读一次屏幕再试。")
        }
        emit(ToolProgress.Done(result))
    }

    protected fun ask(summary: String) = PermissionAsk(tool = name, summary = summary)

    protected fun DeviceController.Result.toToolResult(): ToolResult =
        ToolResult(listOf(TextPart(DeviceToolNames.toNative(text))), isError = isError)
}

private fun failure(text: String) = ToolResult(listOf(TextPart(text)), isError = true)

// ---- 入参提取：错误信息写给模型看，说清哪个字段、该是什么样 ----

private class BadArg(message: String) : Exception(message)

private inline fun <I> parseArgs(block: () -> I): ToolInput<I> =
    try {
        ToolInput.Valid(block())
    } catch (e: BadArg) {
        ToolInput.Invalid(e.message ?: "入参不合法")
    }

private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

private fun JsonObject.requireString(key: String): String {
    val primitive = present(key) as? JsonPrimitive ?: throw BadArg("缺少必填字段 `$key`（字符串）。")
    if (!primitive.isString) throw BadArg("字段 `$key` 必须是字符串。")
    return primitive.content
}

/** 模型偶尔把数字写成 "3"，宽容接受；小数、负数、非数字才拒绝 */
private fun JsonObject.optionalIndex(key: String): Int? {
    val element = present(key) ?: return null
    val primitive = element as? JsonPrimitive ?: throw BadArg(indexHint(key))
    return primitive.content.trim().toIntOrNull()?.takeIf { it >= 0 } ?: throw BadArg(indexHint(key))
}

private fun JsonObject.requireIndex(key: String): Int =
    optionalIndex(key) ?: throw BadArg("缺少必填字段 `$key`（整数，取自最近一次 ${DeviceToolKind.UiTree.nativeName} 的输出）。")

private fun indexHint(key: String) = "字段 `$key` 必须是非负整数（取自最近一次 ${DeviceToolKind.UiTree.nativeName} 的输出）。"

/** 审批卡片上的一行说明里不放长文本：输入的内容可能很长，也可能是隐私 */
private fun String.clipForCard(max: Int = 60): String = if (length <= max) this else take(max) + "…"

// ---- 七个工具 ----

private class UiTreeTool(private val actions: DeviceActions) : DeviceTool<Unit>(DeviceToolKind.UiTree) {
    override fun parse(raw: JsonObject): ToolInput<Unit> = ToolInput.Valid(Unit)
    override fun permissionAsk(input: Unit, ctx: ToolContext) = ask("读取当前屏幕的内容（界面元素列表）")
    override suspend fun execute(input: Unit, ctx: ToolContext) = actions.uiTree().toToolResult()
}

private class TapTool(private val actions: DeviceActions) : DeviceTool<Int>(DeviceToolKind.Tap) {
    override fun parse(raw: JsonObject): ToolInput<Int> = parseArgs { raw.requireIndex("index") }
    override fun permissionAsk(input: Int, ctx: ToolContext) = ask("点击屏幕上的元素 [$input]")
    override suspend fun execute(input: Int, ctx: ToolContext) = actions.tap(input).toToolResult()
}

private data class TypeInput(val index: Int, val text: String)

private class TypeTool(private val actions: DeviceActions) : DeviceTool<TypeInput>(DeviceToolKind.Input) {
    override fun parse(raw: JsonObject): ToolInput<TypeInput> =
        parseArgs { TypeInput(raw.requireIndex("index"), raw.requireString("text")) }

    override fun permissionAsk(input: TypeInput, ctx: ToolContext) =
        ask("在屏幕元素 [${input.index}] 里输入：${input.text.clipForCard()}")

    override suspend fun execute(input: TypeInput, ctx: ToolContext) = actions.input(input.index, input.text).toToolResult()
}

private data class SwipeInput(val direction: String, val index: Int?)

private class SwipeTool(private val actions: DeviceActions) : DeviceTool<SwipeInput>(DeviceToolKind.Swipe) {
    override fun parse(raw: JsonObject): ToolInput<SwipeInput> = parseArgs {
        val direction = raw.requireString("direction").trim().lowercase()
        if (direction !in DIRECTIONS) throw BadArg("字段 `direction` 只能是 up / down / left / right，收到 `$direction`。")
        SwipeInput(direction, raw.optionalIndex("index"))
    }

    override fun permissionAsk(input: SwipeInput, ctx: ToolContext) =
        ask("滑动屏幕（内容向 ${input.direction}）" + (input.index?.let { "，在元素 [$it] 上" } ?: ""))

    override suspend fun execute(input: SwipeInput, ctx: ToolContext) = actions.swipe(input.direction, input.index).toToolResult()

    private companion object {
        val DIRECTIONS = setOf("up", "down", "left", "right")
    }
}

private class OpenAppTool(private val actions: DeviceActions) : DeviceTool<String>(DeviceToolKind.OpenApp) {
    override fun parse(raw: JsonObject): ToolInput<String> = parseArgs {
        raw.requireString("package_name").trim().ifEmpty { throw BadArg("字段 `package_name` 不能为空。") }
    }

    override fun permissionAsk(input: String, ctx: ToolContext) = ask("打开应用 $input")
    override suspend fun execute(input: String, ctx: ToolContext) = actions.openApp(input).toToolResult()
}

private class BackTool(private val actions: DeviceActions) : DeviceTool<Unit>(DeviceToolKind.Back) {
    override fun parse(raw: JsonObject): ToolInput<Unit> = ToolInput.Valid(Unit)
    override fun permissionAsk(input: Unit, ctx: ToolContext) = ask("按系统返回键")
    override suspend fun execute(input: Unit, ctx: ToolContext) = actions.back().toToolResult()
}

/**
 * 截屏。图片以 [ImagePart] 内联给模型（模型不支持图片时由内核在组请求处统一降级成文字，这里不用管），
 * 同时存一份会话附件：用户要另存、模型要在后面的回合里引用都靠这个路径。
 */
private class ScreenshotTool(private val actions: DeviceActions) : DeviceTool<Unit>(DeviceToolKind.Screenshot) {
    override fun parse(raw: JsonObject): ToolInput<Unit> = ToolInput.Valid(Unit)
    override fun permissionAsk(input: Unit, ctx: ToolContext) = ask("截取当前屏幕")

    override suspend fun execute(input: Unit, ctx: ToolContext): ToolResult {
        val shot = actions.screenshot()
        val base64 = shot.base64
        if (base64 == null || shot.isError) return failure(DeviceToolNames.toNative(shot.text))

        val parts = ArrayList<Part>()
        val note = StringBuilder(DeviceToolNames.toNative(shot.text))
        val bytes = catchingNonCancellation { Base64.getDecoder().decode(base64) }.getOrNull()
        if (bytes != null) {
            val saved = catchingNonCancellation { ctx.artifacts.put("device/${ArtifactNames.segment(ctx.callId.value)}-screenshot.png", bytes) }
            saved.getOrNull()?.let { note.append("\n[截图原图在 $it]") }
        }
        parts += TextPart(note.toString())
        parts += ImagePart("image/png", ImageSource.Base64(base64))
        return ToolResult(parts)
    }
}
