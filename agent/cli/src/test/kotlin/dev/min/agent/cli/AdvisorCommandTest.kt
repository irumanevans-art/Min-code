package dev.min.agent.cli

import dev.min.agent.kernel.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

class AdvisorCommandTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `advisor resolver supports arbitrary configured provider model and preserves known pricing`() {
        val catalog = dev.min.agent.llm.catalog.Catalog.bundled()
        val provider = dev.min.agent.llm.ProviderDescriptor("custom", "http://localhost", dev.min.agent.llm.Wire.OpenAIResponses, dev.min.agent.llm.AuthScheme.Bearer)
        val session = ModelSelection(provider, TEST_PROFILE.copy(ref = ModelRef("custom", "main")), "CUSTOM_KEY")
        assertSame(session, resolveAdvisorSelection(session.profile.ref, catalog, session))
        val custom = resolveAdvisorSelection(ModelRef("custom", "oracle"), catalog, session)
        assertSame(provider, custom.provider); assertEquals("CUSTOM_KEY", custom.keyEnv)
        assertNull(custom.profile.pricing)
        val known = resolveAdvisorSelection(ModelRef("openai", "gpt-5.4"), catalog, session)
        assertEquals("OPENAI_API_KEY", known.keyEnv); assertNotNull(known.profile.pricing)
    }

    @Test fun `bad user settings are not overwritten`() {
        val file = tmp.root.toPath().resolve("settings.json"); Files.writeString(file, "[]")
        assertThrows(IllegalStateException::class.java) { saveAdvisor(file, ModelRef("p", "m")) }
        assertEquals("[]", Files.readString(file))
    }

    @Test fun `command persists selection prints status and off disables without model call`() = runBlocking {
        val model = ScriptedModel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val base = testDeps(model, emptyList())
            val deps = KernelDeps(base.clock, base.ids, { ref -> ResolvedModel(TEST_PROFILE.copy(ref = ref), model) }, base.tools, base.host, base.platform, base.zone)
            val rt = SessionHub(FileSessionStore(tmp.root.toPath().resolve("sessions"), { 0L }), deps, scope).create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
            val file = tmp.root.toPath().resolve(".claude/settings.json")
            Files.createDirectories(file.parent); Files.writeString(file, """{"effort":"high"}""")
            val bytes = ByteArrayOutputStream(); val out = PrintStream(bytes)
            val checked = mutableListOf<ModelRef>()
            assertTrue(advisorCommand(rt, "other/oracle", file, { checked += it }, out))
            assertEquals(listOf(ModelRef("other", "oracle")), checked)
            assertEquals(ModelRef("other", "oracle"), rt.state.value.advisor.model)
            val saved = Json.parseToJsonElement(Files.readString(file)).jsonObject
            assertEquals("other/oracle", saved.getValue("advisorModel").jsonPrimitive.content)
            assertEquals("high", saved.getValue("effort").jsonPrimitive.content)
            assertTrue(bytes.toString().contains("different") || bytes.toString().contains("不同的供应商"))
            assertTrue(advisorCommand(rt, "", file, {}, out))
            assertTrue(bytes.toString().contains("other/oracle"))
            assertTrue(advisorCommand(rt, "off", file, { error("off must not resolve") }, out))
            assertNull(rt.state.value.advisor.model)
            assertEquals(JsonNull, Json.parseToJsonElement(Files.readString(file)).jsonObject["advisorModel"])
            assertTrue(model.requests.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun `invalid advisor leaves settings and active selection unchanged and cancellation escapes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rt = SessionHub(FileSessionStore(tmp.root.toPath().resolve("sessions"), { 0L }), testDeps(ScriptedModel(), emptyList()), scope).create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
            val file = tmp.root.toPath().resolve("settings.json")
            Files.writeString(file, "{}")
            val out = PrintStream(ByteArrayOutputStream())
            assertFalse(advisorCommand(rt, "other/missing", file, { error("unknown model") }, out))
            assertEquals("{}", Files.readString(file)); assertNull(rt.state.value.advisor.model)
            try {
                advisorCommand(rt, "other/oracle", file, { throw CancellationException("cancelled") }, out)
                fail("CancellationException must escape")
            } catch (_: CancellationException) { }
            assertEquals("{}", Files.readString(file))
        } finally { scope.cancel() }
    }
}
