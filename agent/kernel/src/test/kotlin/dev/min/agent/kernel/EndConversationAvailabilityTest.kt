package dev.min.agent.kernel

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.*
import dev.min.agent.store.*
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.testDeps
import dev.min.agent.tools.BashTool
import dev.min.agent.tools.EndConversationTool
import dev.min.agent.tools.END_CONVERSATION_TOOL_NAME
import dev.min.agent.tools.ReadTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * EndConversation 什么时候出现在工具表里（CC tools-reference「EndConversation tool behavior」与 permissions 页）：
 * - 型号：只有 Opus 4.8 / Sonnet 5 / Fable 5 或其更新版本（目录里的 supportsEndConversation）；
 * - deny 规则：deny / ask 规则挡不住它，**除非**这些 deny 把其余工具全移除、并且匹配了它（`*` 就是）——那时连它也移除，
 *   不让它成为唯一的工具；deny 之外还有 allow 点名 EndConversation 的话保留。
 */
class EndConversationAvailabilityTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun tearDown() = scopes.forEach { it.cancel() }

    private val capable = TEST_PROFILE.copy(supportsEndConversation = true)
    private var n = 0

    private fun names(profile: dev.min.agent.llm.ModelProfile, rules: List<RuleEntry> = emptyList(), tools: List<Tool<*>> = listOf(ReadTool, BashTool(), EndConversationTool)): List<String> = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val deps = testDeps(ScriptedModel(), tools, profile = profile)
        val rt = SessionRuntime.create(
            FileSessionStore(tmp.root.toPath(), { 0L }), LogHeader(LOG_FORMAT_VERSION, SessionId("s${++n}"), 0),
            SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.Default), deps, scope,
            initial = if (rules.isEmpty()) emptyList() else listOf(PermissionRulesLoaded(rules)),
        )
        availableTools(rt.state.value, deps, runtime = rt).map { it.name }
    }

    private fun deny(text: String) = RuleEntry(RuleKindData.Deny, text, "user")
    private fun allow(text: String) = RuleEntry(RuleKindData.Allow, text, "user")

    @Test fun `a model without the capability never sees the tool`() {
        assertFalse(END_CONVERSATION_TOOL_NAME in names(TEST_PROFILE))
    }

    @Test fun `a model with the capability sees it`() {
        assertTrue(END_CONVERSATION_TOOL_NAME in names(capable))
    }

    @Test fun `a deny rule naming only EndConversation or one other tool cannot remove it`() {
        assertTrue(END_CONVERSATION_TOOL_NAME in names(capable, listOf(deny("EndConversation"))))
        assertTrue(END_CONVERSATION_TOOL_NAME in names(capable, listOf(deny("Bash"))))
    }

    @Test fun `a star deny removes every other tool and takes EndConversation with it`() {
        assertFalse(END_CONVERSATION_TOOL_NAME in names(capable, listOf(deny("*"))))
    }

    @Test fun `an allow rule that names EndConversation keeps it under a star deny`() {
        assertTrue(END_CONVERSATION_TOOL_NAME in names(capable, listOf(deny("*"), allow("EndConversation"))))
    }

    @Test fun `a deny list that removes every other tool without matching EndConversation leaves it in place`() {
        assertTrue(END_CONVERSATION_TOOL_NAME in names(capable, listOf(deny("Read"), deny("Bash"))))
    }

    @Test fun `a glob deny that matches EndConversation but leaves another tool standing keeps it`() {
        // 「E*」只匹配 EndConversation 自己，Read 与 Bash 还在：不是「其余工具全被移除」
        assertTrue(END_CONVERSATION_TOOL_NAME in names(capable, listOf(deny("End*"))))
    }
}
