package dev.min.agent.host

import java.io.File

/**
 * [JvmLiveProcessTest] 起的子进程（同一份测试类路径上的另一个 JVM）。第一个参数选行为：
 * - echo：把 stdin 原样抄到 stdout，同时往 stderr 写一行，stdin 关闭后以退出码 3 结束
 * - stubborn：起一个同样不肯退的孙进程并打印它的 pid，忽略 stdin 关闭，永远睡下去
 * - burst：往 stdout 一口气写 200000 字节后退出
 */
object LiveChild {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args[0]) {
            "echo" -> {
                System.err.println("child-stderr")
                System.err.flush()
                val buf = ByteArray(8192)
                while (true) {
                    val n = System.`in`.read(buf)
                    if (n < 0) break
                    System.out.write(buf, 0, n)
                    System.out.flush()
                }
                System.exit(3)
            }
            "stubborn" -> {
                val java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
                val grandchild = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), LiveChild::class.java.name, "sleeper")
                    .inheritIO().start()
                println("grandchild=${grandchild.pid()}")
                System.out.flush()
                Thread.sleep(600_000)
            }
            "sleeper" -> Thread.sleep(600_000)
            "burst" -> {
                System.out.write(ByteArray(200_000) { 'x'.code.toByte() })
                System.out.flush()
            }
        }
    }
}
