package dev.min.agent.ext.mcp

import dev.min.agent.ext.config.InMemoryFs
import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [McpConfigLoader] 的规则测试，八条规则每条至少一处：来源与优先级、managed 独占与失败即关、allow / deny 管控、
 * 条目解析、环境变量展开（凭证置空）、闸门与最严者胜、凭证不进 toString / note。
 * 事实出处：cc-mcp.md 与 cc-managed-mcp.md（行号见 research/inventory/mcp-client.md §1.1–1.5）。
 */
class McpConfigLoaderTest {
    @Test fun `oauth client secret expands host environment and missing value stays out of notes`() {
        val json="""{"mcpServers":{"db":{"type":"http","url":"https://db.example/mcp","oauth":{"clientId":"client","clientSecret":"${'$'}{MCP_SECRET}"}}}}"""
        val snap=load("$project/.mcp.json" to json,env=mapOf("MCP_SECRET" to "FAKE_SECRET"))
        assertTrue((snap.entry("db")!!.spec as McpServerSpec.Remote).oauth!!.clientSecret=="FAKE_SECRET")
        assertTrue(!snap.notesJoined().contains("FAKE_SECRET"))
        val missing=load("$project/.mcp.json" to json)
        assertTrue((missing.entry("db")!!.spec as McpServerSpec.Remote).oauth!!.clientSecret=="${'$'}{MCP_SECRET}")
        assertEquals(listOf("MCP_SECRET"),missing.entry("db")!!.unresolvedVars)
        assertTrue(!missing.notesJoined().contains("${'$'}{MCP_SECRET}"))
    }
    private val home = "/home/user"
    private val project = "/workspace"

    private fun load(
        vararg files: Pair<String, String>,
        trustProject: Boolean = true,
        env: Map<String, String> = emptyMap(),
    ): McpConfigSnapshot {
        val fs = InMemoryFs()
        files.forEach { (path, text) -> fs.file(path, text) }
        return runBlocking {
            val settings = SettingsLoader.load(fs, GuestPath(project), GuestPath(home)).merged
            McpConfigLoader.load(fs, GuestPath(home), GuestPath(project), settings, trustProject, env::get)
        }
    }

    private fun McpConfigSnapshot.entry(name: String): McpServerEntry? = servers.firstOrNull { it.spec.name == name }
    private fun McpConfigSnapshot.notesJoined(): String = notes.joinToString("\n")

    // ---------- 规则 1：来源与优先级 ----------

    @Test
    fun `local beats project and user with the whole entry, shadowed and endpoint warning recorded`() {
        val snap = load(
            "$home/.claude.json" to """
                {"mcpServers":{"shared":{"type":"http","url":"https://user.example/mcp"}},
                 "projects":{"$project":{"mcpServers":{"shared":{"type":"http","url":"https://local.example/mcp"}}}}}
            """.trimIndent(),
            "$project/.mcp.json" to """{"mcpServers":{"shared":{"type":"http","url":"https://project.example/mcp"}}}""",
        )
        val shared = snap.entry("shared")!!
        assertEquals(McpScope.Local, shared.spec.scope)
        assertEquals("https://local.example/mcp", (shared.spec as McpServerSpec.Remote).url)
        assertEquals(listOf(McpScope.Project, McpScope.User), shared.shadowed)
        assertEquals(1, snap.servers.size)
        assertTrue("同名端点不同的警告", "端点不同" in snap.notesJoined())
    }

    @Test
    fun `claude json project keys compare normalized, other projects do not leak`() {
        val snap = load(
            "$home/.claude.json" to """
                {"projects":{"/workspace/":{"mcpServers":{"trailing":{"command":"srv-trailing"}}},
                              "/other/project":{"mcpServers":{"other":{"command":"srv-other"}}}}}
            """.trimIndent(),
        )
        assertEquals(listOf("trailing"), snap.servers.map { it.spec.name })
        assertEquals(McpScope.Local, snap.entry("trailing")!!.spec.scope)
    }

