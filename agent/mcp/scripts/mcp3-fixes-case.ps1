param([Parameter(Mandatory)][string]$Name,[Parameter(Mandatory)][string]$Module,[Parameter(Mandatory)][string]$Test)
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$dest=Join-Path $repo "agent/mcp/build/review/fixes/$Name"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$task=if($Module -eq 'app'){':app:testDebugUnitTest'}else{":agent:${Module}:test"}
$xml=if($Module -eq 'app'){'app/build/test-results/testDebugUnitTest'}else{"agent/$Module/build/test-results/test"}
Push-Location $repo
try {
    & ./gradlew.bat $task --tests $Test --console=plain --max-workers=2 *> (Join-Path $dest 'gradle.log')
    $code=$LASTEXITCODE
    Add-Content -LiteralPath (Join-Path $dest 'gradle.log') "GRADLE_EXIT=$code"
    Get-ChildItem -Path $xml -Filter TEST-*.xml | Copy-Item -Destination $dest
    $failures=@()
    foreach($file in Get-ChildItem $dest -Filter TEST-*.xml) {
        [xml]$doc=Get-Content -LiteralPath $file.FullName -Raw
        foreach($case in $doc.testsuite.testcase) { if($case.failure){ $failures+="$($case.classname): $($case.name)" } }
    }
    @{exit=$code;failures=$failures} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $dest 'result.json')
    Write-Output "$Name GRADLE_EXIT=$code"
    $failures | Write-Output
    if($code -ne 0 -and $failures.Count -eq 0){Get-Content (Join-Path $dest 'gradle.log') -Tail 25}
} finally { Pop-Location }
exit $code
