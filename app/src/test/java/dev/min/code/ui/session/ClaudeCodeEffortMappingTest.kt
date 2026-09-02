package dev.min.code.ui.session

import dev.min.code.core.claudecode.ReasoningLevel
import dev.min.code.core.claudecode.ClaudeCodeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * effort 档位映射。
 *
 * 这层是 Min 与 CLI `--effort` 之间唯一的正确性保证，而 CLI 对非法 effort 是
 * **warn-and-ignore**（不报错、不退出、退出码 0），传错了 App 侧毫无感知，
 * 只会静默跑在默认档上。所以这里的不变量必须钉死。
 *
 * 档位事实来自官方 CLI v2.1.246 二进制 + 实机复核：
 *   R  = ["low","medium","high","xhigh","max"]   // 合法阶梯，max 是天花板
 *   ze = { med: "medium" }                        // 未文档化别名
 *   Xe = { ultracode: "xhigh" }                   // ultracode = xhigh + 工作流编排，**不是更高档**
 * `auto` / `none` 作为 CLI flag 会被拒（实测均打印 "Unknown --effort value"）。
 */
class ClaudeCodeEffortMappingTest {

    /**
     * 最重要的一条：**任何** ReasoningLevel 都不能映射出 CLI 白名单之外的值。
     * 将来给枚举加一档（比如 ULTRA），这条会立刻红，而不是等到线上静默降级。
     */
    @Test
    fun `no reasoning level can leak an illegal effort value`() {
        ReasoningLevel.entries.forEach { level ->
            val effort = levelToEffort(level)
            assertTrue(
                "ReasoningLevel.$level 映射出了 CLI 不认的 --effort 值: $effort",
                effort == null || effort in ClaudeCodeManager.EFFORT_LEVELS,
            )
        }
    }

    /** OFF/AUTO 在 CLI 那边没有对应取值，只能是"不传 flag" */
    @Test
    fun `off and auto map to no flag`() {
        assertNull(levelToEffort(ReasoningLevel.OFF))
        assertNull(levelToEffort(ReasoningLevel.AUTO))
    }

    @Test
    fun `real levels map to their cli effort strings`() {
        assertEquals("low", levelToEffort(ReasoningLevel.LOW))
        assertEquals("medium", levelToEffort(ReasoningLevel.MEDIUM))
        assertEquals("high", levelToEffort(ReasoningLevel.HIGH))
        assertEquals("xhigh", levelToEffort(ReasoningLevel.XHIGH))
        assertEquals("max", levelToEffort(ReasoningLevel.MAX))
    }

    /** max 是天花板：白名单里不该出现任何"比 max 更高"的东西 */
    @Test
    fun `max is the top of the ladder`() {
        assertEquals("max", ClaudeCodeManager.EFFORT_LEVELS.last())
        assertFalse("ultracode 不是 effort 档位，它等于 xhigh", "ultracode" in ClaudeCodeManager.EFFORT_LEVELS)
    }

    /**
     * ultracode 是 --effort 的合法取值，但**不属于阶梯**（它=xhigh+工作流编排），
     * 所以必须走单独的常量与单独的布尔位，不能混进 EFFORT_LEVELS 白名单里参与档位比较。
     */
    @Test
    fun `ultracode is a separate flag value not a ladder rung`() {
        assertEquals("ultracode", ClaudeCodeManager.EFFORT_ULTRACODE)
        assertFalse(ClaudeCodeManager.EFFORT_ULTRACODE in ClaudeCodeManager.EFFORT_LEVELS)
    }

    /** none / auto 是 CLI flag 拒收的值，绝不能进白名单 */
    @Test
    fun `rejected cli values are not whitelisted`() {
        listOf("none", "auto", "ultrathink", "ultraplan", "").forEach {
            assertFalse("$it 会被 CLI 拒收，不该出现在白名单里", it in ClaudeCodeManager.EFFORT_LEVELS)
        }
    }

    // --- 面板上呈现哪些档 ---

    private fun state(
        model: String? = null,
        models: List<ClaudeCodeManager.ModelOption> = emptyList(),
    ) = ClaudeCodeManager.SessionState(
        options = ClaudeCodeManager.SessionOptions(model = model),
        availableModels = models,
    )

    /**
     * OFF 必须从面板上消失：它和 AUTO 一样都映射成"不传 flag"，
     * 两档行为完全一致，留着就是个选了等于没选的死档。
     */
    @Test
    fun `off is not offered`() {
        val levels = effortLevelsFor(state())
        assertFalse("OFF 是死档，不该出现在面板上", ReasoningLevel.OFF in levels)
        assertTrue(ReasoningLevel.AUTO in levels)
        assertTrue(ReasoningLevel.MAX in levels)
    }

    /** 模型没报能力时不做裁剪，全给 */
    @Test
    fun `without model capability info all levels are offered`() {
        assertEquals(
            ReasoningLevel.entries.filter { it != ReasoningLevel.OFF },
            effortLevelsFor(state(model = "opus", models = emptyList())),
        )
    }

