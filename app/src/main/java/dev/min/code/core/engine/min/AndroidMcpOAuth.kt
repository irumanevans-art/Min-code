package dev.min.code.core.engine.min

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.min.agent.kernel.LoopbackMcpAuthorizer
import dev.min.agent.spi.*
import dev.min.code.ui.components.openInExternalBrowser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 不经 proot 或 WebView；Custom Tab 与回调监听均在 App 进程中。 */
internal class AndroidMcpAuthorizer(private val context: Context) : McpAuthorizer {
    override suspend fun authorize(redirectPort: Int?,authorizationUrl: suspend (String)->String): String =
        LoopbackMcpAuthorizer(openPage={ url -> withContext(Dispatchers.Main) { context.openInExternalBrowser(url) } })
            .authorize(redirectPort,authorizationUrl)
}

/** 只在 App 私有、不备份的目录存密文；rootfs 挂载表从未拿到这里的路径。 */
internal class AndroidMcpTokenStore private constructor(private val file: File,private val keyProvider: ()->SecretKey) : McpTokenStore {
    constructor(context: Context) : this(File(context.noBackupFilesDir,"mcp-oauth.enc"),::keystoreKey)
    internal constructor(file: File,key: SecretKey) : this(file,{key})
    private val lock=locks.computeIfAbsent(file.absolutePath) { Mutex() }
    private val serializer=ListSerializer(McpTokenRecord.serializer())
    override suspend fun load(key: McpTokenKey): McpOAuthCredentials? = access { read().firstOrNull { it.key==key }?.credentials }
    override suspend fun save(key: McpTokenKey,credentials: McpOAuthCredentials): Unit = access { write(read().filterNot { it.key==key }+McpTokenRecord(key,credentials)) }
    override suspend fun delete(key: McpTokenKey): Unit = access { write(read().filterNot { it.key==key }) }
    private suspend fun <T> access(block: ()->T): T = lock.withLock { withContext(Dispatchers.IO) {
        try { block() }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { throw IllegalStateException("MCP 私有凭证仓库不可用，密文已保留。") }
    } }
    private fun read(): List<McpTokenRecord> {
        if(!file.exists()) return emptyList()
        val bytes=file.readBytes()
        if(bytes.size<30 || bytes[0].toInt()!=1 || bytes[1].toInt()!=12) throw IllegalStateException()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,keyProvider(),GCMParameterSpec(128,bytes.copyOfRange(2,14)))
        cipher.updateAAD(AAD)
        val plain=cipher.doFinal(bytes.copyOfRange(14,bytes.size))
        return try { Json.decodeFromString(serializer,plain.decodeToString()) } finally { plain.fill(0) }
    }
    private fun write(records: List<McpTokenRecord>) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,keyProvider()); cipher.updateAAD(AAD)
        val plain=Json.encodeToString(serializer,records).toByteArray()
        val encrypted=try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val iv=cipher.iv
        if(iv.size!=12) throw IllegalStateException()
        val bytes=byteArrayOf(1,12)+iv+encrypted
        file.parentFile!!.mkdirs()
        val temp=Files.createTempFile(file.parentFile!!.toPath(),".mcp-oauth-",".tmp")
        try {
            FileChannel.open(temp,StandardOpenOption.WRITE).use { channel -> val data=ByteBuffer.wrap(bytes); while(data.hasRemaining()) channel.write(data); channel.force(true) }
            Files.move(temp,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
    companion object {
        private val locks=ConcurrentHashMap<String,Mutex>()
        private val AAD="min:mcp-oauth:v1".toByteArray()
        private val keyLock=Any()
        private fun keystoreKey(): SecretKey = synchronized(keyLock) {
            val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val alias="min_mcp_oauth_v1"
            (store.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    }
}
