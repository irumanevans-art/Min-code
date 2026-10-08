package dev.min.code.core.engine.min

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkflowApprovals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 私有 App 目录保存授权，项目 JS 无法通过修改工作区文件替自己批准。 */
class MinWorkflowApprovals(private val directory: File) : WorkflowApprovals {
    private val hashes = ConcurrentHashMap.newKeySet<String>()
    init { directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}")) }?.forEach { hashes += it.name } }
    private fun key(cwd: GuestPath, hash: String): String {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return MessageDigest.getInstance("SHA-256").digest("${cwd.value}\u0000$hash".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    override fun contains(cwd: GuestPath, hash: String) = key(cwd, hash) in hashes
    override suspend fun approve(cwd: GuestPath, hash: String) = withContext(Dispatchers.IO) {
        val id = key(cwd, hash)
        check(directory.isDirectory || directory.mkdirs())
        // 单文件授权没有部分状态，零字节标记的成功创建就是持久事实。
        val file = File(directory, id)
        check(file.isFile || file.createNewFile())
        hashes += id
        Unit
    }
}
