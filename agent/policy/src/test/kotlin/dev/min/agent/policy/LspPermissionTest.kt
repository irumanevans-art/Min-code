package dev.min.agent.policy

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.*
import org.junit.Assert.*
import org.junit.Test

class LspPermissionTest {
    @Test fun `Read deny blocks LSP bare tool and path in every mode`() {
        for (rule in listOf("Read", "Read(//work/secret/**)")) for (mode in PermissionMode.entries) {
            val deny = PermissionRule.parse(RuleKind.Deny, rule, "user")!!
            val ask = PermissionAsk("LSP", "hover", paths = listOf(PathAccess(GuestPath("/work/secret/a.t"), AccessKind.Read, PathScope.Workspace)))
            val context = PolicyContext(mode, listOf(deny), cwd = GuestPath("/work"))
            assertTrue("$mode $rule", PolicyEngine.matchesCondition(rule, ask, context))
            assertTrue("$mode $rule", PolicyEngine.evaluate(ask, true, context) is Verdict.Deny)
        }
    }
}
