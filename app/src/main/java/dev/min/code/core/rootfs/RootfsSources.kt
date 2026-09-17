package dev.min.code.core.rootfs

/**
 * Rootfs 下载源。Ubuntu base 官方 cdimage 优先，国内镜像兜底（阿里云有 ubuntu-cdimage 的完整镜像，
 * 实测可下；清华/中科大对这个路径返回 403）。
 *
 * 按 CPU 架构选包：手机/平板是 arm64，模拟器是 x86_64 —— 之前写死 arm64 在模拟器上根本装不上。
 *
 * 信任模型（H1/H2 修复后）：安装时由 `RootfsInstaller` 先拉 [sumsUrl] 的 SHA256SUMS，
 * 动态解析当前 point release 的文件名（上游发 24.04.4 后这里写死的 24.04.3 不会再 404），
 * 下载完按清单校验 SHA-256；镜像源下载的包也用这份官方清单校验（内容逐字节相同）。
 * 校验失败抛 `RootfsChecksumException`，调用方不得静默换源重试。
 * [RELEASE_FILE] 现在只用于界面预填，不再需要跟着上游发版手动 bump。
 */
object RootfsSources {
    private const val RELEASE_DIR = "ubuntu-base/releases/24.04/release"
    private const val RELEASE_FILE = "ubuntu-base-24.04.3-base"

    private val ARCH: String get() = DeviceArch.debianArch

    fun defaultUrl(): String = "https://cdimage.ubuntu.com/$RELEASE_DIR/$RELEASE_FILE-$ARCH.tar.gz"

    fun mirrorUrl(): String = "https://mirrors.aliyun.com/ubuntu-cdimage/$RELEASE_DIR/$RELEASE_FILE-$ARCH.tar.gz"

    /** 官方 SHA256SUMS：安装时的校验清单（信任锚），文件名与哈希都以它为准 */
    fun sumsUrl(): String = "https://cdimage.ubuntu.com/$RELEASE_DIR/SHA256SUMS"

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