    @Test
    fun `user scope servers load without approval`() {
        val snap = load("$home/.claude.json" to """{"mcpServers":{"hub":{"type":"http","url":"https://hub.example/mcp"}}}""")
        val hub = snap.entry("hub")!!
        assertEquals(McpScope.User, hub.spec.scope)
        assertEquals(McpGate.Enabled, hub.gate)
    }

    // ---------- 规则 2：managed 独占与 managedMcpServers ----------

    @Test
    fun `managed-mcp json is exclusive, other sources are not read at all`() {
        val snap = load(
            "/etc/claude-code/managed-mcp.json" to """{"mcpServers":{"corp":{"type":"http","url":"https://corp.example/mcp"}}}""",
            "/etc/claude-code/managed-settings.json" to """{"managedMcpServers":{"wiki":{"type":"http","url":"https://wiki.example/mcp"}}}""",
            "$home/.claude.json" to """{"mcpServers":{"personal":{"command":"srv"}},"projects":{"$project":{"mcpServers":{"mine":{"command":"srv"}}}}}""",
            "$project/.mcp.json" to """{"mcpServers":{"team":{"command":"srv"}}}""",
        )
        assertTrue(snap.managedExclusive)
        assertEquals(setOf("corp", "wiki"), snap.servers.map { it.spec.name }.toSet())
        assertEquals(McpScope.Managed, snap.entry("corp")!!.spec.scope)
    }

    @Test
    fun `an empty managed-mcp json turns mcp off`() {
        val snap = load(
            "/etc/claude-code/managed-mcp.json" to """{"mcpServers":{}}""",
            "$home/.claude.json" to """{"mcpServers":{"personal":{"command":"srv"}}}""",
        )
        assertTrue(snap.managedExclusive)
        assertTrue(snap.servers.isEmpty())
    }

    @Test
    fun `a broken managed-mcp json fails closed, not even managedMcpServers load`() {
        val snap = load(
            "/etc/claude-code/managed-mcp.json" to """{"mcpServers": {"corp" """,
            "/etc/claude-code/managed-settings.json" to """{"managedMcpServers":{"wiki":{"type":"http","url":"https://wiki.example/mcp"}}}""",
            "$home/.claude.json" to """{"mcpServers":{"personal":{"command":"srv"}}}""",
        )
        assertTrue(snap.managedExclusive)
        assertTrue(snap.servers.isEmpty())
        assertTrue("失败即关要说明", "一台服务器都不加载" in snap.notesJoined())
    }

    @Test
    fun `managedMcpServers load alongside user servers and outrank local by name`() {
        val snap = load(
            "/etc/claude-code/managed-settings.json" to """{"managedMcpServers":{"shared":{"type":"http","url":"https://provided.example/mcp"}}}""",
            "$home/.claude.json" to """
                {"projects":{"$project":{"mcpServers":{"shared":{"type":"http","url":"https://local.example/mcp"},
                                          "mine":{"command":"srv"}}}}}
            """.trimIndent(),
        )
        assertTrue(!snap.managedExclusive)
        assertEquals("https://provided.example/mcp", (snap.entry("shared")!!.spec as McpServerSpec.Remote).url)
        assertEquals(listOf(McpScope.Local), snap.entry("shared")!!.shadowed)
        assertEquals(McpGate.Enabled, snap.entry("mine")!!.gate)
    }

    @Test
    fun `managedMcpServers entries violating the remote https no-vars shape are skipped with notes`() {
        val snap = load(
            "/etc/claude-code/managed-settings.json" to """
                {"managedMcpServers":{
                  "stdio-one":{"command":"srv"},
                  "plain-http":{"type":"http","url":"http://insecure.example/mcp"},
                  "localhost-url":{"type":"http","url":"http://localhost:8787/mcp"},
                  "with-env":{"type":"http","url":"https://a.example/mcp","env":{"X":"1"}},
                  "with-var":{"type":"http","url":"https://b.example/${'$'}{TOKEN}"},
                  "bad name":{"type":"http","url":"https://c.example/mcp"},
                  "fine":{"type":"streamable-http","url":"https://ok.example/mcp"}}}
            """.trimIndent(),
        )
        assertEquals(listOf("fine"), snap.servers.map { it.spec.name })
        val notes = snap.notesJoined()
        assertTrue("type 必须是 http 或 sse" in notes)
        assertTrue("https:// 开头" in notes)
        assertTrue("不允许 command / args / env / headersHelper" in notes)
        assertTrue("不允许 \${VAR} 引用" in notes)
        assertTrue("名字只许字母数字连字符下划线" in notes)
    }

