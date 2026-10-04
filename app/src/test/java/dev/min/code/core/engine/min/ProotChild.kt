package dev.min.code.core.engine.min

import java.io.File

/**
 * [ProotProcessPortTest] 里代替 proot 的子进程（同一份测试类路径上的另一个 JVM）。第一个参数选行为：
 * - echo：把 stdin 原样抄到 stdout，stdin 关闭后正常退出
 * - stubborn：起一个孙进程并打印它的 pid，忽略 stdin 关闭，永远睡下去
 */
object ProotChild {
    /**
     * 起本类的命令行。Android 单测的类路径长到超过 Windows 的命令行上限，所以经 `java @argfile` 传
     * （argfile 里路径统一用正斜杠，免得反斜杠被当转义）。
     */
    fun command(mode: String): List<String> {
        val java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
        val argFile = File.createTempFile("proot-child", ".args").apply { deleteOnExit() }
        argFile.writeText("-cp\n\"" + System.getProperty("java.class.path").replace('\\', '/') + "\"\n")
        return listOf(java, "@" + argFile.absolutePath.replace('\\', '/'), ProotChild::class.java.name, mode)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        when (args[0]) {
            "echo" -> {
                val buf = ByteArray(8192)
                while (true) {
                    val n = System.`in`.read(buf)
                    if (n < 0) break
                    System.out.write(buf, 0, n)
                    System.out.flush()
                }
            }
            "stubborn" -> {
                val grandchild = ProcessBuilder(command("sleeper")).inheritIO().start()
                println("grandchild=${Process::class.java.getMethod("pid").invoke(grandchild)}")
                System.out.flush()
                Thread.sleep(600_000)
            }
            "sleeper" -> Thread.sleep(600_000)
        }
    }
}
