package dev.min.code.core.device

import android.util.Log
import dev.min.code.core.claudecode.ClaudeCodeConfigStore
import dev.min.code.core.claudecode.MCP_TYPE_HTTP
import dev.min.code.core.settings.SettingsStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val TAG = "DeviceMcpReg"

/** 写进 CLI 配置里的服务器名。改它等于让旧配置变成一条永远连不上的僵尸记录 */
const val DEVICE_MCP_SERVER_NAME = "min-device"

/**
 * 把「设置里的开关」翻译成「server 起停 + CLI 配置增删」。
 *
 * ## 为什么每次起来都要重写配置
 *
 * [DeviceMcpServer] 每次 [DeviceMcpServer.start] 都绑一个**随机端口**、生成一个**新 token**
 * （见那边的安全说明）。所以地址不是常量，App 每次冷启动都变——配置文件里上一次的那条
 * 一定是过期的。不重写的话，CLI 读到一个连不上的地址，模型会得到一串它读不懂的连接错误，
 * 然后开始猜「是不是没权限」。
 *
 * ## 为什么在 App 启动时就起，而不是等第一次调用
 *
 * CLI 会话**启动时**读一次 MCP 配置。等模型想用了再起 server，那会儿它手里的配置
 * 已经是旧的了。所以顺序必须是：App 起来 → server 起来 → 写配置 → 用户才可能开始会话。
 *
 * ## 关掉时把配置删干净
 *
 * 关开关不只是停 server，还要把那条 `mcpServers` 记录删掉。留着的话 CLI 每次启动都会去
 * 连一个已经不在的地址，白白吃一次超时，还会在 `/mcp` 里列出一个永远 failed 的服务器。
 */
class DeviceMcpRegistrar(
    private val settings: SettingsStore,
    private val configStore: ClaudeCodeConfigStore,
    private val server: DeviceMcpServer,
) {

    /**
     * 跟着开关走。挂在 AppScope 上，进程活多久它活多久。
     *
     * 用 `distinctUntilChanged` 是因为 [SettingsStore.settings] 任何一个字段变了都会重发，
     * 而我们只关心这一个布尔——不去重的话，用户每改一次主题都会把 server 重启一遍、
     * 顺带换掉端口和 token，正在跑的会话当场失联。
     */
    suspend fun follow() {
        settings.settings
            .map { it.controlDevice }
            .distinctUntilChanged()
            .collect { enabled -> apply(enabled) }
    }

    /**
     * 让实际状态对齐开关。
     *
     * 开关开着但无障碍服务没连上时**照样起 server、照样写配置**：那时工具依然存在，
     * 只是每次调用都返回一句「设备操控还没打开，去系统设置里授权」。
     * 这比让工具凭空消失好——工具不在，模型只会以为这件事做不到；工具在但告诉它
     * 差一步授权，它就能把这句话转告给你。
     */
    suspend fun apply(enabled: Boolean) {
        if (enabled) {
            server.start()
            val endpoint = server.endpoint()
            if (endpoint == null) {
                Log.w(TAG, "server started but endpoint is null")
                return
            }
            val ok = configStore.saveMcpServer(
                ClaudeCodeConfigStore.McpServer(
                    name = DEVICE_MCP_SERVER_NAME,
                    type = MCP_TYPE_HTTP,
                    url = endpoint,
                )
            )
            // 配置没写成（rootfs 还没装、磁盘满了）时不要把 server 留着：
            // 一个连不上的 server 加一条写不进去的配置，等于什么都没有，
            // 但会让设置页显示"已开启"
            if (!ok) {
                Log.w(TAG, "failed to write mcp config, stopping server")
                server.stop()
            }
        } else {
            server.stop()
            runCatching { configStore.deleteMcpServer(DEVICE_MCP_SERVER_NAME) }
                .onFailure { Log.w(TAG, "failed to remove mcp config", it) }
        }
    }
}