    /** 模型报了 supportedEffortLevels 就按它裁剪，避免"界面显示 max、CLI 实际钳到 high" */
    @Test
    fun `levels are clamped to what the model reports`() {
        val levels = effortLevelsFor(
            state(
                model = "weak",
                models = listOf(
                    ClaudeCodeManager.ModelOption(
                        value = "weak",
                        displayName = "Weak",
                        supportedEffortLevels = listOf("low", "medium"),
                    ),
                ),
            )
        )
        assertEquals(
            listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.MEDIUM),
            levels,
        )
        assertFalse(ReasoningLevel.MAX in levels)
    }

    /** AUTO 代表"不传 flag"，与模型能力无关，任何裁剪下都要保留 */
    @Test
    fun `auto survives even the narrowest capability list`() {
        val levels = effortLevelsFor(
            state(
                model = "m",
                models = listOf(
                    ClaudeCodeManager.ModelOption("m", "M", supportedEffortLevels = listOf("nothing-matches")),
                ),
            )
        )
        assertEquals(listOf(ReasoningLevel.AUTO), levels)
    }

    /** 当前模型不在目录里（用户手输的名字）时不裁剪 */
    @Test
    fun `unknown current model is not clamped`() {
        val levels = effortLevelsFor(
            state(
                model = "hand-typed",
                models = listOf(ClaudeCodeManager.ModelOption("other", "Other", supportedEffortLevels = listOf("low"))),
            )
        )
        assertTrue(ReasoningLevel.MAX in levels)
    }

    // --- 本地接管的斜杠命令 ---
    //
    // `-p --output-format stream-json` 是无头模式，没有 TUI：`/model` 这类交互式命令
    // 在真终端里弹选择器，在这里 CLI 只会回一段纯文本（"Available: sonnet, opus, …"），
    // 点不了也选不了。所以要在发送前拦下来改开 App 自己的面板。

    @Test
    fun `interactive slash commands are handled locally`() {
        assertEquals(LocalSlash.MODEL, localSlashTarget("/model"))
        assertEquals(LocalSlash.MODEL, localSlashTarget("  /model  "))
        assertEquals(LocalSlash.MODEL, localSlashTarget("/MODEL"))
        assertEquals(LocalSlash.EFFORT, localSlashTarget("/effort"))
        assertEquals(LocalSlash.PERMISSION, localSlashTarget("/permission-mode"))
        assertEquals(LocalSlash.CWD, localSlashTarget("/cwd"))
    }

    /**
     * 配置类命令同样必须本地接管。它们在 TUI 里是交互式编辑器，无头模式下 CLI 只能回
     * 一段读不了也改不了的文本 —— 而它们改的都是 Rootfs 里的文件，原生表单能做到等价。
     */
    @Test
    fun `config editing slash commands are handled locally too`() {
        assertEquals(LocalSlash.MCP, localSlashTarget("/mcp"))
        assertEquals(LocalSlash.AGENTS, localSlashTarget("/agents"))
        assertEquals(LocalSlash.MEMORY, localSlashTarget("/memory"))
        assertEquals(LocalSlash.CONFIG, localSlashTarget("/config"))
        assertEquals(LocalSlash.CONFIG, localSlashTarget("/output-style"))
        assertEquals(LocalSlash.HOOKS, localSlashTarget("/permissions"))
    }

    /** 只有会话类命令能落到那张 sheet 上；配置类的走另一个面板，必须是 null */
    @Test
    fun `only session commands map to a settings section`() {
        assertEquals(SettingsSection.MODEL, LocalSlash.MODEL.toSection())
        assertEquals(SettingsSection.MODE, LocalSlash.PERMISSION.toSection())
        assertNull(LocalSlash.MCP.toSection())
        assertNull(LocalSlash.AGENTS.toSection())
        assertNull(LocalSlash.MEMORY.toSection())
    }

    /** 带参数的形式（`/model opus`）CLI 自己能处理，别拦 */
    @Test
    fun `slash commands with arguments are passed through`() {
        assertNull(localSlashTarget("/model opus"))
        assertNull(localSlashTarget("/effort max"))
    }

    /** 普通文本和其他命令一律原样发下去 */
    @Test
    fun `other input is not intercepted`() {
        assertNull(localSlashTarget(""))
        assertNull(localSlashTarget("/"))
        assertNull(localSlashTarget("/compact"))
        assertNull(localSlashTarget("/clear"))
        assertNull(localSlashTarget("帮我改一下 /model 这段代码"))
    }

    // --- `@` 文件提及 ---

    @Test
    fun `mention token is the text after the last at sign`() {
        assertEquals("", mentionTokenOf("@"))
        assertEquals("App", mentionTokenOf("@App"))
        assertEquals("src/App", mentionTokenOf("看一下 @src/App"))
        assertEquals("b", mentionTokenOf("@a 和 @b"))
    }

    /** `foo@bar.com` 不该触发补全 —— @ 前面必须是空白或行首 */
    @Test
    fun `at sign inside a word does not start a mention`() {
        assertNull(mentionTokenOf("foo@bar.com"))
        assertNull(mentionTokenOf("mail me at me@example.org"))
    }

    /** 提及打完（后面出现空白）就该收起候选列表，否则它会一直挂在输入框上方 */
    @Test
    fun `a completed mention stops matching`() {
        assertNull(mentionTokenOf("@src/App.kt 帮我看看"))
        assertNull(mentionTokenOf("没有提及"))
    }

    @Test
    fun `picking a file replaces the token in place`() {
        assertEquals(
            "看一下 @/workspace/src/App.kt ",
            replaceMentionToken("看一下 @src/Ap", "/workspace/src/App.kt"),
        )
        assertEquals("@/workspace/a.kt ", replaceMentionToken("@", "/workspace/a.kt"))
        // 没有 @ 时原样返回，绝不能凭空往输入框里塞东西
        assertEquals("纯文本", replaceMentionToken("纯文本", "/workspace/a.kt"))
    }
}
