package dev.min.code.core.persist

import java.io.File

/**
 * 原子写文本：先写同目录的 `${name}.tmp`，再 rename 到目标。
 *
 * 直接覆盖的话，写到一半被杀（前台服务超时、OOM）会留下一个截断的 JSON，
 * 下次启动 CLI 直接起不来。配置 / 会话元数据 / 两份输入草稿 / runtime docs
 * 的落盘都走这一份实现。
 *
 * rename 偶发失败时退化为覆盖写：不再原子，但好过让这次写入整笔丢掉；
 * 临时文件用完即删。失败以异常上抛，调用方按自己的对外语义接
 * （runCatching 转 Boolean，或放行）。
 */
internal fun File.atomicWriteText(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) {
        tmp.copyTo(this, overwrite = true)
        tmp.delete()
    }
}