    @Test
    fun `managedMcpServers in a non-managed layer is dropped with a warning`() {
        val snap = load(
            "$home/.claude/settings.json" to """{"managedMcpServers":{"x":{"type":"http","url":"https://x.example/mcp"}}}""",
        )
        assertTrue(snap.servers.isEmpty())
        assertTrue("只在 managed 设置层生效" in snap.notesJoined())
    }

    // ---------- 规则 3：allow / deny 管控 ----------

    @Test
    fun `deny always wins over allow`() {
        val snap = load(
            "$home/.claude/settings.json" to """
                {"allowedMcpServers":[{"serverName":"github"}],"deniedMcpServers":[{"serverName":"github"}]}
            """.trimIndent(),
            "$home/.claude.json" to """{"mcpServers":{"github":{"command":"srv"}}}""",
        )
        assertEquals(McpGate.Denied, snap.entry("github")!!.gate)
        assertTrue(snap.entry("github")!!.reason!!.contains("deniedMcpServers"))
    }

    @Test
    fun `deny by url uses wildcards and ignores host case and trailing dot`() {
        val snap = load(
            "$home/.claude/settings.json" to """{"deniedMcpServers":[{"serverUrl":"https://Mcp.Example.com./*"}]}""".trim(),
            "$home/.claude.json" to """
                {"mcpServers":{"a":{"type":"http","url":"https://mcp.example.com/api/x"},
                               "b":{"type":"http","url":"https://mcp.example.com/other"},
                               "c":{"type":"http","url":"https://other.example.com/api"}}}
            """.trimIndent(),
        )
        assertEquals(McpGate.Denied, snap.entry("a")!!.gate)
        assertEquals(McpGate.Denied, snap.entry("b")!!.gate)
        assertEquals(McpGate.Enabled, snap.entry("c")!!.gate)
    }

    @Test
    fun `deny by command matches expanded argv exactly`() {
        val snap = load(
            "$home/.claude/settings.json" to """{"deniedMcpServers":[{"serverCommand":["${'$'}{HOME}/bin/server","--flag"]}]}""".trim(),
            "$home/.claude.json" to """
                {"mcpServers":{"same":{"command":"/home/user/bin/server","args":["--flag"]},
                               "extra":{"command":"/home/user/bin/server","args":["--flag","--more"]}}}
            """.trimIndent(),
            env = mapOf("HOME" to "/home/user"),
        )
        assertEquals(McpGate.Denied, snap.entry("same")!!.gate)
        assertEquals(McpGate.Enabled, snap.entry("extra")!!.gate)
    }

    @Test
    fun `an empty allowed list blocks everything, an unset one allows everything`() {
        val blocked = load(
            "$home/.claude/settings.json" to """{"allowedMcpServers":[]}""",
            "$home/.claude.json" to """{"mcpServers":{"a":{"command":"srv"}}}""",
        )
        assertEquals(McpGate.Denied, blocked.entry("a")!!.gate)
        val open = load("$home/.claude.json" to """{"mcpServers":{"a":{"command":"srv"}}}""")
        assertEquals(McpGate.Enabled, open.entry("a")!!.gate)
    }

