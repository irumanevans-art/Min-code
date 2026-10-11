package dev.min.agent.policy

import dev.min.agent.spi.*

object GuestIsolationPolicy {
    private val roots = listOf("/workspace", "/tmp", "/var/tmp", "/root/.cache", "/root/.npm", "/root/.min/exec-logs", "/dev/shm").map(::GuestPath)
    fun forCommand(network: GuestNetwork = GuestNetwork.Offline): GuestIsolation = GuestIsolation(roots, network)
    fun canWrite(policy: GuestIsolation, canonical: GuestPath): Boolean = policy.writableRoots.any { root ->
        canonical.value == root.value || canonical.value.startsWith(root.value.trimEnd('/') + "/")
    }

    /** 从宿主创建的租约读端口，不把这一步放在执行器里，防止客户端 env 自行放权。 */
    fun proxyNetwork(environment: Map<String, String>): GuestNetwork.ProxyOnly {
        val ports = listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY").mapNotNull { environment[it] }.map { value ->
            val uri = try { java.net.URI(value) } catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("代理租约地址无效") }
            require(uri.scheme in setOf("http", "https", "socks5", "socks5h") && uri.host?.removeSurrounding("[", "]") in setOf("127.0.0.1", "::1") && uri.port in 1..65535) { "代理租约必须使用回环地址及显式端口" }
            uri.port
        }.distinct()
        return GuestNetwork.ProxyOnly(ports)
    }
}
