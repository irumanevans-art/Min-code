param([string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$env:PATH = 'C:\Program Files\Git\bin;C:\Program Files\Git\usr\bin;' + $env:PATH
$kernelRoot = Join-Path $Root 'agent/kernel/src/main/kotlin/dev/min/agent/kernel'
$evidenceRoot = Join-Path $Root 'agent/cli/build/compact-hooks-mutations'
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
# target：跑哪个模块的哪个测试类（变异在哪条链路上被杀死）
$cases = @(
    @{ id='M01'; file='Compactor.kt'; before='hooks.run(HookEvent.PreCompact, kind, specific, turnId)'; after='hooks.run(HookEvent.PreCompact, "manual", specific, turnId)'; project='cli'; test='CompactHooksSessionTest' },
    @{ id='M02'; file='Compactor.kt'; before='return if (outcome.decision == HookOutcome.Decision.Block) CompactResult.Blocked(outcome.reason) else null'; after='return if (outcome.decision != HookOutcome.Decision.Block) CompactResult.Blocked(outcome.reason) else null'; project='cli'; test='CompactHooksSessionTest' },
    @{ id='M03'; file='Compactor.kt'; before='preCompactBlocked(turnId, trigger, instructions)?.let { return it }'; after='preCompactBlocked(turnId, trigger, instructions)?.let { return CompactResult.Failed("拦下") }'; project='cli'; test='CompactHooksSessionTest' },
    @{ id='M04'; file='Compactor.kt'; before='if (autoBlockNoted == turnId) return'; after='if (false) return'; project='cli'; test='CompactHooksSessionTest' },
    @{ id='M05'; file='TurnRunner.kt'; before='                        CompactResult.Done -> Unit'; after='                        CompactResult.Done -> throw TurnAbort("压缩成功后不应继续")'; project='kernel'; test='CompactionTest' },
    @{ id='M06'; file='Compactor.kt'; before='HookContextAdded(HookContextTarget.Compaction(compactionId), HookEvent.SessionStart.name, it)'; after='HookContextAdded(HookContextTarget.NextUserMessage, HookEvent.SessionStart.name, it)'; project='cli'; test='CompactHooksSessionTest' },
    @{ id='M07'; file='Compactor.kt'; before='PostCompactInput(trigger = kind, compactSummary = summary)'; after='PostCompactInput(trigger = kind, compactSummary = null)'; project='cli'; test='CompactHooksSessionTest' },
    @{ id='M08'; file='ModelView.kt'; before='add(ModelMessage(Role.User, listOf(TextPart(c.summary)) + reminders))'; after='add(ModelMessage(Role.User, listOf(TextPart(c.summary))))'; project='cli'; test='CompactHooksSessionTest' },
    @{ id='M09'; file='Fold.kt'; before='compactionContexts = emptyMap(),'; after='compactionContexts = compactionContexts,'; project='cli'; test='CompactHooksSessionTest' },
    @{ id='M10'; file='Delegation.kt'; before='?.takeIf { it >= 0 }?.plus(1)'; after='?.takeIf { false }'; project='cli'; test='CompactHooksSessionTest' },
    @{ id='M11'; file='Delegation.kt'; before='return ForkSeeded(messages, results, seed)'; after='return ForkSeeded(messages, results, null)'; project='cli'; test='CompactHooksSessionTest' },
    @{ id='M12'; file='Fold.kt'; before='(target.compactionId to compactionContexts[target.compactionId].orEmpty() + e.text),'; after='(target.compactionId to compactionContexts[target.compactionId].orEmpty() + ""),'; project='cli'; test='CompactHooksSessionTest' }
)
$results = [System.Collections.Generic.List[object]]::new()
Push-Location -LiteralPath $Root
try {
    foreach ($case in $cases) {
        $path = Join-Path $kernelRoot $case.file
        $original = [System.IO.File]::ReadAllBytes($path)
        $text = $utf8.GetString($original)
        if (($text.Split([string[]]@($case.before), [System.StringSplitOptions]::None).Length - 1) -ne 1) {
            throw "$($case.id): mutation anchor must occur exactly once"
        }
        $xmlDir = Join-Path $Root "agent/$($case.project)/build/test-results/test"
        $caseRoot = Join-Path $evidenceRoot $case.id
        New-Item -ItemType Directory -Force -Path $caseRoot | Out-Null
        try {
            [System.IO.File]::WriteAllText($path, $text.Replace($case.before, $case.after), $utf8)
            $targetXml = Join-Path $xmlDir "TEST-dev.min.agent.$($case.project).$($case.test).xml"
            if (Test-Path -LiteralPath $targetXml) { Remove-Item -LiteralPath $targetXml }
            Write-Output "$($case.id) running: $($case.before) -> $($case.after)"
            $gradleHome = Join-Path $Root 'gradlew.bat'
            & cmd /c "`"$gradleHome`" :agent:$($case.project):test --tests `"*.$($case.test)`" --no-daemon > `"$caseRoot\gradle.log`" 2>&1"
            $gradleExit = $LASTEXITCODE
            $failed = @()
            $tests = 0
            Get-ChildItem -LiteralPath $xmlDir -Filter '*.xml' | ForEach-Object {
                [xml]$xml = Get-Content -LiteralPath $_.FullName -Raw
                if ($xml.testsuite.name -eq "dev.min.agent.$($case.project).$($case.test)") {
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
