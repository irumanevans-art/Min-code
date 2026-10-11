package dev.min.agent.cli

import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.protocol.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Path

/** `--model` 认别名：解析成目录里的型号，`[1m]` 把窗口标到 1M，别的供应商没有这一档就启动报错并点名 */
class ModelAliasCliTest {
    private fun select(vararg argv: String) = resolveModel(parseArgs(argv.toList().toTypedArray(), Path.of(".")), Catalog.bundled())

    @Test
    fun `an alias on --model becomes the catalog model of that tier`() {
        val s = select("--provider", "anthropic", "--model", "sonnet")
        assertEquals(ModelRef("anthropic", "claude-sonnet-5-5"), s.profile.ref)
        assertEquals(1_000_000, s.profile.contextWindow)
    }

    @Test
    fun `the 1m suffix is accepted and an explicit --context still wins`() {
        assertEquals(ModelRef("anthropic", "claude-opus-5-5"), select("--provider", "anthropic", "--model", "opus[1m]").profile.ref)
        assertEquals(123_000, select("--provider", "anthropic", "--model", "haiku[1m]", "--context", "123000").profile.contextWindow)
    }

    @Test
    fun `a tier the provider does not have stops startup and names the tier`() {
        val e = assertThrows(UsageException::class.java) { select("--provider", "openai", "--model", "sonnet") }
        assertEquals(true, e.message!!.contains("sonnet"))
    }
}
