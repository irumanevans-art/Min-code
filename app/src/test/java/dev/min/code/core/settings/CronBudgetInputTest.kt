package dev.min.code.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CronBudgetInputTest {
    @Test fun `empty means no cap - that is the default, not an error`() {
        assertEquals(CronBudgetInput.NoCap, parseCronBudgetInput(""))
        assertEquals(CronBudgetInput.NoCap, parseCronBudgetInput("   "))
        assertEquals(CronBudgetInput.NoCap, parseCronBudgetInput("$"))
    }

    @Test fun `a positive decimal is a cap, with or without a dollar sign and spaces`() {
        assertEquals(CronBudgetInput.Cap(0.5), parseCronBudgetInput("0.5"))
        assertEquals(CronBudgetInput.Cap(2.0), parseCronBudgetInput(" $ 2 "))
        assertEquals(CronBudgetInput.Cap(0.25), parseCronBudgetInput(".25"))
        assertEquals(CronBudgetInput.Cap(10.0), parseCronBudgetInput("10"))
    }

    @Test fun `zero, negatives, words and thousands separators are refused`() {
        listOf("0", "0.0", "-1", "abc", "1,000", "1e3", "NaN", "Infinity", "1.2.3", "5 usd").forEach {
            assertEquals("「$it」", CronBudgetInput.Invalid, parseCronBudgetInput(it))
        }
    }

    @Test fun `an absurdly large number is a typo, not a cap`() {
        assertEquals(CronBudgetInput.Cap(MAX_CRON_BUDGET_USD), parseCronBudgetInput("100000"))
        assertEquals(CronBudgetInput.Invalid, parseCronBudgetInput("100000.01"))
        assertEquals(CronBudgetInput.Invalid, parseCronBudgetInput("99999999999999999999"))
    }
}
