package dev.min.agent.ext.security

import dev.min.agent.ext.config.InMemoryFs
import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** security-guidance 的纯逻辑：开关、内置与自定义模式、指引文件、提示词。对照 CC 文档 security-guidance.md。 */
class SecurityGuidanceTest {
    private val enabled = mapOf("security-guidance@claude-plugins-official" to true)
    private val noEnv: (String) -> String? = { null }

    // ---- 开关 ----

    @Test
    fun `it is on only when the plugin is enabled and every layer follows its own switch`() {
        assertNull(SecurityGuidance.layers(emptyMap(), noEnv))
        assertNull(SecurityGuidance.layers(mapOf("security-guidance@claude-plugins-official" to false), noEnv))
        assertNull(SecurityGuidance.layers(mapOf("other@x" to true), noEnv))
        assertEquals(SecurityGuidance.Layers(perEdit = true, stopReview = true, commitReview = true), SecurityGuidance.layers(enabled, noEnv))
        // 市场名不限：键是 security-guidance@<任何市场>
        assertNotNull(SecurityGuidance.layers(mapOf("security-guidance@my-market" to true), noEnv))

        fun env(vararg kv: Pair<String, String>): (String) -> String? = { kv.toMap()[it] }
        assertNull(SecurityGuidance.layers(enabled, env("SECURITY_GUIDANCE_DISABLE" to "1")))
        assertEquals(false, SecurityGuidance.layers(enabled, env("ENABLE_PATTERN_RULES" to "0"))!!.perEdit)
        assertEquals(false, SecurityGuidance.layers(enabled, env("ENABLE_STOP_REVIEW" to "0"))!!.stopReview)
        assertEquals(false, SecurityGuidance.layers(enabled, env("ENABLE_COMMIT_REVIEW" to "0"))!!.commitReview)
        val noModels = SecurityGuidance.layers(enabled, env("ENABLE_CODE_SECURITY_REVIEW" to "0"))!!
        assertEquals(SecurityGuidance.Layers(perEdit = true, stopReview = false, commitReview = false), noModels)
        // 全关 = 没启用
        assertNull(SecurityGuidance.layers(enabled, env("ENABLE_PATTERN_RULES" to "0", "ENABLE_CODE_SECURITY_REVIEW" to "0")))
    }

    // ---- 内置模式 ----

    private fun hits(path: String, content: String) = SecurityGuidance.scan(SecurityGuidance.BUILTIN, path, content).map { it.rule }

    @Test
    fun `the built-in patterns cover the documented categories`() {
        assertEquals(listOf("dynamic_code_execution"), hits("/w/a.py", "x = eval(user)"))
        assertEquals(listOf("dynamic_code_execution"), hits("/w/a.js", "const f = new Function('a', body)"))
        assertEquals(listOf("dynamic_code_execution"), hits("/w/a.py", "os.system(cmd)"))
        assertEquals(listOf("dynamic_code_execution"), hits("/w/a.js", "child_process.exec(cmd)"))
        assertEquals(listOf("unsafe_deserialization"), hits("/w/a.py", "obj = pickle.loads(blob)"))
        assertEquals(listOf("dom_injection"), hits("/w/a.jsx", "<div dangerouslySetInnerHTML={{__html: s}} />"))
        assertEquals(listOf("dom_injection"), hits("/w/a.js", "el.innerHTML = s"))
        assertEquals(listOf("dom_injection"), hits("/w/a.js", "document.write(s)"))
        assertEquals(listOf("workflow_file"), hits("/repo/.github/workflows/ci.yml", "on: push"))
        assertTrue(hits("/w/a.kt", "val x = 1").isEmpty())
        assertTrue(hits("/repo/docs/workflows/ci.yml", "on: push").isEmpty())
    }

    @Test
    fun `only what the edit writes is scanned, not the old text or the path`() {
        val input = Json.parseToJsonElement(
            """{"file_path":"/w/eval(.py","old_string":"eval(old)","new_string":"safe()"}""",
        ).jsonObject
        assertEquals("safe()\n", SecurityGuidance.writtenText(input))
        val write = Json.parseToJsonElement("""{"file_path":"/w/a.py","content":"pickle.load(f)"}""").jsonObject
        assertEquals(listOf("unsafe_deserialization"), hits("/w/a.py", SecurityGuidance.writtenText(write)))
        val multi = Json.parseToJsonElement("""{"file_path":"/w/a.js","edits":[{"old_string":"a","new_string":"el.innerHTML = s"}]}""").jsonObject
        assertEquals(listOf("dom_injection"), hits("/w/a.js", SecurityGuidance.writtenText(multi)))
    }

