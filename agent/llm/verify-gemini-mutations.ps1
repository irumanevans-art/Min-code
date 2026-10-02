param([string]$Workspace = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path)

$ErrorActionPreference = 'Stop'
$taskSource = Join-Path $Workspace 'agent/llm/src/main/kotlin/dev/min/agent/llm/gemini'
$taskEvidence = Join-Path $Workspace 'agent/llm/build/gemini-mutations'
New-Item -ItemType Directory -Path $taskEvidence -Force | Out-Null
$taskUtf8 = New-Object System.Text.UTF8Encoding($false)
$taskMutations = @(
    @{ id='M01'; file='GeminiStream.kt'; old='(prompt - cached).coerceAtLeast(0)'; new='(prompt + cached).coerceAtLeast(0)' },
    @{ id='M02'; file='GeminiStream.kt'; old='candidates + thoughts, cached, 0'; new='candidates, cached, 0' },
    @{ id='M03'; file='GeminiRequest.kt'; old='results.sortedBy {'; new='results.sortedByDescending {' },
    @{ id='M04'; file='GeminiRequest.kt'; old='|| part.str("thoughtSignature") != null'; new='|| false' },
    @{ id='M05'; file='GeminiSchema.kt'; old='out["type"] = JsonPrimitive(nonNull.single())'; new='out["type"] = JsonPrimitive(nonNull.single().lowercase())' },
    @{ id='M06'; file='GeminiStream.kt'; old='stop, reason == "MAX_TOKENS")'; new='stop, false)' },
    @{ id='M07'; file='GeminiStream.kt'; old='if (stopRaw == null && blocked == null) throw ModelException('; new='if (false) throw ModelException(' },
    @{ id='M08'; file='GeminiErrors.kt'; old='code == 503 || code == 529 -> ModelFailureKind.Overloaded'; new='code == 503 || code == 529 -> ModelFailureKind.Server' },
    @{ id='M09'; file='GeminiRequest.kt'; old='else -> "ANY"'; new='else -> "AUTO"' },
    @{ id='M10'; file='GeminiStream.kt'; old='val ordinal = callIds.size'; new='val ordinal = 0' }
)
$taskResults = @()
Push-Location $Workspace
try {
    foreach ($mutation in $taskMutations) {
        $taskFile = Join-Path $taskSource $mutation.file
        $taskOriginal = [IO.File]::ReadAllText($taskFile)
        if (-not $taskOriginal.Contains($mutation.old)) { throw "Mutation target missing: $($mutation.id)" }
        $taskLog = Join-Path $taskEvidence "$($mutation.id).log"
        try {
            [IO.File]::WriteAllText($taskFile, $taskOriginal.Replace($mutation.old, $mutation.new), $taskUtf8)
            & .\gradlew.bat :agent:llm:test --tests 'dev.min.agent.llm.gemini.*' *> $taskLog
            $taskExit = $LASTEXITCODE
            $taskOutput = [IO.File]::ReadAllText($taskLog)
            $taskFailed = @()
            if ($taskOutput -match ':agent:llm:test FAILED') {
                Get-ChildItem (Join-Path $Workspace 'agent/llm/build/test-results/test') -Filter 'TEST-*Gemini*.xml' | ForEach-Object {
                    [xml]$taskXml = [IO.File]::ReadAllText($_.FullName)
                    foreach ($case in $taskXml.testsuite.testcase) {
                        if ($case.failure) { $taskFailed += "$($taskXml.testsuite.name).$($case.name)" }
                    }
                }
            }
            $taskHit = $taskExit -ne 0 -and $taskFailed.Count -gt 0 -and $taskOutput -notmatch ':agent:llm:compile(Kotlin|TestKotlin) FAILED'
            $taskResults += [PSCustomObject]@{ id=$mutation.id; file=$mutation.file; old=$mutation.old; new=$mutation.new; hit=$taskHit; exit=$taskExit; failedTests=$taskFailed }
            Write-Output "$($mutation.id): hit=$taskHit; failing JUnit cases=$($taskFailed.Count)"
            if (-not $taskHit) { throw "Mutation did not produce a JUnit failure: $($mutation.id)" }
        } finally {
            [IO.File]::WriteAllText($taskFile, $taskOriginal, $taskUtf8)
            $taskResults | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskEvidence 'results.json') -Encoding utf8
        }
    }
} finally {
    Pop-Location
}
