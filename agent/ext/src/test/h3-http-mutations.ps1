param([string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$env:PATH = 'C:\Program Files\Git\bin;C:\Program Files\Git\usr\bin;' + $env:PATH
$sourceRoot = Join-Path $Root 'agent/ext/src/main/kotlin/dev/min/agent/ext/hooks'
$evidenceRoot = Join-Path $Root 'agent/ext/build/h3-http-mutations'
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
$cases = @(
    @{ id='M01'; file='HookSet.kt'; before='url = text("url")?.takeUnless { it.isBlank() } ?: error("http 处理器缺少 url")'; after='url = text("url") ?: error("http 处理器缺少 url")'; test='HookHttpTest' },
    @{ id='M02'; file='HookSet.kt'; before='if (type == "http") listOf(url, headers, allowedEnvVars) else emptyList()'; after='emptyList<Any?>()'; test='HookHttpTest' },
    @{ id='M03'; file='HookHttp.kt'; before='fun allowsUrl(url: String): Boolean = allowedUrls == null || url in allowedUrls'; after='fun allowsUrl(url: String): Boolean = true'; test='HookHttpTest' },
    @{ id='M04'; file='HookHttp.kt'; before='layers.mapNotNull(pick).takeIf { it.isNotEmpty() }?.flatten()?.distinct()'; after='layers.first().let(pick)'; test='HookHttpTest' },
    @{ id='M05'; file='HookHttp.kt'; before='if (name in allowed && policy.allowsEnvVar(name)) env[name].orEmpty() else ""'; after='env[name].orEmpty()'; test='HookHttpTest' },
    @{ id='M06'; file='HookHttp.kt'; before='if (name in allowed && policy.allowsEnvVar(name)) env[name].orEmpty()'; after='if (name in allowed) env[name].orEmpty()'; test='HookHttpTest' },
    @{ id='M07'; file='HookHttp.kt'; before='HandlerRun(handler, 1, "", "HTTP ${result.status}"'; after='HandlerRun(handler, 0, "", "HTTP ${result.status}"'; test='HookHttpTest' },
    @{ id='M08'; file='HookHttp.kt'; before='HandlerRun(handler, 1, "", "响应体不是 JSON 对象：$firstLine")'; after='HandlerRun(handler, 0, body, "")'; test='HookHttpTest' },
    @{ id='M09'; file='HookHttp.kt'; before='is HttpHookResult.TimedOut -> HandlerRun(handler, 124, "", "", timedOut = true)'; after='is HttpHookResult.TimedOut -> HandlerRun(handler, 124, "", "", timedOut = false)'; test='HookHttpTest' },
    @{ id='M10'; file='HookHttp.kt'; before='body.isBlank() -> HandlerRun(handler, 0, "", "")'; after='body.isBlank() -> HandlerRun(handler, 1, "", "空响应体")'; test='HookHttpTest' },
    @{ id='M11'; file='HookInterpret.kt'; before='HookEvent.Setup, HookEvent.InstructionsLoaded, HookEvent.PermissionDenied)'; after='HookEvent.Setup, HookEvent.InstructionsLoaded)'; test='HookInterpretTest' }
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
            # Windows PowerShell 5.1 里 Stop 偏好会把原生命令的 stderr 行升成异常；用 cmd 包一层，输出全部落日志
            $gradleHome = Join-Path $Root 'gradlew.bat'
            & cmd /c "`"$gradleHome`" :agent:ext:test --tests `"*.$($case.test)`" --no-daemon > `"$caseRoot\gradle.log`" 2>&1"
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
