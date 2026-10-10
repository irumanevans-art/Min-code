package dev.min.agent.spi

data class PluginFetchSource(val type: String, val location: String, val ref: String? = null, val sha: String? = null, val path: String? = null, val sha256: String? = null)
data class PluginFetchLimits(val downloadBytes: Long = 32L * 1024 * 1024, val expandedBytes: Long = 64L * 1024 * 1024, val files: Int = 4096)

/** 拉取和文件树副作用留在宿主；ext 只决定来源、信任和索引，Android 的 git 仍经 proot。 */
interface PluginSourceHost {
    suspend fun fetch(source: PluginFetchSource, destination: GuestPath, limits: PluginFetchLimits)
    suspend fun copy(source: GuestPath, destination: GuestPath, limits: PluginFetchLimits)
    suspend fun remove(path: GuestPath)
    suspend fun <T> locked(block: suspend () -> T): T
}
