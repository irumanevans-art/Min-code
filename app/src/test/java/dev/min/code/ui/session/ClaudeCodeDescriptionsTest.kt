package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 说明的中英切换。核心约定只有一条：**查不到就回退原文**。
 * 插件命令、自定义命令、新版新增的命令都会走到这条路上，
 * 显示英文原文远好过显示空白或一个猜出来的中文。
 */
class ClaudeCodeDescriptionsTest {

    @Test
    fun `中文模式下已收录的命令给译文`() {
        assertEquals(
            "把已有对话压缩成摘要，腾出上下文空间",
            slashCommandDescription("compact", "Compact the conversation", chinese = true),
        )
    }

    @Test
    fun `中文模式下没收录的命令回退原文`() {
        assertEquals(
            "Run my custom thing",
            slashCommandDescription("my-plugin-cmd", "Run my custom thing", chinese = true),
        )
    }

    @Test
    fun `原文模式一律给原文`() {
        assertEquals(
            "Compact the conversation",
            slashCommandDescription("compact", "Compact the conversation", chinese = false),
        )
    }

    @Test
    fun `命令名大小写不敏感`() {
        assertEquals(
            slashCommandDescription("compact", null, chinese = true),
            slashCommandDescription("Compact", null, chinese = true),
        )
    }

    @Test
    fun `原文为空且查不到时给 null`() {
        assertNull(slashCommandDescription("unknown-cmd", null, chinese = true))
    }

    /**
     * 模型说明按 id 查而不是按英文原文查：CLI 那段英文里嵌着当前默认模型名
     * （`Use the default model (currently Opus 5 (1M context))`），一换默认模型就失配。
     */
    @Test
    fun `模型说明按 id 查表`() {
        val zh = modelDescription("opus[1m]", "Opus 5 with 1M context · \$5/\$25 per Mtok", chinese = true)
        assertEquals("Opus 5，1M 上下文 · 日常和复杂任务都最强 · 每百万 token \$5 / \$25", zh)
    }

    /** 中转站给的是裸 id，用户在群里第一句就是"为啥没有 fable"——固定 id 必须有说明 */
    @Test
    fun `固定 id 也有中文说明`() {
        val zh = modelDescription("claude-fable-5-1", null, chinese = true)
        assertEquals(true, zh?.startsWith("Fable 5.1"))
        // 带日期后缀 / [1m] 的同版本 id 复用同一条说明
        assertEquals(zh, modelDescription("claude-fable-5-1-20260901[1m]", null, chinese = true))
        // 5 和 5.1 价格一样但代际不同，不能串
        assertEquals(true, modelDescription("claude-fable-5", null, chinese = true)?.startsWith("Fable 5 "))
    }

    @Test
    fun `认不出的模型 id 回退原文`() {
        assertNull(modelDescription("my-relay-model", null, chinese = true))
        assertEquals("Raw", modelDescription("my-relay-model", "Raw", chinese = true))
        // 别拿家族瞎猜：旧 Opus 和 Opus 5 价格不同
        assertNull(modelDescription("claude-opus-4-1", null, chinese = true))
    }

    @Test
    fun `覆盖率如实统计`() {
        val names = listOf("compact", "cost", "my-plugin-cmd")
        assertEquals(2, translatedCommandCount(names))
    }
}
