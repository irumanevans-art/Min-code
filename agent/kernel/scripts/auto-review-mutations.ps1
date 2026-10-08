param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
[System.IO.Directory]::CreateDirectory($evidence) | Out-Null
$cases = @(
    @{ Name='floor'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/AutoReviewer.kt';
       Before='alwaysAsk || PolicyEngine.floorReason(ask, context) != null'; After='alwaysAsk';
       Task=':agent:kernel:test'; Filter='dev.min.agent.kernel.AutoReviewerTest.floor cannot be approved by reviewer';
       Xml='agent/kernel/build/test-results/test/TEST-dev.min.agent.kernel.AutoReviewerTest.xml' },
    @{ Name='project-config'; File='agent/ext/src/main/kotlin/dev/min/agent/ext/config/AutoModeSettings.kt';
       Before='layers.filter { it.source == ConfigSource.User || it.source == ConfigSource.Managed }'; After='layers.filter { true }';
       Task=':agent:ext:test'; Filter='dev.min.agent.ext.config.AutoModeSettingsTest.project and local cannot configure auto mode or its reviewer';
       Xml='agent/ext/build/test-results/test/TEST-dev.min.agent.ext.config.AutoModeSettingsTest.xml' },
    @{ Name='failure-allows'; File='agent/kernel/src/main/kotlin/dev/min/agent/kernel/AutoReviewer.kt';
       Before='AutoReviewDecision.Ask to "审查失败、超时或返回不可解析，交给用户决定"'; After='AutoReviewDecision.Allow to "审查失败、超时或返回不可解析，交给用户决定"';
       Task=':agent:kernel:test'; Filter='dev.min.agent.kernel.AutoReviewerTest.review failure goes to human';
       Xml='agent/kernel/build/test-results/test/TEST-dev.min.agent.kernel.AutoReviewerTest.xml' }
)
$summary = @()
Push-Location $root
try {
    foreach ($case in $cases) {
        $file = Join-Path $root $case.File
        $original = [System.IO.File]::ReadAllBytes($file)
        $text = [System.Text.Encoding]::UTF8.GetString($original)
        if (-not $text.Contains($case.Before)) { throw "Missing mutation anchor: $($case.Name)" }
        $log = Join-Path $evidence ($case.Name + '.log')
        try {
            [System.IO.File]::WriteAllText($file, $text.Replace($case.Before, $case.After), [System.Text.UTF8Encoding]::new($false))
            & .\gradlew.bat $case.Task '--tests' $case.Filter '--console=plain' *> $log
            $gradleExit = $LASTEXITCODE
            $xmlPath = Join-Path $root $case.Xml
            $compiled = -not (Select-String -LiteralPath $log -Pattern 'compileKotlin FAILED|compileTestKotlin FAILED|Compilation error' -Quiet)
            $failed = $false
            if (Test-Path -LiteralPath $xmlPath) {
                [xml]$xml = Get-Content -LiteralPath $xmlPath -Raw
                $expected = $case.Filter.Substring($case.Filter.LastIndexOf('.') + 1)
                $failed = @($xml.testsuite.testcase | Where-Object { $_.name -eq $expected -and $_.failure }).Count -gt 0
                Copy-Item -LiteralPath $xmlPath -Destination (Join-Path $evidence ($case.Name + '.xml'))
            }
            $killed = $gradleExit -ne 0 -and $compiled -and $failed
            $summary += "$($case.Name): GRADLE_EXIT=$gradleExit COMPILED=$compiled ASSERTION_FAILED=$failed KILLED=$killed"
            Write-Output $summary[-1]
            if (-not $killed) { throw "Mutation survived or failed to compile: $($case.Name)" }
        } finally {
            [System.IO.File]::WriteAllBytes($file, $original)
        }
    }
} finally {
    Pop-Location
    [System.IO.File]::WriteAllLines((Join-Path $evidence 'summary.txt'), $summary, [System.Text.UTF8Encoding]::new($false))
}
exit 0
