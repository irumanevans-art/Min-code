param([string]$Evidence = 'agent/ext/build/review/plugin-loader/mutations')
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$taskEvidence = [IO.Path]::GetFullPath((Join-Path $taskRoot $Evidence))
New-Item -ItemType Directory -Force -Path $taskEvidence | Out-Null
$taskPath = $env:PATH
$batchFile = 'agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginBatch.kt'
$pathFile = 'agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginValidation.kt'
$discoveryFile = 'agent/ext/src/main/kotlin/dev/min/agent/ext/plugins/PluginDiscovery.kt'
$defaultMcp = @'
        files[".mcp.json"]?.let { f ->
            val o = parseJson(f).jsonObject
            addMap(o.obj("mcpServers") ?: o, ".mcp.json") // CMP:806 包裹可省
        }
'@
$mutations = @(
    @{name='settings-first-wins'; file=$batchFile; old='settings[key] = value'; new='if (key !in settings) settings[key] = value'},
    @{name='duplicate-plugin-first-wins'; file=$batchFile; old='winners.remove(it.ref.id); winners[it.ref.id] = it'; new='if (it.ref.id !in winners) winners[it.ref.id] = it'},
    @{name='no-dependency-pull'; file=$batchFile; old='plugin.manifest?.dependencies.orEmpty())'; new='plugin.manifest?.dependencies.orEmpty().take(0))'},
    @{name='no-path-prefix'; file=$pathFile; old='if (!raw.startsWith("./"))'; new='if (raw.isEmpty())'},
    @{name='no-path-containment'; file=$pathFile; old='if (segs.firstOrNull() != rootName)'; new='if (segs.isEmpty())'},
    @{name='no-path-existence'; file=$pathFile; old='if (!exists)'; new='if (raw.isEmpty())'},
    @{name='non-strict-user-config'; file=$pathFile; old='val unknown = item.keys - USER_CONFIG_FIELDS'; new='val unknown = emptySet<String>()'},
    @{name='mcp-default-loaded-last'; file=$discoveryFile; old=$defaultMcp.TrimEnd().Replace("`r`n", "`n"); new=''; secondOld='        return mcp'; secondNew=($defaultMcp.TrimEnd().Replace("`r`n", "`n") + "`n        return mcp")}
)
$results = @()
try {
    if (Test-Path 'C:\Program Files\Git\bin\bash.exe') { $env:PATH = 'C:\Program Files\Git\bin;C:\Program Files\Git\usr\bin;' + $taskPath }
    Push-Location $taskRoot
    foreach ($mutation in $mutations) {
        $target = Join-Path $taskRoot $mutation.file
        $original = [IO.File]::ReadAllBytes($target)
        $text = [Text.Encoding]::UTF8.GetString($original).Replace("`r`n", "`n")
        $needle = $mutation.old
        if (($text.Split(@($needle), [StringSplitOptions]::None)).Count -ne 2) { throw "Mutation anchor must match once: $($mutation.name)" }
        $changed = $text.Replace($needle, $mutation.new)
        if ($mutation.ContainsKey('secondOld')) {
            if (($changed.Split(@($mutation.secondOld), [StringSplitOptions]::None)).Count -ne 2) { throw "Second mutation anchor must match once" }
            $changed = $changed.Replace($mutation.secondOld, $mutation.secondNew)
        }
        $dir = Join-Path $taskEvidence $mutation.name
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        try {
            [IO.File]::WriteAllText($target, $changed, [Text.UTF8Encoding]::new($false))
            & ./gradlew.bat :agent:ext:cleanTest :agent:ext:test --tests 'dev.min.agent.ext.plugins.*' --console=plain --max-workers=4 *> (Join-Path $dir 'gradle.log')
            $taskExit = $LASTEXITCODE
            $xmlDir = Join-Path $taskRoot 'agent/ext/build/test-results/test'
            $xmlFiles = @(Get-ChildItem $xmlDir -Filter '*.xml' -ErrorAction SilentlyContinue)
            $tests = 0; $failures = 0; $invalid = $false
            foreach ($file in $xmlFiles) {
                [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
                $tests += [int]$xml.testsuite.tests
                $failures += [int]$xml.testsuite.failures + [int]$xml.testsuite.errors
                if ($xml.testsuite.testcase.name -contains 'initializationError') { $invalid = $true }
                Copy-Item -LiteralPath $file.FullName -Destination $dir
            }
            $status = if ($taskExit -ne 0 -and $tests -gt 0 -and $failures -gt 0 -and !$invalid) { 'KILLED' } else { 'INVALID_OR_SURVIVED' }
            $result = [pscustomobject]@{name=$mutation.name; status=$status; GRADLE_EXIT=$taskExit; tests=$tests; failures=$failures}
            $results += $result
            $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $dir 'result.json') -Encoding utf8
            Write-Output "$($mutation.name): $status GRADLE_EXIT=$taskExit tests=$tests failures=$failures"
            if ($status -ne 'KILLED') { throw "Mutation did not produce a valid assertion failure: $($mutation.name)" }
        } finally { [IO.File]::WriteAllBytes($target, $original) }
    }
    $results | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskEvidence 'results.json') -Encoding utf8
    # 最后必须恢复后重跑；变异的失败绝不能充当最终基线验证。
    & ./gradlew.bat :agent:ext:cleanTest :agent:ext:test --tests 'dev.min.agent.ext.plugins.*' --console=plain --max-workers=4 *> (Join-Path $taskEvidence 'baseline.log')
    $taskExit = $LASTEXITCODE
    "BASELINE_GRADLE_EXIT=$taskExit" | Tee-Object -FilePath (Join-Path $taskEvidence 'baseline-exit.txt')
    exit $taskExit
} finally { Pop-Location; $env:PATH = $taskPath }
