param([switch]$Uninstall, [string]$UserSid, [switch]$ValidateOnly)
$ErrorActionPreference = 'Stop'
# A launcher may inherit PowerShell 7's module path. Prefer the Windows modules
# needed by the Windows PowerShell 5.1 installer; change only this process.
$taskWindowsModules = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\Modules'
$env:PSModulePath = $taskWindowsModules + ';' + $env:PSModulePath
$taskBundle = Split-Path -Parent $MyInvocation.MyCommand.Path
$taskInstallFolder = Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'AlterEgo MR Webcam'
$taskFramesRoot = Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'AlterEgoMR'
$taskInstalledDll = Join-Path $taskInstallFolder 'AlterEgoCamera.dll'
$taskInstalledExe = Join-Path $taskInstallFolder 'AlterEgoWebcam.exe'
$taskRequiredFiles = @('AlterEgoWebcam.exe', 'AlterEgoCamera.dll', 'MediaCaptureProbe.exe', 'InstallWebcam.ps1', 'README.md', 'bundle-sha256.json')
$taskFirewallName = 'AlterEgo MR Avatar LAN'
$taskUninstallKey = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\AlterEgoMRWebcam'
$taskShortcut = Join-Path ([Environment]::GetFolderPath('CommonPrograms')) 'AlterEgo MR Webcam.lnk'

