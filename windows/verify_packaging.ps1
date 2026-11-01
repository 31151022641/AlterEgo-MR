# No registration or firewall changes. Checks isolation ACLs and the built bundle.
param([string]$Bundle = (Join-Path $PSScriptRoot 'dist'))
$ErrorActionPreference = 'Stop'
$taskTokens = $null
$taskParseErrors = $null
$taskAst = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'InstallWebcam.ps1'), [ref]$taskTokens, [ref]$taskParseErrors)
if ($taskParseErrors) { throw ($taskParseErrors | Out-String) }
$taskAclFunction = $taskAst.Find({ param($Node) $Node -is [Management.Automation.Language.FunctionDefinitionAst] -and $Node.Name -eq 'New-PrivateAcl' }, $true)
Invoke-Expression $taskAclFunction.Extent.Text
$taskSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$taskUserAcl = New-PrivateAcl $taskSid
$taskRootAcl = New-PrivateAcl '' -Root
foreach ($taskCase in @(@('root', $taskRootAcl), @('user', $taskUserAcl))) {
    if (-not $taskCase[1].AreAccessRulesProtected) { throw "ACL inheritance remains open: $($taskCase[0])" }
    $taskRules = $taskCase[1].GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])
    $taskUsersRules = @($taskRules | Where-Object { $_.IdentityReference.Value -eq 'S-1-5-32-545' })
    if ($taskCase[0] -eq 'user' -and $taskUsersRules.Count -ne 0) { throw 'Other Windows users can access a private frame channel.' }
    if ($taskCase[0] -eq 'root' -and ($taskUsersRules.Count -ne 1 -or ([int]$taskUsersRules[0].FileSystemRights -band (-bnot [int][Security.AccessControl.FileSystemRights]::Synchronize)) -ne [int][Security.AccessControl.FileSystemRights]::Traverse -or $taskUsersRules[0].InheritanceFlags -ne 'None')) { throw 'Root Users permission exceeds non-inherited traversal.' }
    $taskService = @($taskRules | Where-Object { $_.IdentityReference.Value -eq 'S-1-5-19' })
    if ($taskService.Count -ne 1 -or ($taskService[0].FileSystemRights -band [Security.AccessControl.FileSystemRights]::Write) -ne 0 -or ($taskService[0].FileSystemRights -band [Security.AccessControl.FileSystemRights]::Read) -ne [Security.AccessControl.FileSystemRights]::Read) { throw 'FrameServer must have read access without write permission.' }
}
$taskOwnerRule = @(($taskUserAcl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) | Where-Object { $_.IdentityReference.Value -eq $taskSid })
if ($taskOwnerRule.Count -ne 1 -or ($taskOwnerRule[0].FileSystemRights -band [Security.AccessControl.FileSystemRights]::Modify) -ne [Security.AccessControl.FileSystemRights]::Modify) { throw 'Camera user cannot update its frame channel.' }
Write-Output 'Private frame ACL contract passed.'

$taskManifest = Get-Content -LiteralPath (Join-Path $Bundle 'bundle-sha256.json') -Raw | ConvertFrom-Json
foreach ($taskFile in $taskManifest.files.PSObject.Properties) {
    if ($taskFile.Name -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Bundle manifest contains a path instead of a filename.' }
    if ((Get-FileHash -LiteralPath (Join-Path $Bundle $taskFile.Name) -Algorithm SHA256).Hash -ne $taskFile.Value) { throw "Bundle checksum mismatch: $($taskFile.Name)" }
}
$taskAlias = Join-Path (Split-Path -Parent $PSScriptRoot) 'pc_avatar_webcam\dist\pc_avatar_webcam.exe'
if ((Get-FileHash -LiteralPath $taskAlias -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath (Join-Path $Bundle 'AlterEgoWebcam.exe') -Algorithm SHA256).Hash) { throw 'Compatibility EXE differs from the supported receiver.' }
Write-Output 'Offline bundle checksums and byte-identical compatibility receiver passed.'
& (Join-Path $Bundle 'InstallWebcam.ps1') -UserSid $taskSid -ValidateOnly
