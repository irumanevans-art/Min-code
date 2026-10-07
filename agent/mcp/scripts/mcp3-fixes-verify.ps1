$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$manifest=Get-Content (Join-Path $PSScriptRoot 'mcp3-fixes-mutations.json') -Raw | ConvertFrom-Json
$evidence=Join-Path $repo 'agent/mcp/build/review/fixes'
$verified=@()
foreach($m in $manifest) {
    $dest=Join-Path $evidence "mutations/$($m.Item)/$($m.Name)"
    $result=Get-Content (Join-Path $dest 'result.json') -Raw | ConvertFrom-Json
    $log=Get-Content (Join-Path $dest 'gradle.log') -Raw
    $matchedCases=@()
    foreach($file in Get-ChildItem $dest -Filter TEST-*.xml) {
        [xml]$doc=Get-Content -LiteralPath $file.FullName -Raw
        foreach($case in $doc.testsuite.testcase) {
            if($case.failure -and $case.classname -like $m.Test -and $case.name -match $m.Assertion -and $case.name -ne 'initializationError') {
                $matchedCases+="$($case.classname): $($case.name)"
            }
        }
    }
    $task=if($m.Module -eq 'app'){':app:testDebugUnitTest'}else{":agent:$($m.Module):test"}
    if($result.exit -eq 0 -or $matchedCases.Count -eq 0 -or !$log.Contains("> Task $task FAILED") -or $log -notmatch "GRADLE_EXIT=$($result.exit)" -or $log -match 'Compilation error|No tests found|compile\w*Kotlin FAILED') {
        throw "无效变异：$($m.Name)"
    }
    $source=Get-Content (Join-Path $repo $m.File) -Raw
    if(!$source.Replace("`r`n","`n").Contains($m.From)){throw "源文件未恢复：$($m.Name)"}
    $verified+=@{item=$m.Item;name=$m.Name;exit=$result.exit;assertions=$matchedCases;verified=$true}
}
$verified | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $evidence 'mutations/verified-results.json')
$leaks=@(Get-ChildItem $evidence -Recurse -File | Where-Object {$_.Extension -in '.log','.xml'} | Where-Object {(Get-Content -LiteralPath $_.FullName -Raw) -match 'FAKE_ACCESS|FAKE_REFRESH|FAKE_SECRET|FAKE_CODE|OLD_REFRESH'})
if($leaks.Count -gt 0){throw "测试日志或 XML 出现假凭证：$($leaks.Count) 个文件"}
Write-Output "VERIFIED_MUTATIONS=$($verified.Count) CREDENTIAL_LEAK_FILES=0"
