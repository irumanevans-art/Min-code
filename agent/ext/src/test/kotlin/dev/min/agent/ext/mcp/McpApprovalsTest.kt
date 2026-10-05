package dev.min.agent.ext.mcp

import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.WorkspaceFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 可写的内存文件系统（config 测试里的 InMemoryFs 故意禁止写，批准写回需要真写） */
private class WritableFs : WorkspaceFs {
    val files = mutableMapOf<String, ByteArray>()

    override suspend fun stat(path: GuestPath): FileStat? =
        files[path.value]?.let { FileStat(FileKind.File, it.size.toLong(), 0) }

    override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray {
        val content = files.getValue(path.value)
        val from = offset.toInt().coerceAtMost(content.size)
        return content.copyOfRange(from, minOf(content.size.toLong(), from.toLong() + limit).toInt())
    }

    override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) {
        files[path.value] = bytes
    }

    override suspend fun list(dir: GuestPath): List<DirEntry> = emptyList()

    override fun scopeOf(path: GuestPath): PathScope = PathScope.Rootfs
}

/**
 * [McpApprovals]：批准 / 拒绝写回 `.claude/settings.local.json`——去重、保序、不覆盖坏文件（规则 7）。
 */
class McpApprovalsTest {
    private fun writtenJson(fs: WritableFs): JsonObject =
        Json.parseToJsonElement(String(fs.files.getValue("/workspace/.claude/settings.local.json"), Charsets.UTF_8)).jsonObject

    @Test
    fun `approve creates the file and the claude directory when missing`() = runBlocking {
        val fs = WritableFs()
        val result = McpApprovals.approve(fs, GuestPath("/workspace"), "github")
        assertTrue(result.isSuccess)
        val obj = writtenJson(fs)
        assertEquals(listOf("github"), obj.getValue("enabledMcpjsonServers").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `approve keeps other keys and their order, dedupes, and appends at the end`() = runBlocking {
        val fs = WritableFs()
        fs.files["/workspace/.claude/settings.local.json"] = """
            {"model": "sonnet", "enabledMcpjsonServers": ["memory", "github"], "permissions": {"allow": ["Bash"]}}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val result = McpApprovals.approve(fs, GuestPath("/workspace"), "github")
        assertTrue(result.isSuccess)
        val text = String(fs.files.getValue("/workspace/.claude/settings.local.json"), Charsets.UTF_8)
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals(listOf("model", "enabledMcpjsonServers", "permissions"), obj.keys.toList())
        assertEquals(listOf("memory", "github"), obj.getValue("enabledMcpjsonServers").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("sonnet", obj.getValue("model").jsonPrimitive.content)

        // 新名字追加在清单末尾
        McpApprovals.approve(fs, GuestPath("/workspace"), "linear")
        val after = Json.parseToJsonElement(String(fs.files.getValue("/workspace/.claude/settings.local.json"), Charsets.UTF_8)).jsonObject
        assertEquals(listOf("memory", "github", "linear"), after.getValue("enabledMcpjsonServers").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `reject writes the same file's disabledMcpjsonServers`() = runBlocking {
        val fs = WritableFs()
        val result = McpApprovals.reject(fs, GuestPath("/workspace"), "sketchy")
        assertTrue(result.isSuccess)
        val obj = writtenJson(fs)
        assertEquals(listOf("sketchy"), obj.getValue("disabledMcpjsonServers").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a file that is not a valid json object fails and is left untouched`() = runBlocking {
        val fs = WritableFs()
        val original = "this is { not json".toByteArray(Charsets.UTF_8)
        fs.files["/workspace/.claude/settings.local.json"] = original
        val result = McpApprovals.approve(fs, GuestPath("/workspace"), "github")
        assertTrue(result.isFailure)
        assertTrue(fs.files.getValue("/workspace/.claude/settings.local.json").contentEquals(original))

        // 顶层不是对象也一样拒绝，且说明「不覆盖」
        fs.files["/workspace/.claude/settings.local.json"] = "[1,2]".toByteArray(Charsets.UTF_8)
        val rejected = McpApprovals.reject(fs, GuestPath("/workspace"), "x")
        assertTrue(rejected.isFailure)
        assertTrue("不覆盖" in rejected.exceptionOrNull()!!.message.orEmpty())
        assertEquals("[1,2]", String(fs.files.getValue("/workspace/.claude/settings.local.json"), Charsets.UTF_8))
    }

    @Test
    fun `approving a name already on the list does not rewrite the file`() = runBlocking {
        val fs = WritableFs()
        val original = """{"enabledMcpjsonServers":["github"]}"""
        fs.files["/workspace/.claude/settings.local.json"] = original.toByteArray(Charsets.UTF_8)
        assertTrue(McpApprovals.approve(fs, GuestPath("/workspace"), "github").isSuccess)
        assertEquals(original, String(fs.files.getValue("/workspace/.claude/settings.local.json"), Charsets.UTF_8))
    }
}
