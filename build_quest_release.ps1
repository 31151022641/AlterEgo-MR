param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
$signingDirectory = Join-Path $projectDirectory 'signing'
$keystorePath = Join-Path $signingDirectory 'alterego-release.p12'
$passwordPath = Join-Path $signingDirectory 'password.dpapi'
$javaBinDirectory = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot\bin'

if (-not (Test-Path -LiteralPath $signingDirectory)) {
    New-Item -ItemType Directory -Path $signingDirectory | Out-Null
}
if ((Test-Path -LiteralPath $keystorePath) -and -not (Test-Path -LiteralPath $passwordPath)) {
    throw 'The existing release key has no saved password. Restore its original password; do not replace the signing key.'
}
if ((Test-Path -LiteralPath $passwordPath) -and -not (Test-Path -LiteralPath $keystorePath)) {
    throw 'The saved signing password exists but its key is missing. Restore the original key; do not generate a replacement.'
}
if (-not (Test-Path -LiteralPath $passwordPath)) {
    $passwordBytes = New-Object byte[] 32
    $randomSource = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $randomSource.GetBytes($passwordBytes) } finally { $randomSource.Dispose() }
    $newPassword = [Convert]::ToBase64String($passwordBytes)
    $securePassword = ConvertTo-SecureString $newPassword -AsPlainText -Force
    $securePassword | ConvertFrom-SecureString | Set-Content -LiteralPath $passwordPath
    $newPassword = $null
    [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
} else {
    $securePassword = Get-Content -LiteralPath $passwordPath | ConvertTo-SecureString
}

$priorSignPassword = $env:ALTEREGO_SIGN_PASSWORD
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $env:ALTEREGO_SIGN_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    if (-not (Test-Path -LiteralPath $keystorePath)) {
        & (Join-Path $javaBinDirectory 'keytool.exe') -genkeypair -keystore $keystorePath -storetype PKCS12 `
            -storepass:env ALTEREGO_SIGN_PASSWORD -keypass:env ALTEREGO_SIGN_PASSWORD `
            -alias alterego -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 9125 `
            -dname 'CN=AlterEgo MR, OU=Development, O=MistersLee' -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Release signing key generation failed.' }
    }
    Push-Location -LiteralPath $projectDirectory
    try {
        $gradleArguments = @(':app:assembleRelease', ':app:testReleaseUnitTest', '--console=plain')
        if ($Offline) { $gradleArguments += '--offline' }
        & .\gradlew.bat @gradleArguments
        if ($LASTEXITCODE -ne 0) { throw 'Quest release build failed.' }
        & .\scripts\verify-portrait-apk.ps1 -Apk 'app/build/outputs/apk/release/app-release.apk'
    } finally { Pop-Location }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    $env:ALTEREGO_SIGN_PASSWORD = $priorSignPassword
    $securePassword.Dispose()
}
Write-Output 'Signed Quest test build: app/build/outputs/apk/release/app-release.apk'
Write-Output 'Keep the signing folder and encrypted password safe; every future update must use this key.'
