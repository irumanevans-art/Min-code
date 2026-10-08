param([string]$Evidence = 'agent/ext/build/review/plugin-loader/full-check')
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$taskEvidence = [IO.Path]::GetFullPath((Join-Path $taskRoot $Evidence))
New-Item -ItemType Directory -Force -Path $taskEvidence | Out-Null
$taskPath = $env:PATH
try {
    if (Test-Path 'C:\Program Files\Git\bin\bash.exe') {
        $env:PATH = 'C:\Program Files\Git\bin;C:\Program Files\Git\usr\bin;' + $taskPath
    }
    Push-Location $taskRoot
    $modules = @('protocol','spi','mcp','store','llm','policy','context','tools','ext','host','kernel','testkit','cli')
    $tasks = @($modules | ForEach-Object { ":agent:${_}:cleanTest" }) + @(':app:cleanTestDebugUnitTest') +
        @($modules | ForEach-Object { ":agent:${_}:check" }) + @(':app:testDebugUnitTest', ':app:assembleDebug')
    & ./gradlew.bat @tasks --console=plain --max-workers=4 *> (Join-Path $taskEvidence 'gradle.log')
    $taskExit = $LASTEXITCODE
    "GRADLE_EXIT=$taskExit" | Tee-Object -FilePath (Join-Path $taskEvidence 'exit.txt')
    $counts = @()
    foreach ($module in ($modules + @('app'))) {
        $source = if ($module -eq 'app') { 'app/build/test-results/testDebugUnitTest' } else { "agent/$module/build/test-results/test" }
        $target = Join-Path $taskEvidence $module
        New-Item -ItemType Directory -Force -Path $target | Out-Null
        $tests = 0; $failures = 0; $errors = 0; $skipped = 0
        foreach ($file in @(Get-ChildItem $source -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue)) {
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
            $tests += [int]$xml.testsuite.tests
            $failures += [int]$xml.testsuite.failures
            $errors += [int]$xml.testsuite.errors
            $skipped += [int]$xml.testsuite.skipped
            Copy-Item -LiteralPath $file.FullName -Destination $target
        }
        $counts += [pscustomobject]@{module=$module; tests=$tests; failures=$failures; errors=$errors; skipped=$skipped}
    }
    $counts | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskEvidence 'test-results.json') -Encoding utf8
    Get-Content (Join-Path $taskEvidence 'gradle.log') -Tail 55
    exit $taskExit
} finally { Pop-Location; $env:PATH = $taskPath }
