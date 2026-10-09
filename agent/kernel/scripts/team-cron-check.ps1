param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
# M5 团队与定时调度的完整验证：13 个 agent 模块先 cleanTest 再 check，然后 App 的 testDebugUnitTest + assembleDebug。
# 每一步的真实 Gradle 退出码写进 exit.txt；有一步非 0 就停。
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
[System.IO.Directory]::CreateDirectory($evidence) | Out-Null
$oldPath = $env:PATH
$modules = @('protocol','spi','mcp','store','llm','policy','context','tools','ext','host','kernel','testkit','cli')
$exits = @()
Push-Location $root
try {
    # cli 的真实 shell 用例要 Git Bash
    if ($env:OS -eq 'Windows_NT' -and -not (Get-Command bash -ErrorAction SilentlyContinue)) {
        $gitBin = Join-Path $env:ProgramFiles 'Git/bin'
        if (-not (Test-Path -LiteralPath (Join-Path $gitBin 'bash.exe'))) { throw '验证真实 shell 用例需要 Git Bash' }
        $env:PATH = $gitBin + ';' + $oldPath
    }
    function Step($name, $tasks, $log) {
        & .\gradlew.bat @tasks '--no-daemon' '--console=plain' '--max-workers=2' *> (Join-Path $evidence $log)
        $code = $LASTEXITCODE
        $script:exits += "${name}_GRADLE_EXIT=$code"
        Write-Output $script:exits[-1]
        [System.IO.File]::WriteAllLines((Join-Path $evidence 'exit.txt'), $script:exits, [System.Text.UTF8Encoding]::new($false))
        if ($code -ne 0) { exit $code }
    }
    Step 'CLEAN' ($modules | ForEach-Object { ':agent:' + $_ + ':cleanTest' }) 'clean.log'
    Step 'AGENT_CHECK' ($modules | ForEach-Object { ':agent:' + $_ + ':check' }) 'agent-check.log'
    Step 'APP_CLEAN' @(':app:cleanTestDebugUnitTest') 'app-clean.log'
    Step 'APP' @(':app:testDebugUnitTest', ':app:assembleDebug') 'app.log'
    exit 0
} finally {
    $env:PATH = $oldPath
    Pop-Location
}
