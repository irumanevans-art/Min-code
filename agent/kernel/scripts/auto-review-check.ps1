param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
[System.IO.Directory]::CreateDirectory($evidence) | Out-Null
$oldPath = $env:PATH
$modules = @('protocol','spi','mcp','store','llm','policy','context','tools','ext','host','kernel','testkit','cli')
Push-Location $root
try {
    if ($env:OS -eq 'Windows_NT' -and -not (Get-Command bash -ErrorAction SilentlyContinue)) {
        $gitBin = Join-Path $env:ProgramFiles 'Git/bin'
        if (-not (Test-Path -LiteralPath (Join-Path $gitBin 'bash.exe'))) { throw '验证真实 shell 用例需要 Git Bash' }
        $env:PATH = $gitBin + ';' + $oldPath
    }
    $clean = $modules | ForEach-Object { ':agent:' + $_ + ':cleanTest' }
    & .\gradlew.bat @clean '--no-daemon' '--console=plain' '--max-workers=2' *> (Join-Path $evidence 'clean.log')
    $cleanExit = $LASTEXITCODE
    Write-Output "CLEAN_GRADLE_EXIT=$cleanExit"
    if ($cleanExit -ne 0) { exit $cleanExit }
    $check = $modules | ForEach-Object { ':agent:' + $_ + ':check' }
    & .\gradlew.bat @check '--no-daemon' '--console=plain' '--max-workers=2' *> (Join-Path $evidence 'check.log')
    $gradleExit = $LASTEXITCODE
    [System.IO.File]::WriteAllText((Join-Path $evidence 'exit.txt'), "CLEAN_GRADLE_EXIT=$cleanExit`nGRADLE_EXIT=$gradleExit`n")
    Write-Output "GRADLE_EXIT=$gradleExit"
    foreach ($module in $modules) {
        $results = Join-Path $root "agent/$module/build/test-results/test"
        if (Test-Path -LiteralPath $results) {
            $dest = Join-Path $evidence "xml/$module"
            [System.IO.Directory]::CreateDirectory($dest) | Out-Null
            Get-ChildItem -LiteralPath $results -Filter '*.xml' -File | ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination $dest
            }
        }
    }
    exit $gradleExit
} finally {
    $env:PATH = $oldPath
    Pop-Location
}
