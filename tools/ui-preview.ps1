<#
.SYNOPSIS
Captures actual Compose screens from the debug-only UiPreviewActivity.
.EXAMPLE
pwsh -File tools/ui-preview.ps1 -Build -Install -Serial emulator-5554
.EXAMPLE
./tools/ui-preview.ps1 -Scene start,conversation,settings -Theme light,dark -Chrome
.NOTES
No provider credentials, Linux installation, or model sessions are required.
Use -Chrome for interactive scene tabs and animated theme switching.
Without -Chrome the captured content keeps the production viewport height.
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [ValidateSet("start", "blank", "loading", "setup", "controls", "conversation", "settings", "sessions")]
    [string[]]$Scene = @('start', 'blank', 'loading', 'setup', 'controls', 'conversation', 'settings'),
    [ValidateSet('light', 'dark')]
    [string[]]$Theme = @('light', 'dark'),
    [string]$OutputDir,
    [string]$Adb,
    [switch]$Build,
    [switch]$Install,
    [switch]$Chrome,
    [ValidateRange(0, 10000)]
    [int]$SettleMs = 1800,
    [ValidateRange(5, 120)]
    [int]$ReadyTimeoutSeconds = 30
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (!$OutputDir) { $OutputDir = Join-Path $repo 'work/ui-preview' }
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)

if (!$Adb) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { $Adb = $adbCommand.Source }
    if (!$Adb) {
        $sdk = $env:ANDROID_SDK_ROOT
        if (!$sdk) { $sdk = $env:ANDROID_HOME }
        if (!$sdk) {
            $localProperties = Join-Path $repo 'local.properties'
            $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
            if ($sdkLine) { $sdk = $sdkLine.Substring(8).Replace('\\', '\').Replace('\:', ':') }
        }
        if ($sdk) { $Adb = Join-Path $sdk 'platform-tools/adb.exe' }
    }
}
if (!$Adb -or !(Test-Path -LiteralPath $Adb)) { throw 'ADB not found. Supply -Adb or configure the Android SDK.' }

function Invoke-Adb {
    param([string[]]$Arguments)
    $lines = & $Adb @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $($Arguments -join ' ')`n$($lines -join [Environment]::NewLine)" }
    return ($lines -join [Environment]::NewLine)
}

if ($Build) {
    Push-Location $repo
    try {
        & (Join-Path $repo 'gradlew.bat') ':app:assembleDebug'
        if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed ($LASTEXITCODE)" }
    } finally { Pop-Location }
}

$deviceListing = Invoke-Adb @('devices')
$deviceSerials = @($deviceListing -split '\r?\n' | ForEach-Object {
    if ($_ -match '^(\S+)\s+device$') { $Matches[1] }
})
if (!$Serial) {
    if ($deviceSerials.Count -ne 1) { throw 'Specify -Serial when there is not exactly one ready Android device.' }
    $Serial = $deviceSerials[0]
}
if ($Serial -notin $deviceSerials) { throw "Device $Serial is not ready. Check adb devices." }
$deviceArgs = @('-s', $Serial)
$boot = Invoke-Adb ($deviceArgs + @('shell', 'getprop', 'sys.boot_completed'))
if ($boot.Trim() -ne '1') { throw "Device $Serial has not finished booting." }

if ($Install) {
    $apk = Join-Path $repo 'app/build/outputs/apk/debug/app-universal-debug.apk'
    if (!(Test-Path -LiteralPath $apk)) { throw 'Debug APK not found. Add -Build or assembleDebug first.' }
    Write-Host (Invoke-Adb ($deviceArgs + @('install', '-r', $apk)))
}

$component = 'dev.min.code.debug/dev.min.code.debug.UiPreviewActivity'
$componentCheck = Invoke-Adb ($deviceArgs + @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-n', $component))
if ($componentCheck -notmatch 'UiPreviewActivity') { throw 'Installed debug app has no UI preview activity. Run with -Build -Install.' }

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$captures = @()
foreach ($appearance in $Theme) {
    foreach ($screen in $Scene) {
        $runId = [Guid]::NewGuid().ToString('N')
        $launchArgs = $deviceArgs + @(
            'shell', 'am', 'start', '-W', '-n', $component, '-f', '0x14000000',
            '--es', 'min.preview.scene', $screen,
            '--es', 'min.preview.theme', $appearance,
            '--es', 'min.preview.run', $runId,
            '--ez', 'min.preview.chrome', $Chrome.IsPresent.ToString().ToLowerInvariant()
        )
        $launch = Invoke-Adb $launchArgs
        if ($launch -match 'Error:|Exception') { throw "Preview launch failed: $launch" }
        $deadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
        $ready = $false
        do {
            $log = Invoke-Adb ($deviceArgs + @('logcat', '-d', '-t', '200', '-s', 'UiPreview:I', '*:S'))
            $ready = $log.Contains("READY $runId $screen $appearance")
            if (!$ready) { Start-Sleep -Milliseconds 200 }
        } while (!$ready -and [DateTime]::UtcNow -lt $deadline)
        if (!$ready) {
            $crash = Invoke-Adb ($deviceArgs + @('logcat', '-d', '-t', '100', '-s', 'AndroidRuntime:E', '*:S'))
            throw "Preview did not render: $screen / $appearance`n$crash"
        }
        Start-Sleep -Milliseconds $SettleMs
        $focus = Invoke-Adb ($deviceArgs + @('shell', 'dumpsys', 'window', 'displays'))
        if ($focus -notmatch 'mCurrentFocus=.*UiPreviewActivity') { throw "Preview lost focus before capture: $screen / $appearance" }
        $fileName = "$screen-$appearance.png"
        $path = Join-Path $OutputDir $fileName
        $remote = "/sdcard/Download/min-ui-preview-$runId.png"
        try {
            Invoke-Adb ($deviceArgs + @('shell', 'screencap', '-p', $remote)) | Out-Null
            Invoke-Adb ($deviceArgs + @('pull', $remote, $path)) | Out-Null
        } finally {
            Invoke-Adb ($deviceArgs + @('shell', 'rm', $remote)) | Out-Null
        }
        $bytes = [System.IO.File]::ReadAllBytes($path)
        if ($bytes.Length -lt 1000 -or [BitConverter]::ToString($bytes, 0, 8) -ne '89-50-4E-47-0D-0A-1A-0A') {
            throw "Invalid PNG screenshot: $path"
        }
        $width = [int]$bytes[16] * 16777216 + [int]$bytes[17] * 65536 + [int]$bytes[18] * 256 + [int]$bytes[19]
        $height = [int]$bytes[20] * 16777216 + [int]$bytes[21] * 65536 + [int]$bytes[22] * 256 + [int]$bytes[23]
        $captures += [ordered]@{ scene = $screen; theme = $appearance; file = $fileName; width = $width; height = $height; bytes = $bytes.Length }
        Write-Host "$screen / $appearance : $path ($width x $height)"
    }
}

$manifest = [ordered]@{
    generatedAt = [DateTime]::UtcNow.ToString('o')
    source = 'Android Compose / debug UiPreviewActivity'
    device = $Serial
    chrome = $Chrome.IsPresent
    captures = $captures
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDir 'manifest.json') -Encoding utf8
Write-Host "Captured $($captures.Count) screens. Manifest: $(Join-Path $OutputDir 'manifest.json')"
