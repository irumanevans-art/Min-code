package dev.min.agent.spi

/**
 * POSIX shell 单引号转义的唯一实现：各宿主拼脚本时共用，免得每处各抄一份、写法慢慢分叉。
 * 单引号内什么都不展开（`$`、反引号、换行、反斜杠都是字面量），唯一要处理的是单引号本身：
 * 先收口、再写一个被反斜杠转义的引号、再重新开口，即 `'\''`。
 */
object ShellQuote {
    fun posix(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
