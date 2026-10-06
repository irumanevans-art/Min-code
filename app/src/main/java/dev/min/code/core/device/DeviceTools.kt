package dev.min.code.core.device

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 七个设备工具的两套名字：MCP 通道（Claude Code 引擎，经 [DeviceMcpServer]）用 snake_case，
 * Min Agent 的原生工具（`core/engine/min/MinDeviceTools.kt`）用大驼峰、统一 `Device` 前缀。
 *
 * 描述、入参 schema 与执行结果里的文字只在 [DEVICE_TOOLS] 与 [DeviceController] 里写**一份**，写的是 MCP 名字；
 * 原生通道经 [DeviceToolNames.toNative] 把文字里提到的工具名换成自己的名字，所以两边的措辞不会各改各的。
 * 为什么不是模板：这些文字里到处在互相引用（「先 device_ui_tree 再 device_tap」），
 * 用占位符拼一遍既难读，也容易漏掉 [DeviceController] 里的失败提示——换名放在出口，一处管全部。
 */
enum class DeviceToolKind(val mcpName: String, val nativeName: String) {
    UiTree("device_ui_tree", "DeviceUiTree"),
    Tap("device_tap", "DeviceTap"),
    Input("device_input", "DeviceType"),
    Swipe("device_swipe", "DeviceSwipe"),
    OpenApp("device_open_app", "DeviceOpenApp"),
    Screenshot("device_screenshot", "DeviceScreenshot"),
    Back("device_back", "DeviceBack"),
}

object DeviceToolNames {
    /** 把文字里的 MCP 工具名换成原生名字。任何一个 MCP 名都不是另一个的前缀，顺序换不会出错 */
    fun toNative(text: String): String =
        DeviceToolKind.entries.fold(text) { acc, kind -> acc.replace(kind.mcpName, kind.nativeName) }

    /** schema 里所有字符串值（description、enum 项）过 [toNative]；键名不动 */
    fun toNative(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { toNative(it.value) })
        is JsonArray -> JsonArray(element.map(::toNative))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(toNative(element.content)) else element
    }
}

/** [DEVICE_TOOLS] 里 [kind] 对应的那条（MCP 名字、MCP 措辞的原始定义） */
fun deviceToolDefinition(kind: DeviceToolKind): McpTool =
    DEVICE_TOOLS.first { it.name == kind.mcpName }

/**
 * 暴露给 MCP 客户端的设备操作工具 schema；也是 Min Agent 原生工具描述与入参 schema 的唯一来源。
 *
 * 这个文件只有定义，没有执行逻辑；handler 按 [McpTool.name] 分发。三个带 `index` 的工具
 * （device_tap / device_input / device_swipe）的 description 都反复强调索引 freshness，
 * 因为模型最常见的错误就是复用上一次 UI 树里的旧索引。
 */
private const val INDEX_FRESHNESS_RULE =
    " Prerequisite: the index must come from the most recent device_ui_tree output; indices " +
        "become invalid after the screen changes, so call device_ui_tree again before acting."

val DEVICE_TOOLS: List<McpTool> = listOf(
    McpTool(
        name = "device_ui_tree",
        description = "Read the current screen's interactive element list. Each element is " +
            "reported with the index accepted by device_tap, device_input, and device_swipe. " +
            "Use this before any UI action and after every screen change, because indices " +
            "from an older tree may no longer point at the same element. Prerequisite: device " +
            "UI access is available and the screen is ready for interaction.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
            put("required", buildJsonArray {})
        },
    ),
    McpTool(
        name = "device_tap",
        description = "Tap the interactive element at the given index. Use this to activate a " +
            "button, link, list item, or other tappable element that is visible on the current " +
            "screen." + INDEX_FRESHNESS_RULE,
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("index", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Index of the target element in the most recent device_ui_tree output.",
                    )
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("index")) })
        },
    ),
    McpTool(
        name = "device_input",
        description = "Type text into the editable field at the given index. Use this to fill a " +
            "text field, search box, or other input control on the current screen; the target " +
            "must be an editable field present in the device_ui_tree output." +
            INDEX_FRESHNESS_RULE,
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("index", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Index of the target input field in the most recent device_ui_tree output.",
                    )
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to enter into the target field.")
                })
            })
            put("required", buildJsonArray {
                add(JsonPrimitive("index"))
                add(JsonPrimitive("text"))
            })
        },
    ),
    McpTool(
        name = "device_swipe",
        description = "Scroll content in the given direction. direction is where the CONTENT " +
            "should move: down = reveal items below (finger moves up), up = reveal items above. " +
            "Each swipe only moves about one third of the region — it will NOT fling to the " +
            "end; call it again to keep scrolling. Prefer passing index of a scrollable list " +
            "element: many OEM Settings lists ignore whole-screen swipes and only respond when " +
            "the gesture starts on the list itself. If the tree is unchanged after a swipe, " +
            "try the opposite direction once before concluding the region is not scrollable." +
            INDEX_FRESHNESS_RULE,
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("direction", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Where the CONTENT should move. down = reveal lower items; " +
                            "up = reveal upper items. Finger motion is the opposite.",
                    )
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("up"))
                        add(JsonPrimitive("down"))
                        add(JsonPrimitive("left"))
                        add(JsonPrimitive("right"))
                    })
                })
                put("index", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Index of a scrollable element from the most recent device_ui_tree. " +
                            "Strongly preferred over omitting it — whole-screen swipes often " +
                            "do nothing on OEM Settings / system lists.",
                    )
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("direction")) })
        },
    ),
    McpTool(
        name = "device_open_app",
        description = "Open an installed app by its Android package name. Use this to launch or " +
            "switch to an app when you know its package name and need to leave the current app. " +
            "Prerequisite: package_name must be a non-empty Android package name for an app " +
            "installed on the device.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Android package name of the app to open.")
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("package_name")) })
        },
    ),
    McpTool(
        name = "device_screenshot",
        description = "Capture the current screen as an image. This is a FALLBACK for when " +
            "device_ui_tree returns nothing useful - some screens (games, charts, canvas-drawn " +
            "UIs, certain WebViews) are invisible to the accessibility tree and show up as an " +
            "empty or near-empty element list. Prefer device_ui_tree: it is far cheaper in " +
            "tokens and its indices are what device_tap needs. A screenshot gives you pixels " +
            "only - there are no indices in it, so you cannot tap from a screenshot directly. " +
            "Use it to understand what is on screen, then go back to device_ui_tree. " +
            "Fails on screens marked FLAG_SECURE (banking apps, password fields).",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
            put("required", buildJsonArray { })
        },
    ),
    McpTool(
        name = "device_back",
        description = "Press the system back button. Use this to return to the previous screen, " +
            "dismiss a dialog, or close an on-screen menu or keyboard when the current UI " +
            "supports back navigation. Prerequisite: the foreground screen has a back stack or " +
            "an active back handler; do not use this when no back action is available.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
            put("required", buildJsonArray {})
        },
    ),
)