    @Test
    fun `once the allowlist carries serverUrl entries, remote servers must match one`() {
        val snap = load(
            "$home/.claude/settings.json" to """
                {"allowedMcpServers":[{"serverName":"named"},{"serverUrl":"https://ok.example.com/*"}]}
            """.trimIndent(),
            "$home/.claude.json" to """
                {"mcpServers":{"match":{"type":"http","url":"https://ok.example.com/api"},
                               "elsewhere":{"type":"http","url":"https://elsewhere.example/mcp"},
                               "named":{"command":"srv"}}}
            """.trimIndent(),
        )
        assertEquals(McpGate.Enabled, snap.entry("match")!!.gate)
        // 有 serverUrl 项之后 serverName 不再放行远程；stdio 没有任何 serverCommand 项可匹配时名字仍算数
        assertEquals(McpGate.Denied, snap.entry("elsewhere")!!.gate)
        assertEquals(McpGate.Enabled, snap.entry("named")!!.gate)
    }

    @Test
    fun `once the allowlist carries serverCommand entries, stdio servers must match argv exactly`() {
        val snap = load(
            "$home/.claude/settings.json" to """
                {"allowedMcpServers":[{"serverName":"x"},{"serverCommand":["npx","-y","approved"]}]}
            """.trimIndent(),
            "$home/.claude.json" to """
                {"mcpServers":{"match":{"command":"npx","args":["-y","approved"]},
                               "named":{"command":"node","args":["server.js"]}}}
            """.trimIndent(),
        )
        assertEquals(McpGate.Enabled, snap.entry("match")!!.gate)
        assertEquals(McpGate.Denied, snap.entry("named")!!.gate)
    }

    @Test
    fun `allowManagedMcpServersOnly locks the allowlist to managed layers`() {
        val snap = load(
            "/etc/claude-code/managed-settings.json" to """
                {"allowManagedMcpServersOnly":true,"allowedMcpServers":[{"serverName":"a"}]}
            """.trimIndent(),
            "$home/.claude/settings.json" to """{"allowedMcpServers":[{"serverName":"b"}]}""",
            "$home/.claude.json" to """{"mcpServers":{"a":{"command":"srv-a"},"b":{"command":"srv-b"}}}""",
        )
        assertEquals(McpGate.Enabled, snap.entry("a")!!.gate)
        assertEquals(McpGate.Denied, snap.entry("b")!!.gate)
        assertTrue("allowManagedMcpServersOnly 生效" in snap.notesJoined())
    }

    @Test
    fun `managed file entries skip the allowlist unless they use var expansion`() {
        val snap = load(
            "/etc/claude-code/managed-mcp.json" to """
                {"mcpServers":{"literal":{"type":"http","url":"https://literal.example/mcp"},
                               "varied":{"type":"http","url":"https://var.example/${'$'}{TOKEN}/mcp"}}}
            """.trimIndent(),
            "$home/.claude/settings.json" to """{"allowedMcpServers":[]}""",
        )
        assertEquals(McpGate.Enabled, snap.entry("literal")!!.gate)
        assertEquals(McpGate.Denied, snap.entry("varied")!!.gate)
    }

    @Test
    fun `managedMcpServers entries never need an allowlist entry`() {
        val snap = load(
            "/etc/claude-code/managed-settings.json" to """{"managedMcpServers":{"wiki":{"type":"http","url":"https://wiki.example/mcp"}}}""",
            "$home/.claude/settings.json" to """{"allowedMcpServers":[]}""",
        )
        assertEquals(McpGate.Enabled, snap.entry("wiki")!!.gate)
    }

    // ---------- 规则 4：条目解析 ----------

    @Test
    fun `type defaults to stdio and streamable-http is an alias for http`() {
        val snap = load(
            "$home/.claude.json" to """
                {"mcpServers":{"plain":{"command":"srv","args":["--x"]},
                               "aliased":{"type":"streamable-http","url":"https://a.example/mcp"},
                               "legacy":{"type":"sse","url":"https://b.example/sse"}}}
            """.trimIndent(),
        )
        assertTrue(snap.entry("plain")!!.spec is McpServerSpec.Stdio)
        assertEquals(McpTransportKind.Http, (snap.entry("aliased")!!.spec as McpServerSpec.Remote).kind)
        assertEquals(McpTransportKind.Sse, (snap.entry("legacy")!!.spec as McpServerSpec.Remote).kind)
    }

