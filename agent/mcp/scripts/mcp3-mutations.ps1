param([string]$Item = '*')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidence = Join-Path $repo 'agent/mcp/build/review/mcp3/mutations'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$mutations = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'mcp3-mutations.json') -Raw | ConvertFrom-Json
$selected = @($mutations | Where-Object { $_.Item -like $Item })
$resultName = if ($Item -eq '*') { 'all' } else { $Item }
if ($selected.Count -eq 0) { throw '没有匹配的变异组' }
$results = @()
foreach ($m in $selected) {
    $source = Join-Path $repo $m.File
    $original = [IO.File]::ReadAllBytes($source)
    $text = [Text.Encoding]::UTF8.GetString($original).Replace("`r`n", "`n")
    if (-not $text.Contains($m.From)) { throw "变异锚点不存在：$($m.Item)/$($m.Name)" }
    $dest = Join-Path $evidence "$($m.Item)/$($m.Name)"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    $task = if ($m.Module -eq 'app') { ':app:testDebugUnitTest' } else { ":agent:$($m.Module):test" }
    $xmlPath = if ($m.Module -eq 'app') { 'app/build/test-results/testDebugUnitTest' } else { "agent/$($m.Module)/build/test-results/test" }
    try {
        [IO.File]::WriteAllText($source, $text.Replace($m.From, $m.To), [Text.UTF8Encoding]::new($false))
        Push-Location $repo
        try {
            $ErrorActionPreference = 'Continue'
            & ./gradlew.bat $task --tests $m.Test --console=plain --max-workers=2 *> (Join-Path $dest 'gradle.log')
            $code = $LASTEXITCODE
            $ErrorActionPreference = 'Stop'
        } finally { Pop-Location }
        $failed = @()
        Get-ChildItem (Join-Path $repo $xmlPath) -Filter 'TEST-*.xml' | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $dest
            [xml]$xml = Get-Content -LiteralPath $_.FullName -Raw
            foreach ($case in $xml.testsuite.testcase) {
                if ($case.failure -and $case.classname -like $m.Test -and $case.name -ne 'initializationError') { $failed += "$($case.classname): $($case.name)" }
            }
        }
        $log = Get-Content -LiteralPath (Join-Path $dest 'gradle.log') -Raw
        $executed = $log.Contains("> Task $task FAILED")
        $valid = $code -ne 0 -and $executed -and $failed.Count -gt 0 -and $log -notmatch 'Compilation error|No tests found|compileTestKotlin FAILED|compileKotlin FAILED|compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED'
        $credentialPattern = 'FAKE_ACCESS|FAKE_REFRESH|FAKE_SECRET|FAKE_CODE|OLD_REFRESH'
        $leakingXml = @(Get-ChildItem -LiteralPath $dest -Filter 'TEST-*.xml' | Where-Object { [IO.File]::ReadAllText($_.FullName) -match $credentialPattern })
        if ($log -match $credentialPattern -or $leakingXml.Count -gt 0) { throw "变异证据暴露了假凭证，请修正断言：$($m.Item)/$($m.Name)" }
        $result = @{ item=$m.Item; mutation=$m.Name; killed=$valid; exit=$code; failures=$failed }
        $results += $result
        $result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $dest 'result.json')
        $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence "results-$resultName.json")
        Write-Output "$($m.Item)/$($m.Name): $(if ($valid) { 'KILLED' } else { 'INVALID_OR_SURVIVED' })"
    } finally { [IO.File]::WriteAllBytes($source, $original) }
}
$invalid = @($results | Where-Object { -not $_.killed })
Write-Output "有效检出 $($results.Count - $invalid.Count)/$($results.Count)"
if ($invalid.Count -gt 0) { exit 1 }
exit 0
