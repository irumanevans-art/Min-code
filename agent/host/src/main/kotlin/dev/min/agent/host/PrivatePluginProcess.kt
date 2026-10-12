package dev.min.agent.host

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/** 输出只留有界内存，连失败 cause 也不携带令牌；宿主注入 launch/reap，Android 仍走 proot。 */
class PrivatePluginProcess(
    private val launch: (String, GuestPath, Map<String, String>) -> Process,
    private val reap: (Process) -> Unit,
    private val environment: () -> Map<String, String> = System::getenv,
) : PluginProcess {
    override suspend fun run(command: String, cwd: GuestPath, timeoutMs: Long, env: Map<String, String>, scrubCredentials: Boolean): String = coroutineScope {
        val safeEnv = environment().filterKeys { !scrubCredentials || !credentialName(it) } + env
        val launched = java.util.concurrent.atomic.AtomicReference<Process>()
        val process = try { withContext(Dispatchers.IO) { launch(command, cwd, safeEnv).also(launched::set) } }
            catch(e: CancellationException) { withContext(NonCancellable + Dispatchers.IO) { launched.get()?.let(reap) }; throw e }
            catch(_: Exception) { error("插件私有进程无法启动") }
        try {
            process.outputStream.close()
            val stdout = async(Dispatchers.IO) {
                process.inputStream.use { input ->
                    val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer); if (size < 0) break
                        require(bytes.size() + size <= 64 * 1024) { "插件私有进程输出超限" }
                        bytes.write(buffer, 0, size)
                    }
                    bytes.toString("UTF-8")
                }
            }
            val stderr = async(Dispatchers.IO) { process.errorStream.use { input -> val buffer = ByteArray(8192); while (input.read(buffer) >= 0) { /* stderr 不进入日志或异常。 */ } } }
            try {
                val result = withTimeoutOrNull(timeoutMs) {
                    val code = runInterruptible(Dispatchers.IO) { process.waitFor() }
                    require(code == 0) { "插件私有进程失败（退出码 $code）" }
                    val text = stdout.await(); stderr.await(); text
                }
                result ?: error("插件私有进程超时")
            } finally { stdout.cancel(); stderr.cancel() }
        } finally { withContext(NonCancellable + Dispatchers.IO) { reap(process); process.inputStream.close(); process.errorStream.close() } }
    }
    companion object {
        fun credentialName(name: String) = Regex("(?i)(TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|API_?KEY|AUTH)").containsMatchIn(name)
        fun validateCommand(command: String) {
            require(command.isNotBlank() && command.length <= 500 && command.all { it.code in 32..126 } && "    " !in command) { "插件命令须为不超过 500 字符的可打印 ASCII，不能连续四空格" }
        }
    }
}
