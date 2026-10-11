package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.*
import dev.min.agent.testkit.*
import dev.min.agent.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApplyPatchSessionTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun patch(body: String) = args("input" to "*** Begin Patch\n$body\n*** End Patch")

    @Test fun `GPT selects patch other profiles keep string edits`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for (style in EditStyle.entries) {
                val model = ScriptedModel(reply(TextPart("done")))
                val base = testDeps(model, listOf(ReadTool, WriteTool, EditTool))
                val deps = KernelDeps(base.clock, base.ids, { ResolvedModel(TEST_PROFILE.copy(editStyle = style), model) }, base.tools, base.host, base.platform, base.zone)
                val rt = SessionHub(FileSessionStore(tmp.newFolder().toPath(), { 0L }), deps, scope).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
                rt.send(ClientMsgId("u"), listOf(TextPart("test")))
                withTimeout(20_000) { rt.state.first { it.lastTurn != null && it.turn == null } }
                val names = model.requests.single().tools.map { it.name }
                assertEquals(style == EditStyle.ApplyPatch, "apply_patch" in names)
                assertEquals(style == EditStyle.StrReplace, "Edit" in names)
                assertEquals(style == EditStyle.StrReplace, "Write" in names)
            }
        } finally { scope.cancel() }
    }

    @Test fun `Edit deny blocks patch source and move destination before writing`() = runBlocking {
        for (blocked in listOf("a", "secret/b")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val fs = FakeFs(); fs.mkdir("/work"); fs.mkdir("/root"); fs.put("/work/a", "old\n")
                fs.put("/root/.claude/settings.json", """{"permissions":{"deny":["Edit(//$blocked)","Edit(//work/$blocked)"]}}""")
                val model = ScriptedModel(reply(ToolCallPart(CallId("r"), "Read", args("file_path" to "/work/a")), stop = StopReason.ToolUse), reply(ToolCallPart(CallId("p"), "apply_patch", patch("*** Update File: a\n*** Move to: secret/b\n@@\n-old\n+new")), stop = StopReason.ToolUse), reply(TextPart("done")))
                val base = testDeps(model, listOf(ReadTool, ApplyPatchTool), fs = fs)
                val deps = KernelDeps(base.clock, base.ids, { ResolvedModel(TEST_PROFILE.copy(editStyle = EditStyle.ApplyPatch), model) }, base.tools, base.host, base.platform, base.zone, memoryHome = GuestPath("/root"))
                val rt = SessionHub(FileSessionStore(tmp.newFolder().toPath(), { 0L }), deps, scope).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
                rt.send(ClientMsgId("u"), listOf(TextPart("test")))
                withTimeout(20_000) { rt.state.first { it.lastTurn != null && it.turn == null } }
                assertTrue(rt.state.value.toolResults.getValue(CallId("p")).isError)
                assertEquals("old\n", fs.text("/work/a"))
                assertNull(fs.text("/work/secret/b"))
                assertTrue(fs.writes.isEmpty())
            } finally { scope.cancel() }
        }
    }

    @Test fun `checkpoint captures patch and rewind restores move and deleted files`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fs = FakeFs(); fs.mkdir("/work"); fs.put("/work/a", "old\n"); fs.put("/work/d", "delete\n")
            val snapshots = FakeSnapshots(fs)
            val model = ScriptedModel(
                reply(ToolCallPart(CallId("r1"), "Read", args("file_path" to "/work/a")), ToolCallPart(CallId("r2"), "Read", args("file_path" to "/work/d")), stop = StopReason.ToolUse),
                reply(ToolCallPart(CallId("p"), "apply_patch", patch("*** Update File: a\n*** Move to: b\n@@\n-old\n+new\n*** Delete File: d")), stop = StopReason.ToolUse), reply(TextPart("done")),
            )
            val base = testDeps(model, listOf(ReadTool, ApplyPatchTool), fs = fs)
            val deps = KernelDeps(base.clock, base.ids, { ResolvedModel(TEST_PROFILE.copy(editStyle = EditStyle.ApplyPatch), model) }, base.tools, HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts, snapshots = snapshots), base.platform, base.zone)
            val store = FileSessionStore(tmp.newFolder().toPath(), { 0L })
            val rt = SessionHub(store, deps, scope).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
            rt.send(ClientMsgId("u"), listOf(TextPart("test")))
            withTimeout(20_000) { rt.state.first { it.lastTurn != null && it.turn == null } }
            val result = rt.state.value.toolResults.getValue(CallId("p"))
            assertFalse(result.text, result.isError)
            assertEquals(3, result.fileEdits.size)
            assertTrue(result.fileEdits.all { if (it.created) it.after != null else it.before != null })
            assertEquals(3, rt.state.value.editLog.size)
            val records = store.peek(rt.sessionId)!!.read().toList()
            val checkpoint = records.map { it.event }.filterIsInstance<CheckpointTaken>().first()
            assertTrue(rt.rewind(checkpoint.messageId, RewindScope.Code) is RewindOutcome.Done)
            assertEquals("old\n", fs.text("/work/a")); assertEquals("delete\n", fs.text("/work/d")); assertNull(fs.text("/work/b"))
            val log = store.peek(rt.sessionId)!!
            assertEquals(fold(log.header, log.read().toList()), rt.state.value)
        } finally { scope.cancel() }
    }
}
