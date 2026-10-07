param([string]$Item='*')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidence=Join-Path $repo 'agent/mcp/build/review/fixes/mutations'
$items=Get-Content (Join-Path $PSScriptRoot 'mcp3-fixes-mutations.json') -Raw | ConvertFrom-Json
$results=@()
foreach($m in @($items | Where-Object {$_.Item -like $Item})) {
    $source=Join-Path $repo $m.File
    $original=[IO.File]::ReadAllBytes($source)
    $text=[Text.Encoding]::UTF8.GetString($original).Replace("`r`n","`n")
    if(-not $text.Contains($m.From)){throw "变异锚点不存在：$($m.Name)"}
    $name="mutations/$($m.Item)/$($m.Name)"
    try {
        [IO.File]::WriteAllText($source,$text.Replace($m.From,$m.To),[Text.UTF8Encoding]::new($false))
        $ErrorActionPreference='Continue'
        & (Join-Path $PSScriptRoot 'mcp3-fixes-case.ps1') -Name $name -Module $m.Module -Test $m.Test
        $code=$LASTEXITCODE
        $ErrorActionPreference='Stop'
        $dest=Join-Path $repo "agent/mcp/build/review/fixes/$name"
        $result=Get-Content (Join-Path $dest 'result.json') -Raw | ConvertFrom-Json
        $log=Get-Content (Join-Path $dest 'gradle.log') -Raw
        $functional=@($result.failures | Where-Object {$_ -match $m.Assertion -and $_ -notmatch 'initializationError'})
        $task=if($m.Module -eq 'app'){':app:testDebugUnitTest'}else{":agent:$($m.Module):test"}
        $valid=$result.exit -ne 0 -and $functional.Count -gt 0 -and $log.Contains("> Task $task FAILED") -and $log -notmatch 'Compilation error|No tests found|compile\w*Kotlin FAILED'
        $leaks=@(Get-ChildItem $dest -Filter TEST-*.xml | Where-Object {[IO.File]::ReadAllText($_.FullName) -match 'FAKE_ACCESS|FAKE_REFRESH|FAKE_SECRET|FAKE_CODE|OLD_REFRESH'})
        if($log -match 'FAKE_ACCESS|FAKE_REFRESH|FAKE_SECRET|FAKE_CODE|OLD_REFRESH' -or $leaks.Count -gt 0){throw "测试证据含假凭证：$($m.Name)"}
        $row=@{item=$m.Item;name=$m.Name;killed=$valid;exit=$result.exit;failures=$functional}
        $results+=$row
        New-Item -ItemType Directory -Force $evidence | Out-Null
        $results | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $evidence "results-$($Item.Replace('*','all')).json")
        Write-Output "$($m.Name): $(if($valid){'KILLED'}else{'INVALID_OR_SURVIVED'})"
    } finally {[IO.File]::WriteAllBytes($source,$original)}
}
if($results.Count -eq 0 -or @($results | Where-Object {-not $_.killed}).Count -gt 0){exit 1}
exit 0