    @Test
    fun `a url without type is a config error and gets skipped`() {
        val snap = load("$home/.claude.json" to """{"mcpServers":{"broken":{"url":"https://a.example/mcp"}}}""")
        assertNull(snap.entry("broken"))
        assertTrue("有 url 却没有 type" in snap.notesJoined())
    }

    @Test
    fun `unknown and sdk types, invalid and reserved names are skipped with notes`() {
        val snap = load(
            "$home/.claude.json" to """
                {"mcpServers":{"weird":{"type":"carrier-pigeon","url":"https://a.example"},
                               "inproc":{"type":"sdk"},
                               "bad name":{"command":"srv"},
                               "workspace":{"command":"srv"},
                               "ok":{"command":"srv"}}}
            """.trimIndent(),
        )
        assertEquals(listOf("ok"), snap.servers.map { it.spec.name })
        val notes = snap.notesJoined()
        assertTrue("不认识的 type" in notes)
        assertTrue("type \"sdk\"" in notes)
        assertTrue("名字只许字母数字连字符下划线" in notes)
        assertTrue("保留名" in notes)
    }

    @Test
    fun `hidden whitespace is warned about but never trimmed`() {
        val snap = load(
            "$home/.claude.json" to """
                {"mcpServers":{"ws":{"command":"srv ","args":["--token abc "],
                                     "env":{"KEY ":"v "},"type":"stdio",
                                     "typeComment":"headers only exist on remote"}}}
            """.trimIndent(),
        )
        val spec = snap.entry("ws")!!.spec as McpServerSpec.Stdio
        assertEquals("srv ", spec.command)
        assertEquals(listOf("--token abc "), spec.args)
        val notes = snap.notesJoined()
        assertTrue("首尾空白" in notes && "command" in notes && "args[0]" in notes)
    }

    @Test
    fun `timeout below 1000 is ignored, above is kept, and alwaysLoad plus oauth parse`() {
        val snap = load(
            "$home/.claude.json" to """
                {"mcpServers":{"small":{"type":"http","url":"https://a.example/mcp","timeout":999,"alwaysLoad":true},
                               "large":{"type":"http","url":"https://b.example/mcp","timeout":60000,
                                        "oauth":{"clientId":"cid","callbackPort":8080,
                                                 "authServerMetadataUrl":"https://auth.example/.well-known/x",
                                                 "scopes":"read write"}}}}
            """.trimIndent(),
        )
        assertNull(snap.entry("small")!!.spec.timeoutMs)
        assertTrue((snap.entry("small")!!.spec as McpServerSpec.Remote).alwaysLoad)
        assertTrue("低于 1000" in snap.notesJoined())
        val large = snap.entry("large")!!.spec as McpServerSpec.Remote
        assertEquals(60_000L, large.timeoutMs)
        assertEquals("cid", large.oauth!!.clientId)
        assertEquals(8080, large.oauth!!.callbackPort)
        assertEquals("read write", large.oauth!!.scopes)
    }

    @Test
    fun `ws parses but stays disabled with a reason`() {
        val snap = load("$home/.claude.json" to """{"mcpServers":{"sock":{"type":"ws","url":"wss://a.example/ws"}}}""")
        val sock = snap.entry("sock")!!
        assertEquals(McpTransportKind.Ws, (sock.spec as McpServerSpec.Remote).kind)
        assertEquals(McpGate.Disabled, sock.gate)
        assertTrue(sock.reason!!.startsWith("ws 传输首期不支持"))
    }

    // ---------- 规则 5：环境变量展开 ----------

