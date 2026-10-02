param([string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$env:PATH = 'C:\Program Files\Git\bin;C:\Program Files\Git\usr\bin;' + $env:PATH
$sourceRoot = Join-Path $Root 'agent/ext/src/main/kotlin/dev/min/agent/ext/hooks'
$evidenceRoot = Join-Path $Root 'agent/ext/build/h1-mutations'
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
$cases = @(
    @{ id='M01'; file='HookSet.kt'; before='ConfigSource.Managed -> 3'; after='ConfigSource.Managed -> -1'; test='HookSetTest' },
    @{ id='M02'; file='HookSet.kt'; before='Regex(matcher).containsMatchIn(text)'; after='Regex(matcher).matches(text)'; test='HookSetTest' },
    @{ id='M03'; file='HookInterpret.kt'; before='if (run.exitCode == 2 && event in EXIT_TWO_BLOCKS)'; after='if (run.exitCode == 222 && event in EXIT_TWO_BLOCKS)'; test='HookInterpretTest' },
    @{ id='M04'; file='HookInterpret.kt'; before='decision = if (event == HookEvent.PreToolUse) HookDecision.Ask else HookDecision.Neutral'; after='decision = if (event == HookEvent.PreToolUse) HookDecision.Allow else HookDecision.Neutral'; test='HookInterpretTest' },
    @{ id='M05'; file='HookInterpret.kt'; before='if (jsonCandidate) {'; after='if (jsonCandidate && run.exitCode == 0) {'; test='HookInterpretTest' },
    @{ id='M06'; file='HookVerdict.kt'; before='val conflict = inputs.size > 1'; after='val conflict = inputs.size > 99'; test='HookCombineTest' },
    @{ id='M07'; file='HookVerdict.kt'; before='shouldContinue = verdicts.all { it.shouldContinue }'; after='shouldContinue = verdicts.any { it.shouldContinue }'; test='HookCombineTest' },
    @{ id='M08'; file='HookBatchRunner.kt'; before='timedOut = code == 124'; after='timedOut = code == 125'; test='HookBatchRunnerTest' },
    @{ id='M09'; file='HookBatchRunner.kt'; before='argv.joinToString(" ", transform = ::quote)'; after='argv.joinToString(" ", transform = { it })'; test='HookBatchRunnerTest' },
    @{ id='M10'; file='HookVerdict.kt'; before='text.length > 10_000'; after='text.length > 20_000'; test='HookInterpretTest' }
)
$results = [System.Collections.Generic.List[object]]::new()
Push-Location -LiteralPath $Root
try {
    foreach ($case in $cases) {
        $path = Join-Path $sourceRoot $case.file
        $original = [System.IO.File]::ReadAllBytes($path)
        $text = $utf8.GetString($original)
        if (($text.Split([string[]]@($case.before), [System.StringSplitOptions]::None).Length - 1) -ne 1) {
            throw "$($case.id): mutation anchor must occur exactly once"
        }
        $xmlDir = Join-Path $Root 'agent/ext/build/test-results/test'
        $caseRoot = Join-Path $evidenceRoot $case.id
        New-Item -ItemType Directory -Force -Path $caseRoot | Out-Null
        try {
            [System.IO.File]::WriteAllText($path, $text.Replace($case.before, $case.after), $utf8)
            $targetXml = Join-Path $xmlDir "TEST-dev.min.agent.ext.hooks.$($case.test).xml"
            if (Test-Path -LiteralPath $targetXml) { Remove-Item -LiteralPath $targetXml }
            Write-Output "$($case.id) running: $($case.before) -> $($case.after)"
            & (Join-Path $Root 'gradlew.bat') ':agent:ext:test' '--tests' "*.$($case.test)" '--no-daemon' *> (Join-Path $caseRoot 'gradle.log')
            $gradleExit = $LASTEXITCODE
            $failed = @()
            $tests = 0
            Get-ChildItem -LiteralPath $xmlDir -Filter '*.xml' | ForEach-Object {
                [xml]$xml = Get-Content -LiteralPath $_.FullName -Raw
                if ($xml.testsuite.name -eq "dev.min.agent.ext.hooks.$($case.test)") {
                    $tests += [int]$xml.testsuite.tests
                    $failed += @($xml.testsuite.testcase | Where-Object { $_.failure } | ForEach-Object { $_.name })
                    Copy-Item -LiteralPath $_.FullName -Destination $caseRoot
                }
            }
            $result = [ordered]@{ id=$case.id; file=$case.file; before=$case.before; after=$case.after;
                gradleExit=$gradleExit; tests=$tests; failedTests=$failed; killed=($gradleExit -ne 0 -and $failed.Count -gt 0) }
            $results.Add($result)
            $results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'results.json') -Encoding utf8
            Write-Output "$($case.id) killed=$($result.killed); red tests=$($failed.Count)"
            if (!$result.killed) { throw "$($case.id): no runtime assertion failure; compilation failure does not count" }
        } finally { [System.IO.File]::WriteAllBytes($path, $original) }
    }
} finally { Pop-Location }
