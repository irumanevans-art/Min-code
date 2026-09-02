package dev.min.code.core.claudecode

import dev.min.code.ui.session.parseKeyValueLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 子 agent 文件的 frontmatter 解析/回写，以及 MCP 表单里那两个 KEY=VALUE 文本框。
 *
 * 这些是 `/agents`、`/mcp` 这两个原生面板的地基：解析错了，用户在表单里看到的就是空白，
 * 一保存就把原来的定义写没了。
 */
class ClaudeCodeConfigStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun agentFile(content: String): File =
        temp.newFile("reviewer.md").apply { writeText(content) }

    @Test
    fun `frontmatter fields are parsed and the body is kept intact`() {
        val agent = parseAgent(
            agentFile(
                """
                ---
                name: code-reviewer
                description: Reviews changed files
                tools: Read, Grep
                model: sonnet
                ---

                You are a meticulous reviewer.

                Focus on correctness.
                """.trimIndent()
            ),
            userScope = true,
        )

        assertEquals("code-reviewer", agent.name)
        assertEquals("Reviews changed files", agent.description)
        assertEquals("Read, Grep", agent.tools)
        assertEquals("sonnet", agent.model)
        assertEquals("You are a meticulous reviewer.\n\nFocus on correctness.", agent.body.trim())
        assertTrue(agent.userScope)
    }

    /** 没有 frontmatter 的文件也要能读：整篇都是正文，名字退回文件名 */
    @Test
    fun `a file without frontmatter is treated as pure body`() {
        val agent = parseAgent(agentFile("just a prompt"), userScope = false)
        assertEquals("reviewer", agent.name)
        assertEquals("just a prompt", agent.body.trim())
        assertEquals("", agent.description)
    }

    /** 引号是 YAML 的写法，读进表单时要脱掉，否则用户会看到带引号的值再存回去越套越多 */
    @Test
    fun `quoted frontmatter values are unwrapped`() {
        val agent = parseAgent(
            agentFile("---\nname: \"quoted\"\ndescription: 'single'\n---\nbody"),
            userScope = true,
        )
        assertEquals("quoted", agent.name)
        assertEquals("single", agent.description)
    }

    @Test
    fun `render then parse round trips`() {
        val original = ClaudeCodeConfigStore.AgentDefinition(
            fileName = "reviewer.md",
            name = "code-reviewer",
            description = "Reviews changed files",
            tools = "Read, Grep",
            model = "sonnet",
            body = "You are a reviewer.",
            userScope = true,
        )
        val file = agentFile(renderAgent(original))
        val parsed = parseAgent(file, userScope = true)

        assertEquals(original.name, parsed.name)
        assertEquals(original.description, parsed.description)
        assertEquals(original.tools, parsed.tools)
        assertEquals(original.model, parsed.model)
        assertEquals(original.body, parsed.body.trim())
    }

    /**
     * 空字段整行省略，不能写成 `model:` —— 那是个空值，CLI 的 schema 校验会拒掉整个 agent，
     * 表现是"存完之后这个 agent 就消失了"。
     */
    @Test
    fun `blank fields are omitted from the frontmatter`() {
        val rendered = renderAgent(
            ClaudeCodeConfigStore.AgentDefinition(
                fileName = "a.md",
                name = "minimal",
                body = "hi",
            )
        )
        assertTrue(rendered.contains("name: minimal"))
        assertTrue("空 model 不该出现在 frontmatter 里", !rendered.contains("model:"))
        assertTrue(!rendered.contains("tools:"))
        assertTrue(!rendered.contains("description:"))
    }

    // --- MCP 表单里的 KEY=VALUE 文本框 ---

    @Test
    fun `key value lines are parsed per line`() {
        assertEquals(
            mapOf("API_KEY" to "abc123", "REGION" to "us-east-1"),
            parseKeyValueLines("API_KEY=abc123\nREGION=us-east-1"),
        )
    }

    /** 值里带 `=`（base64、连接串很常见）时只按第一个 `=` 切 */
    @Test
    fun `only the first equals sign splits the pair`() {
        assertEquals(mapOf("TOKEN" to "a=b=c"), parseKeyValueLines("TOKEN=a=b=c"))
    }

    /** 空行、没有 `=` 的行、空 key 全部忽略，不能产出脏条目 */
    @Test
    fun `malformed lines are ignored`() {
        assertEquals(
            mapOf("A" to "1"),
            parseKeyValueLines("\nA=1\n没有等号\n=noKey\n   \n"),
        )
    }

    @Test
    fun `values are trimmed but empty values are allowed`() {
        assertEquals(mapOf("A" to "1", "B" to ""), parseKeyValueLines("  A = 1  \nB="))
    }
}
