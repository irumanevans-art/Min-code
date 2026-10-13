package dev.min.agent.spi

/**
 * 「现在跑在 Windows 上吗」的唯一判据，桌面宿主与 JVM 端口实现共用，免得各处各抄一行 `os.name` 判断。
 * 每次读属性、不缓存：和原先散落的写法行为一致。要在测试里伪装系统的类仍然用自己的构造参数
 * （如 `DesktopPaths(windows = …)`），本对象只管「默认值取真实系统」。
 */
object HostOs {
    val isWindows: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Windows")
}
