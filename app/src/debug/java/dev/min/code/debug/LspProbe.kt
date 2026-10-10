package dev.min.code.debug

import android.util.Log
import dev.min.agent.lsp.LspManager
import dev.min.agent.spi.*
import dev.min.code.core.engine.min.MinAgentEngine
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** 直接走组合根的 proot 端口和配额；探针不用模型或真实密钥。 */
internal object LspProbe {
    suspend fun run(engine: MinAgentEngine, install: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val root = GuestPath("/workspace/lsp-probe")
        val path = GuestPath("${root.value}/sample.c")
        val manager = LspManager(scope, engine.processes)
        try {
            if (install) {
                val installed = engine.execForProbe.run(ShellCommand("command -v clangd || (timeout 120 apt-get update -qq && timeout 120 apt-get install -y --no-install-recommends clangd)", GuestPath("/root")), ExecOptions(250_000, maxOutputChars = 3000))
                Log.i("MinLspProbe", "INSTALL $installed")
            }
            var server = LspServerSpec("probe-clangd", Json.parseToJsonElement("""{"command":"clangd","args":["--background-index=false","--log=error"],"extensionToLanguage":{".c":"c"},"startupTimeout":10000}""").jsonObject)
            var lsp = manager.port(root, listOf(server), engine.workspaceFs)
            val before = "int old = ;\nint keep = 1;\n"
            engine.workspaceFs.writeAtomic(path, before.encodeToByteArray())
            var navigation = lsp.query(LspQuery("documentSymbol", path, 1, 1))
            Log.i("MinLspProbe", "NAV error=${navigation.isError} ${navigation.displayText().take(2000)}")
            if (navigation.isError) {
                manager.closeAll()
                val script = GuestPath("${root.value}/fake-lsp.sh")
                engine.workspaceFs.writeAtomic(script, fakeServer().encodeToByteArray())
                server = LspServerSpec("probe-fake", buildJsonObject {
                    put("command", "bash"); put("args", JsonArray(listOf(JsonPrimitive(script.value))))
                    put("env", buildJsonObject { put("LC_ALL", "C") }); put("extensionToLanguage", buildJsonObject { put(".c", "c") })
                })
                lsp = manager.port(root, listOf(server), engine.workspaceFs)
                navigation = lsp.query(LspQuery("documentSymbol", path, 1, 1))
                Log.i("MinLspProbe", "FALLBACK fake=true error=${navigation.isError} ${navigation.displayText()}")
                if (navigation.isError) { Log.i("MinLspProbe", "LSP_DONE success=false"); return }
            }
            Log.i("MinLspProbe", "SERVER ${server.name}")
            // 冷启动索引在第一条导航时已经发起，诊断基线仍沿用生产的 300ms 短期限。
            delay(1500)
            val token = lsp.beforeEdit(path, before)
            val after = "int old = ;\nint keep = ;\n"
            engine.workspaceFs.writeAtomic(path, after.encodeToByteArray())
            val added = lsp.afterEdit(path, after, token)
            Log.i("MinLspProbe", "DIAGNOSTICS baseline=${token != null} added=${added.size} $added")
            val again = lsp.beforeEdit(path, after)
            val unchanged = lsp.afterEdit(path, after, again)
            Log.i("MinLspProbe", "UNCHANGED added=${unchanged.size}")
            Log.i("MinLspProbe", "LSP_DONE success=${token != null && added.size == 1 && added.single().line == 2 && unchanged.isEmpty()}")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.e("MinLspProbe", "LSP_DONE failed=${e::class.simpleName}", e) }
        finally { manager.closeAll(); scope.cancel() }
    }

    // 最小 rootfs 可能没有 Python/Node；退路只用已随 proot 提供的 Bash，也走真实 stdio 帧。
    private fun fakeServer(): String = """
        send() { printf 'Content-Length: %s\r\n\r\n%s' "${'$'}{#1}" "${'$'}1"; }
        while IFS= read -r header; do
          header=${'$'}{header%${'$'}'\r'}
          if [[ ${'$'}header == Content-Length:* ]]; then size=${'$'}{header#*: }; fi
          if [[ -n ${'$'}header ]]; then continue; fi
          IFS= read -r -N "${'$'}size" body || break
          if [[ ${'$'}body =~ \"method\":\"([^\"]+)\" ]]; then method=${'$'}{BASH_REMATCH[1]}; else continue; fi
          if [[ ${'$'}method == exit ]]; then exit 0; fi
          if [[ ${'$'}body =~ \"id\":([0-9]+) ]]; then
            id=${'$'}{BASH_REMATCH[1]}; result='[]'
            if [[ ${'$'}method == initialize ]]; then result='{"capabilities":{"textDocumentSync":1}}'; fi
            if [[ ${'$'}method == shutdown ]]; then result='null'; fi
            send "{\"jsonrpc\":\"2.0\",\"id\":${'$'}id,\"result\":${'$'}result}"
          fi
          if [[ ${'$'}method == textDocument/didOpen || ${'$'}method == textDocument/didChange ]]; then
            issues='{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"severity":1,"message":"old"}'
            if [[ ${'$'}body == *'int keep = ;'* ]]; then issues+=',{"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":1}},"severity":1,"message":"new"}'; fi
            send "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{\"uri\":\"file:///workspace/lsp-probe/sample.c\",\"diagnostics\":[${'$'}issues]}}"
          fi
        done
    """.trimIndent() + "\n"
}
