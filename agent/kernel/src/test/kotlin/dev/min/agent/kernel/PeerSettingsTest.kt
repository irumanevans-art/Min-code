package dev.min.agent.kernel

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SettingsLayer
import dev.min.agent.spi.GuestPath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `crossSessionInbound` 与 `dialogExpiry` 的取值（CC settings-reference）：
 * - crossSessionInbound：先看 managed，再 `--settings`，再 user，取第一个设了的；project / local 只在比它更严时才生效
 *   （refuse 严于 hold 严于 accept），受信来源都没设时 project / local 的 hold 或 refuse 仍然生效、accept 不算；
 *   认不出的值：user / project / local / `--settings` 当 hold，managed 当 refuse。
 * - dialogExpiry：user 或 managed；`60s` / `5m` / `10m` / `never`，默认 5m；环境变量 CLAUDE_CODE_USER_DIALOG_TIMEOUT_MS 优先。
 */
class PeerSettingsTest {
    private fun layer(source: ConfigSource, vararg entries: Pair<String, Any>) = SettingsLayer(
        source = source, path = GuestPath("/x/${source.label}.json"), baseDir = GuestPath("/x"),
        raw = JsonObject(entries.associate { (k, v) -> k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) }),
    )

    private fun inbound(source: ConfigSource, value: Any) = layer(source, "crossSessionInbound" to value)
    private fun resolve(vararg layers: SettingsLayer) = crossSessionInboundFrom(layers.toList())

    @Test fun `nothing set means the per-message default`() {
        assertNull(resolve())
        assertNull(resolve(layer(ConfigSource.User, "model" to "x")))
    }

    @Test fun `a user value applies as it is`() {
        assertEquals(PeerInboundPolicy.Accept, resolve(inbound(ConfigSource.User, "accept")))
        assertEquals(PeerInboundPolicy.Hold, resolve(inbound(ConfigSource.User, "hold")))
        assertEquals(PeerInboundPolicy.Refuse, resolve(inbound(ConfigSource.User, "refuse")))
    }

    @Test fun `managed wins over the settings flag, which wins over user`() {
        assertEquals(PeerInboundPolicy.Refuse, resolve(inbound(ConfigSource.User, "accept"), inbound(ConfigSource.Cli, "hold"), inbound(ConfigSource.Managed, "refuse")))
        assertEquals(PeerInboundPolicy.Accept, resolve(inbound(ConfigSource.User, "refuse"), inbound(ConfigSource.Cli, "accept")))
        // managed 说 accept、user 说 refuse：第一个设了的就是 managed
        assertEquals(PeerInboundPolicy.Accept, resolve(inbound(ConfigSource.User, "refuse"), inbound(ConfigSource.Managed, "accept")))
    }

    @Test fun `a project or local value counts only when it is stricter than the trusted one`() {
        assertEquals(PeerInboundPolicy.Refuse, resolve(inbound(ConfigSource.User, "accept"), inbound(ConfigSource.Project, "refuse")))
        assertEquals(PeerInboundPolicy.Hold, resolve(inbound(ConfigSource.User, "accept"), inbound(ConfigSource.Local, "hold")))
        // 项目想放宽：不认
        assertEquals(PeerInboundPolicy.Refuse, resolve(inbound(ConfigSource.User, "refuse"), inbound(ConfigSource.Project, "accept")))
        assertEquals(PeerInboundPolicy.Hold, resolve(inbound(ConfigSource.User, "hold"), inbound(ConfigSource.Project, "accept")))
        // managed 的值同样只能被更严的 project 加严
        assertEquals(PeerInboundPolicy.Refuse, resolve(inbound(ConfigSource.Managed, "accept"), inbound(ConfigSource.Local, "refuse")))
    }

    @Test fun `with no trusted value a project hold or refuse still applies and an accept does not`() {
        assertEquals(PeerInboundPolicy.Hold, resolve(inbound(ConfigSource.Project, "hold")))
        assertEquals(PeerInboundPolicy.Refuse, resolve(inbound(ConfigSource.Local, "refuse")))
        assertNull(resolve(inbound(ConfigSource.Project, "accept")))
        // 项目与 local 各写一个：取更严的
        assertEquals(PeerInboundPolicy.Refuse, resolve(inbound(ConfigSource.Project, "hold"), inbound(ConfigSource.Local, "refuse")))
    }

    @Test fun `an unrecognised value holds, except in managed settings where it refuses`() {
        assertEquals(PeerInboundPolicy.Hold, resolve(inbound(ConfigSource.User, "maybe")))
        assertEquals(PeerInboundPolicy.Hold, resolve(inbound(ConfigSource.Cli, 3)))
        assertEquals(PeerInboundPolicy.Hold, resolve(inbound(ConfigSource.Project, "maybe")))
        assertEquals(PeerInboundPolicy.Refuse, resolve(inbound(ConfigSource.Managed, "maybe")))
        // 认不出的 user 值是 hold，仍可被更严的 project 加严，不能被放宽
        assertEquals(PeerInboundPolicy.Refuse, resolve(inbound(ConfigSource.User, "maybe"), inbound(ConfigSource.Project, "refuse")))
    }

    private fun expiry(vararg layers: SettingsLayer, env: Map<String, String> = emptyMap()) = dialogExpiryMsFrom(layers.toList(), env::get)

    @Test fun `the dialog deadline defaults to five minutes and reads user or managed values`() {
        assertEquals(300_000L, expiry())
        assertEquals(60_000L, expiry(layer(ConfigSource.User, "dialogExpiry" to "60s")))
        assertEquals(600_000L, expiry(layer(ConfigSource.User, "dialogExpiry" to "10m")))
        assertNull("never = 没有期限", expiry(layer(ConfigSource.User, "dialogExpiry" to "never")))
        // managed 覆盖 user
        assertEquals(60_000L, expiry(layer(ConfigSource.User, "dialogExpiry" to "10m"), layer(ConfigSource.Managed, "dialogExpiry" to "60s")))
    }

    @Test fun `a project file cannot change the dialog deadline, and a bad value falls back to the default`() {
        assertEquals(300_000L, expiry(layer(ConfigSource.Project, "dialogExpiry" to "never")))
        assertEquals(300_000L, expiry(layer(ConfigSource.Local, "dialogExpiry" to "never")))
        assertEquals(300_000L, expiry(layer(ConfigSource.User, "dialogExpiry" to "2h")))
    }

    @Test fun `the environment variable overrides the setting for one session`() {
        assertEquals(1_500L, expiry(layer(ConfigSource.User, "dialogExpiry" to "10m"), env = mapOf("CLAUDE_CODE_USER_DIALOG_TIMEOUT_MS" to "1500")))
        assertEquals(600_000L, expiry(layer(ConfigSource.User, "dialogExpiry" to "10m"), env = mapOf("CLAUDE_CODE_USER_DIALOG_TIMEOUT_MS" to "oops")))
    }
}