function Assert-PlainTree([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $taskItem = Get-Item -LiteralPath $Path -Force
    if (($taskItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Refusing redirected filesystem path: $Path" }
    if ($taskItem.PSIsContainer) {
        foreach ($taskChild in (Get-ChildItem -LiteralPath $Path -Force)) { Assert-PlainTree $taskChild.FullName }
    }
}

function Remove-OwnedTree([string]$Path, [string]$AllowedParent) {
    $taskResolved = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $taskAllowed = [IO.Path]::GetFullPath($AllowedParent).TrimEnd('\') + '\'
    if (-not $taskResolved.StartsWith($taskAllowed, [StringComparison]::OrdinalIgnoreCase)) { throw "Cleanup target is outside its intended folder: $Path" }
    Assert-PlainTree $taskResolved
    if (Test-Path -LiteralPath $taskResolved) { Remove-Item -LiteralPath $taskResolved -Recurse -Force }
}

function Remove-RetiredBackup([string]$Path, [string]$InstalledFolder) {
    # Best-effort retirement happens only after the new installation has committed.
    # FrameServer may still hold the old DLL; cleanup is not an update failure.
    try {
        $taskBackupResolved = [IO.Path]::GetFullPath($Path).TrimEnd('\')
        $taskInstalledResolved = [IO.Path]::GetFullPath($InstalledFolder).TrimEnd('\')
        $taskExpectedBackup = '^' + [Regex]::Escape($taskInstalledResolved) + '\.backup-[0-9a-f]{32}$'
        if ($taskBackupResolved -notmatch $taskExpectedBackup) { throw 'Refusing cleanup of a path that is not this product''s generated upgrade backup.' }
        Remove-OwnedTree $taskBackupResolved (Split-Path -Parent $taskInstalledResolved)
    } catch {
        Write-Warning "The update is installed. Old backup files remain at $Path because cleanup could not finish: $($_.Exception.Message). Close Windows camera apps before cleaning the retired backup; this does not undo the new installation." -WarningAction Continue
    }
}

function New-PrivateAcl([string]$TargetSid, [switch]$Root) {
    $taskAcl = [Security.AccessControl.DirectorySecurity]::new()
    $taskAcl.SetAccessRuleProtection($true, $false)
    $taskAcl.SetOwner([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))
    $taskInherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    foreach ($taskPrincipal in @('S-1-5-18', 'S-1-5-32-544')) {
        $taskAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($taskPrincipal), 'FullControl', $taskInherit, 'None', 'Allow'))
    }
    if ($Root) {
        # Traversal alone cannot enumerate or read another interactive user's avatar files.
        $taskAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-32-545'), 'Traverse', 'None', 'None', 'Allow'))
        $taskAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-19'), 'ReadAndExecute', 'None', 'None', 'Allow'))
    } else {
        $taskAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($TargetSid), 'Modify', $taskInherit, 'None', 'Allow'))
        $taskAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-19'), 'ReadAndExecute', $taskInherit, 'None', 'Allow'))
    }
    return $taskAcl
}

function Set-PrivateAcl([string]$Path, [string]$TargetSid, [switch]$Root) {
    Set-Acl -LiteralPath $Path -AclObject (New-PrivateAcl $TargetSid -Root:$Root)
    if (-not $Root) {
        # Strip explicit broad permissions from earlier files and restore secure inheritance.
        foreach ($taskChild in (Get-ChildItem -LiteralPath $Path -Force -Recurse)) {
            $taskChildAcl = Get-Acl -LiteralPath $taskChild.FullName
            $taskChildAcl.SetAccessRuleProtection($false, $false)
            foreach ($taskRule in @($taskChildAcl.Access | Where-Object { -not $_.IsInherited })) { $taskChildAcl.RemoveAccessRuleSpecific($taskRule) }
            Set-Acl -LiteralPath $taskChild.FullName -AclObject $taskChildAcl
        }
    }
}

function Register-Source([string]$Dll, [switch]$Remove) {
    if (-not (Test-Path -LiteralPath $Dll)) { return }
    $taskArguments = '/s ' + $(if ($Remove) { '/u ' } else { '' }) + '"' + $Dll + '"'
    $taskRegistration = Start-Process -FilePath (Join-Path $env:WINDIR 'System32\regsvr32.exe') -ArgumentList $taskArguments -Wait -PassThru -WindowStyle Hidden
    if ($taskRegistration.ExitCode -ne 0) { throw "Webcam registration failed (exit $($taskRegistration.ExitCode)). Close video-call apps and retry." }
}

function Set-InstalledFirewall {
    Get-NetFirewallRule -DisplayName $taskFirewallName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName $taskFirewallName -Direction Inbound -Program $taskInstalledExe -Protocol TCP -LocalPort 38991 -RemoteAddress LocalSubnet -Profile Private -Action Allow | Out-Null
    New-NetFirewallRule -DisplayName $taskFirewallName -Direction Inbound -Program $taskInstalledExe -Protocol UDP -LocalPort 38992 -RemoteAddress LocalSubnet -Profile Private -Action Allow | Out-Null
}

function Assert-NoInstalledReceiver {
    $taskRunning = Get-CimInstance Win32_Process -Filter "Name = 'AlterEgoWebcam.exe' OR Name = 'pc_avatar_webcam.exe'" | Where-Object {
        $_.ExecutablePath -and ([IO.Path]::GetFullPath($_.ExecutablePath).StartsWith($taskInstallFolder + '\', [StringComparison]::OrdinalIgnoreCase))
    }
    if ($taskRunning) { throw 'Close the installed AlterEgo MR Webcam before updating or removing it, then run this installer again.' }
}

if ($UserSid) {
    if ($UserSid -notmatch '^S-1-5-21-\d+-\d+-\d+-\d+$') { throw 'UserSid must be a normal Windows user SID captured before administrator elevation.' }
    $taskSid = [Security.Principal.SecurityIdentifier]::new($UserSid)
    # A loaded/cached profile avoids requiring a domain controller during offline setup.
    if (-not (Test-Path -LiteralPath ('HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList\' + $taskSid.Value))) { throw 'The intended camera user must have a Windows profile on this PC.' }
}
if (-not $Uninstall) {
    if (-not $UserSid) { throw 'Open AlterEgoWebcam.exe and choose Install webcam, or provide -UserSid for the intended Windows account. The elevated administrator must not be assumed to be the camera user.' }
    foreach ($taskFile in $taskRequiredFiles) {
        $taskPayload = Join-Path $taskBundle $taskFile
        if (-not (Test-Path -LiteralPath $taskPayload -PathType Leaf)) { throw "Offline bundle is incomplete: $taskFile is missing. Extract all files together." }
        Assert-PlainTree $taskPayload
    }
    $taskManifest = Get-Content -LiteralPath (Join-Path $taskBundle 'bundle-sha256.json') -Raw | ConvertFrom-Json
    foreach ($taskFile in ($taskRequiredFiles | Where-Object { $_ -ne 'bundle-sha256.json' })) {
        $taskExpected = $taskManifest.files.PSObject.Properties[$taskFile].Value
        if ($taskExpected -notmatch '^[0-9a-fA-F]{64}$') { throw "Bundle checksum is missing for $taskFile." }
        $taskActual = (Get-FileHash -LiteralPath (Join-Path $taskBundle $taskFile) -Algorithm SHA256).Hash
        if ($taskActual -ne $taskExpected) { throw "Bundle integrity check failed for $taskFile. Obtain the complete bundle again." }
    }
    $taskProcessor = $(if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE })
    if (-not [Environment]::Is64BitOperatingSystem -or $taskProcessor -ne 'AMD64' -or [Environment]::OSVersion.Version.Build -lt 22000) { throw 'AlterEgo MR Webcam requires Windows 11 x64 (build 22000 or newer); the x64 media source cannot run inside an ARM64 FrameServer.' }
}
Assert-PlainTree $taskInstallFolder
if ($ValidateOnly) {
    Write-Output "Validated installation/removal plan for $taskInstallFolder. No Windows settings changed."
    return
}
$taskAdmin = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $taskAdmin.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run this installer as administrator. Webcam registration and its firewall rule are machine-wide.' }
if (-not [Environment]::Is64BitProcess) { throw 'Use 64-bit PowerShell to register the x64 webcam source.' }
Assert-PlainTree $taskFramesRoot
Assert-NoInstalledReceiver

if ($Uninstall) {
    Register-Source $taskInstalledDll -Remove
    Remove-NetFirewallRule -DisplayName $taskFirewallName -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $taskShortcut -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $taskUninstallKey -Recurse -Force -ErrorAction SilentlyContinue
    Remove-OwnedTree $taskInstallFolder ([Environment]::GetFolderPath('ProgramFiles'))
    Remove-OwnedTree $taskFramesRoot ([Environment]::GetFolderPath('CommonApplicationData'))
    Write-Output 'AlterEgo MR Webcam removed, including registration, receiver, frame files, shortcut and firewall rule.'
    return
}

$taskStage = $taskInstallFolder + '.install-' + [Guid]::NewGuid().ToString('N')
$taskBackup = $taskInstallFolder + '.backup-' + [Guid]::NewGuid().ToString('N')
$taskHadInstall = Test-Path -LiteralPath $taskInstallFolder
$taskCommitted = $false
$taskFrameService = Get-Service -Name FrameServer -ErrorAction SilentlyContinue
$taskRestartFrameService = $taskFrameService -and $taskFrameService.Status -eq 'Running'
if ($taskRestartFrameService) { Stop-Service -Name FrameServer -Force -ErrorAction Stop }
try {
    New-Item -ItemType Directory -Path $taskStage | Out-Null
    foreach ($taskFile in $taskRequiredFiles) { Copy-Item -LiteralPath (Join-Path $taskBundle $taskFile) -Destination (Join-Path $taskStage $taskFile) }
    & (Join-Path $env:WINDIR 'System32\icacls.exe') $taskStage /grant '*S-1-5-19:(OI)(CI)(RX)' '*S-1-15-2-1:(OI)(CI)(RX)' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not grant FrameServer access to the webcam source.' }
    New-Item -ItemType Directory -Path $taskFramesRoot -Force | Out-Null
    Set-PrivateAcl $taskFramesRoot '' -Root
    # Retire the former globally readable/writable channel before enabling the private one.
    $taskLegacyFrame = Join-Path $taskFramesRoot 'frames.dat'
    if (Test-Path -LiteralPath $taskLegacyFrame) { Remove-Item -LiteralPath $taskLegacyFrame -Force }
    $taskUserFrames = Join-Path $taskFramesRoot $UserSid
    New-Item -ItemType Directory -Path $taskUserFrames -Force | Out-Null
    Set-PrivateAcl $taskUserFrames $UserSid
    # Existing SID channels keep their own account, with no inherited Users access.
    foreach ($taskExistingChannel in (Get-ChildItem -LiteralPath $taskFramesRoot -Directory)) {
        if ($taskExistingChannel.Name -match '^S-1-5-21-\d+-\d+-\d+-\d+$' -and $taskExistingChannel.Name -ne $UserSid) { Set-PrivateAcl $taskExistingChannel.FullName $taskExistingChannel.Name }
    }
    if ($taskHadInstall) { Move-Item -LiteralPath $taskInstallFolder -Destination $taskBackup }
    Move-Item -LiteralPath $taskStage -Destination $taskInstallFolder
    $taskCommitted = $true
    Register-Source $taskInstalledDll
    Set-InstalledFirewall
    $taskShell = New-Object -ComObject WScript.Shell
    $taskLink = $taskShell.CreateShortcut($taskShortcut)
    $taskLink.TargetPath = $taskInstalledExe
    $taskLink.WorkingDirectory = $taskInstallFolder
    $taskLink.Description = 'AlterEgo MR Quest avatar virtual webcam'
    $taskLink.Save()
    New-Item -Path $taskUninstallKey -Force | Out-Null
    foreach ($taskEntry in @(@('DisplayName', 'AlterEgo MR Webcam'), @('DisplayVersion', [string]$taskManifest.version), @('InstallLocation', $taskInstallFolder), @('DisplayIcon', $taskInstalledExe))) {
        New-ItemProperty -Path $taskUninstallKey -Name $taskEntry[0] -Value $taskEntry[1] -PropertyType String -Force | Out-Null
    }
    $taskUninstallCommand = '"' + (Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe') + '" -NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $taskInstallFolder 'InstallWebcam.ps1') + '" -Uninstall'
    New-ItemProperty -Path $taskUninstallKey -Name UninstallString -Value $taskUninstallCommand -PropertyType String -Force | Out-Null
    New-ItemProperty -Path $taskUninstallKey -Name NoModify -Value 1 -PropertyType DWord -Force | Out-Null
    New-ItemProperty -Path $taskUninstallKey -Name NoRepair -Value 1 -PropertyType DWord -Force | Out-Null
} catch {
    $taskOriginalError = $_
    if ($taskCommitted) {
        try {
            Register-Source $taskInstalledDll -Remove
            Remove-OwnedTree $taskInstallFolder ([Environment]::GetFolderPath('ProgramFiles'))
            if ($taskHadInstall -and (Test-Path -LiteralPath $taskBackup)) {
                Move-Item -LiteralPath $taskBackup -Destination $taskInstallFolder
                Register-Source $taskInstalledDll
                Set-InstalledFirewall
            } else {
                Remove-NetFirewallRule -DisplayName $taskFirewallName -ErrorAction SilentlyContinue
                Remove-Item -LiteralPath $taskShortcut -Force -ErrorAction SilentlyContinue
                Remove-Item -LiteralPath $taskUninstallKey -Recurse -Force -ErrorAction SilentlyContinue
            }
        } catch { Write-Warning "Rollback needs attention: $($_.Exception.Message). Prior files, if any, remain in $taskBackup." }
    } elseif ($taskHadInstall -and (Test-Path -LiteralPath $taskBackup) -and -not (Test-Path -LiteralPath $taskInstallFolder)) {
        Move-Item -LiteralPath $taskBackup -Destination $taskInstallFolder
    }
    throw $taskOriginalError
} finally {
    if (Test-Path -LiteralPath $taskStage) { Remove-OwnedTree $taskStage ([Environment]::GetFolderPath('ProgramFiles')) }
    if ($taskRestartFrameService) { Start-Service -Name FrameServer -ErrorAction Continue }
}
# Successful registration, firewall and installed-app metadata are committed.
# A DLL retained by FrameServer must not send execution through the rollback catch.
if ($taskHadInstall) { Remove-RetiredBackup $taskBackup $taskInstallFolder }
Write-Output "Installed in $taskInstallFolder. Open AlterEgo MR Webcam from the Start menu. Frame access is limited to $UserSid and Windows FrameServer, plus Windows administrators/system."
