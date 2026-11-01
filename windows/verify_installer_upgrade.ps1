# Extract only pure/cleanup helpers. Never run the installer or change Windows settings.
$ErrorActionPreference = 'Stop'
$taskTokens = $null
$taskErrors = $null
$taskAst = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'InstallWebcam.ps1'), [ref]$taskTokens, [ref]$taskErrors)
if ($taskErrors) { throw ($taskErrors | Out-String) }
$taskHelper = $taskAst.Find({ param($Node) $Node -is [Management.Automation.Language.FunctionDefinitionAst] -and $Node.Name -eq 'Remove-RetiredBackup' }, $true)
Invoke-Expression $taskHelper.Extent.Text
$taskCleanupCalls = @($taskAst.FindAll({ param($Node) $Node -is [Management.Automation.Language.CommandAst] -and $Node.GetCommandName() -eq 'Remove-RetiredBackup' }, $true))
if ($taskCleanupCalls.Count -ne 1) { throw 'Expected one post-commit backup cleanup call.' }
for ($taskParent = $taskCleanupCalls[0].Parent; $taskParent; $taskParent = $taskParent.Parent) {
    if ($taskParent -is [Management.Automation.Language.TryStatementAst]) { throw 'Backup cleanup is inside a transaction/rollback handler.' }
}
$taskFakeInstall = Join-Path $PSScriptRoot 'fixture-installed-webcam'
$taskFakeBackup = $taskFakeInstall + '.backup-' + ('a' * 32)
$script:taskRemoveCalled = 0
function Remove-OwnedTree([string]$Path, [string]$AllowedParent) {
    $script:taskRemoveCalled++
    throw [IO.IOException]::new('Simulated FrameServer DLL lock')
}
$taskCommitted = $true
$taskRollback = $false
try { $taskWarnings = @(Remove-RetiredBackup $taskFakeBackup $taskFakeInstall 3>&1) }
catch { $taskRollback = $true }
if (-not $taskCommitted -or $taskRollback -or $script:taskRemoveCalled -ne 1) { throw 'A locked retired DLL triggered rollback or skipped the guarded cleanup.' }
if (-not ($taskWarnings | Where-Object { $_ -is [Management.Automation.WarningRecord] -and $_.Message -like '*update is installed*' })) { throw 'The retained-backup condition was not reported accurately.' }
$script:taskRemoveCalled = 0
$null = Remove-RetiredBackup (Join-Path $PSScriptRoot 'other-product') $taskFakeInstall 3>&1
if ($script:taskRemoveCalled -ne 0) { throw 'An unrelated path reached recursive cleanup.' }
Write-Output 'Upgrade cleanup passed: locked DLL warning cannot roll back a committed update; retirement runs outside transaction and rejects unrelated paths.'
