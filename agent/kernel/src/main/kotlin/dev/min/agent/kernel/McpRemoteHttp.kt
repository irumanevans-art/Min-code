package dev.min.agent.kernel

import dev.min.agent.ext.mcp.McpServerSpec
import dev.min.agent.mcp.McpHttpException
import dev.min.agent.spi.*
import java.net.URI

/** helper 的凭证优先于 OAuth，401 只重跑一次 helper，不能替用户切换认证方式。 */
internal class McpRemoteHttp(
    private val delegate: HttpTransport,
    private val spec: McpServerSpec.Remote,
    private val helper: HeadersHelper,
    private val store: McpTokenStore,
) : HttpTransport by delegate {
    // helper 重取可能漏头；已选择配置认证的连接必须持续拒绝 OAuth 回退。
    @Volatile private var configuredAuthorizationSeen = false
    override suspend fun open(request: HttpRequest): HttpStreamingResponse {
        val initial = helper.headers()
        val reserved = setOf("accept","content-type","mcp-session-id","mcp-protocol-version","last-event-id")
        if (initial.any { it.first.equals("Authorization", true) }) configuredAuthorizationSeen = true
        var configured = configuredAuthorizationSeen
        fun replace(headers: List<Pair<String,String>>): List<Pair<String,String>> {
            val replaced = (initial + headers).map { it.first.lowercase() }.toSet() - reserved
            return request.headers.filterNot { it.first.lowercase() in replaced } + headers.filterNot { it.first.lowercase() in reserved }
        }
        suspend fun attempt(base: List<Pair<String,String>>): HttpStreamingResponse {
            val headers = replace(base).toMutableList()
            if (!configuredAuthorizationSeen && headers.none { it.first.equals("Authorization",true) }) {
                val saved = try { store.load(McpTokenKey(spec.name,spec.url)) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { throw IllegalStateException("MCP 凭证仓库读取失败。") }
                saved?.accessToken?.let { headers += "Authorization" to "Bearer $it" }
            }
            // 旧 SSE 可改变路径，但任何请求都不能把按端点存的令牌带到另一源。
            val a=URI(spec.url); val b=URI(request.url)
            fun port(u: URI) = if(u.port>=0) u.port else if(u.scheme=="https") 443 else 80
            if(a.scheme!=b.scheme || !a.host.equals(b.host,true) || port(a)!=port(b)) throw IllegalStateException("MCP 凭证不允许跨源发送。")
            return delegate.open(HttpRequest(request.method,request.url,headers,request.body,request.timeoutMs))
        }
        var response=attempt(initial)
        if(response.status==401 && spec.headersHelper!=null) {
            response.close()
            val fresh=helper.reload()
            configured = configured || fresh.any { it.first.equals("Authorization",true) }
            if (configured) configuredAuthorizationSeen = true
            response=attempt(fresh)
        }
        configured = configured || configuredAuthorizationSeen
        if(response.status in setOf(401,403) && configured) {
            val status=response.status; response.close()
            throw McpHttpException(status,false,"MCP 配置的 Authorization 被服务器拒绝，请修正 headers 或 headersHelper。")
        }
        return response
    }
}
