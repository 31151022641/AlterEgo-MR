# Build, install, and launch AlterEgo MR on a connected headset.
# When more than one device is connected, supply -Serial with its adb device ID.
[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$DebugBuild
)

$ErrorActionPreference = 'Stop'

$adbPath = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) {
    $adbCommand = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $adbCommand) {
        throw 'ADB was not found. Install Android platform tools or add adb to PATH.'
    }
    $adbPath = $adbCommand.Source
}

Write-Host 'Checking connected headsets...' -ForegroundColor Cyan
$deviceOutput = & $adbPath devices
if ($LASTEXITCODE -ne 0) {
    throw "ADB could not list devices (exit code $LASTEXITCODE)."
}
$deviceIds = @(
    foreach ($line in $deviceOutput) {
        if ($line -match '^(\S+)\s+device(?:\s|$)') {
            $Matches[1]
        }
    }
)

if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    $targetSerial = $Serial.Trim()
    if ($targetSerial -notin $deviceIds) {
        throw "Device '$targetSerial' is not connected and authorized. Check the cable and accept USB debugging on the headset."
    }
} elseif ($deviceIds.Count -eq 0) {
    throw 'No connected, authorized device was found. Connect the headset and accept USB debugging, then try again.'
} elseif ($deviceIds.Count -gt 1) {
    throw "Multiple devices are connected: $($deviceIds -join ', '). Run this script with -Serial followed by the headset device ID."
} else {
    $targetSerial = $deviceIds[0]
}

$gradlePath = Join-Path $PSScriptRoot 'gradlew.bat'
$apkPath = Join-Path $PSScriptRoot $(if ($DebugBuild) { 'app\build\outputs\apk\debug\app-debug.apk' } else { 'app\build\outputs\apk\release\app-release.apk' })
if (-not (Test-Path -LiteralPath $gradlePath -PathType Leaf)) {
    throw "The Gradle wrapper was not found at '$gradlePath'."
}

Write-Host 'Building the latest app...' -ForegroundColor Cyan
Push-Location -LiteralPath $PSScriptRoot
try {
    if ($DebugBuild) { & $gradlePath ':app:assembleDebug' '--console=plain' }
    else { & (Join-Path $PSScriptRoot 'build_quest_release.ps1') }
    if ($LASTEXITCODE -ne 0) {
        throw "Android build failed (exit code $LASTEXITCODE). Installation was not started."
    }
} finally {
    Pop-Location
}
if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
    throw "The build did not produce the expected APK at '$apkPath'."
}

Write-Host "Installing AlterEgo MR on '$targetSerial'..." -ForegroundColor Green
& $adbPath -s $targetSerial install -r $apkPath
if ($LASTEXITCODE -ne 0) {
    throw "APK installation failed (exit code $LASTEXITCODE). The app was not launched."
}

Write-Host 'Launching AlterEgo MR...' -ForegroundColor Cyan
$launchOutput = & $adbPath -s $targetSerial shell am start -W -n 'com.misterslee.alterego.mr/com.oculus.camerademo.MirrorActivity'
$launchExitCode = $LASTEXITCODE
$launchOutput | ForEach-Object { Write-Host $_ }
$launchErrors = @($launchOutput | Where-Object {
    $_ -match '^\s*(Error\b|Exception\b)' -or
        ($_ -match '^\s*Status:\s*(\S+)' -and $Matches[1] -ne 'ok')
})
if ($launchExitCode -ne 0 -or $launchErrors.Count -gt 0) {
    throw "The app could not be launched (exit code $launchExitCode). Check the launch output above."
}

Write-Host 'App launched. Camera access is needed for passthrough face replacement. Eye tracking is optional. My mirror requests wearer tracking permissions separately.' -ForegroundColor Green
