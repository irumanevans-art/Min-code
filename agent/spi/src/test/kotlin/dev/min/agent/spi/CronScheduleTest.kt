package dev.min.agent.spi

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.ClientMsgId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** cron 表达式的解析与「下一个时刻」、间隔写法、任务的下一次触发规则（不补跑），以及事件的线形状 */
class CronScheduleTest {
    private val utc = ZoneId.of("UTC")
    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun at(zone: ZoneId, text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun expr(text: String): CronExpression = (CronExpression.parse(text) as CronParsed.Ok).expression

    private fun next(text: String, from: String, zone: ZoneId = utc): String? =
        expr(text).nextAfter(at(zone, from), zone)?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().toString() }

    @Test
    fun `every five minutes lands on the next multiple of five`() {
        assertEquals("2026-10-08T12:05", next("*/5 * * * *", "2026-10-08T12:00"))
        assertEquals("严格晚于：12:03:30 之后是 12:05", "2026-10-08T12:05", next("*/5 * * * *", "2026-10-08T12:03:30"))
        assertEquals("2026-10-08T13:00", next("*/5 * * * *", "2026-10-08T12:55"))
    }

    @Test
    fun `weekday mornings skip the weekend`() {
        // 2026-10-10 是周六
        assertEquals("2026-10-12T09:00", next("0 9 * * 1-5", "2026-10-10T10:00"))
        assertEquals("2026-10-09T09:00", next("0 9 * * 1-5", "2026-10-08T09:00"))
        assertEquals("名字写法", "2026-10-12T09:00", next("0 9 * * mon-fri", "2026-10-10T10:00"))
    }

    @Test
    fun `day of month and day of week are unioned when both are restricted`() {
        // 每月 15 号或每个周一
        assertEquals("2026-10-12T00:00", next("0 0 15 * 1", "2026-10-09T00:00"))
        assertEquals("2026-10-15T00:00", next("0 0 15 * 1", "2026-10-13T00:00"))
    }

    @Test
    fun `lists ranges steps and sunday as zero or seven`() {
        assertEquals("2026-10-08T12:15", next("0,15,30 12 * * *", "2026-10-08T12:01"))
        assertEquals("2026-10-08T14:00", next("0 10-20/2 * * *", "2026-10-08T13:00"))
        assertEquals(next("0 0 * * 0", "2026-10-08T00:00"), next("0 0 * * 7", "2026-10-08T00:00"))
        assertEquals("2026-10-11T00:00", next("0 0 * * 0", "2026-10-08T00:00"))
    }

    @Test
    fun `leap day schedules wait for the next leap year and impossible dates give null`() {
        assertEquals("2028-02-29T00:00", next("0 0 29 2 *", "2026-10-08T00:00"))
        assertNull(next("0 0 30 2 *", "2026-10-08T00:00"))
    }

    @Test
    fun `expressions are read in the session time zone`() {
        // 上海 09:00 = UTC 01:00
        val shanghaiNine = expr("0 9 * * *").nextAfter(at(utc, "2026-10-08T00:00"), shanghai)!!
        assertEquals(at(shanghai, "2026-10-08T09:00"), shanghaiNine)
        assertEquals(at(utc, "2026-10-08T01:00"), shanghaiNine)
    }

    @Test
    fun `bad expressions say which field is wrong`() {
        fun reason(text: String) = (CronExpression.parse(text) as CronParsed.Invalid).reason
        assertTrue(reason("* * * *").contains("5 个字段"))
        assertTrue(reason("61 * * * *").contains("分钟"))
        assertTrue(reason("* 24 * * *").contains("小时"))
        assertTrue(reason("* * 0 * *").contains("日"))
        assertTrue(reason("* * * 13 *").contains("月"))
        assertTrue(reason("* * * * 8").contains("星期"))
        assertTrue(reason("*/0 * * * *").contains("分钟"))
        assertTrue(reason("5-1 * * * *").contains("分钟"))
        assertTrue(reason("a * * * *").contains("分钟"))
    }

    @Test
    fun `intervals parse in minutes hours and days with a one minute floor`() {
        assertEquals(30 * 60_000L, parseCronInterval("30m"))
        assertEquals(2 * 3_600_000L, parseCronInterval(" 2h "))
        assertEquals(86_400_000L, parseCronInterval("1D"))
        assertNull(parseCronInterval("30s"))
        assertNull(parseCronInterval("0m"))
        assertNull(parseCronInterval("5"))
        assertNull(parseCronInterval("every 5m"))
        assertEquals("每 90 分钟", intervalText(90 * 60_000L))
        assertEquals("每 2 小时", intervalText(2 * 3_600_000L))
        assertEquals("每 3 天", intervalText(3 * 86_400_000L))
    }

