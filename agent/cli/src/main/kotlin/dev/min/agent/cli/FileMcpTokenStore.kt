package dev.min.agent.cli

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.util.concurrent.ConcurrentHashMap

/** 独立于会话/rootfs；先收紧权限，再写凭证，原子替换前的临时文件同样私有。 */
internal class FileMcpTokenStore(private val file: Path) : McpTokenStore {
    private val lock=locks.computeIfAbsent(file.toAbsolutePath().normalize()) { Mutex() }
    private val serializer=ListSerializer(McpTokenRecord.serializer())
    override suspend fun load(key: McpTokenKey): McpOAuthCredentials? = access { read().firstOrNull { it.key==key }?.credentials }
    override suspend fun save(key: McpTokenKey, credentials: McpOAuthCredentials): Unit = access {
        cleanupTemps()
        val records=read().filterNot { it.key==key }+McpTokenRecord(key,credentials)
        write(records)
    }
    override suspend fun delete(key: McpTokenKey): Unit = access { cleanupTemps(); write(read().filterNot { it.key==key }) }
    private suspend fun <T> access(block: () -> T): T = lock.withLock { withContext(Dispatchers.IO) {
        try {
            Files.createDirectories(file.toAbsolutePath().parent)
            val lockFile=file.resolveSibling("${file.fileName}.lock")
            if(Files.isSymbolicLink(file) || Files.isSymbolicLink(lockFile)) throw IllegalStateException()
            FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE).use { channel ->
                restrict(lockFile)
                channel.lock().use { block() }
            }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { throw IllegalStateException("MCP 凭证仓库读写失败，请检查私有文件权限。") }
    } }
    private fun read(): List<McpTokenRecord> {
        if(!Files.exists(file)) return emptyList()
        restrict(file)
        return Json.decodeFromString(serializer,Files.readString(file))
    }
    private fun write(records: List<McpTokenRecord>) {
        val temp=Files.createTempFile(file.toAbsolutePath().parent,".mcp-oauth-",".tmp")
        try {
            restrict(temp)
            val bytes=Json.encodeToString(serializer,records).toByteArray()
            FileChannel.open(temp,StandardOpenOption.WRITE).use { channel ->
                val data=ByteBuffer.wrap(bytes); while(data.hasRemaining()) channel.write(data); channel.force(true)
            }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
    private fun cleanupTemps() {
        // 必须持有仓库的跨进程锁，避免把另一写入者尚未发布的明文临时文件删掉。
        Files.newDirectoryStream(file.toAbsolutePath().parent,".mcp-oauth-*.tmp").use { temps ->
            for(temp in temps) if(!Files.isDirectory(temp,LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(temp)
        }
    }
    private fun restrict(path: Path) {
        val posix=Files.getFileAttributeView(path,PosixFileAttributeView::class.java)
        if(posix!=null) { posix.setPermissions(setOf(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE)); return }
        val acl=Files.getFileAttributeView(path,AclFileAttributeView::class.java) ?: throw IllegalStateException()
        val user=path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        acl.acl=listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user).setPermissions(AclEntryPermission.values().toSet()).build())
    }
    companion object { private val locks=ConcurrentHashMap<Path,Mutex>() }
}
