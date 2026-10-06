package dev.min.agent.kernel

import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.ModelFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackPolicyTest {
    private val primary = ModelRef("p", "main")
    private val a = ModelRef("p", "a")
    private val b = ModelRef("q", "b")
    private val c = ModelRef("p", "c")

    @Test
    fun `only overload, server and unavailable are worth switching for`() {
        val switching = ModelFailureKind.entries.filter(FallbackPolicy::triggers).toSet()
        assertEquals(setOf(ModelFailureKind.Overloaded, ModelFailureKind.Server, ModelFailureKind.ModelUnavailable), switching)
        // 逐个点名：这些换了模型也一样失败，或者会掩盖真问题
        for (kind in listOf(
            ModelFailureKind.Auth, ModelFailureKind.BadRequest, ModelFailureKind.RateLimited,
            ModelFailureKind.ContextOverflow, ModelFailureKind.Network, ModelFailureKind.Other,
        )) assertFalse("$kind", FallbackPolicy.triggers(kind))
    }

    @Test
    fun `candidates follow the chain order after the current model`() {
        val chain = listOf(a, b, c)
        assertEquals(listOf(a, b, c), FallbackPolicy.after(chain, primary, primary))
        assertEquals(listOf(b, c), FallbackPolicy.after(chain, primary, a))
        assertEquals(listOf(c), FallbackPolicy.after(chain, primary, b))
        assertTrue(FallbackPolicy.after(chain, primary, c).isEmpty())
    }

    @Test
    fun `the primary and repeats never become candidates and an unknown current restarts the chain`() {
        assertEquals(listOf(a, b), FallbackPolicy.after(listOf(a, primary, a, b), primary, primary))
        // 回合中途 /model 改了主模型：当前不在链上，从链头重新来
        assertEquals(listOf(a, b), FallbackPolicy.after(listOf(a, b), ModelRef("p", "renamed"), ModelRef("p", "gone")))
        // 主模型被改成链上的某一项：那一项不再是备用
        assertEquals(listOf(b), FallbackPolicy.after(listOf(a, b), a, a))
    }
}