    private fun task(recurring: Boolean = true, cron: String? = null, interval: Long? = null, created: Long = 1_000_000, last: Long? = null) =
        CronTask("c1", "检查构建", recurring, 0.05, created, cron = cron, intervalMs = interval, lastFiredAtMs = last)

    @Test
    fun `a recurring interval counts from the last fire and never catches up`() {
        val every10 = 10 * 60_000L
        assertEquals(1_000_000 + every10, task(interval = every10).nextFireAt(utc))
        assertEquals(5_000_000 + every10, task(interval = every10, last = 5_000_000).nextFireAt(utc))
        // 会话是在 9_000_000 重新打开的：之前错过的不补，从打开那刻重新数
        assertEquals(9_000_000 + every10, task(interval = every10, last = 5_000_000).nextFireAt(utc, floorMs = 9_000_000))
    }

    @Test
    fun `a one shot counts from creation and ignores the floor`() {
        val oneShot = task(recurring = false, interval = 20 * 60_000L)
        assertEquals(1_000_000 + 20 * 60_000L, oneShot.nextFireAt(utc, floorMs = 99_000_000))
        val cronOneShot = task(recurring = false, cron = "30 15 * * *", created = at(utc, "2026-10-08T15:00"))
        assertEquals(at(utc, "2026-10-08T15:30"), cronOneShot.nextFireAt(utc))
    }

    @Test
    fun `a recurring cron task asks the expression for the next match after its anchor`() {
        val t = task(cron = "0 9 * * *", created = at(utc, "2026-10-08T10:00"))
        assertEquals(at(utc, "2026-10-09T09:00"), t.nextFireAt(utc))
        assertEquals(at(utc, "2026-10-10T09:00"), t.copy(lastFiredAtMs = at(utc, "2026-10-09T09:00")).nextFireAt(utc))
        assertEquals("cron 0 9 * * *", t.scheduleText())
        assertEquals("每 5 分钟", task(interval = 300_000).scheduleText())
    }

    @Test
    fun `cron events keep their wire shape and omit defaults`() {
        fun check(event: SessionEvent, json: String) {
            val text = AgentJson.encodeToString(SessionEvent.serializer(), event)
            assertEquals(AgentJson.parseToJsonElement(json), AgentJson.parseToJsonElement(text))
            assertEquals(event, AgentJson.decodeFromString(SessionEvent.serializer(), text))
        }
        check(
            CronScheduled("c1", "检查构建", true, 0.05, 1000, cron = "0 9 * * 1-5", expiresAtMs = 604801000),
            """{"type":"cron_scheduled","cronId":"c1","prompt":"检查构建","recurring":true,"maxBudgetUsd":0.05,"scheduledAtMs":1000,"cron":"0 9 * * 1-5","expiresAtMs":604801000}""",
        )
        check(
            CronScheduled("c2", "提醒我", false, 0.1, 5, intervalMs = 1_200_000),
            """{"type":"cron_scheduled","cronId":"c2","prompt":"提醒我","recurring":false,"maxBudgetUsd":0.1,"scheduledAtMs":5,"intervalMs":1200000}""",
        )
        check(
            CronScheduled("c3", "晨报", true, null, 7, cron = "0 9 * * *", expiresAtMs = 604800007),
            """{"type":"cron_scheduled","cronId":"c3","prompt":"晨报","recurring":true,"scheduledAtMs":7,"cron":"0 9 * * *","expiresAtMs":604800007}""",
        )
        check(CronFired("c1", 2000, ClientMsgId("cron:c1:1")), """{"type":"cron_fired","cronId":"c1","firedAtMs":2000,"clientMsgId":"cron:c1:1"}""")
        check(CronCancelled("c1"), """{"type":"cron_cancelled","cronId":"c1"}""")
        check(CronExpired("c1", CronExpiry.Budget, "花了 0.06"), """{"type":"cron_expired","cronId":"c1","reason":"budget","detail":"花了 0.06"}""")
        check(
            InputReceived(ClientMsgId("cron:c1:1"), listOf(dev.min.agent.protocol.TextPart("检查构建")), Delivery.Queue, InputOrigin.Cron("c1")),
            """{"type":"input_received","clientMsgId":"cron:c1:1","parts":[{"type":"text","text":"检查构建"}],"delivery":"queue","origin":{"type":"cron","cronId":"c1"}}""",
        )
    }
}
