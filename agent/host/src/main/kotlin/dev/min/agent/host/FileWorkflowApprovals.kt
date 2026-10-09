package dev.min.agent.host

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkflowApprovals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** 宿主私有目录保存同意，工作区脚本不能给自己添加授权。 */
class FileWorkflowApprovals(private val directory: File) : WorkflowApprovals {
    private fun key(cwd: GuestPath, hash: String): String {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return MessageDigest.getInstance("SHA-256").digest("${cwd.value}\u0000$hash".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    override fun contains(cwd: GuestPath, hash: String) = File(directory,key(cwd,hash)).isFile
    override fun autoConsented() = File(directory,"auto-consent").isFile
    private suspend fun save(name: String) = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        val file=File(directory,name)
        check(file.isFile || file.createNewFile()); Unit
    }
    override suspend fun approve(cwd: GuestPath, hash: String) = save(key(cwd,hash))
    override suspend fun consentAuto() = save("auto-consent")
    override suspend fun revoke(cwd: GuestPath, hash: String) = withContext(Dispatchers.IO) {
        java.nio.file.Files.deleteIfExists(File(directory,key(cwd,hash)).toPath()); Unit
    }
    override suspend fun revokeAuto() = withContext(Dispatchers.IO) {
        java.nio.file.Files.deleteIfExists(File(directory,"auto-consent").toPath()); Unit
    }
}
