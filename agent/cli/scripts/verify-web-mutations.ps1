param([string]$Workspace = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path)

$ErrorActionPreference = 'Stop'
$env:PATH = 'C:\Program Files\Git\bin;C:\Program Files\Git\usr\bin;' + $env:PATH
$taskEvidence = Join-Path $Workspace 'agent/cli/build/web-mutations'
New-Item -ItemType Directory -Path $taskEvidence -Force | Out-Null
$taskUtf8 = New-Object System.Text.UTF8Encoding($false)
$taskMutations = @(
    @{ id='M01'; module='tools'; tests='dev.min.agent.tools.WebToolsTest'; file='spi/src/main/kotlin/dev/min/agent/spi/WebUrl.kt'; old="&& '.' in host"; new='&& true' },
    @{ id='M02'; module='tools'; tests='dev.min.agent.tools.WebToolsTest'; file='spi/src/main/kotlin/dev/min/agent/spi/WebUrl.kt'; old='return "https:" + value'; new='return uri.scheme + ":" + value' },
    @{ id='M03'; module='tools'; tests='dev.min.agent.tools.WebToolsTest'; file='tools/src/main/kotlin/dev/min/agent/tools/WebFetchTool.kt'; old='ctx.extract(full.take(EXTRACTION_MAX_CHARS), input.prompt)'; new='ctx.extract(full, input.prompt)' },
    @{ id='M04'; module='tools'; tests='dev.min.agent.tools.WebToolsTest'; file='tools/src/main/kotlin/dev/min/agent/tools/WebFetchTool.kt'; old='if (extracted.isError) extracted.copy('; new='if (false) extracted.copy(' },
    @{ id='M05'; module='tools'; tests='dev.min.agent.tools.WebToolsTest'; file='tools/src/main/kotlin/dev/min/agent/tools/WebSearchTool.kt'; old='if (raw.containsKey("allowed_domains") && raw.containsKey("blocked_domains"))'; new='if (false)' },
    @{ id='M06'; module='host'; tests='dev.min.agent.host.CachedWebPortTest'; file='host/src/main/kotlin/dev/min/agent/host/CachedWebPort.kt'; old='if (WebUrl.host(target) != WebUrl.host(url))'; new='if (WebUrl.host(target) == WebUrl.host(url))' },
    @{ id='M07'; module='host'; tests='dev.min.agent.host.CachedWebPortTest'; file='host/src/main/kotlin/dev/min/agent/host/CachedWebPort.kt'; old='0 until cacheTtlMs'; new='0..cacheTtlMs' },
    @{ id='M08'; module='kernel'; tests='dev.min.agent.kernel.WebServicesTest'; file='kernel/src/main/kotlin/dev/min/agent/kernel/Fold.kt'; old='is dev.min.agent.spi.WebExtractionCompleted -> updateTurn(e.turnId) { it.copy(usage = it.usage + e.usage) }'; new='is dev.min.agent.spi.WebExtractionCompleted -> updateTurn(e.turnId) { it.copy(usage = it.usage) }' },
    @{ id='M09'; module='kernel'; tests='dev.min.agent.kernel.WebServicesTest'; file='kernel/src/main/kotlin/dev/min/agent/kernel/SessionHub.kt'; old='caller.state.value.origin?.root ?: caller.sessionId'; new='caller.sessionId' },
    @{ id='M10'; module='kernel'; tests='dev.min.agent.kernel.WebServicesTest'; file='kernel/src/main/kotlin/dev/min/agent/kernel/SessionHub.kt'; old='it.webSearches < deps.maxWebSearches'; new='it.webSearches <= deps.maxWebSearches' }
)
$taskResults = @()
Push-Location $Workspace
try {
    foreach ($mutation in $taskMutations) {
        $taskFile = Join-Path $Workspace ('agent/' + $mutation.file)
        $taskOriginal = [IO.File]::ReadAllBytes($taskFile)
        $taskText = [IO.File]::ReadAllText($taskFile)
        if (-not $taskText.Contains($mutation.old)) { throw "Mutation target missing: $($mutation.id)" }
        $taskLog = Join-Path $taskEvidence "$($mutation.id).log"
        try {
            [IO.File]::WriteAllText($taskFile, $taskText.Replace($mutation.old, $mutation.new), $taskUtf8)
            & .\gradlew.bat ":agent:$($mutation.module):test" --tests $mutation.tests *> $taskLog
            $taskExit = $LASTEXITCODE
            $taskOutput = [IO.File]::ReadAllText($taskLog)
            $taskFailed = @()
            if ($taskOutput.Contains(":agent:$($mutation.module):test FAILED")) {
                $taskXmlPath = Join-Path $Workspace "agent/$($mutation.module)/build/test-results/test/TEST-$($mutation.tests).xml"
                [xml]$taskXml = [IO.File]::ReadAllText($taskXmlPath)
                foreach ($case in $taskXml.testsuite.testcase) {
                    if ($case.failure) { $taskFailed += "$($taskXml.testsuite.name).$($case.name)" }
                }
            }
            $taskHit = $taskExit -ne 0 -and $taskFailed.Count -gt 0 -and $taskOutput -notmatch ':agent:.*:compile(Kotlin|TestKotlin) FAILED'
            $taskResults += [PSCustomObject]@{ id=$mutation.id; file=$mutation.file; old=$mutation.old; new=$mutation.new; hit=$taskHit; failedTests=$taskFailed }
            Write-Output "$($mutation.id): hit=$taskHit; failing JUnit cases=$($taskFailed.Count)"
            if (-not $taskHit) { throw "Mutation did not produce a JUnit failure: $($mutation.id)" }
        } finally {
            [IO.File]::WriteAllBytes($taskFile, $taskOriginal)
            $taskResults | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskEvidence 'results.json') -Encoding utf8
        }
    }
} finally {
    Pop-Location
}