    @Test
    fun `expansion covers command args env url and headers with defaults`() {
        val snap = load(
            "$home/.claude.json" to """
                {"mcpServers":{"local":{"command":"${'$'}{HOME}/bin/srv","args":["--config","${'$'}{CFG:-/etc/default.cfg}"],
                                         "env":{"TOKEN":"${'$'}{TOK}"}},
                               "remote":{"type":"http","url":"${'$'}{HOST}/mcp",
                                         "headers":{"Authorization":"Bearer ${'$'}{AUTH}"}}}}
            """.trimIndent(),
            env = mapOf("HOME" to "/home/user", "TOK" to "tok123", "HOST" to "https://x.example", "AUTH" to "auth456"),
        )
        val local = snap.entry("local")!!.spec as McpServerSpec.Stdio
        assertEquals("/home/user/bin/srv", local.command)
        assertEquals(listOf("--config", "/etc/default.cfg"), local.args)
        assertEquals(mapOf("TOKEN" to "tok123"), local.env)
        val remote = snap.entry("remote")!!.spec as McpServerSpec.Remote
        assertEquals("https://x.example/mcp", remote.url)
        assertEquals(mapOf("Authorization" to "Bearer auth456"), remote.headers)
        assertTrue(snap.entry("local")!!.unresolvedVars.isEmpty())
    }

    @Test
    fun `unset variables without a default stay literal and are reported`() {
        val snap = load(
            "$home/.claude.json" to """{"mcpServers":{"r":{"type":"http","url":"https://${'$'}{MISSING}/mcp"}}}""",
            env = emptyMap(),
        )
        val r = snap.entry("r")!!
        assertEquals("https://${'$'}{MISSING}/mcp", (r.spec as McpServerSpec.Remote).url)
        assertEquals(listOf("MISSING"), r.unresolvedVars)
        assertTrue("MISSING" in snap.notesJoined())
    }

    @Test
    fun `credential variables read as empty in remote url and headers, ANTHROPIC_BASE_URL does not`() {
        val snap = load(
            "$home/.claude.json" to """
                {"mcpServers":{"a":{"type":"http","url":"${'$'}{ANTHROPIC_BASE_URL}/mcp",
                                    "headers":{"Authorization":"Bearer ${'$'}{ANTHROPIC_AUTH_TOKEN}"}},
                               "b":{"type":"http","url":"https://x.example/mcp",
                                    "headers":{"X-Key":"${'$'}{ANTHROPIC_API_KEY:-fallback}"}}}}
            """.trimIndent(),
            env = mapOf(
                "ANTHROPIC_BASE_URL" to "https://proxy.example",
                "ANTHROPIC_AUTH_TOKEN" to "sk-live-secret",
                "ANTHROPIC_API_KEY" to "sk-ant-secret",
            ),
        )
        val a = snap.entry("a")!!.spec as McpServerSpec.Remote
        assertEquals("https://proxy.example/mcp", a.url)
        assertEquals("Bearer ", a.headers["Authorization"])
        val b = snap.entry("b")!!.spec as McpServerSpec.Remote
        assertEquals("", b.headers["X-Key"]) // 默认值也无效
        assertTrue(snap.entry("a")!!.unresolvedVars.isEmpty())
    }

    @Test
    fun `settings env stacks over the host environment for expansion`() {
        val snap = load(
            "$home/.claude/settings.json" to """{"env":{"FOO":"from-settings"}}""",
            "$home/.claude.json" to """{"mcpServers":{"r":{"type":"http","url":"https://${'$'}{FOO}.example/mcp"}}}""",
            env = mapOf("FOO" to "from-host"),
        )
        assertEquals("https://from-settings.example/mcp", (snap.entry("r")!!.spec as McpServerSpec.Remote).url)
    }

    // ---------- 规则 6：闸门 ----------

    @Test
    fun `an untrusted workspace keeps project servers untrusted no matter what approves them`() {
        val snap = load(
            "$project/.mcp.json" to """{"mcpServers":{"a":{"command":"srv"}}}""",
            "$project/.claude/settings.json" to """{"enableAllProjectMcpServers":true}""",
            "$home/.claude/settings.json" to """{"enableAllProjectMcpServers":true}""",
            trustProject = false,
        )
        assertEquals(McpGate.Untrusted, snap.entry("a")!!.gate)
        assertTrue(snap.entry("a")!!.reason!!.contains("信任"))
    }

