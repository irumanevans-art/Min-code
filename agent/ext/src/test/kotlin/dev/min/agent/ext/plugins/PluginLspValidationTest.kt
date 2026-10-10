package dev.min.agent.ext.plugins

import dev.min.agent.testkit.FakeFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PluginLspValidationTest {
    private suspend fun load(config: String, declared: Boolean = true, entry: Boolean = false): PluginLoadOutcome {
        val fs = FakeFs()
        val manifest = """{"name":"check","lspServers":{"test":$config}}"""
        if (!entry) fs.put("/plugins/check/.claude-plugin/plugin.json", if (declared) manifest else """{"name":"check"}""")
        if (!declared) fs.put("/plugins/check/.lsp.json", """{"test":$config}""")
        return PluginLoader(fs).load(PluginRequest("/plugins/check", ref = null, entry = if (entry) Json.parseToJsonElement(manifest).jsonObject else null))
    }

    @Test fun `strict fields reject every invalid declared server and marketplace entry`(): Unit = runBlocking {
        val base = """"command":"fake","extensionToLanguage":{".t":"test"}"""
        val bad = listOf(
            """"command":"fake --stdio","extensionToLanguage":{".t":"test"}""",
            """"command":"","extensionToLanguage":{".t":"test"}""",
            """"command":"fake","extensionToLanguage":{".t":2}""",
        ) + listOf("\"args\":1", "\"args\":[1]", "\"env\":{\"A\":1}", "\"transport\":\"http\"",
            "\"startupTimeout\":0", "\"startupTimeout\":\"3\"", "\"shutdownTimeout\":1.5", "\"requestTimeout\":0", "\"requestTimeout\":\"30\"",
            "\"restartOnCrash\":\"false\"", "\"maxRestarts\":-1", "\"diagnostics\":1", "\"workspaceFolder\":2", "\"unknown\":true").map { "$base,$it" }
        for (body in bad) for (entry in listOf(false, true)) {
            val result = load("{$body}", entry = entry)
            assertNull("entry=$entry $body: ${result.errors}", result.plugin)
            assertTrue(result.errors.isNotEmpty())
        }
    }

    @Test fun `unknown field in default config rejects plugin instead of silently skipping`(): Unit = runBlocking {
        assertNull(load("""{"command":"fake","extensionToLanguage":{".t":"test"},"unknown":true}""", declared = false).plugin)
    }

    @Test fun `valid complete configuration and absolute command with spaces load`(): Unit = runBlocking {
        assertNotNull(load("""{"command":"/opt/my server","extensionToLanguage":{".t":"test"},"args":["--stdio"],"env":{"A":"b"},"transport":"socket","initializationOptions":{},"settings":{},"workspaceFolder":"/work","startupTimeout":10,"shutdownTimeout":10,"requestTimeout":100,"restartOnCrash":false,"maxRestarts":0,"diagnostics":false}""").plugin)
    }
}
