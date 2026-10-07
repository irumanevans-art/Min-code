package dev.min.agent.cli

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.StringReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.*
import java.net.URI

class McpOAuthHostTest {
    @get:Rule val tmp=TemporaryFolder()
    private val key=McpTokenKey("db","https://db.example/mcp")
    private fun credentials()=McpOAuthCredentials("https://as.example","https://as.example/a","https://as.example/t","client","FAKE_SECRET","http://127.0.0.1:1234/mcp/oauth/callback","FAKE_ACCESS","FAKE_REFRESH",123L)
    @Test fun `file credentials survive reopen and are bound to name and endpoint`() = runBlocking {
        val dir=tmp.newFolder().toPath(); val file=dir.resolve("mcp-oauth.json")
        val store=FileMcpTokenStore(file); store.save(key,credentials())
        assertEquals(credentials(),FileMcpTokenStore(file).load(key))
        assertNull(store.load(McpTokenKey("other",key.endpoint))); assertNull(store.load(McpTokenKey(key.server,"https://other.example/mcp")))
        store.delete(key); assertNull(FileMcpTokenStore(file).load(key))
        assertEquals(0,Files.list(dir).use { it.filter { p -> p.fileName.toString().endsWith(".tmp") }.count().toInt() })
        Unit
    }
    @Test fun `credential file and lock permit only current user`() = runBlocking {
        val file=tmp.newFolder().toPath().resolve("mcp-oauth.json"); FileMcpTokenStore(file).save(key,credentials())
        for(path in listOf(file,file.resolveSibling("mcp-oauth.json.lock"))) {
            val posix=Files.getFileAttributeView(path,PosixFileAttributeView::class.java)
            if(posix!=null) assertEquals(setOf(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE),posix.readAttributes().permissions())
            else {
                val acl=Files.getFileAttributeView(path,AclFileAttributeView::class.java).acl
                assertEquals(1,acl.size); assertEquals(AclEntryType.ALLOW,acl.single().type())
                val user=path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
                assertEquals(user,acl.single().principal()); assertTrue(AclEntryPermission.WRITE_DATA in acl.single().permissions())
            }
        }; Unit
    }
    @Test fun `concurrent store instances retain records and corrupted files fail closed without leaking`() = runBlocking {
        val file=tmp.newFolder().toPath().resolve("mcp-oauth.json")
        coroutineScope { (0..9).map { n -> async(Dispatchers.Default) { FileMcpTokenStore(file).save(McpTokenKey("s$n",key.endpoint),credentials()) } }.awaitAll() }
        for(n in 0..9) assertNotNull(FileMcpTokenStore(file).load(McpTokenKey("s$n",key.endpoint)))
        Files.writeString(file,"FAKE_SECRET invalid json")
        try { FileMcpTokenStore(file).load(key); fail("corrupt store") } catch(e: IllegalStateException) { assertFalse(generateSequence(e as Throwable) { it.cause }.joinToString().contains("FAKE_SECRET")) }
        assertTrue(Files.readString(file)=="FAKE_SECRET invalid json"); Unit
    }
    @Test fun `manual browser mode accepts callback without displaying code or verifier`() = runBlocking {
        var browsed=false
        val output=ByteArrayOutputStream(); val host=DesktopMcpAuthorizer(true,browser={browsed=true;false},browserAvailable={true})
        val requested=java.net.ServerSocket(0).use { it.localPort }
        host.output=PrintStream(output); host.input=StringReader("http://127.0.0.1:$requested/mcp/oauth/callback?code=FAKE_CODE\n").buffered()
        var port=0
        val result=host.authorize(requested) { port=URI(it).port; "https://as.example/authorize?code_challenge=proof&state=s" }
        assertEquals(requested,port); assertFalse(browsed); assertTrue(result.contains("FAKE_CODE")); assertFalse(output.toString().contains("FAKE_CODE")); assertTrue(output.toString().contains("粘贴完整回调"))
        assertTrue(output.toString().contains("https://as.example/authorize")); Unit
    }
    @Test fun `manual skip cancels and CLI supports login logout and no browser flag`() = runBlocking {
        val host=DesktopMcpAuthorizer(true,browser={false},browserAvailable={true}); host.output=PrintStream(ByteArrayOutputStream()); host.input=StringReader("/skip\n").buffered()
        try { host.authorize(null) { "https://as.example/a" }; fail("skip") } catch(e: CancellationException) { }
        assertEquals(McpCommand.Login("db",true),parseMcpCommand("/mcp login db --no-browser"))
        assertEquals(McpCommand.Login("db",false),parseMcpCommand("/mcp login db"))
        assertEquals(McpCommand.Logout("db"),parseMcpCommand("/mcp logout db"))
        assertEquals(McpCommand.Invalid,parseMcpCommand("/mcp login")); assertEquals(McpCommand.Invalid,parseMcpCommand("/mcp logout db --no-browser"))
        assertTrue(parseArgs(arrayOf("--model","test","--no-browser"),Path.of(".")).noBrowser); Unit
    }
}
