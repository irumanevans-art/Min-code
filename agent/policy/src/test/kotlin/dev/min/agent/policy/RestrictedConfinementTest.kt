package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `--restricted`（CC cli-reference :123）：内置文件工具限制在工作目录内（cwd + additionalDirectories）。
 * 工作目录外的读写直接拒绝，与模式、允许规则无关——允许规则与 acceptEdits 都不能越过这条线。
 * 对照组：同一个调用不开限制时照常判定，这样能看出拒绝来自限制本身。
 */
class RestrictedConfinementTest {
    private val workspace = "/workspace"

    /** 工作目录 = /workspace；别的路径按 Rootfs 分级（与宿主 scopeOf 的形状一致） */
    private fun scopeOf(path: GuestPath): PathScope =
        if (path.value == workspace || path.value.startsWith("$workspace/")) PathScope.Workspace else PathScope.Rootfs

    private fun read(path: String) = PermissionAsk("Read", "读 $path", paths = listOf(PathAccess(GuestPath(path), AccessKind.Read, PathScope.Rootfs)))
    private fun write(path: String) = PermissionAsk("Write", "写 $path", paths = listOf(PathAccess(GuestPath(path), AccessKind.Write, PathScope.Rootfs)))

    private fun ctx(
        mode: PermissionMode = PermissionMode.Default,
        confine: Boolean,
        additional: List<GuestPath> = emptyList(),
        rules: List<PermissionRule> = emptyList(),
    ) = PolicyContext(
        mode = mode, rules = rules, cwd = GuestPath(workspace), scopeOf = ::scopeOf,
        additionalDirectories = additional, confineToWorkingDirectories = confine,
    )

    private fun verdict(ask: PermissionAsk, context: PolicyContext, readOnly: Boolean = true) = PolicyEngine.evaluate(ask, readOnly, context)

    @Test
    fun `限制开启：工作目录外的只读也被拒`() {
        assertTrue(verdict(read("/etc/hosts"), ctx(confine = true)) is Verdict.Deny)
    }

    @Test
    fun `对照组：不开限制，同一个读放行（只读、默认模式）`() {
        assertTrue(verdict(read("/etc/hosts"), ctx(confine = false)) is Verdict.Allow)
    }

    @Test
    fun `工作目录内的读照常放行`() {
        assertTrue(verdict(read("/workspace/a.kt"), ctx(confine = true)) is Verdict.Allow)
    }

    @Test
    fun `工作目录内的写照常询问，不是拒绝`() {
        assertEquals(Verdict.Ask, verdict(write("/workspace/a.kt"), ctx(confine = true), readOnly = false))
    }

    @Test
    fun `工作目录外的写在 acceptEdits 下也被拒，不降级为询问`() {
        assertTrue(verdict(write("/etc/motd"), ctx(PermissionMode.AcceptEdits, confine = true), readOnly = false) is Verdict.Deny)
    }

    @Test
    fun `允许规则不能越过限制`() {
        val allowRead = PermissionRule.parse(RuleKind.Allow, "Read", "user")!!
        assertTrue(verdict(read("/etc/hosts"), ctx(confine = true, rules = listOf(allowRead))) is Verdict.Deny)
    }

    @Test
    fun `additionalDirectories 里的文件算工作目录内`() {
        assertTrue(verdict(read("/data/notes.md"), ctx(confine = true, additional = listOf(GuestPath("/data")))) is Verdict.Allow)
        // 同一个路径不在附加目录里就拒绝
        assertTrue(verdict(read("/data/notes.md"), ctx(confine = true)) is Verdict.Deny)
    }

    @Test
    fun `拒绝消息说明是受限模式，并告诉模型换个做法`() {
        val deny = verdict(read("/etc/hosts"), ctx(confine = true)) as Verdict.Deny
        assertTrue(deny.messageToModel, deny.messageToModel.contains("--restricted"))
    }
}
