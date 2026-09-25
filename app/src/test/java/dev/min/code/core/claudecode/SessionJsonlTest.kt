package dev.min.code.core.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 导入 transcript 的校验与 cwd 改写。行的形状照本机 `~/.claude/projects` 下真实文件取：
 * user / assistant / attachment / system 行带顶层 `cwd`，`file-history-snapshot` 行不带 `sessionId`。
 */
class SessionJsonlTest {

    private val id = "0af37106-1491-42ae-94e7-ef8da5c44a1c"
    private val other = "11111111-2222-4333-8444-555555555555"

    private fun userLine(cwd: String = "/workspace/old", sid: String = id) =
        """{"parentUuid":null,"type":"user","message":{"role":"user","content":[{"type":"text","text":"hi"}]},""" +
            """"uuid":"u1","cwd":"$cwd","sessionId":"$sid","version":"2.1.246"}"""

    private fun snapshotLine() =
        """{"type":"file-history-snapshot","messageId":"m1","snapshot":{"trackedFileBackups":{}}}"""

    private fun scan(text: String, target: String = "/workspace/new", max: Long = SessionJsonl.MAX_IMPORT_BYTES) =
        scanBytes(text.encodeToByteArray(), target, max)

    private fun scanBytes(bytes: ByteArray, target: String = "/workspace/new", max: Long = SessionJsonl.MAX_IMPORT_BYTES):
        Pair<SessionImportScan, ByteArray> {
        val out = ByteArrayOutputStream()
        val result = SessionJsonl.scanImport(ByteArrayInputStream(bytes), out, target, max)
        return result to out.toByteArray()
    }

    // ---- 校验 ----

    @Test
    fun `a clean transcript yields its session id`() {
        val (result, _) = scan(listOf(userLine(), snapshotLine(), userLine()).joinToString("\n") + "\n")
        assertEquals(SessionImportScan.Ok(sessionId = id, cwdRewritten = 2), result)
    }

    @Test
    fun `two session ids in one file are rejected at the first stranger`() {
        val (result, _) = scan(listOf(userLine(), snapshotLine(), userLine(sid = other)).joinToString("\n"))
        assertEquals(SessionImportScan.Rejected(SessionImportReject.MIXED_SESSION_ID, 3), result)
    }

    @Test
    fun `an empty file or only blank lines is rejected as empty`() {
        assertEquals(SessionImportScan.Rejected(SessionImportReject.EMPTY), scan("").first)
        assertEquals(SessionImportScan.Rejected(SessionImportReject.EMPTY), scan("\n  \r\n\n").first)
    }

    @Test
    fun `a line that is not a json object is rejected with its line number`() {
        val broken = listOf(userLine(), """{"type":"user","cwd":"/w""", userLine()).joinToString("\n")
        assertEquals(SessionImportScan.Rejected(SessionImportReject.BAD_LINE, 2), scan(broken).first)
        val array = listOf(userLine(), "[1,2]").joinToString("\n")
        assertEquals(SessionImportScan.Rejected(SessionImportReject.BAD_LINE, 2), scan(array).first)
        assertEquals(SessionImportScan.Rejected(SessionImportReject.BAD_LINE, 1), scan("hello\n").first)
    }

    @Test
    fun `a file without any session id is rejected`() {
        val (result, _) = scan(listOf(snapshotLine(), snapshotLine()).joinToString("\n"))
        assertEquals(SessionImportScan.Rejected(SessionImportReject.NO_SESSION_ID), result)
    }

    @Test
    fun `blank or non uuid session ids are rejected`() {
        assertEquals(
            SessionImportScan.Rejected(SessionImportReject.BAD_SESSION_ID, 1),
            scan(userLine(sid = "")).first,
        )
        assertEquals(
            SessionImportScan.Rejected(SessionImportReject.BAD_SESSION_ID, 2),
            scan(snapshotLine() + "\n" + userLine(sid = "../../etc/passwd")).first,
        )
        assertEquals(
            SessionImportScan.Rejected(SessionImportReject.BAD_SESSION_ID, 1),
            scan("""{"type":"mode","sessionId":42}""").first,
        )
    }

    @Test
    fun `a file over the cap is rejected before it is all read`() {
        val text = userLine() + "\n"
        val size = text.encodeToByteArray().size.toLong()
        assertTrue(scan(text, max = size).first is SessionImportScan.Ok)
        assertEquals(SessionImportScan.Rejected(SessionImportReject.TOO_LARGE), scan(text, max = size - 1).first)
    }

    @Test
    fun `the cap is 64 MiB`() {
        assertEquals(64L * 1024 * 1024, SessionJsonl.MAX_IMPORT_BYTES)
    }

    // ---- 改写 ----

