package dev.min.agent.spi

import kotlinx.serialization.Serializable

/** 按端点隔离，改名或改 URL 都不能借用另一台服务器的令牌。 */
@Serializable
data class McpTokenKey(val server: String, val endpoint: String) {
    override fun toString() = "McpTokenKey(***)"
}

/** 只供宿主的私有凭证仓库序列化，永远不是 SessionEvent。 */
@Serializable
data class McpOAuthCredentials(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAtMs: Long? = null,
    val scope: String? = null,
    val tokenEndpointAuthMethod: String = "none",
) {
    override fun toString() = "McpOAuthCredentials(***)"
}

interface McpTokenStore {
    suspend fun load(key: McpTokenKey): McpOAuthCredentials?
    suspend fun save(key: McpTokenKey, credentials: McpOAuthCredentials)
    suspend fun delete(key: McpTokenKey)

    object Unavailable : McpTokenStore {
        override suspend fun load(key: McpTokenKey): McpOAuthCredentials? = null
        override suspend fun save(key: McpTokenKey, credentials: McpOAuthCredentials): Unit =
            throw IllegalStateException("这个宿主没有 MCP 凭证仓库。")
        override suspend fun delete(key: McpTokenKey) {}
    }
}

/** 先绑定 loopback 端口，再构造包含真实 redirect_uri 的授权 URL，避免先挑端口再绑定的竞态。 */
fun interface McpAuthorizer {
    suspend fun authorize(redirectPort: Int?, authorizationUrl: suspend (redirectUri: String) -> String): String

    companion object {
        val Unavailable = McpAuthorizer { _, _ -> throw IllegalStateException("这个宿主还不能打开 MCP 授权页。") }
    }
}
