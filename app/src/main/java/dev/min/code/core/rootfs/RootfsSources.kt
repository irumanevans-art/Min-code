package dev.min.code.core.rootfs

/**
 * Rootfs 下载源。Ubuntu base 官方 cdimage 优先，国内镜像兜底（阿里云有 ubuntu-cdimage 的完整镜像，
 * 实测可下；清华/中科大对这个路径返回 403）。
 *
 * 按 CPU 架构选包：手机/平板是 arm64，模拟器是 x86_64 —— 之前写死 arm64 在模拟器上根本装不上。
 * Rootfs 没有校验和可以写死（cdimage 的 SHA256SUMS 随 point release 变），下载完靠 tar 能不能
 * 解开 + `bin/sh` 在不在判断；它是公开发行版镜像，信任模型和 apt 本身一样。
 */
object RootfsSources {
    private const val RELEASE_DIR = "ubuntu-base/releases/24.04/release"
    private const val RELEASE_FILE = "ubuntu-base-24.04.3-base"

    private val ARCH: String get() = DeviceArch.debianArch

    fun defaultUrl(): String = "https://cdimage.ubuntu.com/$RELEASE_DIR/$RELEASE_FILE-$ARCH.tar.gz"

    fun mirrorUrl(): String = "https://mirrors.aliyun.com/ubuntu-cdimage/$RELEASE_DIR/$RELEASE_FILE-$ARCH.tar.gz"

    /** 官方在前，镜像兜底 */
    fun candidates(): List<String> = listOf(defaultUrl(), mirrorUrl())

    /**
     * 实际去下的地址列表。
     *
     * - 空 / 未填：官方再镜像，和向导点「下载并安装」一样
     * - 填的就是官方预填地址：同样走官方再镜像（文件页对话框默认就是这个）
     * - 其余手填：只用这一份，不擅自换源
     */
    fun urlsFor(userUrl: String?): List<String> = resolveUrls(userUrl, defaultUrl(), mirrorUrl())
}

/** 纯函数，单测不碰 [DeviceArch]。 */
internal fun resolveUrls(userUrl: String?, official: String, mirror: String): List<String> {
    val trimmed = userUrl?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed == official) return listOf(official, mirror)
    return listOf(trimmed)
}