    @Test
    fun `only the top level cwd value changes, every other byte stays`() {
        // 键序怪、带空格、有 \u 转义、嵌套里也有 cwd：这些都必须原样留着
        val line = """{"z":1, "message":{"cwd":"/nested","text":"caf\u00e9 {\"cwd\":\"x\"}"},""" +
            """ "cwd" : "/workspace/old" ,"sessionId":"$id","n":1.50}"""
        val expected = """{"z":1, "message":{"cwd":"/nested","text":"caf\u00e9 {\"cwd\":\"x\"}"},""" +
            """ "cwd" : "/workspace/new" ,"sessionId":"$id","n":1.50}"""
        val (result, out) = scan(line)
        assertEquals(SessionImportScan.Ok(id, 1), result)
        assertEquals(expected, out.decodeToString())
    }

    @Test
    fun `lines without cwd or already at the target pass through byte for byte`() {
        val text = listOf(snapshotLine(), userLine(cwd = "/workspace/new"), """{"type":"mode","sessionId":"$id"}""")
            .joinToString("\n") + "\n"
        val bytes = text.encodeToByteArray()
        val (result, out) = scanBytes(bytes)
        assertEquals(SessionImportScan.Ok(id, 0), result)
        assertArrayEquals(bytes, out)
    }

    @Test
    fun `crlf line endings and a missing final newline are kept`() {
        val text = userLine() + "\r\n" + snapshotLine() + "\r\n\r\n" + userLine()
        val (result, out) = scan(text)
        assertEquals(SessionImportScan.Ok(id, 2), result)
        val expected = userLine(cwd = "/workspace/new") + "\r\n" + snapshotLine() + "\r\n\r\n" +
            userLine(cwd = "/workspace/new")
        assertEquals(expected, out.decodeToString())
    }

    @Test
    fun `a leading utf8 bom is dropped because the cli would choke on it`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + (userLine() + "\n").encodeToByteArray()
        val (result, out) = scanBytes(bytes)
        assertEquals(SessionImportScan.Ok(id, 1), result)
        assertEquals(userLine(cwd = "/workspace/new") + "\n", out.decodeToString())
    }

    @Test
    fun `windows cwd from a desktop transcript is replaced and stays valid json`() {
        val line = """{"type":"user","cwd":"C:\\tmp\\cc-protocol-check\\ws","sessionId":"$id"}"""
        val rewritten = SessionJsonl.rewriteTopLevelCwd(line, "/workspace")!!
        assertEquals("""{"type":"user","cwd":"/workspace","sessionId":"$id"}""", rewritten)
    }

    @Test
    fun `a target needing escapes is written as a proper json string`() {
        val target = "/workspace/a \"b\"\\c"
        val rewritten = SessionJsonl.rewriteTopLevelCwd(userLine(), target)!!
        val parsed = Json.parseToJsonElement(rewritten).jsonObject
        assertEquals(target, parsed["cwd"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rewrite leaves non string or absent cwd alone`() {
        assertNull(SessionJsonl.rewriteTopLevelCwd("""{"cwd":null,"sessionId":"$id"}""", "/workspace"))
        assertNull(SessionJsonl.rewriteTopLevelCwd(snapshotLine(), "/workspace"))
        assertNull(SessionJsonl.rewriteTopLevelCwd(userLine(cwd = "/workspace"), "/workspace"))
    }

    @Test
    fun `top level scanner skips nested containers and tricky strings`() {
        val json = """{"a":[{"cwd":"x"},"]}"],"b":{"c":{"cwd":1}},"cwd":"/w","d":true}"""
        val spans = SessionJsonl.topLevelValueSpans(json, "cwd")
        assertEquals(1, spans.size)
        assertEquals("\"/w\"", json.substring(spans[0].first, spans[0].last + 1))
        assertEquals("true", SessionJsonl.topLevelValueSpans(json, "d").single().let { json.substring(it.first, it.last + 1) })
    }

    // ---- 目录名 ----

    @Test
    fun `project dir name follows the cli rule`() {
        assertEquals("-workspace", SessionJsonl.projectDirName("/workspace"))
        assertEquals("-workspace-my-app", SessionJsonl.projectDirName("/workspace/my app"))
        // 本机真实目录：C:\Users\11141 → C--Users-11141
        assertEquals("C--Users-11141", SessionJsonl.projectDirName("C:\\Users\\11141"))
        // JS 按 UTF-16 码元换：一个 emoji 是两个 -
        assertEquals("-w---", SessionJsonl.projectDirName("/w/\uD83D\uDE00"))
    }

    @Test
    fun `project dir names past the cli limit are not guessed`() {
        val longest = "/" + "a".repeat(SessionJsonl.PROJECT_DIR_NAME_MAX - 1)
        assertEquals(SessionJsonl.PROJECT_DIR_NAME_MAX, SessionJsonl.projectDirName(longest)!!.length)
        assertNull(SessionJsonl.projectDirName(longest + "a"))
    }
}
