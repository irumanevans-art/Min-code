package dev.min.code.core.engine.min

import android.content.Context
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.min.agent.kernel.LoopbackMcpAuthorizer
import dev.min.agent.spi.*
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
internal class AndroidMcpAuthorizer(private val context: Context, private val gate: McpLoginGate = McpLoginGate()) : McpAuthorizer {
    // 授权域名与 scope 先让人看到、点了「继续」才开浏览器（内核先 preview 再 authorize，见 McpLoginGate）
    override fun preview(issuer: String,authorizationDomain: String,scopes: String?) = gate.preview(issuer,authorizationDomain,scopes)
    override suspend fun authorize(redirectPort: Int?,expectedState: String,authorizationUrl: suspend (String)->String): String {
        gate.confirm()
        return LoopbackMcpAuthorizer(openPage={url -> withContext(Dispatchers.Main){context.openMcpCustomTab(url)}},expectedState=expectedState).authorize(redirectPort,authorizationUrl)
    }
    override suspend fun authorize(redirectPort: Int?,authorizationUrl: suspend (String)->String): String {
        gate.confirm()
        return LoopbackMcpAuthorizer(openPage={ url -> withContext(Dispatchers.Main) { context.openMcpCustomTab(url) } })
            .authorize(redirectPort,authorizationUrl)
    }
}

/** 普通浏览器出口允许回退，OAuth 只选声明 Custom Tabs 服务的外部提供者。 */
internal fun mcpCustomTabPackage(candidates: List<String>,defaultPackage: String?,self: String,supports: (String)->Boolean): String? {
    val available=candidates.filter { it!=self }.distinct().filter(supports)
    return defaultPackage?.takeIf { it in available } ?: available.firstOrNull()
}

private fun Context.openMcpCustomTab(url: String): Boolean {
    val intent=Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    val candidates=packageManager.queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
    val preferred=packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    // 既有 https BROWSABLE queries 已让这些浏览器包可见；逐包检查服务，不扩大 App 可见性。
    val provider=mcpCustomTabPackage(candidates,preferred,packageName) { candidate ->
        packageManager.resolveService(Intent("android.support.customtabs.action.CustomTabsService").setPackage(candidate),0)
            ?.serviceInfo?.let { it.exported && it.packageName==candidate } == true
    } ?: return false
    intent.setPackage(provider).putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION",null) })
    if(this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try { startActivity(intent); true }
    catch(e: ActivityNotFoundException) { false }
    catch(e: SecurityException) { false }
}

/** 只在 App 私有、不备份的目录存密文；rootfs 挂载表从未拿到这里的路径。 */
internal class AndroidMcpTokenStore private constructor(private val file: File,private val keyProvider: ()->SecretKey,private val resetNote: (String)->Unit = {}) : McpTokenStore {
    constructor(context: Context) : this(File(context.noBackupFilesDir,"mcp-oauth.enc"),::keystoreKey,{ android.util.Log.w("MinMcpOAuth",it) })
    internal constructor(file: File,key: SecretKey,resetNote: (String)->Unit = {}) : this(file,{key},resetNote)
    private val lock=locks.computeIfAbsent(file.absolutePath) { Mutex() }
    private val serializer=ListSerializer(McpTokenRecord.serializer())
    override suspend fun load(key: McpTokenKey): McpOAuthCredentials? = access { read().firstOrNull { it.key==key }?.credentials }
    override suspend fun save(key: McpTokenKey,credentials: McpOAuthCredentials): Unit = access {
        var reset=false
        val records=try {read()} catch(e: UnreadableCredentials){reset=true;emptyList()}
        write(records.filterNot { it.key==key }+McpTokenRecord(key,credentials))
        if(reset) resetNote("凭证文件无法解密，已重置")
    }
    override suspend fun delete(key: McpTokenKey): Unit = access {
        // 旧密钥失效时不能先要求解密，否则用户永远无法退出登录。
        val records=try {read()} catch(e: UnreadableCredentials){Files.deleteIfExists(file.toPath());return@access}
        write(records.filterNot {it.key==key})
    }
    private suspend fun <T> access(block: ()->T): T = lock.withLock { withContext(Dispatchers.IO) {
        try { block() }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { throw IllegalStateException("MCP 私有凭证仓库不可用，密文已保留。") }
    } }
    private fun read(): List<McpTokenRecord> {
        if(!file.exists()) return emptyList()
        val bytes=file.readBytes()
        if(bytes.size<30 || bytes[0].toInt()!=1 || bytes[1].toInt()!=12) throw UnreadableCredentials()
        try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,keyProvider(),GCMParameterSpec(128,bytes.copyOfRange(2,14)))
            cipher.updateAAD(AAD)
            val plain=cipher.doFinal(bytes.copyOfRange(14,bytes.size))
            return try { Json.decodeFromString(serializer,plain.decodeToString()) } finally { plain.fill(0) }
        } catch(e: java.security.GeneralSecurityException){throw UnreadableCredentials()}
        catch(e: kotlinx.serialization.SerializationException){throw UnreadableCredentials()}
    }
    private class UnreadableCredentials : Exception()
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
