package dev.min.code.core.device

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP（Model Context Protocol）server 侧的 JSON-RPC 2.0 帧编解码。
 *
 * 这里只有纯数据结构和字符串进 / 字符串出，**不碰传输层**：不开 socket、不起服务、
 * 不读 stdin。谁把行读进来、谁把返回的行写出去，都是调用方的事。
 *
 * 目前只覆盖三个阶段：`initialize` / `tools/list` / `tools/call`。
 *
 * 两条容易踩的坑，写在这里以免被"顺手优化"掉：
 *
 * 1. **id 必须是 [JsonElement]，不能转成 String。** JSON-RPC 的 id 允许整数也允许字符串，
 *    对端拿它当回调表的键。把 `1` 写成 `"1"` 回给对端，键查不中，那个请求就在对端永远挂着，
 *    而且没有任何报错——最难查的一类 bug。所以 id 原样持有、原样回写，数字仍是数字、
 *    字符串仍是字符串（大整数也按字面量原样搬运，不经过浮点）。
 * 2. **id 为 null 表示 notification**，按 JSON-RPC 规定不该有应答。解析照常解析成
 *    [McpRequest]，是否应答由上层决定；如果上层仍然调了 encode，会写出 `"id":null`
 *    （JSON-RPC 对"无法确定 id 的应答"就是这么写的），但正常流程不该走到这一步。
 *
 * 所有 encode 都返回**单行**字符串：不 pretty print、末尾不带换行。
 */

/** MCP 协议版本。握手时原样回给客户端。 */
const val MCP_PROTOCOL_VERSION: String = "2024-11-05"

/** 本实现认识的方法名，其余一律解析成 [McpRequest.Unsupported]。 */
object McpMethod {
    const val INITIALIZE = "initialize"
    const val TOOLS_LIST = "tools/list"
    const val TOOLS_CALL = "tools/call"
}

/** JSON-RPC 2.0 标准错误码，直接拿去填 [encodeError] 的 code。 */
object McpErrorCode {
    /** 解析错误：收到的根本不是合法 JSON。 */
    const val PARSE_ERROR = -32700

    /** 方法不存在：method 不认识。 */
    const val METHOD_NOT_FOUND = -32601

    /** 参数无效：方法认识，但 params 不合法（比如 tools/call 没给工具名）。 */
    const val INVALID_PARAMS = -32602

    /** 内部错误：工具执行时炸了之类。 */
    const val INTERNAL_ERROR = -32603
}

sealed interface McpRequest {
    /** 原样持有的 JSON-RPC id；null 表示没有 id（notification，调用方不应答）。 */
    val id: JsonElement?

    data class Initialize(override val id: JsonElement?) : McpRequest

    data class ListTools(override val id: JsonElement?) : McpRequest

    data class CallTool(
        override val id: JsonElement?,
        val name: String,
        /** 缺 arguments 时是空对象而不是 null，调用方不用再判空。 */
        val arguments: JsonObject,
    ) : McpRequest

    /** 方法不认识（method 不是字符串也算）。上层回 [McpErrorCode.METHOD_NOT_FOUND]。 */
    data class Unsupported(override val id: JsonElement?, val method: String) : McpRequest
}

/** 一个可暴露给客户端的工具。inputSchema 是 JSON Schema，原样透传。 */
@Serializable
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/**
 * 解析一行 JSON-RPC 请求。
 *
 * 不是合法 JSON、顶层不是对象、或者缺 `method`（method 不是字符串也算缺）时返回 null。
 *
 * 几处刻意的宽松，写清楚免得被当成漏判：
 * - 不校验 `jsonrpc` 字段。多数客户端会带 `"2.0"`，但不带也不影响我们认方法。
 * - 不校验 id 的类型。规范只允许数字和字符串，这里不过滤、原样保留给上层——
 *   丢字段比留字段危险得多。
 * - `"id":null` 与"没有 id"一样对待，都算 notification（[McpRequest.id] 为 null）。
 * - 顶层是数组（JSON-RPC 批量请求）不支持，返回 null。
 * - `tools/call` 缺 `params.name` 时 name 是空串，**不抛异常也不用 null**：请求已经解析
 *   出来了，上层回一个 [McpErrorCode.INVALID_PARAMS] 比把这一行悄悄丢掉（对端永远挂住）好。
 */
