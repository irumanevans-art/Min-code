param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
[System.IO.Directory]::CreateDirectory($evidence) | Out-Null
$env:PATH = 'C:\Program Files\Git\bin;' + $env:PATH
$modules = @('protocol', 'spi', 'mcp', 'store', 'llm', 'policy', 'context', 'tools', 'ext', 'host', 'kernel', 'testkit', 'cli')
Push-Location $root
try {
    $clean = @($modules | ForEach-Object { ":agent:${_}:cleanTest" }) + ':app:cleanTestDebugUnitTest'
    & .\gradlew.bat @clean '--console=plain' '--max-workers=2' '--no-build-cache' *> (Join-Path $evidence 'clean.log')
    $cleanExit = $LASTEXITCODE
    Add-Content (Join-Path $evidence 'clean.log') "GRADLE_EXIT=$cleanExit"
    Write-Output "CLEAN_GRADLE_EXIT=$cleanExit"
    if ($cleanExit -ne 0) { exit $cleanExit }
    $checks = @($modules | ForEach-Object { ":agent:${_}:check" }) + ':app:testDebugUnitTest' + ':app:assembleDebug'
    & .\gradlew.bat @checks '--continue' '--console=plain' '--max-workers=2' '--no-build-cache' *> (Join-Path $evidence 'check.log')
    $checkExit = $LASTEXITCODE
    Add-Content (Join-Path $evidence 'check.log') "GRADLE_EXIT=$checkExit"
    Write-Output "CHECK_GRADLE_EXIT=$checkExit"
    if ($checkExit -ne 0) { exit $checkExit }
} finally { Pop-Location }
exit 0