    @Test
    fun `a trusted project server is enabled by approval keys from trusted layers`() {
        val approved = load(
            "$project/.mcp.json" to """{"mcpServers":{"a":{"command":"srv"},"b":{"command":"srv"}}}""",
            "$project/.claude/settings.local.json" to """{"enabledMcpjsonServers":["a"]}""",
        )
        assertEquals(McpGate.Enabled, approved.entry("a")!!.gate)
        assertEquals(McpGate.NeedsApproval, approved.entry("b")!!.gate)

        val all = load(
            "$project/.mcp.json" to """{"mcpServers":{"a":{"command":"srv"}}}""",
            "$home/.claude/settings.json" to """{"enableAllProjectMcpServers":true}""",
        )
        assertEquals(McpGate.Enabled, all.entry("a")!!.gate)
    }

    @Test
    fun `approval keys of an untrusted project layer do not count, and enableAll false in a higher layer wins`() {
        val untrustedProjectApproval = load(
            "$project/.mcp.json" to """{"mcpServers":{"a":{"command":"srv"}}}""",
            "$project/.claude/settings.json" to """{"enabledMcpjsonServers":["a"],"enableAllProjectMcpServers":true}""",
            trustProject = false,
        )
        // 设计比 CC 严：未信任一律 Untrusted；这里同时验证仓库里的批准键没有被采纳成别的结果
        assertEquals(McpGate.Untrusted, untrustedProjectApproval.entry("a")!!.gate)

        val higherFalse = load(
            "$project/.mcp.json" to """{"mcpServers":{"a":{"command":"srv"}}}""",
            "$home/.claude/settings.json" to """{"enableAllProjectMcpServers":true}""",
            "$project/.claude/settings.local.json" to """{"enableAllProjectMcpServers":false}""",
        )
        assertEquals(McpGate.NeedsApproval, higherFalse.entry("a")!!.gate)
    }

    @Test
    fun `disabledMcpjsonServers applies from any layer, even an untrusted project one`() {
        val snap = load(
            "$project/.mcp.json" to """{"mcpServers":{"a":{"command":"srv"}}}""",
            "$project/.claude/settings.json" to """{"disabledMcpjsonServers":["a"]}""",
            trustProject = false,
        )
        assertEquals(McpGate.Disabled, snap.entry("a")!!.gate)
        assertTrue(snap.entry("a")!!.reason!!.contains("disabledMcpjsonServers"))
    }

    // CC 2.1.299：这两个键写成单个字符串（字母 / 数字 / - / _）也算数
    @Test
    fun `disabledMcpjsonServers as a plain string disables that server even when enableAll is on`() {
        val snap = load(
            "$project/.mcp.json" to """{"mcpServers":{"foo":{"command":"srv"},"bar":{"command":"srv"}}}""",
            "$home/.claude/settings.json" to """{"enableAllProjectMcpServers":true,"disabledMcpjsonServers":"foo"}""",
        )
        assertEquals(McpGate.Disabled, snap.entry("foo")!!.gate)
        assertEquals(McpGate.Enabled, snap.entry("bar")!!.gate)
        assertTrue(snap.notes.isEmpty())
    }

    @Test
    fun `enabledMcpjsonServers as a plain string approves that server only`() {
        val snap = load(
            "$project/.mcp.json" to """{"mcpServers":{"a-b_1":{"command":"srv"},"b":{"command":"srv"}}}""",
            "$project/.claude/settings.local.json" to """{"enabledMcpjsonServers":"a-b_1"}""",
        )
        assertEquals(McpGate.Enabled, snap.entry("a-b_1")!!.gate)
        assertEquals(McpGate.NeedsApproval, snap.entry("b")!!.gate)
    }

    @Test
    fun `a string that is not a single plain server name is ignored with a note`() {
        val snap = load(
            "$project/.mcp.json" to """{"mcpServers":{"foo":{"command":"srv"},"bar":{"command":"srv"}}}""",
            "$home/.claude/settings.json" to """{"enableAllProjectMcpServers":true,"disabledMcpjsonServers":"foo,bar"}""",
            "$project/.claude/settings.local.json" to """{"enabledMcpjsonServers":"a b"}""",
        )
        // 形状不对的字符串不当名单用：foo / bar 没被禁
        assertEquals(McpGate.Enabled, snap.entry("foo")!!.gate)
        assertEquals(McpGate.Enabled, snap.entry("bar")!!.gate)
        assertTrue(snap.notes.any { it.contains("disabledMcpjsonServers") && it.contains("foo,bar") })
        assertTrue(snap.notes.any { it.contains("enabledMcpjsonServers") })
        // 空串、数字也不是服务器名
        val empty = load("$home/.claude/settings.json" to """{"disabledMcpjsonServers":""}""")
        assertTrue(empty.notes.any { it.contains("disabledMcpjsonServers") })
    }

