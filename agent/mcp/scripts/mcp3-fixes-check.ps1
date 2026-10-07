$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$dest=Join-Path $repo 'agent/mcp/build/review/fixes/final-check'
New-Item -ItemType Directory -Force $dest | Out-Null
$modules=@('protocol','spi','mcp','store','llm','policy','context','tools','ext','host','kernel','testkit','cli')
$tasks=@($modules | ForEach-Object {":agent:${_}:check"})+@(':app:testDebugUnitTest',':app:assembleDebug')
Push-Location $repo
try {
    $ErrorActionPreference='Continue'
    & ./gradlew.bat @tasks --console=plain --max-workers=2 *> (Join-Path $dest 'gradle.log')
    $code=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    Add-Content -LiteralPath (Join-Path $dest 'gradle.log') "GRADLE_EXIT=$code"
    $summary=@()
    foreach($module in ($modules+@('app'))) {
        $xml=if($module -eq 'app'){'app/build/test-results/testDebugUnitTest'}else{"agent/$module/build/test-results/test"}
        $out=Join-Path $dest "xml/$module"
        New-Item -ItemType Directory -Force $out | Out-Null
        $tests=0;$failures=0;$skipped=0
        foreach($file in Get-ChildItem -LiteralPath $xml -Filter TEST-*.xml -ErrorAction SilentlyContinue) {
            Copy-Item -LiteralPath $file.FullName -Destination $out
            [xml]$doc=Get-Content -LiteralPath $file.FullName -Raw
            $tests+=[int]$doc.testsuite.tests;$failures+=[int]$doc.testsuite.failures;$skipped+=[int]$doc.testsuite.skipped
        }
        $summary+=@{module=$module;tests=$tests;failures=$failures;skipped=$skipped}
    }
    @{exit=$code;modules=$summary} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $dest 'result.json')
    Write-Output "FINAL GRADLE_EXIT=$code"
    $summary | ForEach-Object {Write-Output "$($_.module): tests=$($_.tests) failures=$($_.failures) skipped=$($_.skipped)"}
    Get-Content (Join-Path $dest 'gradle.log') -Tail 24
} finally {Pop-Location}
exit $code
