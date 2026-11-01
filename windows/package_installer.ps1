# Create a shareable installation pack from the complete offline setup EXE.
param([string]$Bundle = (Join-Path $PSScriptRoot 'dist'))
$ErrorActionPreference = 'Stop'
$taskManifestPath = Join-Path $Bundle 'bundle-sha256.json'
$taskManifest = Get-Content -LiteralPath $taskManifestPath -Raw | ConvertFrom-Json
$taskVersion = [string]$taskManifest.version
if ($taskVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'The bundle must have a numeric release version.' }
$taskSetupName = "AlterEgoMR-Webcam-Setup-$taskVersion.exe"
$taskSetupPath = Join-Path $Bundle $taskSetupName
if (-not (Test-Path -LiteralPath $taskSetupPath -PathType Leaf)) { throw 'Build windows/build.ps1 first; the offline setup EXE is missing.' }

# Check the EXE itself contains this exact bundle, without running installation.
$taskAssembly = [Reflection.Assembly]::Load([IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $taskSetupPath).Path))
$taskPayloadNames = @('AlterEgoWebcam.exe', 'AlterEgoCamera.dll', 'MediaCaptureProbe.exe', 'InstallWebcam.ps1', 'README.md', 'bundle-sha256.json')
$taskResources = @($taskAssembly.GetManifestResourceNames())
if ($taskResources.Count -ne $taskPayloadNames.Count) { throw 'The setup EXE contains an unexpected payload.' }
foreach ($taskPayloadName in $taskPayloadNames) {
    if ($taskResources -notcontains $taskPayloadName) { throw "The setup EXE is missing $taskPayloadName." }
    $taskStream = $taskAssembly.GetManifestResourceStream($taskPayloadName)
    $taskHasher = [Security.Cryptography.SHA256]::Create()
    try { $taskEmbeddedHash = [BitConverter]::ToString($taskHasher.ComputeHash($taskStream)).Replace('-', '').ToLowerInvariant() }
    finally { $taskStream.Dispose(); $taskHasher.Dispose() }
    $taskDiskHash = (Get-FileHash -LiteralPath (Join-Path $Bundle $taskPayloadName) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($taskEmbeddedHash -ne $taskDiskHash) { throw "The setup EXE has an outdated $taskPayloadName. Rebuild windows/build.ps1." }
    if ($taskPayloadName -ne 'bundle-sha256.json' -and $taskDiskHash -ne $taskManifest.files.PSObject.Properties[$taskPayloadName].Value) { throw "Bundle integrity check failed for $taskPayloadName." }
}

$taskPackName = "AlterEgoMR-Webcam-Install-$taskVersion-Windows11-x64"
$taskPackFolder = Join-Path $Bundle $taskPackName
New-Item -ItemType Directory -Path $taskPackFolder -Force | Out-Null
Copy-Item -LiteralPath $taskSetupPath -Destination (Join-Path $taskPackFolder $taskSetupName) -Force
$taskSignature = Get-AuthenticodeSignature -LiteralPath $taskSetupPath
$taskSigningNote = if ($taskSignature.Status -eq 'Valid') { 'This installer has a valid Windows publisher signature.' } else { 'This development installer is not publisher-signed. Windows may show an unknown-publisher warning.' }
$taskGuide = @"
ALTEREGO MR WEBCAM $taskVersion - INSTALLATION PACK

REQUIREMENTS
Windows 11 x64 (Intel/AMD, build 22000 or newer), administrator access,
and a Meta Quest running AlterEgo MR. The installer works offline.

INSTALL
1. Extract this ZIP to a folder.
2. Close any old AlterEgo receiver and camera/video-call apps.
3. Double-click $taskSetupName.
4. Accept Windows administrator consent. Setup installs the app and virtual
   camera, adds the Start menu shortcut, then opens AlterEgo MR Webcam.

CONNECT
1. In the PC app, select the network shared with Quest and click Start receiver.
2. On Quest, open My mirror > Menu > PC Link > Find PC and select this PC.
3. Compare the code on both devices. Approve Codes match on both if identical.
4. Select AlterEgo MR Avatar (Windows Virtual Camera) in your video-call app.
   Use your PC microphone. Keep the Quest mirror and PC receiver running.

UPDATE / REMOVE
Run this setup again to update; close camera apps first. Pairing is preserved.
To remove, open Windows Settings > Apps > Installed apps > AlterEgo MR Webcam.

CONTENTS
$taskSetupName - complete offline installer with the app, virtual-camera
component, diagnostic helper, installation script and detailed documentation.
INSTALL.txt - this guide.
SHA256SUMS.txt - checksums for the installer and guide.

The setup EXE can also be shared and run by itself; no Python, OBS, downloaded
driver or separate payload files are needed.
$taskSigningNote

VERIFY A DOWNLOAD (optional, PowerShell)
Get-FileHash -Algorithm SHA256 '.\$taskSetupName'
Compare the result with SHA256SUMS.txt. Checksums detect file corruption;
they do not establish the publisher's identity.
"@
Set-Content -LiteralPath (Join-Path $taskPackFolder 'INSTALL.txt') -Value $taskGuide -Encoding UTF8
$taskChecksums = foreach ($taskFile in @($taskSetupName, 'INSTALL.txt')) {
    $taskHash = (Get-FileHash -LiteralPath (Join-Path $taskPackFolder $taskFile) -Algorithm SHA256).Hash.ToLowerInvariant()
    "$taskHash  $taskFile"
}
Set-Content -LiteralPath (Join-Path $taskPackFolder 'SHA256SUMS.txt') -Value $taskChecksums -Encoding ASCII
$taskPackFiles = @($taskSetupName, 'INSTALL.txt', 'SHA256SUMS.txt') | ForEach-Object { Join-Path $taskPackFolder $_ }
$taskArchive = Join-Path $Bundle ($taskPackName + '.zip')
Compress-Archive -LiteralPath $taskPackFiles -DestinationPath $taskArchive -Force
Write-Output "Installation pack: $taskArchive"
Write-Output "Standalone installer: $taskSetupPath"
