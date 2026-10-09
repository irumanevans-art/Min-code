package dev.min.code.ui.settings

import dev.min.code.core.settings.CronBudgetInput
import dev.min.code.core.settings.parseCronBudgetInput
import org.junit.Assert.assertEquals
import org.junit.Test

class MinSchedulingSettingsTest {
    @Test fun `the cap shown when the dialog reopens is plain decimal and reads back as the same cap`() {
        for (usd in listOf(0.0001, 0.00005, 0.5, 2.0, 12.25, 1000.0)) {
            val shown = trimmed(usd)
            assertEquals(shown, false, shown.contains('E'))
            assertEquals(shown, CronBudgetInput.Cap(usd), parseCronBudgetInput(shown))
        }
        assertEquals("2", trimmed(2.0))
        assertEquals("0.0001", trimmed(0.0001))
    }
}
