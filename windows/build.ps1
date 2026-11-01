param(
    [string]$CompilerRoot = '',
    [string]$WindowsSdk = 'C:\Program Files (x86)\Windows Kits\10',
    [string]$SdkVersion = '',
    [string]$Version = '1.5.0',
    [string]$CodeSigningThumbprint = '',
    [string]$TimestampUrl = ''
)
$ErrorActionPreference = 'Stop'
$taskDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$taskOutput = Join-Path $taskDirectory 'dist'
$taskIcon = Join-Path $taskDirectory 'assets\alterego.ico'
if (-not (Test-Path -LiteralPath $taskIcon -PathType Leaf)) { throw "Application icon was not found: $taskIcon" }
if (-not $CompilerRoot) {
    $taskToolRoots = @('C:\Program Files\Microsoft Visual Studio\2022', 'C:\Program Files (x86)\Microsoft Visual Studio\2022')
    $taskCandidates = foreach ($taskToolRoot in $taskToolRoots) {
        if (Test-Path -LiteralPath $taskToolRoot) { Get-ChildItem -Path (Join-Path $taskToolRoot '*\VC\Tools\MSVC\*') -Directory -ErrorAction SilentlyContinue }
    }
    $CompilerRoot = ($taskCandidates | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1).FullName
    if (-not $CompilerRoot) { throw 'Visual Studio 2022 C++ tools were not found. Install them or provide -CompilerRoot.' }
}
if (-not $SdkVersion) {
    $SdkVersion = (Get-ChildItem -LiteralPath (Join-Path $WindowsSdk 'Include') -Directory | Where-Object { $_.Name -match '^\d+\.\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1).Name
}
if (-not $SdkVersion -or [version]$SdkVersion -lt [version]'10.0.22000.0') { throw 'A Windows 11 SDK (10.0.22000.0 or newer) is required.' }
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
$taskInclude = @((Join-Path $CompilerRoot 'include')) + @('ucrt','shared','um','winrt') | ForEach-Object { if ($_ -match '^[A-Z]:') { $_ } else { Join-Path $WindowsSdk "Include\$SdkVersion\$_" } }
$taskArgs = @('/nologo','/std:c++17','/EHsc','/MT','/O2','/LD','/DUNICODE','/D_UNICODE','/D_WIN32_WINNT=0x0A00')
foreach ($taskPath in $taskInclude) { $taskArgs += "/I$taskPath" }
$taskArgs += @((Join-Path $taskDirectory 'AlterEgoCamera.cpp'),"/Fo$taskOutput\AlterEgoCamera.obj",'/link',"/OUT:$taskOutput\AlterEgoCamera.dll","/IMPLIB:$taskOutput\AlterEgoCamera.lib", "/LIBPATH:$CompilerRoot\lib\x64", "/LIBPATH:$WindowsSdk\Lib\$SdkVersion\um\x64", "/LIBPATH:$WindowsSdk\Lib\$SdkVersion\ucrt\x64",'mfplat.lib','mf.lib','mfuuid.lib','ole32.lib','oleaut32.lib','advapi32.lib','kernel32.lib')
$taskArgs += "/DEF:$taskDirectory\AlterEgoCamera.def"
& (Join-Path $CompilerRoot 'bin\Hostx64\x64\cl.exe') @taskArgs
if ($LASTEXITCODE -ne 0) { throw 'Native camera build failed' }
& (Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe') /nologo /target:winexe /platform:x64 /unsafe /optimize+ "/out:$taskOutput\AlterEgoWebcam.exe" "/win32icon:$taskIcon" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Core.dll /reference:System.Security.dll (Join-Path $taskDirectory 'AlterEgoWebcam.cs') (Join-Path $taskDirectory 'SecurePairing.cs') (Join-Path $taskDirectory 'LanDiscovery.cs') (Join-Path $taskDirectory 'AvatarBackground.cs') (Join-Path $taskDirectory 'AvatarBackgroundTests.cs') (Join-Path $taskDirectory 'WebcamLayout.cs')
if ($LASTEXITCODE -ne 0) { throw 'Windows receiver build failed' }
& (Join-Path $taskDirectory 'build_capture_probe.ps1') -CompilerRoot $CompilerRoot -SdkVersion $SdkVersion
Copy-Item -LiteralPath (Join-Path $taskDirectory 'InstallWebcam.ps1'),(Join-Path $taskDirectory 'README.md') -Destination $taskOutput -Force
$taskSigned = $false
if ($CodeSigningThumbprint) {
    if ($CodeSigningThumbprint -notmatch '^[0-9a-fA-F]{40}$') { throw 'Code-signing thumbprint must identify an existing 40-hex certificate.' }
    $taskCertificate = Get-Item -LiteralPath ('Cert:\CurrentUser\My\' + $CodeSigningThumbprint) -ErrorAction SilentlyContinue
    $taskMachineCertificate = $false
    if (-not $taskCertificate) {
        $taskCertificate = Get-Item -LiteralPath ('Cert:\LocalMachine\My\' + $CodeSigningThumbprint) -ErrorAction SilentlyContinue
        $taskMachineCertificate = $true
    }
    if (-not $taskCertificate -or -not $taskCertificate.HasPrivateKey -or @($taskCertificate.EnhancedKeyUsageList.ObjectId) -notcontains '1.3.6.1.5.5.7.3.3') { throw 'Certificate must contain a private key and the Code Signing extended key usage.' }
    $taskChain = [Security.Cryptography.X509Certificates.X509Chain]::new()
    try { if (-not $taskChain.Build($taskCertificate)) { throw 'A trusted publisher code-signing certificate is required. A self-signed transport certificate cannot sign this release.' } } finally { $taskChain.Dispose() }
    $taskSignTool = Join-Path $WindowsSdk "bin\$SdkVersion\x64\signtool.exe"
    $taskSignArgs = @('sign', '/sha1', $CodeSigningThumbprint, '/s', 'My', '/fd', 'SHA256')
    if ($taskMachineCertificate) { $taskSignArgs += '/sm' }
    if ($TimestampUrl) { $taskSignArgs += @('/tr', $TimestampUrl, '/td', 'SHA256') }
    $taskSignArgs += @((Join-Path $taskOutput 'AlterEgoWebcam.exe'), (Join-Path $taskOutput 'AlterEgoCamera.dll'), (Join-Path $taskOutput 'MediaCaptureProbe.exe'))
    & $taskSignTool @taskSignArgs
    if ($LASTEXITCODE -ne 0) { throw 'Publisher code signing failed.' }
    $taskScriptSigningArgs = @{ FilePath = (Join-Path $taskOutput 'InstallWebcam.ps1'); Certificate = $taskCertificate; HashAlgorithm = 'SHA256' }
    if ($TimestampUrl) { $taskScriptSigningArgs.TimestampServer = $TimestampUrl }
    $taskScriptSignature = Set-AuthenticodeSignature @taskScriptSigningArgs
    if ($taskScriptSignature.Status -ne 'Valid') { throw 'Installer publisher signature failed validation.' }
    foreach ($taskSignedFile in @('AlterEgoWebcam.exe', 'AlterEgoCamera.dll', 'MediaCaptureProbe.exe')) {
        if ((Get-AuthenticodeSignature -LiteralPath (Join-Path $taskOutput $taskSignedFile)).Status -ne 'Valid') { throw "Publisher signature is not trusted: $taskSignedFile" }
    }
    $taskSigned = $true
} else { Write-Warning 'No publisher code-signing certificate configured. This development release is unsigned; no trusted-publisher claim is made.' }

function Write-BundleManifest([string]$Folder, [string[]]$Files) {
    $taskHashes = [ordered]@{}
    foreach ($taskFile in $Files) { $taskHashes[$taskFile] = (Get-FileHash -LiteralPath (Join-Path $Folder $taskFile) -Algorithm SHA256).Hash.ToLowerInvariant() }
    [ordered]@{ version = $Version; protocol = 'AEMR0005-TLS1.2'; publisherSigned = $taskSigned; files = $taskHashes } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $Folder 'bundle-sha256.json') -Encoding UTF8
}
$taskBundleFiles = @('AlterEgoWebcam.exe', 'AlterEgoCamera.dll', 'MediaCaptureProbe.exe', 'InstallWebcam.ps1', 'README.md')
Write-BundleManifest $taskOutput $taskBundleFiles
$taskSetup = Join-Path $taskOutput "AlterEgoMR-Webcam-Setup-$Version.exe"
$taskSetupArguments = @('/nologo','/target:winexe','/platform:x64','/optimize+',"/out:$taskSetup","/win32icon:$taskIcon",'/reference:System.Windows.Forms.dll',(Join-Path $taskDirectory 'Setup.cs'))
foreach ($taskResource in ($taskBundleFiles + @('bundle-sha256.json'))) { $taskSetupArguments += "/resource:$taskOutput\$taskResource,$taskResource" }
& (Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe') @taskSetupArguments
if ($LASTEXITCODE -ne 0) { throw 'Offline setup bootstrapper build failed.' }
if ($taskSigned) {
    $taskSetupSignArgs = @('sign','/sha1',$CodeSigningThumbprint,'/s','My','/fd','SHA256')
    if ($taskMachineCertificate) { $taskSetupSignArgs += '/sm' }
    if ($TimestampUrl) { $taskSetupSignArgs += @('/tr',$TimestampUrl,'/td','SHA256') }
    $taskSetupSignArgs += $taskSetup
    & $taskSignTool @taskSetupSignArgs
    if ($LASTEXITCODE -ne 0 -or (Get-AuthenticodeSignature -LiteralPath $taskSetup).Status -ne 'Valid') { throw 'Offline setup publisher signing failed.' }
}

# The old distribution filename is now the exact same receiver, not a second product.
$taskLegacyOutput = Join-Path (Split-Path -Parent $taskDirectory) 'pc_avatar_webcam\dist'
New-Item -ItemType Directory -Path $taskLegacyOutput -Force | Out-Null
foreach ($taskFile in $taskBundleFiles) { Copy-Item -LiteralPath (Join-Path $taskOutput $taskFile) -Destination (Join-Path $taskLegacyOutput $taskFile) -Force }
Copy-Item -LiteralPath (Join-Path $taskOutput 'AlterEgoWebcam.exe') -Destination (Join-Path $taskLegacyOutput 'pc_avatar_webcam.exe') -Force
Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $taskLegacyOutput) 'install_virtualcam.ps1') -Destination $taskLegacyOutput -Force
Write-BundleManifest $taskLegacyOutput ($taskBundleFiles + @('pc_avatar_webcam.exe', 'install_virtualcam.ps1'))
$taskArchive = Join-Path $taskOutput "AlterEgoMR-Webcam-$Version-Windows11-x64.zip"
Compress-Archive -LiteralPath (@($taskBundleFiles | ForEach-Object { Join-Path $taskOutput $_ }) + @(Join-Path $taskOutput 'bundle-sha256.json')) -DestinationPath $taskArchive -Force
Write-Output "Complete offline bundle: $taskArchive. Legacy filename is the same native receiver."
& (Join-Path $taskDirectory 'package_installer.ps1') -Bundle $taskOutput
