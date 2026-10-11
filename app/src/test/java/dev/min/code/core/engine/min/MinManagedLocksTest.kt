package dev.min.code.core.engine.min

import dev.min.agent.kernel.TeamLimits
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.telemetry.TelemetrySettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 托管层锁住的 Min 设置项：读哪几个键、怎么解释、生效值怎么换（与桌面 CLI 同一套解析） */
class MinManagedLocksTest {
    private fun locks(json: String) = managedLocksOf(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `no managed file or an empty one locks nothing`() {
        assertEquals(ManagedLocks(), managedLocksOf(null))
        assertEquals(ManagedLocks(), locks("{}"))
    }

    @Test
    fun `the cron switch is locked whenever the managed env names it`() {
        assertEquals(false, locks("""{"env":{"CLAUDE_CODE_DISABLE_CRON":"1"}}""").cronEnabled)
        // 写了但不是真值：锁成开——管理员明确要它开着，用户也关不掉
        assertEquals(true, locks("""{"env":{"CLAUDE_CODE_DISABLE_CRON":"0"}}""").cronEnabled)
        assertNull(locks("""{"env":{"OTHER":"1"}}""").cronEnabled)
    }

    @Test
    fun `budget and team size use the same parsing as the desktop CLI`() {
        assertEquals(2.5, locks("""{"cronMaxBudgetUsd":2.5}""").cronMaxBudgetUsd!!, 0.0)
        // 写错的上限和桌面一样当没设：不锁
        assertNull(locks("""{"cronMaxBudgetUsd":-1}""").cronMaxBudgetUsd)
        assertEquals(5, locks("""{"teamMaxConcurrent":5}""").teamMaxConcurrent)
        assertEquals(TeamLimits.MAX_CONCURRENT, locks("""{"teamMaxConcurrent":99}""").teamMaxConcurrent)
        assertEquals(TeamLimits.DEFAULT_MAX_CONCURRENT, locks("""{"teamMaxConcurrent":"many"}""").teamMaxConcurrent)
    }

    @Test
    fun `telemetry switch, endpoint and headers come from the managed env, and opt-outs win`() {
        val on = locks("""{"env":{"CLAUDE_CODE_ENABLE_TELEMETRY":"1","OTEL_EXPORTER_OTLP_ENDPOINT":" https://otel.corp:4318 ","OTEL_EXPORTER_OTLP_HEADERS":"Authorization=Bearer x"}}""")
        assertEquals(true, on.telemetryEnabled)
        assertEquals("https://otel.corp:4318", on.telemetryEndpoint)
        assertEquals("Authorization=Bearer x", on.telemetryHeaders)
        assertEquals(false, locks("""{"env":{"CLAUDE_CODE_ENABLE_TELEMETRY":"0"}}""").telemetryEnabled)
        assertEquals(false, locks("""{"env":{"CLAUDE_CODE_ENABLE_TELEMETRY":"1","DISABLE_TELEMETRY":"1"}}""").telemetryEnabled)
        assertEquals(false, locks("""{"env":{"DO_NOT_TRACK":"1"}}""").telemetryEnabled)
        assertNull(locks("""{"env":{}}""").telemetryEnabled)
    }

    @Test
    fun `a broken managed file is reported even though nothing specific can be locked`() {
        assertTrue(managedLocksOf(null, broken = true).broken)
        assertFalse(managedLocksOf(JsonObject(emptyMap())).broken)
    }

    @Test
    fun `effective settings take the locked values and keep the rest`() {
        val mine = AppSettings(
            cronEnabled = true, cronMaxBudgetUsd = null, teamMaxConcurrent = 2,
            telemetry = TelemetrySettings(enabled = false, endpoint = "http://mine", headersConfigured = false),
        )
        val effective = mine.withManaged(
            ManagedLocks(cronEnabled = false, teamMaxConcurrent = 6, telemetryEnabled = true, telemetryHeaders = "A=b"),
        )
        assertFalse(effective.cronEnabled)
        assertNull(effective.cronMaxBudgetUsd)
        assertEquals(6, effective.teamMaxConcurrent)
        assertTrue(effective.telemetry.enabled)
        assertEquals("http://mine", effective.telemetry.endpoint)
        assertTrue(effective.telemetry.headersConfigured)
        assertEquals(mine, mine.withManaged(ManagedLocks()))
    }
}