    @Test
    fun `a hit has a stable per rule and file key`() {
        val a = SecurityGuidance.scan(SecurityGuidance.BUILTIN, "/w/a.py", "eval(x); os.system(y)").single()
        val b = SecurityGuidance.scan(SecurityGuidance.BUILTIN, "/w/a.py", "eval(z)").single()
        val c = SecurityGuidance.scan(SecurityGuidance.BUILTIN, "/w/b.py", "eval(z)").single()
        assertEquals(a.key, b.key)
        assertFalse(a.key == c.key)
    }

    // ---- 自定义模式 ----

    private fun custom(vararg files: Pair<String, String>): SecurityGuidance.Custom = runBlocking {
        val fs = InMemoryFs()
        files.forEach { (p, t) -> fs.file(p, t) }
        SecurityGuidance.loadCustom(fs, GuestPath("/workspace"), GuestPath("/root"))
    }

    @Test
    fun `custom substring and regex rules with path globs and excludes`() {
        val c = custom(
            "/workspace/.claude/security-patterns.json" to """{"patterns":[
              {"rule_name":"internal_api_key","substrings":["sk_live_","AKIA"],"reminder":"Hardcoded key."},
              {"rule_name":"tenant_unfiltered","regex":"\\.objects\\.all\\(\\)","paths":["**/src/tenants/**"],"exclude_paths":["**/tests/**"],"reminder":"Filter by org_id."}
            ]}""",
        )
        assertEquals(listOf("internal_api_key", "tenant_unfiltered"), c.patterns.map { it.rule })
        val all = SecurityGuidance.BUILTIN + c.patterns
        assertEquals(listOf("internal_api_key"), SecurityGuidance.scan(all, "/workspace/a.py", "k = 'AKIA123'").map { it.rule })
        assertEquals(listOf("tenant_unfiltered"), SecurityGuidance.scan(all, "/workspace/src/tenants/x.py", "Model.objects.all()").map { it.rule })
        // 路径不在 paths 里、或在 exclude_paths 里：不命中
        assertTrue(SecurityGuidance.scan(all, "/workspace/src/other/x.py", "Model.objects.all()").isEmpty())
        assertTrue(SecurityGuidance.scan(all, "/workspace/src/tenants/tests/x.py", "Model.objects.all()").isEmpty())
    }

    @Test
    fun `custom rules are capped at fifty, reminders at 1 KB, and bad ones are skipped with a note`() {
        val many = (1..60).joinToString(",") { """{"rule_name":"r$it","substrings":["x$it"],"reminder":"m"}""" }
        val capped = custom("/workspace/.claude/security-patterns.json" to """{"patterns":[$many]}""")
        assertEquals(SecurityGuidance.MAX_CUSTOM_RULES, capped.patterns.size)
        assertTrue(capped.notes.any { "50" in it })

        val long = "é".repeat(2000)
        val c = custom(
            "/workspace/.claude/security-patterns.json" to """{"patterns":[
              {"rule_name":"long","substrings":["a"],"reminder":"$long"},
              {"rule_name":"bomb","regex":"(a+)+$","reminder":"m"},
              {"rule_name":"broken","regex":"(","reminder":"m"},
              {"rule_name":"noreminder","substrings":["a"]},
              {"rule_name":"nomatcher","reminder":"m"}
            ]}""",
        )
        assertEquals(listOf("long"), c.patterns.map { it.rule })
        assertTrue(c.patterns.single().reminder.toByteArray().size <= SecurityGuidance.MAX_REMINDER_BYTES)
        assertEquals(4, c.notes.size)
        assertTrue(SecurityGuidance.backtrackProne("(a+)+") && SecurityGuidance.backtrackProne("(.*)*x") && !SecurityGuidance.backtrackProne("a+b*"))
    }

    @Test
    fun `user and project files merge`() {
        val c = custom(
            "/root/.claude/security-patterns.json" to """{"patterns":[{"rule_name":"u","substrings":["u1"],"reminder":"m"}]}""",
            "/workspace/.claude/security-patterns.json" to """{"patterns":[{"rule_name":"p","substrings":["p1"],"reminder":"m"}]}""",
        )
        assertEquals(listOf("u", "p"), c.patterns.map { it.rule })
        assertTrue(custom("/workspace/.claude/security-patterns.json" to "{not json").notes.any { "JSON" in it })
    }

    // ---- YAML 形式（文档：yaml / yml / json 同一个 schema） ----

    private val docYaml = """
        patterns:
          - rule_name: internal_api_key
            substrings: ["sk_live_", "AKIA"]
            reminder: "Hardcoded API key prefix. Load credentials from the secret manager."
          - rule_name: tenant_unfiltered_query
            regex: "\\.objects\\.all\\(\\)"
            paths: ["**/src/tenants/**"]
            exclude_paths:
              - "**/tests/**"
            reminder: "Multi-tenant code must filter by org_id."
    """.trimIndent()

