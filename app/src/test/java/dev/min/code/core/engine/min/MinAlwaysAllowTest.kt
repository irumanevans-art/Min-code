package dev.min.code.core.engine.min

import dev.min.agent.kernel.HubPending
import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.QuestionId
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.QuestionAnswer
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.spi.WorkspaceFs
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.PendingInteraction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审批卡的「总是允许」：选项的有无、答复怎么路由与落盘、规则怎么写进 settings 文件。 */
class MinAlwaysAllowTest {
    private val text = FakeText()
    private val root = SessionId("s_root")
    private val tid = TurnId("t1")
    private val rid = RequestId("r1")

    private fun card(rule: String?): PendingInteraction.Approval {
        val ask = PermissionAsk("Bash", "run git status")
        val input = JsonObject(mapOf("command" to JsonPrimitive("git status")))
        val state = foldOf(
            TurnStarted(tid, TurnTrigger.User), ModelRequestStarted(tid, rid, 1, T_MODEL, "fp"),
            AssistantMessageCompleted(tid, rid, MessageId("a1"), listOf(ToolCallPart(CallId("c1"), "Bash", input)), Usage(), StopReason.ToolUse),
            ToolPlanned(tid, CallId("c1"), "Bash", input, ResumePolicy.Unknown),
            ApprovalRequested(tid, ApprovalId("ap1"), CallId("c1"), ask, rule, null),
        )
        val what = HubPending.What.Approval(ApprovalId("ap1"), CallId("c1"), "Bash", ask, rule, null)
        return minInteractions(listOf(HubPending(root, root, null, null, 5, what)), root, mapOf(root to state), emptyList(), text)
            .single() as PendingInteraction.Approval
    }

    // ---- 选项 ----

    @Test fun `a suggested rule adds three always options after the standard three, none of them for the notification`() {
        val c = card("Bash(git status)")
        assertEquals(listOf("allow", "deny", "deny-stop"), c.options.take(3).map { it.id })
        val always = c.options.drop(3)
        assertEquals(
            listOf(
                "always:session:Bash(git status)", "always:local:Bash(git status)", "always:user:Bash(git status)",
            ),
            always.map { it.id },
        )
        assertTrue(always.all { it.kind == ApprovalOption.Kind.AllowAlways && !it.notificationSafe && !it.acceptsNote })
        assertEquals(listOf("allow", "deny"), c.notificationOptions.map { it.id })
        assertEquals("always-local:Bash(git status)", always[1].label)
    }

    @Test fun `without a suggested rule there is no always option`() {
        assertEquals(listOf("allow", "deny", "deny-stop"), card(null).options.map { it.id })
        assertEquals(listOf("allow", "deny", "deny-stop"), card("  ").options.map { it.id })
    }

    @Test fun `option ids round trip even when the rule itself contains colons and spaces`() {
        val rule = "WebFetch(domain:example.com)"
        val parsed = AlwaysOptionIds.parse(AlwaysOptionIds.of(RuleLayer.User, rule))
        assertEquals(RuleLayer.User to rule, parsed)
        assertNull(AlwaysOptionIds.parse("allow"))
        assertNull(AlwaysOptionIds.parse("always:nowhere:Bash"))
        assertNull(AlwaysOptionIds.parse("always:local:"))
    }

    // ---- 路由 ----

    private class Target(var approveResult: Boolean = true) : MinAnswerTarget {
        val approvals = mutableListOf<ApprovalChoice>()
        override suspend fun approve(id: ApprovalId, choice: ApprovalChoice): Boolean { approvals += choice; return approveResult }
        override suspend fun answer(id: QuestionId, answers: List<QuestionAnswer>) = true
        override suspend fun decline(id: QuestionId) = true
    }

    private fun route(target: Target, optionId: String, persisted: MutableList<Pair<RuleLayer, String>>): CommandResult = runBlocking {
        MinInteractionRouter(
            session = { target }, interrupt = {},
            persistRule = { layer, rule -> persisted += layer to rule },
        ).answer("s_root/ap1", InteractionAnswer.Choose(optionId))
    }

    @Test fun `always local lets the kernel allow with a session rule and then persists it`() {
        val target = Target()
        val persisted = mutableListOf<Pair<RuleLayer, String>>()
        assertEquals(CommandResult.Accepted, route(target, "always:local:Bash(git status)", persisted))
        assertEquals(listOf<ApprovalChoice>(ApprovalChoice.Always("Bash(git status)")), target.approvals)
        assertEquals(listOf(RuleLayer.Local to "Bash(git status)"), persisted)
    }

    @Test fun `always for the session writes no file`() {
        val target = Target()
        val persisted = mutableListOf<Pair<RuleLayer, String>>()
        assertEquals(CommandResult.Accepted, route(target, "always:session:Bash(ls)", persisted))
        assertEquals(listOf<ApprovalChoice>(ApprovalChoice.Always("Bash(ls)")), target.approvals)
        assertTrue(persisted.isEmpty())
    }

    @Test fun `an approval another surface already answered is stale and touches no settings file`() {
        val target = Target(approveResult = false)
        val persisted = mutableListOf<Pair<RuleLayer, String>>()
        assertTrue(route(target, "always:user:Bash(ls)", persisted) is CommandResult.Stale)
        assertTrue("别处已经答过，不该再改用户的配置", persisted.isEmpty())
    }