    @Test
    fun `the per-project ui toggle in claude json disables a user-scope server`() {
        val snap = load(
            "$home/.claude.json" to """
                {"mcpServers":{"hub":{"type":"http","url":"https://hub.example/mcp"}},
                 "projects":{"$project":{"disabledMcpServers":["hub"]}}}
            """.trimIndent(),
        )
        assertEquals(McpGate.Disabled, snap.entry("hub")!!.gate)
    }

    @Test
    fun `headersHelper of project and local scope waits for trust, user scope does not`() {
        val snap = load(
            "$project/.mcp.json" to """{"mcpServers":{"p":{"type":"http","url":"https://p.example/mcp","headersHelper":"helper.sh"}}}""",
            "$home/.claude.json" to """
                {"mcpServers":{"u":{"type":"http","url":"https://u.example/mcp","headersHelper":"helper.sh"}},
                 "projects":{"$project":{"mcpServers":{"l":{"type":"http","url":"https://l.example/mcp","headersHelper":"helper.sh"}}}}}
            """.trimIndent(),
            trustProject = false,
        )
        assertNull((snap.entry("p")!!.spec as McpServerSpec.Remote).headersHelper)
        assertNull((snap.entry("l")!!.spec as McpServerSpec.Remote).headersHelper)
        assertEquals("helper.sh", (snap.entry("u")!!.spec as McpServerSpec.Remote).headersHelper)
        assertTrue("headersHelper" in snap.notesJoined() && "信任" in snap.notesJoined())

        val trusted = load(
            "$project/.mcp.json" to """{"mcpServers":{"p":{"type":"http","url":"https://p.example/mcp","headersHelper":"helper.sh"}}}""",
            trustProject = true,
        )
        assertEquals("helper.sh", (trusted.entry("p")!!.spec as McpServerSpec.Remote).headersHelper)
    }

    @Test
    fun `the strictest gate wins and its reason is the one reported`() {
        val snap = load(
            "$project/.mcp.json" to """{"mcpServers":{"a":{"command":"srv"}}}""",
            "$project/.claude/settings.json" to """{"disabledMcpjsonServers":["a"]}""",
            "$home/.claude/settings.json" to """{"deniedMcpServers":[{"serverName":"a"}]}""",
            trustProject = false,
        )
        assertEquals(McpGate.Denied, snap.entry("a")!!.gate)
        assertTrue(snap.entry("a")!!.reason!!.contains("deniedMcpServers"))
    }

    // ---------- 规则 8：凭证不外泄 ----------

    @Test
    fun `toString and notes never carry env values or header values`() {
        val snap = load(
            "$home/.claude.json" to """
                {"mcpServers":{"local":{"command":"srv","env":{"SECRET_TOKEN":"env-secret-value-xyz"}},
                               "remote":{"type":"http","url":"https://r.example/mcp",
                                         "headers":{"Authorization":"Bearer hdr-secret-value-xyz"}}}}
            """.trimIndent(),
            env = mapOf("SECRET_TOKEN" to "env-secret-value-xyz"),
        )
        val dump = snap.servers.joinToString("\n") { it.spec.toString() }
        assertTrue("env 值不进 toString", "env-secret-value-xyz" !in dump)
        assertTrue("header 值不进 toString", "hdr-secret-value-xyz" !in dump)
        assertTrue("env 值不进 note", "env-secret-value-xyz" !in snap.notesJoined())
        assertTrue("header 值不进 note", "hdr-secret-value-xyz" !in snap.notesJoined())
        assertTrue("toString 写了名字与作用域", "local" in dump && "User" in dump)
    }
}
