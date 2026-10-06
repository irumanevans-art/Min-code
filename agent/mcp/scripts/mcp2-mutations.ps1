param([string]$Item = 'roots')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidence = Join-Path $repo "agent/mcp/build/mcp2-review/$Item"
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$mutations = @(
    @{ Item='roots'; Name='capability'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpConnections.kt'; From='put("listChanged", true)'; To='put("listChanged", false)'; Module='kernel'; Test='*McpRootsTest' },
    @{ Item='roots'; Name='project-union'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpManager.kt'; From='.flatMap { it.rt.state.value.additionalDirectories }'; To='.flatMap { emptyList<GuestPath>() }'; Module='kernel'; Test='*McpRootsTest' },
    @{ Item='roots'; Name='change-notification'; File='agent/mcp/src/main/kotlin/dev/min/agent/mcp/McpClient.kt'; From='rpcNotification("notifications/roots/list_changed")'; To='rpcNotification("notifications/roots/changed")'; Module='mcp'; Test='*McpClientTest' },
    @{ Item='resources'; Name='hide-ui'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpResources.kt'; From='uri.startsWith("ui://",'; To='uri.startsWith("wrong://",'; Module='kernel'; Test='*McpResourcesSessionTest' },
    @{ Item='resources'; Name='frozen-prefix'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/Fold.kt'; From='mcpTools = e,'; To='mcpTools = e.copy(resourceServers = mcpTools?.resourceServers ?: e.resourceServers),'; Module='kernel'; Test='*McpResourcesSessionTest' },
    @{ Item='resources'; Name='input-content'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpResources.kt'; From='attached += result.content'; To='attached += emptyList<Part>()'; Module='kernel'; Test='*McpResourcesSessionTest' },
    @{ Item='resources'; Name='read-image'; File='agent/mcp/src/main/kotlin/dev/min/agent/mcp/McpClient.kt'; From='embedded.mimeType?.startsWith("image/") == true'; To='embedded.mimeType?.startsWith("wrong/") == true'; Module='mcp'; Test='*McpResourcesTest' },
    @{ Item='prompts'; Name='required'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/mcp/McpPromptCommands.kt'; From='it.required && it.name !in values'; To='it.required && it.name !in values && false'; Module='ext'; Test='*McpPromptCommandsTest' },
    @{ Item='prompts'; Name='quote'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/mcp/McpPromptCommands.kt'; From='if (char == quote) quote = null else token.append(char)'; To='if (char == quote) quote = null else token.append(if (char.isWhitespace()) ''_'' else char)'; Module='ext'; Test='*McpPromptCommandsTest' },
    @{ Item='prompts'; Name='assistant-content'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpPrompts.kt'; From='mcpCaller.getPrompt(server, prompt, arguments).flatMapIndexed'; To='mcpCaller.getPrompt(server, prompt, arguments).filter { it.role == "user" }.flatMapIndexed'; Module='kernel'; Test='*McpPromptsSessionTest' },
    @{ Item='elicitation'; Name='unattended'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpElicitation.kt'; From='!deps.canAnswerMcpElicitation'; To='false'; Module='kernel'; Test='*McpElicitationTest' },
    @{ Item='elicitation'; Name='typed-content'; File='agent/spi/src/main/kotlin/dev/min/agent/spi/McpFormValues.kt'; From='"integer" -> JsonPrimitive(text.toLongOrNull() ?: return null)'; To='"integer" -> JsonPrimitive(text)'; Module='kernel'; Test='*McpElicitationTest' },
    @{ Item='elicitation'; Name='url-consent'; File='agent/cli/src/main/kotlin/dev/min/agent/cli/McpUrlConsent.kt'; From='if (reply.trim().lowercase() !in setOf("y", "yes"))'; To='if (false && reply.trim().lowercase() !in setOf("y", "yes"))'; Module='cli'; Test='*McpUrlConsentTest' },
    @{ Item='elicitation'; Name='reverse-cancel'; File='agent/mcp/src/main/kotlin/dev/min/agent/mcp/McpClient.kt'; From='serverCancelled.add(requestId); it.cancel()'; To='it.cancel()'; Module='mcp'; Test='*McpClientTest' },
    @{ Item='elicitation'; Name='input-owner'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpManager.kt'; From='?: entry.inputOwner'; To='?: null'; Module='kernel'; Test='*McpElicitationTest' },
    @{ Item='toolsearch'; Name='threshold'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpToolSearch.kt'; From='tokens >= window'; To='tokens > window'; Module='kernel'; Test='*McpToolSearchTest' },
    @{ Item='toolsearch'; Name='append-order'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/Fold.kt'; From='loadedMcpTools = (loadedMcpTools + e.names).distinct()'; To='loadedMcpTools = (loadedMcpTools + e.names).distinct().sorted()'; Module='kernel'; Test='*McpToolSearchTest' },
    @{ Item='toolsearch'; Name='inherit'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/SessionHub.kt'; From='parent.loadedMcpTools.takeIf'; To='emptyList<String>().takeIf'; Module='kernel'; Test='*McpToolSearchTest' },
    @{ Item='toolsearch'; Name='server-always'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpManager.kt'; From='e.spec.alwaysLoad ||'; To='false ||'; Module='kernel'; Test='*McpManagerTest' },
    @{ Item='toolsearch'; Name='tool-always'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/McpManager.kt'; From='d.meta?.get("anthropic/alwaysLoad")'; To='d.meta?.get("wrong/alwaysLoad")'; Module='kernel'; Test='*McpManagerTest' }
)
$results = @()
foreach ($m in $mutations | Where-Object { $_.Item -eq $Item }) {
    $source = Join-Path $repo $m.File
    $original = [IO.File]::ReadAllBytes($source)
    $text = [Text.Encoding]::UTF8.GetString($original)
    if (-not $text.Contains($m.From)) { throw "Mutation anchor missing: $($m.Name)" }
    $dest = Join-Path $evidence $m.Name
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    try {
        [IO.File]::WriteAllText($source, $text.Replace($m.From, $m.To), [Text.UTF8Encoding]::new($false))
        Push-Location $repo
        try {
            $ErrorActionPreference = 'Continue'
            & ./gradlew.bat ":agent:$($m.Module):test" --tests $m.Test --console=plain *> (Join-Path $dest 'gradle.log')
            $code = $LASTEXITCODE
            $ErrorActionPreference = 'Stop'
        } finally { Pop-Location }
        $failed = @()
        Get-ChildItem (Join-Path $repo "agent/$($m.Module)/build/test-results/test") -Filter 'TEST-*.xml' | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $dest
            [xml]$xml = Get-Content -LiteralPath $_.FullName -Raw
            foreach ($case in $xml.testsuite.testcase) { if ($case.failure -and $case.classname -like $m.Test -and $case.name -ne 'initializationError') { $failed += "$($case.classname): $($case.name)" } }
        }
        $log = Get-Content (Join-Path $dest 'gradle.log') -Raw
        $valid = $code -ne 0 -and $failed.Count -gt 0 -and $log -notmatch 'Compilation error|No tests found'
        $results += @{ mutation=$m.Name; killed=$valid; exit=$code; failures=$failed }
    } finally { [IO.File]::WriteAllBytes($source, $original) }
}
$results | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $evidence 'results.json')
if ($results.Count -lt 3 -or @($results | Where-Object { -not $_.killed }).Count -gt 0) { throw 'Mutation validation failed' }