    @Test fun `a malformed always option is rejected and nothing is approved`() {
        val target = Target()
        assertTrue(route(target, "always:bogus:x", mutableListOf()) is CommandResult.Rejected)
        assertTrue(target.approvals.isEmpty())
    }

    // ---- 规则怎么写进 settings 文本 ----

    private fun allowOf(text: String): List<String> =
        Json.parseToJsonElement(text).jsonObject["permissions"]!!.jsonObject["allow"]!!.jsonArray.map { (it as JsonPrimitive).content }

    @Test fun `a missing or blank file becomes a fresh settings object`() {
        for (existing in listOf(null, "", "  \n")) {
            val updated = addAllowRule(existing, "Bash(git status)") as AddRule.Updated
            assertEquals(listOf("Bash(git status)"), allowOf(updated.text))
            assertTrue(updated.text.endsWith("\n"))
        }
    }

    @Test fun `other keys and their order survive and the rule is appended once`() {
        val existing = """{"model":"x","permissions":{"deny":["Bash(rm *)"],"allow":["Read"]},"env":{"A":"1"}}"""
        val updated = addAllowRule(existing, "Bash(ls)") as AddRule.Updated
        val root = Json.parseToJsonElement(updated.text).jsonObject
        assertEquals(listOf("model", "permissions", "env"), root.keys.toList())
        assertEquals(listOf("Read", "Bash(ls)"), allowOf(updated.text))
        assertEquals("""["Bash(rm *)"]""", root["permissions"]!!.jsonObject["deny"]!!.jsonArray.toString())
        assertEquals(AddRule.AlreadyThere, addAllowRule(updated.text, "Bash(ls)"))
    }

    @Test fun `a file that is not safe to edit is refused and never overwritten`() {
        for (existing in listOf("{ not json", "[1,2]", """{"permissions":[]}""", """{"permissions":{"allow":"Read"}}""")) {
            assertTrue(existing, addAllowRule(existing, "Bash(ls)") is AddRule.Refused)
        }
    }

    // ---- 各层的文件路径与读写 ----

    @Test fun `each layer has its own settings file and the session layer has none`() {
        val cwd = GuestPath("/workspace/app")
        val home = GuestPath("/root")
        assertNull(settingsFileOf(RuleLayer.Session, cwd, home))
        assertEquals("/workspace/app/.claude/settings.local.json", settingsFileOf(RuleLayer.Local, cwd, home)?.value)
        assertEquals("/root/.claude/settings.json", settingsFileOf(RuleLayer.User, cwd, home)?.value)
        assertEquals("/workspace/.claude/settings.local.json", settingsFileOf(RuleLayer.Local, GuestPath("/workspace/"), home)?.value)
    }

    private class MemoryFs(val files: MutableMap<String, ByteArray> = mutableMapOf(), val failWrites: Boolean = false) : WorkspaceFs {
        override suspend fun stat(path: GuestPath): FileStat? = files[path.value]?.let { FileStat(dev.min.agent.spi.FileKind.File, it.size.toLong(), 0L) }
        override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray = files.getValue(path.value)
        override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) {
            if (failWrites) error("disk full")
            files[path.value] = bytes
        }
        override suspend fun list(dir: GuestPath): List<DirEntry> = emptyList()
        override fun scopeOf(path: GuestPath) = PathScope.Workspace
    }

    @Test fun `writing creates the file, appends to an existing one and reports an unchanged rule`() = runBlocking {
        val fs = MemoryFs()
        val cwd = GuestPath("/workspace")
        val home = GuestPath("/root")
        assertEquals(PersistRule.Written, writeAllowRule(fs, RuleLayer.Local, "Bash(ls)", cwd, home))
        assertEquals(listOf("Bash(ls)"), allowOf(fs.files.getValue("/workspace/.claude/settings.local.json").decodeToString()))
        assertEquals(PersistRule.Written, writeAllowRule(fs, RuleLayer.Local, "Bash(pwd)", cwd, home))
        assertEquals(listOf("Bash(ls)", "Bash(pwd)"), allowOf(fs.files.getValue("/workspace/.claude/settings.local.json").decodeToString()))
        assertEquals(PersistRule.AlreadyThere, writeAllowRule(fs, RuleLayer.Local, "Bash(ls)", cwd, home))
        // 用户层是另一个文件
        assertEquals(PersistRule.Written, writeAllowRule(fs, RuleLayer.User, "Bash(ls)", cwd, home))
        assertTrue(fs.files.containsKey("/root/.claude/settings.json"))
    }

    @Test fun `a broken existing file is left exactly as it was`() = runBlocking {
        val broken = "{ \"permissions\": ".encodeToByteArray()
        val fs = MemoryFs(mutableMapOf("/workspace/.claude/settings.local.json" to broken))
        val result = writeAllowRule(fs, RuleLayer.Local, "Bash(ls)", GuestPath("/workspace"), GuestPath("/root"))
        assertTrue(result.toString(), result is PersistRule.Refused)
        assertTrue(fs.files.getValue("/workspace/.claude/settings.local.json").contentEquals(broken))
    }

    @Test fun `a write that fails is reported not thrown`() = runBlocking {
        val result = writeAllowRule(MemoryFs(failWrites = true), RuleLayer.User, "Bash(ls)", GuestPath("/workspace"), GuestPath("/root"))
        assertTrue(result is PersistRule.Failed)
        assertFalse((result as PersistRule.Failed).reason.isBlank())
    }
}
