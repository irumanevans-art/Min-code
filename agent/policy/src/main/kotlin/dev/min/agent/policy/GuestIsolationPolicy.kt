package dev.min.agent.policy

import dev.min.agent.spi.*

object GuestIsolationPolicy {
    private val roots = listOf("/workspace", "/tmp", "/var/tmp", "/root/.cache", "/root/.npm", "/root/.min/exec-logs", "/dev/shm").map(::GuestPath)
    /**
     * [extraWritable]（[dev.min.agent.spi.ShellCommand.extraWritable]）只收 hook 流程的两类目录（[isGrantable]），别的路径直接拒绝：
     * 这是宿主按内核给的清单放行，模型的命令拿不到；清单里混进别的目录说明调用方写错了，宁可让这次执行失败也不悄悄放宽沙箱
     */
    fun forCommand(network: GuestNetwork = GuestNetwork.Offline, extraWritable: List<GuestPath> = emptyList()): GuestIsolation {
        require(extraWritable.all(::isGrantable)) { "只有 hook 的批目录与 CLAUDE_ENV_FILE 目录能额外放行写入：" + extraWritable.filterNot(::isGrantable) }
        return GuestIsolation(roots + extraWritable.distinct(), network)
    }

    /** hook 的 staging 根（批目录、后台 hook 目录都在它下面） */
    val hookStagingRoot = GuestPath("/root/.min/hooks")
    private val envFileDir = Regex("/root/\\.min/artifacts/[A-Za-z0-9_-]+/env")

    /** 能额外放行写入的目录：hook staging 根及其下，或某个会话附件目录下的 `env`（`CLAUDE_ENV_FILE` 住的地方）。路径里不许有 `.` / `..` / 空段 */
    fun isGrantable(path: GuestPath): Boolean {
        val value = path.value
        if (value.split('/').drop(1).any { it.isEmpty() || it == "." || it == ".." }) return false
        return value == hookStagingRoot.value || value.startsWith(hookStagingRoot.value + "/") || envFileDir.matches(value)
    }
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
