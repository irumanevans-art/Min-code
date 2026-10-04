# MCP 配置加载器的变异验证：每条关键规则改坏一处，确认有测试变红（编译失败不算），再改回。
# 用法：powershell -ExecutionPolicy Bypass -File mcp-config-mutations.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
$env:PATH = "C:\Program Files\Git\bin;C:\Program Files\Git\usr\bin;$env:PATH"

function Mutate([string]$name, [string]$file, [string]$find, [string]$replace, [string[]]$tests) {
    $path = Join-Path $root $file
    $original = [System.IO.File]::ReadAllBytes($path)
    try {
        $text = [System.Text.Encoding]::UTF8.GetString($original)
        $count = ([regex]::Matches($text, [regex]::Escape($find))).Count
        if ($count -ne 1) { Write-Output "[$name] SKIP：锚点出现 $count 次，不是唯一的"; return }
        $mutated = $text.Replace($find, $replace)
        [System.IO.File]::WriteAllText($path, $mutated, (New-Object System.Text.UTF8Encoding($false)))
        $testArgs = ($tests | ForEach-Object { " --tests $_" }) -join ""
        $log = Join-Path $env:TEMP "mcp-mutation-$name.log"
        cmd /c "gradlew.bat :agent:ext:test$testArgs --console=plain > `"$log`" 2>&1" | Out-Null
        $out = Get-Content $log -Raw
        $failed = $out -match "FAILED"
        if ($LASTEXITCODE -ne 0 -and $failed) {
            Write-Output "[$name] KILLED（测试变红）"
        } elseif ($LASTEXITCODE -ne 0) {
            Write-Output "[$name] BROKEN（不是测试断言失败，是别的错误）：`n$($out | Select-Object -First 20)"
        } else {
            Write-Output "[$name] !!! NOT KILLED（测试还是绿的）"
        }
    } finally {
        [System.IO.File]::WriteAllBytes($path, $original)
    }
}

$loader = "dev.min.agent.ext.mcp.McpConfigLoaderTest"
$compat = "dev.min.agent.ext.mcp.McpConfigCompatTest"
$approvals = "dev.min.agent.ext.mcp.McpApprovalsTest"
$k = "agent/ext/src/main/kotlin/dev/min/agent/ext/mcp"

# M1 规则1：优先级反了（user 最高）
Mutate "M1-priority-reversed" "$k/McpConfigLoader.kt" `
    "listOf(Origin.ManagedSetting to providedServers, Origin.Local to localServers, Origin.ProjectFile to projectServers, Origin.User to userServers)" `
    "listOf(Origin.User to userServers, Origin.ProjectFile to projectServers, Origin.Local to localServers, Origin.ManagedSetting to providedServers)" `
    @($loader, $compat)

# M2 规则2：managed 独占失效（其余来源照样进表）
Mutate "M2-exclusive-ignored" "$k/McpConfigLoader.kt" `
    "listOf(Origin.ManagedFile to exclusiveServers, Origin.ManagedSetting to providedServers)" `
    "listOf(Origin.ManagedFile to exclusiveServers, Origin.ManagedSetting to providedServers, Origin.Local to localServers, Origin.ProjectFile to projectServers, Origin.User to userServers)" `
    @($compat)

# M3 规则3：deny 不再优先
Mutate "M3-deny-not-first" "$k/McpConfigLoader.kt" `
    'policy.deniedBy(finalSpec)?.let { candidates += McpGate.Denied to "被 deniedMcpServers 拦下（${it.describe}）" }' `
    'run { }' `
    @($loader, $compat)

# M4 规则3：allowlist 有 serverCommand 项时名字也能放行 stdio
Mutate "M4-command-entry-requirement-dropped" "$k/McpPolicy.kt" `
    "commands.isEmpty() && allowed.any { it is McpPolicyEntry.Name && it.name == spec.name }" `
    "true || allowed.any { it is McpPolicyEntry.Name && it.name == spec.name }" `
    @($loader)

# M5 规则5：远程 url/headers 里的凭证变量照常展开
Mutate "M5-credential-vars-expand" "$k/McpVars.kt" `
    "credentialEmpty && name in CREDENTIAL_VARS -> " `
    "false && credentialEmpty && name in CREDENTIAL_VARS -> " `
    @($loader, $compat)

# M6 规则6：未信任的项目服务器不再 Untrusted
Mutate "M6-untrusted-gate-removed" "$k/McpConfigLoader.kt" `
    'candidates += McpGate.Untrusted to "工作区还没被信任（设计 §3：项目 .mcp.json 的服务器一律不启动）"' `
    'Unit' `
    @($loader, $compat)

# M7 规则6：disabledMcpjsonServers 只从可信层读（未信任项目层的拒绝失效）
Mutate "M7-disabledmcpjson-trusted-only" "$k/McpConfigLoader.kt" `
    'val disabledMcpjson = settings.layers.flatMap { it.raw.stringArrayOf("disabledMcpjsonServers") }.toSet()' `
    'val disabledMcpjson = trustedLayers.flatMap { it.raw.stringArrayOf("disabledMcpjsonServers") }.toSet()' `
    @($loader)

# M8 规则4：低于 1000 的 timeout 照收
Mutate "M8-small-timeout-accepted" "$k/McpConfigLoader.kt" `
    "if (n != null && n >= 1000) n else {" `
    "if (n != null && n >= 0) n else {" `
    @($loader, $compat)

# M9 规则4：ws 不再 Disabled
Mutate "M9-ws-enabled" "$k/McpConfigLoader.kt" `
    "if (finalSpec is McpServerSpec.Remote && finalSpec.kind == McpTransportKind.Ws) {" `
    "if (false && finalSpec is McpServerSpec.Remote && finalSpec.kind == McpTransportKind.Ws) {" `
    @($loader)

# M10 规则8：Stdio 的 toString 泄漏 env 的值
Mutate "M10-tostring-leaks-env" "$k/McpConfig.kt" `
    "envKeys=`${env.keys.sorted()}" `
    "env=`${env}" `
    @($loader)

# M11 规则7：批准写回覆盖不是对象的坏文件
Mutate "M11-approvals-overwrite-broken" "$k/McpApprovals.kt" `
    "if (element !is JsonObject) throw IllegalStateException(" `
    "if (false && element !is JsonObject) throw IllegalStateException(" `
    @($approvals)

Write-Output "---- 完成 ----"
