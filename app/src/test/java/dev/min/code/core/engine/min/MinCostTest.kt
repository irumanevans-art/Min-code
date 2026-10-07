package dev.min.code.core.engine.min

import dev.min.agent.llm.Cost
import dev.min.agent.llm.ModelPricing
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.ModelFallback
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 花费账本：增量记到当时的模型头上，币种原样，没价格不显示。 */
class MinCostTest {
    private val root = SessionId("s_root")
    private val usd = ModelPricing(currency = "USD", inputPerMTok = 3.0, outputPerMTok = 15.0, asOf = "2026-10-01")
    private val cny = ModelPricing(currency = "CNY", inputPerMTok = 2.0, outputPerMTok = 8.0, asOf = "2026-10-01")
    private val other = ModelRef("test", "other")
    private val prices = mapOf(T_MODEL to usd, other to cny)

    private fun reply(turn: String, model: ModelRef, usage: Usage, id: String = "a-$turn") = listOf<SessionEvent>(
        ModelRequestStarted(TurnId(turn), RequestId("r-$id"), 1, model, "fp"),
        AssistantMessageCompleted(TurnId(turn), RequestId("r-$id"), MessageId(id), listOf(TextPart("ok")), usage, StopReason.EndTurn),
    )

    private fun turn(turn: String, model: ModelRef, usage: Usage) =
        listOf<SessionEvent>(TurnStarted(TurnId(turn), TurnTrigger.User)) + reply(turn, model, usage) +
            TurnEnded(TurnId(turn), TurnOutcome.Completed, usage, 0)

    private val oneM = Usage(inputTokens = 1_000_000)

    @Test fun `no usage means no cost`() {
        val ledger = MinCostLedger { prices[it] }
        ledger.observe(mapOf(root to foldOf()))
        assertNull(ledger.label())
    }

    @Test fun `usage is priced by the model that spent it`() {
        val ledger = MinCostLedger { prices[it] }
        ledger.observe(mapOf(root to foldOf(*turn("t1", T_MODEL, oneM).toTypedArray())))
        assertEquals("$3.00", ledger.label())
    }

    @Test fun `a frame is only counted once and the next turn adds its own increment`() {
        val ledger = MinCostLedger { prices[it] }
        val first = foldOf(*turn("t1", T_MODEL, oneM).toTypedArray())
        ledger.observe(mapOf(root to first))
        ledger.observe(mapOf(root to first))
        val second = foldOf(*(turn("t1", T_MODEL, oneM) + turn("t2", T_MODEL, oneM)).toTypedArray())
        ledger.observe(mapOf(root to second))
        assertEquals("$6.00", ledger.label())
    }

    @Test fun `a turn in progress counts as it streams and is not counted again when it ends`() {
        val ledger = MinCostLedger { prices[it] }
        val running = foldOf(TurnStarted(TurnId("t1"), TurnTrigger.User), *reply("t1", T_MODEL, oneM).toTypedArray())
        ledger.observe(mapOf(root to running))
        assertEquals("$3.00", ledger.label())
        val ended = foldOf(*turn("t1", T_MODEL, oneM).toTypedArray())
        ledger.observe(mapOf(root to ended))
        assertEquals("结束后总量连续，不能重复算", "$3.00", ledger.label())
    }

    @Test fun `a fallback turn is priced by the fallback model and the primary by its own`() {
        val ledger = MinCostLedger { prices[it] }
        val primaryOnly = foldOf(*turn("t1", T_MODEL, oneM).toTypedArray())
        ledger.observe(mapOf(root to primaryOnly))
        // 第二回合主模型倒了，换到回退模型 other 上答完（回退生效中 activeModel = other）
        val inFallback = foldOf(
            *turn("t1", T_MODEL, oneM).toTypedArray(),
            TurnStarted(TurnId("t2"), TurnTrigger.User),
            ModelRequestStarted(TurnId("t2"), RequestId("rf"), 1, T_MODEL, "fp"),
            ModelRequestFailed(TurnId("t2"), RequestId("rf"), 1, ModelFailureKind.Overloaded, "529", 529, false),
            ModelFallback(TurnId("t2"), RequestId("rf"), T_MODEL, other, ModelFailureKind.Overloaded, "529"),
            *reply("t2", other, oneM, id = "af").toTypedArray(),
        )
        ledger.observe(mapOf(root to inFallback))
        assertEquals("$3.00 + ¥2.0000".replace("¥2.0000", "¥2.00"), ledger.label())
    }

    @Test fun `a model without a price hides the whole figure rather than showing part of it`() {
        val ledger = MinCostLedger { if (it == T_MODEL) usd else null }
        ledger.observe(mapOf(root to foldOf(*turn("t1", T_MODEL, oneM).toTypedArray())))
        assertEquals("$3.00", ledger.label())
        val mixed = foldOf(*turn("t1", T_MODEL, oneM).toTypedArray(), TurnStarted(TurnId("t2"), TurnTrigger.User),
            *reply("t2", T_MODEL, oneM).toTypedArray(), TurnEnded(TurnId("t2"), TurnOutcome.Completed, oneM, 0),
            // 然后设置里换成没价格的模型
            dev.min.agent.spi.SettingsChanged(model = other), *turn("t3", other, oneM).toTypedArray())
        ledger.observe(mapOf(root to mixed))
        assertNull(ledger.label())
    }

    @Test fun `a sub-agent session adds its own spend`() {
        val ledger = MinCostLedger { prices[it] }
        val child = SessionId("s_child")
        ledger.observe(
            mapOf(
                root to foldOf(*turn("t1", T_MODEL, oneM).toTypedArray()),
                child to foldOf(*turn("c1", T_MODEL, oneM).toTypedArray(), id = "s_child"),
            ),
        )
        assertEquals("$6.00", ledger.label())
    }

    @Test fun `a reopened session books its history to the model in use now`() {
        val ledger = MinCostLedger { prices[it] }
        // 新账本第一次见到一个带历史的会话：整份记给此刻的模型（文档里写明的估算）
        ledger.observe(mapOf(root to foldOf(*turn("t1", other, oneM).toTypedArray(), dev.min.agent.spi.SettingsChanged(model = T_MODEL))))
        assertEquals("$3.00", ledger.label())
    }

    @Test fun `cost text keeps the currency as priced and widens the decimals below one unit`() {
        assertEquals("$0.0123", formatCost(Cost(0.0123, "USD")))
        assertEquals("¥12.35", formatCost(Cost(12.345, "CNY")))
        assertEquals("EUR 0.5000", formatCost(Cost(0.5, "EUR")))
        assertEquals("$1.00", formatCost(Cost(1.0, "USD")))
    }
}
