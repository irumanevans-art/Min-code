package dev.min.agent.spi

/** 只有宿主租约能授予代理端口；环境变量是给客户端用的提示，不能授予网络能力。 */
sealed interface GuestNetwork {
    data object Offline : GuestNetwork
    data class ProxyOnly(val ports: List<Int>) : GuestNetwork {
        init { require(ports.isNotEmpty() && ports.size <= 8 && ports.all { it in 1..65535 }) }
    }
}

data class GuestIsolation(val writableRoots: List<GuestPath>, val network: GuestNetwork = GuestNetwork.Offline)
