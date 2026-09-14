package dev.min.code.core.claudecode

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 会话仓库直接读官方 CLI 自持久化的 transcript
 * (`root/.claude/projects/<cwd目录>/<uuid>.jsonl`)，这里用真实抓到的行格式做夹具。
 *
 * 夹具字段参照本机 projects 目录下 .jsonl 的实际内容：
 * 每行是一个 JSON 对象，`user`/`assistant` 带 `message`，另有 `attachment`、
 * `queue-operation`、`atis-latch` 等内部行需要被忽略。
 */
class ClaudeCodeSessionStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val store = ClaudeCodeSessionStore()

    private fun linuxDirWith(vararg sessions: Pair<String, List<String>>): File {
        val linuxDir = temp.newFolder("linux")
        val projects = File(linuxDir, "root/.claude/projects/-workspace").apply { mkdirs() }
        sessions.forEach { (id, lines) ->
            File(projects, "$id.jsonl").writeText(lines.joinToString("\n"))
        }
        return linuxDir
    }

    private fun userLine(text: String) =
        """{"type":"user","sessionId":"s","uuid":"u","parentUuid":null,"cwd":"/workspace",
           "message":{"role":"user","content":[{"type":"text","text":"$text"}]}}""".replace("\n", "")

    private fun assistantLine(text: String) =
        """{"type":"assistant","sessionId":"s","uuid":"a","parentUuid":"u","cwd":"/workspace",
           "message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}""".replace("\n", "")

    private fun toolUseLine(id: String, name: String) =
        """{"type":"assistant","sessionId":"s","uuid":"a2","message":{"role":"assistant",
           "content":[{"type":"tool_use","id":"$id","name":"$name","input":{"command":"ls"}}]}}""".replace("\n", "")

    private fun toolResultLine(id: String, out: String) =
        """{"type":"user","sessionId":"s","uuid":"u2","message":{"role":"user",
           "content":[{"type":"tool_result","tool_use_id":"$id","content":"$out"}]}}""".replace("\n", "")

    @Test
    fun `lists sessions with title from the first user message`() = runBlocking {
        val linuxDir = linuxDirWith(
            "aaa" to listOf(userLine("帮我看下这个仓库"), assistantLine("好的")),
        )
        val sessions = store.listSessions(linuxDir)
        assertEquals(1, sessions.size)
        assertEquals("aaa", sessions[0].id)
        assertEquals("帮我看下这个仓库", sessions[0].title)
        assertEquals(2, sessions[0].messageCount)
        assertFalse(sessions[0].titled)
    }

    /** CLI 拟的名字（custom-title）优先于第一条用户消息，和终端会话列表一致 */
    @Test
    fun `prefers CLI custom-title over the first user message`() = runBlocking {
        val linuxDir = linuxDirWith(
            "aaa" to listOf(
                userLine("C:\\Users\\11141\\Documents\\kimi\\workspace\\min-code。blob:file:///…。为这个项目建立一个自动的更新日志"),
                """{"type":"custom-title","customTitle":"会话标题、置顶分类与更新日志"}""",
            ),
        )
        val s = store.listSessions(linuxDir).single()
        assertEquals("会话标题、置顶分类与更新日志", s.title)
        assertTrue(s.titled)
    }

    /** 自动拟名是 ai-title；无头会话几乎只有这一行，没有 custom-title */
    @Test
    fun `prefers CLI ai-title over the first user message`() = runBlocking {
        val linuxDir = linuxDirWith(
            "aaa" to listOf(
                userLine("请帮我看看这个界面为什么标题不对"),
                """{"type":"ai-title","aiTitle":"会话列表标题显示问题"}""",
            ),
        )
        val s = store.listSessions(linuxDir).single()
        assertEquals("会话列表标题显示问题", s.title)
        assertTrue(s.titled)
        assertTrue(s.bodyText.contains("请帮我看看这个界面为什么标题不对"))
    }

    /** 人手 /rename（custom-title）必须压过自动拟名（ai-title） */
    @Test
    fun `custom-title wins over later ai-title`() = runBlocking {
        val linuxDir = linuxDirWith(
            "aaa" to listOf(
                userLine("随便说一句"),
                """{"type":"ai-title","aiTitle":"自动拟的名字"}""",
                """{"type":"custom-title","customTitle":"我改过的名字"}""",
                // 模拟 CLI 之后又刷了一次 ai-title —— 仍不能盖掉人手改名
                """{"type":"ai-title","aiTitle":"又自动拟了一次"}""",
            ),
        )
        val s = store.listSessions(linuxDir).single()
        assertEquals("我改过的名字", s.title)
        assertTrue(s.titled)
    }

    @Test
    fun `sorts sessions by most recently updated`() = runBlocking {
        val linuxDir = linuxDirWith(
            "old" to listOf(userLine("旧的")),
            "new" to listOf(userLine("新的")),
        )
        val projects = File(linuxDir, "root/.claude/projects/-workspace")
        File(projects, "old.jsonl").setLastModified(1_000_000L)
        File(projects, "new.jsonl").setLastModified(2_000_000L)

        assertEquals(listOf("new", "old"), store.listSessions(linuxDir).map { it.id })
    }

    /** 内部行（attachment / queue-operation / atis-latch）不能算进消息数，也不能当标题 */
    @Test
    fun `ignores internal transcript lines`() = runBlocking {
        val linuxDir = linuxDirWith(
            "x" to listOf(
                """{"type":"queue-operation","operation":"enqueue","sessionId":"s"}""",
                """{"type":"atis-latch","atis":{},"sessionId":"s"}""",
                """{"type":"attachment","attachment":{},"sessionId":"s"}""",
                userLine("真正的第一句"),
            ),
        )
        val s = store.listSessions(linuxDir).single()
        assertEquals("真正的第一句", s.title)
        assertEquals(1, s.messageCount)
    }

    /** 子 agent 的 sidechain 混进主线程会很乱，应当跳过 */
    @Test
    fun `skips sidechain lines`() = runBlocking {
        val sidechain = """{"type":"user","isSidechain":true,"message":{"role":"user",
            "content":[{"type":"text","text":"子agent的话"}]}}""".replace("\n", "")
        val linuxDir = linuxDirWith("x" to listOf(sidechain, userLine("主线程的话")))
        assertEquals("主线程的话", store.listSessions(linuxDir).single().title)
    }

    @Test
    fun `rebuilds transcript into the same events as the live stream`() = runBlocking {
        val linuxDir = linuxDirWith(
            "s1" to listOf(
                userLine("跑一下 ls"),
                toolUseLine("t1", "Bash"),
                toolResultLine("t1", "a.txt"),
                assistantLine("目录里有 a.txt"),
            ),
        )
        val events = store.loadTranscript(linuxDir, "s1")

        assertEquals("跑一下 ls", (events[0] as ClaudeCodeEvent.UserMessage).text)
        val toolUse = events[1] as ClaudeCodeEvent.ToolUse
        assertEquals("Bash", toolUse.name)
        val result = events[2] as ClaudeCodeEvent.ToolResult
        assertEquals("t1", result.toolUseId)
        assertEquals("a.txt", result.content)
        assertFalse(result.isError)
        assertEquals("目录里有 a.txt", (events[3] as ClaudeCodeEvent.AssistantText).text)
    }

    /** 一条 user 行要么是真的用户输入，要么是 tool_result 回填，不能两边都算 */
    @Test
    fun `tool result lines are not mistaken for user messages`() = runBlocking {
        val linuxDir = linuxDirWith("s" to listOf(toolResultLine("t9", "output")))
        val events = store.loadTranscript(linuxDir, "s")
        assertEquals(1, events.size)
        assertTrue(events.single() is ClaudeCodeEvent.ToolResult)
    }

    /**
     * 换过工作目录（set_cwd）之后，CLI 会按新 cwd 另建一个 projects 子目录。
     * 早先按固定两层扫的写法只能看到其中一个，会话列表就会莫名其妙地少一半。
     */
    @Test
    fun `lists sessions across multiple cwd directories`() = runBlocking {
        val linuxDir = temp.newFolder("linux")
        val a = File(linuxDir, "root/.claude/projects/-workspace").apply { mkdirs() }
        val b = File(linuxDir, "root/.claude/projects/-workspace-sub").apply { mkdirs() }
        File(a, "s1.jsonl").writeText(userLine("第一个目录"))
        File(b, "s2.jsonl").writeText(userLine("第二个目录"))

        val ids = store.listSessions(linuxDir).map { it.id }.toSet()
        assertEquals(setOf("s1", "s2"), ids)
    }

    /** 跨目录也要能按 id 定位到 transcript */
    @Test
    fun `loads transcript from a non default cwd directory`() = runBlocking {
        val linuxDir = temp.newFolder("linux")
        val dir = File(linuxDir, "root/.claude/projects/-workspace-other").apply { mkdirs() }
        File(dir, "deep.jsonl").writeText(userLine("在别的目录里"))

        val events = store.loadTranscript(linuxDir, "deep")
        assertEquals("在别的目录里", (events.single() as ClaudeCodeEvent.UserMessage).text)
    }

    @Test
    fun `missing session yields empty results rather than throwing`() = runBlocking {
        val linuxDir = linuxDirWith("a" to listOf(userLine("hi")))
        assertTrue(store.loadTranscript(linuxDir, "does-not-exist").isEmpty())
        assertTrue(store.listSessions(temp.newFolder("empty")).isEmpty())
    }

    /** 会话 id 来自文件名，不能让 ../ 之类的东西越出 projects 目录 */
    @Test
    fun `rejects path traversal in session id`() = runBlocking {
        val linuxDir = linuxDirWith("a" to listOf(userLine("hi")))
        assertTrue(store.loadTranscript(linuxDir, "../../../etc/passwd").isEmpty())
        assertFalse(store.deleteSession(linuxDir, "../a"))
    }

    @Test
    fun `hasTranscript is true only when the jsonl exists`() = runBlocking {
        val linuxDir = linuxDirWith("aaa" to listOf(userLine("hi")))
        assertTrue(store.hasTranscript(linuxDir, "aaa"))
        assertFalse(store.hasTranscript(linuxDir, "does-not-exist"))
        assertFalse(store.hasTranscript(temp.newFolder("empty"), "aaa"))
        assertFalse(store.hasTranscript(linuxDir, ""))
        assertFalse(store.hasTranscript(linuxDir, "../aaa"))
    }

    @Test
    fun `deletes a session transcript`() = runBlocking {
        val linuxDir = linuxDirWith("gone" to listOf(userLine("待删")))
        assertTrue(store.deleteSession(linuxDir, "gone"))
        assertTrue(store.listSessions(linuxDir).isEmpty())
    }

    /** 删会话要把 sidecar（子 agent 目录、jsonl 备份）一起清掉，否则 CLI 内部还看得见 */
    @Test
    fun `deletes sidecar files and directories along with the transcript`() = runBlocking {
        val linuxDir = linuxDirWith("gone" to listOf(userLine("待删")))
        val projects = File(linuxDir, "root/.claude/projects/-workspace")
        File(projects, "gone.jsonl.bak").writeText("backup")
        File(projects, "gone").apply { mkdirs(); File(this, "agent.jsonl").writeText("{}") }

        assertTrue(store.deleteSession(linuxDir, "gone"))
        assertFalse(File(projects, "gone.jsonl").exists())
        assertFalse(File(projects, "gone.jsonl.bak").exists())
        assertFalse(File(projects, "gone").exists())
    }
}
