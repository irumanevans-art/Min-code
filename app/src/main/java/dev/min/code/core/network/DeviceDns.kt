package dev.min.code.core.network

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService

/**
 * 设备当前 DNS。终端 prepare 以前私有实现；会话 / 本地服务 proot 也该用同一份，
 * 否则 guest 只看得到公共 1.1.1.1，连公司内网域名会失败。
 */
fun Context.activeDnsServers(): List<String> {
    val cm = applicationContext.getSystemService<ConnectivityManager>() ?: return emptyList()
    val network = cm.activeNetwork ?: return emptyList()
    return cm.getLinkProperties(network)
        ?.dnsServers
        ?.mapNotNull { it.hostAddress?.substringBefore('%') }
        ?.filter { it.isNotBlank() }
        .orEmpty()
}
