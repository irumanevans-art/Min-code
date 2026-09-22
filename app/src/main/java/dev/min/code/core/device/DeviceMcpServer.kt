package dev.min.code.core.device

import android.util.Log
import dev.min.code.core.relay.readRequest
import dev.min.code.core.relay.writeResponse
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DeviceMcp"

/**
 * 把六个设备工具当成 MCP server 挂给 rootfs 里的 CLI。
 *
 * ## 为什么走 MCP 而不是改引擎
 *
 * Min 驱动的是**官方** CLI，不是自己写的 agent loop。想给它加一把工具，正路只有 MCP——
 * 这样 Claude Code 和 Codex 两个引擎一行协议都不用改就都能用，将来换 CLI 版本也不会
 * 因为我们动过它的内部结构而崩。
 *
 * ## 安全边界
 *
 * 1. **只绑 `127.0.0.1`**。CLI 跑在同一台设备的 proot 里，回环就够。同类开源项目
 *    （如 NeuralBridge）默认绑在设备的局域网 IP 上且不鉴权——那等于给同一个 Wi-Fi 下
 *    的任何人一把遥控你手机的钥匙。
 * 2. **路径里带一次性 token**，形如 `/mcp/{token}`。回环上仍有别的进程（rootfs 里跑的
 *    任何东西都是同一个 UID），token 让这把工具只对我们自己写进配置的那个 CLI 可见。
 *    每次 [start] 重新生成。
 * 3. 真正的闸不在这里，在审批：每一次 tap / input 都会变成 CLI 的一次工具调用，
 *    照样弹权限 sheet。这一层只负责别把入口敞给不该进来的人。
 *
 * ## 传输选择
 *
 * MCP 的 streamable HTTP：POST 一个 JSON-RPC 请求、回一个 JSON-RPC 响应。
 * 不上 SSE / 长连接——六个工具全是「问一次答一次」，没有服务端主动推送的需要。
 *
 * HTTP 原语复用 `core/relay/HttpWire.kt`（[LocalRelayServer] 也用它），不引第三方框架。
 */
class DeviceMcpServer(private val controller: DeviceController) {

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var boundPort: Int = -1
    private var token: String = ""

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "min-device-mcp").apply { isDaemon = true }
    }

    /** 写进 CLI 的 MCP 配置里的那个地址。没起来时为 null */
    fun endpoint(): String? =
        if (running.get() && boundPort > 0) "http://127.0.0.1:$boundPort/mcp/$token" else null

    @Synchronized
    fun start() {
        if (running.get()) return
        token = UUID.randomUUID().toString().replace("-", "")
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = ss
        boundPort = ss.localPort
        running.set(true)
        pool.execute {
            while (running.get()) {
                try {
                    val socket = ss.accept()
                    pool.execute { handle(socket) }
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed", e)
                }
            }
        }
        Log.i(TAG, "listening on 127.0.0.1:$boundPort")
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { server?.close() }
        server = null
        boundPort = -1
        token = ""
        Log.i(TAG, "stopped")
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        var output: BufferedOutputStream? = null
        try {
            val input = BufferedInputStream(socket.getInputStream())
            output = BufferedOutputStream(socket.getOutputStream())
            val req = readRequest(input) ?: run {
                writeResponse(output, 400, "text/plain", "bad request".toByteArray())
                return
            }
            // token 对不上一律 404 而不是 401：401 等于告诉对方「这里确实有个东西，
            // 只是你没资格」，404 什么都不承认
            if (req.path.trim('/') != "mcp/$token" || token.isEmpty()) {
                writeResponse(output, 404, "text/plain", "not found".toByteArray())
                return
            }
            if (req.method != "POST") {
                writeResponse(output, 405, "text/plain", "method not allowed".toByteArray())
                return
            }
            val line = req.body.toString(Charsets.UTF_8)
            val response = dispatch(line)
            if (response == null) {
                // notification（没有 id），按 JSON-RPC 不应答
                writeResponse(output, 204, "application/json", ByteArray(0))
            } else {
                writeResponse(output, 200, "application/json", response.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle failed", e)
            // **必须回点什么**。工具实现里任何一个没接住的异常（真机上第一次就撞上了：
            // takeScreenshot 直接抛 SecurityException）走到这里，如果只记一行日志就关掉
            // socket，CLI 那头拿到的是一个空连接——它看不出是超时、崩溃还是协议不对，
            // 只能干等或报一句没头没尾的连接错误。回一条 JSON-RPC 错误，至少模型知道
            // 这次调用失败了、可以换条路
            output?.let { out ->
                runCatching {
                    writeResponse(
                        out,
                        200,
                        "application/json",
                        encodeError(null, McpErrorCode.INTERNAL_ERROR, "tool crashed: ${e.javaClass.simpleName}")
                            .toByteArray(Charsets.UTF_8),
                    )
                }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    /** @return 要回的那行 JSON-RPC；notification 时返回 null */
    private fun dispatch(line: String): String? {
        val request = parseMcpRequest(line)
            ?: return encodeError(null, McpErrorCode.PARSE_ERROR, "invalid json-rpc")
        if (request.id == null) return null
        return when (request) {
            is McpRequest.Initialize ->
                encodeInitializeResult(request.id, SERVER_NAME, SERVER_VERSION)

            is McpRequest.ListTools -> encodeToolsList(request.id, DEVICE_TOOLS)

            is McpRequest.CallTool -> callTool(request)

            is McpRequest.Unsupported ->
                encodeError(request.id, McpErrorCode.METHOD_NOT_FOUND, "unknown method: ${request.method}")
        }
    }

    private fun callTool(request: McpRequest.CallTool): String {
        val args = request.arguments
        fun int(key: String): Int? = runCatching { args[key]?.jsonPrimitive?.content?.toInt() }.getOrNull()
        fun str(key: String): String? = runCatching { args[key]?.jsonPrimitive?.content }.getOrNull()

        // 截图走单独的返回形状（text + image 两条 content），不走下面那套纯文本
        if (request.name == "device_screenshot") {
            val shot = controller.screenshot()
            return if (shot.base64 == null) {
                encodeToolResult(request.id, shot.text, isError = true)
            } else {
                encodeImageResult(request.id, shot.text, shot.base64)
            }
        }

        val result = when (request.name) {
            "device_ui_tree" -> controller.uiTree()

            "device_tap" -> int("index")
                ?.let { controller.tap(it) }
                ?: missing("index")

            "device_input" -> {
                val index = int("index")
                val text = str("text")
                if (index == null) missing("index")
                else if (text == null) missing("text")
                else controller.input(index, text)
            }

            "device_swipe" -> str("direction")
                ?.let { controller.swipe(it, int("index")) }
                ?: missing("direction")

            "device_open_app" -> str("package_name")
                ?.let { controller.openApp(it) }
                ?: missing("package_name")

            "device_back" -> controller.back()

            else -> return encodeError(
                request.id,
                McpErrorCode.METHOD_NOT_FOUND,
                "unknown tool: ${request.name}",
            )
        }
        // 工具**执行**出错走 isError 而不是 JSON-RPC error：后者是协议层面的失败
        //（方法不存在、参数缺失），会让 CLI 认为这次调用根本没发生；而「点不动那个按钮」
        // 是一次正常完成、结果为失败的调用，模型需要读到那句话才知道下一步怎么办
        return encodeToolResult(request.id, result.text, isError = result.isError)
    }

    private fun missing(key: String) = DeviceController.Result.failure("缺少参数 $key")

    private companion object {
        const val SERVER_NAME = "min-device"
        const val SERVER_VERSION = "1"
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
