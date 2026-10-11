package dev.min.agent.kernel

/** 设置文件监视（ConfigChange）：下一个提交里实现；这里先占位，让 [FileWatches] 的轮询路径完整 */
internal class ConfigWatch(@Suppress("unused") private val rt: SessionRuntime) {
    suspend fun baseline() {}
    suspend fun poll(@Suppress("UNUSED_PARAMETER") driver: HookDriver) {}
}
