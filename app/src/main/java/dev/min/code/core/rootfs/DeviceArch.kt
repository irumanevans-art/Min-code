package dev.min.code.core.rootfs

import android.os.Build

/**
 * 本机的**主** ABI。
 *
 * 必须取 `SUPPORTED_ABIS[0]` 而不是"列表里有没有 arm64"：x86_64 的模拟器（Android 11+ 带 ARM 翻译层）
 * 会把 arm64-v8a 也列进去，按"有没有"判就会给 x86 机器下 arm64 的 rootfs 和 Node，
 * proot 一跑就是 SIGILL（"terminated with signal 4"）。真机上两种判法结果相同。
 */
object DeviceArch {
    val primaryAbi: String get() = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    val isArm64: Boolean get() = primaryAbi.equals("arm64-v8a", ignoreCase = true)
    val isX64: Boolean get() = primaryAbi.equals("x86_64", ignoreCase = true)

    /** Node.js / npm 平台包用的后缀 */
    val nodeArch: String
        get() = when {
            isArm64 -> "linux-arm64"
            isX64 -> "linux-x64"
            else -> error("不支持的 CPU 架构: ${Build.SUPPORTED_ABIS.joinToString()}")
        }

    /** Ubuntu 发行包用的后缀 */
    val debianArch: String
        get() = when {
            isArm64 -> "arm64"
            isX64 -> "amd64"
            else -> error("不支持的 CPU 架构: ${Build.SUPPORTED_ABIS.joinToString()}")
        }
}
