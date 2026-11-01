# Read stored AlterEgo summaries after a wearer test; does not wake/launch the app.
[CmdletBinding()]
param([string]$Serial)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$adbPath = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) {
    $adbCommand = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $adbCommand) { throw 'Android platform tools were not found.' }
    $adbPath = $adbCommand.Source
}
$deviceOutput = & $adbPath devices
if ($LASTEXITCODE -ne 0) { throw 'Could not list connected headsets.' }
$deviceIds = @(
    foreach ($line in $deviceOutput) {
        if ($line -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
    }
)
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    $targetSerial = $Serial.Trim()
    if ($targetSerial -notin $deviceIds) { throw "Headset '$targetSerial' is not connected and authorized." }
} elseif ($deviceIds.Count -eq 1) {
    $targetSerial = $deviceIds[0]
} elseif ($deviceIds.Count -eq 0) {
    throw 'Connect the headset and accept USB debugging, then retry.'
} else {
    throw "Multiple connections are available ($($deviceIds -join ', ')). Supply -Serial to choose one."
}

$taskStamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$outputDirectory = Join-Path $projectDirectory "scratch\hand-performance-$taskStamp"
New-Item -ItemType Directory -Path $outputDirectory | Out-Null
$package = 'com.misterslee.alterego.mr'
$packageDetails = & $adbPath -s $targetSerial shell dumpsys package $package
if ($LASTEXITCODE -ne 0) { throw 'Could not read the installed app version.' }
$packageDetails | Select-String 'versionName=|versionCode=|com.oculus.handtracking.version|com.oculus.vr.focusaware' |
    Set-Content -LiteralPath (Join-Path $outputDirectory 'installed-version.txt') -Encoding UTF8
$activity = & $adbPath -s $targetSerial shell dumpsys activity $package
if ($LASTEXITCODE -eq 0) {
    $activity | Set-Content -LiteralPath (Join-Path $outputDirectory 'activity.txt') -Encoding UTF8
}
$power = & $adbPath -s $targetSerial shell dumpsys power
if ($LASTEXITCODE -eq 0) {
    $power | Select-String 'mWakefulness=|mProximityPositive=' |
        Set-Content -LiteralPath (Join-Path $outputDirectory 'power.txt') -Encoding UTF8
}
$remote = "/sdcard/Android/data/$package/files/diagnostics"
& $adbPath -s $targetSerial shell test -d $remote
if ($LASTEXITCODE -eq 0) {
    & $adbPath -s $targetSerial pull $remote $outputDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy the stored performance summaries.' }
    $summaryFiles = @(Get-ChildItem -LiteralPath (Join-Path $outputDirectory 'diagnostics') -Filter '*.log' -File)
    $samples = @($summaryFiles | Select-String '^sample ')
    Write-Output "Saved $($samples.Count) frame summaries to $outputDirectory"
    if ($samples.Count -eq 0) {
        Write-Output 'The log has no visible scene frames yet. A worn-headset test is still needed.'
    }
} else {
    Write-Output "Saved device status to $outputDirectory"
    Write-Output 'No stored performance log exists yet. Open version 1.2.15 or newer and test passthrough first.'
}
