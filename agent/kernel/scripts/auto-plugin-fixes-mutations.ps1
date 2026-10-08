param([Parameter(Mandatory=$true)][string]$EvidenceDirectory, [string]$Only = '*')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
[System.IO.Directory]::CreateDirectory($evidence) | Out-Null
$env:PATH = 'C:\Program Files\Git\bin;' + $env:PATH
$cases = @(
    @{ Name='project-allow'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/config/Settings.kt'; Before='!trustProject && it.source == ConfigSource.Project.label'; After='false && it.source == ConfigSource.Project.label'; Module='kernel'; Class='dev.min.agent.kernel.SettingsSecurityFixTest' },
    @{ Name='project-directories'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/config/Settings.kt'; Before='trustProject || it.source != ConfigSource.Project'; After='true || it.source != ConfigSource.Project'; Module='kernel'; Class='dev.min.agent.kernel.SettingsSecurityFixTest' },
    @{ Name='ask-human'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/ToolExecutor.kt'; Before='else if (PolicyEngine.askRuleHit(ask, policyContext()) != null && policy !is Verdict.Deny)'; After='else if (false && PolicyEngine.askRuleHit(ask, policyContext()) != null && policy !is Verdict.Deny)'; Module='kernel'; Class='dev.min.agent.kernel.AutoReviewerTest' },
    @{ Name='auto-child'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/SessionHub.kt'; Before='ps.settings.mode == PermissionMode.Auto -> PermissionMode.Auto'; After='false && ps.settings.mode == PermissionMode.Auto -> PermissionMode.Auto'; Module='kernel'; Class='dev.min.agent.kernel.SettingsSecurityFixTest' },
    @{ Name='auto-wide-allow'; File='agent/policy/src/main/kotlin/dev/min/agent/policy/PolicyEngine.kt'; Before='!suspendedInAuto(it)'; After='true'; Module='kernel'; Class='dev.min.agent.kernel.AutoReviewerTest' },
    @{ Name='origin-user'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/AutoReviewer.kt'; Before='entry.origin != InputOrigin.User'; After='false'; Module='kernel'; Class='dev.min.agent.kernel.AutoReviewerTest' },
    @{ Name='mcp-attachment'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/AutoReviewer.kt'; Before='.takeWhile { !it.text.startsWith("[MCP 附件 @") }'; After=''; Module='kernel'; Class='dev.min.agent.kernel.AutoReviewerTest' },
    @{ Name='mode-reread'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/ToolExecutor.kt'; Before='if (rt.state.value.settings.mode != PermissionMode.Auto) {'; After='if (false) {'; Module='kernel'; Class='dev.min.agent.kernel.AutoReviewerTest' },
    @{ Name='circuit-short-circuit'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/AutoReviewer.kt'; Before='rt.state.value.autoReviewTripped ->'; After='false ->'; Module='kernel'; Class='dev.min.agent.kernel.AutoReviewerTest' },
    @{ Name='circuit-three'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/Fold.kt'; Before='autoReviewConsecutiveDenials + 1 >= 3'; After='autoReviewConsecutiveDenials + 1 >= 300'; Module='kernel'; Class='dev.min.agent.kernel.AutoReviewerTest' },
    @{ Name='circuit-twenty'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/Fold.kt'; Before='autoReviewDenials + 1 >= 20'; After='autoReviewDenials + 1 >= 200'; Module='kernel'; Class='dev.min.agent.kernel.AutoReviewerTest' },
    @{ Name='default-soft'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/AutoReviewer.kt'; Before='softDeny = expand(config.softDeny, DEFAULT_SOFT)'; After='softDeny = config.softDeny'; Module='kernel'; Class='dev.min.agent.kernel.AutoReviewerTest' },
    @{ Name='malformed-auto'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/config/AutoModeSettings.kt'; Before='AutoReviewSettingsLoaded(unavailable = true'; After='AutoReviewSettingsLoaded(unavailable = false'; Module='kernel'; Class='dev.min.agent.kernel.SettingsSecurityFixTest' },
    @{ Name='duplicate-json'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/AutoReviewer.kt'; Before='rejectDuplicateKeys(json)'; After='
'; Module='kernel'; Class='dev.min.agent.kernel.AutoReviewerTest' },
    @{ Name='auto-workspace-edit'; File='agent/policy/src/main/kotlin/dev/min/agent/policy/PolicyEngine.kt'; Before='if (effectivelyReadOnly || facts.isWorkspaceEditOnly) Verdict.Allow(byMode) else Verdict.Ask'; After='if (effectivelyReadOnly) Verdict.Allow(byMode) else Verdict.Ask'; Module='policy'; Class='dev.min.agent.policy.PolicyEngineTest' },
    @{ Name='managed-create-lock'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/SessionRuntime.kt'; Before='if (mode == PermissionMode.Auto && configured.disableAutoMode'; After='if (false && mode == PermissionMode.Auto && configured.disableAutoMode'; Module='kernel'; Class='dev.min.agent.kernel.SettingsSecurityFixTest' },
    @{ Name='permission-denied-hook'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/ToolExecutor.kt'; Before='if (verdict.by == DecidedBy.Classifier)'; After='if (false && verdict.by == DecidedBy.Classifier)'; Module='kernel'; Class='dev.min.agent.kernel.AutoReviewerTest' },
    @{ Name='short-circuit-model'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/AutoReviewer.kt'; Before='call.callId, calledModel, result.first'; After='call.callId, ref, result.first'; Module='kernel'; Class='dev.min.agent.kernel.AutoReviewerTest' },
    @{ Name='hard-prose'; File='agent/policy/src/main/kotlin/dev/min/agent/policy/Rules.kt'; Before='source == "autoMode.hard_deny"'; After='false && source == "autoMode.hard_deny"'; Module='kernel'; Class='dev.min.agent.kernel.AutoReviewerTest' },
    @{ Name='object-command-path'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginParser.kt'; Before='m.obj("commands")?.forEach { (name, value) -> add("commands.$name.source", (value as? JsonObject)?.get("source")) }'; After=''; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='final-command-path'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBundle.kt'; Before='if (!realPath.startsWith("$commandRoot/"))'; After='if (false && !realPath.startsWith("$commandRoot/"))'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='plugin-agent-mode'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBundle.kt'; Before='hooks = null, permissionMode = null, mcpServers = null, initialPrompt = null'; After='hooks = it.hooks, permissionMode = it.permissionMode, mcpServers = it.mcpServers, initialPrompt = it.initialPrompt'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='enable-source'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBundle.kt'; Before='if (projectEnabled) trust(cwd)'; After='if (projectEnabled) true'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='plugin-key-validation'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBundle.kt'; Before='return valid
'; After='return true
'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='cache-containment'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBundle.kt'; Before='if (!canonical(GuestPath(base)).value.startsWith("$cacheRoot/"))'; After='if (false && !canonical(GuestPath(base)).value.startsWith("$cacheRoot/"))'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='five-hook-types'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBundle.kt'; Before='val handlers = (obj["hooks"] as? JsonArray).orEmpty()'; After='val handlers = (obj["hooks"] as? JsonArray).orEmpty().filter { (it as? JsonObject)?.get("type") == JsonPrimitive("command") }'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='dependency-false'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBatch.kt'; Before='dependency && winners[id]?.explicitlyDisabled == true'; After='false && dependency && winners[id]?.explicitlyDisabled == true'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='bad-hook-args'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBundle.kt'; Before='if (args.any { it !is JsonPrimitive || !it.isString })'; After='if (false && args.any { it !is JsonPrimitive || !it.isString })'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='plugin-runtime-fallback'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/SessionRuntime.kt'; Before='catch (_: Exception) {
            pluginsAttempted = true'; After='catch (e: Exception) {
            throw e
            pluginsAttempted = true'; Module='kernel'; Class='dev.min.agent.kernel.SettingsSecurityFixTest' },
    @{ Name='hook-spawn-env'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/hooks/HookBatchRunner.kt'; Before='cwd, pluginProcessEnv(handlers)'; After='cwd, emptyMap()'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='inline-trust'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBundle.kt'; Before='realRoot in inlineRoots ||'; After='false ||'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='inline-identity'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginDiscovery.kt'; Before='if (request.sideload) "inline" else request.ref?.marketplace'; After='request.ref?.marketplace'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='namespace-conflict'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBatch.kt'; Before='it.namespace == plugin.namespace && plugin.enabled'; After='false && it.namespace == plugin.namespace && plugin.enabled'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='managed-sideload'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBundle.kt'; Before='?.booleanOrNull == true
            if (disableSideload'; After='?.booleanOrNull == false
            if (disableSideload'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='component-isolation'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginLoader.kt'; Before='return result.copy(plugin = result.plugin?.copy(errors = allErrors), errors = allErrors)'; After='return result.copy(plugin = if (allErrors.isEmpty()) result.plugin else null, errors = allErrors)'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='undeclared-file'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginLoader.kt'; Before='if (depth == 0 && e.name !in roots && e.name !in defaultFiles) continue'; After='if (false && depth == 0 && e.name !in roots && e.name !in defaultFiles) continue'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='directory-namespace'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginDiscovery.kt'; Before='if (!validPluginName(ns))'; After='if (false && !validPluginName(ns))'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='semantic-versions'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBundle.kt'; Before='versions.maxWithOrNull(compareBySemanticVersion { it.name })'; After='versions.maxByOrNull { it.name }'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' },
    @{ Name='project-plugin-mcp'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/mcp/McpConfigLoader.kt'; Before='?.requiresApproval == true &&'; After='?.requiresApproval == false &&'; Module='ext'; Class='dev.min.agent.ext.plugins.PluginSecurityFixTest' }
)
$summary = @()
Push-Location $root
try {
    foreach ($case in $cases | Where-Object { $_.Name -like $Only }) {
        $file = Join-Path $root $case.File
        $original = [System.IO.File]::ReadAllBytes($file)
        $source = [System.Text.Encoding]::UTF8.GetString($original).Replace("`r`n", "`n")
        $log = Join-Path $evidence ($case.Name + '.log')
        if (-not $source.Contains($case.Before)) { throw "Missing mutation anchor: $($case.Name)" }
        Write-Output "START $($case.Name)"
        try {
            [System.IO.File]::WriteAllText($file, $source.Replace($case.Before, $case.After), [System.Text.UTF8Encoding]::new($false))
            & .\gradlew.bat ":agent:$($case.Module):cleanTest" ":agent:$($case.Module):test" '--tests' $case.Class '--console=plain' '--max-workers=2' *> $log
            $gradleExit = $LASTEXITCODE
            Add-Content -LiteralPath $log -Value "GRADLE_EXIT=$gradleExit"
            $xmlPath = Join-Path $root "agent/$($case.Module)/build/test-results/test/TEST-$($case.Class).xml"
            $compiled = -not (Select-String -LiteralPath $log -Pattern 'compileKotlin FAILED|compileTestKotlin FAILED|Compilation error' -Quiet)
            $failedNames = @()
            if (Test-Path -LiteralPath $xmlPath) {
                [xml]$xml = Get-Content -LiteralPath $xmlPath -Raw
                $failedNames = @($xml.testsuite.testcase | Where-Object { $_.failure -and $_.failure.type -notmatch 'TimeoutCancellation|CancellationException' } | ForEach-Object { $_.name })
                Copy-Item -LiteralPath $xmlPath -Destination (Join-Path $evidence ($case.Name + '.xml'))
            }
            $killed = $gradleExit -ne 0 -and $compiled -and $failedNames.Count -gt 0
            $summary += "$($case.Name): GRADLE_EXIT=$gradleExit COMPILED=$compiled KILLED=$killed TESTS=$($failedNames -join ' | ')"
            Write-Output $summary[-1]
        } finally { [System.IO.File]::WriteAllBytes($file, $original) }
        [System.IO.File]::WriteAllLines((Join-Path $evidence 'summary.txt'), $summary, [System.Text.UTF8Encoding]::new($false))
    }
} finally { Pop-Location }
if (@($summary | Where-Object { $_ -notmatch 'KILLED=True' }).Count -gt 0) { exit 1 }
exit 0

