package dev.min.agent.kernel

import dev.min.agent.spi.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class CredentialSearchTest {
    @get:Rule val tmp=TemporaryFolder()
    @Test fun `recursive searches omit credential contents paths counts and context`() = runBlocking {
        val root=tmp.newFolder().toPath()
        Files.createDirectories(root.resolve("root/.min-agent"))
        Files.writeString(root.resolve("root/.min-agent/mcp-oauth.json"),"FAKE_ACCESS\nordinary\nFAKE_REFRESH")
        Files.writeString(root.resolve("root/.min-agent/.mcp-oauth-old.tmp"),"FAKE_ACCESS")
        Files.writeString(root.resolve("root/notes.txt"),"ordinary")
        // 通过内核搜索边界，不能依赖单个工具的渲染恰好丢弃命中。
        val delegate=object : Search {
            override fun grep(query: GrepQuery)=flowOf(GrepHit(GuestPath("/root/.min-agent/mcp-oauth.json"),1,"FAKE_ACCESS"),GrepHit(GuestPath("/root/notes.txt"),1,"ordinary"))
            override fun glob(query: GlobQuery)=flowOf(GuestPath("/root/.min-agent/.mcp-oauth-old.tmp"),GuestPath("/root/notes.txt"))
        }
        val search=credentialSearch(delegate,setOf(GuestPath("/root/.min-agent/mcp-oauth.json*"),GuestPath("/root/.min-agent/.mcp-oauth-*"))) { it }
        val hits=search.grep(GrepQuery(".",GuestPath("/root"),contextLines=2,maxHits=1)).toList()
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.path.value=="/root/notes.txt" && !it.text.contains("FAKE_") })
        val files=search.glob(GlobQuery("**/*",GuestPath("/root"),1)).toList()
        assertEquals(listOf(GuestPath("/root/notes.txt")),files)
        Unit
    }
}
