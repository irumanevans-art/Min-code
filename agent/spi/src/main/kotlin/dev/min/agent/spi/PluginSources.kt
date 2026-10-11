package dev.min.agent.spi

data class PluginFetchSource(val type: String, val location: String, val ref: String? = null, val sha: String? = null, val path: String? = null, val sha256: String? = null,
    val version: String? = null, val registry: String? = null, val commandTimeout: Int = 60, val mode: String = "copy",
    val headers: Map<String, String> = emptyMap(), val headersHelper: String? = null, val helperEnvironment: Map<String, String> = emptyMap(),
    val scrubCredentials: Boolean = true, val inheritedAuthentication: PluginFetchSource? = null) {
    override fun toString() = "PluginFetchSource(type=$type, mode=$mode, authenticated=${headers.isNotEmpty() || headersHelper != null || inheritedAuthentication != null})"
}

/** 认证命令的输出不得经过普通 Exec 日志；独立端口只交 stdout，失败不附 stderr 或命令输出。 */
fun interface PluginProcess {
    suspend fun run(command: String, cwd: GuestPath, timeoutMs: Long, env: Map<String, String>, scrubCredentials: Boolean): String
}
data class PluginSourceTag(val ref: String, val version: String, val sha: String)

data class PluginFetchLimits(val downloadBytes: Long = 32L * 1024 * 1024, val expandedBytes: Long = 64L * 1024 * 1024, val files: Int = 4096)

/** 拉取和文件树副作用留在宿主；ext 只决定来源、信任和索引，Android 的 git 仍经 proot。 */
interface PluginSourceHost {
    suspend fun tags(source: PluginFetchSource, pluginName: String): List<PluginSourceTag> = emptyList()
    suspend fun fetch(source: PluginFetchSource, destination: GuestPath, limits: PluginFetchLimits)
    suspend fun copy(source: GuestPath, destination: GuestPath, limits: PluginFetchLimits)
    suspend fun remove(path: GuestPath)
    suspend fun <T> locked(block: suspend () -> T): T
}