    @Test
    fun `the yaml form from the documentation is read like the json one`() {
        for (name in listOf("security-patterns.yaml", "security-patterns.yml")) {
            val c = custom("/workspace/.claude/$name" to docYaml)
            assertEquals(name, listOf("internal_api_key", "tenant_unfiltered_query"), c.patterns.map { it.rule })
            assertTrue(c.notes.toString(), c.notes.isEmpty())
            val all = SecurityGuidance.BUILTIN + c.patterns
            assertEquals(listOf("internal_api_key"), SecurityGuidance.scan(all, "/workspace/a.py", "k = 'AKIA123'").map { it.rule })
            assertEquals(listOf("tenant_unfiltered_query"), SecurityGuidance.scan(all, "/workspace/src/tenants/x.py", "Model.objects.all()").map { it.rule })
            assertTrue(SecurityGuidance.scan(all, "/workspace/src/tenants/tests/x.py", "Model.objects.all()").isEmpty())
        }
    }

    @Test
    fun `yaml json and the two layers merge in order and share the fifty rule cap`() {
        val c = custom(
            "/root/.claude/security-patterns.yaml" to "patterns:\n  - {rule_name: u, substrings: [u1], reminder: m}\n",
            "/workspace/.claude/security-patterns.yml" to "---\npatterns:\n  - rule_name: y\n    substrings: [y1]\n    reminder: m\n",
            "/workspace/.claude/security-patterns.json" to """{"patterns":[{"rule_name":"j","substrings":["j1"],"reminder":"m"}]}""",
        )
        assertEquals(listOf("u", "y", "j"), c.patterns.map { it.rule })
        val many = (1..60).joinToString("\n") { "  - rule_name: r$it\n    substrings: [x$it]\n    reminder: m" }
        val capped = custom("/workspace/.claude/security-patterns.yaml" to "patterns:\n$many\n")
        assertEquals(SecurityGuidance.MAX_CUSTOM_RULES, capped.patterns.size)
    }

    @Test
    fun `an invalid yaml file is skipped with a note and the same safety filters apply`() {
        val bad = custom("/workspace/.claude/security-patterns.yaml" to "patterns:\n  - rule_name: a\n   substrings: [x]\n")
        assertTrue(bad.patterns.isEmpty())
        assertTrue(bad.notes.toString(), bad.notes.any { "YAML" in it })
        val bomb = custom(
            "/workspace/.claude/security-patterns.yaml" to
                "patterns:\n  - rule_name: bomb\n    regex: \"(a+)+$\"\n    reminder: m\n  - rule_name: noreminder\n    substrings: [a]\n",
        )
        assertTrue(bomb.patterns.isEmpty())
        assertEquals(2, bomb.notes.size)
    }

    // ---- 指引文件 ----

    @Test
    fun `guidance files concatenate user, project, local with a combined 8 KB cap`() = runBlocking {
        val fs = InMemoryFs()
        fs.file("/root/.claude/claude-security-guidance.md", "USER RULES")
        fs.file("/workspace/.claude/claude-security-guidance.md", "PROJECT RULES")
        fs.file("/workspace/.claude/claude-security-guidance.local.md", "LOCAL RULES")
        val text = SecurityGuidance.loadGuidance(fs, GuestPath("/workspace"), GuestPath("/root"))
        assertEquals("USER RULES\n\nPROJECT RULES\n\nLOCAL RULES", text)
        assertEquals("", SecurityGuidance.loadGuidance(InMemoryFs(), GuestPath("/workspace"), null))

        val big = InMemoryFs()
        big.file("/workspace/.claude/claude-security-guidance.md", "x".repeat(20_000))
        assertEquals(SecurityGuidance.MAX_GUIDANCE_BYTES, SecurityGuidance.loadGuidance(big, GuestPath("/workspace"), null).toByteArray().size)
    }

    // ---- 提示词 ----

    @Test
    fun `the review prompts carry the files, the additive guidance and the verdict contract`() {
        val files = (1..40).map { SecurityGuidance.Changed("/w/f$it.py", "body $it") }
        val p = SecurityGuidance.turnReviewPrompt(files, "Never log customer_id.")
        assertTrue(p.contains("/w/f1.py") && p.contains("/w/f30.py") && !p.contains("/w/f31.py"))
        assertTrue(p.contains("Never log customer_id.") && p.contains("never removes"))
        assertTrue(p.contains("\"ok\""))
        assertFalse(SecurityGuidance.turnReviewPrompt(files.take(1), "").contains("project adds"))
        val c = SecurityGuidance.commitReviewPrompt("git commit -m x", "diff --git a b", "")
        assertTrue(c.contains("git commit -m x") && c.contains("diff --git a b") && c.contains("\"ok\""))
    }
}
