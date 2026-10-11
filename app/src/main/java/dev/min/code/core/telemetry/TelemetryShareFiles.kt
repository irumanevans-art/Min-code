package dev.min.code.core.telemetry

import java.io.File

/**
 * 「导出到文件」之后的分享：本机 JSONL 在 App 私有目录里，要给别的应用看，得经 FileProvider 给出 URI。
 *
 * 为什么拷一份进缓存目录再分享，而不是直接分享原文件：原文件还在被后续导出追加，对方拿着 URI 读到一半文件变长不是好事；
 * 缓存里的拷贝是这一刻的快照。和工作区分享、诊断包导出是同一种写法（缓存目录的专用子目录，先清空，不长期占空间）。
 * 这里只做文件那一半（纯 JVM、可单测）；URI 与分享面板由界面层接（见 ui/settings/TelemetryShare.kt）。
 */
object TelemetryShareFiles {
    /** 缓存目录下的专用子目录（FileProvider 的 cache-path 已把 cache 整个暴露出去） */
    const val SHARE_DIR = "telemetry-share"

    /** 返回缓存里的快照；还没导出过（没有文件或是空的）返回 null */
    fun prepare(source: File, cacheDir: File, nowMs: Long): File? {
        if (!source.isFile || source.length() == 0L) return null
        val dir = File(cacheDir, SHARE_DIR).apply {
            listFiles()?.forEach { it.deleteRecursively() }
            mkdirs()
        }
        return File(dir, "min-telemetry-$nowMs.jsonl").also { source.copyTo(it, overwrite = true) }
    }
}
