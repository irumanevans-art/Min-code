package dev.min.agent.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessEnvTest {
    private val host = mapOf(
        "HOME" to "/home/u", "PATH" to "/usr/bin", "LANG" to "C", "TZ" to "UTC",
        "ANTHROPIC_API_KEY" to "sk-ant-secret", "AWS_SECRET_ACCESS_KEY" to "aws", "SYSTEMROOT" to "C:\\Windows",
    )

    private fun build(extra: Map<String, String> = emptyMap(), secrets: Set<String> = emptySet(), windows: Boolean = false) =
        ProcessEnv.build({ host[it] }, extra, secrets, windows)

    @Test
    fun `只继承白名单 其余宿主变量不进子进程`() {
        val env = build()
        assertEquals(setOf("HOME", "PATH", "LANG", "TZ"), env.keys)
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"))
        assertFalse(env.containsKey("AWS_SECRET_ACCESS_KEY"))
    }

    @Test
    fun `Windows 多继承系统目录等变量 其他平台不继承`() {
        assertTrue(build(windows = true).containsKey("SYSTEMROOT"))
        assertFalse(build(windows = false).containsKey("SYSTEMROOT"))
    }

    @Test
    fun `spec 的 env 叠在继承之上 同名覆盖`() {
        val env = build(extra = mapOf("LANG" to "zh_CN.UTF-8", "MY_FLAG" to "1"))
        assertEquals("zh_CN.UTF-8", env["LANG"])
        assertEquals("1", env["MY_FLAG"])
    }

    @Test
    fun `凭证名无论来自继承还是 spec env 都剔除 且不分大小写`() {
        val env = build(
            extra = mapOf("ANTHROPIC_API_KEY" to "from-config", "anthropic_api_key" to "lower", "OK" to "yes"),
            secrets = setOf("ANTHROPIC_API_KEY", "TZ"),
        )
        assertEquals(setOf("HOME", "PATH", "LANG", "OK"), env.keys)
        assertFalse(env.values.any { it.contains("secret") || it == "from-config" || it == "lower" })
    }

    @Test
    fun `Windows 上 Path 与 PATH 是同一个变量 spec 的覆盖不产生两份`() {
        val env = build(extra = mapOf("Path" to "C:\\mine"), windows = true)
        assertEquals(1, env.keys.count { it.equals("path", ignoreCase = true) })
        assertEquals("C:\\mine", env["Path"])
        assertNull(env["PATH"])
    }
}
