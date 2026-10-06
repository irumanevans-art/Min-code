package dev.min.agent.cli

import dev.min.agent.kernel.LoopbackMcpAuthorizer
import dev.min.agent.spi.McpAuthorizer
import kotlinx.coroutines.CancellationException
import java.awt.Desktop
import java.io.BufferedReader
import java.io.PrintStream
import java.net.URI

internal class DesktopMcpAuthorizer(var noBrowser: Boolean) : McpAuthorizer {
    var input: BufferedReader? = null
    var output: PrintStream = System.err
    private val pending=StringBuilder()
    override suspend fun authorize(redirectPort: Int?,authorizationUrl: suspend (String)->String): String {
        pending.clear()
        return LoopbackMcpAuthorizer(
            openPage={ url ->
                if(noBrowser || noLocalBrowser()) false else try {
                    if(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { Desktop.getDesktop().browse(URI(url)); true } else false
                } catch(e: Exception) { false }
            },
            manualUrl={ url -> output.println("请在浏览器打开：${terminalText(url)}\n授权后粘贴完整回调 URL（/skip 取消）；回调内容不会发送给模型。") },
            pollCallback={
                val reader=input ?: throw IllegalStateException("MCP 手工登录需要交互终端。")
                var line: String? = null
                while(reader.ready() && line==null) {
                    val c=reader.read()
                    if(c<0) throw CancellationException("MCP 登录已取消。")
                    if(c==10) { line=pending.toString().trim(); pending.clear() }
                    else if(c!=13) {
                        if(pending.length>=16_384) throw IllegalStateException("MCP 回调 URL 超过长度上限。")
                        pending.append(c.toChar())
                    }
                }
                if(line=="/skip") throw CancellationException("MCP 登录已取消。")
                line
            },
        ).authorize(redirectPort,authorizationUrl)
    }
    private fun noLocalBrowser() = System.getenv("CI")=="true" || System.getenv("SSH_CONNECTION")!=null ||
        (System.getProperty("os.name").startsWith("Linux") && System.getenv("DISPLAY")==null && System.getenv("WAYLAND_DISPLAY")==null)
}
