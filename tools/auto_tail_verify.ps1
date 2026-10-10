param([string]$Name, [string[]]$Tasks)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$evidence = 'D:\kimi\workspace\research\tasks\auto-tail-evidence'
New-Item -ItemType Directory -Force $evidence | Out-Null
$env:PATH = 'C:\Program Files\Git\bin;C:\Program Files\Git\mingw64\bin;' + $env:PATH
Push-Location $root
try {
    & .\gradlew.bat @Tasks --no-daemon *> (Join-Path $evidence "$Name.log")
    $code = $LASTEXITCODE
    Set-Content (Join-Path $evidence "$Name.exit.txt") "GRADLE_EXIT=$code"
    $xml = Join-Path $evidence "$Name-xml"
    New-Item -ItemType Directory -Force $xml | Out-Null
    Get-ChildItem agent,app -Recurse -Filter 'TEST-*.xml' | Where-Object { $_.FullName -match 'test-results' } | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $xml ($_.FullName.Substring($root.Length + 1) -replace '[\\/:]', '_'))
    }
    Write-Output "${Name}: GRADLE_EXIT=$code"
    exit $code
} finally { Pop-Location }
