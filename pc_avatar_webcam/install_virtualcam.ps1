# Compatibility installer. Registers only the complete, bundled AlterEgo source.
param([switch]$Uninstall, [string]$UserSid, [switch]$ValidateOnly)
$ErrorActionPreference = 'Stop'
if (-not $UserSid) { $UserSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value }
if ($UserSid -notmatch '^S-1-5-21-\d+-\d+-\d+-\d+$') { throw 'Run setup as the intended Windows user so its SID can be captured before elevation.' }
$taskInstalledScript = Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'AlterEgo MR Webcam\InstallWebcam.ps1'
$taskCandidates = @((Join-Path $PSScriptRoot 'InstallWebcam.ps1'), (Join-Path $PSScriptRoot 'dist\InstallWebcam.ps1'), (Join-Path (Split-Path -Parent $PSScriptRoot) 'windows\dist\InstallWebcam.ps1'))
if ($Uninstall) { $taskCandidates = @($taskInstalledScript) + $taskCandidates }
$taskInstaller = $taskCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (-not $taskInstaller) { throw 'The offline native bundle is missing. Build windows/build.ps1 or extract all Windows release files together.' }
$taskAdministrator = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if ($ValidateOnly -or $taskAdministrator.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    & $taskInstaller -UserSid $UserSid -Uninstall:$Uninstall -ValidateOnly:$ValidateOnly
    return
}
$taskArguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $taskInstaller + '" -UserSid ' + $UserSid
if ($Uninstall) { $taskArguments += ' -Uninstall' }
$taskProcess = Start-Process -FilePath (Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe') -ArgumentList $taskArguments -Verb RunAs -Wait -PassThru -WindowStyle Hidden
if ($taskProcess.ExitCode -ne 0) { throw "AlterEgo setup failed (exit $($taskProcess.ExitCode)). Close the installed receiver and call apps, then retry." }
Write-Output 'Setup completed. Open AlterEgo MR Webcam from the Start menu.'
