package dev.min.agent.cli

import dev.min.agent.kernel.SessionRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface McpCommand {
    data object List : McpCommand
    data class Login(val server: String,val noBrowser: Boolean) : McpCommand
    data class Logout(val server: String) : McpCommand
    data object Invalid : McpCommand
}
internal fun parseMcpCommand(raw: String): McpCommand {
    val tokens=raw.trim().split(Regex("\\s+"))
    if(tokens==listOf("/mcp")) return McpCommand.List
    if(tokens.size<3 || tokens[0]!="/mcp" || !tokens[2].matches(Regex("[A-Za-z0-9_-]+"))) return McpCommand.Invalid
    return when(tokens[1]) {
        "login" -> if(tokens.size==3 || (tokens.size==4 && tokens[3]=="--no-browser")) McpCommand.Login(tokens[2],tokens.size==4) else McpCommand.Invalid
        "logout" -> if(tokens.size==3) McpCommand.Logout(tokens[2]) else McpCommand.Invalid
        else -> McpCommand.Invalid
    }
}
internal suspend fun mcpCommand(app: DesktopApp,runtime: SessionRuntime,line: String) {
    try {
        when(val command=parseMcpCommand(line)) {
            McpCommand.List -> app.hub().mcpServers(runtime.sessionId).forEach { System.err.println(terminalText("${it.name}: ${it.state}${it.reason?.let { reason -> "，$reason" }.orEmpty()}")) }
            is McpCommand.Login -> { app.loginMcp(command.server,runtime,command.noBrowser); System.err.println("MCP 登录完成，工具表将在下一回合更新。") }
            is McpCommand.Logout -> { app.logoutMcp(command.server,runtime); System.err.println("MCP 已退出登录。") }
            McpCommand.Invalid -> System.err.println("用法：/mcp login <服务器名> [--no-browser] 或 /mcp logout <服务器名>。")
        }
    } catch(e: CancellationException) { currentCoroutineContext().ensureActive(); System.err.println("MCP 登录已取消。") }
    catch(e: Exception) { System.err.println("MCP 登录/退出操作未完成，请检查服务器状态和配置。") }
}
