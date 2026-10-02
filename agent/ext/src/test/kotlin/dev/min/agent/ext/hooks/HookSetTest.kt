package dev.min.agent.ext.hooks

import dev.min.agent.ext.config.ConfigSource
import org.junit.Assert.*
import org.junit.Test

class HookSetTest {
    @Test fun `坏配置逐项跳过且 note 说明层事件与位置`() {
        val set = HookSet.parse(listOf(source("""{
          "Bogus":[],"Stop":{},"PreToolUse":[
            {"hooks":"wrong"},{"matcher":1,"hooks":[]},
            {"matcher":"[","hooks":[{"type":"command","command":"never"}]},
            {"hooks":[null,{}, {"type":true},{"type":"oops"},{"type":"command"},
              {"type":"command","command":2},{"type":"command","args":[1]},
              {"type":"command","command":"a","timeout":"5"},
              {"type":"command","command":"a","timeout":0},
              {"type":"command","command":"a","if":false},
              {"type":"command","command":"ok","args":null},
              {"type":"command","command":"a","async":"true"},
              {"type":"command","command":"ok"}]}]}
        """, ConfigSource.Local)))
        assertEquals(listOf("ok"), set.handlers.map { it.command })
        assertEquals(17, set.notes.size)
        assertTrue(set.notes.all { it.contains("local") })
        assertTrue(set.notes.any { it.contains("PreToolUse 第 4 组 第 10 项") })
    }

    @Test fun `五字段原样解析且 raw 不丢未知字段`() {
        val raw = """{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"exe","args":["a b","中文"],"timeout":9,"if":"Bash(git *)","extra":7}]}]}"""
        val h = HookSet.parse(listOf(source(raw))).handlers.single()
        assertEquals("Bash", h.matcher); assertEquals(listOf("a b", "中文"), h.args)
        assertEquals(9, h.timeout); assertEquals("Bash(git *)", h.condition)
        assertEquals("7", h.raw["extra"].toString())
    }

    @Test fun `跨层去重保留最高优先级并保留原来的顺序`() {
        val config = """{"PreToolUse":[{"hooks":[{"type":"command","command":"same"},{"type":"command","command":"other"}]}]}"""
        val set = HookSet.parse(listOf(source(config, ConfigSource.Managed), source(config, ConfigSource.User),
            source(config, ConfigSource.Local), source(config, ConfigSource.Project)))
        assertEquals(listOf("same", "other"), set.handlers.map { it.command })
        assertTrue(set.handlers.all { it.source == ConfigSource.Managed })
    }

    @Test fun `去重身份包含 args if timeout type`() {
        val set = HookSet.parse(listOf(source("""{"PreToolUse":[{"hooks":[
          {"type":"command","command":"a"},{"type":"command","command":"a","args":["b"]},
          {"type":"command","command":"a","if":"Bash(*)"},{"type":"command","command":"a","timeout":10},
          {"type":"http","command":"a"}]}]}""")))
        assertEquals(5, set.handlers.size)
    }

    @Test fun `同处理器不同事件保留并对多个命中 matcher 合批去重`() {
        val set = HookSet.parse(listOf(source("""{
          "PreToolUse":[{"matcher":"Edit","hooks":[{"type":"command","command":"a"}]},
            {"matcher":"Edit.*","hooks":[{"type":"command","command":"a"}]}],
          "Stop":[{"hooks":[{"type":"command","command":"a"}]}]}""")))
        assertEquals(3, set.handlers.size)
        assertEquals(1, set.matching(HookEvent.PreToolUse, "Edit").size)
        assertEquals(1, set.matching(HookEvent.Stop, null).size)
        assertEquals(1, set.matching(HookEvent.PreToolUse, "NotebookEdit").size)
    }

    @Test fun `保留未来类型但匹配跳过并给 note`() {
        val set = HookSet.parse(listOf(source("""{"Stop":[{"hooks":[
          {"type":"http","url":"x"},{"type":"prompt","prompt":"p"},{"type":"agent","prompt":"a"},
          {"type":"mcp_tool"},{"type":"command","command":"a","async":true}]}]}""")))
        assertEquals(5, set.handlers.size)
        assertTrue(set.handlers.none { it.executable })
        val match = set.matchingDetailed(HookEvent.Stop, null)
        assertTrue(match.handlers.isEmpty()); assertEquals(5, match.notes.size)
        assertTrue(match.notes.all { it.contains("本期不执行") })
    }

    @Test fun `工具 if 由调用方注入且没有注入时可见地跳过`() {
        val set = HookSet.parse(listOf(source("""{"PreToolUse":[{"hooks":[{"type":"command","command":"a","if":"Bash(git *)"}]}],
          "Stop":[{"hooks":[{"type":"command","command":"a","if":"ignored"}]}]}""")))
        assertEquals(1, set.matchingDetailed(HookEvent.PreToolUse, "Bash").notes.size)
        assertTrue(set.matchingDetailed(HookEvent.PreToolUse, "Bash") { false }.handlers.isEmpty())
        assertEquals(1, set.matchingDetailed(HookEvent.PreToolUse, "Bash") { assertEquals("Bash(git *)", it); true }.handlers.size)
        assertEquals(1, set.matching(HookEvent.Stop, null).size)
    }

    @Test fun `matcher 三种写法精确名单与非锚定正则`() {
        val e = HookEvent.PreToolUse
        listOf(null, "", "*").forEach { assertTrue(matchesHookTarget(e, it, null)) }
        assertTrue(matchesHookTarget(e, "Edit | Write, Bash", "Write"))
        assertFalse(matchesHookTarget(e, "Edit|Write", "NotebookEdit"))
        assertTrue(matchesHookTarget(e, "Edit.*", "NotebookEdit"))
        assertFalse(matchesHookTarget(e, "mcp__memory", "mcp__memory__read"))
        assertTrue(matchesHookTarget(e, "mcp__memory", "mcp__memory"))
        assertFalse(matchesHookTarget(e, "Bash", "bash"))
    }

    @Test fun `FileChanged 和 StopFailure 的窄字符集将逗号空格连字符作为正则`() {
        listOf(HookEvent.FileChanged, HookEvent.StopFailure).forEach { event ->
            assertTrue(matchesHookTarget(event, "a-b", "prefix-a-b-suffix"))
            assertTrue(matchesHookTarget(event, "a,b", "prefix-a,b-suffix"))
            assertTrue(matchesHookTarget(event, "a b", "prefix-a b-suffix"))
            assertFalse(matchesHookTarget(event, "foo|bar", "prefixfoo"))
            assertTrue(matchesHookTarget(event, "foo|bar", "foo"))
        }
        assertFalse(matchesHookTarget(HookEvent.PreToolUse, "a-b", "prefix-a-b-suffix"))
    }

    @Test fun `不支持 matcher 的事件忽略无效正则及空 target 的边界`() {
        assertTrue(matchesHookTarget(HookEvent.UserPromptSubmit, "[", "anything"))
        val set = HookSet.parse(listOf(source("""{"Stop":[{"matcher":"[","hooks":[{"type":"command","command":"a"}]}]}""")))
        assertTrue(set.notes.isEmpty()); assertEquals(1, set.matching(HookEvent.Stop, null).size)
        assertFalse(matchesHookTarget(HookEvent.SubagentStop, "reviewer", null))
        assertTrue(matchesHookTarget(HookEvent.SubagentStop, ".*", null))
        assertTrue(matchesHookTarget(HookEvent.PreModelSwitch, "specific", null))
    }
}