fun parseMcpRequest(line: String): McpRequest? {
    val text = line.trim()
    if (text.isEmpty()) return null

    // 不是合法 JSON 就返回 null；用 runCatching 是因为深嵌套的输入还可能抛别的，
    // 一行坏输入不该把 server 带走
    val obj = runCatching { mcpJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null

    // JSON 里的 null 和"没有 id"在这里是同一件事：都没有可回写的 id
    val id = obj["id"]?.takeUnless { it is JsonNull }
    val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null

    return when (method) {
        McpMethod.INITIALIZE -> McpRequest.Initialize(id)
        McpMethod.TOOLS_LIST -> McpRequest.ListTools(id)
        McpMethod.TOOLS_CALL -> {
            val params = obj["params"] as? JsonObject
            McpRequest.CallTool(
                id = id,
                name = (params?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                arguments = params?.get("arguments") as? JsonObject ?: buildJsonObject { },
            )
        }
        else -> McpRequest.Unsupported(id, method)
    }
}

/**
 * initialize 的应答：
 * `{"jsonrpc":"2.0","id":…,"result":{"protocolVersion":"2024-11-05",
 * "capabilities":{"tools":{}},"serverInfo":{"name":…,"version":…}}}`
 */
fun encodeInitializeResult(id: JsonElement?, serverName: String, serverVersion: String): String =
    encodeResult(id) {
        put("protocolVersion", MCP_PROTOCOL_VERSION)
        put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
        put("serverInfo", buildJsonObject {
            put("name", serverName)
            put("version", serverVersion)
        })
    }

/**
 * tools/list 的应答：
 * `{"jsonrpc":"2.0","id":…,"result":{"tools":[{"name":…,"description":…,"inputSchema":{…}}…]}}`
 */
fun encodeToolsList(id: JsonElement?, tools: List<McpTool>): String =
    encodeResult(id) {
        put("tools", buildJsonArray {
            tools.forEach { add(mcpJson.encodeToJsonElement(McpTool.serializer(), it)) }
        })
    }

/**
 * 工具调用的返回：MCP 规定 `result.content` 是一个数组，这里只支持单条 text。
 *
 * 形状是 `{"jsonrpc":"2.0","id":…,"result":{"content":[{"type":"text","text":…}],"isError":…}}`。
 * isError 为 false 时**不输出**该字段：规范里它可选、缺省即 false，多写一个 false 会让
 * 一些严格的客户端白走一遍错误分支。
 */
fun encodeToolResult(id: JsonElement?, text: String, isError: Boolean = false): String =
    encodeResult(id) {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
        if (isError) put("isError", true)
    }

/**
 * 带一张图的返回：一条 text 说明 + 一条 image。
 *
 * MCP 的 image content 是 `{"type":"image","data":"<base64>","mimeType":"..."}`，
 * data 是**裸 base64**，不带 `data:` URI 前缀——带了的话多数客户端会当成损坏的图。
 *
 * text 放在图前面：模型先读到"这是什么、为什么给你图"，再看图。只给图的话它得自己猜
 * 这张图是哪来的、该拿它干什么。
 */
fun encodeImageResult(
    id: JsonElement?,
    text: String,
    base64: String,
    mimeType: String = "image/png",
): String =
    encodeResult(id) {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
            add(buildJsonObject {
                put("type", "image")
                put("data", base64)
                put("mimeType", mimeType)
            })
        })
    }

/** 错误应答：`{"jsonrpc":"2.0","id":…,"error":{"code":…,"message":…}}`，code 用 [McpErrorCode]。 */
fun encodeError(id: JsonElement?, code: Int, message: String): String =
    mcpJson.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("jsonrpc", JSONRPC_VERSION)
        // id 认不出来时（比如整行不是 JSON）规范要求写 "id":null，这里统一如此
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    })

// ---------------------------------------------------------------------------
// 内部
// ---------------------------------------------------------------------------

private const val JSONRPC_VERSION = "2.0"

/** prettyPrint 关掉：MCP 走逐行帧，输出必须单行、末尾不能有换行。 */
private val mcpJson = Json {
    prettyPrint = false
    encodeDefaults = true
}

/** 组装 `{"jsonrpc":"2.0","id":…,"result":{…}}`，id 原样回写。 */
private fun encodeResult(id: JsonElement?, result: JsonObjectBuilder.() -> Unit): String =
    mcpJson.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("jsonrpc", JSONRPC_VERSION)
        put("id", id ?: JsonNull)
        put("result", buildJsonObject(result))
    })
