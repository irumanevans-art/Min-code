package dev.min.code.core.engine.min

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class AndroidMcpTokenStoreTest {
    @get:Rule val tmp=TemporaryFolder()
    private val key=McpTokenKey("db","https://db.example/mcp")
    private fun aes()=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun credentials()=McpOAuthCredentials("https://as.example","https://as.example/a","https://as.example/t","client","FAKE_SECRET","http://127.0.0.1:1234/mcp/oauth/callback","FAKE_ACCESS","FAKE_REFRESH",123L)
    @Test fun `AES GCM private file survives reopen with fresh IV and no plaintext tokens`() = runBlocking {
        val file=tmp.newFolder().resolve("mcp-oauth.enc"); val secret=aes(); val store=AndroidMcpTokenStore(file,secret)
        store.save(key,credentials()); val first=file.readBytes()
        assertFalse(first.decodeToString().contains("FAKE_")); assertEquals(1,first[0].toInt()); assertEquals(12,first[1].toInt())
        assertEquals(credentials(),AndroidMcpTokenStore(file,secret).load(key))
        store.save(key,credentials()); assertFalse(first.contentEquals(file.readBytes()))
        assertEquals(credentials(),store.load(key)); Unit
    }
    @Test fun `endpoint and name isolate credentials and logout deletes record`() = runBlocking {
        val file=tmp.newFolder().resolve("mcp-oauth.enc"); val store=AndroidMcpTokenStore(file,aes())
        store.save(key,credentials()); assertNull(store.load(McpTokenKey("other",key.endpoint))); assertNull(store.load(McpTokenKey(key.server,"https://other.example")))
        store.delete(key); assertNull(store.load(key)); assertFalse(file.readText().contains("FAKE_")); Unit
    }
    @Test fun `wrong key and tampered ciphertext fail closed preserve file and redact cause chain`() = runBlocking {
        val file=tmp.newFolder().resolve("mcp-oauth.enc"); val secret=aes(); val store=AndroidMcpTokenStore(file,secret)
        store.save(key,credentials()); val saved=file.readBytes()
        try { AndroidMcpTokenStore(file,aes()).load(key); fail("wrong key") } catch(e: IllegalStateException) { assertFalse(generateSequence(e as Throwable) { it.cause }.joinToString().contains("FAKE_")) }
        assertArrayEquals(saved,file.readBytes())
        val damaged=saved.copyOf().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() }; file.writeBytes(damaged)
        try { store.load(key); fail("tampered") } catch(e: IllegalStateException) { assertFalse(generateSequence(e as Throwable) { it.cause }.joinToString().contains("FAKE_")) }
        assertArrayEquals(damaged,file.readBytes()); Unit
    }
    @Test fun `save with unreadable ciphertext rebuilds repository and emits only fixed reset note`() = runBlocking {
        val file=tmp.newFolder().resolve("mcp-oauth.enc");val old=aes()
        AndroidMcpTokenStore(file,old).save(key,credentials())
        val notes=mutableListOf<String>();val fresh=aes();val store=AndroidMcpTokenStore(file,fresh){notes+=it}
        store.save(key,credentials().copy(scope="new"))
        assertTrue(store.load(key)?.scope=="new");assertEquals(listOf("凭证文件无法解密，已重置"),notes)
        assertFalse(file.readBytes().decodeToString().contains("FAKE_"));Unit
    }
    @Test fun `logout deletes unreadable ciphertext without requiring decryption`() = runBlocking {
        val file=tmp.newFolder().resolve("mcp-oauth.enc")
        AndroidMcpTokenStore(file,aes()).save(key,credentials())
        AndroidMcpTokenStore(file,aes()).delete(key)
        assertFalse(file.exists());Unit
    }
    @Test fun `concurrent private store instances retain independent server registrations`() = runBlocking {
        val file=tmp.newFolder().resolve("mcp-oauth.enc"); val secret=aes()
        coroutineScope { (0..5).map { n -> async(Dispatchers.Default) { AndroidMcpTokenStore(file,secret).save(McpTokenKey("db$n",key.endpoint),credentials()) } }.awaitAll() }
        for(n in 0..5) assertNotNull(AndroidMcpTokenStore(file,secret).load(McpTokenKey("db$n",key.endpoint)))
        assertEquals(1,file.parentFile!!.listFiles()!!.size); Unit
    }
}
