package dev.min.code.core.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 权限档 ↔ 两个协议值的映射，以及「继续」时哪些设置跟着走。
 * 这张表错一格就是悄悄放宽（或收紧）了权限，所以每一格都钉住。
 */
class CodexSessionSettingsTest {

    /** 三档的组合抄自官方 approval-presets（rust-v0.157.0）；线上取值是 turn/start 里真正写出去的字符串 */
    @Test
    fun `each level maps onto the official approval and sandbox pair`() {
        val wires = CodexPermissionPreset.entries.associate { it.id to (it.approvalPolicy.wire to it.sandbox.wire) }
        assertEquals(
            mapOf(
                "read-only" to ("on-request" to "readOnly"),
                "auto" to ("on-request" to "workspaceWrite"),
                "full-access" to ("never" to "dangerFullAccess"),
            ),
            wires,
        )
    }

    /** 默认档必须就是接入以来一直在发的那一对，不能借这次改动悄悄放宽 */
    @Test
    fun `the default level is what sessions have always used`() {
        val defaults = CodexAppServerManager.Options()
        assertEquals(CodexPermissionPreset.DEFAULT, defaults.permissionPreset)
        assertEquals(CodexPermissionPreset.AUTO, CodexPermissionPreset.DEFAULT)
        assertEquals(true, defaults.networkAccess)
    }

    @Test
    fun `a pair that is no level is reported as such`() {
        assertNull(CodexPermissionPreset.of(CodexApprovalPolicy.UNTRUSTED, CodexSandbox.WORKSPACE_WRITE))
        assertNull(CodexPermissionPreset.of(CodexApprovalPolicy.NEVER, CodexSandbox.READ_ONLY))
        CodexPermissionPreset.entries.forEach {
            assertEquals(it, CodexPermissionPreset.of(it.approvalPolicy, it.sandbox))
        }
    }

    @Test
    fun `the wire summary reads exactly like the protocol`() {
        assertEquals("on-request · workspaceWrite", CodexPermissionPreset.AUTO.wireSummary)
        assertEquals("never · dangerFullAccess", CodexPermissionPreset.FULL_ACCESS.wireSummary)
    }

    /** 换档只动那两个值：可写根、联网是连接层的事 */
    @Test
    fun `switching level leaves roots and network alone`() {
        val before = CodexAppServerManager.Options(writableRoots = listOf("/workspace", "/tmp/x"), networkAccess = false)
        val after = before.withPermission(CodexPermissionPreset.FULL_ACCESS)

        assertEquals(CodexApprovalPolicy.NEVER, after.approvalPolicy)
        assertEquals(CodexSandbox.DANGER_FULL_ACCESS, after.sandbox)
        assertEquals(before.copy(approvalPolicy = after.approvalPolicy, sandbox = after.sandbox), after)
    }

    @Test
    fun `resuming carries session settings but takes connection fields from the new start`() {
        val previous = CodexAppServerManager.Options(
            cwd = "/workspace/app",
            model = "gpt-x",
            effort = "high",
            summary = "none",
            writableRoots = listOf("/old"),
            networkAccess = false,
        ).withPermission(CodexPermissionPreset.READ_ONLY)
        val fresh = CodexAppServerManager.Options(model = "profile-model")

        val merged = fresh.withSessionSettingsOf(previous)

        assertEquals("gpt-x", merged.model)
        assertEquals("high", merged.effort)
        assertEquals("none", merged.summary)
        assertEquals("/workspace/app", merged.cwd)
        assertEquals(CodexPermissionPreset.READ_ONLY, merged.permissionPreset)
        assertEquals(fresh.writableRoots, merged.writableRoots)
        assertEquals(fresh.networkAccess, merged.networkAccess)
    }

    @Test
    fun `blank picks mean not sent`() {
        assertNull(null.orUnset())
        assertNull("   ".orUnset())
        assertEquals("high", " high ".orUnset())
    }

    /** 官方 ReasoningSummary 的四个 lowercase 取值；多写一个 Codex 会整轮拒掉 */
    @Test
    fun `reasoning summaries are the official values`() {
        assertEquals(listOf("auto", "concise", "detailed", "none"), CODEX_REASONING_SUMMARIES)
    }
}
